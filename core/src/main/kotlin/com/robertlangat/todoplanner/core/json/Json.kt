package com.robertlangat.todoplanner.core.json

/**
 * Tiny dependency-free JSON reader/writer used for sync snapshots, backups, the Wear data payload
 * and AI responses. Values map to: null, Boolean, Long, Double, String, List<Any?>, Map<String, Any?>.
 */
object Json {
    class ParseException(message: String) : Exception(message)

    fun parse(text: String): Any? = Parser(text).run {
        skipWs()
        val v = readValue(0)
        skipWs()
        if (pos != text.length) fail("Trailing characters")
        v
    }

    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> if (v.isFinite()) sb.append(if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()) else sb.append("null")
            is Float -> write(sb, v.toDouble())
            is String -> writeString(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString()); sb.append(':'); write(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) { if (!first) sb.append(','); first = false; write(sb, item) }
                sb.append(']')
            }
            else -> writeString(sb, v.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val text: String) {
        var pos = 0

        fun fail(msg: String): Nothing = throw ParseException("$msg at $pos")

        fun skipWs() { while (pos < text.length && text[pos].isWhitespace()) pos++ }

        fun readValue(depth: Int): Any? {
            if (depth > 64) fail("Nesting too deep")
            if (pos >= text.length) fail("Unexpected end")
            return when (val c = text[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else fail("Unexpected '$c'")
            }
        }

        fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) fail("Expected $word")
            pos += word.length
            return value
        }

        fun readObject(depth: Int): Map<String, Any?> {
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < text.length && text[pos] == '}') { pos++; return map }
            while (true) {
                skipWs()
                if (pos >= text.length || text[pos] != '"') fail("Expected key")
                val key = readString()
                skipWs()
                if (pos >= text.length || text[pos] != ':') fail("Expected ':'")
                pos++
                skipWs()
                map[key] = readValue(depth + 1)
                skipWs()
                if (pos >= text.length) fail("Unterminated object")
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        fun readArray(depth: Int): List<Any?> {
            pos++ // [
            val list = ArrayList<Any?>()
            skipWs()
            if (pos < text.length && text[pos] == ']') { pos++; return list }
            while (true) {
                skipWs()
                list += readValue(depth + 1)
                skipWs()
                if (pos >= text.length) fail("Unterminated array")
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> fail("Expected ',' or ']'")
                }
            }
        }

        fun readString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) fail("Unterminated string")
                when (val c = text[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= text.length) fail("Bad escape")
                        when (val e = text[pos++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) fail("Bad unicode escape")
                                sb.append(text.substring(pos, pos + 4).toIntOrNull(16)?.toChar() ?: fail("Bad unicode escape"))
                                pos += 4
                            }
                            else -> fail("Bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun readNumber(): Any {
            val start = pos
            if (text[pos] == '-') pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
            val s = text.substring(start, pos)
            return if (s.any { it in ".eE" }) s.toDoubleOrNull() ?: fail("Bad number")
            else s.toLongOrNull() ?: s.toDoubleOrNull() ?: fail("Bad number")
        }
    }
}

/** Convenience accessors for parsed JSON maps. */
@Suppress("UNCHECKED_CAST")
fun Any?.asObject(): Map<String, Any?>? = this as? Map<String, Any?>
fun Any?.asList(): List<Any?>? = this as? List<Any?>
fun Map<String, Any?>.str(key: String): String? = this[key] as? String
fun Map<String, Any?>.long(key: String): Long? = when (val v = this[key]) {
    is Long -> v; is Double -> v.toLong(); is String -> v.toLongOrNull(); else -> null
}
fun Map<String, Any?>.int(key: String): Int? = long(key)?.toInt()
fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean
