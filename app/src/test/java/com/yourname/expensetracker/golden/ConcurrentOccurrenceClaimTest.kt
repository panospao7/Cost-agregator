package com.yourname.expensetracker.golden

import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Golden Scenario Test 10: Concurrent Occurrence Claim
 *
 * Verifies that the atomic claim mechanism prevents two expenses
 * from linking to the same recurring occurrence.
 * Uses the conditional UPDATE WHERE status='PLANNED' AND linkedExpenseId IS NULL.
 */
class ConcurrentOccurrenceClaimTest : GoldenTestBase() {

    @Test
    fun `two claims on same occurrence - only one succeeds`() = runTest {
        // Given: A PLANNED occurrence
        val ruleId = database.manualRecurringExpenseDao().insert(ManualRecurringExpense(
            merchant = "Netflix", amount = 15.99, currency = "EUR",
            frequency = "MONTHLY", nextDate = fixedNow + 86400000L * 30,
            isActive = true, createdAt = fixedNow, updatedAt = fixedNow
        ))
        val occId = database.recurringOccurrenceDao().insert(RecurringOccurrence(
            sourceType = "RECURRING_RULE", sourceId = ruleId,
            occurrenceKey = "key1", dueDate = fixedNow + 86400000L * 30,
            expectedAmount = 15.99, expectedCurrency = "EUR",
            status = "PLANNED", createdAt = fixedNow
        ))

        // When: Two expenses try to claim the same occurrence
        val claim1 = database.recurringOccurrenceDao().claimForExpense(occId, 100L, 15.99, "EUR", fixedNow)
        val claim2 = database.recurringOccurrenceDao().claimForExpense(occId, 200L, 15.99, "EUR", fixedNow)

        // Then: Exactly one succeeds
        assertEquals(1, claim1 + claim2)
        // First claim wins (sequential in this test)
        assertEquals(1, claim1)
        assertEquals(0, claim2)

        // And: Occurrence is linked to the first expense only
        val occ = database.recurringOccurrenceDao().getById(occId)
        assertEquals(100L, occ?.linkedExpenseId)
        assertEquals("PAID", occ?.status)
    }

    @Test
    fun `PAID occurrence cannot be claimed`() = runTest {
        val ruleId = database.manualRecurringExpenseDao().insert(ManualRecurringExpense(
            merchant = "Spotify", amount = 9.99, currency = "EUR",
            frequency = "MONTHLY", nextDate = fixedNow + 86400000L * 30,
            isActive = true, createdAt = fixedNow, updatedAt = fixedNow
        ))
        // Insert as already PAID
        val occId = database.recurringOccurrenceDao().insert(RecurringOccurrence(
            sourceType = "RECURRING_RULE", sourceId = ruleId,
            occurrenceKey = "key2", dueDate = fixedNow + 86400000L * 30,
            expectedAmount = 9.99, expectedCurrency = "EUR",
            status = "PAID", linkedExpenseId = 50L,
            createdAt = fixedNow
        ))

        // When: Try to claim a PAID occurrence
        val claimed = database.recurringOccurrenceDao().claimForExpense(occId, 300L, 9.99, "EUR", fixedNow)

        // Then: Claim fails
        assertEquals(0, claimed)

        // And: Original link unchanged
        val occ = database.recurringOccurrenceDao().getById(occId)
        assertEquals(50L, occ?.linkedExpenseId)
    }

    @Test
    fun `CANCELLED occurrence cannot be claimed`() = runTest {
        val ruleId = database.manualRecurringExpenseDao().insert(ManualRecurringExpense(
            merchant = "Gym", amount = 30.0, currency = "EUR",
            frequency = "MONTHLY", nextDate = fixedNow + 86400000L * 30,
            isActive = true, createdAt = fixedNow, updatedAt = fixedNow
        ))
        val occId = database.recurringOccurrenceDao().insert(RecurringOccurrence(
            sourceType = "RECURRING_RULE", sourceId = ruleId,
            occurrenceKey = "key3", dueDate = fixedNow + 86400000L * 30,
            expectedAmount = 30.0, expectedCurrency = "EUR",
            status = "CANCELLED", createdAt = fixedNow
        ))

        // When
        val claimed = database.recurringOccurrenceDao().claimForExpense(occId, 400L, 30.0, "EUR", fixedNow)

        // Then
        assertEquals(0, claimed)
    }
}
