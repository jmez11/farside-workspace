package com.farside.workspace

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.RejectedExecutionException

class MacSavedPasswordInputTest {
    @Test fun unicodeAndReturnAreSubmittedOnce() {
        val input=MacSavedPasswordInput("A\u00e9\uD83D\uDE42")
        val events=mutableListOf<Pair<Int,Boolean>>()
        fun submit()=input.submit({true},{it.run()},{k,d->events+=k to d},pause={})
        assertTrue(submit());assertFalse(submit())
        assertEquals(listOf(65,233,0x0101f642,0xff0d).flatMap { listOf(it to true,it to false) },events)
    }
    @Test fun disconnectedAndControlContainingPasswordsSendNothing() {
        for(password in listOf("","a\nb","a\tb","a\u0000b")) {
            assertFalse(MacSavedPasswordInput(password).submit({true},{fail("Queued invalid input")},{_,_->fail("Sent invalid input")}))
        }
        val input=MacSavedPasswordInput("example")
        assertFalse(input.submit({false},{fail("Queued disconnected input")},{_,_->fail("Sent disconnected input")}))
        assertFalse(input.attempted)
    }
    @Test fun disconnectDuringTypingDoesNotSubmitReturnOrRetry() {
        val input=MacSavedPasswordInput("abc")
        val events=mutableListOf<Pair<Int,Boolean>>()
        assertTrue(input.submit({events.size<2},{it.run()},{k,d->events+=k to d},pause={}))
        assertEquals(listOf(97 to true,97 to false),events)
        assertFalse(input.submit({true},{fail("Retried partial input")},{_,_->fail("Retried partial input")}))
    }
    @Test fun closedWriterCannotRetry() {
        val input=MacSavedPasswordInput("example")
        assertFalse(input.submit({true},{throw RejectedExecutionException()},{_,_->fail("Closed writer sent input")}))
        assertTrue(input.attempted)
        assertFalse(input.submit({true},{fail("Retried closed writer")},{_,_->fail("Retried closed writer")}))
    }
    @Test fun cooldownCompletesBeforeAnyCredentialInput() {
        var waited=false
        val input=MacSavedPasswordInput("a")
        assertTrue(input.submit({true},{it.run()},{_,_->assertTrue(waited)},pause={},beforeInput={waited=true}))
    }
}
