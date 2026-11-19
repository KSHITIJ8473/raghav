package com.laddu100.rareanimes

import android.util.Base64

object JuicyCodes {

    private val SYMBOLS = charArrayOf('`', '%', '-', '+', '*', '$', '!', '_', '^', '=')

    fun decode(payload: String): String? {
        return try {
            if (payload.length < 8) return null
            val salt = payload.takeLast(3)
            val offset = buildString {
                salt.forEach { append(it.code - 100) }
            }.toLongOrNull() ?: return null

            val body = payload.dropLast(3)
                .replace('_', '+')
                .replace('-', '/')
                .let { it + "=".repeat((4 - it.length % 4) % 4) }

            val decoded = String(Base64.decode(body, Base64.DEFAULT), Charsets.ISO_8859_1)

            val digits = StringBuilder()
            for (c in decoded) {
                val idx = SYMBOLS.indexOf(c)
                if (idx < 0) return null
                digits.append(idx)
            }

            val out = StringBuilder()
            var i = 0
            while (i + 4 <= digits.length) {
                val quad = digits.substring(i, i + 4).toLongOrNull() ?: return null
                val code = ((quad % 1000L) - offset).toInt()
                if (code < 9 || code > 126) return null
                out.append(code.toChar())
                i += 4
            }
            out.toString()
        } catch (e: Exception) {
            null
        }
    }

    fun decodeFromHtml(html: String): String? {
        val start = html.indexOf("_juicycodes(")
        if (start == -1) return null
        val end = html.indexOf(");", start)
        if (end == -1) return null
        val call = html.substring(start + 12, end)
        val sb = StringBuilder()
        var inQuote = false
        for (c in call) {
            when {
                c == '"' -> inQuote = !inQuote
                inQuote && c != '"' -> sb.append(c)
            }
        }
        if (sb.isEmpty()) return null
        return decode(sb.toString())
    }
}
