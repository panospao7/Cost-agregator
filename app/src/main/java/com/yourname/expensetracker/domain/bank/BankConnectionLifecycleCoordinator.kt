package com.yourname.expensetracker.domain.bank

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.BankConnectionDao
import com.yourname.expensetracker.data.database.entity.BankConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BankConnectionLifecycleCoordinator @Inject constructor(
    private val bankConnectionDao: BankConnectionDao,
    private val bankApiIntegration: BankApiIntegration,
    private val writeBarrier: DatabaseWriteBarrier
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

    suspend fun syncConnection(connectionId: Long): ConnectionSyncResult {
        try {
            val connection = bankConnectionDao.getById(connectionId)
                ?: return ConnectionSyncResult.NotFound
            return try {
                bankApiIntegration.syncTransactions(connection)
                ConnectionSyncResult.Success
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                ConnectionSyncResult.RetryableFailure
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            return ConnectionSyncResult.RetryableFailure
        }
    }

    suspend fun disconnectConnection(connectionId: Long): ConnectionDisconnectResult {
        try {
            writeBarrier.checkWritesAllowed("BankConnectionLifecycleCoordinator.disconnectConnection")
            val exists = bankConnectionDao.getById(connectionId)
            if (exists == null) return ConnectionDisconnectResult.NotFound
            bankConnectionDao.disconnect(connectionId)
            return ConnectionDisconnectResult.Success
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            return ConnectionDisconnectResult.RetryableFailure
        }
    }
}

sealed class ConnectionSyncResult {
    data object Success : ConnectionSyncResult()
    data object NotFound : ConnectionSyncResult()
    data object RetryableFailure : ConnectionSyncResult()
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
