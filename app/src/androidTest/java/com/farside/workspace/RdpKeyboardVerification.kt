package com.farside.workspace

import android.content.Intent
import android.os.SystemClock
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.ProfileManager
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Real Windows receipt, rather than mocked IME callbacks or successful JNI calls. */
internal class RdpKeyboardVerification(private val test: RdpResizeInstrumentation,
    private val acceptNewCertificate:Boolean=false) {
    fun run(): String {
        val manager = ProfileManager(test.targetContext)
        val original = checkNotNull(manager.getProfiles().firstOrNull {
            it.type == ConnectionType.RDP && it.remoteApp.isNullOrBlank()
        }) { "First saved desktop RDP connection required" }
        val profile = original.copy(id=UUID.randomUUID().toString(), name="Keyboard verification",
            cardBackground=null, redirectStorage=true, enableCamera=false, redirectMicrophone=false)
        val root = File(test.targetContext.getExternalFilesDir(null), "keyboard/${UUID.randomUUID()}").apply { check(mkdirs()) }
        File(root,"probe.ps1").writeText(test.context.assets.open("keyboard/probe.ps1").bufferedReader().use {it.readText()})
        val remote = "\\\\tsclient\\Quest" + root.absolutePath.removePrefix("/sdcard")
            .removePrefix("/storage/emulated/0").replace('/','\\')
        var activity: SessionActivity? = null
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var pump: Runnable? = null
        val evidence = File(test.targetContext.cacheDir,"rdp-keyboard-results.txt")
        evidence.writeText("Actual saved Windows RDP connection; owned finite WinForms receiver.\n")
        manager.addProfile(profile)
        try {
            activity = test.startActivitySync(Intent(test.targetContext,SessionActivity::class.java)
                .putExtra("profileId",profile.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SessionActivity
            val session = activity
            try {
                await("RDP connected") {
                    session.rdpMonitor?.session?.certificateRequest?.value?.let {request ->
                        if(acceptNewCertificate && !request.changed && request.host==original.host && request.port==original.port)
                            request.respond(remember=true)
                    }
                    session.rdpInstance != 0L
                }
                await("RDP input ready") {session.rdpMonitor?.session?.inputReady == true}
            } catch(error:Throwable) {
                val bitmap=android.graphics.Bitmap.createBitmap(session.window.decorView.width,session.window.decorView.height,android.graphics.Bitmap.Config.ARGB_8888)
                val latch=java.util.concurrent.CountDownLatch(1)
                test.runOnMainSync {android.view.PixelCopy.request(session.window,bitmap,{latch.countDown()},handler)}
                latch.await(5,java.util.concurrent.TimeUnit.SECONDS)
                File(test.targetContext.cacheDir,"rdp-keyboard-startup.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
                evidence.appendText("Startup: monitor=${session.rdpMonitor!=null} instance=${session.rdpInstance!=0L} certificate=${session.rdpMonitor?.session?.certificateRequest?.value!=null}\n")
                throw error
            }
            val bridge = checkNotNull(session.rdpBridge)
            val handle = session.rdpInstance
            fun key(code:Int) { bridge.sendKeyEvent(handle,code,true); bridge.sendKeyEvent(handle,code,false) }
            fun control(code:Int) { bridge.sendKeyEvent(handle,0x1d,true); key(code); bridge.sendKeyEvent(handle,0x1d,false) }
            fun unicode(text:String) {text.forEach {bridge.sendUnicodeKeyEvent(handle,it.code,true);bridge.sendUnicodeKeyEvent(handle,it.code,false)}}
            // Bootstrap through scancodes: this must still work when Unicode is disabled.
            fun ascii(text:String) {
                val plain = "1234567890-=qwertyuiop[]asdfghjkl;'`zxcvbnm,./"
                val shifted = "!@#$%^&*()_+QWERTYUIOP{}ASDFGHJKL:\"~ZXCVBNM<>?"
                val codes = intArrayOf(2,3,4,5,6,7,8,9,10,11,12,13,16,17,18,19,20,21,22,23,24,25,26,27,30,31,32,33,34,35,36,37,38,39,40,41,44,45,46,47,48,49,50,51,52,53)
                for(c in text) {
                    if(c==' ') key(0x39)
                    else if(c=='\\') key(0x2b)
                    else {
                        val normal=plain.indexOf(c); val shift=normal<0
                        val index=if(shift) shifted.indexOf(c) else normal
                        check(index>=0) {"Unsupported bootstrap character"}
                        if(shift) bridge.sendKeyEvent(handle,0x2a,true)
                        key(codes[index])
                        if(shift) bridge.sendKeyEvent(handle,0x2a,false)
                    }
                    Thread.sleep(8)
                }
            }
            // A cold Windows sign-in can present GFX before Explorer accepts Win+R.
            Thread.sleep(7000)
            key(1); bridge.sendKeyEvent(handle,0x15b,true); key(0x13); bridge.sendKeyEvent(handle,0x15b,false)
            Thread.sleep(800); control(0x1e)
            ascii("powershell.exe -NoProfile -STA -ExecutionPolicy Bypass -File \"$remote\\probe.ps1\"")
            key(0x1c)
            try {
                await("owned Windows keyboard receiver ready") {File(root,"ready.txt").exists()}
            } catch(error:Throwable) {
                capture(session,"rdp-keyboard-bootstrap.png")
                throw error
            }
            var serial=0
            fun expect(label:String, expected:String) {
                val id=(++serial).toString()
                File(root,"request.txt").writeText(id)
                var last:JSONObject?=null
                await("$label (remote text mismatch)",12000) {
                    last=runCatching {JSONObject(File(root,"response.json").readText())}.getOrNull()
                    last?.optString("id")==id && last?.optString("text")==expected
                }
                check(last?.getBoolean("focused")==true) {"Owned receiver lost focus: $label"}
                evidence.appendText("PASS: $label; exact ${expected.length} UTF-16 units received on Windows.\n")
            }
            unicode("Farside Aa19!? Ω中é")
            expect("native Unicode down/up", "Farside Aa19!? Ω中é")
            check(RdpTransportVerification.unicodeEnabled(handle)) {"Negotiated Unicode input disabled"}
            control(0x1e); key(0x0e); expect("physical Ctrl+A and Backspace", "")

            revealSessionToolbar(test,session)
            tapSessionControl(test,session,test.targetContext.getString(R.string.keyboard))
            pump=object:Runnable { override fun run() {
                val decor=session.window.decorView
                decor.rootWindowInsets?.let {insets ->
                    decor.dispatchApplyWindowInsets(WindowInsets.Builder(insets)
                        .setVisible(WindowInsets.Type.ime(),true).build())
                }
                handler.postDelayed(this,100)
            }}
            handler.post(pump!!)
            await("production Compose keyboard editor") {session.keyboardEditorView != null}
            Thread.sleep(600)
            lateinit var input:InputConnection
            test.runOnMainSync {
                val info=EditorInfo()
                input=checkNotNull(session.keyboardEditorView?.onCreateInputConnection(info))
                verifyRemoteImeOptions(info)
                check(input.commitText("Aa19!? Ω中é",1))
            }
            var expected="Aa19!? Ω中é"
            expect("real Compose InputConnection commitText",expected)
            test.runOnMainSync {check(input.setComposingText("b",1))}
            expect("live composing text",expected+"b")
            test.runOnMainSync {
                val end=checkNotNull(input.getExtractedText(android.view.inputmethod.ExtractedTextRequest(),0)).text.length
                check(input.replaceText(end-1,end,"/",1,null))
                check(input.finishComposingText())
            }
            expected+="/"; expect("IME alternative replacement and finish",expected)
            test.runOnMainSync { check(input.deleteSurroundingText(1,0)) }
            expected=expected.dropLast(1); expect("IME Backspace",expected)
            test.runOnMainSync {check(input.performEditorAction(EditorInfo.IME_ACTION_DONE))}
            expected+="\r\n"; expect("IME editor action Return",expected)
            val burst="0123456789".repeat(16)
            test.runOnMainSync {check(input.commitText(burst,1))}
            expected+=burst; expect("rapid 160-character IME commit",expected)
            test.runOnMainSync {check(input.commitText("\uD83D\uDE00",1))}
            expected+="\uD83D\uDE00"; expect("IME Unicode surrogate pair",expected)
            key(0x0f); expected+="\t"; expect("physical Tab",expected)
            bridge.sendKeyEvent(handle,0x2a,true); key(0x1e); bridge.sendKeyEvent(handle,0x2a,false)
            expected+="A"; expect("physical Shift+A",expected)
            await("companion keyboard editor") {session.keyboardPanelEditorView!=null}
            test.runOnMainSync {
                val info=EditorInfo()
                val panel=checkNotNull(session.keyboardPanelEditorView?.onCreateInputConnection(info))
                verifyRemoteImeOptions(info)
                check(panel.commitText(" panel Ω",1))
            }
            expected+=" panel Ω"; expect("real companion InputConnection",expected)
            revealSessionToolbar(test,session)
            tapSessionControl(test,session,test.targetContext.getString(R.string.keyboard))
            await("keyboard closed") {session.keyboardEditorView==null}
            revealSessionToolbar(test,session)
            tapSessionControl(test,session,test.targetContext.getString(R.string.keyboard))
            await("keyboard reopened") {session.keyboardEditorView!=null}
            Thread.sleep(500)
            test.runOnMainSync {
                val info=EditorInfo()
                input=checkNotNull(session.keyboardEditorView?.onCreateInputConnection(info))
                verifyRemoteImeOptions(info)
                check(input.commitText(" reopen",1))
            }
            expected+=" reopen"; expect("typing after keyboard close/reopen",expected)
            for(mode in listOf("key-event","surrounding","code-points","physical")) {
                Thread.sleep(250) // Each Return is a distinct user action, beyond duplicate debounce.
                test.runOnMainSync {check(input.performEditorAction(EditorInfo.IME_ACTION_DONE))}
                expected+="\r\n"; expect("$mode domain setup Return",expected)
                test.runOnMainSync {check(input.commitText("Youtube ",1))}
                expected+="Youtube "; expect("$mode domain with trailing space",expected)
                test.runOnMainSync {
                    verifyRemoteImeBuffer(input," Youtube ")
                    when(mode) {
                        "key-event" -> for(action in listOf(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.ACTION_UP))
                            check(input.sendKeyEvent(android.view.KeyEvent(action,android.view.KeyEvent.KEYCODE_DEL)))
                        "surrounding" -> check(input.deleteSurroundingText(1,0))
                        "code-points" -> check(input.deleteSurroundingTextInCodePoints(1,0))
                        else -> for(action in listOf(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.ACTION_UP))
                            check(session.dispatchKeyEvent(android.view.KeyEvent(action,android.view.KeyEvent.KEYCODE_DEL)))
                    }
                }
                expected=expected.dropLast(1); expect("$mode trailing space deleted",expected)
                test.runOnMainSync {verifyRemoteImeBuffer(input," Youtube")}
                var suffix=""
                for(character in ".com") {
                    suffix+=character
                    test.runOnMainSync {check(input.commitText(character.toString(),1))}
                    expected+=character; expect("$mode incremental suffix $suffix",expected)
                    test.runOnMainSync {verifyRemoteImeBuffer(input," Youtube$suffix")}
                }
            }
            Thread.sleep(250)
            test.runOnMainSync {
                val info=EditorInfo()
                input=checkNotNull(session.keyboardPanelEditorView?.onCreateInputConnection(info))
                verifyRemoteImeOptions(info)
                check(input.performEditorAction(EditorInfo.IME_ACTION_DONE))
            }
            expected+="\r\n"; expect("companion domain setup Return",expected)
            test.runOnMainSync {check(input.commitText("Youtube ",1))}
            expected+="Youtube "; expect("companion domain with trailing space",expected)
            test.runOnMainSync {
                for(action in listOf(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.ACTION_UP))
                    check(input.sendKeyEvent(android.view.KeyEvent(action,android.view.KeyEvent.KEYCODE_DEL)))
            }
            expected=expected.dropLast(1); expect("companion trailing space deleted",expected)
            test.runOnMainSync {verifyRemoteImeBuffer(input," Youtube")}
            var suffix=""
            for(c in ".com") {
                suffix+=c
                test.runOnMainSync {check(input.commitText(c.toString(),1))}
                expected+=c; expect("companion incremental suffix $suffix",expected)
                test.runOnMainSync {verifyRemoteImeBuffer(input," Youtube$suffix")}
            }
            return evidence.readText()+"Artifacts: ${root.absolutePath}; IME visibility/focus synthetic."
        } finally {
            pump?.let {handler.removeCallbacks(it)}
            File(root,"done.txt").writeText("done")
            // Wait for finite owned receiver cleanup before disconnecting its redirected drive.
            val cleanup=runCatching {
                if(File(root,"ready.txt").exists()) await("owned receiver stopped",10000) {File(root,"stopped.txt").exists()}
            }
            activity?.let {test.runOnMainSync {it.finish()}}
            Thread.sleep(700)
            manager.deleteProfile(profile.id)
            check(test.targetContext.getSharedPreferences("farside_profiles",0).edit().commit())
            cleanup.getOrThrow()
        }
    }

    private fun await(label:String, timeout:Long=45000, predicate:()->Boolean) {
        val end=SystemClock.uptimeMillis()+timeout
        while(!predicate() && SystemClock.uptimeMillis()<end) Thread.sleep(100)
        check(predicate()) {"Timed out: $label"}
    }

    private fun capture(activity:SessionActivity,name:String) {
        val bitmap=android.graphics.Bitmap.createBitmap(activity.window.decorView.width,activity.window.decorView.height,android.graphics.Bitmap.Config.ARGB_8888)
        val done=java.util.concurrent.CountDownLatch(1)
        test.runOnMainSync {android.view.PixelCopy.request(activity.window,bitmap,{done.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))}
        check(done.await(5,java.util.concurrent.TimeUnit.SECONDS))
        File(test.targetContext.cacheDir,name).outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
}
