package com.yourname.expensetracker.ui.screens.review

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * J-05-1: the review edit dialog submits only the transfer direction and account the user changed
 * from what the dialog was seeded with, and only while TRANSFER is the selected type. An unedited
 * row submits null/null so bulk approve leaves every other row on its own suggestion.
 * Synthetic values only.
 */
class ReviewScreenTransferSubmitTest {

    private val seedAccount = "Synthetic Savings"

    private fun submit(
        type: TransactionType,
        direction: TransferDirection?,
        account: String,
        seededDirection: TransferDirection?,
        seededAccount: String
    ): ReviewTransferSubmit = resolveTransferSubmit(
        selectedType = type,
        transferDirection = direction,
        transferAccount = account,
        seededDirection = seededDirection,
        seededAccount = seededAccount
    )

    @Test
    fun `unedited TRANSFER submits null direction and null account`() {
        val result = submit(
            TransactionType.TRANSFER,
            TransferDirection.OUTGOING, seedAccount,
            seededDirection = TransferDirection.OUTGOING, seededAccount = seedAccount
        )

        assertNull(result.direction)
        assertNull(result.accountName)
    }

    @Test
    fun `unedited TRANSFER without any seed submits null direction and null account`() {
        val result = submit(
            TransactionType.TRANSFER,
            null, "",
            seededDirection = null, seededAccount = ""
        )

        assertNull(result.direction)
        assertNull(result.accountName)
    }

    @Test
    fun `direction-only edit submits only the direction`() {
        val result = submit(
            TransactionType.TRANSFER,
            TransferDirection.INCOMING, seedAccount,
            seededDirection = TransferDirection.OUTGOING, seededAccount = seedAccount
        )

        assertEquals(TransferDirection.INCOMING, result.direction)
        assertNull(result.accountName)
    }

    @Test
    fun `direction-only edit is submitted for each direction pair`() {
        TransferDirection.entries.forEach { seeded ->
            TransferDirection.entries.filter { it != seeded }.forEach { chosen ->
                val result = submit(
                    TransactionType.TRANSFER,
                    chosen, seedAccount,
                    seededDirection = seeded, seededAccount = seedAccount
                )

                assertEquals("seed=$seeded chosen=$chosen", chosen, result.direction)
                assertNull("seed=$seeded chosen=$chosen", result.accountName)
            }
        }
    }

    @Test
    fun `account-only edit submits only the account`() {
        val result = submit(
            TransactionType.TRANSFER,
            TransferDirection.OUTGOING, "Synthetic Edited Account",
            seededDirection = TransferDirection.OUTGOING, seededAccount = seedAccount
        )

        assertNull(result.direction)
        assertEquals("Synthetic Edited Account", result.accountName)
    }

    @Test
    fun `direction and account both edited submits both`() {
        val result = submit(
            TransactionType.TRANSFER,
            TransferDirection.INCOMING, "Synthetic Edited Account",
            seededDirection = TransferDirection.OUTGOING, seededAccount = seedAccount
        )

        assertEquals(TransferDirection.INCOMING, result.direction)
        assertEquals("Synthetic Edited Account", result.accountName)
    }

    @Test
    fun `account cleared to blank submits null account`() {
        listOf("", " ", "   ", "\t", " \n ").forEach { blank ->
            val result = submit(
                TransactionType.TRANSFER,
                TransferDirection.OUTGOING, blank,
                seededDirection = TransferDirection.OUTGOING, seededAccount = seedAccount
            )

            assertNull("account=<$blank>", result.accountName)
            assertNull("account=<$blank>", result.direction)
        }
    }

    @Test
    fun `blank account does not suppress an edited direction`() {
        val result = submit(
            TransactionType.TRANSFER,
            TransferDirection.INCOMING, "",
            seededDirection = TransferDirection.OUTGOING, seededAccount = seedAccount
        )

        assertEquals(TransferDirection.INCOMING, result.direction)
        assertNull(result.accountName)
    }

    @Test
    fun `account typed back equal to the seed submits null account`() {
        // The user edits, then restores the seeded text: that is no change, so nothing is submitted.
        val result = submit(
            TransactionType.TRANSFER,
            TransferDirection.OUTGOING, "Synthetic Savings",
            seededDirection = TransferDirection.OUTGOING, seededAccount = "Synthetic Savings"
        )

        assertNull(result.accountName)
    }

    @Test
    fun `account that differs from the seed only by case is a real edit`() {
        val result = submit(
            TransactionType.TRANSFER,
            TransferDirection.OUTGOING, "synthetic savings",
            seededDirection = TransferDirection.OUTGOING, seededAccount = "Synthetic Savings"
        )

        assertEquals("synthetic savings", result.accountName)
    }

    @Test
    fun `direction chosen back to the seed submits null direction`() {
        val result = submit(
            TransactionType.TRANSFER,
            TransferDirection.INCOMING, "Synthetic Edited Account",
            seededDirection = TransferDirection.INCOMING, seededAccount = seedAccount
        )

        assertNull(result.direction)
        assertEquals("Synthetic Edited Account", result.accountName)
    }

