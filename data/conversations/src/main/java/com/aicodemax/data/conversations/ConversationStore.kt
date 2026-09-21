package com.aicodemax.data.conversations

import com.aicodemax.core.common.Clock
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.SystemClock
import com.aicodemax.core.common.fold
import com.aicodemax.core.common.runOutcome
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
enum class MessageRole { USER, AI, SYSTEM, STATUS }

@Serializable
data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: MessageRole,
    val text: String,
    val createdAt: Long,
    val attachments: List<String> = emptyList(),
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val modelId: String? = null,
)

interface ConversationStore {
    fun createConversation(title: String, modelId: String? = null): Outcome<Conversation>
    fun rename(conversationId: String, title: String): Outcome<Conversation>
    fun list(): Outcome<List<Conversation>>
    fun get(conversationId: String): Outcome<Conversation>
    fun appendMessage(
        conversationId: String,
        role: MessageRole,
        text: String,
        attachments: List<String> = emptyList(),
    ): Outcome<ChatMessage>

    fun getMessages(conversationId: String): Outcome<List<ChatMessage>>
    fun delete(conversationId: String): Boolean
}

/** File-backed conversations: one JSON list + one JSONL file per conversation. */
class FileConversationStore(
    rootDir: File,
    private val clock: Clock = SystemClock,
) : ConversationStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val listSer = ListSerializer(Conversation.serializer())
    private val base: File = rootDir.apply { mkdirs() }
    private val indexFile: File = File(base, "conversations.json")
    private val messagesDir: File = File(base, "messages").apply { mkdirs() }

    private fun readIndex(): MutableList<Conversation> {
        if (!indexFile.exists()) return mutableListOf()
        val text = indexFile.readText()
        if (text.isBlank()) return mutableListOf()
        return json.decodeFromString(listSer, text).toMutableList()
    }

    private fun writeIndex(conversations: List<Conversation>) {
        indexFile.writeText(json.encodeToString(listSer, conversations))
    }

    @Synchronized
    override fun createConversation(title: String, modelId: String?): Outcome<Conversation> =
        runOutcome("CONV_WRITE") {
            val now = clock.nowMillis()
            val conversation = Conversation(Ids.newId("conv"), title, now, now, modelId)
            val all = readIndex().apply { add(conversation) }
            writeIndex(all)
            conversation
        }

    @Synchronized
    override fun rename(conversationId: String, title: String): Outcome<Conversation> =
        runOutcome("CONV_WRITE") {
            val all = readIndex()
            val index = all.indexOfFirst { it.id == conversationId }
            if (index < 0) throw NoSuchElementException("conversation '$conversationId' not found")
            val updated = all[index].copy(title = title, updatedAt = clock.nowMillis())
            all[index] = updated
            writeIndex(all)
            updated
        }

    @Synchronized
    override fun list(): Outcome<List<Conversation>> = runOutcome("CONV_READ") {
        readIndex().sortedByDescending { it.updatedAt }
    }

    @Synchronized
    override fun get(conversationId: String): Outcome<Conversation> = runOutcome("CONV_READ") {
        readIndex().firstOrNull { it.id == conversationId }
            ?: throw NoSuchElementException("conversation '$conversationId' not found")
    }

    @Synchronized
    override fun appendMessage(
        conversationId: String,
        role: MessageRole,
        text: String,
        attachments: List<String>,
    ): Outcome<ChatMessage> = runOutcome("CONV_WRITE") {
        get(conversationId).fold(
            onSuccess = { conv ->
                val message = ChatMessage(Ids.newId("msg"), conversationId, role, text, clock.nowMillis(), attachments)
                File(messagesDir, "$conversationId.jsonl")
                    .appendText(json.encodeToString(ChatMessage.serializer(), message) + "\n")
                val all = readIndex()
                val index = all.indexOfFirst { it.id == conversationId }
                all[index] = conv.copy(updatedAt = message.createdAt)
                writeIndex(all)
                message
            },
            onFailure = { throw NoSuchElementException("conversation '$conversationId' not found") },
        )
    }

    @Synchronized
    override fun getMessages(conversationId: String): Outcome<List<ChatMessage>> =
        runOutcome("CONV_READ") {
            val file = File(messagesDir, "$conversationId.jsonl")
            if (!file.exists()) return@runOutcome emptyList()
            file.readLines()
                .filter { it.isNotBlank() }
                .map { json.decodeFromString(ChatMessage.serializer(), it) }
        }

    @Synchronized
    override fun delete(conversationId: String): Boolean = try {
        val all = readIndex()
        val removed = all.removeIf { it.id == conversationId }
        if (removed) {
            writeIndex(all)
            File(messagesDir, "$conversationId.jsonl").delete()
        }
        removed
    } catch (_: Exception) {
        false
    }
}
