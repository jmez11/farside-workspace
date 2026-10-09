package com.farside.workspace

import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import kotlinx.coroutines.delay

internal class ImeEditorActions {
    var focusRequests = 0
    var focusResets = 0
    var restarts = 0
    var showRequests = 0
}

internal fun processImeChange(
    next: TextFieldValue,
    lastEnterTime: Long,
    now: Long = android.os.SystemClock.uptimeMillis(),
    sendUnicode: (Int, Boolean) -> Unit,
    sendKey: (Int, Boolean) -> Unit
): Long {
    var newLastEnterTime = lastEnterTime
    if (next.text.isEmpty()) {
        sendKey(0x0E, true)
        sendKey(0x0E, false)
    } else {
        val isReplacement = !next.text.startsWith(" ")
        if (isReplacement) {
            // Horizon OS popup selection replaced the previously committed character.
            // Erase the previously sent character on the remote session before sending replacement.
            sendKey(0x0E, true)
            sendKey(0x0E, false)
        }
        val newChars = if (next.text.startsWith(" ")) next.text.substring(1) else next.text
        var i = 0
        while (i < newChars.length) {
            val codePoint = newChars.codePointAt(i)
            i += Character.charCount(codePoint)
            if (codePoint == '\n'.code) {
                if (now - lastEnterTime > 200) {
                    newLastEnterTime = now
                    sendKey(0x1C, true)
                    sendKey(0x1C, false)
                }
            } else {
                sendUnicode(codePoint, true)
                sendUnicode(codePoint, false)
            }
        }
    }
    return newLastEnterTime
}

/** Each focusable session surface needs its own real InputConnection for Horizon's IME. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun RemoteImeEditor(label: String, activation: Long, showInitially: Boolean,
    keyboardVisible: Boolean,
    onFocus: (Boolean) -> Unit, onView: (View?) -> Unit,
    sendUnicode: (Int, Boolean) -> Unit, sendKey: (Int, Boolean) -> Unit,
    onActions: (ImeEditorActions?) -> Unit,
    onImeVisibility: (Boolean) -> Unit = {}) {
    val view = LocalView.current
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val input = remember(view) { view.context.getSystemService(InputMethodManager::class.java) }
    val actions = remember { ImeEditorActions() }
    val visibleAnywhere by rememberUpdatedState(keyboardVisible)
    var focused by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) { onImeVisibility(imeVisible) }
    var text by remember { mutableStateOf(TextFieldValue(" ", selection = TextRange(1))) }
    SideEffect { onView(view); onActions(actions) }
    DisposableEffect(Unit) { onDispose { keyboard?.hide(); onView(null); onActions(null); onFocus(false); onImeVisibility(false) } }

    var lastEnterTime by remember { mutableStateOf(0L) }
    BasicTextField(value = text, onValueChange = { next ->
        lastEnterTime = processImeChange(
            next = next,
            lastEnterTime = lastEnterTime,
            sendUnicode = sendUnicode,
            sendKey = sendKey
        )
        text = TextFieldValue(" ", selection = TextRange(1))
    }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.None),
        modifier = Modifier.size(1.dp).alpha(0f).focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                    if (event.type == KeyEventType.KeyDown) {
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastEnterTime > 200) {
                            lastEnterTime = now
                            sendKey(0x1C, true)
                            sendKey(0x1C, false)
                        }
                    }
                    true
                } else false
            }
            .onFocusChanged { focused = it.isFocused && it.hasFocus; onFocus(focused) }
            .semantics { contentDescription = label })
    LaunchedEffect(Unit) {
        // Prepare once. Native window-focus gain can reuse this editor's InputConnection.
        actions.focusRequests++
        focus.requestFocus()
        if (showInitially) { actions.showRequests++; keyboard?.show() }
    }
    LaunchedEffect(activation) {
        if (activation == 0L) return@LaunchedEffect
        // Give Android's native focus handoff time to bind the already-prepared editor.
        repeat(3) { withFrameNanos { } }
        if (!view.hasWindowFocus()) return@LaunchedEffect
        if (!focused) { actions.focusRequests++; focus.requestFocus(); withFrameNanos { } }
        if (!input.isActive(view)) {
            actions.restarts++
            input.restartInput(view)
            delay(80)
            if (!view.hasWindowFocus()) return@LaunchedEffect
            if (!input.isActive(view)) {
                // Recovery only: do not tear down a working editor on ordinary surface switches.
                actions.focusResets++
                focusManager.clearFocus(force = true)
                actions.focusRequests++
                focus.requestFocus()
                withFrameNanos { }
            }
        }
        if (!visibleAnywhere) {
            delay(100)
            if (view.hasWindowFocus() && !visibleAnywhere) { actions.showRequests++; keyboard?.show() }
        }
    }
}
