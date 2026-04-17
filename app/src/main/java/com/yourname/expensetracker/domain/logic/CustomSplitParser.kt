package com.yourname.expensetracker.domain.logic

import java.math.BigDecimal

enum class CustomSplitMode {
    EQUAL,
    CUSTOM_AMOUNT,
    CUSTOM_PERCENT,
    UNEQUAL
}

sealed class CustomSplitParseResult {
    data class Valid(val splits: Map<Long, Double>) : CustomSplitParseResult()
    data class Invalid(
        val reason: String,
        val parsedSplits: Map<Long, Double> = emptyMap()
    ) : CustomSplitParseResult()
}

/**
 * Strict parser/validator for custom split payloads.
 *
 * Payload format: "memberId:value,memberId:value"
 */
object CustomSplitParser {
    private const val MINOR_UNIT_SCALE = 2
    private const val HUNDRED_PERCENT_IN_BASIS_POINTS = 10_000L

    fun parseAndValidate(
        splitsString: String?,
        splitType: CustomSplitMode,
        totalAmount: Double,
        groupMemberIds: Set<Long>
    ): CustomSplitParseResult {
        if (splitType == CustomSplitMode.EQUAL) {
            return CustomSplitParseResult.Invalid("EQUAL split type does not require custom splits")
        }

        if (splitsString.isNullOrBlank()) {
            return CustomSplitParseResult.Invalid("Custom split payload is missing")
        }

        if (groupMemberIds.isEmpty()) {
            return CustomSplitParseResult.Invalid("Cannot validate custom splits without group members")
        }

        if (!totalAmount.isFinite()) {
            return CustomSplitParseResult.Invalid("Total amount must be finite")
        }

        val expectedTotalMinorUnits = when (splitType) {
            CustomSplitMode.CUSTOM_AMOUNT,
            CustomSplitMode.UNEQUAL -> totalAmount.toMinorUnitsOrNull()
                ?: return CustomSplitParseResult.Invalid("Total amount must be representable in cents")

            CustomSplitMode.CUSTOM_PERCENT,
            CustomSplitMode.EQUAL -> null
        }

        val parsed = linkedMapOf<Long, Double>()
        val parsedMinorUnits = linkedMapOf<Long, Long>()
        val tokens = splitsString.split(',')
        for (token in tokens) {
            val pair = token.trim()
            if (pair.isBlank()) {
                return CustomSplitParseResult.Invalid(
                    reason = "Malformed custom split entry",
                    parsedSplits = parsed
                )
            }

            val parts = pair.split(':', limit = 2)
            if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                return CustomSplitParseResult.Invalid(
                    reason = "Malformed custom split pair: $pair",
                    parsedSplits = parsed
                )
            }

            val memberId = parts[0].trim().toLongOrNull()
                ?: return CustomSplitParseResult.Invalid(
                    reason = "Invalid memberId in split pair: $pair",
                    parsedSplits = parsed
                )

            val rawValue = parts[1].trim()
            val preciseValue = rawValue.toBigDecimalOrNull()
                ?: return CustomSplitParseResult.Invalid(
                    reason = "Invalid split value in split pair: $pair",
                    parsedSplits = parsed
                )

            val value = preciseValue.toDouble()

            if (!value.isFinite()) {
                return CustomSplitParseResult.Invalid(
                    reason = "Non-finite split value for memberId=$memberId",
                    parsedSplits = parsed
                )
            }

            if (memberId !in groupMemberIds) {
                return CustomSplitParseResult.Invalid(
                    reason = "Split references unknown memberId=$memberId",
                    parsedSplits = parsed
                )
            }

            if (parsed.containsKey(memberId)) {
                return CustomSplitParseResult.Invalid(
                    reason = "Duplicate split entry for memberId=$memberId",
                    parsedSplits = parsed
                )
            }

            if (value < 0.0) {
                return CustomSplitParseResult.Invalid(
                    reason = "Negative split value for memberId=$memberId",
                    parsedSplits = parsed
                )
            }

            val minorUnits = when (splitType) {
                CustomSplitMode.CUSTOM_PERCENT -> preciseValue.toMinorUnitsOrNull()
                    ?: return CustomSplitParseResult.Invalid(
                        reason = "Split percentage for memberId=$memberId must be representable in basis points",
                        parsedSplits = parsed
                    )

                CustomSplitMode.CUSTOM_AMOUNT,
                CustomSplitMode.UNEQUAL -> preciseValue.toMinorUnitsOrNull()
                    ?: return CustomSplitParseResult.Invalid(
                        reason = "Split amount for memberId=$memberId must not contain fractional cents",
                        parsedSplits = parsed
                    )

                CustomSplitMode.EQUAL -> null
            }

            parsed[memberId] = value
            minorUnits?.let { parsedMinorUnits[memberId] = it }
        }

