package com.farside.workspace

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class MacConnectionPolicyTest {
    private val direct=MacConnectionPolicy.Route.DIRECT
    private val companion=MacConnectionPolicy.Route.COMPANION
    private val vnc=MacConnectionPolicy.Route.VNC
    @Test fun directSuccessDoesNotOpenCompanionOrAuthenticateAgain() {
        val attempts=mutableListOf<MacConnectionPolicy.Route>()
        assertEquals("connected",MacConnectionPolicy.connect(true,true,{false},{false},{_,_->fail()}) {
            attempts+=it;"connected"
        })
        assertEquals(listOf(direct),attempts)
    }
    @Test fun unavailableMediaTriesCompanionBeforeVnc() {
        val attempts=mutableListOf<MacConnectionPolicy.Route>()
        assertEquals("connected",MacConnectionPolicy.connect(true,true,{false},{false},{_,_->}) {
            attempts+=it;if(it!=vnc) throw IOException("Media unavailable");"connected"
        })
        assertEquals(listOf(direct,companion,vnc),attempts)
    }
    @Test fun absentCompanionFallsBackToVnc() {
        val attempts=mutableListOf<MacConnectionPolicy.Route>()
        MacConnectionPolicy.connect(true,false,{false},{false},{_,_->}) {
            attempts+=it;if(it==direct) throw IOException();true
        }
        assertEquals(listOf(direct,vnc),attempts)
    }
    @Test fun rejectionAndCancellationDoNotRetryCredentials() {
        for(error in listOf(VncAuthenticationException(),MacSessionRejectedException("Declined"),SecurityException(),InterruptedException())) {
        val attempts=mutableListOf<MacConnectionPolicy.Route>()
        assertNull(MacConnectionPolicy.connect(true,true,{false},MacConnectionPolicy::isTerminal,{_,_->}) {
            attempts+=it;throw error
        })
        assertEquals(listOf(direct),attempts)
        }
        MacConnectionPolicy.connect(true,true,{true},{false},{_,_->fail()}) { fail("Cancelled route started") }
    }
}
