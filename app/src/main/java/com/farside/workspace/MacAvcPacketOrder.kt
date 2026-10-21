package com.farside.workspace

/** A bounded jitter window: duplicates/late retransmits never reach the picture assembler. */
internal class MacAvcPacketOrder {
    data class Packet(val payload:ByteArray,val sequence:Int,val timestamp:Long,val marker:Boolean)
    private val pending=HashMap<Int,Packet>()
    private var expected:Int?=null
    private var blockedSince:Long?=null
    private var bytes=0
    var repaired=0L; private set
    var duplicates=0L; private set
    var expiredGaps=0L; private set
    private var loss=false
    fun takeLoss()=loss.also { loss=false }
    fun reset(next:Int?=null) { pending.clear();bytes=0;expected=next;blockedSince=null;loss=false }
    fun accept(packet:Packet,now:Long):List<Packet> {
        val next=expected ?: packet.sequence.also { expected=it }
        val distance=(packet.sequence-next) and 65535
        if(distance>=32768 || pending.containsKey(packet.sequence)) { duplicates++;return drain(now) }
        if(distance!=0) repaired++
        pending[packet.sequence]=packet;bytes+=packet.payload.size
        return drain(now)
    }
    fun drain(now:Long):List<Packet> {
        val output=ArrayList<Packet>()
        while(pending.isNotEmpty()) {
            val next=expected ?: break
            val packet=pending.remove(next)
            if(packet!=null) {
                bytes-=packet.payload.size;output.add(packet);expected=(next+1) and 65535;blockedSince=null
                continue
            }
            val since=blockedSince ?: now.also { blockedSince=it }
            if(now-since<100 && pending.size<4096 && bytes<8*1024*1024) break
            expected=pending.keys.minBy { (it-next) and 65535 }
            expiredGaps++;loss=true;blockedSince=null
        }
        return output
    }
}
