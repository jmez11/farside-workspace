package com.multidesk.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

private data class ExtraKey(val label: String, val code: Int, val toggle: Boolean = false)

private val extraKeys = listOf(
    ExtraKey("WIN", 0x15B, true), ExtraKey("SHIFT", 0x2A, true),
    ExtraKey("CTRL", 0x1D, true), ExtraKey("ALT", 0x38, true),
    ExtraKey("AltGr", 0x138, true), ExtraKey("DEL", 0x153),
    ExtraKey("ESC", 0x01), ExtraKey("TAB", 0x0F),
    ExtraKey("123", 0)
)

/** IME companion strip. Disposal releases latched keys on keyboard/session closure. */
@Composable
internal fun RemoteExtraKeys(modifier: Modifier = Modifier, sendKey: (Int, Boolean) -> Unit, rightClick: () -> Unit) {
    val held = remember { mutableStateMapOf<Int, Boolean>() }
    val currentSendKey by rememberUpdatedState(sendKey)
    var expanded by remember { mutableStateOf(false) }
    var numbers by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { held.filterValues { it }.keys.forEach { currentSendKey(it, false) } }
    }
    fun press(code: Int) {
        currentSendKey(code, true)
        currentSendKey(code, false)
    }
    Column(modifier.background(Color(0xFF202020))) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val keyWidth = (maxWidth / extraKeys.size).coerceAtLeast(48.dp)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                extraKeys.forEach { key ->
                    val active = held[key.code] == true
                    Keycap(if (key.code == 0 && expanded) "ABC" else key.label, Modifier.width(keyWidth - 2.dp), key.toggle, active) {
                        when {
                            key.code == 0 -> { expanded = !expanded; numbers = false }
                            key.toggle -> {
                                held[key.code] = !active
                                currentSendKey(key.code, !active)
                            }
                            else -> press(key.code)
                        }
                    }
                }
            }
        }
        if (expanded) BoxWithConstraints(Modifier.fillMaxWidth()) {
            val panelWidth = maxWidth.coerceAtLeast(600.dp)
            Row(Modifier.horizontalScroll(rememberScrollState()).width(panelWidth).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val rows = if (numbers) listOf(
                        listOf(ExtraKey("7", 0x08), ExtraKey("8", 0x09), ExtraKey("9", 0x0A)),
                        listOf(ExtraKey("4", 0x05), ExtraKey("5", 0x06), ExtraKey("6", 0x07)),
                        listOf(ExtraKey("1", 0x02), ExtraKey("2", 0x03), ExtraKey("3", 0x04)),
                        listOf(ExtraKey("0", 0x0B), ExtraKey(".", 0x34))
                    ) else (1..12).chunked(3).map { row -> row.map { n ->
                        ExtraKey("F$n", if (n <= 10) 0x3A + n else 0x57 + n - 11)
                    } }
                    rows.forEach { keys ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            keys.forEach { key -> Keycap(key.label, Modifier.weight(if (numbers && key.label == "0") 2f else 1f)) { press(key.code) } }
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Keycap("Right click", Modifier.weight(1f), icon = Icons.Default.Mouse, onClick = rightClick)
                        Keycap("Backspace", Modifier.weight(1f), display = "\u232B") { press(0x0E) }
                        Keycap("/", Modifier.weight(1f)) { press(0x135) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Keycap("Print Screen", Modifier.weight(1f), icon = Icons.Default.CameraAlt) { press(0x137) }
                        Keycap("Keypad Enter", Modifier.weight(1f), display = "\u21B5") { press(0x11C) }
                        Keycap("*", Modifier.weight(1f)) { press(0x37) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Spacer(Modifier.weight(1f))
                        Spacer(Modifier.weight(1f))
                        Keycap("-", Modifier.weight(1f)) { press(0x4A) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Keycap(if (numbers) "Function Keys" else "Number Keys", Modifier.weight(2f)) { numbers = !numbers }
                        Keycap("+", Modifier.weight(1f)) { press(0x4E) }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val rows = listOf(
                        listOf(ExtraKey("Home", 0x147), ExtraKey("Up", 0x148), ExtraKey("PgUp", 0x149)),
                        listOf(ExtraKey("Left", 0x14B), null, ExtraKey("Right", 0x14D)),
                        listOf(ExtraKey("End", 0x14F), ExtraKey("Down", 0x150), ExtraKey("PgDn", 0x151)),
                        listOf(ExtraKey("Insert", 0x152), ExtraKey("Delete", 0x153), ExtraKey("Enter", 0x1C))
                    )
                    val arrows = mapOf("Up" to "\u2191", "Left" to "\u2190", "Right" to "\u2192", "Down" to "\u2193")
                    rows.forEach { keys ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            keys.forEach { key ->
                                if (key == null) Spacer(Modifier.weight(1f))
                                else Keycap(key.label, Modifier.weight(1f), display = arrows[key.label] ?: key.label) { press(key.code) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Keycap(label: String, modifier: Modifier, toggle: Boolean = false, active: Boolean = false, display: String = label, icon: ImageVector? = null, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = modifier.height(52.dp).background(Color(0xFF454545))
            .semantics { contentDescription = label; if (toggle) stateDescription = if (active) "Held" else "Released" },
        shape = RoundedCornerShape(0.dp), contentPadding = PaddingValues(0.dp)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, contentDescription = null, tint = Color.White)
            else Text(display, color = Color.White)
            if (toggle) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp)
                .width(24.dp).height(2.dp).background(if (active) Color(0xFF39C5CF) else Color(0xFF292929)))
        }
    }
}
