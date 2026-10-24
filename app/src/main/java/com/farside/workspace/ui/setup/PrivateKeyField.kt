package com.farside.workspace.ui.setup

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.farside.workspace.FieldTextMenuPosition
import com.farside.workspace.TextActionCapsule
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.farside.workspace.R

/** Explicit spatial popup: Android's native text toolbar may not appear in a Horizon window. */
@Composable
internal fun PrivateKeyField(value: String, onChange: (String) -> Unit, error: Boolean) {
    val context = LocalContext.current
    val label = stringResource(R.string.private_key)
    val pasteLabel = stringResource(android.R.string.paste)
    var editor by remember { mutableStateOf(TextFieldValue(value)) }
    var showPaste by remember { mutableStateOf(false) }
    var fieldBounds by remember { mutableStateOf(Rect.Zero) }
    val gap=with(LocalDensity.current) {8.dp.roundToPx()}
    val current = if (editor.text == value) editor else TextFieldValue(value)
    LaunchedEffect(value) { if (editor.text != value) editor = TextFieldValue(value) }
    Box(Modifier.fillMaxWidth().onGloballyPositioned {fieldBounds=it.boundsInRoot()}.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var pressed = true
                while (pressed) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    pressed = event.changes.all { it.pressed && (it.position - down.position).getDistance() <= viewConfiguration.touchSlop }
                }
                false
            }
            if (held == null) showPaste = true
        }
    }) {
        OutlinedTextField(value = current, onValueChange = { editor = it; onChange(it.text) },
            minLines = 3, maxLines = 6, isError = error,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            shape = RoundedCornerShape(11.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = SetupColors.field,
                unfocusedContainerColor = SetupColors.field, focusedBorderColor = SetupColors.accent,
                unfocusedBorderColor = SetupColors.border), modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = label
                    onLongClick(pasteLabel) { showPaste = true; true }
                })
        if(showPaste) Popup(popupPositionProvider=remember(fieldBounds,gap) {FieldTextMenuPosition(fieldBounds,gap)},
            onDismissRequest={showPaste=false},properties=PopupProperties(focusable=false)) {
            TextActionCapsule(listOf(android.R.string.paste to {
                showPaste = false
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                if (text != null) {
                    val range = current.selection
                    val replacement = current.text.replaceRange(range.min, range.max, text)
                    editor = TextFieldValue(replacement, TextRange(range.min + text.length))
                    onChange(replacement)
                }
            }))
        }
    }
}
