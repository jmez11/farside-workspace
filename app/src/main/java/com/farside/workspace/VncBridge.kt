package com.farside.workspace

import android.view.Surface
import android.os.ParcelFileDescriptor
import android.util.Log
import com.farside.workspace.data.VncAuthMode
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.ProxyType
import com.farside.workspace.ssh.openHttpConnectSocket
import com.farside.workspace.ssh.openSocks5Socket
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.DHParameterSpec
import javax.crypto.spec.DHPublicKeySpec
import javax.crypto.spec.SecretKeySpec

internal class VncAuthenticationException : Exception()
internal class MacSessionRejectedException(message:String):java.io.IOException(message)

/** Per-session LibVNCClient owner. The monitor serializes native handles with teardown. */
class VncBridge : DesktopSessionBridge {
    @Volatile internal var isConfirmedMac=false; private set
    private var nativePasswordInput:MacSavedPasswordInput?=null
    private var nativePasswordGate:(()->Unit)?=null
    @Volatile override var clipboardListener: ((String) -> Unit)? = null
    @Volatile internal var disconnectListener: ((Int, Boolean) -> Unit)? = null
    @Volatile private var active = 0L
    @Volatile private var macHevcSession: MacHevcSession? = null
    private val stateLock = Any()
    private val callbacks = android.os.Handler(android.os.Looper.getMainLooper())
    private val heldPrintable = mutableMapOf<Int, Int>()
    private var boundAudioSession=0
    @Volatile internal var audioSpatialBound=false; private set

    @Synchronized internal fun bindAudioWindow(activity:android.app.Activity) {
        val session=getAudioPlaybackSessionId(active)
        if(session!=boundAudioSession) {
            QuestSpatialAudio.forget(boundAudioSession)
            boundAudioSession=session;audioSpatialBound=false
        }
        if(session>0) QuestSpatialAudio.windowSpace(activity)?.let { space ->
            audioSpatialBound=QuestSpatialAudio.bind(session,space)
        }
    }
    @Synchronized private fun clearAudioBinding() {
        QuestSpatialAudio.forget(boundAudioSession);boundAudioSession=0;audioSpatialBound=false
    }

