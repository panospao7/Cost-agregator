package com.yourname.expensetracker.domain.recurring

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentEntryLinkReader
import com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander.OccurrenceCandidate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * F-AE-7 (owner item 37(b)): [RecurringLinkTypeEligibility] is the single layer-1 rule. The table
 * pins both forms over every [TransactionType] x isNotMine, drives [OccurrenceConflictResolver] (a
 * real call site of [RecurringLinkTypeEligibility.mayCreateLink]) against the table, and pins by a
 * source scan that neither the resolver nor the coordinator keeps an inline type rule. The
 * coordinator gate itself is exercised with real Room in
 * RecurringLifecycleCoordinatorInstallmentLinkPolicyTest.
 */
class RecurringLinkTypeEligibilityParityTest {

    private val createRefused = setOf(
        TransactionType.TRANSFER, TransactionType.DEPOSIT, TransactionType.UNKNOWN
    )
    private val keepRefused = setOf(TransactionType.TRANSFER, TransactionType.DEPOSIT)

    @Test
    fun mayCreateLinkTableOverEveryTypeAndOwnership() {
        for (type in TransactionType.entries) {
            for (isNotMine in listOf(false, true)) {
                val expected = !isNotMine && type !in createRefused
                assertEquals(
                    "mayCreateLink($type, isNotMine=$isNotMine)",
                    expected,
                    RecurringLinkTypeEligibility.mayCreateLink(type, isNotMine)
                )
            }
        }
    }

    @Test
    fun mayKeepLinkTableOverEveryTypeAndOwnership() {
        for (type in TransactionType.entries) {
            for (isNotMine in listOf(false, true)) {
                val expected = !isNotMine && type !in keepRefused
                assertEquals(
                    "mayKeepLink($type, isNotMine=$isNotMine)",
                    expected,
                    RecurringLinkTypeEligibility.mayKeepLink(type, isNotMine)
                )
            }
        }
    }

    @Test
    fun keepIsASupersetOfCreateAndTheOnlyDifferenceIsUnknown() {
        for (type in TransactionType.entries) {
            for (isNotMine in listOf(false, true)) {
                val create = RecurringLinkTypeEligibility.mayCreateLink(type, isNotMine)
                val keep = RecurringLinkTypeEligibility.mayKeepLink(type, isNotMine)
                if (create) assertTrue("create implies keep for $type/$isNotMine", keep)
                if (keep && !create) {
                    assertEquals(TransactionType.UNKNOWN, type)
                    assertFalse(isNotMine)
                }
            }
        }
    }

    private class EmptyReader : InstallmentEntryLinkReader {
        override suspend fun linkedEntryIdFor(expenseId: Long): Long? = null
        override suspend fun isPlanPurchase(expenseId: Long): Boolean = false
        override suspend fun linkedExpenseIds(): Set<Long> = emptySet()
        override suspend fun planPurchaseExpenseIds(): Set<Long> = emptySet()
    }

    @Test
    fun resolverAgreesWithMayCreateLinkForEveryTypeAndOwnership() = runTest(timeout = 60.seconds) {
        val day = 1_700_000_000_000L
        val candidate = OccurrenceCandidate(
            occurrenceKey = "RECURRING_RULE|1|$day|MONTHLY",
            dueDate = day,
            expectedAmount = 15.99,
            expectedCurrency = "EUR",
            frequency = "MONTHLY",
            merchant = "Netflix",
            categoryId = null,
            sourceType = "RECURRING_RULE",
            sourceId = 1L
        )
        val resolver = OccurrenceConflictResolver(RecurringInstallmentEligibility(dagger.Lazy { EmptyReader() }))
        for (type in TransactionType.entries) {
            for (isNotMine in listOf(false, true)) {
                val expense = Expense(
                    id = 1L,
                    amount = 15.99,
                    currency = "EUR",
                    merchant = "Netflix",
                    transactionType = type,
                    date = day,
                    createdAt = day,
                    source = "test",
                    isNotMine = isNotMine
                )
                val paid = resolver.resolve(listOf(candidate), listOf(expense)).single().status == "PAID"
                assertEquals(
                    "resolver vs mayCreateLink for $type/isNotMine=$isNotMine",
                    RecurringLinkTypeEligibility.mayCreateLink(type, isNotMine),
                    paid
                )
            }
        }
    }

    @Test
    fun noInlineLayerOneTypeRuleRemainsInTheResolverOrTheCoordinator() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/java/com/yourname/expensetracker/domain/recurring") }
            .first { it.isDirectory }
        val files = listOf(
            File(root, "OccurrenceConflictResolver.kt"),
            File(root, "lifecycle/RecurringLifecycleCoordinator.kt")
        )
        for (file in files) {
            assertTrue("${file.name} must exist", file.isFile)
            val text = file.readText()
            assertFalse("${file.name} must not compare TransactionType.TRANSFER inline",
                text.contains("TransactionType.TRANSFER"))
            assertFalse("${file.name} must not compare TransactionType.DEPOSIT inline",
                text.contains("TransactionType.DEPOSIT"))
            assertFalse("${file.name} must not compare TransactionType.UNKNOWN inline",
                text.contains("TransactionType.UNKNOWN"))
            assertTrue("${file.name} must call the owner", text.contains("RecurringLinkTypeEligibility."))
        }
        val coordinator = files[1].readText()
        assertTrue(coordinator.contains("RecurringLinkTypeEligibility.mayCreateLink"))
        assertTrue(coordinator.contains("RecurringLinkTypeEligibility.mayKeepLink"))
    }
}
