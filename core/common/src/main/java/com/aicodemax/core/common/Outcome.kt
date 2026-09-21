package com.aicodemax.core.common

/** Canonical result type across all layers. Never throw across layers — return [Outcome]. */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: AppError) : Outcome<Nothing>
}

data class AppError(
    val code: String,
    val message: String,
    val causeMessage: String? = null,
)

fun <T> Outcome<T>.isSuccess(): Boolean = this is Outcome.Success
fun <T> Outcome<T>.isFailure(): Boolean = this is Outcome.Failure

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
    is Outcome.Success -> transform(value)
    is Outcome.Failure -> this
}

inline fun <T, R> Outcome<T>.fold(onSuccess: (T) -> R, onFailure: (AppError) -> R): R = when (this) {
    is Outcome.Success -> onSuccess(value)
    is Outcome.Failure -> onFailure(error)
}

inline fun <T> Outcome<T>.onSuccess(action: (T) -> Unit): Outcome<T> {
    if (this is Outcome.Success) action(value)
    return this
}

inline fun <T> Outcome<T>.onFailure(action: (AppError) -> Unit): Outcome<T> {
    if (this is Outcome.Failure) action(error)
    return this
}

fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Success)?.value

/** Runs [block], converting any thrown [Throwable] into [Outcome.Failure]. */
inline fun <T> runOutcome(code: String = "UNEXPECTED", block: () -> T): Outcome<T> = try {
    Outcome.Success(block())
} catch (t: Throwable) {
    Outcome.Failure(AppError(code, t.message ?: t.javaClass.simpleName, t.cause?.message))
}
