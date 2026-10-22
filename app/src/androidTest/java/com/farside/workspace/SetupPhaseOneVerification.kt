package com.farside.workspace

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import com.farside.workspace.data.*
import com.farside.workspace.ui.setup.ConnectionSetupFlow
import com.farside.workspace.utils.DiscoveredHost
import java.io.File
import java.security.MessageDigest

/** Real Compose/accessibility flow and owned persistence; discovery responses are synthetic. */
internal class SetupPhaseOneVerification(private val test:Instrumentation) {
    private fun label(id:Int)=test.targetContext.getString(id)
    internal fun nodes():List<AccessibilityNodeInfo> {
        test.uiAutomation.clearCache()
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun visit(n:AccessibilityNodeInfo) { result+=n;repeat(n.childCount) { n.getChild(it)?.let(::visit) } }
        val windows=test.uiAutomation.windowsOnAllDisplays
        for(i in 0 until windows.size()) windows.valueAt(i).forEach { it.root?.let(::visit) }
        return result
    }
    internal fun find(text:String,editable:Boolean=false):AccessibilityNodeInfo {
        val deadline=SystemClock.uptimeMillis()+7000
        while(SystemClock.uptimeMillis()<deadline) {
            val all=nodes()
            if(!editable) all.firstOrNull {it.isVisibleToUser && it.isClickable && !it.isEditable && it.contentDescription?.toString()==text}?.let {return it}
            for(node in all.filter { it.isVisibleToUser && (it.text?.toString()==text || it.hintText?.toString()==text || it.contentDescription?.toString()==text) }) {
                if(!editable && !node.isEditable) return node
                if(editable) {
                    var field:AccessibilityNodeInfo?=node
                    while(field!=null && !field.isEditable) field=field.parent
                    if(field!=null) return field
                }
            }
            if(!editable) all.firstOrNull { it.isVisibleToUser && it.text?.toString()==text }?.let { return it }
            all.filter { it.isScrollable && it.className?.toString()!="android.widget.EditText" }.forEach { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
            Thread.sleep(150)
        }
        java.io.File(test.targetContext.cacheDir,"setup-missing-ui-private.txt").writeText(nodes().joinToString("\n") {
            "${it.className} text=${it.text} desc=${it.contentDescription} editable=${it.isEditable} password=${it.isPassword} visible=${it.isVisibleToUser}"
        })
        error("Missing setup control: $text")
    }
    internal fun click(text:String) {
        find(text)
        val deadline=SystemClock.uptimeMillis()+7000
        while(SystemClock.uptimeMillis()<deadline) {
            // setContent and dialog dismissal can leave one old accessibility frame.
            val matches=nodes().filter {it.isVisibleToUser && (it.text?.toString()==text || it.contentDescription?.toString()==text)}.sortedBy {it.isEditable}
            for(node in matches) {
                var target:AccessibilityNodeInfo?=node
                while(target!=null && !target.isClickable) target=target.parent
                if(target?.isEnabled==true && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {Thread.sleep(250);return}
            }
            Thread.sleep(100)
        }
        error("No enabled clickable setup control: $text")
    }
    internal fun enter(id:Int,value:String) {
        check(find(label(id),true).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)
        }))
        Thread.sleep(150)
    }
    internal fun enabled(text:String):Boolean {
        var node=find(text)
        while(!node.isClickable && node.parent!=null) node=checkNotNull(node.parent)
        return node.isEnabled
    }
    internal fun capture(activity:MainActivity,name:String) {
        val bitmap=android.graphics.Bitmap.createBitmap(activity.window.decorView.width,activity.window.decorView.height,android.graphics.Bitmap.Config.ARGB_8888)
        val done=java.util.concurrent.CountDownLatch(1)
        var status=-1
        android.view.PixelCopy.request(activity.window,bitmap,{status=it;done.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))
        check(done.await(5,java.util.concurrent.TimeUnit.SECONDS) && status==android.view.PixelCopy.SUCCESS)
        File(test.targetContext.cacheDir,name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
    fun run():String {
        val context=test.targetContext
        val pm=ProfileManager(context)
        val originalIds=pm.getProfiles().map { it.id }.toSet()
        fun images()=File(context.filesDir,"card-backgrounds").listFiles().orEmpty().associate { it.name to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
        val originalImages=images()
        val credential=SavedCredential(name="Setup phase one fixture",user="fixture",pass="fixture")
        val owned=mutableListOf<String>()
        pm.addCredential(credential)
        val keyPair=java.security.KeyPairGenerator.getInstance("RSA").apply {initialize(2048)}.generateKeyPair()
        val fixtureKey=com.farside.workspace.data.SavedSshKey(name="Setup flow owned key",privateKey="-----BEGIN PRIVATE KEY-----\n"+android.util.Base64.encodeToString(keyPair.private.encoded,android.util.Base64.NO_WRAP).chunked(64).joinToString("\n")+"\n-----END PRIVATE KEY-----\n")
        pm.addSshKey(fixtureKey)
        val info=test.uiAutomation.serviceInfo
        info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo=info
        val monitor=test.addMonitor(MainActivity::class.java.name,null,false)
        var activity:MainActivity?=null
        try {
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            activity=checkNotNull(test.waitForMonitorWithTimeout(monitor,15000)) as MainActivity
            val modes=listOf(ConnectionType.RDP to false,ConnectionType.SSH to false,ConnectionType.VNC to false,ConnectionType.VNC to true,ConnectionType.SSH to true)
            for((index,mode) in modes.withIndex()) {
                val type=mode.first
                val accountOrKey=mode.second
                val profileName="Setup fixture ${type.name} $index"
                var saved:ConnectionProfile?=null
                val scans=mutableListOf<ConnectionType>()
                test.runOnMainSync {
                    activity.setContent {
                        androidx.compose.runtime.key(index) {
                            ConnectionSetupFlow(profileManager=pm,onCancel={},onSave={ profile ->
                                saved=profile;owned+=profile.id;pm.addProfile(profile)
                            },discovery={ requested ->
                                scans+=requested
                                listOf(DiscoveredHost("127.0.0.1",profileName,if(requested==ConnectionType.RDP) 3389 else 5900))
                            })
                        }
                    }
                }
                click(type.name)
                if(type==ConnectionType.VNC) click(label(if(accountOrKey) R.string.setup_user_password else R.string.setup_vnc_password))
                if(type==ConnectionType.RDP) { find(label(R.string.setup_connecting_to));capture(activity,"setup-phase1-protocol.png") }
                click(label(R.string.continue_action))
                check(!enabled(label(R.string.continue_action))) { "Blank server allowed Continue" }
                find(label(R.string.profile_name_required));find(label(R.string.profile_host_required))
                if(type==ConnectionType.RDP) capture(activity,"setup-phase1-validation.png")
                enter(R.string.connection_name,profileName)
                enter(R.string.host_server,"127.0.0.1")
                enter(R.string.setup_port,"65536")
                check(!enabled(label(R.string.continue_action)))
                find(label(R.string.profile_port_invalid))
                enter(R.string.setup_port,"")
                check(!enabled(label(R.string.continue_action)))
                if(type==ConnectionType.SSH) {
                    check(nodes().none { it.text?.toString() in listOf(label(R.string.setup_find_devices),label(R.string.setup_find_pcs)) })
                    enter(R.string.setup_port,"22")
                } else {
                    click(label(if(type==ConnectionType.RDP) R.string.setup_find_pcs else R.string.setup_find_devices))
                    click(profileName)
                    check(scans==listOf(type)) { "Discovery requested another protocol" }
                }
                check(enabled(label(R.string.continue_action)))
                if(type==ConnectionType.RDP) capture(activity,"setup-phase1-server.png")
                click(label(R.string.continue_action))
                if(type==ConnectionType.SSH && accountOrKey) {
                    click(label(R.string.setup_connection_specific));enter(R.string.setup_username,"fixture")
                    click(label(R.string.setup_ssh_key));click(fixtureKey.name)
                } else click(credential.name)
                if(type==ConnectionType.RDP) capture(activity,"setup-phase2-sign-in.png")
                click(label(R.string.continue_action))
                find(test.targetContext.getString(R.string.setup_edit_step,label(R.string.setup_sign_in)))
                click(label(R.string.setup_save_connection))
                val result=checkNotNull(saved)
                check(result.type==type && result.host=="127.0.0.1" && result.name==profileName)
                check(result.port==when(type) { ConnectionType.RDP->3389;ConnectionType.SSH->22;ConnectionType.VNC->5900 })
                check(pm.getProfiles().single { it.id==result.id }.type==type)
                if(type==ConnectionType.VNC) check(result.vncAuthMode==(if(accountOrKey) VncAuthMode.MAC_USER else VncAuthMode.VNC_PASSWORD) && result.credentialId==credential.id)
                if(type==ConnectionType.SSH && accountOrKey) check(result.sshKeyId==fixtureKey.id && result.credentialId==null)
                if(type==ConnectionType.SSH) check(result.enableSftp) {"New SSH profile did not default to SFTP enabled"}
            }
            return "PASS actual flow creates/reloads all five owned RDP,SSH password/key,VNC password/account variants; required name/host/port and range errors; SSH has no discovery; injected RDP/VNC scans select name/IP/port; existing sign-in/save path; photo integrity. Physical Horizon input and live discovery not asserted."
        } finally {
            owned.forEach(pm::deleteProfile);pm.deleteCredential(credential.id);pm.deleteSshKey(fixtureKey.id)
            test.runOnMainSync { activity?.finish() };test.removeMonitor(monitor)
            check(pm.getProfiles().map { it.id }.toSet()==originalIds) { "Profile cleanup changed original identities" }
            check(images()==originalImages) { "Setup fixture changed existing photos" }
        }
    }
}
