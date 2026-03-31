package com.yourname.expensetracker.domain.groups

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data class representing a settlement transaction.
 */
data class Settlement(
    val fromMemberId: Long,
    val fromMemberName: String,
    val toMemberId: Long,
    val toMemberName: String,
    val amount: Double
)

/**
 * Calculates optimal settlements between group members.
 * Uses a greedy algorithm to minimize the number of transactions.
 */
@Singleton
class SettlementCalculator @Inject constructor() {
    
    /**
     * Calculate the optimal set of settlements to balance all debts.
     * Returns a list of who should pay whom and how much.
     */
    fun calculateSettlements(balances: Map<Long, MemberBalance>): List<Settlement> {
        val settlements = mutableListOf<Settlement>()
        
        // Separate debtors (owe money) and creditors (are owed money)
        val debtors = balances.values
            .filter { it.netBalance < -0.01 } // Owes money (negative balance)
            .sortedBy { it.netBalance } // Sort by amount owed (most first)
            .map { BalanceNode(it.memberId, it.memberName, -it.netBalance) } // Make positive
            .toMutableList()
        
        val creditors = balances.values
            .filter { it.netBalance > 0.01 } // Is owed money (positive balance)
            .sortedByDescending { it.netBalance } // Sort by amount owed (most first)
            .map { BalanceNode(it.memberId, it.memberName, it.netBalance) } // Keep positive
            .toMutableList()
        
        // Greedy algorithm: Match largest debtor with largest creditor
        while (debtors.isNotEmpty() && creditors.isNotEmpty()) {
            val debtor = debtors.first()
            val creditor = creditors.first()
            
            val settlementAmount = minOf(debtor.amount, creditor.amount)
            
            settlements.add(
                Settlement(
                    fromMemberId = debtor.id,
                    fromMemberName = debtor.name,
                    toMemberId = creditor.id,
                    toMemberName = creditor.name,
                    amount = settlementAmount
                )
            )
            
            // Update remaining amounts
            debtor.amount -= settlementAmount
            creditor.amount -= settlementAmount
            
            // Remove settled accounts
            if (debtor.amount < 0.01) {
                debtors.removeAt(0)
            }
            if (creditor.amount < 0.01) {
                creditors.removeAt(0)
            }
        }
        
        return settlements
    }
    
    /**
     * Alternative settlement calculation that tries to minimize total amount transferred.
     * This might result in more transactions but less money movement.
     */
    fun calculateSettlementsMinAmount(balances: Map<Long, MemberBalance>): List<Settlement> {
        // For now, use the same greedy algorithm
        // In the future, this could implement a more complex optimization
        return calculateSettlements(balances)
    }
    
    /**
     * Get a summary of who owes what to whom as a readable string.
     */
    fun getSettlementSummary(settlements: List<Settlement>): String {
        if (settlements.isEmpty()) {
            return "All settled up! No payments needed."
        }
        
        val builder = StringBuilder()
        builder.append("Settlement Plan:\n\n")
        
        var totalVolume = 0.0
        for ((index, settlement) in settlements.withIndex()) {
            builder.append("${index + 1}. ${settlement.fromMemberName} pays ${settlement.toMemberName}: €${String.format("%.2f", settlement.amount)}\n")
            totalVolume += settlement.amount
        }
        
        builder.append("\nTotal to settle: €${String.format("%.2f", totalVolume)}")
        builder.append("\n${settlements.size} transaction${if (settlements.size > 1) "s" else ""} needed")
        
        return builder.toString()
    }
    
    /**
     * Get total amount that needs to be settled.
     */
    fun getTotalSettlementAmount(settlements: List<Settlement>): Double {
        return settlements.sumOf { it.amount }
    }
    
    /**
     * Get number of transactions needed.
     */
    fun getTransactionCount(settlements: List<Settlement>): Int {
        return settlements.size
    }
    
    private data class BalanceNode(
        val id: Long,
        val name: String,
        var amount: Double
    )
}
