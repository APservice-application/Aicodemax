package com.aicodemax.tools.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FewShotStoreTest {
    @Test
    fun relevantRanksByOverlap() {
        val store = FewShotStore()
        store.record("export วิดีโอออกมา", "media.timeline.export")
        store.record("จำบทเรียนนี้ไว้", "memory.save")
        val found = store.relevant("export วิดีโอให้หน่อย")
        assertEquals(1, found.size)
        assertEquals("media.timeline.export", found.first().capabilityId)
    }

    @Test
    fun failuresAreExcluded() {
        val store = FewShotStore()
        store.record("export วิดีโอ", "media.timeline.export", ok = false)
        assertTrue(store.relevant("export วิดีโอ").isEmpty())
    }

    @Test
    fun emptyQueryReturnsNothing() {
        val store = FewShotStore()
        store.record("export วิดีโอ", "media.timeline.export")
        assertTrue(store.relevant("").isEmpty())
    }

    @Test
    fun capEvictsOldest() {
        val store = FewShotStore(maxExamples = 2)
        store.record("หนึ่ง", "a.x")
        store.record("สอง", "b.y")
        store.record("สาม", "c.z")
        assertEquals(2, store.size())
        assertTrue(store.relevant("หนึ่ง").isEmpty())
        assertTrue(store.relevant("สาม").isNotEmpty())
    }

    @Test
    fun serializeRoundtrip() {
        val store = FewShotStore()
        store.record("export วิดีโอ", "media.timeline.export", mapOf("path" to "p.mp4"))
        store.record("bad call", "files.delete", ok = false)
        val text = store.serialize()
        val restored = FewShotStore()
        restored.load(text)
        assertEquals(2, restored.size())
        val found = restored.relevant("export วิดีโอ")
        assertEquals(1, found.size)
        assertEquals(mapOf("path" to "p.mp4"), found.first().argsSkeleton)
        assertTrue(restored.relevant("bad call").isEmpty())
    }
}
