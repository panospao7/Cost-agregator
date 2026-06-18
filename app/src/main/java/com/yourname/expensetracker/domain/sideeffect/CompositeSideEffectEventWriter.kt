package com.yourname.expensetracker.domain.sideeffect

import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CompositeSideEffectEventWriter @Inject constructor(
    private val diagnosticWriter: DiagnosticSideEffectEventWriter,
    private val transactionFailureWriter: TransactionSideEffectFailureEventWriter
) : SideEffectEventWriter {

    override suspend fun started(action: PostCommitAction) {
        try {
            diagnosticWriter.started(action)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Side-effect event writer diagnostic failed")
        }
    }

    override suspend fun completed(action: PostCommitAction) {
        try {
            diagnosticWriter.completed(action)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Side-effect event writer diagnostic failed")
        }
    }

    override suspend fun skipped(action: PostCommitAction, reason: SideEffectSkipReason) {
        try {
            diagnosticWriter.skipped(action, reason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Side-effect event writer diagnostic failed")
        }
    }

    override suspend fun failed(
        action: PostCommitAction,
        retryable: Boolean,
        reason: String,
        error: Throwable?
    ) {
        try {
            diagnosticWriter.failed(action, retryable, reason, error)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Side-effect event writer diagnostic failed")
        }
        try {
            transactionFailureWriter.failed(action, retryable, reason, error)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Side-effect event writer transaction failed")
        }
    }

    override suspend fun cancelled(action: PostCommitAction, reason: String?) {
        try {
            diagnosticWriter.cancelled(action, reason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Side-effect event writer child failed")
        }
    }
}
