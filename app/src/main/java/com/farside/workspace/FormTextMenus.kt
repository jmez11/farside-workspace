package com.farside.workspace

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/** Spatial windows cannot rely on Android's floating selection toolbar being visible. */
@Composable
internal fun FormTextMenus(content:@Composable ()->Unit) {
    val context=LocalContext.current
    val clipboard=remember(context) {context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager}
    val toolbar=remember(clipboard) {FormTextToolbar(clipboard)}
    CompositionLocalProvider(LocalTextToolbar provides toolbar) {content()}
    val gap=with(LocalDensity.current) {8.dp.roundToPx()}
    if(toolbar.status==TextToolbarStatus.Shown) Popup(popupPositionProvider=remember(toolbar.rect,gap) {FieldTextMenuPosition(toolbar.rect,gap)},
        onDismissRequest={toolbar.hide()},properties=PopupProperties(focusable=false)) {
        Surface(modifier=Modifier.widthIn(min=120.dp,max=240.dp).width(IntrinsicSize.Max),shadowElevation=6.dp) {Column {
            toolbar.actions.forEach {(label,action) -> DropdownMenuItem(text={Text(stringResource(label))},onClick={toolbar.hide();action()})}
        }}
    }
}

private class FormTextToolbar(private val clipboard:ClipboardManager):TextToolbar {
    override var status by mutableStateOf(TextToolbarStatus.Hidden)
        private set
    var rect by mutableStateOf(Rect.Zero)
        private set
    var actions by mutableStateOf<List<Pair<Int,()->Unit>>>(emptyList())
        private set
    override fun showMenu(rect:Rect,onCopyRequested:(()->Unit)?,onPasteRequested:(()->Unit)?,onCutRequested:(()->Unit)?,onSelectAllRequested:(()->Unit)?) {
        this.rect=rect
        actions=buildList {
            if(onPasteRequested!=null && clipboard.primaryClip?.let {it.itemCount>0 && !it.getItemAt(0).text.isNullOrEmpty()}==true) add(android.R.string.paste to onPasteRequested)
            onCopyRequested?.let {add(android.R.string.copy to it)}
            onCutRequested?.let {add(android.R.string.cut to it)}
            onSelectAllRequested?.let {add(android.R.string.selectAll to it)}
        }
        status=if(actions.isEmpty()) TextToolbarStatus.Hidden else TextToolbarStatus.Shown
    }
    override fun hide() {status=TextToolbarStatus.Hidden}
}

/** TextToolbar supplies root coordinates. Popup's default anchor is after the field,
 * so adding those coordinates as an offset positioned the old menu twice as far down. */
private class FieldTextMenuPosition(private val field:Rect,private val gap:Int):PopupPositionProvider {
    override fun calculatePosition(anchorBounds:IntRect,windowSize:IntSize,layoutDirection:LayoutDirection,popupContentSize:IntSize):IntOffset {
        val x=field.left.toInt().coerceIn(0,(windowSize.width-popupContentSize.width).coerceAtLeast(0))
        val above=field.top.toInt()-popupContentSize.height-gap
        val y=if(above>=0) above else field.bottom.toInt()+gap
        return IntOffset(x,y.coerceIn(0,(windowSize.height-popupContentSize.height).coerceAtLeast(0)))
    }
}
