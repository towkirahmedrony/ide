package com.agentx.app.model

import com.agentx.app.model.http.HttpRequestSpec
import com.agentx.app.model.http.HttpResponseSpec
import com.agentx.app.model.http.HttpTransport
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Runs a suspending block without pulling in the coroutines library. The code
 * under test never truly suspends, so the coroutine completes on this thread.
 */
internal fun <T> runSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context: CoroutineContext = EmptyCoroutineContext

        override fun resumeWith(result: Result<T>) {
            outcome = result
        }
    })
    return checkNotNull(outcome) { "The test coroutine suspended unexpectedly" }.getOrThrow()
}

/** [HttpTransport] stub returning canned responses and streaming lines. */
internal class FakeHttpTransport(
    var response: HttpResponseSpec = HttpResponseSpec(statusCode = 200, body = "{}"),
    var streamResponse: HttpResponseSpec = HttpResponseSpec(statusCode = 200, body = ""),
    var streamLines: List<String> = emptyList(),
    var onExecute: (() -> Unit)? = null,
    var onStream: (() -> Unit)? = null,
    var executeHandler: ((HttpRequestSpec) -> HttpResponseSpec)? = null,
) : HttpTransport {

    val requests = mutableListOf<HttpRequestSpec>()

    val lastRequest: HttpRequestSpec? get() = requests.lastOrNull()

    override suspend fun execute(request: HttpRequestSpec): HttpResponseSpec {
        requests += request
        onExecute?.invoke()
        return executeHandler?.invoke(request) ?: response
    }

    override suspend fun executeStreaming(request: HttpRequestSpec, onLine: (String) -> Unit): HttpResponseSpec {
        requests += request
        onStream?.invoke()
        streamLines.forEach { onLine(it) }
        return streamResponse
    }
}

internal fun openAiConfig(
    providerId: String = "openai-compatible",
    baseUrl: String = "http://localhost:11434/v1",
    model: String = "local-model",
    apiKey: String? = null,
    stream: Boolean = false,
    generation: ModelGenerationSettings = ModelGenerationSettings(),
    capabilities: ModelCapabilities? = null,
): ModelConfig = ModelConfig(
    providerId = providerId,
    baseUrl = baseUrl,
    model = model,
    apiKey = apiKey,
    stream = stream,
    generation = generation,
    capabilities = capabilities,
)

internal fun request(config: ModelConfig, vararg messages: ModelMessage): ModelRequest =
    ModelRequest(config = config, messages = messages.toList())

internal const val SUCCESS_RESPONSE = """
{
  "id": "chatcmpl-1",
  "model": "local-model",
  "choices": [
    {
      "index": 0,
      "message": { "role": "assistant", "content": "Hello!" },
      "finish_reason": "stop"
    }
  ],
  "usage": { "prompt_tokens": 3, "completion_tokens": 2, "total_tokens": 5 }
}
"""
