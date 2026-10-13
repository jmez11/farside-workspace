package com.farside.workspace

import android.os.Bundle
import android.util.Log
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Rect
import android.os.SystemClock
import android.view.ViewConfiguration
import android.view.MotionEvent
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Keyboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.ExperimentalComposeUiApi

import metavrx.layout.compose.SpatialScene
import metavrx.layout.window.compose.SpatialWindow
import metavrx.layout.window.compose.layout.WindowModifier
import metavrx.layout.window.compose.layout.size
import android.content.Intent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.ProfileManager
import com.farside.workspace.ui.LauncherScreen
import java.util.UUID

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.platform.LocalSoftwareKeyboardController


class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionWindowLauncher.forgetLegacyPositions(this)
        val profileManager = ProfileManager(this)
        val arrangementStore = com.farside.workspace.data.LauncherArrangementStore(this)
        
        setContent {
            var profiles by remember { mutableStateOf(profileManager.getProfiles()) }
            var arrangement by remember { mutableStateOf(arrangementStore.load()) }

            val refreshProfiles = { profiles = profileManager.getProfiles() }
            DisposableEffect(profileManager) {
                val preferences = getSharedPreferences("farside_profiles", android.content.Context.MODE_PRIVATE)
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == "profiles") refreshProfiles()
                }
                preferences.registerOnSharedPreferenceChangeListener(listener)
                onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
            }

            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LauncherScreen(
                        profiles = arrangement.arrange(profiles),
                        onAddProfile = {
                            profileManager.addProfile(it)
                            refreshProfiles()
                        },
                        onDeleteProfile = {
                            profileManager.deleteProfile(it)
                            arrangement = arrangementStore.remove(it)
                            refreshProfiles()
                        },
                        onConnectProfile = { profileId ->
                            SessionWindowLauncher.launch(this@MainActivity, profileId)
                            moveTaskToBack(true)
                        },
                        profileManager = profileManager,
                        starredIds = arrangement.starred,
                        onToggleStar = { arrangement = arrangementStore.toggleStar(it) },
                        onMoveProfile = { profile, target -> arrangement = arrangementStore.move(profiles, profile, target) }
                    )
                }
            }
        }
    }
}

