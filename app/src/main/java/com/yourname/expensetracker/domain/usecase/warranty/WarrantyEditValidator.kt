package com.yourname.expensetracker.domain.usecase.warranty

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Bounded validation failure reasons for a warranty edit (INV-4). Safe to log by name. */
enum class WarrantyEditError {
    PRODUCT_BLANK,
    MERCHANT_BLANK,
    NAME_TOO_LONG,
    PHONE_TOO_LONG,
    PURCHASE_DATE_OUT_OF_RANGE,
    DURATION_OUT_OF_RANGE
}

/**
 * Single owner of warranty-edit validation (INV-4), used by both the UI and the repository.
 * The O-11 limits are tunable constants and live here and nowhere else.
 *
 * Pure: "now" is a parameter (INV-17); the purchase-day bounds use the system zone at call time.
 * Checks run on the [WarrantyEditInput.normalized] form, in a fixed order, and report the first
 * failure.
 */
object WarrantyEditValidator {

    /** Maximum trimmed length of product and merchant names. */
    const val MAX_NAME_LENGTH = 120

    /** Maximum trimmed length of the support phone. */
    const val MAX_PHONE_LENGTH = 40

    /** Minimum warranty duration in months. */
    const val MIN_DURATION_MONTHS = 1

    /** Maximum warranty duration in months. */
    const val MAX_DURATION_MONTHS = 120

    /** Earliest allowed purchase day (1970-01-01, avoids the Gregorian-cutover branch). */
    val MIN_PURCHASE_DAY: LocalDate = LocalDate.of(1970, 1, 1)

    /** Latest allowed purchase day, in days after today (pre-orders). */
    const val MAX_PURCHASE_DAYS_AHEAD = 365L

    /** @return the first failure for [input], or null when valid. */
    fun validate(input: WarrantyEditInput, nowMillis: Long): WarrantyEditError? {
        val n = input.normalized()
        if (n.productName.isEmpty()) return WarrantyEditError.PRODUCT_BLANK
        if (n.merchantName.isEmpty()) return WarrantyEditError.MERCHANT_BLANK
        if (n.productName.length > MAX_NAME_LENGTH || n.merchantName.length > MAX_NAME_LENGTH) {
            return WarrantyEditError.NAME_TOO_LONG
        }
        if ((n.supportPhone?.length ?: 0) > MAX_PHONE_LENGTH) {
            return WarrantyEditError.PHONE_TOO_LONG
        }
        if (!isPurchaseDayInRange(n.purchaseDate, nowMillis)) {
            return WarrantyEditError.PURCHASE_DATE_OUT_OF_RANGE
        }
        if (n.durationMonths < MIN_DURATION_MONTHS || n.durationMonths > MAX_DURATION_MONTHS) {
            return WarrantyEditError.DURATION_OUT_OF_RANGE
        }
        return null
    }

    private fun isPurchaseDayInRange(purchaseMillis: Long, nowMillis: Long): Boolean {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(purchaseMillis).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return !day.isBefore(MIN_PURCHASE_DAY) && !day.isAfter(today.plusDays(MAX_PURCHASE_DAYS_AHEAD))
    }
}
