package com.yourname.expensetracker.domain.bank

import com.yourname.expensetracker.data.database.entity.SyncStatus
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode

/**
 * RP-17 17-B: typed outcome of a bank connection sync.
 *
 * Replaces the flat `SyncResult(success: Boolean, errors: List<String>)` and all
 * string parsing. Every barrier/token/reauth/disconnect branch assigns exactly
 * one outcome; the coordinator persists it one-to-one and returns it to the
 * ViewModel, which maps codes to resources / re-auth UI.
 *
 * Register D12 (sync status): the DB stores TERMINAL statuses only
 * (NEVER/SUCCESS/PARTIAL/FAILED). There is deliberately NO durable
 * running/attempt field and NO migration for one — in-flight state lives in the
 * ViewModel only ([BankConnectionsViewModel.syncingConnectionIds]).
 *
 * Cancellation never becomes an outcome: [kotlinx.coroutines.CancellationException]
 * propagates to the caller, per the existing operation contract.
 */
sealed interface BankSyncOutcome {
    /** Transactions imported (created or sent to review). */
    val importedCount: Int

    /** Transactions legitimately skipped (duplicates, contract skips). */
    val skippedCount: Int

    /** Transactions that failed (validation or unexpected error). */
    val failedCount: Int

    /** Optional controlled reason code; never payload-derived text. */
    val reasonCode: DiagnosticReasonCode?

    /** Sync finished; all items processed without failures. Skips are legitimate. */
    data class Success(
        override val importedCount: Int = 0,
        override val skippedCount: Int = 0,
        override val reasonCode: DiagnosticReasonCode? = null
    ) : BankSyncOutcome {
        override val failedCount: Int = 0
    }

    /** Sync finished but some items failed while others imported. */
    data class Partial(
        override val importedCount: Int,
        override val skippedCount: Int = 0,
        override val failedCount: Int,
        override val reasonCode: DiagnosticReasonCode? = null
    ) : BankSyncOutcome

    /**
     * Keystore key permanently invalidated — the stored refresh token can never
     * be decrypted again; the user must re-authenticate (re-auth UI).
     */
    data class ReauthRequired(
        override val reasonCode: DiagnosticReasonCode = DiagnosticReasonCode.TOKEN_INVALID
    ) : BankSyncOutcome {
        override val importedCount: Int = 0
        override val skippedCount: Int = 0
        override val failedCount: Int = 0
    }

    /**
     * Sync could not run: write barrier (restore/backup in progress) or the
     * connection was disconnected mid-flight (token write affected zero rows).
     */
    data class Blocked(
        override val reasonCode: DiagnosticReasonCode
    ) : BankSyncOutcome {
        override val importedCount: Int = 0
        override val skippedCount: Int = 0
        override val failedCount: Int = 0
    }

    /** Sync failed with a transient/retryable condition. */
    data class RetryableFailure(
        override val reasonCode: DiagnosticReasonCode? = null,
        override val failedCount: Int = 0
    ) : BankSyncOutcome {
        override val importedCount: Int = 0
        override val skippedCount: Int = 0
    }

    /** Sync failed with a condition that will not succeed on retry. */
    data class PermanentFailure(
        override val reasonCode: DiagnosticReasonCode? = null
    ) : BankSyncOutcome {
        override val importedCount: Int = 0
        override val skippedCount: Int = 0
        override val failedCount: Int = 0
    }

    /** Connection id does not exist. No status transition is persisted. */
    data object NotFound : BankSyncOutcome {
        override val importedCount: Int = 0
        override val skippedCount: Int = 0
        override val failedCount: Int = 0
        override val reasonCode: DiagnosticReasonCode? = null
    }

    /**
     * 17-A / D1: bank-sync surface is unavailable in this build. Returned by the
     * coordinator's public entry points; never enters provider integration.
     */
    data class FeatureUnavailable(
        val unavailableReason: BankUnavailableReason
    ) : BankSyncOutcome {
        override val importedCount: Int = 0
        override val skippedCount: Int = 0
        override val failedCount: Int = 0
        override val reasonCode: DiagnosticReasonCode? = null
    }
}

/**
 * Terminal sync status for an outcome, or null when the outcome must not touch
 * the persisted connection state at all.
 *
 * Last-sync semantics (17-B): `lastSync` advances only for terminal statuses
 * that reflect actual data retrieval (SUCCESS / PARTIAL). FAILED-family outcomes
 * update `lastSyncStatus` only and never advance `lastSync`. No outcome ever
 * persists RUNNING (register D12).
 */
fun BankSyncOutcome.toTerminalSyncStatus(): SyncStatus? = when (this) {
    is BankSyncOutcome.Success -> SyncStatus.SUCCESS
    is BankSyncOutcome.Partial -> SyncStatus.PARTIAL
    is BankSyncOutcome.ReauthRequired,
    is BankSyncOutcome.Blocked,
    is BankSyncOutcome.RetryableFailure,
    is BankSyncOutcome.PermanentFailure -> SyncStatus.FAILED
    BankSyncOutcome.NotFound,
    is BankSyncOutcome.FeatureUnavailable -> null
}

/** True when [status] advanced the connection's `lastSync` timestamp. */
fun BankSyncOutcome.advancesLastSync(): Boolean = when (toTerminalSyncStatus()) {
    SyncStatus.SUCCESS, SyncStatus.PARTIAL -> true
    else -> false
}
