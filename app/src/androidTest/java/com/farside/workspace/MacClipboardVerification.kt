package com.farside.workspace

import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.os.SystemClock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.Inflater

/** Owned encrypted peer; authentication/media are deliberately bypassed, never a live Mac claim. */
internal class MacClipboardVerification(private val test: Instrumentation) {
    fun live(profileId: String): String {
        val context = test.targetContext
        val profiles = com.farside.workspace.data.ProfileManager(context)
        val profile = profiles.getProfiles().single {
            it.type == com.farside.workspace.data.ConnectionType.VNC && (it.id == profileId || it.name == profileId)
        }
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val originalAndroid = clipboard.primaryClip
        try {
            MacMediaSurface(test, profile.copy(reconnectAutomatically = false)).use { display ->
                check(display.awaitConnection()) { "Saved Mac connection failed" }
                val bridge = checkNotNull(display.bridge)
                val session = VncBridge::class.java.getDeclaredField("macHevcSession").apply { isAccessible = true }
                    .get(bridge) as? MacHevcSession ?: error("Ordinary VNC fallback cannot test Apple pasteboard")
                val incoming = java.util.concurrent.LinkedBlockingQueue<String>()
                val productionCallback = session.onClipboardText
                session.onClipboardText = { text -> incoming.offer(text); productionCallback?.invoke(text) }
                var originalRemote: MacClipboard.Payload? = null
                fun fetchUntil(expected: String): Boolean {
                    val end = SystemClock.uptimeMillis() + 10000
                    while (SystemClock.uptimeMillis() < end) {
                        check(session.requestClipboard())
                        val text = incoming.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                        if (text == expected) return true
                    }
                    return false
                }
                try {
                    val before = session.clipboardPayload
                    check(session.requestClipboard())
                    await("Mac did not return a pasteboard archive; preserve clipboard and stop") {
                        session.clipboardPayload != null && session.clipboardPayload !== before
                    }
                    val captured = checkNotNull(session.clipboardPayload)
                    check(captured.archive != null) { "Mac returned an unsupported/invalid archive; preserve clipboard and stop" }
                    originalRemote = captured
                    val token = "Farside clipboard ${java.util.UUID.randomUUID()} café 日本語 😀\nsecond line"
                    incoming.clear()
                    test.runOnMainSync { clipboard.setPrimaryClip(ClipData.newPlainText("fixture", token)) }
                    check(fetchUntil(token)) { "Android clipboard did not reach the live Mac pasteboard" }
                    // Empty Android text is not forwarded. The distinct direct protocol
                    // write/readback then exercises remote callback → Android delivery.
                    val fromMac = "Mac readback ${java.util.UUID.randomUUID()} — Ελληνικά 😀\nreturn line"
                    test.runOnMainSync { clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "")) }
                    incoming.clear()
                    check(bridge.sendClipboardText(display.handle, fromMac))
                    check(fetchUntil(fromMac)) { "Live Mac did not return the Unicode archive" }
                    await("Live Mac readback did not update Android clipboard") {
                        clipboard.primaryClip?.getItemAt(0)?.text?.toString() == fromMac
                    }
                    display.capture("mac-clipboard-live.png")
                } finally {
                    try {
                        originalRemote?.let { original ->
                            incoming.clear()
                            check(session.sendClipboardPayload(original)) { "Remote clipboard restore rejected" }
                            val before = session.clipboardPayload
                            check(session.requestClipboard())
                            await("Remote pasteboard archive restore unverified") {
                                val restored = session.clipboardPayload
                                restored != null && restored !== before && restored.archive?.contentEquals(original.archive) == true
                            }
                        }
                    } finally {
                        session.onClipboardText = productionCallback
                        // Close synchronously before restoring Android's clip; the
                        // fixture Activity's asynchronous disposal must not forward it.
                        bridge.disconnect(display.handle)
                    }
                }
            }
            return "PASS saved Mac production streaming connection: Android clipboard → live pasteboard, explicit Mac fetch/readback → Android, repeated UTF-8/CJK/Greek/emoji/multiline payloads, original remote pasteboard archive restored; Android clip restored after disposal. No SSH commands or Mac settings changed. Physical copy/paste remains manual."
        } finally {
            test.runOnMainSync { if (originalAndroid != null) clipboard.setPrimaryClip(originalAndroid) else clipboard.clearPrimaryClip() }
        }
    }

    private fun await(reason: String, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10000
        while (!check()) { check(SystemClock.uptimeMillis() < end) { reason }; Thread.sleep(30) }
    }

    private fun set(owner: Any, field: String, value: Any?) {
        owner.javaClass.getDeclaredField(field).apply { isAccessible = true }.set(owner, value)
    }

    private fun verifyDisabled() {
        val key=ByteArray(16);val iv=ByteArray(16)
        val peer=MacHevcRecordLayer(key,iv)
        val golden=test.context.assets.open("mac-clipboard-reference.bin").use {it.readBytes()}
        val sink=java.io.ByteArrayOutputStream()
        val session=MacHevcSession("127.0.0.1",1,"fixture","fixture",clipboardEnabled=false)
        try {
            set(session,"recordLayer",MacHevcRecordLayer(key,iv))
            set(session,"rawInput",DataInputStream(peer.encryptRecord(golden).inputStream()))
            set(session,"rawOutput",DataOutputStream(sink))
            var delivered=false
            session.onClipboardText={delivered=true}
            session.initStreamingLayer()
            check(!session.sendClipboardText("disabled") && !session.requestClipboard())
            session.readNextFramebufferUpdate()
            check(!delivered) {"Disabled clipboard delivered remote text"}
            Thread.sleep(100)
            val wire=DataInputStream(peer.createInputStream(DataInputStream(sink.toByteArray().inputStream())))
            check(wire.readUnsignedByte()==3);wire.skipBytes(9)
            check(wire.read()==-1) {"Disabled clipboard sent monitoring/fetch messages"}
        } finally {session.close()}
    }

    fun run(): String {
        verifyDisabled()
        val clipboard = test.targetContext.getSystemService(ClipboardManager::class.java)
        val original = clipboard.primaryClip
        val listener = CopyOnWriteArrayList<String>()
        val disconnected = java.util.concurrent.CountDownLatch(1)
        val session = MacHevcSession("127.0.0.1", 1, "fixture", "fixture")
        val bridge = VncBridge()
        var sync: DesktopClipboardSync? = null
        val endpoint = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val client = Socket("127.0.0.1", endpoint.localPort)
        val peer = endpoint.accept().apply { soTimeout = 10000 }
        endpoint.close()
        val key = ByteArray(16) { (it+1).toByte() }
        val iv = ByteArray(16) { (it+20).toByte() }
        val serverLayer = MacHevcRecordLayer(key, iv)
        val input = DataInputStream(serverLayer.createInputStream(DataInputStream(peer.getInputStream())))
        val output = DataOutputStream(peer.getOutputStream())
        try {
            set(session, "socket", client)
            set(session, "rawInput", DataInputStream(client.getInputStream()))
            set(session, "rawOutput", DataOutputStream(client.getOutputStream()))
            set(session, "recordLayer", MacHevcRecordLayer(key, iv))
            set(bridge, "macHevcSession", session); set(bridge, "active", 0xCAFE0001L)
            // Same main-thread delivery boundary as the production bridge callback.
            session.onClipboardText = { text ->
                listener.add(text)
                android.os.Handler(android.os.Looper.getMainLooper()).post { bridge.clipboardListener?.invoke(text) }
            }
            session.initStreamingLayer()
            val enableAndFetch = ByteArray(16).also(input::readFully)
            check(enableAndFetch.contentEquals(byteArrayOf(21,0,0,1,0,0,0,0,11,0,0,0,0,0,0,0)))
            check(input.readUnsignedByte() == 3); input.skipBytes(9) // Initial framebuffer request.
            session.startStreaming(null) { disconnected.countDown() }
            test.runOnMainSync {
                clipboard.setPrimaryClip(ClipData.newPlainText("fixture", ""))
                sync = DesktopClipboardSync(test.targetContext, bridge, 0xCAFE0001L, "fixture")
            }
            // Golden vector generated by iShareScreen's independent Python encoder.
            val remote = "Mac clipboard café 日本語 😀\nsecond line"
            val golden = test.context.assets.open("mac-clipboard-reference.bin").use { it.readBytes() }
            // Notification must trigger exactly eight fetch bytes, with no stray byte.
            serverLayer.writeRecord(output, byteArrayOf(20,0,0,4,0,1,0,2))
            check(ByteArray(8).also(input::readFully).contentEquals(byteArrayOf(11,0,0,0,0,0,0,0)))
            // Split header and payload over encrypted records to exercise real stream reassembly.
            golden.asList().chunked(7).forEach { serverLayer.writeRecord(output, it.toByteArray()) }
            await("Android clipboard did not receive the independent Unicode vector") {
                clipboard.primaryClip?.getItemAt(0)?.text?.toString() == remote
            }
            check(listener.single() == remote)
            // Incompressible ASCII exceeds a record. Independently inflate/inspect the outbound archive.
            val random = java.util.Random(321)
            val local = buildString { repeat(190000) { append((33 + random.nextInt(90)).toChar()) }; append(" 日本語 😀") }
            test.runOnMainSync { clipboard.setPrimaryClip(ClipData.newPlainText("fixture", local)) }
            check(input.readUnsignedByte() == 31) { "Unexpected message (possibly a clipboard echo)" }
            input.skipBytes(7)
            val plainSize = input.readInt(); val compressedSize = input.readInt()
            check(compressedSize > MacClipboard.RECORD_CHUNK_BYTES)
            val compressed = ByteArray(compressedSize).also(input::readFully)
            val inflater = Inflater()
            val plain = ByteArray(plainSize)
            try {
                inflater.setInput(compressed)
                var size = 0
                while (size < plain.size) { val n = inflater.inflate(plain, size, plain.size-size); check(n>0); size += n }
            } finally { inflater.end() }
            DataInputStream(plain.inputStream()).use { archive ->
                check(archive.readInt() == 1)
                check(ByteArray(archive.readInt()).also(archive::readFully).toString(Charsets.UTF_8) == "public.utf8-plain-text")
                check(archive.readInt() == 0 && archive.readInt() == 0)
                check(ByteArray(archive.readInt()).also(archive::readFully).toString(Charsets.UTF_8) == local)
                check(archive.available() == 0)
            }
            // Rejected bounded archive is consumed without corrupting the next notification.
            val malformed = golden.clone().apply { this[11] = (this[11]+1).toByte() }
            serverLayer.writeRecord(output, malformed)
            serverLayer.writeRecord(output, byteArrayOf(20,0,0,4,0,1,0,2))
            check(ByteArray(8).also(input::readFully).contentEquals(byteArrayOf(11,0,0,0,0,0,0,0)))
            Thread.sleep(200)
            check(listener.size == 1)
            check(!bridge.sendClipboardText(0xCAFE0001L, "x".repeat(MacClipboard.MAX_TEXT_BYTES+1)))
            test.runOnMainSync { sync?.close(); sync = null }
            bridge.disconnect(0xCAFE0001L)
            check(disconnected.await(5, java.util.concurrent.TimeUnit.SECONDS))
            check(!bridge.sendClipboardText(0xCAFE0001L, "closed"))
            return "PASS encrypted Apple notification/fetch, independent UTF-8 vector across fragmented records, Android clipboard binding, no inbound echo, >190KB outbound archive across records, malformed archive recovery, size rejection and teardown. Owned fixture; no Mac authentication or Horizon focus claim."
        } finally {
            test.runOnMainSync { sync?.close(); if (original != null) clipboard.setPrimaryClip(original) else clipboard.clearPrimaryClip() }
            bridge.disconnect(0xCAFE0001L); session.close(); peer.close(); client.close()
        }
    }
}
