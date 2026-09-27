package dev.forge.ide.core

/**
 * A minimal result type used to make failure paths explicit across module
 * boundaries without depending on a third-party library.
 */
sealed interface ForgeResult<out T, out E> {
    data class Success<out T>(val value: T) : ForgeResult<T, Nothing>

    data class Failure<out E>(val error: E) : ForgeResult<Nothing, E>
}

fun <T> success(value: T): ForgeResult<T, Nothing> = ForgeResult.Success(value)

fun <E> failure(error: E): ForgeResult<Nothing, E> = ForgeResult.Failure(error)

inline fun <T, E, R> ForgeResult<T, E>.map(transform: (T) -> R): ForgeResult<R, E> = when (this) {
    is ForgeResult.Success -> ForgeResult.Success(transform(value))
    is ForgeResult.Failure -> this
}

fun <T, E> ForgeResult<T, E>.valueOrNull(): T? = when (this) {
    is ForgeResult.Success -> value
    is ForgeResult.Failure -> null
}

fun <T, E> ForgeResult<T, E>.errorOrNull(): E? = when (this) {
    is ForgeResult.Success -> null
    is ForgeResult.Failure -> error
}
