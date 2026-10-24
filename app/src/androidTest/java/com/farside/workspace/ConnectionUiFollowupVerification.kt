package com.farside.workspace

import android.app.Instrumentation
import android.content.*
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.farside.workspace.data.*
import com.farside.workspace.ui.setup.*

internal class ConnectionUiFollowupVerification(private val test:Instrumentation) {
    fun vncFailure():String {
        val context=test.targetContext
        val port=java.net.ServerSocket(0).use {it.localPort}
        val profile=ConnectionProfile("owned-vnc-failure","Owned VNC failure",ConnectionType.VNC,"127.0.0.1",port,user="",pass="fixture",vncAuthMode=VncAuthMode.VNC_PASSWORD)
        val done=java.util.concurrent.CountDownLatch(1)
        var reason:String?=null
        val owner=VncSessionConnection(context,profile,VncBridge(),onDisconnected={text,_->reason=text;done.countDown()},onConnected={error("Closed loopback port unexpectedly connected")})
        val texture=android.graphics.SurfaceTexture(0)
        try {
            owner.attach(android.view.Surface(texture))
            check(done.await(15,java.util.concurrent.TimeUnit.SECONDS)) {"VNC failure callback timed out"}
            check(!reason.isNullOrBlank() && listOf("Screen Sharing","ARD","Mac").none {reason!!.contains(it)}) {"Generic VNC failure contains Mac guidance"}
            return "PASS generic VNC refusal callback has no Mac Screen Sharing/ARD guidance; owned closed loopback port only."
        } finally {owner.close();texture.release()}
    }
    fun run():String {
        val context=test.targetContext
        val namespace="owned_connection_followup"
        context.deleteSharedPreferences(namespace)
        val prefs=context.getSharedPreferences(namespace,0)
        val isolated=object:ContextWrapper(context) {override fun getSharedPreferences(name:String,mode:Int)=prefs}
        val pm=ProfileManager(isolated)
        val credentials=listOf(SavedCredential(name="Owned regular",user="fixture",pass="fixture"),
            SavedCredential(name="Owned VNC",user="",pass="fixture"),
            SavedCredential(name="Owned Mac",user="Full Fixture",userShort="fixture",pass="fixture"))
        credentials.forEach(pm::addCredential)
        val ui=SetupPhaseOneVerification(test)
        val info=test.uiAutomation.serviceInfo
        info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo=info
        val monitor=test.addMonitor(MainActivity::class.java.name,null,false)
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val activity=checkNotNull(test.waitForMonitorWithTimeout(monitor,15000)) {"Launcher did not start"} as MainActivity
        test.removeMonitor(monitor)
        android.util.Log.i("FollowupVerification","Launcher started")
        val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val original=clipboard.primaryClip
        try {
            for(type in ConnectionType.entries) for(auth in VncAuthMode.entries) {
                android.util.Log.i("FollowupVerification","Filter $type/$auth")
                val base=ConnectionProfile(id="owned",user="",pass="",name="Owned",type=type,host="127.0.0.1",vncAuthMode=auth)
                val state=SetupSignInState(null)
                test.runOnMainSync {activity.setContent {MaterialTheme {SetupStepSignIn(base,state,pm)}}}
                ui.find(credentials[if(type!=ConnectionType.VNC) 0 else if(auth==VncAuthMode.VNC_PASSWORD) 1 else 2].name)
                credentials.forEachIndexed {index,credential ->
                    val expected=when(type) {ConnectionType.VNC -> index==if(auth==VncAuthMode.VNC_PASSWORD) 1 else 2;else -> index==0}
                    check(credentialMatchesConnection(credential,base)==expected)
                    check(ui.nodes().any {it.text?.toString()==credential.name}==expected) {"Incorrect credential filter for $type/$auth/${credential.name}; shown="+ui.nodes().mapNotNull {it.text?.toString()}.filter {it.startsWith("Owned")}}
                    state.credentialId=credential.id
                    check((state.profile(base,credentials).credentialId==credential.id)==expected) {"Filtered stale credential still resolved"}
                }
            }
            // Exercise all offered actions together; empty fields only offer Paste.
            val invoked=mutableListOf<Int>()
            fun showActionRow() {
                var toolbar:androidx.compose.ui.platform.TextToolbar?=null
                test.runOnMainSync {activity.setContent {MaterialTheme {FormTextMenus {
                    Box(Modifier.fillMaxSize()) {Text("Owned toolbar fixture")}
                    toolbar=androidx.compose.ui.platform.LocalTextToolbar.current
                }}}}
                ui.find("Owned toolbar fixture")
                test.runOnMainSync {
                    checkNotNull(toolbar).showMenu(androidx.compose.ui.geometry.Rect(40f,400f,400f,460f),
                        onCopyRequested={invoked.add(android.R.string.copy)},
                        onPasteRequested={invoked.add(android.R.string.paste)},
                        onCutRequested={invoked.add(android.R.string.cut)},
                        onSelectAllRequested={invoked.add(android.R.string.selectAll)})
                }
            }
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Owned","row-fixture"))
            for(action in listOf(android.R.string.copy,android.R.string.paste,android.R.string.cut,android.R.string.selectAll)) {
                showActionRow()
                val labels=listOf(android.R.string.paste,android.R.string.copy,android.R.string.cut,android.R.string.selectAll)
                val nodes=labels.map {ui.find(context.getString(it))}
                val rectangles=nodes.map {node->android.graphics.Rect().also(node::getBoundsInScreen)}
                check(rectangles.all {kotlin.math.abs(it.exactCenterY()-rectangles.first().exactCenterY())<2}) {"Text actions must share one horizontal row: $rectangles"}
                check(rectangles.zipWithNext().all {(a,b)->a.right<=b.left}) {"Text actions overlap: $rectangles"}
                if(action==android.R.string.copy) test.runOnMainSync {
                    val popup=checkNotNull(android.view.inspector.WindowInspector.getGlobalWindowViews().firstOrNull {it.createAccessibilityNodeInfo().windowId==nodes.first().windowId})
                    val origin=IntArray(2).also(popup::getLocationOnScreen)
                    val actionBounds=nodes.map {node->
                        var target=node
                        while(!target.isClickable && target.parent!=null) target=target.parent
                        android.graphics.Rect().also(target::getBoundsInScreen)
                    }
                    check(kotlin.math.abs(actionBounds.first().left-origin[0])<=1 &&
                        kotlin.math.abs(actionBounds.last().right-origin[0]-popup.width)<=1) {"Action highlights must reach the capsule edges: $actionBounds popup=${popup.width}"}
                    val image=android.graphics.Bitmap.createBitmap(popup.width,popup.height,android.graphics.Bitmap.Config.ARGB_8888)
                    popup.draw(android.graphics.Canvas(image))
                    java.io.File(context.cacheDir,"form-actions-capsule.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                    image.recycle()
                }
                ui.click(context.getString(action))
                check(invoked.lastOrNull()==action) {"Wrong text action callback"}
                test.runOnMainSync {activity.setContent {}}
            }
            for(label in listOf(R.string.setup_username,R.string.password,R.string.host_server,R.string.connection_name,R.string.account_display_name)) {
                android.util.Log.i("FollowupVerification","Paste field $label")
                test.runOnMainSync {activity.setContent {FormTextMenus {MaterialTheme {key(label) {
                    if(label==R.string.account_display_name) CredentialSheet(pm,allowMac=false,onDismiss={},onSaved={}) else {
                    var value by remember {mutableStateOf("")}
                    Column(Modifier.fillMaxSize().padding(top=when(label) {R.string.setup_username->80.dp;R.string.password->200.dp;R.string.host_server->300.dp;else->120.dp})) {
                        SetupInput(label,value,{value=it},secret=label==R.string.password)
                    }
                    }
                }}}}}
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Owned", "paste-owned"))
                Thread.sleep(350)
                val field=ui.find(context.getString(label),true)
                val bounds=android.graphics.Rect().also(field::getBoundsInScreen)
                test.runOnMainSync {
                    val root=checkNotNull(android.view.inspector.WindowInspector.getGlobalWindowViews().firstOrNull {it.createAccessibilityNodeInfo().windowId==field.windowId})
                    val origin=IntArray(2).also(root::getLocationOnScreen)
                    val down=android.os.SystemClock.uptimeMillis()
                    val event=android.view.MotionEvent.obtain(down,down,android.view.MotionEvent.ACTION_DOWN,bounds.exactCenterX()-origin[0],bounds.exactCenterY()-origin[1],0)
                    event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;root.dispatchTouchEvent(event);event.recycle()
                }
                Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong()+200)
                test.runOnMainSync {
                    val root=checkNotNull(android.view.inspector.WindowInspector.getGlobalWindowViews().firstOrNull {it.createAccessibilityNodeInfo().windowId==field.windowId})
                    val origin=IntArray(2).also(root::getLocationOnScreen)
                    val now=android.os.SystemClock.uptimeMillis()
                    val event=android.view.MotionEvent.obtain(now-800,now,android.view.MotionEvent.ACTION_UP,bounds.exactCenterX()-origin[0],bounds.exactCenterY()-origin[1],0)
                    event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;root.dispatchTouchEvent(event);event.recycle()
                }
                val paste=ui.find(context.getString(android.R.string.paste))
                test.runOnMainSync {
                    val popup=checkNotNull(android.view.inspector.WindowInspector.getGlobalWindowViews().firstOrNull {it.createAccessibilityNodeInfo().windowId==paste.windowId})
                    val origin=IntArray(2).also(popup::getLocationOnScreen)
                    val menu=android.graphics.Rect(origin[0],origin[1],origin[0]+popup.width,origin[1]+popup.height)
                    check(menu.width()<bounds.width()*3/4 && menu.height()<300) {"Paste popup expanded to a screen-wide panel: $menu field=$bounds"}
                    val distance=when {menu.bottom<bounds.top->bounds.top-menu.bottom;menu.top>bounds.bottom->menu.top-bounds.bottom;else->0}
                    check(distance<=64 && menu.right>bounds.left && menu.left<bounds.right) {"Paste popup is not beside its field: $menu field=$bounds"}
                    if(label==R.string.setup_username || label==R.string.account_display_name) {
                        val image=android.graphics.Bitmap.createBitmap(popup.width,popup.height,android.graphics.Bitmap.Config.ARGB_8888)
                        popup.draw(android.graphics.Canvas(image))
                        java.io.File(context.cacheDir,if(label==R.string.account_display_name) "credential-paste-popup.png" else "form-paste-popup.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                        image.recycle()
                    }
                }
                ui.click(context.getString(android.R.string.paste))
                val value=ui.find(context.getString(label),true).text.toString()
                check(if(label==R.string.password) value.length==11 else value=="paste-owned") {"Paste failed in field $label: length=${value.length}"}
            }
            return "PASS actual credential filtering for all protocol/auth combinations, stale selections rejected, single horizontal nonoverlapping four-action capsule with all callbacks, compact anchored held-touch Paste at varied field heights into username/password/host/display-name fields and credential dialog. Owned clipboard/preferences restored; physical Horizon gestures remain manual."
        } finally {
            if(original!=null) clipboard.setPrimaryClip(original) else clipboard.clearPrimaryClip()
            test.runOnMainSync {activity.finish()};context.deleteSharedPreferences(namespace)
        }
    }
}
