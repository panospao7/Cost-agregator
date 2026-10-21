package com.yourname.expensetracker.domain.diagnostics

/**
 * Single policy for turning a [Throwable] into text that may be stored or logged by diagnostics.
 *
 * Exception messages can carry raw user data (merchant names, amounts, paths, OCR/notification
 * text, SQL fragments), so only the exception CLASS simple name is ever persisted. The message
 * and stack trace are never read here.
 */
object DiagnosticThrowableSummary {

    private const val FALLBACK = "Exception"
    private const val MAX_LENGTH = 64

    /** Returns the exception class simple name only (never the message), bounded in length. */
    fun className(error: Throwable): String =
        error.javaClass.simpleName
            .takeIf { it.isNotBlank() }
            ?.take(MAX_LENGTH)
            ?: FALLBACK
}
