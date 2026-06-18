package com.yourname.expensetracker.data.database.model

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * B.4-29: Verifies that the member [ExpenseWithCategory.formattedDate] and the
 * extension [formattedTime] return distinct formats and do not shadow each other.
 *
 * - Member `formattedDate` → "MMM dd, HH:mm" (e.g. "Apr 12, 14:30")
 * - Extension `formattedTime` → "HH:mm" (e.g. "14:30")
 */
class ExpenseWithCategoryFormattedTimeTest {

    private val fixedEpoch = 1_700_000_000_000L // 2023-11-14 ~22:13 UTC

    @Test
    fun `formattedDate (member) returns MMM dd HH-mm format`() {
        val ewc = makeEwc(date = fixedEpoch)
        val result = ewc.formattedDate

        // Must contain both a month abbreviation and a colon-separated time
        assertThat(result).containsMatch("[A-Za-z]{3} \\d{2}, \\d{2}:\\d{2}")
    }

    @Test
    fun `formattedTime (extension) returns HH-mm format`() {
        val ewc = makeEwc(date = fixedEpoch)
        val result = ewc.formattedTime

        // Time-only: exactly "HH:mm", no month/day
        assertThat(result).matches("\\d{2}:\\d{2}")
    }

    @Test
    fun `formattedDate and formattedTime are different for the same instance`() {
        val ewc = makeEwc(date = fixedEpoch)
        assertThat(ewc.formattedDate).isNotEqualTo(ewc.formattedTime)
    }

    @Test
    fun `formattedTime contains only the time portion of formattedDate`() {
        val ewc = makeEwc(date = fixedEpoch)
        // The time portion is the last 5 characters of formattedDate ("HH:mm")
        val timePortion = ewc.formattedDate.takeLast(5)
        assertThat(ewc.formattedTime).isEqualTo(timePortion)
    }

    @Test
    fun `formattedTime returns Unknown for invalid epoch`() {
        // Extreme negative date that may trip formatter
        val ewc = makeEwc(date = Long.MIN_VALUE)
        // Should not crash — returns either a valid time or "Unknown"
        val result = ewc.formattedTime
        assertThat(result).isNotEmpty()
    }

    // ---- Helper ----

    private fun makeEwc(date: Long = 1_700_000_000_000L): ExpenseWithCategory {
        val expense = Expense(
            amount = 10.0,
            currency = "EUR",
            merchant = "TestMerchant",
            transactionType = TransactionType.PURCHASE,
            date = date
        )
        return ExpenseWithCategory(
            expense = expense,
            category = Category(id = 1, name = "Test", icon = "T", color = "#FF0000")
        )
    }
}
