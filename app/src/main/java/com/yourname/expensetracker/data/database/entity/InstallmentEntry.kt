package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One scheduled payment of an [InstallmentPlan], plus at most one PAYOFF row
 * (loan-installments domain spec section 2.2).
 *
 * Persistence only (schema 152). The sole writer is the installment plan lifecycle
 * coordinator (added in a later slice).
 *
 * ## Storage conventions
 * Enum-like fields (`kind`, `status`, `statusReason`, `linkOrigin`, `adjustReason`)
 * are bounded TEXT codes (no converter). `scheduledAmount` and
 * `originalScheduledAmount` are REAL in the plan currency, scale 2. `dueDate` is
 * INTEGER epochDay. `statusChangedAt` / `adjustedAt` are epoch millis.
 *
 * ## Constraints
 * - FK `planId` -> installment_plans(id) ON DELETE CASCADE.
 * - Unique (planId, kind, seq). The "at most one PAYOFF per plan" rule cannot be
 *   expressed as a plain unique index here and is owned by the plan coordinator.
 * - INV-4: unique nullable `linkedExpenseId` (one entry per expense). Deliberately
 *   NOT a FK: deleting an expense must not cascade into or null a payment record.
 *   The reconcile (spec section 5.4) owns the PAID -> SCHEDULED reopen.
 */
@Entity(
    tableName = "installment_entries",
    foreignKeys = [
        ForeignKey(
            entity = InstallmentPlan::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        // Leading column planId also serves as the FK child index.
        Index(value = ["planId", "kind", "seq"], unique = true),
        Index(value = ["linkedExpenseId"], unique = true),
        Index(value = ["dueDate"]),
        Index(value = ["status"])
    ]
)
data class InstallmentEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val planId: Long,
    /** 1..N for REGULAR; extensions continue at N+1. */
    val seq: Int,
    /** REGULAR | PAYOFF. */
    val kind: String,
    /** Civil date, epochDay. PAYOFF: the payoff date. */
    val dueDate: Long,
    val scheduledAmount: Double,
    /** Set on the first manual adjust (audit). */
    val originalScheduledAmount: Double? = null,
    /** SCHEDULED | PAID | SKIPPED | CANCELLED | REVERSED | PAID_BEFORE_TRACKING. */
    val status: String,
    /** Bounded code only. Never free text. */
    val statusReason: String? = null,
    /** expenses.id. Unique when not null (INV-4). Null when PAID_BEFORE_TRACKING. No FK. */
    val linkedExpenseId: Long? = null,
    /** AUTO_RECORDED | USER_CONFIRMED | USER_LINKED; set together with linkedExpenseId. */
    val linkOrigin: String? = null,
    /** True once auto-record has created an expense for this entry. Never resets (INV-12). */
    val autoRecordConsumed: Boolean = false,
    /** +1 every time the entry returns to SCHEDULED from PAID. Part of the expense idempotency key. */
    val recordGeneration: Int = 0,
    val adjustReason: String? = null,
    val statusChangedAt: Long,
    val adjustedAt: Long? = null
)
