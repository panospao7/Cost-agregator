package com.yourname.expensetracker.domain.bank

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.BankConnectionDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.entity.BankConnection
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.privacy.SensitiveHashingService
import com.yourname.expensetracker.domain.util.TimeProvider
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome of the coordinator-level connection establishment APIs
 * ([BankConnectionLifecycleCoordinator.initiateConnection] /
 * [BankConnectionLifecycleCoordinator.completeConnection]).
 *
 * RP-17 17-A: these are the ONLY public connection-establishment paths, and
 * each applies the central [BankFeatureAvailability] gate before any provider
 * integration. Unavailable builds get the typed FeatureUnavailable outcome —
 * never a silent retry, an empty render, or integration entry.
 */
sealed class BankConnectionOperationOutcome {
    /** OAuth page URL obtained; user must complete the flow. */
    data class AuthInitiated(val authUrl: String) : BankConnectionOperationOutcome()

    /** Connection established and persisted. */
    data class ConnectionReady(val connection: BankConnection) : BankConnectionOperationOutcome()

    /** Provider rejected the request (unsupported bank / stub violation). */
    data class Rejected(val reasonCode: DiagnosticReasonCode) : BankConnectionOperationOutcome()

    /** 17-A / D1: bank-sync surface unavailable in this build. */
    data class FeatureUnavailable(val unavailableReason: BankUnavailableReason) : BankConnectionOperationOutcome()

    data class RetryableFailure(val reasonCode: DiagnosticReasonCode) : BankConnectionOperationOutcome()
}

