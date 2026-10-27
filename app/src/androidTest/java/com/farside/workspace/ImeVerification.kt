package com.farside.workspace

import android.app.Instrumentation
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

class ImeVerification(private val instrumentation: Instrumentation) {

    fun run(): String {
        testAlternativeKeys()
        testNormalTyping()
        testSpaceTyping()
        testEmptyBufferBackspace()
        testEnterDebounce()
        testBufferedEnter()
        testMultiCharacterReplacement()
        testEmojiInput()
        testComposingTyping()
        testSuggestionCommit()
        testSuggestionCompositionRange()
        testKeyboardDismissal()
        return "All IME input scenarios verified: alternative keys, standard typing, space, empty backspace, buffered Enter without text replay, enter debouncing, multi-char replacements, composing typing, silent dismissal callbacks, and emojis."
    }

    private fun testSuggestionCompositionRange() {
        check(suggestionCompositionRange(" y", 2, 2, "Youtube ") == (1 to 2))
        check(suggestionCompositionRange(" hello y", 8, 8, "Youtube ") == (7 to 8))
        check(suggestionCompositionRange(" youtube tyrvhikj y", 19, 19, "Youtube tyrvhikj you") == (18 to 19))
        check(suggestionCompositionRange(" y", 2, 2, "foo") == null)
        check(suggestionCompositionRange(" y", 1, 2, "Youtube ") == null)
    }

    private fun testSuggestionCommit() {
        val previous = TextFieldValue(" y", TextRange(2))
        val appended = TextFieldValue(" yYoutube", TextRange(9))
        val adjusted = normalizeImeSuggestion(previous, appended, "Youtube")
        check(adjusted.text == " Youtube")
        val unicode = mutableListOf<Int>()
        val keys = mutableListOf<Int>()
        processImeChange(adjusted, lastEnterTime = 0L, previous = previous,
            sendUnicode = { code, down -> if (down) unicode.add(code) },
            sendKey = { code, down -> if (down) keys.add(code) })
        check(keys == listOf(0x0E) && unicode.joinToString("") { it.toChar().toString() } == "Youtube") {
            "Suggestion did not replace the remote trigger: $keys $unicode"
        }
        check(normalizeImeSuggestion(previous, appended, "outube") == appended) {
            "Nonmatching multi-character commit must append"
        }
        check(normalizeImeSuggestion(previous, appended, null) == appended) {
            "Ordinary edit must not be treated as a suggestion"
        }
        val composing = TextFieldValue(" y", TextRange(2), TextRange(1, 2))
        val firstWord = TextFieldValue("yYoutube ", TextRange(9), TextRange(1, 9))
        val normalized = normalizeImeSuggestion(composing, firstWord, "Youtube ")
        check(normalized.text == " Youtube ") { "First-word suggestion lost its sentinel: ${normalized.text}" }
        unicode.clear(); keys.clear()
        processImeChange(normalized, lastEnterTime = 0L, previous = composing,
            sendUnicode = { code, down -> if (down) unicode.add(code) },
            sendKey = { code, down -> if (down) keys.add(code) })
        check(keys == listOf(0x0E) && unicode.joinToString("") { it.toChar().toString() } == "Youtube ") {
            "First-word suggestion left its initial y on remote: $keys $unicode"
        }
        check(normalizeImeSuggestion(composing, TextFieldValue("yfoobar"), "foobar") == TextFieldValue("yfoobar")) {
            "Unrelated composing text must remain an append"
        }
        val multiWordPrev = TextFieldValue(" youtube tyrvhikj y", TextRange(19))
        val multiWordNext = TextFieldValue(" youtube tyrvhikj Youtube tyrvhikj you", TextRange(38))
        val multiWordAdjusted = normalizeImeSuggestion(multiWordPrev, multiWordNext, "Youtube tyrvhikj you")
        check(multiWordAdjusted.text == " youtube tyrvhikj you") {
            "Multi-word suggestion duplicated committed prefix: ${multiWordAdjusted.text}"
        }
        unicode.clear(); keys.clear()
        processImeChange(multiWordAdjusted, lastEnterTime = 0L, previous = multiWordPrev,
            sendUnicode = { code, down -> if (down) unicode.add(code) },
            sendKey = { code, down -> if (down) keys.add(code) })
        check(keys.isEmpty() && unicode.joinToString("") { it.toChar().toString() } == "ou") {
            "Multi-word suggestion replayed prefix or sent wrong keys: $keys $unicode"
        }
    }

