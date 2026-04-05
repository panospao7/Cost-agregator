package com.yourname.expensetracker.data.ai.provider.internal

import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.random.Random

object CloudRetryPolicy {
    const val MAX_RETRY_ATTEMPTS = 3
    const val BASE_RETRY_BACKOFF_MS = 250L
    const val MAX_RETRY_BACKOFF_MS = 1_500L
    const val RETRY_JITTER_MS = 200L

    fun isRetryable(code: Int): Boolean =
        code in 500..599 || code == 429 || code == 408

    fun isRetryableHttpStatus(code: Int): Boolean = isRetryable(code)

    fun isRetryableIoException(exception: IOException): Boolean {
        var current: Throwable? = exception
        while (current != null) {
            if (current is SocketTimeoutException) return true
            if (current is IOException) {
                val message = current.message.orEmpty()
                if (
                    message.contains("connection reset", ignoreCase = true) ||
                    message.contains("connection aborted", ignoreCase = true) ||
                    message.contains("timeout", ignoreCase = true)
                ) {
                    return true
                }
            }
            current = current.cause
        }

        return false
    }

    fun backoffDelayMs(attempt: Int): Long {
        val safeAttempt = attempt.coerceAtLeast(1)
        val exponential = (BASE_RETRY_BACKOFF_MS shl (safeAttempt - 1)).coerceAtMost(MAX_RETRY_BACKOFF_MS)
        val jitter = Random.nextLong(0, RETRY_JITTER_MS + 1)
        return exponential + jitter
    }
}
