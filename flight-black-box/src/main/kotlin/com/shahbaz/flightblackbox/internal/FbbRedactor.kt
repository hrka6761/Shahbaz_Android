package com.shahbaz.flightblackbox.internal

import com.shahbaz.flightblackbox.FbbConfig
import java.util.Locale

internal class FbbRedactor(private val config: FbbConfig) {
    fun metadata(metadata: Map<String, Any?>): Map<String, String> =
        metadata.toSortedMap().mapValues { (key, value) ->
            if (isSensitiveKey(key)) {
                "<REDACTED>"
            } else {
                valueToString(value)
            }
        }

    fun valueToString(value: Any?): String {
        val raw = when (value) {
            null -> "null"
            is Throwable -> "${value.javaClass.simpleName}: ${value.message.orEmpty()}"
            is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { entry ->
                val key = entry.key?.toString().orEmpty()
                val valueText = if (isSensitiveKey(key)) "<REDACTED>" else valueToString(entry.value)
                "$key=$valueText"
            }
            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { valueToString(it) }
            is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { valueToString(it) }
            is ByteArray -> "bytes(size=${value.size}, sha256=${value.sha256Short()})"
            else -> value.toString()
        }
        return sanitize(raw).take(config.maxInlineValueLength)
    }

    fun detail(text: String): String = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .take(config.maxDetailLength)

    private fun sanitize(value: String): String = value
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('|', '/')
        .trim()

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.US)
        return SensitiveFragments.any { normalized.contains(it) }
    }

    private companion object {
        val SensitiveFragments = listOf(
            "password",
            "passwd",
            "token",
            "authorization",
            "auth",
            "secret",
            "api_key",
            "apikey",
            "credential",
            "sessiontoken",
            "accesskey",
        )
    }
}
