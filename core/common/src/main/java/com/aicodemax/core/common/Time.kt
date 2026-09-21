package com.aicodemax.core.common

/** Injectable clock so logic stays testable. */
interface Clock {
    fun nowMillis(): Long
}

object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
