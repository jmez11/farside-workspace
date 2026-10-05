import sys
content = open('app/src/main/java/com/multidesk/explorer/MainActivity.kt').read()

bad_start = 'IconButton(onClick = { showSoftKeyboard = !showSoftKeyboard;     if (showSoftKeyboard) {'
start_idx = content.find(bad_start)
if start_idx != -1:
    end_idx = content.find('                }', start_idx) + 17
    
    fixed = '''IconButton(onClick = { 
                        showSoftKeyboard = !showSoftKeyboard
                        if (!showSoftKeyboard) { keyboardController?.hide() } 
                    }) {
                        Icon(Icons.Default.Keyboard, "Keyboard", tint = Color.White)
                    }'''
    
    content = content[:start_idx] + fixed + content[end_idx:]
    
    target = '    Box('
    insert = '''    if (showSoftKeyboard) {
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

    Box('''
    
    content = content.replace('    Box(', insert, 1)

open('app/src/main/java/com/multidesk/explorer/MainActivity.kt', 'w').write(content)
