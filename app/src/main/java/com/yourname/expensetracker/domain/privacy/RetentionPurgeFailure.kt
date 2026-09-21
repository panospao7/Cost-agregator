package com.yourname.expensetracker.domain.privacy

/**
 * RP-14 14b: typed retention purge failure.
 *
 * Replaces retry classification from arbitrary exception message substrings:
 * a purge that fails in a controlled way throws/encodes this type (or the
 * worker classifies controlled DAO/SQLite/IO exception CLASSES), and only the
 * failure code, exception class name, target name, and row counts are ever
 * persisted — never messages, SQL text, stack traces, or file paths.
 *
 * The message is the controlled [failureCode] constant only.
 *
 * @param failureCode Controlled constant (e.g. a [com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode] name).
 * @param transient True when the failure is retryable (I/O, SQLite contention,
 *   write-barrier denial); false for permanent failures.
 */
class RetentionPurgeFailure(
    val failureCode: String,
    val transient: Boolean,
    cause: Throwable? = null
) : RuntimeException(failureCode, cause)
