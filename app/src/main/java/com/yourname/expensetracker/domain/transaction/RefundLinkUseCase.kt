package com.yourname.expensetracker.domain.transaction

import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ExpenseRefundLinkDao
import com.yourname.expensetracker.data.database.dao.GroupExpenseDao
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.CancellationSafe
import timber.log.Timber
import javax.inject.Inject

/**
 * Refund netting (DR-05) application entry point and the ONLY caller of
 * [TransactionLifecycleCoordinator.linkRefund] and [TransactionLifecycleCoordinator.unlinkRefund]
 * (INV-16): a link exists only after one explicit user action that names the pair. No worker,
 * parser, intake path or post-commit action calls it.
 *
 * - [link] and [unlink] delegate to the lifecycle coordinator, which owns every write.
 * - [findCandidates] and [linksOf] are read-only and fail closed (INV-17): a blocked read gives
 *   `Unavailable(READ_BLOCKED)`, any other error gives `Unavailable(READ_FAILED)`, never an empty
 *   `Available`. [kotlinx.coroutines.CancellationException] is rethrown.
 *
 * Diagnostics carry bounded codes only (`REFUND_CANDIDATES_UNAVAILABLE`, `REFUND_LINKS_UNAVAILABLE`
 * with `READ_BLOCKED` or `READ_FAILED`); never a merchant, amount, path or SQL message.
 */
class RefundLinkUseCase @Inject constructor(
    private val coordinator: TransactionLifecycleCoordinator,
    private val expenseDao: ExpenseDao,
    private val refundLinkDao: ExpenseRefundLinkDao,
    private val groupExpenseDao: GroupExpenseDao,
    private val readBarrier: DatabaseReadBarrier
) {

    /** Links the DEPOSIT [refundId] to the PURCHASE [purchaseId]. See [TransactionLifecycleCoordinator.linkRefund]. */
    suspend fun link(refundId: Long, purchaseId: Long): RefundLinkResult =
        coordinator.linkRefund(refundId, purchaseId)

    /** Removes the link of [refundId]. See [TransactionLifecycleCoordinator.unlinkRefund]. */
    suspend fun unlink(refundId: Long): RefundUnlinkResult =
        coordinator.unlinkRefund(refundId)

    /**
     * Ranked purchases the DEPOSIT [refundId] could be applied to (spec 1.6): one range read of
     * `getExpensesBetween(refund.date - WINDOW_MS, refund.date + 1 day)`, the pair rule and ranking
     * from [RefundNettingPolicy], group-linked purchases skipped, at most
     * [RefundNettingPolicy.MAX_CANDIDATES] items. A missing, ineligible, group-linked or already
     * linked refund gives `Available(emptyList())` (nothing to offer), which is a real empty result
     * and not an error.
     */
    suspend fun findCandidates(refundId: Long): RefundCandidates {
        return try {
            readBarrier.checkReadAllowed("RefundLinkUseCase.findCandidates")
            RefundCandidates.Available(computeCandidates(refundId))
        } catch (e: Exception) {
            CancellationSafe.rethrowIfCancellation(e)
            val reason = readFailureOf(e)
            Timber.w("Refund netting: code=REFUND_CANDIDATES_UNAVAILABLE reason=%s", reason.name)
            RefundCandidates.Unavailable(reason)
        }
    }

    /** The refund links of [expenseId], seen from that row (either side). Fail closed like [findCandidates]. */
    suspend fun linksOf(expenseId: Long): RefundLinks {
        return try {
            readBarrier.checkReadAllowed("RefundLinkUseCase.linksOf")
            val links = refundLinkDao.getForExpense(expenseId).map { link ->
                val currency = CurrencyCode.parse(link.currency)
                    ?: throw IllegalStateException("REFUND_LINK_CURRENCY_INVALID")
                RefundLinkInfo(
                    linkId = link.id,
                    counterpartId = if (link.refundExpenseId == expenseId) {
                        link.purchaseExpenseId
                    } else {
                        link.refundExpenseId
                    },
                    appliedAmount = MoneyAmount(link.appliedAmount, currency),
                    createdAt = link.createdAt
                )
            }
            RefundLinks.Available(expenseId, links)
        } catch (e: Exception) {
            CancellationSafe.rethrowIfCancellation(e)
            val reason = readFailureOf(e)
            Timber.w("Refund netting: code=REFUND_LINKS_UNAVAILABLE reason=%s", reason.name)
            RefundLinks.Unavailable(reason)
        }
    }

    private suspend fun computeCandidates(refundId: Long): List<RefundCandidate> {
        val refund = expenseDao.getById(refundId) ?: return emptyList()
        if (groupExpenseDao.existsForExpense(refundId)) return emptyList()
        val purchases = expenseDao.getExpensesBetween(
            refund.date - RefundLikelihoodDetector.WINDOW_MS,
            refund.date + RefundNettingPolicy.CANDIDATE_LOOKAHEAD_MS
        )
        val items = ArrayList<RefundCandidate>()
        for (candidate in RefundNettingPolicy.rankCandidates(refund, purchases)) {
            if (items.size >= RefundNettingPolicy.MAX_CANDIDATES) break
            if (groupExpenseDao.existsForExpense(candidate.purchaseId)) continue
            items.add(candidate)
        }
        return items
    }

    private fun readFailureOf(error: Exception): RefundReadFailure =
        if (error is DatabaseAccessBlockedException) RefundReadFailure.READ_BLOCKED else RefundReadFailure.READ_FAILED
}
