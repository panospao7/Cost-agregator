package com.yourname.expensetracker.domain.export

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Test

/**
 * DR-08: the Xero, FreshBooks and QuickBooks IIF exporters write the amount the user bears
 * (effective amount), never the full amount, and write nothing for a row the user bears nothing of
 * (not-mine, or effective 0.0). Rows come through the real [toExportTransaction] mapper.
 * Synthetic data only.
 */
class AccountingExportersEffectiveAmountTest {

    private val categories = mapOf(1L to "Dining")
    private val date = 1_773_000_000_000L // fixed instant; only the amount and presence are asserted

    private fun row(
        id: Long,
        merchant: String,
        amount: Double = 90.0,
        notMine: Boolean = false,
        shareAmount: Double? = null
    ): ExportTransaction = Expense(
        id = id,
        amount = amount,
        currency = "EUR",
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = date,
        categoryId = 1L,
        isNotMine = notMine,
        isSharedExpense = shareAmount != null,
        myShareAmount = shareAmount
    ).toExportTransaction()

    private val shared = row(1L, "SharedDinner", shareAmount = 30.0)
    private val notMineRow = row(2L, "NotMineRow", notMine = true)
    private val zeroEffective = row(3L, "ZeroEffective", shareAmount = 0.0)
    private val control = row(4L, "ControlCafe", amount = 12.5)

    private fun dataLines(content: String, headerLines: Int) = content.lines().filter { it.isNotBlank() }.drop(headerLines)

    @Test
    fun `Xero writes effective 30_00 for amount 90 with share 30`() {
        val lines = dataLines(XeroCSVExporter().export(listOf(shared), categories), headerLines = 1)

        assertThat(lines).hasSize(1)
        assertThat(lines.single().split(",")[2]).isEqualTo("30.00")
    }

    @Test
    fun `FreshBooks writes effective 30_00 for amount 90 with share 30`() {
        val lines = dataLines(FreshBooksExporter().export(listOf(shared), categories), headerLines = 1)

        assertThat(lines).hasSize(1)
        assertThat(lines.single().split(",")[2]).isEqualTo("30.00")
    }

    @Test
    fun `QuickBooks writes 30_00 on TRNS and minus 30_00 on SPL for amount 90 with share 30`() {
        val lines = QuickBooksIIFExporter().export(listOf(shared), categories).lines()

        assertThat(lines.single { it.startsWith("TRNS\t") }.split("\t")[3]).isEqualTo("30.00")
        assertThat(lines.single { it.startsWith("SPL\t") }.split("\t")[3]).isEqualTo("-30.00")
    }

    @Test
    fun `Xero FreshBooks and QuickBooks write nothing for not-mine and effective zero rows`() {
        val skipped = listOf(notMineRow, zeroEffective)

        assertThat(dataLines(XeroCSVExporter().export(skipped, categories), headerLines = 1)).isEmpty()
        assertThat(dataLines(FreshBooksExporter().export(skipped, categories), headerLines = 1)).isEmpty()
        val iif = QuickBooksIIFExporter().export(skipped, categories)
        assertThat(iif).doesNotContain("\nTRNS\t")
        assertThat(iif).doesNotContain("\nSPL\t")
        assertThat(iif.lines()).doesNotContain("ENDTRNS")
        assertThat(iif).doesNotContain("NotMineRow")
        assertThat(iif).doesNotContain("ZeroEffective")
    }

    @Test
    fun `skipped rows do not disturb the surrounding rows`() {
        val rows = listOf(control, notMineRow, shared, zeroEffective)

        val xero = dataLines(XeroCSVExporter().export(rows, categories), headerLines = 1)
        val freshBooks = dataLines(FreshBooksExporter().export(rows, categories), headerLines = 1)
        val iif = QuickBooksIIFExporter().export(rows, categories)

        assertThat(xero.map { it.split(",")[2] }).containsExactly("12.50", "30.00").inOrder()
        assertThat(freshBooks.map { it.split(",")[2] }).containsExactly("12.50", "30.00").inOrder()
        assertThat(iif.lines().filter { it.startsWith("TRNS\t") }.map { it.split("\t")[3] })
            .containsExactly("12.50", "30.00").inOrder()
    }

    @Test
    fun `a row with the full amount as its effective amount is written unchanged`() {
        val lines = dataLines(XeroCSVExporter().export(listOf(control), categories), headerLines = 1)

        assertThat(lines.single().split(",")[2]).isEqualTo("12.50")
    }
}
