package com.multidesk.explorer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import com.multidesk.explorer.data.ConnectionType
import com.multidesk.explorer.data.ProfileManager

class SessionActivity : ComponentActivity() {
    private val windowInteraction = androidx.compose.runtime.mutableStateOf(0L)
    private val movementObserver = WindowMovementObserver(this) { windowInteraction.value += 1L }

    override fun onPostResume() {
        super.onPostResume()
        window.decorView.post { if (!isDestroyed) movementObserver.start() }
    }

    override fun onDestroy() {
        movementObserver.close()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // System window affordances may take focus while moving/resizing the panel.
        windowInteraction.value += 1L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        
        val profileId = intent.getStringExtra("profileId")
        if (profileId == null) {
            finish()
            return
        }
        
        val profileManager = ProfileManager(this)
        val profile = profileManager.getProfiles().find { it.id == profileId }
        
        if (profile == null) {
            finish()
            return
        }
        setTitle(profile.name)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            setTaskDescription(android.app.ActivityManager.TaskDescription(profile.name))
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.ui.graphics.Color.Transparent
                ) {
                    if (profile.type == ConnectionType.RDP) {
                        RdpSessionScreen(
                            session = profile,
                            windowInteraction = windowInteraction.value,
                            onInstanceCreated = { /* Handle instance creation if needed */ },
                            onClose = { finish() }
                        )
                    } else {
                        SshSessionScreen(
                            session = profile,
                            onClose = { finish() }
                        )
                    }
                }
            }
        }
    }
}
