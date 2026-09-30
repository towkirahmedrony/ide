package com.agentx.app.agent.conversation

import com.agentx.app.tools.SecretRedactor

/** Redacts secrets from conversation payloads before they are persisted. */
object ConversationSecrets {

    fun redact(content: MessageContent): Pair<MessageContent, Boolean> {
        val text = SecretRedactor.redactText(content.text)
        val arguments = content.toolArguments?.let(SecretRedactor::redactText)
        val result = content.toolResult?.let(SecretRedactor::redactText)
        val redacted = text != content.text ||
            arguments != content.toolArguments ||
            result != content.toolResult
        return content.copy(text = text, toolArguments = arguments, toolResult = result) to redacted
    }

    fun redact(message: ConversationMessage): ConversationMessage {
        val (content, redacted) = redact(message.content)
        return if (!redacted) {
            message
        } else {
            message.copy(
                content = content,
                metadata = message.metadata.copy(redacted = true),
            )
        }
    }
}