    private fun testKeyboardDismissal() {
        for (buffer in listOf(" ", " word", " word\n", " \uD83D\uDE00")) {
            for (composing in listOf(false, true)) {
                val previous = TextFieldValue(buffer, TextRange(buffer.length),
                    if (composing) TextRange(0, buffer.length) else null)
                val events = mutableListOf<Pair<Int, Boolean>>()
                val time = processImeChange(TextFieldValue(buffer, TextRange(0)),
                    lastEnterTime = 500L, now = 1000L, previous = previous,
                    explicitReplacement = true,
                    sendUnicode = { code, down -> events.add(code to down) },
                    sendKey = { code, down -> events.add(code to down) })
                check(events.isEmpty() && time == 500L) { "IME dismissal replayed unchanged buffer: $events" }
                if ('\n' !in buffer) {
                    val keys = mutableListOf<Pair<Int, Boolean>>()
                    processImeChange(TextFieldValue("$buffer!", TextRange(buffer.length + 1)),
                        lastEnterTime = time, now = 1500L, previous = TextFieldValue(buffer),
                        sendUnicode = { code, down -> events.add(code to down) },
                        sendKey = { code, down -> keys.add(code to down) })
                    check(keys.isEmpty() && events == listOf('!'.code to true, '!'.code to false)) {
                        "Typing after finalization replayed retained buffer: $events $keys"
                    }
                }
            }
        }
    }

    private fun testBufferedEnter() {
        for (previous in listOf(TextFieldValue(" ", TextRange(1)),
            TextFieldValue(" word", TextRange(5), TextRange(1, 5)))) {
            for (buffer in listOf(" word\n", "different\n", " \n")) {
                for (lastEnter in listOf(0L, 900L)) {
                    val unicodes = mutableListOf<Pair<Int, Boolean>>()
                    val keys = mutableListOf<Pair<Int, Boolean>>()
                    val time = processImeChange(TextFieldValue(buffer, TextRange(buffer.length), TextRange(0, buffer.length)),
                        lastEnterTime = lastEnter, now = 1000L, previous = previous, explicitReplacement = true,
                        sendUnicode = { code, down -> unicodes.add(code to down) },
                        sendKey = { code, down -> keys.add(code to down) })
                    check(unicodes.isEmpty()) { "Enter replayed buffered text: $unicodes" }
                    check(keys == if (lastEnter == 0L) listOf(0x1C to true, 0x1C to false) else emptyList()) {
                        "Buffered Enter sent text-editing keys or duplicate Return: $keys"
                    }
                    check(time == if (lastEnter == 0L) 1000L else lastEnter)
                }
            }
        }
    }

    private fun testComposingTyping() {
        val unicodes = mutableListOf<Pair<Int, Boolean>>()
        val keys = mutableListOf<Pair<Int, Boolean>>()
        var previous = TextFieldValue(" ", TextRange(1))
        fun input(value: TextFieldValue) {
            processImeChange(next = value, previous = previous, lastEnterTime = 0L, now = 1000L,
                sendUnicode = { code, down -> unicodes.add(code to down) },
                sendKey = { code, down -> keys.add(code to down) })
            previous = if (value.composition != null) value else TextFieldValue(" ", TextRange(1))
        }
        for (prefix in listOf("w", "wo", "wor", "word")) {
            input(TextFieldValue(" $prefix", TextRange(prefix.length + 1), TextRange(1, prefix.length + 1)))
            check(unicodes.filter { it.second }.map { it.first } == prefix.map { it.code }) { "Typing delayed or duplicated composing prefix" }
            check(keys.isEmpty())
        }
        input(TextFieldValue(" word", TextRange(5)))
        check(unicodes.size == 8 && keys.isEmpty()) { "Finishing composition resent the word" }
        unicodes.clear()
        input(TextFieldValue(" b", TextRange(2), TextRange(1, 2)))
        check(unicodes == listOf('b'.code to true, 'b'.code to false)) { "Composing b must be immediate" }
        input(TextFieldValue(" /", TextRange(2)))
        check(keys == listOf(0x0E to true, 0x0E to false)) { "Alternative did not erase composing b" }
        check(unicodes.filter { it.second }.map { it.first } == listOf('b'.code, '/'.code))
        unicodes.clear(); keys.clear()
        val emoji = "\uD83D\uDE00"
        input(TextFieldValue(" $emoji", TextRange(3), TextRange(1, 3)))
        input(TextFieldValue(" $emoji!", TextRange(4), TextRange(1, 4)))
        input(TextFieldValue(" !", TextRange(2)))
        check(unicodes.filter { it.second }.map { it.first } == listOf(0x1F600, '!'.code, '!'.code))
        check(keys.size == 4) { "Surrogate-pair replacement must erase two code points, not UTF-16 units" }
    }

