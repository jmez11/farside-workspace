package com.termux.terminal

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.farside.workspace.R
import com.farside.workspace.localizedMessage
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.ProxyType
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SshTerminalSession(
    val sshSessionInfo: ConnectionProfile,
    client: TerminalSessionClient,
    context: Context? = null
) : TerminalSession("dummy", "/", emptyArray(), emptyArray(), sshSessionInfo.terminalScrollback, client) {
    val context: Context? = context?.applicationContext
    val forwarding get() = connectionLease.forwarding
    var onForwardStatus: ((Map<String, String>) -> Unit)? = null
    /** Invoked on the connection thread after the shell starts; must not block the terminal. */
    var onConnected: (() -> Unit)? = null
    /** Called on the main thread when a connection attempt fails before the shell starts. */
    var onConnectionFailed: (() -> Unit)? = null

    companion object {
        init {
            ensureBouncyCastleProvider()
        }

        fun ensureBouncyCastleProvider() {
            com.farside.workspace.ssh.SshConnectionManager.ensureBouncyCastleProvider()
        }
    }

    private var sshOut: OutputStream? = null
    private val connectionLease = com.farside.workspace.ssh.SshConnectionManager.acquire(this.context, sshSessionInfo.id)
    private var sshSession: net.schmizz.sshj.connection.channel.direct.Session? = null
    private var sshShell: net.schmizz.sshj.connection.channel.direct.Session.Shell? = null
    @Volatile
    private var isFinished = false
    private val inputQueue = LinkedBlockingQueue<Byte>()
    private val finishNotified = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun notifySessionFinished() {
        if (finishNotified.compareAndSet(false, true)) Handler(Looper.getMainLooper()).post { mClient?.onSessionFinished(this) }
    }
    private val outputWriter = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "ssh-terminal-input").apply { isDaemon = true }
    }

    init {
        mShellPid = -1
        connectionLease.onForwardStatus = { statuses ->
            Handler(Looper.getMainLooper()).post { if(!isFinished) onForwardStatus?.invoke(statuses) }
        }
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
                val lease = connectionLease
                if (isFinished) { lease.close(); return@thread }
                val ssh = lease.connect(sshSessionInfo, ::printToTerminal,
                    interactive = { echo -> readInteractiveLine(echo) },
                    onExit = {
                        if (!isFinished) {
                            printToTerminal(context?.getString(R.string.ssh_tunnel_process_exited) ?: "\r\n[Cloudflared tunnel process exited]\r\n")
                            notifySessionFinished()
                            finishIfRunning()
                        }
                    })
                if (isFinished) return@thread

                val session = ssh.startSession()
                sshSession = session
                session.allocateDefaultPTY()
                val shell = session.startShell()
                sshShell = shell
                
                sshOut = shell.outputStream
                val sshIn = shell.inputStream
                runCatching { onConnected?.invoke() }
                
                val buffer = ByteArray(4096)
                val mainHandler = Handler(Looper.getMainLooper())
                
                val connectedHost = if (sshSessionInfo.useCloudflare || sshSessionInfo.proxyType == ProxyType.CLOUDFLARED) "127.0.0.1" else sshSessionInfo.host
                val connectedBytes = (context?.getString(R.string.ssh_connected_host, connectedHost)
                    ?: "\r\n[SSH Connected to $connectedHost]\r\n").toByteArray()
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
                notifySessionFinished()
            } catch (e: Exception) {
                if (sshShell != null && !isFinished) notifySessionFinished()
                val mainHandler = Handler(Looper.getMainLooper())
                val reason = context?.let { e.localizedMessage(it) } ?: e.message
                val errorBytes = (context?.getString(R.string.ssh_terminal_error, reason)
                    ?: "\r\n[SSH Error: $reason]\r\n").toByteArray()
                mainHandler.post {
                    if (mEmulator != null) {
                        mEmulator.append(errorBytes, errorBytes.size)
                        mClient.onTextChanged(this)
                    }
                    onConnectionFailed?.invoke()
                }
            } finally {
                outputWriter.shutdownNow()
                runCatching { sshShell?.close() }
                runCatching { sshSession?.close() }
                connectionLease.close()
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
        mShellPid = -1
        inputQueue.offer(3.toByte())
        thread {
            try { sshOut?.close() } catch (e: Exception) {}
            try { sshShell?.close() } catch (e: Exception) {}
            try { sshSession?.close() } catch (e: Exception) {}
            connectionLease.close()
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
