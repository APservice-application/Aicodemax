package com.aicodemax.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionnaireTest {
    @Test
    fun browserCloseAsksForTabId() {
        val intent = IntentParser.parse("ปิดแท็บ")
        assertEquals(IntentType.BROWSER_CLOSE, intent.type)
        val slots = QuestionnaireSlots.forIntent(intent)
        assertEquals(1, slots.size)
        val state = QuestionnaireState(intent, slots)
        assertTrue(state.questionText().contains("แท็บไหน"))
        val done = state.answer("2")
        assertTrue(done.done)
        assertEquals("2", done.completedIntent().parameters["tabId"])
    }

    @Test
    fun browserCloseWithNumberSkipsQuestions() {
        val intent = IntentParser.parse("ปิดแท็บ 2")
        assertEquals(IntentType.BROWSER_CLOSE, intent.type)
        assertEquals("2", intent.parameters["tabId"])
        assertTrue(QuestionnaireSlots.forIntent(intent).isEmpty())
    }

    @Test
    fun mediaEditAsksPlatformThenGoal() {
        val intent = IntentParser.parse("ตัดคลิปให้หน่อย")
        assertEquals(IntentType.MEDIA_EDIT, intent.type)
        val slots = QuestionnaireSlots.forIntent(intent)
        assertEquals(2, slots.size)
        var state = QuestionnaireState(intent, slots)
        assertTrue(state.questionText().contains("ที่ไหน"))
        state = state.answer("1") // numeric pick → TikTok
        assertFalse(state.done)
        assertEquals("TikTok", state.answers["platform"])
        state = state.answer("เล่าเรื่อง")
        assertTrue(state.done)
        val completed = state.completedIntent()
        assertEquals("TikTok", completed.parameters["platform"])
        assertEquals("เล่าเรื่อง", completed.parameters["goal"])
    }

    @Test
    fun mediaEditWithPlatformSkipsPlatformQuestion() {
        val intent = IntentParser.parse("ตัดต่อคลิปลงติ๊กต็อก")
        assertEquals(IntentType.MEDIA_EDIT, intent.type)
        assertEquals("tiktok", intent.parameters["platform"])
        val slots = QuestionnaireSlots.forIntent(intent)
        assertEquals(1, slots.size)
        assertEquals("goal", slots.single().key)
    }

    @Test
    fun storeRoundtrip() {
        val store: QuestionnaireStore = InMemoryQuestionnaireStore()
        val intent = UserIntent(IntentType.BROWSER_CLOSE, "ปิดแท็บ")
        val state = QuestionnaireState(intent, QuestionnaireSlots.forIntent(intent))
        store.save("c1", state)
        assertEquals(state, store.pending("c1"))
        store.save("c1", null)
        assertEquals(null, store.pending("c1"))
    }
}
