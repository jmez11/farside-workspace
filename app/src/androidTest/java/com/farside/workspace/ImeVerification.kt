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
        testKeyboardDismissal()
        return "All IME input scenarios verified: alternative keys, standard typing, space, empty backspace, buffered Enter without text replay, enter debouncing, multi-char replacements, composing typing, silent dismissal callbacks, and emojis."
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
