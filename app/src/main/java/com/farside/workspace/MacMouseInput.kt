package com.farside.workspace

/** RDP-style button transitions must retain the held mask on subsequent motion events. */
internal class MacMouseInput(private val send:(Int,Int,Int)->Unit) {
    private var buttons=0
    private var last:Triple<Int,Int,Int>?=null
    private fun emit(x:Int,y:Int,mask:Int) {last=Triple(x,y,mask);send(x,y,mask)}
    @Synchronized fun pointer(x:Int,y:Int,mask:Int) {
        buttons=mask and 7
        emit(x,y,mask)
    }
    @Synchronized fun mouse(x:Int,y:Int,flags:Int) {
        if(flags and 0x0600!=0) {
            val negative=flags and 0x0100!=0
            val wheel=if(flags and 0x0400!=0) { if(negative) 5 else 6 } else { if(negative) 4 else 3 }
            emit(x,y,buttons or (1 shl wheel));emit(x,y,buttons)
        } else {
            val mask=(if(flags and 0x1000!=0) 1 else 0) or
                (if(flags and 0x2000!=0) 4 else 0) or (if(flags and 0x4000!=0) 2 else 0)
            buttons=if(flags and 0x8000!=0) buttons or mask else buttons and mask.inv()
            // Horizon can deliver the same hover through Activity and Compose.
            // Only discard identical pure motion; button edges and wheel ticks always pass.
            if(flags==0x0800 && last==Triple(x,y,buttons)) return
            emit(x,y,buttons)
        }
    }
}
