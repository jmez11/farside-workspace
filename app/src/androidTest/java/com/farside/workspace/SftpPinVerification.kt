package com.farside.workspace

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalContext
import com.farside.workspace.data.*
import com.farside.workspace.sftp.SftpFilesEntry
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

/** Owned loopback refusal counts prove no transport opens before selected-session unlock. */
internal class SftpPinVerification(private val test:Instrumentation) {
    fun run():String {
        val context=test.targetContext
        val namespace="sftp_pin_owned_fixture"
        context.deleteSharedPreferences(namespace)
        val prefs=context.getSharedPreferences(namespace,0)
        val original=context.getSharedPreferences("farside_profiles",0).all.toMap()
        val isolated=object:ContextWrapper(context) {
            override fun getSharedPreferences(name:String,mode:Int)=prefs
            override fun getApplicationContext():Context=this
        }
        val pm=ProfileManager(isolated)
        pm.setAppPinVerifier(CredentialPin.create("5827"))
        val credential=SavedCredential(name="Owned SFTP secret",user="fixture",pass="fixture",requirePin=true)
        val sshKey=SavedSshKey(name="Owned protected key",privateKey="unused-owned-fixture",requirePin=true)
        pm.addCredential(credential);pm.addSshKey(sshKey)
        val listener=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        val accepted=AtomicInteger()
        val server=Thread({while(!listener.isClosed) {runCatching {listener.accept().use {accepted.incrementAndGet()}}}},"owned-sftp-pin-listener").apply {isDaemon=true;start()}
        val plain=ConnectionProfile("owned-sftp-plain","Owned unprotected SFTP",ConnectionType.SSH,"127.0.0.1",listener.localPort,"fixture","",enableSftp=true)
        val protected=plain.copy(id="owned-sftp-protected",name="Owned protected SFTP",credentialId=credential.id)
        val keyProfile=plain.copy(id="owned-sftp-key",name="Owned key SFTP",sshKeyId=sshKey.id)
        pm.addProfile(plain);pm.addProfile(protected);pm.addProfile(keyProfile)
        val ui=SetupPhaseOneVerification(test)
        fun text(id:Int)=context.getString(id)
        fun waitForCount(count:Int) {
            val deadline=android.os.SystemClock.uptimeMillis()+10000
            while(accepted.get()<count && android.os.SystemClock.uptimeMillis()<deadline) Thread.sleep(100)
            check(accepted.get()==count) {"Expected $count owned connection attempts, got ${accepted.get()}; storage permission=${hasStoragePermission(context)}"}
        }
        val info=test.uiAutomation.serviceInfo
        info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo=info
        val monitor=test.addMonitor(MainActivity::class.java.name,null,false)
        var activity:MainActivity?=null
        try {
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            activity=checkNotNull(test.waitForMonitorWithTimeout(monitor,15000)) as MainActivity
            var revision=0
            fun show(profileId:String?=null) {
                revision++
                test.runOnMainSync {activity.setContent {
                    CompositionLocalProvider(LocalContext provides isolated,androidx.activity.compose.LocalActivityResultRegistryOwner provides activity) {
                        key(revision) {SftpFilesEntry(onClose={},profileId=profileId)}
                    }
                }}
            }
            show();ui.find(protected.name)
            check(ui.nodes().none {it.isVisibleToUser && it.isEditable && it.text?.toString()==text(R.string.pin)})
            check(accepted.get()==0)
            ui.click(protected.name);ui.find(text(R.string.pin),true)
            check(accepted.get()==0)
            ui.enter(R.string.pin,"0000");ui.click(text(R.string.unlock));ui.find(text(R.string.incorrect_pin))
            check(accepted.get()==0)
            ui.click(text(R.string.cancel));ui.find(plain.name)
            ui.click(plain.name);waitForCount(1)
            show();ui.find(protected.name);ui.click(protected.name)
            ui.enter(R.string.pin,"5827");ui.click(text(R.string.unlock));waitForCount(2)
            show(keyProfile.id);ui.find(text(R.string.pin),true);check(accepted.get()==2)
            ui.click(text(R.string.cancel));check(accepted.get()==2)
            show(protected.id);ui.find(text(R.string.pin),true);check(accepted.get()==2)
            ui.capture(activity,"sftp-selected-pin.png")
            ui.click(text(R.string.cancel));check(accepted.get()==2)
            return "PASS browser lists without PIN; unprotected target opens without PIN; protected credential/key and direct profile entry gate before any socket; wrong PIN/cancel open no transport; correct app PIN permits owned loopback attempt. Isolated profiles/PIN only; real SFTP transport and physical keyboard not asserted."
        } finally {
            test.runOnMainSync {activity?.finish()};test.removeMonitor(monitor)
            listener.close();server.join(2000);CredentialPin.clearCache()
            context.deleteSharedPreferences(namespace)
            check(context.getSharedPreferences("farside_profiles",0).all==original)
        }
    }
}
