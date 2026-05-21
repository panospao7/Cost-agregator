package com.yourname.expensetracker.domain.sideeffect

import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Runs a [PostCommitActionBatch] with best-effort semantics.
 *
 * - Empty batches return null immediately.
 * - [CancellationException] is always re-thrown to preserve coroutine cancellation.
 * - Non-cancellation exceptions are logged and return null (best-effort).
 */
suspend fun PostCommitActionRunner.runBestEffortAfterCommit(
    batch: PostCommitActionBatch,
    logMessage: String,
    targetId: Long? = null
): SideEffectBatchResult? {
    if (batch.actions.isEmpty()) return null

    return try {
        run(batch)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (targetId != null) {
            Timber.w(e, "$logMessage targetId=%d", targetId)
        } else {
            Timber.w(e, logMessage)
        }
        null
    }
}

/**
 * Runs an arbitrary suspend block with best-effort post-commit semantics.
 *
 * - [CancellationException] is always re-thrown.
 * - Non-cancellation exceptions are logged and swallowed.
 */
suspend inline fun runBestEffortPostCommit(
    logMessage: String,
    crossinline block: suspend () -> Unit
) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, logMessage)
    }
}
