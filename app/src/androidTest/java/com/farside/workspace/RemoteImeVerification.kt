package com.farside.workspace

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/** Assert the actual attributes handed to the system IME, not just Compose options. */
internal fun verifyRemoteImeOptions(info:EditorInfo) {
    check(info.inputType and InputType.TYPE_MASK_CLASS==InputType.TYPE_CLASS_TEXT)
    check(info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS!=0) {"Remote IME still permits dictionary suggestions"}
    val automatic=InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE or
        InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    check(info.inputType and automatic==0) {"Remote IME still enables automatic text changes"}
}

internal fun verifyRemoteImeBuffer(input:InputConnection,expected:String) {
    val extracted=checkNotNull(input.getExtractedText(ExtractedTextRequest(),0))
    check(extracted.text.toString()==expected) {"Local IME buffer diverged: expected=$expected, actual=${extracted.text}"}
    check(extracted.selectionStart==expected.length && extracted.selectionEnd==expected.length) {"Local IME caret diverged"}
    check(input.getTextBeforeCursor(expected.length+1,0).toString()==expected) {"IME surrounding text diverged"}
}
