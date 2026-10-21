package com.farside.workspace

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class MacPointerQueueTest {
    @Test fun slowTransportKeepsLatestMotionAndEveryButtonPosition() {
        val tasks=ArrayDeque<Runnable>();val sent=mutableListOf<MacPointerQueue.Point>()
        val queue=MacPointerQueue({tasks.add(it)},{sent.add(it)})
        queue.offer(10,10,0);queue.offer(20,20,1)
        repeat(10000) { queue.offer(it,it,1) }
        queue.offer(30,30,0)
        assertEquals(1,tasks.size)
        while(tasks.isNotEmpty()) tasks.removeFirst().run()
        assertEquals(listOf(10 to 0,20 to 1,9999 to 1,30 to 0),sent.map { it.x to it.buttons })
        assertEquals(9999L,queue.coalesced)
    }
    @Test fun keyboardBarrierPreservesModifierClickOrdering() {
        val tasks=ArrayDeque<Runnable>();val sent=mutableListOf<String>()
        val queue=MacPointerQueue({tasks.add(it)},{sent.add("${it.x}:${it.buttons}")})
        queue.offer(1,1,0);queue.barrier { sent.add("Command down") }
        queue.offer(2,2,1);queue.offer(3,3,0);queue.barrier { sent.add("Command up") }
        while(tasks.isNotEmpty()) tasks.removeFirst().run()
        assertEquals(listOf("1:0","Command down","2:1","3:0","Command up"),sent)
    }
    @Test fun scrollTicksAreNeverCoalescedAndCloseDiscardsPendingInput() {
        val tasks=ArrayDeque<Runnable>();val sent=mutableListOf<MacPointerQueue.Point>()
        val queue=MacPointerQueue({tasks.add(it)},{sent.add(it)})
        repeat(100) { queue.offer(it,0,8) }
        while(tasks.isNotEmpty()) tasks.removeFirst().run()
        assertEquals(100,sent.size)
        queue.offer(101,0,0);queue.close();queue.offer(102,0,1)
        while(tasks.isNotEmpty()) tasks.removeFirst().run()
        assertEquals(100,sent.size)
    }
}
