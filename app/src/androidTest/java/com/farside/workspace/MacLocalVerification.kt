package com.farside.workspace

import android.app.Instrumentation
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.farside.workspace.data.*
import java.io.File
import java.net.InetAddress
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** Explicit local-profile transfer and direct-media isolation; no SSH or credential guessing. */
internal class MacLocalVerification(private val test: Instrumentation) {
    private val context get()=test.targetContext
    private fun file(name:String)=File(context.cacheDir,"mac-local-$name")
    fun setupSsh(id:String):String {
        val pm=ProfileManager(context)
        val p=pm.getProfiles().single { it.id==id && it.name=="LadySif" && it.type==ConnectionType.VNC }
        require(p.proxyType==ProxyType.NONE && !p.useCloudflare)
        val credential=pm.getCredentials().single { it.id==p.credentialId }
        require(credential.user=="james") { "Saved local credential does not match explicitly supplied short account" }
        require(pm.getProfiles().none { it.type==ConnectionType.SSH && it.host==p.host })
        pm.addProfile(ConnectionProfile(java.util.UUID.randomUUID().toString(),"LadySif SSH",ConnectionType.SSH,p.host,22,"","",credentialId=credential.id))
        check(context.getSharedPreferences("farside_profiles",0).edit().commit())
        return "PASS local debug SSH profile added using existing credential; no remote settings changed"
    }
    fun transfer(mode:String,id:String?):String {
        val keyFile=file("key")
        if(mode=="localCopyPrepare") {
            keyFile.writeBytes(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
            return "PASS one-use transfer key prepared in private cache"
        }
        val key=keyFile.readBytes();require(key.size==32)
        try {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            val pm=ProfileManager(context)
            if(mode=="localCopyExport") {
                val p=pm.getProfiles().single { it.id==id && it.name=="LadySif" && it.type==ConnectionType.VNC }
                require(!p.useCloudflare && p.proxyType==ProxyType.NONE)
                val c=pm.getCredentials().single { it.id==p.credentialId }
                val credential=c.toJson().apply { remove("passEncrypted");put("pass",c.pass) }
                val profile=p.toJson().apply { remove("passEncrypted");remove("pass") }
                val payload=JSONObject().put("profile",profile).put("credential",credential).toString().toByteArray()
                cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"))
                file("transfer").writeBytes(cipher.iv+cipher.doFinal(payload));payload.fill(0)
                return "PASS selected local profile encrypted for destination; no credential output"
            }
            require(mode=="localCopyImport")
            val bytes=file("transfer").readBytes();require(bytes.size in 29..65536)
            cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
            val plaintext=cipher.doFinal(bytes.copyOfRange(12,bytes.size))
            val payload=JSONObject(String(plaintext,Charsets.UTF_8));plaintext.fill(0)
            val p=ConnectionProfile.fromJson(payload.getJSONObject("profile"))
            val c=SavedCredential.fromJson(payload.getJSONObject("credential"))
            require(p.id==id && p.name=="LadySif" && p.type==ConnectionType.VNC && p.credentialId==c.id)
            require(pm.getProfiles().none { it.id==p.id } && pm.getCredentials().none { it.id==c.id })
            pm.saveCredentials(pm.getCredentials()+c)
            pm.saveProfiles(pm.getProfiles()+p)
            // Both calls use apply(); commit a barrier before instrumentation process exits.
            check(context.getSharedPreferences("farside_profiles",0).edit().commit())
            check(pm.getCredentials().single { it.id==c.id }.pass==c.pass)
            return "PASS local profile and credential copied; destination encryption round-trip verified"
        } finally { key.fill(0);keyFile.delete();if(mode=="localCopyImport") file("transfer").delete() }
    }

    fun direct(id:String,baselineOnly:Boolean=false,production:Boolean=false):String {
        test.uiAutomation.adoptShellPermissionIdentity("android.permission.ACCESS_NETWORK_STATE","android.permission.READ_CLIPBOARD_IN_BACKGROUND")
        val output=file("results.txt");output.writeText("")
        fun record(s:String) { output.appendText("${System.currentTimeMillis()}: $s\n") }
        val pm=ProfileManager(context)
        val p=pm.getProfiles().single { it.id==id && it.name=="LadySif" && it.type==ConnectionType.VNC }
        require(!p.useCloudflare && p.proxyType==ProxyType.NONE && p.macSshRelayProfileId==null && !p.reconnectAutomatically)
        val c=pm.getCredentials().single { it.id==p.credentialId }
        require(c.user.isNotBlank() && c.pass.isNotEmpty())
        val cm=context.getSystemService(ConnectivityManager::class.java)
        val active=cm.activeNetwork?:error("No active network")
        val caps=cm.getNetworkCapabilities(active)?:error("No network capabilities")
        check(!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) { "VPN still active: direct isolation requires VPN disconnected" }
        check(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) { "Expected local Wi-Fi" }
        val address=InetAddress.getAllByName(p.host).first { it is java.net.Inet4Address }
        check(address.isSiteLocalAddress) { "Local profile resolved outside private LAN" }
        file("endpoint.txt").writeText(address.hostAddress.orEmpty())
        record("PASS active Wi-Fi without VPN; local IPv4 resolved; direct TCP/UDP, no SSH relay")
        val last=File(context.cacheDir,"mac-hevc-last-auth.txt")
        fun space() { val prior=last.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()?:0;val wait=5000-(System.currentTimeMillis()-prior);if(wait>0) Thread.sleep(wait);last.writeText(System.currentTimeMillis().toString()) }
        fun baseline(label:String) {
            space()
            val result=VncVerification(test,liveFrameTimeoutMs=45000).run("live",id)
            check(result.contains("LIVE PASS"));record("PASS $label standard VNC nonblank baseline; no unlock input")
        }
        baseline("before")
        if(baselineOnly) return output.readText()
        space()
        var rejected=false
        var failure:Exception?=null
        try {
            if(production) {
                MacMediaSurface(test,p.copy(expMacHevc=true,macPreferAvc=true,macAudioEnabled=false,macSshRelayProfileId=null)).use { display ->
                    check(display.awaitConnection()) { "Production connection failed; stop further authentication" }
                    repeat(20) { Thread.sleep(1000);display.capture("mac-local-production-$it.png") }
                    val pipeline=checkNotNull(display.bridge?.macMediaPipeline)
                    record("PRODUCTION DIRECT frames=${pipeline.framesDecoded.get()} authenticated=${pipeline.authenticatedPackets.get()} decoder=${pipeline.decoderDescription}; ${pipeline.avcAssemblyDiagnostics}")
                    check(pipeline.framesDecoded.get()>0 && display.capture("mac-local-production-final.png")) { "No nonblank production hardware image" }
                }
            } else MacMediaSurface(test).use { display ->
                MacHevcSession(address.hostAddress!!,p.port,c.user,c.pass,sessionUsername=c.user,
                    authenticationSpacingMs=5000,preferAvc=true,audioEnabled=false,
                    logCallback={ line ->
                        if(line.contains("Received SessionSelect reply") && !line.contains("status=0)")) rejected=true
                        record(line.replace(Regex("(activeUser|user)='[^']*'"),"$1='<redacted>'"))
                    }).use { session ->
                    session.setSurface(display.surface)
                    val first=session.start();session.initStreamingLayer(first);session.startStreaming(display.surface)
                    repeat(20) { Thread.sleep(1000);display.capture("mac-local-frame-$it.png") }
                    record("DIRECT MEDIA frames=${session.videoPipeline?.framesDecoded?.get()} authenticated=${session.videoPipeline?.authenticatedPackets?.get()} decoder=${session.videoPipeline?.decoderDescription}; ${session.videoPipeline?.avcAssemblyDiagnostics}")
                    check((session.videoPipeline?.framesDecoded?.get()?:0)>0) { "No hardware frames" }
                    check(display.capture("mac-local-final.png")) { "No nonblank hardware image" }
                }
            }
        } catch(e:Exception) { failure=e;record("FAILED ${e.javaClass.simpleName}: ${e.message}") }
        last.writeText(System.currentTimeMillis().toString())
        check(!rejected && failure !is VncAuthenticationException) { "Authentication rejected: stop new attempts" }
        baseline("after")
        failure?.let { throw it }
        record("PASS direct media and surrounding baselines; inspect private captures for account stability")
        return output.readText()
    }
}
