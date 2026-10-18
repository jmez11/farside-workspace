package com.farside.workspace

import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.ResultReceiver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import metavrx.layout.compose.SpatialScene

/** Debug-only AAudio fixture using the real rdpsnd speaker, without a host or credentials. */
object SpatialAudioFixture {
    @JvmStatic external fun open(bridge: FreeRDPBridge): Long
    @JvmStatic external fun reopen(handle: Long): Boolean
    @JvmStatic external fun session(handle: Long): Int
    @JvmStatic external fun stats(handle: Long): LongArray
    @JvmStatic external fun mute(handle: Long, muted: Boolean)
    @JvmStatic external fun close(handle: Long)
}

open class SpatialAudioFixtureActivity : ComponentActivity() {
    private var handle = 0L
    private var bound = false
    private var serviceAccepted = false
    private val bridge = FreeRDPBridge()
    private var space: java.util.UUID? = null
    private val main = Handler(Looper.getMainLooper())
    private val commands: Messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            @Suppress("DEPRECATION")
            val reply = msg.data.getParcelable<ResultReceiver>("reply") ?: return
            when (msg.what) {
                1 -> reply.send(1, snapshot())
                2 -> { SpatialAudioFixture.mute(handle, msg.arg1 != 0); reply.send(1, snapshot()) }
                3 -> { finishAndRemoveTask(); reply.send(1, null) }
                4 -> { check(SpatialAudioFixture.reopen(handle)); reply.send(1, snapshot()) }
            }
        }
    })
    private fun snapshot() = Bundle().apply {
        putInt("pid", Process.myPid()); putInt("uid", Process.myUid())
        putString("process", Application.getProcessName()); putInt("task", taskId)
        putInt("session", if (handle == 0L) 0 else SpatialAudioFixture.session(handle))
        putLongArray("stats", if (handle == 0L) longArrayOf(0, 0) else SpatialAudioFixture.stats(handle))
        putBoolean("bound", bound); putBinder("commands", commands.binder)
        putBoolean("serviceAccepted", serviceAccepted)
        putBoolean("spaceAccepted", bridge.audioSpatialBound); putString("space", space?.toString())
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FreeRDPBridge()
        // A failed/interrupted remote instrumentation run must not leave a tone playing.
        main.postDelayed({ if (!isFinishing) finishAndRemoveTask() }, 60_000)
        @Suppress("DEPRECATION")
        val ready = requireNotNull(intent.getParcelableExtra<ResultReceiver>("fixtureReady"))
        setContent { SpatialScene { Text("Spatial audio diagnostic") } }
        var attempts = 0
        val start = object : Runnable {
            override fun run() {
                if (isDestroyed || isFinishing) return
                val kind = intent.getStringExtra("audioToken") ?: "space"
                val token = if (kind == "android") window.decorView.windowToken
                    else LegacySpatialAudioProbe.resolveVolumetricWindowToken(this@SpatialAudioFixtureActivity)
                if (token == null && attempts++ < 50) { main.postDelayed(this, 100); return }
                if (token == null) { ready.send(0, null); return }
                space = QuestSpatialAudio.windowSpace(this@SpatialAudioFixtureActivity)
                if (kind == "space") bridge.audioWindowSpace = checkNotNull(space)
                else if (kind != "framework") LegacySpatialAudioProbe.setDefaultVolumetricWindowToken(token)
                handle = SpatialAudioFixture.open(bridge)
                check(handle != 0L)
                if (kind != "space" && kind != "framework") bound = LegacySpatialAudioProbe.bindAudioSessionToWindow(SpatialAudioFixture.session(handle), token)
                if (kind != "space") serviceAccepted = LegacySpatialAudioProbe.associateSpatialAudio(SpatialAudioFixture.session(handle), token)
                main.postDelayed({ if (!isDestroyed) ready.send(1, snapshot()) }, 500)
            }
        }
        window.decorView.post(start)
    }
    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (handle != 0L) {
            QuestSpatialAudio.forget(SpatialAudioFixture.session(handle))
            SpatialAudioFixture.close(handle); handle = 0L
        }
        super.onDestroy()
    }
}
class SpatialAudioIsolatedActivity0 : SpatialAudioFixtureActivity()
class SpatialAudioIsolatedActivity1 : SpatialAudioFixtureActivity()
