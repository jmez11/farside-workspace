package com.farside.workspace

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import com.farside.workspace.data.*
import com.farside.workspace.ui.LauncherScreen
import com.farside.workspace.ui.setup.SignInVault
import java.io.File

internal class SetupVaultVerification(private val test:Instrumentation) {
    fun run():String {
        val context=test.targetContext
        val namespace="setup_vault_owned_fixture"
        context.deleteSharedPreferences(namespace)
        val prefs=context.getSharedPreferences(namespace,Context.MODE_PRIVATE)
        val originals=context.getSharedPreferences("farside_profiles",0).all.toMap()
        val scratch=File(context.cacheDir,"setup-vault-${java.util.UUID.randomUUID()}").apply {mkdirs()}
        check(scratch.canonicalPath.startsWith(context.cacheDir.canonicalPath+File.separator))
        val isolated=object:ContextWrapper(context) {
            override fun getSharedPreferences(name:String,mode:Int)=prefs
            override fun getFilesDir()=scratch
        }
        val pm=ProfileManager(isolated)
        val mac=SavedCredential(name="Owned Mac login",user="Full Fixture",userShort="shortfixture",pass="owned-secret")
        val password=SavedCredential(name="Owned VNC password",user="",pass="fixture")
        pm.addCredential(mac);pm.addCredential(password)
        val pair=java.security.KeyPairGenerator.getInstance("RSA").apply {initialize(2048)}.generateKeyPair()
        val pem="-----BEGIN PRIVATE KEY-----\n"+android.util.Base64.encodeToString(pair.private.encoded,android.util.Base64.NO_WRAP).chunked(64).joinToString("\n")+"\n-----END PRIVATE KEY-----\n"
        val key=SavedSshKey(name="Owned vault key",privateKey=pem)
        pm.addSshKey(key)
        val base=ConnectionProfile("owned-rdp","Owned RDP",ConnectionType.RDP,"127.0.0.1",user="",pass="",credentialId=mac.id)
        pm.addProfile(base)
        pm.addProfile(base.copy(id="owned-vnc",type=ConnectionType.VNC,vncAuthMode=VncAuthMode.MAC_USER,port=5900))
        pm.addProfile(base.copy(id="owned-ssh",type=ConnectionType.SSH,port=22,credentialId=null,user="fixture",sshKeyId=key.id,enableSftp=true))
        pm.addProfile(base.copy(id="owned-inline",type=ConnectionType.VNC,port=5900,credentialId=null,user="",pass="inline",vncAuthMode=VncAuthMode.VNC_PASSWORD))
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
            fun vault() {test.runOnMainSync {activity.setContent {CompositionLocalProvider(LocalContext provides isolated, androidx.activity.compose.LocalActivityResultRegistryOwner provides activity) {SignInVault(pm,onClose={})}}}}
            vault()
            ui.find(text(R.string.setup_vault));check(ui.nodes().none {it.contentDescription?.toString()==text(R.string.setup_vault_search)})
            Thread.sleep(500)
            ui.capture(activity,"setup-phase4-vault.png")
            if(test.arguments?.getString("vaultCaptureOnly")=="true") return "PASS settled owned vault capture"
            ui.click(context.getString(R.string.setup_edit_named,mac.name))
            check(ui.find(text(R.string.password),true).text.isNullOrEmpty())
            check(ui.find(text(R.string.setup_short_name),true).text.toString()=="shortfixture")
            ui.enter(R.string.account_display_name,"Owned Mac edited")
            ui.enter(R.string.setup_short_name,"override-fixture")
            ui.click(text(R.string.save_changes))
            check(pm.getCredentials().single {it.id==mac.id}.let {it.name=="Owned Mac edited" && it.pass==mac.pass && it.userShort=="override-fixture"})
            ui.click(context.getString(R.string.setup_edit_named,key.name))
            check(ui.find(text(R.string.private_key),true).text.isNullOrEmpty())
            ui.enter(R.string.key_display_name,"Owned vault key edited")
            ui.click(text(R.string.save_changes))
            val deadline=android.os.SystemClock.uptimeMillis()+10000
            while(pm.getSshKeys().single().name==key.name && android.os.SystemClock.uptimeMillis()<deadline) Thread.sleep(100)
            val edited=pm.getSshKeys().single()
            check(edited.name=="Owned vault key edited" && edited.privateKey==pem)
            ui.click(text(R.string.ssh_public_key_title))
            val public=deriveSshPublicKey(edited.privateKey,edited.passphrase,edited.name)
            ui.find(public.fingerprint)
            check(ui.nodes().none {it.text?.toString()?.contains("BEGIN PRIVATE KEY")==true})
            ui.click(text(R.string.close))
            ui.click(text(R.string.setup_new_sign_in))
            ui.click(text(R.string.setup_vnc_password))
            ui.enter(R.string.account_display_name,"Owned created password")
            ui.enter(R.string.setup_vnc_password,"fixture")
            // Fresh protection is configured directly from the unsaved credential sheet.
            check(pm.getCredentials().none {it.name=="Owned created password"})
            ui.click(text(R.string.require_pin))
            ui.find(text(R.string.setup_set_pin))
            check(ui.nodes().none {it.text?.toString()==text(R.string.setup_use_headset_pin)})
            ui.click(text(R.string.cancel))
            check(pm.getAppPinVerifier()==null)
            ui.click(text(R.string.require_pin))
            ui.enter(R.string.enter_new_app_pin,"5827");ui.enter(R.string.setup_confirm_pin,"5827");ui.click(text(R.string.save_pin))
            check(CredentialPin.matches("5827",checkNotNull(pm.getAppPinVerifier())))
            ui.click(text(R.string.save_changes))
            check(pm.getCredentials().single {it.name=="Owned created password"}.requirePin)
            ui.click(text(R.string.change_app_pin));ui.enter(R.string.pin,"5827");ui.click(text(R.string.unlock))
            ui.enter(R.string.enter_new_app_pin,"6928");ui.enter(R.string.setup_confirm_pin,"6928");ui.click(text(R.string.save_pin))
            check(CredentialPin.matches("6928",checkNotNull(pm.getAppPinVerifier())))
            ui.click(text(R.string.remove_app_pin));ui.enter(R.string.pin,"6928");ui.click(text(R.string.unlock))
            val removeDeadline=android.os.SystemClock.uptimeMillis()+7000
            while(pm.getAppPinVerifier()!=null && android.os.SystemClock.uptimeMillis()<removeDeadline) Thread.sleep(100)
            check(pm.getAppPinVerifier()==null)
            ui.click(context.getString(R.string.setup_edit_named,"Owned vault key edited"))
            ui.click(text(R.string.require_pin));ui.find(text(R.string.setup_set_pin))
            ui.click(text(R.string.cancel));ui.click(text(R.string.save_changes))
            check(pm.getAppPinVerifier()==null && !pm.getSshKeys().single().requirePin)
            // Owned URI round-trip uses the exact production exporter/importer and local Keystore.
            pm.setAppPinVerifier(CredentialPin.create("5827"))
            val photo=File(scratch,"card-backgrounds/owned.jpg").apply {
                parentFile!!.mkdirs()
                val image=android.graphics.Bitmap.createBitmap(8,8,android.graphics.Bitmap.Config.ARGB_8888)
                image.eraseColor(0xff554477.toInt());outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.JPEG,85,it)};image.recycle()
            }
            val expectedPhoto=photo.readBytes()
            pm.addProfile(base.copy(cardBackground=photo.name))
            val uri=android.net.Uri.fromFile(File(scratch,"owned.fsbk"))
            val phrase="owned-backup-passphrase".toCharArray()
            BackupManager.exportToUri(isolated,pm,uri,phrase)
            pm.getProfiles().map {it.id}.forEach(pm::deleteProfile)
            pm.getCredentials().map {it.id}.forEach(pm::deleteCredential)
            pm.getSshKeys().map {it.id}.forEach(pm::deleteSshKey)
            pm.setAppPinVerifier(null)
            val restored=BackupManager.importFromUri(isolated,pm,uri,phrase)
            check(restored.profilesRestored==4 && restored.credentialsRestored==3 && restored.sshKeysRestored==1 && restored.pinRestored && restored.desktopImagesRestored==1)
            check(pm.getCredentials().single {it.id==mac.id}.userShort=="override-fixture")
            check(pm.getProfiles().single {it.id=="owned-inline"}.let {it.credentialId==null && it.pass=="inline"})
            check(photo.readBytes().contentEquals(expectedPhoto))
            phrase.fill('\u0000')
            test.runOnMainSync {activity.setContent {CompositionLocalProvider(LocalContext provides isolated, androidx.activity.compose.LocalActivityResultRegistryOwner provides activity) {
                LauncherScreen(profiles=pm.getProfiles(),profileManager=pm,onAddProfile={},onDeleteProfile={},onConnectProfile={})
            }}}
            ui.click(text(R.string.setup_vault));ui.find(text(R.string.setup_close_vault));ui.click(text(R.string.setup_close_vault))
            ui.click(text(R.string.setup_info));ui.find(text(R.string.open_source_licenses));ui.capture(activity,"about-screen.png")
            ui.find(text(R.string.clear_trusted_certificates));ui.capture(activity,"about-actions.png")
            check(ui.nodes().none {it.text?.toString()==text(R.string.setup_vault_protection)})
            return "PASS compact search-free vault/edit and immediate require-PIN setup/cancel, Mac fields and replace-only password/key, public-key view, inline VNC-password creation, real app-PIN set/change/remove dialogs, authenticated encrypted URI backup/import with profiles/short names/keys/PIN/photos/inline VNC, launcher Vault and Info entries. Isolated preferences/files only; physical headset-passcode and SAF-picker UI remain manual."
        } finally {
            test.runOnMainSync {activity?.finish()};test.removeMonitor(monitor)
            context.deleteSharedPreferences(namespace)
            check(scratch.canonicalPath.startsWith(context.cacheDir.canonicalPath+File.separator));scratch.deleteRecursively()
            check(context.getSharedPreferences("farside_profiles",0).all==originals) {"Vault fixture changed real profile/PIN preferences"}
        }
    }
}
