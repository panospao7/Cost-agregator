package com.yourname.expensetracker.domain.workers

/**
 * Thrown by a worker block to explicitly request a WorkManager retry,
 * independent of message-based transient classification.
 *
 * [WorkerExecutionGuard] recognizes this type directly (with precedence below
 * [kotlinx.coroutines.CancellationException]) and maps it to
 * [WorkerGuardResult.Retry], so a worker's retry intent is never lost to the
 * keyword-matching fallback in `classifyTransient`.
 */
class RetryableWorkerException(message: String, cause: Throwable? = null) : Exception(message, cause)
