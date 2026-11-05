package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.database.dao.CurrentUserSettlementRow
import com.yourname.expensetracker.data.database.dao.GroupExpenseDao
import com.yourname.expensetracker.data.database.dao.GroupSettlementDao
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** DR-01 group reads for one half-open window. A failed read leaves its field empty and its flag set. */
data class GroupCashFlowSnapshot(
    val nonPayerExpenseIds: Set<Long>,
    val payerExpenseIds: Set<Long>,
    val settlements: List<CurrentUserSettlementRow>,
    val nonPayerReadFailed: Boolean,
    val payerReadFailed: Boolean,
    val settlementReadFailed: Boolean
) {
    val anyReadFailed: Boolean get() = nonPayerReadFailed || payerReadFailed || settlementReadFailed
}

/**
 * Read-only wrapper over the three DR-01 group probes (non-payer links, payer links, the current
 * user's settlements), each behind the read barrier with its own typed failure flag. Same
 * half-open range [startDate, endDate) as the DAOs. Cancellation is always rethrown; logs carry
 * only the exception class name.
 */
@Singleton
class GroupCashFlowReader @Inject constructor(
    private val groupExpenseDao: GroupExpenseDao,
    private val groupSettlementDao: GroupSettlementDao,
    private val databaseReadBarrier: DatabaseReadBarrier
) {
    suspend fun read(startDate: Long, endDate: Long, operationPrefix: String): GroupCashFlowSnapshot {
        var nonPayerFailed = false
        val nonPayerIds: Set<Long> = try {
            databaseReadBarrier.checkReadAllowed("$operationPrefix.readGroupPayers")
            groupExpenseDao.getNonPayerLinkedExpenseIdsBetween(startDate, endDate).toSet()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.w("GroupCashFlowReader: group payer lookup failed (%s)", e.javaClass.simpleName)
            nonPayerFailed = true
            emptySet()
        }

        var payerFailed = false
        val payerIds: Set<Long> = try {
            databaseReadBarrier.checkReadAllowed("$operationPrefix.readGroupPayerRows")
            groupExpenseDao.getPayerLinkedExpenseIdsBetween(startDate, endDate).toSet()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.w("GroupCashFlowReader: group payer-row lookup failed (%s)", e.javaClass.simpleName)
            payerFailed = true
            emptySet()
        }

        var settlementFailed = false
        val settlements: List<CurrentUserSettlementRow> = try {
            databaseReadBarrier.checkReadAllowed("$operationPrefix.readGroupSettlements")
            groupSettlementDao.getCurrentUserSettlementsBetween(startDate, endDate)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.w("GroupCashFlowReader: group settlement lookup failed (%s)", e.javaClass.simpleName)
            settlementFailed = true
            emptyList()
        }

        return GroupCashFlowSnapshot(
            nonPayerExpenseIds = nonPayerIds,
            payerExpenseIds = payerIds,
            settlements = settlements,
            nonPayerReadFailed = nonPayerFailed,
            payerReadFailed = payerFailed,
            settlementReadFailed = settlementFailed
        )
    }
}
