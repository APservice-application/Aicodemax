package com.aicodemax.data.conversations

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.core.common.runOutcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Conversation session management (CP-34 Chat UI data backfill; MASTER §29). */
data class MessageHit(
    val conversationId: String,
    val title: String,
    val role: MessageRole,
    val snippet: String,
    val createdAt: Long,
)

@Serializable
data class ConversationExport(
    val title: String,
    val modelId: String? = null,
    val messages: List<ChatMessage>,
)

class ConversationManager(private val store: ConversationStore) {
    private val json = Json { ignoreUnknownKeys = true }

    fun recentMessages(conversationId: String, maxMessages: Int = 50): Outcome<List<ChatMessage>> {
        return store.getMessages(conversationId).fold(
            onSuccess = { Outcome.Success(it.takeLast(maxMessages.coerceAtLeast(0))) },
            onFailure = { Outcome.Failure(it) },
        )
    }

    /** Substring search across all conversations (newest hits first). */
    fun search(query: String, limit: Int = 20): Outcome<List<MessageHit>> {
        if (query.isBlank()) return Outcome.Success(emptyList())
        return store.list().fold(
            onSuccess = { conversations ->
                val hits = mutableListOf<MessageHit>()
                for (conv in conversations) {
                    store.getMessages(conv.id).fold(
                        onSuccess = { messages ->
                            for (message in messages) {
                                if (message.text.contains(query, ignoreCase = true)) {
                                    hits.add(
                                        MessageHit(
                                            conv.id,
                                            conv.title,
                                            message.role,
                                            message.text.take(160),
                                            message.createdAt,
                                        ),
                                    )
                                }
                            }
                        },
                        onFailure = { /* unreadable conversation: skip */ },
                    )
                }
                Outcome.Success(
                    hits.sortedByDescending { it.createdAt }.take(limit.coerceAtLeast(0)),
                )
            },
            onFailure = { Outcome.Failure(it) },
        )
    }

    /** Keeps only the last [keepLast] messages. Returns the deleted count. */
    fun prune(conversationId: String, keepLast: Int = 100): Outcome<Int> {
        return store.getMessages(conversationId).fold(
            onSuccess = { messages ->
                val keep = messages.takeLast(keepLast.coerceAtLeast(0))
                store.replaceMessages(conversationId, keep).fold(
                    onSuccess = { Outcome.Success(messages.size - keep.size) },
                    onFailure = { Outcome.Failure(it) },
                )
            },
            onFailure = { Outcome.Failure(it) },
        )
    }

    fun export(conversationId: String): Outcome<String> {
        return store.get(conversationId).fold(
            onSuccess = { conv ->
                store.getMessages(conversationId).fold(
                    onSuccess = { messages ->
                        runOutcome("CONV_EXPORT") {
                            json.encodeToString(
                                ConversationExport.serializer(),
                                ConversationExport(conv.title, conv.modelId, messages),
                            )
                        }
                    },
                    onFailure = { Outcome.Failure(it) },
                )
            },
            onFailure = { Outcome.Failure(it) },
        )
    }

    /** Imports an export as a NEW conversation (fresh ids, same content). */
    fun importExport(exportJson: String): Outcome<Conversation> {
        return runOutcome("CONV_IMPORT") {
            json.decodeFromString(ConversationExport.serializer(), exportJson)
        }.fold(
            onSuccess = { export ->
                store.createConversation(export.title, export.modelId).fold(
                    onSuccess = { conv ->
                        for (message in export.messages) {
                            val appended = store.appendMessage(conv.id, message.role, message.text, message.attachments)
                            if (appended is Outcome.Failure) return@fold appended
                        }
                        Outcome.Success(conv)
                    },
                    onFailure = { Outcome.Failure(it) },
                )
            },
            onFailure = { Outcome.Failure(it) },
        )
    }
}