        if (parsed.keys != groupMemberIds) {
            return CustomSplitParseResult.Invalid(
                reason = "Custom split entries must include every member exactly once",
                parsedSplits = parsed
            )
        }

        return when (splitType) {
            CustomSplitMode.CUSTOM_PERCENT -> {
                val percentTotal = parsedMinorUnits.values.sum()
                if (percentTotal != HUNDRED_PERCENT_IN_BASIS_POINTS) {
                    CustomSplitParseResult.Invalid(
                        reason = "Custom percentages must sum to 100.00 (actual=${percentTotal.toDisplayAmount()})",
                        parsedSplits = parsed
                    )
                } else {
                    CustomSplitParseResult.Valid(parsed)
                }
            }

            CustomSplitMode.CUSTOM_AMOUNT,
            CustomSplitMode.UNEQUAL -> {
                val splitTotal = parsedMinorUnits.values.sum()
                if (splitTotal != expectedTotalMinorUnits) {
                    CustomSplitParseResult.Invalid(
                        reason = "Custom split amounts must sum to totalAmount=${totalAmount.toMoneyString()} (actual=${splitTotal.toDisplayAmount()})",
                        parsedSplits = parsed
                    )
                } else {
                    CustomSplitParseResult.Valid(parsed)
                }
            }

            CustomSplitMode.EQUAL -> CustomSplitParseResult.Invalid("EQUAL split type does not require custom splits")
        }
    }

    private fun BigDecimal.toMinorUnitsOrNull(): Long? {
        val scaled = movePointRight(MINOR_UNIT_SCALE).stripTrailingZeros()
        if (scaled.scale() > 0) return null
        return scaled.longValueExact()
    }

    private fun Double.toMinorUnitsOrNull(): Long? {
        return BigDecimal.valueOf(this).toMinorUnitsOrNull()
    }

    private fun Long.toDisplayAmount(): String {
        return BigDecimal.valueOf(this)
            .movePointLeft(MINOR_UNIT_SCALE)
            .toPlainString()
    }

    private fun Double.toMoneyString(): String {
        return BigDecimal.valueOf(this)
            .setScale(MINOR_UNIT_SCALE)
            .toPlainString()
    }

    /**
     * Safe reference detection for member usage in custom split payloads.
     * Uses parsed data when available and falls back to raw token matching.
     */
    fun referencesMember(
        splitsString: String?,
        memberId: Long,
        parseResult: CustomSplitParseResult? = null
    ): Boolean {
        when (parseResult) {
            is CustomSplitParseResult.Valid -> if (memberId in parseResult.splits.keys) return true
            is CustomSplitParseResult.Invalid -> if (memberId in parseResult.parsedSplits.keys) return true
            null -> Unit
        }

        if (splitsString.isNullOrBlank()) return false

        val pattern = Regex("(^|,)\\s*${Regex.escape(memberId.toString())}\\s*:")
        return pattern.containsMatchIn(splitsString)
    }
}
