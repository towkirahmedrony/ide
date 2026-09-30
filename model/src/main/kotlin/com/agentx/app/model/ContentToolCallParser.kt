package com.agentx.app.model

import com.agentx.app.model.json.JsonCodec
import com.agentx.app.model.json.JsonObject
import com.agentx.app.model.json.JsonValue
import com.agentx.app.model.json.arrayOrNull
import com.agentx.app.model.json.objectOrNull
import com.agentx.app.model.json.stringOrNull

/**
 * Recovers structured [ModelToolCall]s when a model writes a tool invocation as
 * assistant text instead of a protocol `tool_calls` payload.
 *
 * Local and OpenAI-compatible servers (Ollama, llama.cpp, vLLM, Colab) often
 * emit JSON such as `{"name":"read_file","arguments":{"path":"..."}}` in
 * `message.content`. The Agent Runtime must treat that as an executable tool
 * call, not as the final answer.
 */
object ContentToolCallParser {

    private val FENCE = Regex("^```(?:json|JSON|tool|tools)?\\s*\\r?\\n?(.*?)\\r?\\n?```\\s*$", RegexOption.DOT_MATCHES_ALL)
    private val RESERVED = setOf(
        "name", "tool", "tool_name", "arguments", "args", "parameters",
        "id", "tool_call_id", "type", "function", "tool_calls", "role", "content",
    )

    fun parse(content: String): List<ModelToolCall> {
        if (content.isBlank()) return emptyList()
        val calls = LinkedHashMap<String, ModelToolCall>()
        for (blob in jsonBlobs(content)) {
            val value = runCatching { JsonCodec.parse(blob) }.getOrNull() ?: continue
            for (call in callsFromValue(value)) {
                val key = call.id.ifBlank { "${call.name}:${JsonCodec.encode(JsonValue.Obj(call.arguments))}" }
                calls.putIfAbsent(key, call)
            }
        }
        return calls.values.toList()
    }

    fun remainder(content: String): String {
        if (content.isBlank()) return ""
        var leftover = content
        for (blob in jsonBlobs(content)) {
            val value = runCatching { JsonCodec.parse(blob) }.getOrNull() ?: continue
            if (callsFromValue(value).isEmpty()) continue
            leftover = leftover.replaceFirst(blob, " ")
        }
        return leftover.replace(FENCE, " ").trim()
    }

    fun isLikelyToolCallText(content: String): Boolean {
        if (content.isBlank()) return false
        if (parse(content).isNotEmpty()) return true
        val trimmed = stripFence(content)
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return false
        val compact = trimmed.replace(WHITESPACE, "")
        val hasName = compact.contains("\"name\"") || compact.contains("\"tool\"") || compact.contains("\"tool_name\"")
        val hasArgs = compact.contains("\"arguments\"") || compact.contains("\"args\"") || compact.contains("\"parameters\"")
        return hasName && hasArgs
    }

    fun normalize(response: ModelResponse): ModelResponse {
        val existing = response.toolCalls.filter { it.name.isNotBlank() }
        val fromContent = if (existing.isEmpty()) parse(response.content) else emptyList()
        val merged = ensureIds(existing + fromContent)
        if (merged.isEmpty()) return response
        return response.copy(
            content = visibleContent(response.content),
            toolCalls = merged,
            finishReason = when (response.finishReason) {
                null, ModelFinishReason.STOP, ModelFinishReason.UNKNOWN -> ModelFinishReason.TOOL_CALLS
                else -> response.finishReason
            },
        )
    }

    private fun visibleContent(content: String): String {
        if (content.isBlank()) return ""
        if (parse(content).isEmpty() && !isLikelyToolCallText(content)) return content
        val leftover = remainder(content)
        return if (leftover.isBlank() || parse(leftover).isNotEmpty() || isLikelyToolCallText(leftover)) {
            ""
        } else {
            leftover
        }
    }

    private fun ensureIds(calls: List<ModelToolCall>): List<ModelToolCall> =
        calls.mapIndexed { index, call ->
            if (call.id.isNotBlank()) call else call.copy(id = "call-${index + 1}-${call.name}")
        }

    private fun jsonBlobs(content: String): List<String> {
        val blobs = mutableListOf<String>()
        val stripped = stripFence(content)
        if (stripped.isNotEmpty()) blobs += stripped
        if (stripped != content.trim() && content.trim().isNotEmpty()) blobs += content.trim()
        blobs += scanBalanced(content)
        return blobs.distinct()
    }

    private fun stripFence(content: String): String {
        val trimmed = content.trim()
        return FENCE.matchEntire(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed
    }

    private fun scanBalanced(content: String): List<String> {
        val found = mutableListOf<String>()
        var index = 0
        while (index < content.length) {
            val startChar = content[index]
            if (startChar != '{' && startChar != '[') {
                index++
                continue
            }
            val end = matchingEnd(content, index) ?: break
            found += content.substring(index, end + 1)
            index = end + 1
        }
        return found
    }

    private fun matchingEnd(content: String, start: Int): Int? {
        val open = content[start]
        val close = if (open == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escape = false
        for (index in start until content.length) {
            val char = content[index]
            if (inString) {
                when {
                    escape -> escape = false
                    char == '\\' -> escape = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private fun callsFromValue(value: JsonValue): List<ModelToolCall> {
        value.objectOrNull()?.let { fields ->
            fields["tool_calls"]?.let { nested ->
                val nestedCalls = callsFromValue(nested)
                if (nestedCalls.isNotEmpty()) return nestedCalls
            }
            callFromObject(fields)?.let { return listOf(it) }
            return emptyList()
        }
        val items = value.arrayOrNull() ?: return emptyList()
        return items.flatMap(::callsFromValue)
    }

    private fun callFromObject(fields: JsonObject): ModelToolCall? {
        val function = fields.objectOrNull("function")
        val name = fields.stringOrNull("name")
            ?: fields.stringOrNull("tool")
            ?: fields.stringOrNull("tool_name")
            ?: function?.stringOrNull("name")
            ?: return null
        if (name.isBlank()) return null
        val hasExplicitArgs = fields.containsKey("arguments") ||
            fields.containsKey("args") ||
            fields.containsKey("parameters") ||
            function != null
        val extra = fields.filterKeys { it !in RESERVED }
        if (!hasExplicitArgs && extra.isEmpty()) return null
        val arguments = argumentsOf(fields, function)
        val id = fields.stringOrNull("id")
            ?: fields.stringOrNull("tool_call_id")
            ?: function?.stringOrNull("id")
            ?: ""
        return ModelToolCall(id = id, name = name.trim(), arguments = arguments)
    }

    private fun argumentsOf(fields: JsonObject, function: JsonObject?): JsonObject {
        parseArguments(fields["arguments"])?.let { return it }
        parseArguments(fields["args"])?.let { return it }
        parseArguments(fields["parameters"])?.let { return it }
        function?.let { parseArguments(it["arguments"]) }?.let { return it }
        val inferred = fields.filterKeys { it !in RESERVED }
        return inferred
    }

    private fun parseArguments(raw: JsonValue?): JsonObject? = when (raw) {
        null, is JsonValue.Null -> null
        is JsonValue.Obj -> raw.fields
        is JsonValue.Str -> {
            if (raw.value.isBlank()) emptyMap()
            else runCatching { JsonCodec.parse(raw.value).objectOrNull() }.getOrNull() ?: emptyMap()
        }
        else -> emptyMap()
    }

    private val WHITESPACE = Regex("\\s+")
}
