package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One refund link: a DEPOSIT expense ([refundExpenseId]) applied against a PURCHASE expense
 * ([purchaseExpenseId]) for [appliedAmount] (refund-netting domain spec section 1.1).
 *
 * Persistence only (schema 153). The sole writer is the transaction lifecycle coordinator
 * (linkRefund, unlinkRefund and the delete cleanup, added in a later slice) through
 * `ExpenseRefundLinkDao`. The link rows are the source of truth for the denormalised
 * `expenses.refundNettedAmount` (INV-1).
 *
 * ## Storage conventions
 * - [appliedAmount]: REAL, major units of [currency], already at the currency's scale.
 * - [currency]: ISO-4217 code, normalised; equal to both rows' currency when the link is made.
 * - [createdAt]: epoch millis.
 * - [source]: bounded `RefundLinkSource` name (TEXT, no converter). v1 has one value,
 *   `USER_CONFIRMED`.
 *
 * ## Constraints
 * - FK `refundExpenseId` -> expenses(id) ON DELETE CASCADE and FK `purchaseExpenseId` ->
 *   expenses(id) ON DELETE CASCADE. CASCADE is only a safety net for the full
 *   `DELETE FROM expenses` wipe; the legal single-row delete removes links explicitly first.
 * - UNIQUE (refundExpenseId, purchaseExpenseId); its leading column also serves as the
 *   `refundExpenseId` FK child index.
 * - Non-unique (purchaseExpenseId) as the FK child index of the purchase side.
 * - The schema allows many-to-many links; the one-purchase-per-refund limit of v1 is
 *   enforced by the coordinator, so lifting it later needs no schema bump.
 */
@Entity(
    tableName = "expense_refund_links",
    foreignKeys = [
        ForeignKey(
            entity = Expense::class,
            parentColumns = ["id"],
            childColumns = ["refundExpenseId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Expense::class,
            parentColumns = ["id"],
            childColumns = ["purchaseExpenseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["refundExpenseId", "purchaseExpenseId"], unique = true),
        Index(value = ["purchaseExpenseId"])
    ]
)
data class ExpenseRefundLink(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** expenses.id of the DEPOSIT row. */
    val refundExpenseId: Long,
    /** expenses.id of the PURCHASE row. */
    val purchaseExpenseId: Long,
    /** Major units of [currency], at the currency's scale. At least one minor unit. */
    val appliedAmount: Double,
    /** Normalised ISO-4217 code. */
    val currency: String,
    /** Epoch millis. */
    val createdAt: Long,
    /** Bounded RefundLinkSource name. Never free text. */
    val source: String
)
