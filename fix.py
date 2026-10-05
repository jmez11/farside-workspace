import sys

with open('app/src/main/java/com/multidesk/explorer/MainActivity.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Add imports
imports = '''
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
'''
content = content.replace('import java.util.UUID', 'import java.util.UUID\n' + imports)

# 2. Add helper
helper = '''
fun mapAndroidKeyCodeToXt(keyCode: Int): Int {
    return when(keyCode) {
        67 -> 0x0E // DEL/Backspace
        66 -> 0x1C // ENTER
        19 -> 0x148 // UP
        20 -> 0x150 // DOWN
        21 -> 0x14B // LEFT
        22 -> 0x14D // RIGHT
        61 -> 0x0F // TAB
        111 -> 0x01 // ESC
        113 -> 0x1D // CTRL
        57 -> 0x38 // ALT
        59 -> 0x2A // SHIFT
        62 -> 0x39 // SPACE
        else -> 0
    }
}

@Composable
fun RdpSessionScreen'''
content = content.replace('@Composable\nfun RdpSessionScreen', helper)

# Only replace within RdpSessionScreen
idx = content.find('fun RdpSessionScreen')
before = content[:idx]
after = content[idx:]

vars_find = '    val context = LocalContext.current'
vars_repl = '''    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var showSoftKeyboard by remember { mutableStateOf(false) }'''
after = after.replace(vars_find, vars_repl, 1)

box_find = '    Box(\n        modifier = Modifier\n            .fillMaxSize()\n            .pointerInput(Unit) {'
box_repl = '''    if (showSoftKeyboard) {
        val softKeyboardFocus = remember { FocusRequester() }
        var textValue by remember { mutableStateOf("") }
        androidx.compose.foundation.text.BasicTextField(
            value = textValue,
            onValueChange = { newVal ->
                if (newVal.length > textValue.length && rdpInstance != 0L) {
                    val char = newVal.last()
                    rdpBridge.sendUnicodeKeyEvent(rdpInstance, char.code, true)
                    rdpBridge.sendUnicodeKeyEvent(rdpInstance, char.code, false)
                } else if (newVal.length < textValue.length && rdpInstance != 0L) {
                    rdpBridge.sendKeyEvent(rdpInstance, 0x0E, true) // Backspace
                    rdpBridge.sendKeyEvent(rdpInstance, 0x0E, false)
                }
                textValue = newVal
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.size(1.dp).focusRequester(softKeyboardFocus)
        )
        LaunchedEffect(Unit) {
            softKeyboardFocus.requestFocus()
            keyboardController?.show()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (rdpInstance != 0L) {
                    val isDown = event.type == KeyEventType.KeyDown
                    val unicode = event.nativeKeyEvent.unicodeChar
                    val scancode = mapAndroidKeyCodeToXt(event.nativeKeyEvent.keyCode)
                    if (unicode != 0 && scancode == 0) {
                        rdpBridge.sendUnicodeKeyEvent(rdpInstance, unicode, isDown)
                        true
                    } else if (scancode != 0) {
                        rdpBridge.sendKeyEvent(rdpInstance, scancode, isDown)
                        true
                    } else {
                        // Pass EVDEV scancode as fallback
                        rdpBridge.sendKeyEvent(rdpInstance, event.nativeKeyEvent.scanCode, isDown)
                        true
                    }
                } else {
                    false
                }
            }
            .pointerInput(Unit) {'''
after = after.replace(box_find, box_repl, 1)

touch_find = '                    setOnTouchListener { view, event ->\n                        gestureDetector.onTouchEvent(event)'
touch_repl = '''                    setOnTouchListener { view, event ->
                        focusRequester.requestFocus()
                        gestureDetector.onTouchEvent(event)'''
after = after.replace(touch_find, touch_repl, 1)

hover_find = '''                        if (rdpInstance != 0L) {
                            rdpBridge.sendMouseEvent(rdpInstance, rdpX, rdpY, flags)
                        }
                        true
                    }'''
hover_repl = hover_find + '''
                    
                    setOnHoverListener { view, event ->
                        if (rdpInstance != 0L) {
                            rdpBridge.sendMouseEvent(rdpInstance, event.x.toInt(), event.y.toInt(), FreeRDPBridge.PTR_FLAGS_MOVE)
                        }
                        true
                    }'''
after = after.replace(hover_find, hover_repl, 1)

kbd_find = 'IconButton(onClick = { /* Implement keyboard toggle later */ })'
kbd_repl = 'IconButton(onClick = { showSoftKeyboard = !showSoftKeyboard; if (!showSoftKeyboard) { keyboardController?.hide() } })'
after = after.replace(kbd_find, kbd_repl, 1)

content = before + after

with open('app/src/main/java/com/multidesk/explorer/MainActivity.kt', 'w', encoding='utf-8') as f:
    f.write(content)

