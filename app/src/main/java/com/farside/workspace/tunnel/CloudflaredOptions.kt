package com.farside.workspace.tunnel

import java.net.IDN

/** Parse values as argv, never as shell code. Reject the entire input on any unknown option. */
object CloudflaredOptions {
    fun hostname(input: String): String {
        val host = try { IDN.toASCII(input.trim(), IDN.USE_STD3_ASCII_RULES).lowercase() }
            catch (_: Exception) { throw IllegalArgumentException("Cloudflared needs a DNS hostname without a URL, port or path.") }
        require(host.length in 1..253 && host.split('.').all {
            it.length in 1..63 && it.first().isLetterOrDigit() && it.last().isLetterOrDigit() &&
                it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' }
        }) { "Cloudflared needs a DNS hostname without a URL, port or path." }
        return host
    }

    fun parse(input: String): List<String> {
        require(input.length <= 8192) { "Cloudflared parameters are too long." }
        require(input.none { it.isISOControl() || it in ";&|`$<>\\" }) {
            "Shell operators, escapes and control characters are not allowed."
        }
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
        require(quote == null) { "Close the quote in Cloudflared parameters." }
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
                else -> throw IllegalArgumentException("Unsupported Cloudflared option. Allowed: --service-token-id, --service-token-secret, --header (-H).")
            }
            val value = if ('=' in option) option.substringAfter('=') else {
                require(index < words.size) { "Each Cloudflared option needs a value." }
                words[index++]
            }
            require(value.isNotBlank() && value.length <= 4096 && !value.startsWith('-')) { "Invalid Cloudflared option value." }
            if (name == "--header") {
                val header = value.substringBefore(':').lowercase()
                require(':' in value && header in setOf("cf-access-token", "cf-access-client-id", "cf-access-client-secret")) {
                    "Only Cloudflare Access authentication headers are allowed."
                }
                require(value.substringAfter(':').trim().isNotEmpty() && seen.add(header)) { "Empty or duplicate authentication header." }
            } else {
                require(value.none { it.isWhitespace() } && seen.add(name)) { "Invalid or duplicate service token option." }
            }
            result.add(name)
            result.add(value)
        }
        require(("--service-token-id" in seen) == ("--service-token-secret" in seen)) { "Supply both the service token ID and secret." }
        require(("cf-access-client-id" in seen) == ("cf-access-client-secret" in seen)) { "Supply both Cloudflare client authentication headers." }
        require(!("--service-token-id" in seen && "cf-access-client-id" in seen)) { "Use either service token options or client headers." }
        return result
    }
}
