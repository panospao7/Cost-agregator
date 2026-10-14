package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Durable consumption of a monthly sweep opportunity, independent of goals.
 * No goal FK or cleanup writer: deleting goals must not reopen their month.
 * Contains only period bounds, a timestamp and a controlled reason, not money.
 */
@Entity(tableName = "savings_sweep_month_acceptance")
data class SavingsSweepMonthAcceptance(
    @PrimaryKey val monthStart: Long,
    val monthEndExclusive: Long,
    val actionedAt: Long,
    val reasonCode: String
) {
    companion object {
        const val ACCEPTED = "SAVINGS_SWEEP_MONTH_ACCEPTED"
        const val LEGACY_HISTORY_UNKNOWN = "SAVINGS_SWEEP_LEGACY_MONTH_UNKNOWN"
    }
}
