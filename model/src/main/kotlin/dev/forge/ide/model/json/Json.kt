package dev.forge.ide.model.json

/**
 * Minimal, dependency-free JSON value model. The model layer uses it to keep
 * request/response payloads structured (tool schemas, tool-call arguments) and
 * to serialize provider traffic without pulling in a JSON library.
 */
sealed interface JsonValue {
    data object Null : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    data class Num(val value: Double) : JsonValue

    data class Str(val value: String) : JsonValue

    data class Arr(val items: List<JsonValue>) : JsonValue

    data class Obj(val fields: JsonObject) : JsonValue
}

/** A JSON object: field name to [JsonValue]. */
typealias JsonObject = Map<String, JsonValue>

fun JsonValue.stringOrNull(): String? = (this as? JsonValue.Str)?.value

fun JsonValue.numberOrNull(): Double? = (this as? JsonValue.Num)?.value

fun JsonValue.booleanOrNull(): Boolean? = (this as? JsonValue.Bool)?.value

fun JsonValue.objectOrNull(): JsonObject? = (this as? JsonValue.Obj)?.fields

fun JsonValue.arrayOrNull(): List<JsonValue>? = (this as? JsonValue.Arr)?.items

fun JsonObject.stringOrNull(key: String): String? = this[key]?.stringOrNull()

fun JsonObject.numberOrNull(key: String): Double? = this[key]?.numberOrNull()

fun JsonObject.booleanOrNull(key: String): Boolean? = this[key]?.booleanOrNull()

fun JsonObject.objectOrNull(key: String): JsonObject? = this[key]?.objectOrNull()

fun JsonObject.arrayOrNull(key: String): List<JsonValue>? = this[key]?.arrayOrNull()

/** Convenience constructors for building [JsonValue] trees. */
object Json {
    fun of(value: String): JsonValue = JsonValue.Str(value)

    fun of(value: Boolean): JsonValue = JsonValue.Bool(value)

    fun of(value: Double): JsonValue = JsonValue.Num(value)

    fun of(value: Int): JsonValue = JsonValue.Num(value.toDouble())

    fun of(value: Long): JsonValue = JsonValue.Num(value.toDouble())

    fun array(items: List<JsonValue>): JsonValue = JsonValue.Arr(items)

    fun array(vararg items: JsonValue): JsonValue = JsonValue.Arr(items.toList())

    fun obj(fields: JsonObject): JsonValue = JsonValue.Obj(fields)

    fun obj(vararg fields: Pair<String, JsonValue>): JsonValue = JsonValue.Obj(linkedMapOf(*fields))
}