    fun connect(host: String, port: Int, password: String, surface: Surface,
        username: String = "", authMode: VncAuthMode = VncAuthMode.VNC_PASSWORD,
        httpProxy: ConnectionProfile? = null, macSessionUsername: String = username,
        macDatagramSender: ((Int, ByteArray) -> Unit)? = null,
        macAuthenticationGate: (() -> Unit)? = null): Long {
        synchronized(stateLock) {
            check(active == 0L)
            isConfirmedMac=false
        }
        require(port in 1..65535)
        if (httpProxy?.expMacHevc == true) {
            val session = MacHevcSession(host.removeSurrounding("[", "]"), port, username, password,
                shareConsole = httpProxy.expMacShareConsole, sessionUsername = macSessionUsername,
                mediaDatagramSender = macDatagramSender,
                preferAvc = httpProxy.macPreferAvc, audioEnabled = httpProxy.macAudioEnabled,
                authenticationGate = macAuthenticationGate,onAuthenticated={ isConfirmedMac=true },
                controlSocketFactory=if(httpProxy.proxyType in listOf(ProxyType.HTTP,ProxyType.SOCKS5)) ({openSocket(host,port,httpProxy)}) else null) { line ->
                Log.i("MacHevcBridge", line)
            }
            session.setSurface(surface)
            session.onResolutionChanged = { w, h ->
                Log.i("MacHevcBridge", "Resolution changed to ${w}x${h}")
            }
            try {
                session.start()
                session.initStreamingLayer()
                session.startStreaming(surface) { error ->
                    callbacks.post { if(macHevcSession===session && active!=0L) disconnectListener?.invoke(R.string.vnc_server_disconnected, true) }
                }
            } catch(e: Exception) { runCatching { session.close() }; throw e }
            synchronized(stateLock) {
                macHevcSession = session
                active = 0xCAFE0001L
            }
            return 0xCAFE0001L
        }
        val instanceId: Long
        if (authMode == VncAuthMode.VNC_PASSWORD) {
            if (!(password.isNotEmpty() && password.toByteArray(Charsets.ISO_8859_1).size <= 8 && password.all { it.code in 1..255 }))
                throw LocalizableException(R.string.vnc_password_latin1)
            val fd = if (httpProxy?.proxyType in listOf(ProxyType.HTTP, ProxyType.SOCKS5)) detachSocket(openSocket(host, port, httpProxy)) else -1
            instanceId = nativeConnect(host.removeSurrounding("[", "]"), port, password.toByteArray(Charsets.ISO_8859_1), surface, fd)
        } else {
            require(username.isNotBlank() && password.isNotEmpty())
            macAuthenticationGate?.invoke()
            val fd = authenticateApple(host.removeSurrounding("[", "]"), port, username, password, httpProxy)
            isConfirmedMac=true
            nativePasswordInput=MacSavedPasswordInput(password)
            nativePasswordGate=macAuthenticationGate
            instanceId = nativeConnectAuthenticated(host.removeSurrounding("[", "]"), port, fd, surface)
        }
        synchronized(stateLock) {
            active = instanceId
        }
        return instanceId
    }
    private fun openSocket(host: String, port: Int, httpProxy: ConnectionProfile?): Socket {
        if (httpProxy == null || httpProxy.proxyType !in listOf(ProxyType.HTTP, ProxyType.SOCKS5))
            return Socket().apply { connect(InetSocketAddress(host, port), 5000) }
        val proxyHost = httpProxy.proxyHost?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw LocalizableException(if (httpProxy.proxyType == ProxyType.SOCKS5) R.string.socks5_proxy_host_port_required else R.string.http_proxy_host_port_required)
        val proxyPort = httpProxy.proxyPort?.takeIf { it in 1..65535 }
            ?: throw LocalizableException(if (httpProxy.proxyType == ProxyType.SOCKS5) R.string.socks5_proxy_host_port_required else R.string.http_proxy_host_port_required)
        if (httpProxy.proxyType == ProxyType.SOCKS5) return openSocks5Socket(host.removeSurrounding("[", "]"), port,
            proxyHost, proxyPort, httpProxy.proxyUser, httpProxy.proxyPass)
        return openHttpConnectSocket(host.removeSurrounding("[", "]"), port, proxyHost, proxyPort,
            httpProxy.proxyUser, httpProxy.proxyPass)
    }
    private fun detachSocket(socket: Socket): Int = socket.use {
        ParcelFileDescriptor.fromSocket(it).detachFd()
    }
    private fun authenticateApple(host: String, port: Int, username: String, password: String,
                                  httpProxy: ConnectionProfile?): Int {
        val socket = openSocket(host, port, httpProxy)
        try {
            socket.soTimeout = 5000
            val input = java.io.DataInputStream(socket.getInputStream())
            val output = java.io.DataOutputStream(socket.getOutputStream())
            val version = ByteArray(12).also { input.readFully(it) }.toString(Charsets.US_ASCII)
            require(version.startsWith("RFB 003."))
            output.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII)); output.flush()
            val count = input.readUnsignedByte()
            if (count == 0) throw VncAuthenticationException()
            val offered = ByteArray(count).also { input.readFully(it) }
            Log.i("VncSession", "Mac security types: ${offered.joinToString { (it.toInt() and 255).toString() }}")
            if (offered.none { (it.toInt() and 255) == 30 }) throw VncAuthenticationException()
            output.writeByte(30); output.flush()
            val generator = input.readUnsignedShort()
            val keyLength = input.readUnsignedShort()
            require(generator > 1 && keyLength in 128..512)
            val primeBytes = ByteArray(keyLength).also { input.readFully(it) }
            val serverBytes = ByteArray(keyLength).also { input.readFully(it) }
            val prime = BigInteger(1, primeBytes)
            val serverPublic = BigInteger(1, serverBytes)
            require(prime.bitLength() >= 1024 && serverPublic > BigInteger.ONE && serverPublic < prime.subtract(BigInteger.ONE))
            val spec = DHParameterSpec(prime, BigInteger.valueOf(generator.toLong()))
            val pair = KeyPairGenerator.getInstance("DH").apply { initialize(spec) }.generateKeyPair()
            val peer = KeyFactory.getInstance("DH").generatePublic(DHPublicKeySpec(serverPublic, spec.p, spec.g))
            val agreement = KeyAgreement.getInstance("DH").apply { init(pair.private); doPhase(peer, true) }
            val shared = agreement.generateSecret().let { if (it.size == keyLength) it else ByteArray(keyLength).also { dest ->
                require(it.size <= keyLength); it.copyInto(dest, keyLength - it.size)
            } }
            val key = MessageDigest.getInstance("MD5").digest(shared)
            val plain = ByteArray(128).also { SecureRandom().nextBytes(it) }
            fun putCredential(value: String, offset: Int) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                var length = bytes.size.coerceAtMost(63)
                if (length < bytes.size) while (length > 0 && (bytes[length].toInt() and 0xc0) == 0x80) length--
                bytes.copyInto(plain, offset, 0, length)
                plain[offset + length] = 0
            }
            putCredential(username, 0); putCredential(password, 64)
            val encrypted = Cipher.getInstance("AES/ECB/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            }.doFinal(plain)
            plain.fill(0); shared.fill(0); key.fill(0)
            output.write(encrypted)
            val publicBytes = (pair.public as javax.crypto.interfaces.DHPublicKey).y.toByteArray()
                .let { if (it.size > keyLength) it.copyOfRange(it.size - keyLength, it.size) else it }
            output.write(ByteArray(keyLength - publicBytes.size)); output.write(publicBytes); output.flush()
            val result = input.readInt()
            if (result != 0) throw VncAuthenticationException()
            return detachSocket(socket)
        } catch (error: Exception) {
            socket.close()
            throw error
        }
    }
    fun disconnect(instance: Long) {
        val sessionToClose: MacHevcSession?
        val nativeInstanceToClose: Long
        synchronized(stateLock) {
            if (instance == 0L || instance != active) return
            sessionToClose = macHevcSession
            macHevcSession = null
            nativeInstanceToClose = if (sessionToClose == null) active else 0L
            active = 0L
            nativePasswordInput=null
            nativePasswordGate=null
            isConfirmedMac=false
        }
        synchronized(heldPrintable) {
            heldPrintable.clear()
        }
        clearAudioBinding()
        if (sessionToClose != null) {
            sessionToClose.close()
        } else if (nativeInstanceToClose != 0L) {
            nativeDisconnect(nativeInstanceToClose)
        }
    }
    fun setSurface(instance: Long, surface: Surface?) {
        val mac = macHevcSession
        if (instance != 0L && instance == active) {
            if (mac != null) mac.setSurface(surface)
            else nativeSetSurface(instance, surface)
        }
    }
    internal val macCursor:MacCursor? get()=macHevcSession?.cursor
    internal val macInputCounts:Pair<Long,Long> get()=(macHevcSession?.pointerEventsSent?.get() ?: 0L) to (macHevcSession?.keyEventsSent?.get() ?: 0L)
    fun sendPointerEvent(instance: Long, x: Int, y: Int, buttonMask: Int) {
        val mac = macHevcSession
        if (instance != 0L && instance == active) {
            if (mac != null) mac.sendPointer(x, y, buttonMask)
            else nativePointer(instance, x, y, buttonMask)
        }
    }
    override fun sendMouseEvent(instance: Long, x: Int, y: Int, flags: Int) {
        val mac = macHevcSession
        if (instance != 0L && instance == active) {
            if (mac != null) { MacInputTrace.event("pointer-forwarded");mac.sendMouseEvent(x, y, flags) }
            else nativeMouse(instance, x, y, flags)
        }
    }
    /** XT scancodes from the shared strip map to X11 keysyms, including Command/Super. */
    override fun sendKeyEvent(instance: Long, scancode: Int, isDown: Boolean) {
        if (instance == 0L || instance != active) return
        val keysym = xtKeysym(scancode) ?: return
        val mac = macHevcSession
        if (mac != null) { MacInputTrace.event("key-forwarded");mac.sendKeyEvent(keysym, isDown) }
        else nativeKey(instance, keysym, isDown)
    }
    /** Unicode is one X11 keysym, including supplementary code points. */
    override fun sendUnicodeKeyEvent(instance: Long, unicodeChar: Int, isDown: Boolean) {
        if (instance == 0L || instance != active || !Character.isValidCodePoint(unicodeChar)) return
        val keysym = when (unicodeChar) { 8 -> 0xff08; 9 -> 0xff09; 10, 13 -> 0xff0d; 27 -> 0xff1b
            else -> if (unicodeChar <= 255) unicodeChar else 0x01000000 or unicodeChar }
        val mac = macHevcSession
        if (mac != null) { MacInputTrace.event("key-forwarded");mac.sendKeyEvent(keysym, isDown) }
        else nativeKey(instance, keysym, isDown)
    }
    // Key release retains its down keysym if Shift/Ctrl changed in between.
    internal fun sendPhysicalKey(instance: Long, event: android.view.KeyEvent, down: Boolean) {
        if (instance == 0L || instance != active) return
        val key = event.keyCode
        val unicode = event.unicodeChar
        val symbol = synchronized(heldPrintable) { if (!down) heldPrintable.remove(key) else null }
        val chosen = symbol ?: if (unicode != 0 && !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed) {
            if (unicode <= 255) unicode else 0x01000000 or unicode
        } else androidKeysym(key) ?: return
        if (down) synchronized(heldPrintable) { heldPrintable[key] = chosen }
        val mac = macHevcSession
        if (mac != null) mac.sendKeyEvent(chosen, down)
        else nativeKey(instance, chosen, down)
    }
    override fun sendClipboardText(instance: Long, text: String): Boolean {
        if (instance == 0L || instance != active) return false
        val mac = macHevcSession
        return if (mac != null) true
        else nativeClipboard(instance, text.toByteArray(Charsets.UTF_8), text.toByteArray(Charsets.ISO_8859_1))
    }
    override fun getDesktopWidth(instance: Long): Int {
        val mac = macHevcSession
        return if (instance != 0L && instance == active) {
            if (mac != null) mac.width else nativeSize(instance, true)
        } else 0
    }
    override fun getDesktopHeight(instance: Long): Int {
        val mac = macHevcSession
        return if (instance != 0L && instance == active) {
            if (mac != null) mac.height else nativeSize(instance, false)
        } else 0
    }
    override fun updateDisplay(instance: Long, width: Int, height: Int, zoom: Float) {
        if (instance != 0L && instance == active && macHevcSession != null) {
            macHevcSession?.updateDisplay(width, height)
        }
    }
    val isMacHevc: Boolean get() = macHevcSession != null
    val macHevcStage: String? get() = macHevcSession?.stage
    internal val macMediaPipeline: MacHevcVideoPipeline? get() = macHevcSession?.videoPipeline
    internal fun canSubmitSavedMacPassword(instance:Long):Boolean =
        instance!=0L && instance==active && isConfirmedMac &&
            (macHevcSession?.canSubmitSavedPassword ?: (nativePasswordInput?.attempted==false))
    internal fun submitSavedMacPassword(instance:Long):Boolean {
        if(!canSubmitSavedMacPassword(instance)) return false
        macHevcSession?.let { return it.submitSavedPassword() }
        val input=nativePasswordInput ?: return false
        val gate=nativePasswordGate
        return input.submit(active={ instance==active && isConfirmedMac && nativePasswordInput===input },
            enqueue={ Thread(it,"MacVncUnlock").apply { isDaemon=true;start() } }, key={ symbol,down ->
                synchronized(stateLock) { if(instance==active) nativeKey(instance,symbol,down) }
            },beforeInput={ gate?.invoke() })
    }
    internal fun awaitMacVideo(timeoutMs:Long=3500,cancelled:()->Boolean={false}):Boolean {
        return MacVideoStartup.await(
            frame={ (macMediaPipeline?.framesDecoded?.get() ?: 0)>0 },
            progress={ (macMediaPipeline?.authenticatedPackets?.get() ?: 0)>0 },
            initialMs=timeoutMs,progressMs=maxOf(timeoutMs,10000),cancelled=cancelled)
    }
    override fun getAudioPlaybackSessionId(instance:Long):Int =
        if(instance!=0L && instance==active) macHevcSession?.videoPipeline?.audio?.playbackSessionId ?: 0 else 0
    override fun setAudioMuted(instance: Long, speakerMuted: Boolean, micMuted: Boolean) {
        macHevcSession?.videoPipeline?.audio?.muted=speakerMuted
    }

    override fun getDecoderDescription(instance: Long): String {
        val mac = macHevcSession
        return when {
            mac != null -> mac.getDecoderDescription()
            instance != 0L && instance == active -> "CPU Software (LibVNCClient / ZRLE / Raw)"
            else -> "Disconnected"
        }
    }
    @androidx.annotation.Keep fun onNativeDisconnected() { callbacks.post { disconnectListener?.invoke(R.string.vnc_server_disconnected, true) } }
    @androidx.annotation.Keep fun onNativeClipboardChanged(bytes: ByteArray, utf8: Boolean) {
        val text = bytes.toString(if (utf8) Charsets.UTF_8 else Charsets.ISO_8859_1)
        callbacks.post { clipboardListener?.invoke(text) }
    }
    private external fun nativeConnect(host: String, port: Int, password: ByteArray, surface: Surface, fd: Int): Long
    private external fun nativeConnectAuthenticated(host: String, port: Int, fd: Int, surface: Surface): Long
    private external fun nativeDisconnect(instance: Long)
    private external fun nativeSetSurface(instance: Long, surface: Surface?)
    private external fun nativePointer(instance: Long, x: Int, y: Int, mask: Int)
    private external fun nativeMouse(instance: Long, x: Int, y: Int, flags: Int)
    private external fun nativeKey(instance: Long, keysym: Int, down: Boolean)
    private external fun nativeClipboard(instance: Long, utf8: ByteArray, latin1: ByteArray): Boolean
    private external fun nativeSize(instance: Long, width: Boolean): Int
    companion object {
        init { System.loadLibrary("vnc-jni") }
        internal fun xtKeysym(code: Int): Int? = when (code) {
            0x01 -> 0xff1b; 0x0e -> 0xff08; 0x0f -> 0xff09; 0x1c, 0x11c -> 0xff0d
            0x1d -> 0xffe3; 0x11d -> 0xffe4; 0x2a -> 0xffe1; 0x36 -> 0xffe2
            0x38 -> 0xffe9; 0x138 -> 0xffea; 0x15b -> 0xffeb; 0x15c -> 0xffec
            0x3a -> 0xffe5; 0x45 -> 0xff7f; 0x46 -> 0xff14; 0x137 -> 0xff61; 0x145 -> 0xff13
            0x147 -> 0xff50; 0x148 -> 0xff52; 0x149 -> 0xff55; 0x14b -> 0xff51
            0x14d -> 0xff53; 0x14f -> 0xff57; 0x150 -> 0xff54; 0x151 -> 0xff56
            0x152 -> 0xff63; 0x153 -> 0xffff; 0x39 -> 32; 0x0c -> 45; 0x0d -> 61
            0x33 -> 44; 0x34 -> 46; 0x35, 0x135 -> 47; 0x37 -> 42; 0x4a -> 45; 0x4e -> 43
            in 0x02..0x0b -> "1234567890"[code - 2].code
            in 0x3b..0x44 -> 0xffbe + code - 0x3b
            0x57 -> 0xffc8; 0x58 -> 0xffc9
            else -> letterScancodes[code]?.code
        }
        internal fun androidKeysym(code: Int): Int? = when (code) {
            in 29..54 -> 'a'.code + code - 29
            in 7..16 -> '0'.code + code - 7
            in 131..142 -> 0xffbe + code - 131
            114 -> 0xffe4; 58 -> 0xffea; 60 -> 0xffe2; 117 -> 0xffeb; 118 -> 0xffec
            115 -> 0xffe5; 116 -> 0xff14; 143 -> 0xff7f; 120 -> 0xff61; 121 -> 0xff13
            122 -> 0xff50; 123 -> 0xff57; 92 -> 0xff55; 93 -> 0xff56; 124 -> 0xff63; 112 -> 0xffff
            68 -> 96; 69 -> 45; 70 -> 61; 71 -> 91; 72 -> 93; 73 -> 92; 74 -> 59; 75 -> 39
            76 -> 47; 55 -> 44; 56 -> 46
            in 144..153 -> 0xffb0 + code - 144
            154 -> 0xffaf; 155 -> 0xffaa; 156 -> 0xffad; 157 -> 0xffab; 158 -> 0xffae; 159 -> 0xffac; 160 -> 0xff8d
            else -> xtKeysym(mapAndroidKeyCodeToXt(code))
        }
        private val letterScancodes = listOf(0x10 to "qwertyuiop", 0x1e to "asdfghjkl", 0x2c to "zxcvbnm")
            .flatMap { (base, letters) -> letters.mapIndexed { i, c -> base + i to c } }.toMap() +
            mapOf(0x1a to '[', 0x1b to ']', 0x27 to ';', 0x28 to '\'', 0x29 to '`', 0x2b to '\\')
    }
}
