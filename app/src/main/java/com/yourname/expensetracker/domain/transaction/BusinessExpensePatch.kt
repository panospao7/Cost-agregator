package com.yourname.expensetracker.domain.transaction

data class BusinessExpensePatch(
    val isBusinessExpense: Boolean? = null,
    val requiresReceipt: Boolean? = null,
    val businessPurpose: String? = null,
    val businessCategory: String? = null,
    val businessProject: String? = null,

    // Legacy unsupported fields. These must not be silently ignored.
    val businessUsePercent: Double? = null,
    val taxCategory: String? = null,
    val vatEligible: Boolean? = null
) {
    fun unsupportedFields(): List<String> = buildList {
        if (businessUsePercent != null) add("businessUsePercent")
        if (taxCategory != null) add("taxCategory")
        if (vatEligible != null) add("vatEligible")
    }

    fun isEmpty(): Boolean =
        isBusinessExpense == null &&
            requiresReceipt == null &&
            businessPurpose == null &&
            businessCategory == null &&
            businessProject == null &&
            businessUsePercent == null &&
            taxCategory == null &&
            vatEligible == null

    fun hasOnlyUnsupportedFields(): Boolean =
        unsupportedFields().isNotEmpty() &&
            isBusinessExpense == null &&
            requiresReceipt == null &&
            businessPurpose == null &&
            businessCategory == null &&
            businessProject == null
}
