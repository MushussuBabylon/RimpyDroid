package com.rimpydroid.manager.core

/**
 * Tiny dependency-free JSON reader / writer (JDK only, so it also runs in plain JVM unit tests).
 *
 * Parsed values are: Map<String, Any?> (insertion ordered), List<Any?>, String, Long, Double,
 * Boolean or null.
 */
object Json {

    fun parse(text: String): Any? {
        val p = JsonParser(text)
        p.skipWs()
        val v = p.readValue(0)
        p.skipWs()
        if (!p.atEnd()) throw JsonException("Unexpected data after JSON value at ${p.pos}")
        return v
    }

    /** Like [parse] but returns null instead of throwing. */
    fun parseOrNull(text: String?): Any? {
        if (text == null) return null
        return try {
            parse(text)
        } catch (e: Exception) {
            null
        }
    }

    fun stringify(value: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder()
        writeValue(sb, value, if (pretty) 0 else -1)
        return sb.toString()
    }

    // ---- writer ---------------------------------------------------------------------------

    private fun newline(sb: StringBuilder, indent: Int) {
        sb.append('\n')
        for (i in 0 until indent) sb.append("  ")
    }

    private fun writeValue(sb: StringBuilder, v: Any?, indent: Int) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Double -> if (v.isNaN() || v.isInfinite()) sb.append("null") else sb.append(v.toString())
            is Float -> if (v.isNaN() || v.isInfinite()) sb.append("null") else sb.append(v.toString())
            is Number -> sb.append(v.toString())
            is Map<*, *> -> {
                if (v.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append('{')
                var first = true
                for (entry in v.entries) {
                    if (!first) sb.append(',')
                    first = false
                    if (indent >= 0) newline(sb, indent + 1)
                    writeString(sb, entry.key.toString())
                    sb.append(if (indent >= 0) ": " else ":")
                    writeValue(sb, entry.value, if (indent >= 0) indent + 1 else -1)
                }
                if (indent >= 0) newline(sb, indent)
                sb.append('}')
            }
            is Iterable<*> -> {
                val items = v.toList()
                if (items.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append('[')
                var first = true
                for (item in items) {
                    if (!first) sb.append(',')
                    first = false
                    if (indent >= 0) newline(sb, indent + 1)
                    writeValue(sb, item, if (indent >= 0) indent + 1 else -1)
                }
                if (indent >= 0) newline(sb, indent)
                sb.append(']')
            }
            else -> writeString(sb, v.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (ch < ' ') {
                        sb.append("\\u")
                        val hex = Integer.toHexString(ch.code)
                        for (i in hex.length until 4) sb.append('0')
                        sb.append(hex)
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
    }
}

class JsonException(message: String) : Exception(message)

private class JsonParser(private val s: String) {
    var pos = 0

    fun atEnd(): Boolean = pos >= s.length

    fun skipWs() {
        while (pos < s.length) {
            val c = s[pos]
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '﻿') pos++ else break
        }
    }

    fun readValue(depth: Int): Any? {
        if (depth > 200) throw JsonException("JSON nested too deeply")
        skipWs()
        if (atEnd()) throw JsonException("Unexpected end of JSON")
        val c = s[pos]
        return when {
            c == '{' -> readObject(depth)
            c == '[' -> readArray(depth)
            c == '"' -> readString()
            c == 't' -> literal("true", true)
            c == 'f' -> literal("false", false)
            c == 'n' -> literal("null", null)
            c == '-' || (c in '0'..'9') -> readNumber()
            else -> throw JsonException("Unexpected character '$c' at $pos")
        }
    }

    private fun literal(word: String, value: Any?): Any? {
        if (!s.startsWith(word, pos)) throw JsonException("Invalid literal at $pos")
        pos += word.length
        return value
    }

    private fun readObject(depth: Int): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        pos++ // {
        skipWs()
        if (pos < s.length && s[pos] == '}') {
            pos++
            return map
        }
        while (true) {
            skipWs()
            if (atEnd() || s[pos] != '"') throw JsonException("Expected object key at $pos")
            val key = readString()
            skipWs()
            if (atEnd() || s[pos] != ':') throw JsonException("Expected ':' at $pos")
            pos++
            val value = readValue(depth + 1)
            map[key] = value
            skipWs()
            if (atEnd()) throw JsonException("Unterminated object")
            val c = s[pos]
            if (c == ',') {
                pos++
                continue
            }
            if (c == '}') {
                pos++
                return map
            }
            throw JsonException("Expected ',' or '}' at $pos")
        }
    }

    private fun readArray(depth: Int): List<Any?> {
        val list = ArrayList<Any?>()
        pos++ // [
        skipWs()
        if (pos < s.length && s[pos] == ']') {
            pos++
            return list
        }
        while (true) {
            list.add(readValue(depth + 1))
            skipWs()
            if (atEnd()) throw JsonException("Unterminated array")
            val c = s[pos]
            if (c == ',') {
                pos++
                continue
            }
            if (c == ']') {
                pos++
                return list
            }
            throw JsonException("Expected ',' or ']' at $pos")
        }
    }

    private fun readString(): String {
        pos++ // opening quote
        val sb = StringBuilder()
        while (true) {
            if (atEnd()) throw JsonException("Unterminated string")
            val c = s[pos++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (atEnd()) throw JsonException("Bad escape at end")
                    val e = s[pos++]
                    when (e) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > s.length) throw JsonException("Bad unicode escape")
                            val code = s.substring(pos, pos + 4).toIntOrNull(16)
                                ?: throw JsonException("Bad unicode escape at $pos")
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> throw JsonException("Bad escape '\\$e' at ${pos - 1}")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun readNumber(): Any {
        val start = pos
        if (s[pos] == '-') pos++
        while (pos < s.length) {
            val c = s[pos]
            if ((c in '0'..'9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') pos++ else break
        }
        val text = s.substring(start, pos)
        if (text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0) {
            val l = text.toLongOrNull()
            if (l != null) return l
        }
        return text.toDoubleOrNull() ?: throw JsonException("Bad number '$text' at $start")
    }
}

// ---- convenience accessors ----------------------------------------------------------------------

@Suppress("UNCHECKED_CAST")
fun Any?.asObj(): Map<String, Any?>? = this as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Any?.asArr(): List<Any?>? = this as? List<Any?>

fun Any?.asStr(): String? = when (this) {
    is String -> this
    is Number -> this.toString()
    is Boolean -> this.toString()
    else -> null
}

fun Any?.asLong(): Long? = when (this) {
    is Long -> this
    is Int -> this.toLong()
    is Double -> this.toLong()
    is String -> this.trim().toLongOrNull()
    else -> null
}

fun Any?.asInt(): Int? = this.asLong()?.toInt()

fun Any?.asBool(): Boolean? = when (this) {
    is Boolean -> this
    is String -> if (this.equals("true", ignoreCase = true)) true else if (this.equals("false", ignoreCase = true)) false else null
    is Number -> this.toInt() != 0
    else -> null
}
