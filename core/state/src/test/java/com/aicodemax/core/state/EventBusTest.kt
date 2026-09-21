package com.aicodemax.core.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventBusTest {
    @Test
    fun publishesToSubscribers() = runBlocking {
        val bus: EventBus = SharedFlowEventBus()
        val subscribed = CompletableDeferred<Unit>()
        val received = async {
            withTimeout(5000) {
                bus.events.onSubscription { subscribed.complete(Unit) }.first()
            }
        }
        // Wait until the collector is actually subscribed (replay=0 by design).
        withTimeout(5000) { subscribed.await() }
        bus.publish(AppEvent.Notice("sys", "hello"))
        val event = withTimeout(5000) { received.await() } as AppEvent.Notice
        assertEquals("hello", event.message)
    }

    @Test
    fun tryPublishNeverSuspends() {
        val bus: EventBus = SharedFlowEventBus()
        assertTrue(bus.tryPublish(AppEvent.CapabilityChanged("terminal")))
    }

    @Test
    fun appStateStoreUpdates() {
        val store: AppStateStore = InMemoryAppStateStore()
        store.update { it.copy(theme = ThemeMode.DARK, autonomy = AutonomyLevel.AUTO_SAFE) }
        val state = store.state.value
        assertEquals(ThemeMode.DARK, state.theme)
        assertEquals(AutonomyLevel.AUTO_SAFE, state.autonomy)
    }
}
