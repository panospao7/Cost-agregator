package com.yourname.expensetracker.domain.location

/**
 * Lightweight domain representation of an expense that has been resolved to
 * geographic coordinates.  Used by [SpendingHeatmapEngine] and
 * [LocationInsightsEngine] so that the domain layer has no dependency on
 * the UI layer's [com.yourname.expensetracker.ui.screens.map.MapExpenseMarker].
 *
 * The ViewModel is responsible for mapping [MapExpenseMarker] → [LocatedExpense]
 * before passing data down to the engines.
 */
data class LocatedExpense(
    val expenseId: Long,
    val latitude: Double,
    val longitude: Double,
    val amount: Double,
    val merchant: String,
    val date: Long,
    val locationSource: String?,
    val placeId: String?
)
