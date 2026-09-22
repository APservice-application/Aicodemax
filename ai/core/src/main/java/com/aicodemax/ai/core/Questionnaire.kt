package com.aicodemax.ai.core

/**
 * CP-57: dynamic questionnaire (blueprint §6) — the AI asks ONLY for missing slots,
 * then proceeds. Pure state machine; answers arrive as normal chat messages.
 * Security rule: NEVER ask for secrets (keys/tokens/passwords) — those go through
 * dedicated UI (Models screen), never chat.
 */
data class QuestionSlot(
    val key: String,
    val question: String,
    val options: List<String> = emptyList(),
)

data class QuestionnaireState(
    val intent: UserIntent,
    val slots: List<QuestionSlot>,
    val answers: Map<String, String> = emptyMap(),
    val index: Int = 0,
) {
    val done: Boolean get() = index >= slots.size
    val current: QuestionSlot? get() = slots.getOrNull(index)

    fun questionText(): String {
        val slot = current ?: return ""
        return buildString {
            append(slot.question)
            if (slot.options.isNotEmpty()) {
                append("\n")
                slot.options.forEachIndexed { i, option -> appendLine("${i + 1}. $option") }
            }
        }.trim()
    }

    /** Advances with a raw chat answer; numeric answers pick from options. */
    fun answer(rawText: String): QuestionnaireState {
        val slot = current ?: return this
        val trimmed = rawText.trim()
        val picked = trimmed.toIntOrNull()
            ?.takeIf { it in 1..slot.options.size }
            ?.let { slot.options[it - 1] }
            ?: trimmed
        return copy(answers = answers + (slot.key to picked), index = index + 1)
    }

    fun completedIntent(): UserIntent =
        intent.copy(parameters = intent.parameters + answers)
}

/** Slots required per intent before planning. Empty = plan immediately. */
object QuestionnaireSlots {
    fun forIntent(intent: UserIntent): List<QuestionSlot> = when (intent.type) {
        IntentType.BROWSER_CLOSE -> {
            if (intent.parameters["tabId"].isNullOrBlank()) {
                listOf(QuestionSlot("tabId", "ปิดแท็บไหนครับ? (ดูเลขแท็บที่หน้าเบราว์เซอร์)"))
            } else {
                emptyList()
            }
        }
        IntentType.MEDIA_EDIT -> {
            buildList {
                if (intent.parameters["platform"].isNullOrBlank()) {
                    add(
                        QuestionSlot(
                            "platform",
                            "ต้องการนำไปใช้ที่ไหนครับ?",
                            listOf("TikTok", "Facebook", "YouTube", "เก็บไว้ดูเอง"),
                        ),
                    )
                }
                add(
                    QuestionSlot(
                        "goal",
                        "ต้องการให้เน้นอะไรเป็นหลัก?",
                        listOf("กระชับ-เร็ว", "เล่าเรื่อง", "รีวิว", "ขายของ"),
                    ),
                )
            }
        }
        IntentType.LLM_CONNECT -> {
            listOf(
                QuestionSlot(
                    "provider",
                    "ต่อ LLM เจ้าไหนครับ? (ใส่ API key ที่หน้า Models — ไม่ต้องพิมพ์ key ในแชท)",
                    listOf("OpenAI-compatible URL", "localhost (llama-server)", "ข้ามไปก่อน"),
                ),
            )
        }
        else -> emptyList()
    }
}

/** Conversation-scoped pending questionnaires. */
interface QuestionnaireStore {
    fun pending(conversationId: String): QuestionnaireState?
    fun save(conversationId: String, state: QuestionnaireState?)
}

class InMemoryQuestionnaireStore : QuestionnaireStore {
    private val states = mutableMapOf<String, QuestionnaireState>()

    @Synchronized
    override fun pending(conversationId: String): QuestionnaireState? = states[conversationId]

    @Synchronized
    override fun save(conversationId: String, state: QuestionnaireState?) {
        if (state == null) states.remove(conversationId) else states[conversationId] = state
    }
}
