package com.yourname.expensetracker.ui.util

/**
 * Shared ownership validation for Add/Edit expense paths.
 *
 * Enforces consistent rules:
 * - isNotMine and isSharedExpense cannot both be true
 * - Shared expense requires sharedWithName
 * - Shared expense requires either percentage OR amount (not both, not neither)
 * - Percentage must be 0..100
 * - Share amount must be > 0
 */
object OwnershipValidator {

    sealed interface ValidationResult {
        data object Valid : ValidationResult
        data class Invalid(val message: String) : ValidationResult
    }

    fun validate(
        isNotMine: Boolean,
        isSharedExpense: Boolean,
        sharedWithName: String,
        sharePercentageText: String,
        shareAmountText: String
    ): ValidationResult {
        if (isNotMine && isSharedExpense) {
            return ValidationResult.Invalid("Expense cannot be both not-mine and shared")
        }

        if (!isSharedExpense) return ValidationResult.Valid

        if (sharedWithName.trim().isBlank()) {
            return ValidationResult.Invalid("Shared with name is required for shared expenses")
        }

        val hasPercentage = sharePercentageText.isNotEmpty()
        val hasAmount = shareAmountText.isNotEmpty()

        if (hasPercentage == hasAmount) {
            return ValidationResult.Invalid("Set either share % or share amount for shared expenses")
        }

        if (hasPercentage) {
            val pct = sharePercentageText.toIntOrNull()
            if (pct == null || pct !in 0..100) {
                return ValidationResult.Invalid("Share percentage must be between 0 and 100")
            }
        }

        if (hasAmount) {
            val amt = AmountInputSanitizer.sanitize(shareAmountText).toDoubleOrNull()
            if (amt == null || amt <= 0) {
                return ValidationResult.Invalid("Share amount must be greater than 0")
            }
        }

        return ValidationResult.Valid
    }
}
