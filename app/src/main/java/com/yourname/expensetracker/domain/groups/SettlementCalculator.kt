package com.yourname.expensetracker.domain.groups

import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import kotlinx.coroutines.flow.first
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Data class representing a settlement transaction.
 */
data class Settlement(
    val fromMemberId: Long,
    val fromMemberName: String,
    val toMemberId: Long,
    val toMemberName: String,
    val amount: Double,
    val usedGreedyFallback: Boolean = false,
    val currency: String // MUST be explicitly provided by callers
)

/**
 * Calculates settlements between group members.
 *
 * Uses an exact DFS/backtracking solver (with pruning) on integer cents,
 * minimizing number of transfers with deterministic ordering.
 *
 * ## SHR-3: Configurable timeout
 * The DFS solver timeout (both iteration limit and time budget) is now
 * configurable via [dfsTimeBudgetNs] and [dfsIterationLimit]. Defaults
 * match the pre-v112 hardcoded values (500ms, 100k iterations). Callers
 * can inject custom values through the constructor or via a Hilt module.
 */
@Singleton
class SettlementCalculator @Inject constructor(
    private val currencySettingsRepository: CurrencySettingsRepository,
    /** Time budget for the DFS solver in nanoseconds (default 500ms). */
    private val dfsTimeBudgetNs: Long = 500_000_000L,
    /** Maximum iterations before falling back to greedy solver. */
    private val dfsIterationLimit: Int = 100_000
) {

    /**
     * Calculate an optimal settlement plan to balance all debts.
     * Returns a list of who should pay whom and how much.
     *
     * @param balances Per-member balance map (all must be in the same currency).
     * @param groupCurrency Explicit group currency to use. If null, resolved from balances
     *                      or falls back to the user's home currency.
     * @throws IllegalArgumentException if balances contain mixed currencies.
     */
    suspend fun calculateSettlements(
        balances: Map<Long, MemberBalance>,
        groupCurrency: String? = null
    ): List<Settlement> {
        if (balances.isEmpty()) return emptyList()

        val currencies = balances.values.map { it.currency }.distinct()
        require(currencies.size <= 1) {
            "Settlement requires single currency. Found: $currencies. Convert all balances to group currency first."
        }

        val currency = groupCurrency ?: currencies.firstOrNull()
            ?: currencySettingsRepository.homeCurrency().first()

        val normalized = normalizeBalancesToCents(balances)
        val debtors = normalized
            .filter { it.netCents < 0 }
            .sortedBy { it.memberId }
            .map { Participant(it.memberId, it.memberName, -it.netCents) }
            .toMutableList()
        val creditors = normalized
            .filter { it.netCents > 0 }
            .sortedBy { it.memberId }
            .map { Participant(it.memberId, it.memberName, it.netCents) }
            .toMutableList()

        if (debtors.isEmpty() || creditors.isEmpty()) return emptyList()

        val transferPlan = findMinimalTransferPlan(debtors, creditors)

        return transferPlan.settlements.map {
            Settlement(
                fromMemberId = it.fromMemberId,
                fromMemberName = it.fromMemberName,
                toMemberId = it.toMemberId,
                toMemberName = it.toMemberName,
                amount = centsToAmount(it.amountCents),
                usedGreedyFallback = transferPlan.usedGreedyFallback,
                currency = currency
            )
        }
    }

    /**
     * Alternative settlement entry point.
     * Uses the same exact optimization strategy.
     */
    suspend fun calculateSettlementsMinAmount(
        balances: Map<Long, MemberBalance>,
        groupCurrency: String? = null
    ): List<Settlement> {
        return calculateSettlements(balances, groupCurrency)
    }

    /**
     * Get a summary of who owes what to whom as a readable string.
     *
     * @param groupCurrency ISO 4217 currency code for formatting amounts.
     */
    fun getSettlementSummary(settlements: List<Settlement>, groupCurrency: String): String {
        if (settlements.isEmpty()) {
            return "All settled up! No payments needed."
        }

        val builder = StringBuilder()
        builder.append("Settlement Plan:\n\n")

        var totalVolumeCents = 0L
        for ((index, settlement) in settlements.withIndex()) {
            builder.append("${index + 1}. ${settlement.fromMemberName} pays ${settlement.toMemberName}: ${CurrencyFormatter.format(settlement.amount, groupCurrency)}\n")
            totalVolumeCents += amountToCents(settlement.amount)
        }

        val totalVolume = centsToAmount(totalVolumeCents)
        builder.append("\nTotal to settle: ${CurrencyFormatter.format(totalVolume, groupCurrency)}")
        builder.append("\n${settlements.size} transaction${if (settlements.size > 1) "s" else ""} needed")
        if (settlements.any { it.usedGreedyFallback }) {
            builder.append("\n(Approximate plan used due to solver budget limit)")
        }

        return builder.toString()
    }

    /**
     * Get total amount that needs to be settled.
     */
    fun getTotalSettlementAmount(settlements: List<Settlement>): Double {
        val totalCents = settlements.sumOf { amountToCents(it.amount) }
        return centsToAmount(totalCents)
    }

    /**
     * Get number of transactions needed.
     */
    fun getTransactionCount(settlements: List<Settlement>): Int {
        return settlements.size
    }

    private data class BalanceInCents(
        val memberId: Long,
        val memberName: String,
        val netCents: Long
    )

    private data class Participant(
        val memberId: Long,
        val memberName: String,
        var amountCents: Long
    )

    private data class SettlementCents(
        val fromMemberId: Long,
        val fromMemberName: String,
        val toMemberId: Long,
        val toMemberName: String,
        val amountCents: Long
    )

    private data class TransferPlanCents(
        val settlements: List<SettlementCents>,
        val usedGreedyFallback: Boolean
    )

    private fun findMinimalTransferPlan(
        debtorsInput: List<Participant>,
        creditorsInput: List<Participant>
    ): TransferPlanCents {
        val debtors = debtorsInput.map { it.copy() }.toMutableList()
        val creditors = creditorsInput.map { it.copy() }.toMutableList()

        var best: List<SettlementCents>? = null
        val current = mutableListOf<SettlementCents>()
        var iterations = 0
        var timedOut = false
        val startedAtNs = System.nanoTime()

        fun exceedsSolverBudget(): Boolean {
            iterations++
            if (iterations > dfsIterationLimit) {
                return true
            }

            if (iterations % TIME_CHECK_INTERVAL == 0) {
                val elapsedNs = System.nanoTime() - startedAtNs
                if (elapsedNs > dfsTimeBudgetNs) {
                    return true
                }
            }

            return false
        }

        fun lowerBoundRemainingTransactions(): Int {
            val debtLeft = debtors.count { it.amountCents > 0 }
            val credLeft = creditors.count { it.amountCents > 0 }
            return maxOf(debtLeft, credLeft)
        }

        fun lexicographicallyBetter(candidate: List<SettlementCents>, incumbent: List<SettlementCents>): Boolean {
            if (candidate.size != incumbent.size) return candidate.size < incumbent.size

            val c = candidate.sortedWith(settlementComparator())
            val b = incumbent.sortedWith(settlementComparator())

            for (i in c.indices) {
                val cTx = c[i]
                val bTx = b[i]
                if (cTx.fromMemberId != bTx.fromMemberId) return cTx.fromMemberId < bTx.fromMemberId
                if (cTx.toMemberId != bTx.toMemberId) return cTx.toMemberId < bTx.toMemberId
                if (cTx.amountCents != bTx.amountCents) return cTx.amountCents < bTx.amountCents
            }

            return false
        }

        fun dfs() {
            if (timedOut) return
            if (exceedsSolverBudget()) {
                timedOut = true
                return
            }

            val incumbent = best
            if (incumbent != null && current.size + lowerBoundRemainingTransactions() > incumbent.size) {
                return
            }

            val debtorIndex = debtors.indexOfFirst { it.amountCents > 0 }
            if (debtorIndex == -1) {
                val candidate = current.sortedWith(settlementComparator())
                best = when (val existing = best) {
                    null -> candidate
                    else -> if (lexicographicallyBetter(candidate, existing)) candidate else existing
                }
                return
            }

            val debtor = debtors[debtorIndex]
            val usedCreditorAmounts = mutableSetOf<Long>()

            for (creditorIndex in creditors.indices) {
                val creditor = creditors[creditorIndex]
                if (creditor.amountCents <= 0) continue

                if (!usedCreditorAmounts.add(creditor.amountCents)) {
                    continue
                }

                val transfer = minOf(debtor.amountCents, creditor.amountCents)
                if (transfer <= 0) continue

                debtor.amountCents -= transfer
                creditor.amountCents -= transfer
                current.add(
                    SettlementCents(
                        fromMemberId = debtor.memberId,
                        fromMemberName = debtor.memberName,
                        toMemberId = creditor.memberId,
                        toMemberName = creditor.memberName,
                        amountCents = transfer
                    )
                )

                dfs()

                current.removeAt(current.lastIndex)
                creditor.amountCents += transfer
                debtor.amountCents += transfer
            }
        }

        dfs()
        return if (timedOut || best == null) {
            TransferPlanCents(
                settlements = buildGreedyTransferPlan(debtorsInput, creditorsInput),
                usedGreedyFallback = true
            )
        } else {
            TransferPlanCents(
                settlements = best.orEmpty(),
                usedGreedyFallback = false
            )
        }
    }

    private fun buildGreedyTransferPlan(
        debtorsInput: List<Participant>,
        creditorsInput: List<Participant>
    ): List<SettlementCents> {
        val debtors = debtorsInput
            .map { it.copy() }
            .sortedBy { it.memberId }
            .toMutableList()
        val creditors = creditorsInput
            .map { it.copy() }
            .sortedBy { it.memberId }
            .toMutableList()

        val settlements = mutableListOf<SettlementCents>()
        var debtorIndex = 0
        var creditorIndex = 0

        while (debtorIndex < debtors.size && creditorIndex < creditors.size) {
            val debtor = debtors[debtorIndex]
            val creditor = creditors[creditorIndex]

            if (debtor.amountCents <= 0) {
                debtorIndex++
                continue
            }

            if (creditor.amountCents <= 0) {
                creditorIndex++
                continue
            }

            val transfer = minOf(debtor.amountCents, creditor.amountCents)
            if (transfer <= 0) {
                break
            }

            settlements.add(
                SettlementCents(
                    fromMemberId = debtor.memberId,
                    fromMemberName = debtor.memberName,
                    toMemberId = creditor.memberId,
                    toMemberName = creditor.memberName,
                    amountCents = transfer
                )
            )

            debtor.amountCents -= transfer
            creditor.amountCents -= transfer

            if (debtor.amountCents == 0L) debtorIndex++
            if (creditor.amountCents == 0L) creditorIndex++
        }

        return settlements.sortedWith(settlementComparator())
    }

    private fun settlementComparator(): Comparator<SettlementCents> {
        return compareBy<SettlementCents>({ it.fromMemberId }, { it.toMemberId }, { it.amountCents })
    }

    private fun normalizeBalancesToCents(balances: Map<Long, MemberBalance>): List<BalanceInCents> {
        val centsBalances = balances.values
            .map {
                BalanceInCents(
                    memberId = it.memberId,
                    memberName = it.memberName,
                    netCents = amountToCents(it.netBalance)
                )
            }
            .filter { it.netCents != 0L }
            .toMutableList()

        if (centsBalances.isEmpty()) return emptyList()

        val sum = centsBalances.sumOf { it.netCents }
        if (sum == 0L) return centsBalances

        val indexToAdjust = if (sum > 0) {
            centsBalances
                .withIndex()
                .filter { it.value.netCents > 0 }
                .maxByOrNull { it.value.netCents }
                ?.index
        } else {
            centsBalances
                .withIndex()
                .filter { it.value.netCents < 0 }
                .minByOrNull { it.value.netCents }
                ?.index
        } ?: centsBalances.withIndex().maxByOrNull { abs(it.value.netCents) }?.index

        if (indexToAdjust != null) {
            val current = centsBalances[indexToAdjust]
            centsBalances[indexToAdjust] = current.copy(netCents = current.netCents - sum)
        }

        return centsBalances.filter { it.netCents != 0L }
    }

    private fun amountToCents(amount: Double): Long {
        return BigDecimal.valueOf(amount)
            .setScale(2, RoundingMode.HALF_UP)
            .movePointRight(2)
            .toLong()
    }

    private fun centsToAmount(cents: Long): Double {
        return BigDecimal.valueOf(cents)
            .movePointLeft(2)
            .toDouble()
    }

    private companion object {
        /** How often (in iterations) to check elapsed wall-clock time. */
        private const val TIME_CHECK_INTERVAL = 256
    }
}
