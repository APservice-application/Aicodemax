package com.aicodemax.data.conversations

import com.aicodemax.core.common.Clock
import com.aicodemax.core.common.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.aicodemax.core.common.isFailure

class FakeClock(var tick: Long = 9000L) : Clock {
    override fun nowMillis(): Long = tick++
}

class ConversationStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun conversationLifecycle() {
        val store: ConversationStore = FileConversationStore(tmp.root, FakeClock())
        val conv = (store.createConversation("hello") as Outcome.Success<Conversation>).value
        store.appendMessage(conv.id, MessageRole.USER, "hi")
        store.appendMessage(conv.id, MessageRole.STATUS, "AI runtime: not connected yet")

        val messages = (store.getMessages(conv.id) as Outcome.Success<List<ChatMessage>>).value
        assertEquals(2, messages.size)
        assertEquals(MessageRole.STATUS, messages[1].role)

        val renamed = (store.rename(conv.id, "greeting") as Outcome.Success<Conversation>).value
        assertEquals("greeting", renamed.title)

        assertTrue(store.delete(conv.id))
        assertTrue(store.get(conv.id).isFailure())
    }
}
