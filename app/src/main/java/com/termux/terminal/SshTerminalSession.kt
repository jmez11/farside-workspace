package com.termux.terminal

import android.content.Context
import android.os.Handler
import android.os.Looper
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.ChallengeResponseProvider
import net.schmizz.sshj.userauth.password.Resource
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.ProfileManager
import com.farside.workspace.data.ProxyType
import com.farside.workspace.tunnel.CloudflaredTunnel
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.OutputStream
import java.security.Security
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SshTerminalSession(
    val sshSessionInfo: ConnectionProfile,
    client: TerminalSessionClient,
    val context: Context? = null
) : TerminalSession("dummy", "/", emptyArray(), emptyArray(), sshSessionInfo.terminalScrollback, client) {
    val forwarding = com.farside.workspace.SshForwarding { statuses ->
        Handler(Looper.getMainLooper()).post { onForwardStatus?.invoke(statuses) }
    }
    var onForwardStatus: ((Map<String, String>) -> Unit)? = null

    companion object {
        init {
            ensureBouncyCastleProvider()
        }

        fun ensureBouncyCastleProvider() {
            try {
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            } catch (e: Exception) {
                android.util.Log.w("SshTerminalSession", "Failed to register BouncyCastleProvider: ${e.message}")
            }
        }
    }

    private var sshOut: OutputStream? = null
    private var sshClient: SSHClient? = null
    private var sshSession: net.schmizz.sshj.connection.channel.direct.Session? = null
    private var sshShell: net.schmizz.sshj.connection.channel.direct.Session.Shell? = null
    private var tunnel: CloudflaredTunnel? = null
    @Volatile
    private var isFinished = false
    private val inputQueue = LinkedBlockingQueue<Byte>()
    private val outputWriter = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "ssh-terminal-input").apply { isDaemon = true }
    }

    init {
        mShellPid = -1
        forwarding.update(sshSessionInfo.sshForwards)
    }

    override fun isRunning(): Boolean {
        return !isFinished
    }

    private fun printToTerminal(text: String) {
        val bytes = text.toByteArray()
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            mEmulator?.append(bytes, bytes.size)
            mClient?.onTextChanged(this)
        }
    }

    private fun readInteractiveLine(echo: Boolean = true): String? {
        val sb = StringBuilder()
        while (!isFinished) {
            val b = inputQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            val intVal = b.toInt() and 0xFF
            if (intVal == 13 || intVal == 10) { // Enter (\r or \n)
                printToTerminal("\r\n")
                return sb.toString()
            } else if (intVal == 127 || intVal == 8) { // Backspace
                if (sb.isNotEmpty()) {
                    sb.deleteCharAt(sb.length - 1)
                    if (echo) {
                        printToTerminal("\b \b")
                    }
                }
            } else if (intVal == 3) { // Ctrl+C
                printToTerminal("^C\r\n")
                return null
            } else if (intVal in 32..126) {
                val c = intVal.toChar()
                sb.append(c)
                if (echo) {
                    printToTerminal(c.toString())
                }
            }
        }
        return null
    }

    override fun initializeEmulator(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        mEmulator = TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, 2000, mClient)
        mEmulator.setScrollbackRows(sshSessionInfo.terminalScrollback)
        
        thread {
            try {
                var targetHost = sshSessionInfo.host
                var targetPort = sshSessionInfo.port

                val useCloudflared = sshSessionInfo.useCloudflare || sshSessionInfo.proxyType == ProxyType.CLOUDFLARED
                if (useCloudflared && context != null) {
                    printToTerminal("[Starting Cloudflare Access SSH tunnel...]\r\n")
                    val cfTunnel = CloudflaredTunnel(context)
                    tunnel = cfTunnel
                    val tunnelPort = cfTunnel.start(
                        hostInput = sshSessionInfo.host,
                        parameters = sshSessionInfo.cloudflaredParameters,
                        onStatus = { status -> printToTerminal("[$status]\r\n") },
                        onExit = {
                            if (!isFinished) {
                                printToTerminal("\r\n[Cloudflared tunnel process exited]\r\n")
                                finishIfRunning()
                            }
                        },
                        accessSubcommand = "ssh"
                    )
                    targetHost = "127.0.0.1"
                    targetPort = tunnelPort
                }

                val ssh = SSHClient()
                sshClient = ssh
                ssh.addHostKeyVerifier(PromiscuousVerifier())

                ssh.connect(targetHost, targetPort)

                var user = sshSessionInfo.user.trim()
                if (user.isEmpty() && sshSessionInfo.host.contains("@")) {
                    user = sshSessionInfo.host.substringBefore("@").trim()
                }
                if (user.isEmpty()) {
                    printToTerminal("login as: ")
                    user = readInteractiveLine(echo = true)?.trim() ?: return@thread
                    if (user.isEmpty()) {
                        printToTerminal("No username provided.\r\n")
                        return@thread
                    }
                }

                var authenticated = false

                // 1. Try public key authentication if SSH key is selected
                val savedKey = sshSessionInfo.sshKeyId?.let { keyId ->
                    context?.let { ctx -> ProfileManager(ctx).getSshKeys().find { it.id == keyId } }
                }
                if (savedKey != null && savedKey.privateKey.isNotBlank()) {
                    printToTerminal("[Offering SSH key '${savedKey.name}' for user '$user'...]\r\n")
                    try {
                        ensureBouncyCastleProvider()
                        val passwordFinder = if (savedKey.passphrase.isNotEmpty()) {
                            net.schmizz.sshj.userauth.password.PasswordUtils.createOneOff(savedKey.passphrase.toCharArray())
                        } else null
                        val cleanKey = savedKey.privateKey.trim().replace("\r\n", "\n")
                        val keyProvider = ssh.loadKeys(cleanKey, null as String?, passwordFinder)
                        ssh.authPublickey(user, keyProvider)
                        authenticated = ssh.isAuthenticated
                        if (authenticated) {
                            printToTerminal("[SSH key '${savedKey.name}' accepted by server]\r\n")
                        } else {
                            val allowed = try { ssh.userAuth.allowedMethods } catch (e: Exception) { null }
                            printToTerminal("[Server refused key '${savedKey.name}' for user '$user'${if (allowed != null) " (allowed methods: $allowed)" else ""}]\r\n")
                        }
                    } catch (e: Exception) {
                        val allowed = try { ssh.userAuth.allowedMethods } catch (ex: Exception) { null }
                        val reason = if (e is net.schmizz.sshj.userauth.UserAuthException) {
                            "Key refused by server${if (allowed != null) " (allowed methods: $allowed)" else ""}"
                        } else {
                            e.message ?: "Authentication failed"
                        }
                        printToTerminal("[Server refused SSH key '${savedKey.name}' for user '$user': $reason]\r\n")
                    }
                } else if (savedKey != null && savedKey.privateKey.isBlank()) {
                    printToTerminal("[Notice: SSH key '${savedKey.name}' has no private key data]\r\n")
                } else if (!sshSessionInfo.sshKeyId.isNullOrBlank()) {
                    printToTerminal("[Notice: Configured SSH key not found in saved keys]\r\n")
                }

                // 2. Try password authentication if password was provided and not yet authenticated
                if (!authenticated && sshSessionInfo.pass.isNotEmpty()) {
                    try {
                        ssh.authPassword(user, sshSessionInfo.pass)
                        authenticated = ssh.isAuthenticated
                    } catch (e: Exception) {
                        // Password failed; fall back to interactive prompt below
                    }
                }

                // 3. Fallback to interactive login when credentials were not entered or failed
                if (!authenticated) {
                    var attempts = 0
                    while (!authenticated && attempts < 3 && !isFinished) {
                        attempts++
                        printToTerminal("$user@$targetHost's password: ")
                        val enteredPass = readInteractiveLine(echo = false) ?: return@thread
                        try {
                            ssh.authPassword(user, enteredPass)
                            authenticated = ssh.isAuthenticated
                        } catch (e: Exception) {
                            try {
                                ssh.auth(user, AuthKeyboardInteractive(object : ChallengeResponseProvider {
                                    override fun getSubmethods(): List<String> = emptyList()
                                    override fun init(resource: Resource<*>?, name: String?, instruction: String?) {}
                                    override fun getResponse(prompt: String?, echo: Boolean): CharArray = enteredPass.toCharArray()
                                    override fun shouldRetry(): Boolean = false
                                }))
                                authenticated = ssh.isAuthenticated
                            } catch (e2: Exception) {
                                // Interactive also rejected
                            }
                        }
                        if (!authenticated) {
                            printToTerminal("Permission denied, please try again.\r\n")
                        }
                    }
                }

                if (!authenticated) {
                    printToTerminal("[Authentication failed]\r\n")
                    return@thread
                }

                forwarding.attach(ssh)
                val session = ssh.startSession()
                sshSession = session
                session.allocateDefaultPTY()
                val shell = session.startShell()
                sshShell = shell
                
                sshOut = shell.outputStream
                val sshIn = shell.inputStream
                
                val buffer = ByteArray(4096)
                val mainHandler = Handler(Looper.getMainLooper())
                
                val connectedBytes = "\r\n[SSH Connected to ${targetHost}]\r\n".toByteArray()
                mainHandler.post {
                    mEmulator.append(connectedBytes, connectedBytes.size)
                    mClient.onTextChanged(this)
                }

                while (true) {
                    val read = sshIn.read(buffer)
                    if (read == -1) break
                    
                    val chunk = buffer.copyOf(read)
                    mainHandler.post {
                        mEmulator.append(chunk, read)
                        mClient.onTextChanged(this)
                    }
                }
                mainHandler.post {
                    mClient.onSessionFinished(this)
                }
            } catch (e: Exception) {
                val mainHandler = Handler(Looper.getMainLooper())
                val errorBytes = "\r\n[SSH Error: ${e.message}]\r\n".toByteArray()
                mainHandler.post {
                    if (mEmulator != null) {
                        mEmulator.append(errorBytes, errorBytes.size)
                        mClient.onTextChanged(this)
                    }
                }
            } finally {
                outputWriter.shutdownNow()
                forwarding.close()
                runCatching { sshClient?.disconnect() }
                runCatching { tunnel?.close() }
            }
        }
    }

    override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels)
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels)
            thread {
                try {
                    sshShell?.changeWindowDimensions(columns, rows, cellWidthPixels * columns, cellHeightPixels * rows)
                } catch (e: Exception) {}
            }
        }
    }

    override fun finishIfRunning() {
        if (isFinished) return
        isFinished = true
        outputWriter.shutdownNow()
        forwarding.close()
        mShellPid = -1
        inputQueue.offer(3.toByte())
        thread {
            try { sshOut?.close() } catch (e: Exception) {}
            try { sshShell?.close() } catch (e: Exception) {}
            try { sshSession?.close() } catch (e: Exception) {}
            try { sshClient?.disconnect() } catch (e: Exception) {}
            try { tunnel?.close() } catch (e: Exception) {}
        }
    }

    override fun write(data: ByteArray, offset: Int, count: Int) {
        if (isFinished) return
        val out = sshOut
        if (out != null) {
            // Preserve primary/backspace/alternative ordering and snapshot Termux's reused buffer.
            val bytes = data.copyOfRange(offset, offset + count)
            try {
                outputWriter.execute {
                    try {
                        out.write(bytes)
                        out.flush()
                    } catch (e: Exception) {}
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) { /* Session closed. */ }
        } else {
            for (i in offset until (offset + count)) {
                inputQueue.offer(data[i])
            }
        }
    }
}
