package com.farside.workspace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import metavrx.layout.compose.SpatialScene
import metavrx.layout.window.compose.SpatialWindow
import metavrx.layout.window.compose.layout.*
import metavrx.layout.window.compose.state.*

/** Debug-only local panels: no remote connection or saved-profile mutation. */
class WindowCapacityFixtureActivity : ComponentActivity() {
    var childCount by mutableIntStateOf(0)
    val children = mutableListOf<WindowState>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val number = intent.getIntExtra("number", 0)
        setTitle("Capacity test $number")
        setContent {
            SpatialScene {
                Box(Modifier.fillMaxSize().background(Color.DarkGray)) { Text("Capacity test $number", color = Color.White) }
                repeat(2) { index ->
                    val state = rememberWindowState()
                    SideEffect { if (state !in children) children.add(state) }
                    if (index < childCount) SpatialWindow(key = "capacity-child-$index", windowState = state,
                        fallbackStrategy = WindowFallback.Drop,
                        modifier = WindowModifier.width(400.dp).height(76.dp)
                            .anchor(if (index == 0) WindowAnchor.Bottom else WindowAnchor.Top)) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF304060))) { Text("Keyboard child ${index + 1}", color = Color.White) }
                    }
                }
            }
        }
    }
}