@Singleton
class BankConnectionLifecycleCoordinator @Inject constructor(
    private val bankConnectionDao: BankConnectionDao,
    private val bankApiIntegration: BankApiIntegration,
    private val writeBarrier: DatabaseWriteBarrier,
    private val pendingReviewDao: PendingReviewDao,
    private val database: AppDatabase,
    private val hashingService: SensitiveHashingService,
    private val timeProvider: TimeProvider,
    private val featureAvailability: BankFeatureAvailability
) {
    fun observeConnections(): Flow<List<BankConnectionSummary>> {
        return bankConnectionDao.getAllConnections().map { list ->
            list.map { it.toSummary() }
                .ifEmpty {
                    BankApiIntegration.SUPPORTED_BANKS.map { bank ->
                        BankConnectionSummary(
                            id = 0,
                            bankId = bank.id,
                            bankName = bank.name,
                            countryCode = bank.countryCode,
                            isConnected = false,
                            isActive = false,
                            lastSync = null,
                            lastSyncStatus = null,
                            syncFrequency = "MANUAL"
                        )
                    }
                }
        }
    }

    /**
     * RP-17 17-A: public connection-initiation path. Applies the central
     * availability gate (D1) before any provider integration.
     */
    suspend fun initiateConnection(bankId: String): BankConnectionOperationOutcome {
        if (!featureAvailability.isBankSyncAvailable) {
            return BankConnectionOperationOutcome.FeatureUnavailable(
                BankUnavailableReason.RELEASE_BUILD
            )
        }
        return try {
            val url = bankApiIntegration.initiateConnection(bankId)
            if (url == null) {
                BankConnectionOperationOutcome.Rejected(DiagnosticReasonCode.PROVIDER_DISABLED)
            } else {
                BankConnectionOperationOutcome.AuthInitiated(url)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            BankConnectionOperationOutcome.RetryableFailure(DiagnosticReasonCode.UNKNOWN_ERROR)
        }
    }

    /**
     * RP-17 17-A: public connection-completion path. Same availability gate as
     * [initiateConnection].
     */
    suspend fun completeConnection(bankId: String, authCode: String): BankConnectionOperationOutcome {
        if (!featureAvailability.isBankSyncAvailable) {
            return BankConnectionOperationOutcome.FeatureUnavailable(
                BankUnavailableReason.RELEASE_BUILD
            )
        }
        return try {
            val connection = bankApiIntegration.completeConnection(bankId, authCode)
            if (connection == null) {
                BankConnectionOperationOutcome.Rejected(DiagnosticReasonCode.PROVIDER_DISABLED)
            } else {
                BankConnectionOperationOutcome.ConnectionReady(connection)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            BankConnectionOperationOutcome.RetryableFailure(DiagnosticReasonCode.UNKNOWN_ERROR)
        }
    }

    /**
     * RP-17 17-B: sync a connection and return the typed [BankSyncOutcome].
     *
     * Mapping contract (one-to-one):
     *  - the coordinator persists a TERMINAL status for every outcome that has
     *    one ([BankSyncOutcome.toTerminalSyncStatus]) — SUCCESS/PARTIAL advance
     *    `lastSync`, FAILED-family update `lastSyncStatus` only;
     *  - no outcome ever persists RUNNING (register D12: terminal-only in DB,
     *    in-flight state lives in the ViewModel);
     *  - [BankSyncOutcome.NotFound] / [BankSyncOutcome.FeatureUnavailable] never
     *    touch the persisted connection state;
     *  - cancellation propagates to the caller and is never an outcome.
     */
    suspend fun syncConnection(connectionId: Long): BankSyncOutcome {
        // 17-A: availability gate before any integration or connection work.
        if (!featureAvailability.isBankSyncAvailable) {
            return BankSyncOutcome.FeatureUnavailable(BankUnavailableReason.RELEASE_BUILD)
        }
        val connection = try {
            bankConnectionDao.getById(connectionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return BankSyncOutcome.RetryableFailure(DiagnosticReasonCode.UNKNOWN_ERROR)
        } ?: return BankSyncOutcome.NotFound

        return try {
            val outcome = bankApiIntegration.syncTransactions(connection)
            persistOutcome(connectionId, outcome)
            outcome
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val outcome = BankSyncOutcome.RetryableFailure(DiagnosticReasonCode.UNKNOWN_ERROR)
            persistOutcome(connectionId, outcome)
            outcome
        }
    }

    /** Persist the outcome's terminal status; never advances lastSync on failure. */
    private suspend fun persistOutcome(connectionId: Long, outcome: BankSyncOutcome) {
        val status = outcome.toTerminalSyncStatus() ?: return
        try {
            writeBarrier.checkWritesAllowed("BankConnectionLifecycleCoordinator.persistOutcome")
            if (outcome.advancesLastSync()) {
                bankConnectionDao.updateSyncStatus(connectionId, timeProvider.now(), status)
            } else {
                bankConnectionDao.updateSyncStatusOnly(connectionId, status)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: DatabaseAccessBlockedException) {
            // Status persistence is secondary; never write through a denied restore barrier.
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Status persistence is secondary UI state; the outcome itself is the
            // product truth. Never fail the reported outcome over it.
            Timber.w(
                "Failed to persist bank sync status for connection %s",
                connectionId
            )
        }
    }

    /**
     * Disconnect a connection.
     *
     * RP-17 17-E: defined barrier/transaction order —
     *  1. write barrier checked before any DB access,
     *  2. in ONE database transaction: delete ONLY the pending reviews whose
     *     stable bank connection scope hash matches this connection
     *     (17-D identity scope), then disconnect the connection row
     *     (clears accessToken/refreshToken/tokenExpiry and isConnected/isActive).
     *
     * This path is availability-independent: disconnect is privacy-positive
     * cleanup of bank data and must always be able to run.
     */
    suspend fun disconnectConnection(connectionId: Long): ConnectionDisconnectResult {
        try {
            writeBarrier.checkWritesAllowed("BankConnectionLifecycleCoordinator.disconnectConnection")
            val connection = bankConnectionDao.getById(connectionId)
                ?: return ConnectionDisconnectResult.NotFound
            val scopeHash = hashingService.hmacSha256Prefix(
                connection.id.toString(),
                BankApiIntegration.BANK_ACCOUNT_SCOPE_PURPOSE
            )
            database.withTransaction {
                writeBarrier.checkWritesAllowed("BankConnectionLifecycleCoordinator.disconnectConnection.tx")
                if (scopeHash != null) {
                    pendingReviewDao.deleteByBankConnectionScope(scopeHash)
                }
                bankConnectionDao.disconnect(connectionId)
            }
            return ConnectionDisconnectResult.Success
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return ConnectionDisconnectResult.RetryableFailure
        }
    }
}

sealed class ConnectionDisconnectResult {
    data object Success : ConnectionDisconnectResult()
    data object NotFound : ConnectionDisconnectResult()
    data object RetryableFailure : ConnectionDisconnectResult()
}

private fun BankConnection.toSummary() = BankConnectionSummary(
    id = id,
    bankId = bankId,
    bankName = bankName,
    countryCode = countryCode,
    isConnected = isConnected,
    isActive = isActive,
    lastSync = lastSync,
    lastSyncStatus = lastSyncStatus.name,
    syncFrequency = syncFrequency.name
)
