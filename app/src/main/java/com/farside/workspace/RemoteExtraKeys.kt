package com.farside.workspace

import com.farside.workspace.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
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

private data class ExtraKey(val label: Int, val code: Int, val toggle: Boolean = false)

private val functionKeyLabels = listOf(R.string.key_f1, R.string.key_f2, R.string.key_f3, R.string.key_f4, R.string.key_f5, R.string.key_f6, R.string.key_f7, R.string.key_f8, R.string.key_f9, R.string.key_f10, R.string.key_f11, R.string.key_f12)

private val extraKeys = listOf(
    ExtraKey(R.string.key_win, 0x15B, true), ExtraKey(R.string.key_shift, 0x2A, true),
    ExtraKey(R.string.key_ctrl, 0x1D, true), ExtraKey(R.string.key_alt, 0x38, true),
    ExtraKey(R.string.key_altgr, 0x138, true), ExtraKey(R.string.key_del, 0x153),
    ExtraKey(R.string.key_esc, 0x01), ExtraKey(R.string.key_tab, 0x0F),
    ExtraKey(R.string.key_backspace, 0x0E)
)

/** IME companion strip. Disposal releases latched keys on keyboard/session closure. */
@Composable
internal fun RemoteExtraKeys(modifier: Modifier = Modifier, enabled: Boolean, mac: Boolean = false, sendKey: (Int, Boolean) -> Unit) {
    val held = remember { mutableStateMapOf<Int, Boolean>() }
    val currentSendKey by rememberUpdatedState(sendKey)
    var numbers by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) {
        if (!enabled) {
            held.filterValues { it }.keys.forEach { currentSendKey(it, false) }
            held.clear()
            numbers = false
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
    Column(modifier.clip(RoundedCornerShape(bottomStart = 12.8.dp, bottomEnd = 12.8.dp))) {
        Box(Modifier.fillMaxWidth().background(Color(0xFF181818)).padding(9.6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.4.dp)) {
                extraKeys.forEach { key ->
                    val active = held[key.code] == true
                    val label = if (mac) when (key.code) {
                        0x15B -> stringResource(R.string.key_mac_command)
                        0x38 -> stringResource(R.string.key_mac_option)
                        0x138 -> stringResource(R.string.key_mac_right_option)
                        0x1D -> stringResource(R.string.key_mac_control)
                        0x2A -> stringResource(R.string.key_mac_shift)
                        0x153 -> stringResource(R.string.key_mac_forward_delete)
                        else -> stringResource(key.label)
                    } else stringResource(key.label)
                    Keycap(label, Modifier.weight(1f), key.toggle, active,
                        display = if (key.code == 0x0E) stringResource(R.string.key_backspace_symbol) else label) {
                        if (!enabled) return@Keycap
                        when {
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
        BoxWithConstraints(Modifier.fillMaxWidth()
            .background(Color(0xFF181818)).padding(start = 9.6.dp, end = 9.6.dp, bottom = 9.6.dp)) {
            val panelWidth = maxWidth.coerceAtLeast(480.dp)
            Row(Modifier.horizontalScroll(rememberScrollState()).width(panelWidth),
                horizontalArrangement = Arrangement.spacedBy(12.8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.8.dp)) {
                    val rows = if (numbers) listOf(
                        listOf(ExtraKey(R.string.key_7, 0x08), ExtraKey(R.string.key_8, 0x09), ExtraKey(R.string.key_9, 0x0A)),
                        listOf(ExtraKey(R.string.key_4, 0x05), ExtraKey(R.string.key_5, 0x06), ExtraKey(R.string.key_6, 0x07)),
                        listOf(ExtraKey(R.string.key_1, 0x02), ExtraKey(R.string.key_2, 0x03), ExtraKey(R.string.key_3, 0x04)),
                        listOf(ExtraKey(R.string.key_0, 0x0B), ExtraKey(R.string.key_decimal, 0x34))
                    ) else (1..12).chunked(3).map { row -> row.map { n ->
                        ExtraKey(functionKeyLabels[n - 1], if (n <= 10) 0x3A + n else 0x57 + n - 11)
                    } }
                    rows.forEach { keys ->
                        Row(horizontalArrangement = Arrangement.spacedBy(3.2.dp)) {
                            keys.forEach { key -> Keycap(stringResource(key.label), Modifier.weight(if (numbers && key.code == 0x0B) 2f else 1f)) { press(key.code) } }
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.2.dp)) {
                        Keycap(stringResource(R.string.key_print_screen), Modifier.weight(1f), icon = Icons.Default.CameraAlt) { press(0x137) }
                        Spacer(Modifier.weight(1f))
                        Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(41.6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(3.2.dp)) {
                        Keycap(stringResource(R.string.key_slash), Modifier.weight(1f)) { press(0x135) }
                        Keycap(stringResource(R.string.key_multiply), Modifier.weight(1f)) { press(0x37) }
                        Keycap(stringResource(R.string.key_minus), Modifier.weight(1f)) { press(0x4A) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(3.2.dp)) {
                        Keycap(if (numbers) stringResource(R.string.function_keys) else stringResource(R.string.number_keys), Modifier.weight(2f)) { numbers = !numbers }
                        Keycap(stringResource(R.string.key_plus), Modifier.weight(1f)) { press(0x4E) }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.8.dp)) {
                    val rows = listOf(
                        listOf(ExtraKey(R.string.key_home, 0x147), ExtraKey(R.string.key_up, 0x148), ExtraKey(R.string.key_page_up, 0x149)),
                        listOf(ExtraKey(R.string.key_left, 0x14B), null, ExtraKey(R.string.key_right, 0x14D)),
                        listOf(ExtraKey(R.string.key_end, 0x14F), ExtraKey(R.string.key_down, 0x150), ExtraKey(R.string.key_page_down, 0x151)),
                        listOf(ExtraKey(R.string.key_insert, 0x152), ExtraKey(R.string.key_delete, 0x153), ExtraKey(R.string.key_enter, 0x1C))
                    )
                    val arrows = mapOf(R.string.key_up to stringResource(R.string.key_up_symbol), R.string.key_left to stringResource(R.string.key_left_symbol), R.string.key_right to stringResource(R.string.key_right_symbol), R.string.key_down to stringResource(R.string.key_down_symbol))
                    rows.forEach { keys ->
                        Row(horizontalArrangement = Arrangement.spacedBy(3.2.dp)) {
                            keys.forEach { key ->
                                if (key == null) Spacer(Modifier.weight(1f))
                                else Keycap(stringResource(key.label), Modifier.weight(1f), display = arrows[key.label] ?: stringResource(key.label)) { press(key.code) }
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
    val keyState = stringResource(if (active) R.string.key_held else R.string.key_released)
    TextButton(
        onClick = onClick,
        modifier = modifier.height(41.6.dp)
            .focusProperties { canFocus = false }
            .semantics { contentDescription = label; if (toggle) stateDescription = keyState },
        shape = RoundedCornerShape(6.4.dp), contentPadding = PaddingValues(horizontal = 3.2.dp),
        colors = ButtonDefaults.textButtonColors(containerColor = if (active) Color(0xFF285A72) else Color(0xFF353535),
            contentColor = Color(0xFFF2F2F2))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, contentDescription = null, tint = Color(0xFFF2F2F2), modifier = Modifier.size(17.6.dp))
            else Text(display, color = Color(0xFFF2F2F2), fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal, fontSize = if (display.length > 5) 8.96.sp else 11.52.sp,
                letterSpacing = 0.sp, maxLines = 1)
            if (toggle) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 2.4.dp)
                .width(19.2.dp).height(1.6.dp).background(if (active) Color(0xFF39C5CF) else Color(0xFF292929)))
        }
    }
}
