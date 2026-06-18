package com.yourname.expensetracker.ui.model

import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.util.CurrencyFormatter

/**
 * Universal frontend contract for displaying money amounts.
 *
 * Rule: No frontend screen may format financial amounts without explicit currency.
 *
 * This model ensures:
 * 1. Currency is always explicit (never defaults to EUR silently)
 * 2. Partial/degraded state is visible to the user
 * 3. Formatting is consistent across all screens
 */
data class MoneyDisplayUi(
    val amount: Double,
    val currency: String,
    val formatted: String,
    val isPartial: Boolean = false,
    val warning: UiText? = null
) {
    companion object {
        /**
         * Creates a MoneyDisplayUi from amount + currency.
         */
        fun from(amount: Double, currency: String, showCents: Boolean = true): MoneyDisplayUi {
            return MoneyDisplayUi(
                amount = amount,
                currency = currency,
                formatted = CurrencyFormatter.formatMoney(amount, currency, showCents)
            )
        }

        /**
         * Creates a MoneyDisplayUi from a MoneyAggregate (multi-currency safe).
         */
        fun fromAggregate(aggregate: MoneyAggregate): MoneyDisplayUi {
            return MoneyDisplayUi(
                amount = aggregate.displayAmount,
                currency = aggregate.displayCurrency.code,
                formatted = CurrencyFormatter.formatMoney(
                    aggregate.displayAmount,
                    aggregate.displayCurrency.code
                ),
                isPartial = aggregate.isPartial,
                warning = if (aggregate.isPartial) {
                    UiText.DynamicString(
                        aggregate.warningMessage ?: "Some amounts could not be converted"
                    )
                } else null
            )
        }

        /**
         * Placeholder for loading state (no amount yet).
         */
        fun loading(currency: String = "—"): MoneyDisplayUi {
            return MoneyDisplayUi(
                amount = 0.0,
                currency = currency,
                formatted = "—",
                isPartial = false
            )
        }
    }
}
