package com.farside.workspace

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.farside.workspace.data.*
import com.farside.workspace.ui.setup.*

/** Isolated preference namespace: no real PIN, credential, profile or key is edited. */
internal class SetupSignInVerification(private val test:Instrumentation) {
    private fun capturePopup(name:String) {
        val ui=SetupPhaseOneVerification(test)
        val id=ui.find(test.targetContext.getString(R.string.save)).windowId
        var image:android.graphics.Bitmap?=null
        test.runOnMainSync {
            val root=android.view.inspector.WindowInspector.getGlobalWindowViews().firstOrNull {it.createAccessibilityNodeInfo().windowId==id}
            if(root!=null && root.width>0 && root.height>0) {
                image=android.graphics.Bitmap.createBitmap(root.width,root.height,android.graphics.Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(checkNotNull(image)))
            }
        }
        image?.let {bitmap ->
            java.io.File(test.targetContext.cacheDir,name).outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }

    fun run():String {
        val context=test.targetContext
        val namespace="setup_phase2_owned_fixture"
        context.deleteSharedPreferences(namespace)
        val prefs=context.getSharedPreferences(namespace,Context.MODE_PRIVATE)
        val isolated=object:ContextWrapper(context) {
            override fun getSharedPreferences(name:String,mode:Int)=prefs
        }
        val pm=ProfileManager(isolated)
        val verifier=org.json.JSONObject().apply {CredentialManager.writePassword(this,"pin",CredentialPin.create("5827"))}
        check(prefs.edit().putString("app_pinVerifier",verifier.toString()).commit())
        val ui=SetupPhaseOneVerification(test)
        fun text(id:Int)=context.getString(id)
        val info=test.uiAutomation.serviceInfo
        info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo=info
        val monitor=test.addMonitor(MainActivity::class.java.name,null,false)
        var activity:MainActivity?=null
        try {
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            activity=checkNotNull(test.waitForMonitorWithTimeout(monitor,15000)) as MainActivity
            val modes=listOf(ConnectionType.RDP to VncAuthMode.MAC_USER,ConnectionType.VNC to VncAuthMode.VNC_PASSWORD,
                ConnectionType.VNC to VncAuthMode.MAC_USER,ConnectionType.SSH to VncAuthMode.MAC_USER,ConnectionType.SSH to VncAuthMode.MAC_USER)
            for((index,mode) in modes.withIndex()) {
                val base=ConnectionProfile(id="setup-signin-$index",name="Setup owned $index",type=mode.first,host="127.0.0.1",user="",pass="",vncAuthMode=mode.second)
                val state=SetupSignInState(null)
                test.runOnMainSync {
                    activity.setContent {
                        MaterialTheme {
                            Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())) {
                                SetupStepSignIn(base,state,pm)
                                Button(enabled=state.error(base,pm.getCredentials())==null,onClick={}) {Text(text(R.string.continue_action))}
                            }
                        }
                    }
                }
                check((state.error(base,pm.getCredentials())==null)==(mode.first==ConnectionType.SSH))
                if(index==4) {
                    ui.click(text(R.string.setup_connection_specific));ui.enter(R.string.setup_username,"fixture")
                    ui.click(text(R.string.setup_ssh_key));ui.click(text(R.string.setup_import_key))
                    ui.verifyCredentialPopup(R.string.save)
                    val beforeClose=pm.getSshKeys()
                    ui.enter(R.string.key_display_name,"Discarded imported key")
                    ui.click(text(R.string.close));check(pm.getSshKeys()==beforeClose)
                    ui.click(text(R.string.setup_import_key));ui.verifyCredentialPopup(R.string.save)
                    ui.capturePopup(R.string.save,"key-import-popup.png")
                    ui.enter(R.string.key_display_name,"Setup owned key")
                    val pair=java.security.KeyPairGenerator.getInstance("RSA").apply {initialize(2048)}.generateKeyPair()
                    val pem="-----BEGIN PRIVATE KEY-----\n"+android.util.Base64.encodeToString(pair.private.encoded,android.util.Base64.NO_WRAP).chunked(64).joinToString("\n")+"\n-----END PRIVATE KEY-----\n"
                    ui.enter(R.string.private_key,pem);ui.click(text(R.string.require_pin));ui.click(text(R.string.save))
                    val deadline=android.os.SystemClock.uptimeMillis()+10000
                    while(state.keyId==null && android.os.SystemClock.uptimeMillis()<deadline) Thread.sleep(100)
                    check(pm.getSshKeys().single().requirePin && state.keyId==pm.getSshKeys().single().id)
                    check(state.profile(base,pm.getCredentials()).user=="fixture")
                    continue
                }
                val passwordOnly=mode.first==ConnectionType.VNC && mode.second==VncAuthMode.VNC_PASSWORD
                ui.click(text(if(passwordOnly) R.string.setup_new_vnc_password else R.string.setup_new_sign_in))
                ui.verifyCredentialPopup(R.string.save)
                val beforeClose=pm.getCredentials()
                ui.enter(R.string.account_display_name,"Discarded unsaved credential")
                ui.click(text(R.string.close));check(pm.getCredentials()==beforeClose)
                ui.click(text(if(passwordOnly) R.string.setup_new_vnc_password else R.string.setup_new_sign_in))
                ui.verifyCredentialPopup(R.string.save)
                check(ui.find(text(R.string.account_display_name),true).text.isNullOrEmpty())
                ui.enter(R.string.account_display_name,"Setup credential $index")
                if(index==2) {
                    ui.enter(R.string.setup_full_name,"Englebert Rumplestiltskin")
                    check(ui.find(text(R.string.setup_short_name),true).text.toString()=="englebertrumplestiltskin")
                    ui.enter(R.string.setup_short_name,"my_override")
                    ui.enter(R.string.setup_full_name,"Changed Full Name")
                    check(ui.find(text(R.string.setup_short_name),true).text.toString()=="my_override")
                    ui.enter(R.string.setup_short_name,"")
                    check(ui.find(text(R.string.setup_short_name),true).text.toString()=="changedfullname")
                } else if(!passwordOnly) ui.enter(R.string.setup_username,"fixture")
                if(index!=0) ui.enter(if(passwordOnly) R.string.setup_vnc_password else R.string.password,"fixture")
                ui.find(text(R.string.save))
                check(ui.nodes().none {it.text?.toString()==text(R.string.setup_mac_names_help)})
                fun checkSwitchAlignment(id:Int) {
                    val label=text(id)
                    var toggle:android.view.accessibility.AccessibilityNodeInfo?=null
                    var caption:android.view.accessibility.AccessibilityNodeInfo?=null
                    for(attempt in 0..24) {
                        val nodes=ui.nodes()
                        toggle=nodes.firstOrNull {it.isVisibleToUser && it.contentDescription?.toString()==label}
                        caption=nodes.firstOrNull {it.isVisibleToUser && it.text?.toString()==label}
                        if(toggle!=null && caption!=null) break
                        val direction=if(attempt<8) android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        nodes.filter {it.isScrollable && !it.isEditable}.forEach {it.performAction(direction)}
                        Thread.sleep(100)
                    }
                    val switchNode=checkNotNull(toggle) {"Missing popup switch: $label"}
                    val labelNode=checkNotNull(caption) {"Missing popup switch label: $label"}
                    val a=android.graphics.Rect().also(switchNode::getBoundsInScreen)
                    val b=android.graphics.Rect().also(labelNode::getBoundsInScreen)
                    check(kotlin.math.abs(a.centerY()-b.centerY())<=2) {"Popup switch is not centered with its label: $label"}
                }
                checkSwitchAlignment(R.string.require_pin)
                check(ui.nodes().none {it.contentDescription?.toString()==text(R.string.setup_macos_account)})
                ui.nodes().filter {it.isScrollable && !it.isEditable}.forEach {it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)}
                Thread.sleep(200)
                capturePopup("credential-popup-$index.png")
                check(ui.nodes().none {it.text?.toString()==text(R.string.setup_use_headset_pin)})
                ui.click(text(R.string.require_pin));ui.click(text(R.string.save))
                val created=checkNotNull(pm.getCredentials().find {it.id==state.credentialId})
                if(index==0) check(created.pass.isEmpty())
                check(created.requirePin && state.error(base,pm.getCredentials())==null)
                check(SavedCredential.fromJson(created.toJson()).userShort==created.userShort)
                if(index==2) {
                    check(created.userShort=="changedfullname")
                    pm.addProfile(base.copy(type=ConnectionType.SSH,credentialId=created.id))
                    check(pm.getProfiles().single {it.id==base.id}.user=="changedfullname")
                    pm.deleteProfile(base.id)
                    ui.capture(activity,"setup-phase2-mac-login.png")
                }
                ui.click(text(R.string.setup_connection_specific))
                if(!passwordOnly) ui.enter(R.string.setup_username,"fixture")
                if(passwordOnly) {
                    ui.enter(R.string.setup_vnc_password,"123456789");check(state.error(base,pm.getCredentials())!=null)
                    ui.enter(R.string.setup_vnc_password,"\u0100");check(state.error(base,pm.getCredentials())!=null)
                }
                ui.enter(if(passwordOnly) R.string.setup_vnc_password else R.string.password,"fixture")
                val inline=state.profile(base,pm.getCredentials())
                check(inline.credentialId==null && inline.pass=="fixture" && state.error(base,pm.getCredentials())==null)
                val count=pm.getCredentials().size
                pm.addProfile(inline)
                val loaded=pm.getProfiles().single {it.id==base.id}
                check(loaded.credentialId==null && loaded.pass=="fixture") {"Specific credentials linked to vault or lost on reload"}
                check(pm.getCredentials().size==count)
                val raw=prefs.getString("profiles","").orEmpty()
                check(!raw.contains("\"pass\":\"fixture\""))
                val savedJson=org.json.JSONArray(raw).getJSONObject(0)
                check(savedJson.has("passEncrypted") && !savedJson.has("pass"))
                pm.deleteProfile(base.id)
            }
            return "PASS five sign-in variants, inline credential/key sheets, PIN metadata, Mac derive/override/reset and short-name JSON/SSH resolution; RDP/VNC required and VNC8/Latin1 validation; SSH optional; complete saved pairs vs encrypted connection-specific save/reload without auto-link or new vault item. Isolated preferences only; physical keyboard/remote authentication not asserted."
        } finally {
            test.runOnMainSync {activity?.finish()};test.removeMonitor(monitor)
            context.deleteSharedPreferences(namespace)
        }
    }
}
