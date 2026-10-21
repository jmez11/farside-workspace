package com.farside.workspace

/** RDP-style button transitions must retain the held mask on subsequent motion events. */
internal class MacMouseInput(private val send:(Int,Int,Int)->Unit) {
    private var buttons=0
    @Synchronized fun pointer(x:Int,y:Int,mask:Int) {
        buttons=mask and 7
        send(x,y,mask)
    }
    @Synchronized fun mouse(x:Int,y:Int,flags:Int) {
        if(flags and 0x0600!=0) {
            val negative=flags and 0x0100!=0
            val wheel=if(flags and 0x0400!=0) { if(negative) 5 else 6 } else { if(negative) 4 else 3 }
            send(x,y,buttons or (1 shl wheel));send(x,y,buttons)
        } else {
            val mask=(if(flags and 0x1000!=0) 1 else 0) or
                (if(flags and 0x2000!=0) 4 else 0) or (if(flags and 0x4000!=0) 2 else 0)
            buttons=if(flags and 0x8000!=0) buttons or mask else buttons and mask.inv()
            send(x,y,buttons)
        }
    }
}
