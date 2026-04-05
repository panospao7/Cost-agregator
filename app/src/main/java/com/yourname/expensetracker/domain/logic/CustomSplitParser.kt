package com.yourname.expensetracker.domain.logic

import kotlin.math.abs

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
    private const val AMOUNT_TOLERANCE = 0.01
    private const val PERCENT_TOLERANCE = 0.1

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

        val parsed = linkedMapOf<Long, Double>()
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

            val value = parts[1].trim().toDoubleOrNull()
                ?: return CustomSplitParseResult.Invalid(
                    reason = "Invalid split value in split pair: $pair",
                    parsedSplits = parsed
                )

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

            parsed[memberId] = value
        }

        if (parsed.keys != groupMemberIds) {
            return CustomSplitParseResult.Invalid(
                reason = "Custom split entries must include every member exactly once",
                parsedSplits = parsed
            )
        }

        return when (splitType) {
            CustomSplitMode.CUSTOM_PERCENT -> {
                val percentTotal = parsed.values.sum()
                if (abs(percentTotal - 100.0) > PERCENT_TOLERANCE) {
                    CustomSplitParseResult.Invalid(
                        reason = "Custom percentages must sum to ~100 (actual=$percentTotal)",
                        parsedSplits = parsed
                    )
                } else {
                    CustomSplitParseResult.Valid(parsed)
                }
            }

            CustomSplitMode.CUSTOM_AMOUNT,
            CustomSplitMode.UNEQUAL -> {
                val splitTotal = parsed.values.sum()
                if (abs(splitTotal - totalAmount) > AMOUNT_TOLERANCE) {
                    CustomSplitParseResult.Invalid(
                        reason = "Custom split amounts must sum to totalAmount=$totalAmount (actual=$splitTotal)",
                        parsedSplits = parsed
                    )
                } else {
                    CustomSplitParseResult.Valid(parsed)
                }
            }

            CustomSplitMode.EQUAL -> CustomSplitParseResult.Invalid("EQUAL split type does not require custom splits")
        }
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
