package com.aicodemax.ai.models

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome

/** Minimum on-device AI (BLUEPRINT §48 EMBEDDED BOOTSTRAP AI). Real impl ships with model file. */
interface BootstrapAI {
    fun status(): ModelStatus
    suspend fun generate(prompt: String, maxTokens: Int = 256): Outcome<String>
}

/** Honest placeholder: reports the model file is missing instead of faking answers. */
class UnavailableBootstrapAI(private val reason: String) : BootstrapAI {
    override fun status(): ModelStatus = ModelStatus.UNKNOWN
    override suspend fun generate(prompt: String, maxTokens: Int): Outcome<String> =
        Outcome.Failure(AppError("MODEL_NOT_BUNDLED", reason))
}
