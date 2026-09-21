package com.aicodemax.data.conversations

import com.aicodemax.core.common.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun manager() = ConversationManager(FileConversationStore(tmp.root))

    @Test
    fun searchFindsAcrossConversations() {
        val manager = manager()
        val store = FileConversationStore(tmp.root)
        val a = (store.createConversation("Alpha") as Outcome.Success<Conversation>).value
        val b = (store.createConversation("Beta") as Outcome.Success<Conversation>).value
        store.appendMessage(a.id, MessageRole.USER, "how do I zip files?")
        store.appendMessage(a.id, MessageRole.AI, "use the archive action")
        store.appendMessage(b.id, MessageRole.USER, "hello there")

        val hits = (manager.search("zip") as Outcome.Success<List<MessageHit>>).value
        assertEquals(1, hits.size)
        assertEquals("Alpha", hits[0].title)

        val hits2 = (manager.search("ARCHIVE") as Outcome.Success<List<MessageHit>>).value
        assertEquals(1, hits2.size)
        assertEquals(MessageRole.AI, hits2[0].role)
        assertTrue((manager.search("  ") as Outcome.Success<List<MessageHit>>).value.isEmpty())
    }

    @Test
    fun recentAndPruneWindow() {
        val manager = manager()
        val store = FileConversationStore(tmp.root)
        val conv = (store.createConversation("Long") as Outcome.Success<Conversation>).value
        repeat(5) { store.appendMessage(conv.id, MessageRole.USER, "m$it") }

        val recent = (manager.recentMessages(conv.id, 2) as Outcome.Success<List<ChatMessage>>).value
        assertEquals(listOf("m3", "m4"), recent.map { it.text })

        assertEquals(3, (manager.prune(conv.id, keepLast = 2) as Outcome.Success<Int>).value)
        assertEquals(2, (store.getMessages(conv.id) as Outcome.Success<List<ChatMessage>>).value.size)
    }

    @Test
    fun exportImportRoundtrip() {
        val manager = manager()
        val store = FileConversationStore(tmp.root)
        val conv = (store.createConversation("Trip", "m1") as Outcome.Success<Conversation>).value
        store.appendMessage(conv.id, MessageRole.USER, "hi")
        store.appendMessage(conv.id, MessageRole.AI, "hello")

        val json = (manager.export(conv.id) as Outcome.Success<String>).value
        assertTrue(manager.export("ghost") is Outcome.Failure)

        val imported = (manager.importExport(json) as Outcome.Success<Conversation>).value
        assertTrue(imported.id != conv.id)
        assertEquals("Trip", imported.title)
        val messages = (store.getMessages(imported.id) as Outcome.Success<List<ChatMessage>>).value
        assertEquals(listOf("hi", "hello"), messages.map { it.text })
        assertTrue(manager.importExport("{bad json") is Outcome.Failure)
    }
}
