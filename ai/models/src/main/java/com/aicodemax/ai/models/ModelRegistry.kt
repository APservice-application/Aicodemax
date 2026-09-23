package com.aicodemax.ai.models

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import java.util.concurrent.ConcurrentHashMap

enum class ModelKind { LOCAL_BOOTSTRAP, LOCAL_FULL, EXTERNAL }
enum class ModelStatus { UNKNOWN, AVAILABLE, DOWNLOADING, READY, LOADED, ERROR }

data class ModelDescriptor(
    val id: String,
    val name: String,
    val kind: ModelKind,
    val provider: String = "built-in",
    val sizeBytes: Long = 0,
    val status: ModelStatus = ModelStatus.UNKNOWN,
    val capabilities: List<String> = emptyList(),
    val statusDetail: String = "",
)

data class ModelRequirement(
    val capability: String = "chat",
    val preferLocal: Boolean = true,
)

interface ModelRegistry {
    fun register(descriptor: ModelDescriptor): Outcome<ModelDescriptor>
    fun update(descriptor: ModelDescriptor): Outcome<ModelDescriptor>
    fun get(modelId: String): ModelDescriptor?
    fun all(): List<ModelDescriptor>
}

class InMemoryModelRegistry : ModelRegistry {
    private val models = ConcurrentHashMap<String, ModelDescriptor>()

    override fun register(descriptor: ModelDescriptor): Outcome<ModelDescriptor> {
        val prev = models.putIfAbsent(descriptor.id, descriptor)
        return if (prev == null) {
            Outcome.Success(descriptor)
        } else {
            Outcome.Failure(AppError("MODEL_EXISTS", "model '${descriptor.id}' already registered"))
        }
    }

    override fun update(descriptor: ModelDescriptor): Outcome< ModelDescriptor> {
        return if (models.replace(descriptor.id, descriptor) != null) {
            Outcome.Success(descriptor)
        } else {
            Outcome.Failure(AppError("MODEL_UNKNOWN", "model '${descriptor.id}' is not registered"))
        }
    }

    override fun get(modelId: String): ModelDescriptor? = models[modelId]
    override fun all(): List<ModelDescriptor> = models.values.sortedBy { it.id }
}

interface ModelRouter {
    fun pick(requirement: ModelRequirement = ModelRequirement()): Outcome<ModelDescriptor>

    /** CP-107: ordered failover candidates, best first (default = single pick). */
    fun candidates(requirement: ModelRequirement = ModelRequirement()): List<ModelDescriptor> =
        when (val picked = pick(requirement)) {
            is Outcome.Success -> listOf(picked.value)
            is Outcome.Failure -> emptyList()
        }
}

/** Picks the first usable model in preference order; fails honestly when none is ready. */
class FallbackModelRouter(
    private val registry: ModelRegistry,
    private val preferenceOrder: List<String> = emptyList(),
) : ModelRouter {
    private val usable = setOf(ModelStatus.READY, ModelStatus.LOADED, ModelStatus.AVAILABLE)

    override fun candidates(requirement: ModelRequirement): List<ModelDescriptor> =
        registry.all()
            .filter { it.status in usable }
            .sortedWith(
                compareBy<ModelDescriptor> {
                    if (requirement.preferLocal && it.kind == ModelKind.EXTERNAL) 1 else 0
                }.thenBy {
                    val index = preferenceOrder.indexOf(it.id)
                    if (index < 0) Int.MAX_VALUE else index
                },
            )

    override fun pick(requirement: ModelRequirement): Outcome<ModelDescriptor> {
        return candidates(requirement).firstOrNull()?.let { Outcome.Success(it) }
            ?: Outcome.Failure(
                AppError("MODEL_NONE_READY", "no model is ready (registry holds ${registry.all().size})"),
            )
    }
}
