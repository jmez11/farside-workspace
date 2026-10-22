package com.farside.workspace

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import com.farside.workspace.data.*
import com.farside.workspace.ui.setup.*

internal class SetupOptionsVerification(private val test:Instrumentation) {
    fun run():String {
        val context=test.targetContext
        val namespace="setup_phase3_owned_fixture"
        context.deleteSharedPreferences(namespace)
        val prefs=context.getSharedPreferences(namespace,Context.MODE_PRIVATE)
        val pm=ProfileManager(object:ContextWrapper(context) {override fun getSharedPreferences(name:String,mode:Int)=prefs})
        val pair=java.security.KeyPairGenerator.getInstance("RSA").apply {initialize(2048)}.generateKeyPair()
        val pem="-----BEGIN PRIVATE KEY-----\n"+android.util.Base64.encodeToString(pair.private.encoded,android.util.Base64.NO_WRAP).chunked(64).joinToString("\n")+"\n-----END PRIVATE KEY-----\n"
        val key=SavedSshKey(name="Owned key",privateKey=pem)
        pm.addSshKey(key)
        val rdp=ConnectionProfile("owned-rdp","Owned RDP",ConnectionType.RDP,"127.0.0.1",user="fixture",pass="fixture",soundRedirection=SoundRedirection.PLAY_ON_REMOTE,resolutionScaling=125)
        val ssh=rdp.copy(id="owned-ssh",name="Owned SSH key",type=ConnectionType.SSH,port=22,pass="",sshKeyId=key.id,enableSftp=true)
        val vnc=rdp.copy(id="owned-vnc",name="Owned VNC",type=ConnectionType.VNC,port=5900,vncAuthMode=VncAuthMode.MAC_USER)
        val legacyCredential=SavedCredential(name="Owned legacy login",user="fixture",pass="fixture")
        pm.addCredential(legacyCredential)
        val legacyKey=ssh.copy(id="owned-legacy-key",name="Owned legacy key login",credentialId=legacyCredential.id,pass=legacyCredential.pass)
        val fixtures=listOf(rdp,ssh,vnc,legacyKey)
        fixtures.forEach(pm::addProfile)
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
            for(fixture in fixtures) {
                var saved:ConnectionProfile?=null
                test.runOnMainSync {
                    activity.setContent {key(fixture.id) {
                        ConnectionSetupFlow(initialProfile=fixture,profileManager=pm,savedProfiles=fixtures,onCancel={},onSave={saved=it;pm.addProfile(it)})
                    }}
                }
                ui.find(text(R.string.edit_connection))
                ui.click("2 "+text(R.string.setup_server))
                check(ui.find(text(R.string.connection_name),true).text.toString()==fixture.name)
                check(ui.find(text(R.string.host_server),true).text.toString()==fixture.host)
                check(ui.find(text(R.string.setup_port),true).text.toString()==fixture.port.toString())
                ui.click("4 "+text(R.string.setup_review))
                when(fixture.type) {
                    ConnectionType.RDP -> {
                        ui.capture(activity,"setup-phase3-review.png")
                        ui.click(text(R.string.setup_play_sound))
                        ui.click("HTTP")
                        check(!ui.enabled(text(R.string.save_changes)))
                        ui.enter(R.string.proxy_hostname_or_ip,"127.0.0.1");ui.enter(R.string.proxy_port,"70000")
                        check(!ui.enabled(text(R.string.save_changes)))
                        ui.enter(R.string.proxy_port,"3128")
                        check(ui.enabled(text(R.string.save_changes)))
                    }
                    ConnectionType.SSH -> ui.click(text(R.string.auto_copy_selection_to_clipboard))
                    ConnectionType.VNC -> {ui.click(text(R.string.setup_none));ui.click(ssh.name)}
                }
                ui.click(text(R.string.save_changes))
                val result=checkNotNull(saved)
                val loaded=pm.getProfiles().single {it.id==fixture.id}
                check(result.id==fixture.id && loaded.host==fixture.host && loaded.user==fixture.user)
                when(fixture.type) {
                    ConnectionType.RDP -> check(loaded.soundRedirection==SoundRedirection.DO_NOT_PLAY && loaded.proxyType==ProxyType.HTTP && loaded.proxyPort==3128 && loaded.resolutionScaling==125)
                    ConnectionType.SSH -> check(!loaded.autoCopySelection && loaded.sshKeyId==key.id &&
                        (if(fixture.credentialId==null) loaded.pass.isEmpty() else loaded.credentialId==fixture.credentialId && loaded.pass==fixture.pass))
                    ConnectionType.VNC -> check(loaded.sftpPairProfileId==ssh.id && loaded.pass==fixture.pass)
                }
            }
            test.runOnMainSync {activity.setContent {androidx.compose.material3.MaterialTheme {SshKeySheet(pm,existing=key,onDismiss={},onSaved={})}}}
            check(ui.find(text(R.string.private_key),true).text.isNullOrEmpty()) {"Saved private key rendered in edit field"}
            check(ui.find(text(R.string.passphrase_optional),true).text.isNullOrEmpty())
            ui.find(text(R.string.setup_key_keep))
            val badCloudflare=vnc.copy(proxyType=ProxyType.CLOUDFLARED,host="https://invalid.example/path")
            check(setupOptionsError(context,badCloudflare,fixtures)!=null)
            val chainA=ssh.copy(id="cycle-a",jumpHostProfileId="cycle-b")
            val chainB=ssh.copy(id="cycle-b",jumpHostProfileId="cycle-a")
            check(setupOptionsError(context,chainA,listOf(chainA,chainB))!=null)
            return "PASS Phase3 actual edit flow RDP/VNC/SSH-key prefill, option changes/save/reload and hidden-setting retention; proxy host/port Save gating, Cloudflare hostname and jump-cycle validation; plain SSH pairing names; existing private-key/passphrase edit fields empty. Owned isolated preferences; physical permission prompts not asserted."
        } finally {
            test.runOnMainSync {activity?.finish()};test.removeMonitor(monitor);context.deleteSharedPreferences(namespace)
        }
    }
}
