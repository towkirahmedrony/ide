package com.agentx.app.integrations.oauth

/**
 * Minimal JSON reader for OAuth responses.
 *
 * The integrations module deliberately avoids a JSON dependency (dependency
 * versions are pinned elsewhere), and the OAuth layer only needs to read flat
 * token responses and a couple of provider objects. Values read here are treated
 * as secrets by the callers: nothing in this file logs or echoes them.
 */
sealed interface OAuthJsonValue {

    data class Obj(val fields: Map<String, OAuthJsonValue>) : OAuthJsonValue

    data class Arr(val items: List<OAuthJsonValue>) : OAuthJsonValue

    data class Str(val value: String) : OAuthJsonValue

    data class Num(val value: Double) : OAuthJsonValue

    data class Bool(val value: Boolean) : OAuthJsonValue

    data object Null : OAuthJsonValue
}

/** Convenience readers used by the providers. */
fun OAuthJsonValue?.string(field: String): String? =
    (this as? OAuthJsonValue.Obj)?.fields?.get(field)?.let { value ->
        when (value) {
            is OAuthJsonValue.Str -> value.value
            is OAuthJsonValue.Num -> value.value.toString()
            is OAuthJsonValue.Bool -> value.value.toString()
            else -> null
        }
    }?.takeIf { it.isNotBlank() }

fun OAuthJsonValue?.long(field: String): Long? =
    (this as? OAuthJsonValue.Obj)?.fields?.get(field)?.let { value ->
        when (value) {
            is OAuthJsonValue.Num -> value.value.toLong()
            is OAuthJsonValue.Str -> value.value.toLongOrNull()
            else -> null
        }
    }

/** First element of a top-level array, if the payload is one. */
fun OAuthJsonValue?.firstElement(): OAuthJsonValue? =
    (this as? OAuthJsonValue.Arr)?.items?.firstOrNull()

object OAuthJson {

    /** Returns null when [text] is not valid JSON. */
    fun parse(text: String): OAuthJsonValue? {
        val reader = Reader(text)
        return runCatching {
            reader.skipWhitespace()
            val value = reader.readValue()
            reader.skipWhitespace()
            value
        }.getOrNull()
    }

    private class Reader(private val text: String) {
        private var index = 0

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun readValue(): OAuthJsonValue {
            skipWhitespace()
            if (index >= text.length) throw IllegalArgumentException("Unexpected end of JSON")
            return when (val char = text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> OAuthJsonValue.Str(readString())
                't' -> readLiteral("true", OAuthJsonValue.Bool(true))
                'f' -> readLiteral("false", OAuthJsonValue.Bool(false))
                'n' -> readLiteral("null", OAuthJsonValue.Null)
                else -> if (char == '-' || char.isDigit()) OAuthJsonValue.Num(readNumber()) else {
                    throw IllegalArgumentException("Unexpected character '$char'")
                }
            }
        }

        private fun readObject(): OAuthJsonValue.Obj {
            expect('{')
            val fields = LinkedHashMap<String, OAuthJsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return OAuthJsonValue.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                fields[key] = readValue()
                skipWhitespace()
                when (val char = text[index]) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return OAuthJsonValue.Obj(fields)
                    }
                    else -> throw IllegalArgumentException("Unexpected character '$char' in object")
                }
            }
        }

        private fun readArray(): OAuthJsonValue.Arr {
            expect('[')
            val items = mutableListOf<OAuthJsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return OAuthJsonValue.Arr(items)
            }
            while (true) {
                items += readValue()
                skipWhitespace()
                when (val char = text[index]) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return OAuthJsonValue.Arr(items)
                    }
                    else -> throw IllegalArgumentException("Unexpected character '$char' in array")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (index >= text.length) throw IllegalArgumentException("Unterminated string")
                when (val char = text[index++]) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        val escaped = text[index++]
                        when (escaped) {
                            '"', '\\', '/' -> builder.append(escaped)
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                val hex = text.substring(index, index + 4)
                                index += 4
                                builder.append(hex.toInt(16).toChar())
                            }
                            else -> throw IllegalArgumentException("Unsupported escape '\\$escaped'")
                        }
                    }
                    else -> builder.append(char)
                }
            }
        }

        private fun readNumber(): Double {
            val start = index
            if (peek() == '-') index++
            while (index < text.length && (text[index].isDigit() || text[index] == '.' ||
                    text[index] == 'e' || text[index] == 'E' || text[index] == '+' || text[index] == '-')
            ) {
                index++
            }
            return text.substring(start, index).toDouble()
        }

        private fun <T : OAuthJsonValue> readLiteral(literal: String, value: T): T {
            if (!text.startsWith(literal, index)) throw IllegalArgumentException("Expected $literal")
            index += literal.length
            return value
        }

        private fun peek(): Char? = text.getOrNull(index)

        private fun expect(char: Char) {
            skipWhitespace()
            if (peek() != char) throw IllegalArgumentException("Expected '$char'")
            index++
        }
    }
}