    @Test
    fun `seeded direction null with a chosen direction submits the chosen direction`() {
        TransferDirection.entries.forEach { chosen ->
            val result = submit(
                TransactionType.TRANSFER,
                chosen, "",
                seededDirection = null, seededAccount = ""
            )

            assertEquals(chosen, result.direction)
            assertNull(result.accountName)
        }
    }

    @Test
    fun `seeded direction null with a chosen direction and a typed account submits both`() {
        val result = submit(
            TransactionType.TRANSFER,
            TransferDirection.OUTGOING, "Synthetic Typed Account",
            seededDirection = null, seededAccount = ""
        )

        assertEquals(TransferDirection.OUTGOING, result.direction)
        assertEquals("Synthetic Typed Account", result.accountName)
    }

    @Test
    fun `non TRANSFER types submit null and null even when state differs from the seed`() {
        val nonTransfer = listOf(
            TransactionType.DEPOSIT,
            TransactionType.PURCHASE,
            TransactionType.WITHDRAWAL,
            TransactionType.UNKNOWN
        )
        nonTransfer.forEach { type ->
            TransferDirection.entries.forEach { chosen ->
                val seeded = TransferDirection.entries.first { it != chosen }
                val result = submit(
                    type,
                    chosen, "Synthetic Edited Account",
                    seededDirection = seeded, seededAccount = seedAccount
                )

                assertNull("type=$type chosen=$chosen direction", result.direction)
                assertNull("type=$type chosen=$chosen account", result.accountName)
            }
        }
    }

    @Test
    fun `DEPOSIT with edited state and a null seed submits null and null`() {
        val result = submit(
            TransactionType.DEPOSIT,
            TransferDirection.INCOMING, "Synthetic Payroll",
            seededDirection = null, seededAccount = ""
        )

        assertNull(result.direction)
        assertNull(result.accountName)
    }

    @Test
    fun `switching away from TRANSFER drops edited transfer values`() {
        val edited = submit(
            TransactionType.TRANSFER,
            TransferDirection.INCOMING, "Synthetic Edited",
            seededDirection = TransferDirection.OUTGOING, seededAccount = seedAccount
        )
        assertEquals(TransferDirection.INCOMING, edited.direction)
        assertEquals("Synthetic Edited", edited.accountName)

        val switched = submit(
            TransactionType.PURCHASE,
            TransferDirection.INCOMING, "Synthetic Edited",
            seededDirection = TransferDirection.OUTGOING, seededAccount = seedAccount
        )
        assertNull(switched.direction)
        assertNull(switched.accountName)
    }

    @Test
    fun `J-05-1 unedited seeded rows never submit their own seed so bulk approve cannot overwrite peers`() {
        // The previous implementation returned the dialog state for TRANSFER/DEPOSIT, so an untouched
        // OUTGOING/"Synthetic Savings" row submitted exactly those values. Every such row must now
        // submit null/null, whatever the seed is.
        val seeds = listOf(
            TransferDirection.OUTGOING to "Synthetic Savings",
            TransferDirection.INCOMING to "Synthetic Main"
        )
        seeds.forEach { (direction, account) ->
            val result = submit(
                TransactionType.TRANSFER,
                direction, account,
                seededDirection = direction, seededAccount = account
            )

            assertNull("seed=$direction/$account direction", result.direction)
            assertNull("seed=$direction/$account account", result.accountName)
        }
    }

    // Call-site contract (the dialog seeds from review.suggestedDirection / suggestedAccountName):
    // the Compose dialog itself needs a Compose UI harness, so only the pure parse-then-resolve
    // composition is pinned here.

    @Test
    fun `call-site seed parsing then resolve gives null and null for an unedited row`() {
        val suggestedDirection: String? = "OUTGOING"
        val suggestedAccountName: String? = "Synthetic Savings"
        val seededDirection = parseTransferDirectionOrNull(suggestedDirection)
        val seededAccount = suggestedAccountName ?: ""

        val result = resolveTransferSubmit(
            TransactionType.TRANSFER,
            seededDirection,
            seededAccount,
            seededDirection = parseTransferDirectionOrNull(suggestedDirection),
            seededAccount = suggestedAccountName ?: ""
        )

        assertEquals(TransferDirection.OUTGOING, seededDirection)
        assertNull(result.direction)
        assertNull(result.accountName)
    }

    @Test
    fun `call-site with unparseable or missing suggested direction treats the seed as null`() {
        assertNull(parseTransferDirectionOrNull(null))
        assertNull(parseTransferDirectionOrNull(""))
        assertNull(parseTransferDirectionOrNull("SIDEWAYS"))

        val result = resolveTransferSubmit(
            TransactionType.TRANSFER,
            TransferDirection.INCOMING,
            "",
            seededDirection = parseTransferDirectionOrNull("SIDEWAYS"),
            seededAccount = (null as String?) ?: ""
        )

        assertEquals(TransferDirection.INCOMING, result.direction)
        assertNull(result.accountName)
    }
}
