package com.farside.workspace.tunnel

import java.net.IDN
import com.farside.workspace.LocalizableException
import com.farside.workspace.R
import androidx.annotation.StringRes

/** Parse values as argv, never as shell code. Reject the entire input on any unknown option. */
object CloudflaredOptions {
    private fun validate(valid: Boolean, @StringRes resId: Int) {
        if (!valid) throw LocalizableException(resId)
    }
    fun hostname(input: String): String {
        val host = try { IDN.toASCII(input.trim(), IDN.USE_STD3_ASCII_RULES).lowercase() }
            catch (_: Exception) { throw LocalizableException(R.string.cf_hostname_invalid) }
        validate(host.length in 1..253 && host.split('.').all {
            it.length in 1..63 && it.first().isLetterOrDigit() && it.last().isLetterOrDigit() &&
                it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' }
        }, R.string.cf_hostname_invalid)
        return host
    }

    fun parse(input: String): List<String> {
        validate(input.length <= 8192, R.string.cf_parameters_too_long)
        validate(input.none { it.isISOControl() || it in ";&|`$<>\\" }, R.string.cf_shell_operators_invalid)
        val words = mutableListOf<String>()
        val word = StringBuilder()
        var quote: Char? = null
        var started = false
        for (c in input) {
            when {
                quote != null -> if (c == quote) quote = null else word.append(c)
                c == '\'' || c == '"' -> { quote = c; started = true }
                c == ' ' -> if (started) { words.add(word.toString()); word.clear(); started = false }
                else -> { word.append(c); started = true }
            }
        }
        validate(quote == null, R.string.cf_quote_unclosed)
        if (started) words.add(word.toString())
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var index = 0
        while (index < words.size) {
            val option = words[index++]
            val rawName = option.substringBefore('=')
            val name = when (rawName) {
                "--service-token-id", "--id" -> "--service-token-id"
                "--service-token-secret", "--secret" -> "--service-token-secret"
                "--header", "-H" -> "--header"
                else -> throw LocalizableException(R.string.cf_option_unsupported)
            }
            val value = if ('=' in option) option.substringAfter('=') else {
                validate(index < words.size, R.string.cf_option_missing_value)
                words[index++]
            }
            validate(value.isNotBlank() && value.length <= 4096 && !value.startsWith('-'), R.string.cf_option_invalid_value)
            if (name == "--header") {
                val header = value.substringBefore(':').lowercase()
                validate(':' in value && header in setOf("cf-access-token", "cf-access-client-id", "cf-access-client-secret"), R.string.cf_header_not_allowed)
                validate(value.substringAfter(':').trim().isNotEmpty() && seen.add(header), R.string.cf_header_empty_duplicate)
            } else {
                validate(value.none { it.isWhitespace() } && seen.add(name), R.string.cf_token_invalid_duplicate)
            }
            result.add(name)
            result.add(value)
        }
        validate(("--service-token-id" in seen) == ("--service-token-secret" in seen), R.string.cf_token_pair_required)
        validate(("cf-access-client-id" in seen) == ("cf-access-client-secret" in seen), R.string.cf_header_pair_required)
        validate(!("--service-token-id" in seen && "cf-access-client-id" in seen), R.string.cf_token_headers_exclusive)
        return result
    }
}
