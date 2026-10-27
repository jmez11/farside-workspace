package com.farside.workspace

import android.view.View
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
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
private open class RemoteImeInputConnection(
    target: InputConnection,
    protected val replacement: () -> Unit,
    protected val submitted: (CharSequence) -> Unit,
    protected val sendKey: (Int, Boolean) -> Unit
) : InputConnectionWrapper(target, false) {
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
    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
        val result = super.getTextBeforeCursor(n, flags)
        if (result.isNullOrEmpty()) return " "
        return result
    }
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength > 0 && afterLength == 0) {
            val before = super.getTextBeforeCursor(beforeLength, 0)
            val available = before?.length ?: 0
            if (available < beforeLength) {
                repeat(beforeLength - available) {
                    sendKey(0x0E, true)
                    sendKey(0x0E, false)
                }
            }
        }
        return super.deleteSurroundingText(beforeLength, afterLength)
    }
    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        return deleteSurroundingText(beforeLength, afterLength)
    }
    override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_DEL) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                sendKey(0x0E, true)
                sendKey(0x0E, false)
            }
            return true
        }
        return super.sendKeyEvent(event)
    }
    override fun commitText(text: CharSequence, cursor: Int): Boolean {
        beforeCommit()
        submitted(text)
        return super.commitText(text, cursor)
    }
    override fun setComposingText(text: CharSequence, cursor: Int): Boolean {
        val extracted = super.getExtractedText(ExtractedTextRequest(), 0)
        val range = extracted?.let {
            suggestionCompositionRange(it.text?.toString().orEmpty(), it.selectionStart, it.selectionEnd, text.toString())
        }
        if (range != null) super.setComposingRegion(range.first, range.second)
        return super.setComposingText(text, cursor)
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

/** Keep a suggestion's composing range on the typed token, beyond the dummy leading space. */
internal fun suggestionCompositionRange(current: String, selectionStart: Int, selectionEnd: Int,
    submitted: String): Pair<Int, Int>? {
    if (selectionStart != selectionEnd || selectionEnd != current.length ||
        submitted.codePointCount(0, submitted.length) < 2) return null
    val start = current.indexOfLast { it.isWhitespace() } + 1
    if (start >= current.length) return null
    val word = current.substring(start)
    val trimmed = current.removePrefix(" ").dropLast(word.length)
    val effective = if (trimmed.isNotEmpty() && submitted.startsWith(trimmed, ignoreCase = true)) {
        submitted.substring(trimmed.length)
    } else submitted
    if (effective.length <= word.length || !effective.startsWith(word, ignoreCase = true)) return null
    return start to current.length
}

@androidx.annotation.RequiresApi(34)
private class RemoteImeInputConnectionApi34(
    target: InputConnection,
    replacement: () -> Unit,
    submitted: (CharSequence) -> Unit,
    sendKey: (Int, Boolean) -> Unit
) : RemoteImeInputConnection(target, replacement, submitted, sendKey) {
    override fun commitText(text: CharSequence, cursor: Int, attributes: TextAttribute?): Boolean {
        beforeCommit()
        submitted(text)
        return super.commitText(text, cursor, attributes)
    }
    override fun replaceText(start: Int, end: Int, text: CharSequence, cursor: Int, attributes: TextAttribute?): Boolean {
        if (start != end) replacement()
        return super.replaceText(start, end, text, cursor, attributes)
    }
}

/** A suggestion replaces the typed trigger token without replaying or duplicating already-committed prefix history. */
internal fun normalizeImeSuggestion(previous: TextFieldValue, next: TextFieldValue,
    submitted: String?): TextFieldValue {
    if (submitted.isNullOrEmpty() || submitted.codePointCount(0, submitted.length) < 2 ||
        previous.selection.collapsed.not() || previous.selection.end != previous.text.length) return next
    val word = previous.text.substringAfterLast(' ')
    if (word.isEmpty()) return next
    val prevPrefix = previous.text.dropLast(word.length)
    val trimmedPrevPrefix = prevPrefix.trimStart()

    val effective = if (trimmedPrevPrefix.isNotEmpty() && submitted.startsWith(trimmedPrevPrefix, ignoreCase = true)) {
        submitted.substring(trimmedPrevPrefix.length)
    } else submitted

    if (!effective.startsWith(word, ignoreCase = true)) return next
    val appended = next.text == previous.text + submitted ||
        next.text == prevPrefix + submitted ||
        (trimmedPrevPrefix.isNotEmpty() && next.text == previous.text + effective)
    val droppedSentinel = previous.text.startsWith(" ") &&
        (next.text == previous.text.removePrefix(" ") + submitted ||
         next.text == prevPrefix.removePrefix(" ") + submitted ||
         next.text == previous.text.removePrefix(" ") + effective)
    val fullReplacement = (next.text == " $submitted" || next.text == submitted) &&
        trimmedPrevPrefix.isNotEmpty() && submitted.startsWith(trimmedPrevPrefix, ignoreCase = true)

    if (!appended && !droppedSentinel && !fullReplacement) return next
    val newText = prevPrefix + effective
    return TextFieldValue(newText, selection = TextRange(newText.length))
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
        // For already-committed prefix words, match case-insensitively so sentence auto-capitalization
        // never erases prior words.
        val before = previous.text.removePrefix(" ").codePoints().toArray()
        val after = next.text.removePrefix(" ").codePoints().toArray()
        var common = 0
        while (common < before.size && common < after.size &&
            (before[common] == after[common] ||
             (common < before.size - 1 && Character.toLowerCase(before[common]) == Character.toLowerCase(after[common])))) {
            common++
        }
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
    var submittedText by remember { mutableStateOf<String?>(null) }
    val interceptor = remember {
        PlatformTextInputInterceptor { request, handler ->
            handler.startInputMethod(object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                    // Horizon's cached range can clamp to insertion after the sentinel reset.
                    // Preserve the requested replacement operation through Compose's wrapper.
                    val target = request.createInputConnection(outAttributes)
                    val replacement = { explicitReplacement = true }
                    val submitted = { value: CharSequence -> submittedText = value.toString() }
                    return if (android.os.Build.VERSION.SDK_INT >= 34) RemoteImeInputConnectionApi34(target, replacement, submitted, sendKey)
                    else RemoteImeInputConnection(target, replacement, submitted, sendKey)
                }
            })
        }
    }
    SideEffect { onView(view); onActions(actions) }
    DisposableEffect(Unit) { onDispose { if (hideOnDispose) keyboard?.hide(); onView(null); onActions(null); onFocus(false); onImeVisibility(false) } }

    var lastEnterTime by remember { mutableStateOf(0L) }
    InterceptPlatformTextInput(interceptor) {
        BasicTextField(value = text, onValueChange = { next ->
            val adjusted = normalizeImeSuggestion(text, next, submittedText)
            submittedText = null
            lastEnterTime = processImeChange(
                next = adjusted,
                lastEnterTime = lastEnterTime,
                previous = text,
                explicitReplacement = explicitReplacement,
                sendUnicode = sendUnicode,
                sendKey = sendKey
            )
            explicitReplacement = false
            // Keep the streamed buffer through composition finalization and IME dismissal.
            // Ensure sentinel leading space is never lost so cursor underflow can always backspace.
            val ensuredText = if (adjusted.text.isEmpty() || adjusted.text.startsWith(" ")) adjusted
                              else TextFieldValue(" " + adjusted.text, selection = TextRange(adjusted.selection.start + 1, adjusted.selection.end + 1))
            text = if ('\n' !in ensuredText.text) ensuredText else TextFieldValue(" ", selection = TextRange(1))
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
                    } else if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DEL) {
                        if (event.type == KeyEventType.KeyDown) {
                            sendKey(0x0E, true)
                            sendKey(0x0E, false)
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
