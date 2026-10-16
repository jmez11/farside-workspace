package com.farside.workspace.ssh

import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.ConnectionType
import android.content.Context
import androidx.annotation.AnyRes
import androidx.annotation.StringRes
import com.farside.workspace.LocalizableError
import com.farside.workspace.R
import com.farside.workspace.localizedMessage

class JumpChainException : Exception, LocalizableError {
    override val resId: Int
    override val formatArgs: Array<out Any>
    override val quantity: Int?
    constructor(@AnyRes resId: Int, vararg args: Any, quantity: Int? = null) : super() {
        this.resId = resId; formatArgs = args; this.quantity = quantity
    }
}

/** [hops] are in connect order and end with the target profile itself. [warning] is set for a dangling jump reference. */
data class JumpChain(val hops: List<ConnectionProfile>, @StringRes val warning: Int? = null) {
    val jumpHosts: List<ConnectionProfile> get() = hops.dropLast(1)
    /** "bastion → db-01", or just the name for a direct profile. */
    fun describe(context: Context): String = hops.joinToString(context.getString(R.string.jump_route_separator)) { it.name }
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
        var warning: Int? = null
        var next = profile.jumpHostProfileId
        while (!next.isNullOrBlank()) {
            val jump = byId[next]
            if (jump == null) { warning = R.string.jump_host_deleted; break }
            if (!seen.add(jump.id)) throw JumpChainException(R.string.jump_host_cycle, jump.name)
            if (jump.type != ConnectionType.SSH) throw JumpChainException(R.string.jump_host_wrong_type, jump.name)
            hops.addFirst(jump)
            if (hops.size > MAX_HOPS) throw JumpChainException(R.plurals.jump_chain_too_long, MAX_HOPS, hops.map { it.name }, quantity = MAX_HOPS)
            next = jump.jumpHostProfileId
        }
        return JumpChain(hops.toList(), warning)
    }

    /** Safe variant for UI: never throws; returns an error string instead. */
    fun preview(context: Context, profile: ConnectionProfile, all: List<ConnectionProfile>): Pair<JumpChain?, String?> =
        try { resolveChain(profile, all).let { it to it.warning?.let(context::getString) } }
        catch (e: JumpChainException) { null to e.localizedMessage(context) }

    /** SSH profiles that may be picked as [profile]'s jump host: excludes self and anything creating a cycle or exceeding the cap. */
    fun candidates(profile: ConnectionProfile, all: List<ConnectionProfile>): List<ConnectionProfile> =
        all.filter { it.type == ConnectionType.SSH && it.id != profile.id }.filter { candidate ->
            try { resolveChain(profile.copy(jumpHostProfileId = candidate.id), all); true } catch (e: JumpChainException) { false }
        }
}
