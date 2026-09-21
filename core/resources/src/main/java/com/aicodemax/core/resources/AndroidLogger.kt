package com.aicodemax.core.resources

import android.util.Log
import com.aicodemax.core.common.AppLogger
import com.aicodemax.core.common.LogLevel

class AndroidLogger : AppLogger {
    override fun log(level: LogLevel, tag: String, message: String, causeMessage: String?) {
        val full = causeMessage?.let { "$message | cause=$it" } ?: message
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, full)
            LogLevel.INFO -> Log.i(tag, full)
            LogLevel.WARN -> Log.w(tag, full)
            LogLevel.ERROR -> Log.e(tag, full)
        }
    }
}
