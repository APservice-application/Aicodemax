package com.aicodemax.ai.models

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.resources.ResourceSnapshot

/** CP-08: routing by capability + RAM fit + health + cost class (local preferred, external last resort). */
data class RouteScore(
    val modelId: String,
    val score: Int,
    val reasons: List<String>,
)

class ScoringModelRouter(
    private val registry: ModelRegistry,
    private val resources: () -> ResourceSnapshot? = { null },
    private val health: (String) -> Boolean = { true },
) : ModelRouter {
    private val usable = setOf(ModelStatus.READY, ModelStatus.LOADED, ModelStatus.AVAILABLE)

    override fun candidates(requirement: ModelRequirement): List<ModelDescriptor> =
        scoreAll(requirement).mapNotNull { registry.get(it.modelId) }

    override fun pick(requirement: ModelRequirement): Outcome<ModelDescriptor> {
        val scored = scoreAll(requirement)
        return scored.firstOrNull()?.let { best -> registry.get(best.modelId)?.let { Outcome.Success(it) } }
            ?: Outcome.Failure(
                AppError("MODEL_NONE_READY", "no model is ready (registry holds ${registry.all().size})"),
            )
    }

    /** All usable candidates, best first, with explainable scores. */
    fun scoreAll(requirement: ModelRequirement): List<RouteScore> {
        val snapshot = resources()
        return registry.all()
            .filter { it.status in usable }
            .filter { requirement.capability.isBlank() || requirement.capability in it.capabilities }
            .mapNotNull { model ->
                var score = 100
                val reasons = mutableListOf<String>()
                if (model.kind == ModelKind.EXTERNAL) {
                    score -= 40
                    reasons.add("external:-40")
                    if (snapshot != null && !snapshot.networkAvailable) return@mapNotNull null
                } else if (requirement.preferLocal) {
                    score += 10
                    reasons.add("local:+10")
                }
                if (!health(model.id)) return@mapNotNull null
                if (model.status == ModelStatus.LOADED) {
                    score += 20
                    reasons.add("loaded:+20")
                }
                if (snapshot != null && model.sizeBytes > 0) {
                    // Model must fit in half of available RAM (room for runtime + OS).
                    if (model.sizeBytes > snapshot.ramAvailableBytes / 2) return@mapNotNull null
                    if (model.sizeBytes <= snapshot.ramAvailableBytes / 4) {
                        score += 10
                        reasons.add("ram-fit:+10")
                    }
                }
                if (model.kind == ModelKind.LOCAL_BOOTSTRAP) {
                    score -= 5
                    reasons.add("bootstrap:-5")
                }
                RouteScore(model.id, score, reasons)
            }
            .sortedByDescending { it.score }
    }
}
