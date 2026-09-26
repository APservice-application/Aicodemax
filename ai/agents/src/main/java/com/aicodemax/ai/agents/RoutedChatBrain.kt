package com.aicodemax.ai.agents

import com.aicodemax.ai.core.ChatBrain
import com.aicodemax.ai.core.LlmTurn
import com.aicodemax.ai.models.ModelDescriptor
import com.aicodemax.ai.models.ModelRequirement
import com.aicodemax.ai.models.ModelRouter
import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome

/**
 * CP-107: router-driven brain — local-first ([ModelRequirement.preferLocal])
 * with automatic failover across router candidates, then the manually
 * connected provider. Every failure names the model that failed.
 */
class RoutedChatBrain(
    private val router: ModelRouter,
    private val resolve: (ModelDescriptor) -> ChatBrain?,
    private val manual: () -> ChatBrain?,
    private val requirement: ModelRequirement = ModelRequirement("chat"),
) : ChatBrain {
    override suspend fun reply(text: String, history: List<LlmTurn>): Outcome<String> {
        val errors = mutableListOf<String>()
        var manualAlreadyTried = false
        for (candidate in router.candidates(requirement)) {
            val brain = resolve(candidate) ?: continue
            if (candidate.id == "manual") manualAlreadyTried = true
            when (val result = brain.reply(text, history)) {
                is Outcome.Success -> return result
                is Outcome.Failure -> errors.add("${candidate.name}: ${result.error.message}")
            }
        }
        // The manual endpoint is also a router candidate. Retrying it here can
        // send the same paid prompt twice after an error: never do so.
        if (!manualAlreadyTried) manual()?.let { return it.reply(text, history) }
        return if (errors.isEmpty()) {
            Outcome.Failure(AppError("BRAIN_OFF", "ยังไม่ต่อ LLM (ตั้งค่าที่หน้า Models)"))
        } else {
            Outcome.Failure(AppError("BRAIN_ALL_FAILED", "LLM ทุกตัวล้มเหลว: " + errors.joinToString(" | ")))
        }
    }

    /** Human-readable route line for the Models screen. */
    fun routeLine(): String {
        val candidates = router.candidates(requirement)
        val names = candidates.map { it.name }
        return when {
            names.isEmpty() && manual() == null -> "สมอง: ยังไม่ต่อ LLM"
            names.isEmpty() -> "สมอง: ต่อตรง (manual)"
            else -> "สมอง: " + names.joinToString(" → ") +
                if (manual() == null || candidates.any { it.id == "manual" }) "" else " → manual"
        }
    }
}
