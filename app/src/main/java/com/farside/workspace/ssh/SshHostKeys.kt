package com.farside.workspace.ssh

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.json.JSONObject
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal data class SshHostKey(val algorithm: String, val fingerprint: String) {
    companion object {
        fun from(key: PublicKey): SshHostKey {
            val wire = Buffer.PlainBuffer().putPublicKey(key).compactData
            val hash = MessageDigest.getInstance("SHA-256").digest(wire)
            return SshHostKey(KeyType.fromKey(key).toString(), "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(hash))
        }
    }
}

/** Public server fingerprints, indexed by logical destination, never the proxy's loopback port. */
internal class SshHostKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("ssh_host_keys", Context.MODE_PRIVATE)
    private fun endpoint(host: String, port: Int) = "[${host.trim().substringAfterLast('@').removeSurrounding("[", "]").lowercase(Locale.ROOT)}]:$port"
    fun get(host: String, port: Int): SshHostKey? = synchronized(lock) {
        runCatching { JSONObject(prefs.getString(endpoint(host, port), null) ?: return null).let {
            SshHostKey(it.getString("algorithm"), it.getString("fingerprint"))
        } }.getOrNull()
    }
    fun remove(host: String, port: Int): Boolean = synchronized(lock) {
        prefs.edit().remove(endpoint(host, port)).commit()
    }
    fun generation(): Long = synchronized(lock) { prefs.getLong("generation", 0) }
    fun save(host: String, port: Int, key: SshHostKey): Boolean = synchronized(lock) {
        prefs.edit().putString(endpoint(host, port), JSONObject().put("algorithm", key.algorithm).put("fingerprint", key.fingerprint).toString()).commit()
    }
    fun saveIfUnchanged(host: String, port: Int, key: SshHostKey, previous: SshHostKey?, generation: Long): Boolean = synchronized(lock) {
        this.generation() == generation && get(host, port) == previous && save(host, port, key)
    }
    fun clear(): Boolean = synchronized(lock) {
        prefs.edit().clear().putLong("generation", generation() + 1).commit()
    }
    private companion object { val lock = Any() }
}

internal class SshHostKeyRequest(
    val host: String, val port: Int, val key: SshHostKey, val previous: SshHostKey?, val profileId: String = ""
) {
    val id: String = UUID.randomUUID().toString()
    val decision = CompletableFuture<Boolean>()
    val changed: Boolean get() = previous != null && previous != key
}

/** One prompt at a time across spatial windows; closing the owning UI cancels its decision. */
internal object SshHostKeyPrompts {
    data class Pending(val request: SshHostKeyRequest, val owner: String? = null)
    private val state = MutableStateFlow<List<Pending>>(emptyList())
    val pending = state.asStateFlow()
    private val hosts = mutableMapOf<String, Set<String>>()
    @Synchronized fun register(owner: String, profiles: Set<String>) { hosts[owner] = profiles }
    @Synchronized fun claim(id: String, owner: String): Boolean {
        val head = state.value.firstOrNull() ?: return false
        if (head.request.id != id || head.owner != null) return false
        val matching = hosts.filterValues { head.request.profileId in it }
        if (matching.isNotEmpty() && owner !in matching) return false
        state.value = listOf(head.copy(owner = owner)) + state.value.drop(1)
        return true
    }
    @Synchronized fun release(owner: String) {
        hosts.remove(owner)
        state.value.filter { it.owner == owner }.forEach { it.request.decision.complete(false) }
        state.value = state.value.filterNot { it.owner == owner }
    }
    @Synchronized fun cancelAll() {
        state.value.forEach { it.request.decision.complete(false) }
        state.value = emptyList()
    }
    @Synchronized private fun add(request: SshHostKeyRequest) { state.value = state.value + Pending(request) }
    @Synchronized private fun remove(request: SshHostKeyRequest) { state.value = state.value.filterNot { it.request === request } }

    fun verify(store: SshHostKeyStore, host: String, port: Int, key: SshHostKey, profileId: String, cancelled: () -> Boolean): Boolean {
        val previous = store.get(host, port)
        if (previous == key) return !cancelled()
        val generation = store.generation()
        val request = SshHostKeyRequest(host, port, key, previous, profileId)
        add(request)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        try {
            while (!cancelled() && System.nanoTime() < deadline) {
                if (store.get(host, port) == key) return true // Another window already approved this exact key.
                try {
                    return request.decision.get(200, TimeUnit.MILLISECONDS) && !cancelled() &&
                        store.saveIfUnchanged(host, port, key, previous, generation)
                } catch (_: TimeoutException) { }
            }
            return false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } finally {
            request.decision.complete(false)
            remove(request)
        }
    }
}

internal class SavedSshHostKeyVerifier(
    private val store: SshHostKeyStore, private val host: String, private val port: Int,
    private val profileId: String = "", private val cancelled: () -> Boolean
) : HostKeyVerifier {
    override fun verify(ignoredHost: String, ignoredPort: Int, key: PublicKey): Boolean =
        SshHostKeyPrompts.verify(store, host, port, SshHostKey.from(key), profileId, cancelled)
    override fun findExistingAlgorithms(ignoredHost: String, ignoredPort: Int): List<String> =
        store.get(host, port)?.let { listOf(it.algorithm) } ?: emptyList()
}

/** Also routes jump-hop and paired-media prompts to the connection window that uses them. */
internal fun sshHostKeyScope(context: Context, profile: com.farside.workspace.data.ConnectionProfile): Set<String> {
    val profiles = com.farside.workspace.data.ProfileManager(context).getProfiles().associateBy { it.id }
    val ids = mutableSetOf<String>()
    fun visit(current: com.farside.workspace.data.ConnectionProfile) {
        if (!ids.add(current.id)) return
        listOfNotNull(current.jumpHostProfileId, current.macSshRelayProfileId, current.sftpPairProfileId)
            .forEach { id -> profiles[id]?.let(::visit) }
    }
    visit(profile)
    return ids
}
