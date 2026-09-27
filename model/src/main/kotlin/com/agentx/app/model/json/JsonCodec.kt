package com.agentx.app.model.json

import kotlin.math.abs
import kotlin.math.floor

/** Raised when a string is not valid JSON. */
class JsonParseException(message: String) : RuntimeException(message)

/**
 * Parses and serializes [JsonValue] without a third-party dependency. Only the
 * provider layer should need this; higher layers work with typed contracts.
 */
object JsonCodec {

    fun encode(value: JsonValue): String = buildString { write(value, this) }

    fun encodeObject(fields: JsonObject): String = encode(JsonValue.Obj(fields))

    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.readValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) {
            throw JsonParseException("Unexpected trailing content at index ${parser.index}")
        }
        return value
    }

    // --- serialization -----------------------------------------------------

    private fun write(value: JsonValue, out: StringBuilder) {
        when (value) {
            is JsonValue.Null -> out.append("null")
            is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
            is JsonValue.Num -> out.append(encodeNumber(value.value))
            is JsonValue.Str -> writeString(value.value, out)
            is JsonValue.Arr -> {
                out.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }
            is JsonValue.Obj -> {
                out.append('{')
                var first = true
                for ((key, field) in value.fields) {
                    if (!first) out.append(',')
                    first = false
                    writeString(key, out)
                    out.append(':')
                    write(field, out)
                }
                out.append('}')
            }
        }
    }

    private fun encodeNumber(value: Double): String {
        if (!value.isFinite()) return "0"
        if (value == floor(value) && abs(value) < 1e15) return value.toLong().toString()
        return value.toString()
    }

    private fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        for (character in value) {
            when (character) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (character < ' ') {
                    out.append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(character)
                }
            }
        }
        out.append('"')
    }

    // --- parsing -----------------------------------------------------------

    private class Parser(private val text: String) {
        var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun readValue(): JsonValue {
            skipWhitespace()
            if (atEnd()) throw JsonParseException("Unexpected end of input")
            return when (val character = text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.Str(readString())
                't' -> readLiteral("true", JsonValue.Bool(true))
                'f' -> readLiteral("false", JsonValue.Bool(false))
                'n' -> readLiteral("null", JsonValue.Null)
                else -> if (character == '-' || character.isDigit()) {
                    readNumber()
                } else {
                    throw JsonParseException("Unexpected character '$character' at index $index")
                }
            }
        }

        private fun readObject(): JsonValue {
            expect('{')
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                fields[key] = readValue()
                skipWhitespace()
                when (val character = next()) {
                    ',' -> Unit
                    '}' -> return JsonValue.Obj(fields)
                    else -> throw JsonParseException("Expected ',' or '}' but found '$character' at index ${index - 1}")
                }
            }
        }

        private fun readArray(): JsonValue {
            expect('[')
            val items = mutableListOf<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return JsonValue.Arr(items)
            }
            while (true) {
                items += readValue()
                skipWhitespace()
                when (val character = next()) {
                    ',' -> Unit
                    ']' -> return JsonValue.Arr(items)
                    else -> throw JsonParseException("Expected ',' or ']' but found '$character' at index ${index - 1}")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonParseException("Unterminated string")
                when (val character = text[index++]) {
                    '"' -> return builder.toString()
                    '\\' -> builder.append(readEscape())
                    else -> builder.append(character)
                }
            }
        }

        private fun readEscape(): Char {
            if (atEnd()) throw JsonParseException("Unterminated escape sequence")
            return when (val character = text[index++]) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> readUnicodeEscape()
                else -> throw JsonParseException("Invalid escape '\\$character' at index ${index - 1}")
            }
        }

        private fun readUnicodeEscape(): Char {
            if (index + 4 > text.length) throw JsonParseException("Invalid unicode escape")
            val hex = text.substring(index, index + 4)
            index += 4
            return hex.toIntOrNull(16)?.toChar()
                ?: throw JsonParseException("Invalid unicode escape '\\u$hex'")
        }

        private fun readNumber(): JsonValue {
            val start = index
            if (peek() == '-') index++
            while (!atEnd() && text[index].isDigit()) index++
            if (peek() == '.') {
                index++
                while (!atEnd() && text[index].isDigit()) index++
            }
            if (peek() == 'e' || peek() == 'E') {
                index++
                if (peek() == '+' || peek() == '-') index++
                while (!atEnd() && text[index].isDigit()) index++
            }
            val raw = text.substring(start, index)
            val value = raw.toDoubleOrNull() ?: throw JsonParseException("Invalid number '$raw' at index $start")
            return JsonValue.Num(value)
        }

        private fun <T : JsonValue> readLiteral(literal: String, value: T): T {
            if (!text.startsWith(literal, index)) {
                throw JsonParseException("Expected '$literal' at index $index")
            }
            index += literal.length
            return value
        }

        private fun expect(character: Char) {
            if (atEnd() || text[index] != character) {
                throw JsonParseException("Expected '$character' at index $index")
            }
            index++
        }

        private fun peek(): Char = if (atEnd()) '\u0000' else text[index]

        private fun next(): Char {
            if (atEnd()) throw JsonParseException("Unexpected end of input")
            return text[index++]
        }
    }
}
