package com.farside.workspace

import android.view.View
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.TextAttribute
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
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
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

/** Compose's outer connection may expand API 34 replaceText into these older operations. */
private open class RemoteImeInputConnection(target: InputConnection, protected val replacement: () -> Unit) :
    InputConnectionWrapper(target, false) {
    private var batchDepth = 0
    private var finishedComposition = false
    private var selectedRange = false
    override fun beginBatchEdit(): Boolean {
        if (batchDepth++ == 0) { finishedComposition = false; selectedRange = false }
        return super.beginBatchEdit()
    }
    override fun finishComposingText(): Boolean {
        if (batchDepth > 0) finishedComposition = true
        return super.finishComposingText()
    }
    override fun setSelection(start: Int, end: Int): Boolean {
        if (batchDepth > 0 && finishedComposition) selectedRange = start != end
        return super.setSelection(start, end)
    }
    override fun commitText(text: CharSequence, cursor: Int): Boolean {
        beforeCommit()
        return super.commitText(text, cursor)
    }
    protected fun beforeCommit() {
        if (batchDepth > 0 && finishedComposition && selectedRange) replacement()
    }
    override fun endBatchEdit(): Boolean {
        try { return super.endBatchEdit() }
        finally {
            batchDepth = (batchDepth - 1).coerceAtLeast(0)
            if (batchDepth == 0) { finishedComposition = false; selectedRange = false }
        }
    }
}

@androidx.annotation.RequiresApi(34)
private class RemoteImeInputConnectionApi34(target: InputConnection, replacement: () -> Unit) :
    RemoteImeInputConnection(target, replacement) {
    override fun commitText(text: CharSequence, cursor: Int, attributes: TextAttribute?): Boolean {
        beforeCommit()
        return super.commitText(text, cursor, attributes)
    }
    override fun replaceText(start: Int, end: Int, text: CharSequence, cursor: Int, attributes: TextAttribute?): Boolean {
        if (start != end) replacement()
        return super.replaceText(start, end, text, cursor, attributes)
    }
}

internal fun processImeChange(
    next: TextFieldValue,
    lastEnterTime: Long,
    now: Long = android.os.SystemClock.uptimeMillis(),
    previous: TextFieldValue? = null,
    explicitReplacement: Boolean = false,
    sendUnicode: (Int, Boolean) -> Unit,
    sendKey: (Int, Boolean) -> Unit
): Long {
    // Dismissing the IME can update selection or finish composition without editing text.
    // Those callbacks must not replay the buffer (including the sentinel space).
    if (previous?.text == next.text) return lastEnterTime
    // Horizon may submit the whole keyboard buffer with Return; its text was already streamed.
    if ('\n' in next.text) {
        if (now - lastEnterTime > 200) {
            sendKey(0x1C, true)
            sendKey(0x1C, false)
            return now
        }
        return lastEnterTime
    }
    var newLastEnterTime = lastEnterTime
    var newChars: String
    if (previous != null && (previous.composition != null || previous.text != " ")) {
        // Horizon composes whole words during ordinary typing. Stream each changed code point
        // immediately; finalizing the unchanged word must not send it a second time.
        val before = previous.text.removePrefix(" ").codePoints().toArray()
        val after = next.text.removePrefix(" ").codePoints().toArray()
        var common = 0
        while (common < before.size && common < after.size && before[common] == after[common]) common++
        repeat(before.size - common) { sendKey(0x0E, true); sendKey(0x0E, false) }
        newChars = String(after, common, after.size - common)
    } else if (next.text.isEmpty()) {
        sendKey(0x0E, true)
        sendKey(0x0E, false)
        newChars = ""
    } else {
        val isReplacement = !next.text.startsWith(" ")
        if (isReplacement || explicitReplacement) {
            // Horizon OS popup selection replaced the previously committed character.
            // Erase the previously sent character on the remote session before sending replacement.
            sendKey(0x0E, true)
            sendKey(0x0E, false)
        }
        newChars = next.text.removePrefix(" ")
    }
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
    return newLastEnterTime
}

/** Each focusable session surface needs its own real InputConnection for Horizon's IME. */
@Composable
@OptIn(ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun RemoteImeEditor(label: String, activation: Long, showInitially: Boolean,
    keyboardVisible: Boolean,
    onFocus: (Boolean) -> Unit, onView: (View?) -> Unit,
    sendUnicode: (Int, Boolean) -> Unit, sendKey: (Int, Boolean) -> Unit,
    onActions: (ImeEditorActions?) -> Unit,
    onImeVisibility: (Boolean) -> Unit = {}, hideOnDispose: Boolean = true) {
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
    var explicitReplacement by remember { mutableStateOf(false) }
    val interceptor = remember {
        PlatformTextInputInterceptor { request, handler ->
            handler.startInputMethod(object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                    // Horizon's cached range can clamp to insertion after the sentinel reset.
                    // Preserve the requested replacement operation through Compose's wrapper.
                    val target = request.createInputConnection(outAttributes)
                    val replacement = { explicitReplacement = true }
                    return if (android.os.Build.VERSION.SDK_INT >= 34) RemoteImeInputConnectionApi34(target, replacement)
                    else RemoteImeInputConnection(target, replacement)
                }
            })
        }
    }
    SideEffect { onView(view); onActions(actions) }
    DisposableEffect(Unit) { onDispose { if (hideOnDispose) keyboard?.hide(); onView(null); onActions(null); onFocus(false); onImeVisibility(false) } }

    var lastEnterTime by remember { mutableStateOf(0L) }
    InterceptPlatformTextInput(interceptor) {
        BasicTextField(value = text, onValueChange = { next ->
            lastEnterTime = processImeChange(
                next = next,
                lastEnterTime = lastEnterTime,
                previous = text,
                explicitReplacement = explicitReplacement,
                sendUnicode = sendUnicode,
                sendKey = sendKey
            )
            explicitReplacement = false
            // Keep the streamed buffer through composition finalization and IME dismissal.
            // Resetting it on commit makes Horizon restore and resend its cached text.
            text = if ('\n' !in next.text && next.text.isNotEmpty()) next else TextFieldValue(" ", selection = TextRange(1))
        }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.None, autoCorrectEnabled = false),
            modifier = Modifier.size(1.dp).alpha(0f).focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                        if (event.type == KeyEventType.KeyDown) {
                            text = TextFieldValue(" ", selection = TextRange(1))
                            explicitReplacement = false
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
    }
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
