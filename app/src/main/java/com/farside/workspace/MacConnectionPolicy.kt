package com.farside.workspace

/** Route changes follow transport/media failures; credential/permission rejection is terminal. */
internal object MacConnectionPolicy {
    enum class Route { DIRECT, COMPANION, VNC }
    fun isTerminal(error:Exception):Boolean = error is VncAuthenticationException ||
        error is MacSessionRejectedException || error is SecurityException || error is InterruptedException
    fun routes(macLogin:Boolean,companion:Boolean):List<Route> = if(!macLogin) listOf(Route.VNC)
        else buildList { add(Route.DIRECT);if(companion) add(Route.COMPANION);add(Route.VNC) }
    fun <T> connect(macLogin:Boolean, companion:Boolean, cancelled:()->Boolean,
        terminal:(Exception)->Boolean, failed:(Route,Exception)->Unit, attempt:(Route)->T):T? {
        for(route in routes(macLogin,companion)) {
            if(cancelled()) break
            try { return attempt(route) } catch(error:Exception) {
                failed(route,error)
                if(terminal(error)) break
            }
        }
        return null
    }
}
