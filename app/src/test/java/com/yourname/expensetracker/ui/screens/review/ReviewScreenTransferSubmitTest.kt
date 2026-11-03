package com.yourname.expensetracker.ui.screens.review

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * J-05-1: the review edit dialog submits transfer direction and account for the type the user has
 * selected now, not only when the type differs from the suggestion. Synthetic values only.
 */
class ReviewScreenTransferSubmitTest {

    private fun assertNothingSubmitted(type: TransactionType, direction: TransferDirection?, account: String) {
        val submit = resolveTransferSubmit(type, direction, account)
        assertNull("type=$type direction=$direction", submit.direction)
        assertNull("type=$type account=<$account>", submit.accountName)
    }

    @Test
    fun `TRANSFER with direction and account submits both`() {
        val submit = resolveTransferSubmit(TransactionType.TRANSFER, TransferDirection.OUTGOING, "Synthetic Savings")

        assertEquals(TransferDirection.OUTGOING, submit.direction)
        assertEquals("Synthetic Savings", submit.accountName)
    }

    @Test
    fun `TRANSFER submits each direction as given`() {
        TransferDirection.entries.forEach { direction ->
            val submit = resolveTransferSubmit(TransactionType.TRANSFER, direction, "Synthetic Savings")
            assertEquals(direction, submit.direction)
        }
    }

    @Test
    fun `DEPOSIT behaves as TRANSFER and submits both`() {
        val submit = resolveTransferSubmit(TransactionType.DEPOSIT, TransferDirection.INCOMING, "Synthetic Payroll")

        assertEquals(TransferDirection.INCOMING, submit.direction)
        assertEquals("Synthetic Payroll", submit.accountName)
    }

    @Test
    fun `DEPOSIT with blank account and null direction submits null for both`() {
        val submit = resolveTransferSubmit(TransactionType.DEPOSIT, null, "")

        assertNull(submit.direction)
        assertNull(submit.accountName)
    }

    @Test
    fun `blank account is submitted as null so the repository falls back to the suggestion`() {
        listOf("", " ", "   ", "\t", " \n ").forEach { blank ->
            val submit = resolveTransferSubmit(TransactionType.TRANSFER, TransferDirection.OUTGOING, blank)

            assertNull("account=<$blank>", submit.accountName)
            // The direction is independent of the account and is still submitted.
            assertEquals(TransferDirection.OUTGOING, submit.direction)
        }
    }

    @Test
    fun `account with surrounding text is not treated as blank`() {
        val submit = resolveTransferSubmit(TransactionType.TRANSFER, TransferDirection.OUTGOING, "x")

        assertEquals("x", submit.accountName)
    }

    @Test
    fun `TRANSFER with a null direction submits a null direction and keeps the account`() {
        val submit = resolveTransferSubmit(TransactionType.TRANSFER, null, "Synthetic Savings")

        assertNull(submit.direction)
        assertEquals("Synthetic Savings", submit.accountName)
    }

    @Test
    fun `PURCHASE submits null and null even with direction and account set`() {
        assertNothingSubmitted(TransactionType.PURCHASE, TransferDirection.OUTGOING, "Synthetic Savings")
    }

    @Test
    fun `WITHDRAWAL submits null and null even with direction and account set`() {
        assertNothingSubmitted(TransactionType.WITHDRAWAL, TransferDirection.INCOMING, "Synthetic Savings")
    }

    @Test
    fun `UNKNOWN submits null and null even with direction and account set`() {
        assertNothingSubmitted(TransactionType.UNKNOWN, TransferDirection.OUTGOING, "Synthetic Savings")
    }

    @Test
    fun `J-05-1 edited direction with the type unchanged is submitted`() {
        // The old gate compared the selected type with the suggested type and dropped the edit when they
        // matched. The helper takes no suggested type, so an unchanged TRANSFER row must still submit an
        // edited direction (suggestion OUTGOING, user switched to INCOMING).
        val suggestedType = TransactionType.TRANSFER
        val selectedType = suggestedType

        val submit = resolveTransferSubmit(selectedType, TransferDirection.INCOMING, "Synthetic Savings")

        assertEquals(TransferDirection.INCOMING, submit.direction)
    }

    @Test
    fun `J-05-1 edited account with the type unchanged is submitted`() {
        val suggestedType = TransactionType.DEPOSIT
        val selectedType = suggestedType

        val submit = resolveTransferSubmit(selectedType, TransferDirection.INCOMING, "Synthetic Edited Account")

        assertEquals("Synthetic Edited Account", submit.accountName)
    }

    @Test
    fun `switching to a non transfer type drops previously edited transfer values`() {
        // Control for the finding: the metadata only travels with TRANSFER and DEPOSIT.
        val edited = resolveTransferSubmit(TransactionType.TRANSFER, TransferDirection.INCOMING, "Synthetic Edited")
        assertEquals(TransferDirection.INCOMING, edited.direction)
        assertEquals("Synthetic Edited", edited.accountName)

        assertNothingSubmitted(TransactionType.PURCHASE, TransferDirection.INCOMING, "Synthetic Edited")
    }
}
