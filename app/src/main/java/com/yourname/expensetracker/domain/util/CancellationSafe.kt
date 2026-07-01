package com.yourname.expensetracker.domain.util

import kotlinx.coroutines.CancellationException

/**
 * Shared cancellation-safety helpers for suspend/coroutine paths.
 *
 * U-PR1 / PR 2 — MIT-034: CancellationException propagation.
 *
 * Rules:
 * - Rethrow [CancellationException] — never convert cancellation into success/failure.
 * - Sanitize non-cancellation exceptions where safe (caller's responsibility).
 * - Never swallow cancellation in result wrappers or error handlers.
 */
object CancellationSafe {

    /**
     * A cancellation-safe alternative to [kotlin.runCatching] for suspend paths.
     *
     * Wraps the [block] result in a [Result], but rethrows [CancellationException]
     * AND any subclass (such as [kotlinx.coroutines.TimeoutCancellationException])
     * instead of trapping it inside [Result.failure].
     *
     * Usage:
     * ```kotlin
     * val result: Result<Data> = CancellationSafe.runCatchingCancellable {
     *     fetchData()
     * }
     * ```
     */
    inline fun <T> runCatchingCancellable(block: () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Rethrows [CancellationException] (and subclasses) so that structured
     * concurrency can propagate cancellation.
     *
     * Usage inside `catch (e: Exception)` blocks:
     * ```kotlin
     * } catch (e: Exception) {
     *     CancellationSafe.rethrowIfCancellation(e)
     *     Timber.w(e, "Non-cancellation failure")
     * }
     * ```
     *
     * @throws CancellationException if [error] is a [CancellationException].
     */
    fun rethrowIfCancellation(error: Throwable) {
        if (error is CancellationException) throw error
    }
}