    private fun testAlternativeKeys() {
        val testCases = listOf('/', '\\', ';', ':', '?', '!', '@', '#', '$', '%', '^', '&', '*', '(', ')', '_', '+', '=', 'é')
        for (altChar in testCases) {
            val unicodes = mutableListOf<Pair<Int, Boolean>>()
            val keys = mutableListOf<Pair<Int, Boolean>>()

            // Horizon OS popup selection invokes replaceText(0, 1, altChar, 1),
            // replacing the dummy space " " with altChar, resulting in next.text = "$altChar"
            val next = TextFieldValue(altChar.toString(), selection = TextRange(1))
            val newEnterTime = processImeChange(
                next = next,
                lastEnterTime = 0L,
                now = 1000L,
                sendUnicode = { code, down -> unicodes.add(code to down) },
                sendKey = { code, down -> keys.add(code to down) }
            )

            // Must send Backspace (0x0E) first to erase the initial character committed on trigger down
            check(keys.size == 2) { "Alternative key '$altChar' did not send backspace down+up: $keys" }
            check(keys[0] == (0x0E to true)) { "Alternative key '$altChar' backspace down mismatch: ${keys[0]}" }
            check(keys[1] == (0x0E to false)) { "Alternative key '$altChar' backspace up mismatch: ${keys[1]}" }

            // Followed by the alternative character unicode down+up
            check(unicodes.size == 2) { "Alternative key '$altChar' did not produce unicode down+up: $unicodes" }
            check(unicodes[0] == (altChar.code to true)) { "Alternative key '$altChar' down mismatch: ${unicodes[0]}" }
            check(unicodes[1] == (altChar.code to false)) { "Alternative key '$altChar' up mismatch: ${unicodes[1]}" }
            check(newEnterTime == 0L) { "Enter time modified unexpectedly" }
        }
    }

    private fun testNormalTyping() {
        val chars = listOf('a', 'b', 'Z', '9', 'm')
        for (char in chars) {
            val unicodes = mutableListOf<Pair<Int, Boolean>>()
            val keys = mutableListOf<Pair<Int, Boolean>>()

            // Normal typing calls commitText(char, 1), appending after the dummy space " "
            val next = TextFieldValue(" $char", selection = TextRange(2))
            processImeChange(
                next = next,
                lastEnterTime = 0L,
                now = 1000L,
                sendUnicode = { code, down -> unicodes.add(code to down) },
                sendKey = { code, down -> keys.add(code to down) }
            )

            check(keys.isEmpty()) { "Normal typing '$char' triggered unexpected key codes: $keys" }
            check(unicodes.size == 2) { "Normal typing '$char' event count mismatch: $unicodes" }
            check(unicodes[0] == (char.code to true)) { "Normal typing '$char' down mismatch: ${unicodes[0]}" }
            check(unicodes[1] == (char.code to false)) { "Normal typing '$char' up mismatch: ${unicodes[1]}" }
        }
    }

    private fun testSpaceTyping() {
        val unicodes = mutableListOf<Pair<Int, Boolean>>()
        val keys = mutableListOf<Pair<Int, Boolean>>()

        // Space key calls commitText(" ", 1), appending after the dummy space -> "  "
        val next = TextFieldValue("  ", selection = TextRange(2))
        processImeChange(
            next = next,
            lastEnterTime = 0L,
            now = 1000L,
            sendUnicode = { code, down -> unicodes.add(code to down) },
            sendKey = { code, down -> keys.add(code to down) }
        )

        check(keys.isEmpty()) { "Space triggered unexpected key codes: $keys" }
        check(unicodes.size == 2) { "Space event count mismatch: $unicodes" }
        check(unicodes[0] == (0x20 to true)) { "Space down mismatch: ${unicodes[0]}" }
        check(unicodes[1] == (0x20 to false)) { "Space up mismatch: ${unicodes[1]}" }
    }

    private fun testEmptyBufferBackspace() {
        val unicodes = mutableListOf<Pair<Int, Boolean>>()
        val keys = mutableListOf<Pair<Int, Boolean>>()

        // Backspace deletes the dummy space, resulting in empty string
        val next = TextFieldValue("", selection = TextRange(0))
        processImeChange(
            next = next,
            lastEnterTime = 0L,
            now = 1000L,
            sendUnicode = { code, down -> unicodes.add(code to down) },
            sendKey = { code, down -> keys.add(code to down) }
        )

        check(unicodes.isEmpty()) { "Backspace triggered unexpected unicodes: $unicodes" }
        check(keys.size == 2) { "Backspace event count mismatch: $keys" }
        check(keys[0] == (0x0E to true)) { "Backspace down mismatch: ${keys[0]}" }
        check(keys[1] == (0x0E to false)) { "Backspace up mismatch: ${keys[1]}" }
        // Subsequent backspace on empty buffer must continue sending backspace
        keys.clear()
        processImeChange(
            next = TextFieldValue("", selection = TextRange(0)),
            lastEnterTime = 0L,
            now = 1100L,
            previous = TextFieldValue(" ", selection = TextRange(1)),
            sendUnicode = { code, down -> unicodes.add(code to down) },
            sendKey = { code, down -> keys.add(code to down) }
        )
        check(keys == listOf(0x0E to true, 0x0E to false)) { "Subsequent backspace failed: $keys" }
    }

