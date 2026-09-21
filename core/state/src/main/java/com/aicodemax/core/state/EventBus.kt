package com.aicodemax.core.state

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

interface EventBus {
    val events: SharedFlow<AppEvent>
    suspend fun publish(event: AppEvent)
    fun tryPublish(event: AppEvent): Boolean
}

/** Hot bus with buffer so publishers never block the AI control loop. */
class SharedFlowEventBus : EventBus {
    private val _events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<AppEvent> = _events.asSharedFlow()
    override suspend fun publish(event: AppEvent) {
        _events.emit(event)
    }
    override fun tryPublish(event: AppEvent): Boolean = _events.tryEmit(event)
}
