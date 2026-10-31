package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A declared installment plan (loan-installments domain spec section 2.1).
 *
 * Persistence only (schema 152). The sole writer is the installment plan lifecycle
 * coordinator (added in a later slice); the DAO has no callers yet.
 *
 * ## Storage conventions
 * - Enum-like fields (`kind`, `splitMethod`, `frequency`, `residualPlacement`,
 *   `fulfilmentMode`, `status`, `statusReason`) are stored as bounded TEXT codes,
 *   the same way [Expense.source] stores `ExpenseSource`. No type converter.
 * - Money (`declaredTotal`, `fixedPaymentAmount`) is REAL in the plan [currency],
 *   scale 2 (rounding is owned by the schedule policy, not by this entity).
 * - Civil dates (`firstDueDate`, `declaredOnDate`) are INTEGER epochDay.
 *   Timestamps (`createdAt`, `updatedAt`, `terminalAt`) are epoch millis.
 *
 * ## Deliberately NOT foreign keys
 * `purchaseExpenseId` and `categoryId` have no FK. A purchase expense can be
 * deleted while the plan survives (the plan detaches to STANDALONE, spec 7.6); a
 * FK action would either cascade away or null the plan silently. Referential
 * integrity is owned by the plan coordinator and the integrity reconcile (INV-3).
 *
 * `displayName` and `lenderLabel` are user free text and sensitive.
 */
@Entity(
    tableName = "installment_plans",
    indices = [
        Index(value = ["planUuid"], unique = true),
        // INV-3: at most one plan per purchase. Nullable, so STANDALONE plans never conflict.
        Index(value = ["purchaseExpenseId"], unique = true),
        Index(value = ["status"])
    ]
)
data class InstallmentPlan(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Stable identity, unique. Generated once per declare form session (idempotency). */
    val planUuid: String,
    /** PURCHASE_ATTACHED | STANDALONE. */
    val kind: String,
    /** expenses.id of the purchase; null for STANDALONE. Unique when not null (INV-3). No FK. */
    val purchaseExpenseId: Long? = null,
    val displayName: String,
    val lenderLabel: String? = null,
    /** EUR | USD (ISO 4217, scale 2). One currency per plan. */
    val currency: String,
    /** EQUAL_SPLIT | FIXED_PAYMENT. */
    val splitMethod: String,
    val declaredTotal: Double? = null,
    val fixedPaymentAmount: Double? = null,
    val installmentCount: Int,
    /** v1: MONTHLY. */
    val frequency: String,
    /** Civil date, epochDay. */
    val firstDueDate: Long,
    val anchorDayOfMonth: Int,
    /** FIRST | LAST. */
    val residualPlacement: String,
    /** No FK: reassignment is fenced to the plan coordinator. */
    val categoryId: Long? = null,
    val paymentMethod: PaymentMethod = PaymentMethod.UNKNOWN,
    /** AUTO_RECORD | CONFIRM. */
    val fulfilmentMode: String,
    /** Civil date, epochDay. */
    val declaredOnDate: Long,
    /** ACTIVE | COMPLETED | CANCELLED | REFUNDED | PAID_OFF. */
    val status: String,
    /** Bounded code only (e.g. USER_STOP). Never free text. */
    val statusReason: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val terminalAt: Long? = null
)