    private fun testEnterDebounce() {
        val unicodes = mutableListOf<Pair<Int, Boolean>>()
        val keys = mutableListOf<Pair<Int, Boolean>>()

        // Initial enter commit
        val next = TextFieldValue(" \n", selection = TextRange(2))
        val t1 = processImeChange(
            next = next,
            lastEnterTime = 0L,
            now = 1000L,
            sendUnicode = { code, down -> unicodes.add(code to down) },
            sendKey = { code, down -> keys.add(code to down) }
        )

        check(keys.size == 2 && keys[0] == (0x1C to true) && keys[1] == (0x1C to false)) { "Enter key failed: $keys" }
        check(t1 == 1000L) { "t1 not updated" }

        // Second enter within 150ms (< 200ms debounce threshold)
        keys.clear()
        val t2 = processImeChange(
            next = next,
            lastEnterTime = t1,
            now = 1150L,
            sendUnicode = { code, down -> unicodes.add(code to down) },
            sendKey = { code, down -> keys.add(code to down) }
        )
        check(keys.isEmpty()) { "Rapid enter within 150ms was not debounced: $keys" }
        check(t2 == t1) { "t2 should remain t1 when debounced" }

        // Third enter after 250ms (> 200ms debounce threshold)
        keys.clear()
        val t3 = processImeChange(
            next = next,
            lastEnterTime = t2,
            now = 1300L,
            sendUnicode = { code, down -> unicodes.add(code to down) },
            sendKey = { code, down -> keys.add(code to down) }
        )
        check(keys.size == 2 && keys[0] == (0x1C to true) && keys[1] == (0x1C to false)) { "Enter after 250ms not sent: $keys" }
        check(t3 == 1300L) { "t3 not updated" }
    }

    private fun testMultiCharacterReplacement() {
        val unicodes = mutableListOf<Pair<Int, Boolean>>()
        val keys = mutableListOf<Pair<Int, Boolean>>()

        // Autocomplete or multi-character paste replacing dummy space
        val next = TextFieldValue("https://", selection = TextRange(8))
        processImeChange(
            next = next,
            lastEnterTime = 0L,
            now = 1000L,
            sendUnicode = { code, down -> unicodes.add(code to down) },
            sendKey = { code, down -> keys.add(code to down) }
        )

        // Must send backspace to erase previous character, then the full replacement
        check(keys.size == 2 && keys[0] == (0x0E to true) && keys[1] == (0x0E to false)) { "Replacement backspace failed: $keys" }
        val sentString = unicodes.filter { it.second }.map { it.first.toChar() }.joinToString("")
        check(sentString == "https://") { "Expected 'https://', got '$sentString'" }
    }

    private fun testEmojiInput() {
        val emoji = "😀" // U+1F600 (surrogate pair \uD83D\uDE00)
        val expectedCodePoint = 0x1F600

        // 1. Emoji typed normally (appended after space)
        val unicodes = mutableListOf<Pair<Int, Boolean>>()
        val keys = mutableListOf<Pair<Int, Boolean>>()
        val nextNormal = TextFieldValue(" $emoji", selection = TextRange(3))
        processImeChange(
            next = nextNormal,
            lastEnterTime = 0L,
            now = 1000L,
            sendUnicode = { code, down -> unicodes.add(code to down) },
            sendKey = { code, down -> keys.add(code to down) }
        )

        check(keys.isEmpty()) { "Normal emoji typing triggered keys: $keys" }
        check(unicodes.size == 2) { "Emoji did not dispatch single code point down+up: $unicodes" }
        check(unicodes[0] == (expectedCodePoint to true)) { "Emoji down mismatch: ${unicodes[0]}" }
        check(unicodes[1] == (expectedCodePoint to false)) { "Emoji up mismatch: ${unicodes[1]}" }

        // 2. Emoji replacing previous character
        unicodes.clear()
        keys.clear()
        val nextReplace = TextFieldValue(emoji, selection = TextRange(2))
        processImeChange(
            next = nextReplace,
            lastEnterTime = 0L,
            now = 1000L,
            sendUnicode = { code, down -> unicodes.add(code to down) },
            sendKey = { code, down -> keys.add(code to down) }
        )

        check(keys.size == 2 && keys[0] == (0x0E to true) && keys[1] == (0x0E to false)) { "Emoji replacement backspace failed: $keys" }
        check(unicodes.size == 2) { "Emoji replacement did not dispatch single code point: $unicodes" }
        check(unicodes[0] == (expectedCodePoint to true)) { "Emoji replacement down mismatch: ${unicodes[0]}" }
        check(unicodes[1] == (expectedCodePoint to false)) { "Emoji replacement up mismatch: ${unicodes[1]}" }
    }
}
