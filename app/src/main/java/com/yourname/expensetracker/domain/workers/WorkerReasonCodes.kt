package com.yourname.expensetracker.domain.workers

import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * PR12J-1: Central mapper that converts exceptions into safe, structured
 * [DiagnosticReasonCode] names. All paths are constrained to 1–80 uppercase
 * alphanumeric/underscore characters — never raw exception messages, file
 * paths, or PII.
 */
object WorkerReasonCodes {
    fun retryReasonFor(error: Throwable): String = when (error) {
        is TimeoutCancellationException -> DiagnosticReasonCode.WORKER_TIMEOUT.name
        is RetryableWorkerException -> sanitizeReasonCode(error.reasonCode)
        is WorkerCheckpointBlockedException -> error.reasonCode
        else -> DiagnosticReasonCode.WORKER_TRANSIENT_ERROR.name
    }

    fun failureReasonFor(error: Throwable): String = when (error) {
        is TimeoutCancellationException -> DiagnosticReasonCode.WORKER_TIMEOUT.name
        is RetryableWorkerException -> sanitizeReasonCode(error.reasonCode)
        is WorkerCheckpointBlockedException -> error.reasonCode
        is CancellationException -> DiagnosticReasonCode.WORKER_CANCELLED.name
        is SecurityException -> DiagnosticReasonCode.WORKER_NOTIFICATION_PERMISSION_DENIED.name
        else -> DiagnosticReasonCode.WORKER_UNHANDLED_EXCEPTION.name
    }

    fun diagnosticFor(reasonCode: String, error: Throwable?): String = when {
        error is TimeoutCancellationException -> DiagnosticReasonCode.WORKER_TIMEOUT.name
        error is RetryableWorkerException -> sanitizeReasonCode(error.reasonCode)
        error is WorkerCheckpointBlockedException -> error.reasonCode
        error is SecurityException -> DiagnosticReasonCode.WORKER_NOTIFICATION_PERMISSION_DENIED.name
        else -> sanitizeReasonCode(reasonCode)
    }

    fun sanitizeReasonCode(candidate: String?): String {
        if (candidate == null) return DiagnosticReasonCode.WORKER_UNHANDLED_EXCEPTION.name
        return if (candidate.matches(Regex("[A-Z0-9_]{1,80}"))) {
            candidate
        } else {
            DiagnosticReasonCode.WORKER_UNHANDLED_EXCEPTION.name
        }
    }
}
