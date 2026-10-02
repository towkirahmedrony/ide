package com.agentx.app.model.ratelimit

/**
 * A single numeric quota. Unknown values are never replaced with guessed
 * numbers: only [Known] limits are enforced.
 */
sealed class Quota {
    data object Unknown : Quota()

    data class Known(val value: Long) : Quota() {
        init {
            require(value >= 0L) { "quota must not be negative" }
        }
    }

    val knownValue: Long? get() = (this as? Known)?.value

    val isUnknown: Boolean get() = this is Unknown

    fun wouldExceed(used: Long, additional: Long): Boolean = when (this) {
        is Unknown -> false
        is Known -> used + additional > value
    }

    companion object {
        fun of(value: Long?): Quota = if (value == null) Unknown else Known(value)

        fun of(value: Int?): Quota = of(value?.toLong())
    }
}
