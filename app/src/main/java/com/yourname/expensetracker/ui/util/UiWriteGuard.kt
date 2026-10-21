package com.yourname.expensetracker.ui.util

import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.domain.model.UiText
import timber.log.Timber

/** Bounded diagnostic code logged when a user-started write hits the maintenance write barrier. */
const val UI_WRITE_BLOCKED_CODE = "WRITE_BLOCKED"

/**
 * User-facing message shown while backup / restore / reset holds the database write barrier.
 * Resource-backed, so no raw exception text can reach the UI.
 */
val UI_WRITE_BLOCKED_MESSAGE: UiText = UiText.StringResource(R.string.ui_write_blocked_maintenance)

/**
 * Runs a user-started write [block]. While a backup/restore/reset holds the write barrier the
 * repository layer throws [DatabaseAccessBlockedException]; that single exception type is turned
 * into a bounded message delivered through [onBlocked] and the call returns `null`.
 *
 * Contract:
 * - ONLY [DatabaseAccessBlockedException] is handled. [kotlinx.coroutines.CancellationException]
 *   and every other failure propagate unchanged (no global handler, no swallowing).
 * - The write is NOT retried; the user can retry once the maintenance operation finishes.
 * - Only the exception class name is logged (never the message, operation or payload).
 */
suspend fun <T> guardedWrite(
    onBlocked: (UiText) -> Unit,
    block: suspend () -> T
): T? =
    try {
        block()
    } catch (e: DatabaseAccessBlockedException) {
        Timber.w("%s class=%s", UI_WRITE_BLOCKED_CODE, e::class.java.simpleName)
        onBlocked(UI_WRITE_BLOCKED_MESSAGE)
        null
    }
