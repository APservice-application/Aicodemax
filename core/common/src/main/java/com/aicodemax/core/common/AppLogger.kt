package com.aicodemax.core.common

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** Central logger port. Android runtime swaps in AndroidLogger at startup. */
interface AppLogger {
    fun log(level: LogLevel, tag: String, message: String, causeMessage: String? = null)
}

fun AppLogger.d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
fun AppLogger.i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
fun AppLogger.w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
fun AppLogger.e(tag: String, message: String, causeMessage: String? = null) =
    log(LogLevel.ERROR, tag, message, causeMessage)

class JvmLogger : AppLogger {
    override fun log(level: LogLevel, tag: String, message: String, causeMessage: String?) {
        val cause = causeMessage?.let { " | cause=$it" } ?: ""
        println("[${level.name}] [$tag] $message$cause")
    }
}

object LoggerFactory {
    @Volatile
    var current: AppLogger = JvmLogger()
}
