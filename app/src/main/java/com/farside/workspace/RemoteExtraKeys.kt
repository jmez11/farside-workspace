package com.farside.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties

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
internal fun RemoteExtraKeys(modifier: Modifier = Modifier, enabled: Boolean, expanded: Boolean, onExpandedChange: (Boolean) -> Unit,
    sendKey: (Int, Boolean) -> Unit) {
    val held = remember { mutableStateMapOf<Int, Boolean>() }
    val currentSendKey by rememberUpdatedState(sendKey)
    var category by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    LaunchedEffect(enabled) {
        if (!enabled) {
            held.filterValues { it }.keys.forEach { currentSendKey(it, false) }
            held.clear()
            category = 0; page = 0
        }
    }
    DisposableEffect(Unit) {
        onDispose { held.filterValues { it }.keys.forEach { currentSendKey(it, false) } }
    }
    fun press(code: Int) {
        if (!enabled) return
        currentSendKey(code, true)
        currentSendKey(code, false)
    }
    val functionKeys = (1..12).map { n -> ExtraKey("F$n", if (n <= 10) 0x3A + n else 0x57 + n - 11) }
    val numberKeys = (0..9).map { ExtraKey("$it", if (it == 0) 0x0B else it + 1) } + listOf(
        ExtraKey(".", 0x34), ExtraKey("/", 0x135), ExtraKey("*", 0x37), ExtraKey("-", 0x4A),
        ExtraKey("+", 0x4E), ExtraKey("Keypad Enter", 0x11C))
    val navigationKeys = listOf(ExtraKey("Left", 0x14B), ExtraKey("Down", 0x150), ExtraKey("Up", 0x148),
        ExtraKey("Right", 0x14D), ExtraKey("Home", 0x147), ExtraKey("End", 0x14F),
        ExtraKey("PgUp", 0x149), ExtraKey("PgDn", 0x151), ExtraKey("Insert", 0x152),
        ExtraKey("Delete", 0x153), ExtraKey("Backspace", 0x0E), ExtraKey("Enter", 0x1C), ExtraKey("Print Screen", 0x137))
    BoxWithConstraints(modifier.background(Color(0xFF181818)).padding(horizontal = 24.dp, vertical = 12.dp)) {
        val keyWidth = ((maxWidth - 8.dp * 8) / 9).coerceAtLeast(48.dp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!expanded) extraKeys.forEach { key ->
                val active = held[key.code] == true
                Keycap(key.label, Modifier.width(keyWidth), key.toggle, active) {
                    if (!enabled) return@Keycap
                    when {
                        key.code == 0 -> { category = 0; page = 0; onExpandedChange(true) }
                        key.toggle -> { held[key.code] = !active; currentSendKey(key.code, !active) }
                        else -> press(key.code)
                    }
                }
            } else {
                val pages = when (category) { 0 -> functionKeys; 1 -> numberKeys; else -> navigationKeys }.chunked(6)
                val currentPage = page.coerceIn(pages.indices)
                Keycap("ABC", Modifier.width(keyWidth)) { onExpandedChange(false) }
                pages[currentPage].forEach { key -> Keycap(key.label, Modifier.width(keyWidth)) { press(key.code) } }
                repeat(6 - pages[currentPage].size) { Spacer(Modifier.width(keyWidth)) }
                val nextCategory = listOf("Number Keys", "Navigation Keys", "Function Keys")[category]
                Keycap(nextCategory, Modifier.width(keyWidth), display = listOf("123", "Nav", "F keys")[category]) {
                    category = (category + 1) % 3; page = 0
                }
                Keycap("Next key page", Modifier.width(keyWidth), display = "${currentPage + 1}/${pages.size} ?") {
                    page = (currentPage + 1) % pages.size
                }
            }
        }
    }
}

@Composable
private fun Keycap(label: String, modifier: Modifier, toggle: Boolean = false, active: Boolean = false, display: String = label, icon: ImageVector? = null, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = modifier.height(52.dp)
            .focusProperties { canFocus = false }
            .semantics { contentDescription = label; if (toggle) stateDescription = if (active) "Held" else "Released" },
        shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 4.dp),
        colors = ButtonDefaults.textButtonColors(containerColor = if (active) Color(0xFF285A72) else Color(0xFF353535),
            contentColor = Color(0xFFF2F2F2))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, contentDescription = null, tint = Color(0xFFF2F2F2), modifier = Modifier.size(22.dp))
            else Text(display, color = Color(0xFFF2F2F2), fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal, fontSize = if (display.length > 5) 14.sp else 18.sp,
                letterSpacing = 0.sp, maxLines = 1)
            if (toggle) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp)
                .width(24.dp).height(2.dp).background(if (active) Color(0xFF39C5CF) else Color(0xFF292929)))
        }
    }
}
