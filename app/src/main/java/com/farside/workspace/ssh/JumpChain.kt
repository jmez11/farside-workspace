package com.farside.workspace.ssh

import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.ConnectionType

class JumpChainException(message: String) : Exception(message)

/** [hops] are in connect order and end with the target profile itself. [warning] is set for a dangling jump reference. */
data class JumpChain(val hops: List<ConnectionProfile>, val warning: String? = null) {
    val jumpHosts: List<ConnectionProfile> get() = hops.dropLast(1)
    /** "bastion → db-01", or just the name for a direct profile. */
    fun describe(): String = hops.joinToString(" → ") { it.name }
}

object JumpChains {
    /** Total SSH connections in a chain, including the target. */
    const val MAX_HOPS = 3

    /** Throws [JumpChainException] on a cycle or when the chain exceeds [MAX_HOPS]; a deleted jump profile ends the chain with a warning. */
    fun resolveChain(profile: ConnectionProfile, all: List<ConnectionProfile>): JumpChain {
        val byId = all.associateBy { it.id }
        val hops = ArrayDeque<ConnectionProfile>()
        hops.addFirst(profile)
        val seen = mutableSetOf(profile.id)
        var warning: String? = null
        var next = profile.jumpHostProfileId
        while (!next.isNullOrBlank()) {
            val jump = byId[next]
            if (jump == null) { warning = "Jump host no longer exists; connecting directly."; break }
            if (!seen.add(jump.id)) throw JumpChainException("Jump host cycle: ${jump.name} appears twice in the chain.")
            if (jump.type != ConnectionType.SSH) throw JumpChainException("Jump host ${jump.name} is not an SSH profile.")
            hops.addFirst(jump)
            if (hops.size > MAX_HOPS) throw JumpChainException("Jump chain is longer than $MAX_HOPS hops (${hops.joinToString(" → ") { it.name }}).")
            next = jump.jumpHostProfileId
        }
        return JumpChain(hops.toList(), warning)
    }

    /** Safe variant for UI: never throws; returns an error string instead. */
    fun preview(profile: ConnectionProfile, all: List<ConnectionProfile>): Pair<JumpChain?, String?> =
        try { resolveChain(profile, all).let { it to it.warning } } catch (e: JumpChainException) { null to e.message }

    /** SSH profiles that may be picked as [profile]'s jump host: excludes self and anything creating a cycle or exceeding the cap. */
    fun candidates(profile: ConnectionProfile, all: List<ConnectionProfile>): List<ConnectionProfile> =
        all.filter { it.type == ConnectionType.SSH && it.id != profile.id }.filter { candidate ->
            try { resolveChain(profile.copy(jumpHostProfileId = candidate.id), all); true } catch (e: JumpChainException) { false }
        }
}
