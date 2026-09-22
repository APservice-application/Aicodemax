package com.aicodemax.tools.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileRenderQueueTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun enqueuePersistsAndLists() {
        val queue = FileRenderQueue(temp.root)
        val job = queue.enqueue("p1", RenderPreset.byName("720p"))
        assertTrue(job.id.startsWith("job_"))
        assertNotNull(queue.get(job.id))
        assertEquals(1, queue.list().size)
        // A second queue on the same dir sees the same jobs (crash-safe).
        assertEquals(1, FileRenderQueue(temp.root).list().size)
    }

    @Test
    fun retryRequeuesOnlyFailed() {
        val queue = FileRenderQueue(temp.root)
        val job = queue.enqueue("p1", RenderPreset.byName(null))
        assertEquals(RenderPreset("720p", 720, 4_000_000), job.preset)
        queue.save(job.copy(status = RenderStatus.FAILED, error = "boom", progress = 42))
        val retried = queue.retry(job.id)!!
        assertEquals(RenderStatus.QUEUED, retried.status)
        assertEquals(0, retried.progress)
        assertEquals("", retried.error)
        assertNull(queue.retry("missing"))
    }

    @Test
    fun latestFindsCandidate() {
        val queue = FileRenderQueue(temp.root)
        val first = queue.enqueue("p1", RenderPreset.byName(null))
        queue.save(first.copy(status = RenderStatus.DONE))
        queue.enqueue("p1", RenderPreset.byName(null))
        assertEquals(RenderStatus.QUEUED, queue.latest { it.status == RenderStatus.QUEUED }?.status)
    }
}
