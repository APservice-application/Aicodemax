package com.aicodemax.core.state

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkingSetTest {
    @Test
    fun tracksProjectConversationAndFiles() {
        val store: WorkingSetStore = InMemoryWorkingSetStore()
        store.setProject("p1")
        store.setConversation("c1")
        store.setSelectedFiles(listOf("a.txt", "b.txt"))
        val set = store.workingSet.value
        assertEquals("p1", set.projectId)
        assertEquals("c1", set.conversationId)
        assertEquals(2, set.selectedFiles.size)
    }

    @Test
    fun promptHandoffConsumesOnce() {
        val store: WorkingSetStore = InMemoryWorkingSetStore()
        assertEquals(null, store.consumePrompt())
        store.handPrompt("summarize?")
        assertEquals("summarize?", store.consumePrompt())
        assertEquals(null, store.consumePrompt())
    }
}
