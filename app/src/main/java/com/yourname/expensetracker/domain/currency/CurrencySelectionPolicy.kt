package com.yourname.expensetracker.domain.currency

import com.yourname.expensetracker.domain.core.money.CurrencyCode

/** Where the currency control is used. [INSTALLMENT] restricts the options to the loan plan currencies. */
enum class CurrencyEntryMode {
    STANDARD,
    INSTALLMENT
}

/** Why a [CurrencySelection] has no currency yet. Bounded. */
enum class CurrencyUnsetReason {
    /** A proposal (for example an OCR currency) exists but is not selectable. */
    PROPOSAL_NOT_SELECTABLE,

    /** Nothing was proposed. */
    NONE_PROPOSED
}

/** State of the currency control. [Unset] can never be saved and is never replaced silently (INV-11). */
sealed interface CurrencySelection {
    data class Selected(val code: CurrencyCode, val isHome: Boolean) : CurrencySelection
    data class Unset(val reason: CurrencyUnsetReason) : CurrencySelection
}

/**
 * Single owner of "which currencies may be chosen for a new user-present entry" (INV-10) and of
 * proposal resolution (INV-11). Independent of rate availability. Pure: no clock, I/O or logging.
 *
 * `selectable(home, STANDARD) = ({active catalog} - NO_RATE_SOURCE) + {home}`.
 * `INSTALLMENT` keeps only EUR and USD from that set (home stays only if it is EUR or USD).
 * [SupportedCurrency.isActive] is not changed by this policy (legacy rows keep converting).
 */
object CurrencySelectionPolicy {

    /** Active catalog currencies that no rate source lists (BGN since 2026-01-02, RUB since 2022-03-01). */
    val NO_RATE_SOURCE: Set<CurrencyCode> = setOf(CurrencyCode.BGN, CurrencyCode("RUB"))

    /** Plan currencies of installment mode (loan v1). */
    val INSTALLMENT_CURRENCIES: Set<CurrencyCode> = setOf(CurrencyCode.EUR, CurrencyCode.USD)

    /**
     * Ordered options for the control: [home] first (when it qualifies), the others by code.
     * Home is always present in [CurrencyEntryMode.STANDARD], even if inactive or without a rate source.
     */
    fun selectable(home: CurrencyCode, mode: CurrencyEntryMode): List<CurrencyCode> {
        val others = SupportedCurrency.activeCatalog
            .map { CurrencyCode(it.code) }
            .filter { it !in NO_RATE_SOURCE && it != home }
            .sorted()
        val all = listOf(home) + others
        return when (mode) {
            CurrencyEntryMode.STANDARD -> all
            CurrencyEntryMode.INSTALLMENT -> all.filter { it in INSTALLMENT_CURRENCIES }
        }
    }

    /** `true` when [code] is in [selectable] for [home] and [mode]. */
    fun isSelectable(code: CurrencyCode, home: CurrencyCode, mode: CurrencyEntryMode): Boolean =
        code in selectable(home, mode)

    /**
     * Resolves a proposed currency. `null` -> `Unset(NONE_PROPOSED)`; a selectable proposal ->
     * `Selected`; anything else -> `Unset(PROPOSAL_NOT_SELECTABLE)`. A proposal is never swapped
     * for home or another currency.
     */
    fun resolveProposal(proposed: CurrencyCode?, home: CurrencyCode, mode: CurrencyEntryMode): CurrencySelection =
        when {
            proposed == null -> CurrencySelection.Unset(CurrencyUnsetReason.NONE_PROPOSED)
            isSelectable(proposed, home, mode) -> CurrencySelection.Selected(proposed, isHome = proposed == home)
            else -> CurrencySelection.Unset(CurrencyUnsetReason.PROPOSAL_NOT_SELECTABLE)
        }
}
