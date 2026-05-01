package com.yourname.expensetracker.domain.transaction

import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection

/**
 * Patch-style update request for modifying an existing expense's mutable fields.
 *
 * Only non-null fields (for object types) or non-default primitives should be applied.
 * For nullable fields, use a sentinel pattern or the actor/reason fields are always required.
 *
 * @property actor Who is performing this update (e.g. "user", "system:backfill").
 * @property reason Optional human-readable reason for the update.
 * @property categoryId New category ID, or null to keep current.
 * @property merchant New merchant name, or null to keep current.
 * @property notes New notes, or null to keep current.
 * @property paymentMethod New payment method, or null to keep current.
 * @property transactionType New transaction type, or null to keep current.
 * @property transferDirection New transfer direction, or null to keep current.
 * @property transferAccountName New transfer account name, or null to keep current.
 * @property isNotMine New isNotMine flag.
 * @property ownerName New owner name, or null to keep current.
 * @property isSharedExpense New isSharedExpense flag.
 * @property sharedWithName New shared-with name, or null to keep current.
 * @property mySharePercentage New share percentage, or null to keep current.
 * @property myShareAmount New share amount, or null to keep current.
 * @property latitude New latitude, or null to keep current.
 * @property longitude New longitude, or null to keep current.
 * @property locationSource New location source, or null to keep current.
 * @property placeId New place ID, or null to keep current.
 * @property resolvedAddress New resolved address, or null to keep current.
 * @property isBusinessExpense New isBusinessExpense flag.
 * @property businessPurpose New business purpose, or null to keep current.
 * @property requiresReceipt New requiresReceipt flag.
 */
data class ExpenseUpdates(
    val actor: String,
    val reason: String? = null,
    val categoryId: Long? = null,
    val merchant: String? = null,
    val notes: String? = null,
    val paymentMethod: PaymentMethod? = null,
    val transactionType: TransactionType? = null,
    val transferDirection: TransferDirection? = null,
    val transferAccountName: String? = null,
    val isNotMine: Boolean? = null,
    val ownerName: String? = null,
    val isSharedExpense: Boolean? = null,
    val sharedWithName: String? = null,
    val mySharePercentage: Int? = null,
    val myShareAmount: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationSource: String? = null,
    val placeId: String? = null,
    val resolvedAddress: String? = null,
    val isBusinessExpense: Boolean? = null,
    val businessPurpose: String? = null,
    val requiresReceipt: Boolean? = null
)
