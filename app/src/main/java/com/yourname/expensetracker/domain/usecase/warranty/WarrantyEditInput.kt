package com.yourname.expensetracker.domain.usecase.warranty

import com.yourname.expensetracker.data.database.entity.WarrantyType

/**
 * The six user-editable warranty content fields (warranty-edit O-1). Deliberately carries no
 * provenance, status or review fields (INV-3).
 *
 * @property purchaseDate any instant on the chosen local purchase day
 * @property durationMonths warranty duration in months (range enforced by [WarrantyEditValidator])
 */
data class WarrantyEditInput(
    val productName: String,
    val merchantName: String,
    val purchaseDate: Long,
    val durationMonths: Int,
    val warrantyType: WarrantyType,
    val supportPhone: String?
) {
    /** Trims names and phone; a blank phone becomes null. Other fields are unchanged. */
    fun normalized(): WarrantyEditInput = copy(
        productName = productName.trim(),
        merchantName = merchantName.trim(),
        supportPhone = supportPhone?.trim()?.takeIf { it.isNotEmpty() }
    )
}
