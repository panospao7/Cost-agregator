package com.yourname.expensetracker.domain.installment

import com.yourname.expensetracker.domain.installment.model.InstallmentPlanKind
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode

/** Result of a plan category check. */
sealed interface PlanCategoryDecision {
    /** The category is acceptable. [categoryId] is null only for a purchase-attached plan whose purchase has none. */
    data class Accepted(val categoryId: Long?) : PlanCategoryDecision

    /** Refused with CATEGORY_REQUIRED or CATEGORY_NOT_FOUND; nothing is written. */
    data class Refused(val code: InstallmentRefusalCode) : PlanCategoryDecision
}

/**
 * Plan category source policy (domain spec 6 Q13, AMD-3, INV-33, D-7). Pure: the set of
 * categories that exist is passed in by the caller, which reads it inside its transaction.
 *
 * - There is no default category. None of the shipped default categories is a Loan, Mortgage,
 *   Bills or Debt category, and no plan operation creates, renames or seeds a category. A
 *   standalone plan therefore starts with nothing preselected ([preselectedCategoryId] is
 *   always null) and the user must pick an existing spending category.
 * - A purchase-attached plan copies the purchase `categoryId` exactly. Null stays null: there is
 *   no fallback or default ([purchaseAttachedCategoryId]).
 * - Standalone create and edit (E3): a missing pick is `CATEGORY_REQUIRED`; a pick that is not
 *   an existing spending category is `CATEGORY_NOT_FOUND`. Edit never sets a standalone plan's
 *   category to null.
 * - Purchase-attached edit (E3): null is allowed (it mirrors a purchase without a category); a
 *   non-null pick must still be an existing spending category. The spec does not pin this case.
 *
 * "Existing spending category" means a category the standard expense category picker offers; the
 * caller builds [existingSpendingCategoryIds] from that same source.
 */
object StandalonePlanDefaultCategoryPolicy {

    /**
     * The category the create-standalone-plan form preselects: always none (D-7: no Loan-like
     * category exists, so there is nothing to preselect and nothing may be created). The offered
     * ids are accepted only to make the "no preselection whatever is offered" rule explicit.
     */
    @Suppress("UNUSED_PARAMETER")
    fun preselectedCategoryId(offeredCategoryIds: Collection<Long>): Long? = null

    /** Category of a purchase-attached plan: the purchase's category, copied exactly (null stays null). */
    fun purchaseAttachedCategoryId(purchaseCategoryId: Long?): Long? = purchaseCategoryId

    /** Validates the category of a standalone plan at create (INV-33). */
    fun validateStandalone(pickedCategoryId: Long?, existingSpendingCategoryIds: Set<Long>): PlanCategoryDecision =
        when {
            pickedCategoryId == null -> PlanCategoryDecision.Refused(InstallmentRefusalCode.CATEGORY_REQUIRED)
            pickedCategoryId !in existingSpendingCategoryIds ->
                PlanCategoryDecision.Refused(InstallmentRefusalCode.CATEGORY_NOT_FOUND)

            else -> PlanCategoryDecision.Accepted(pickedCategoryId)
        }

    /**
     * Validates a category change on an existing plan (edit E3). STANDALONE plans follow
     * [validateStandalone]; PURCHASE_ATTACHED plans accept null and otherwise require an
     * existing spending category.
     */
    fun validateEdit(
        planKind: InstallmentPlanKind,
        newCategoryId: Long?,
        existingSpendingCategoryIds: Set<Long>
    ): PlanCategoryDecision = when (planKind) {
        InstallmentPlanKind.STANDALONE -> validateStandalone(newCategoryId, existingSpendingCategoryIds)
        InstallmentPlanKind.PURCHASE_ATTACHED -> when {
            newCategoryId == null -> PlanCategoryDecision.Accepted(null)
            newCategoryId !in existingSpendingCategoryIds ->
                PlanCategoryDecision.Refused(InstallmentRefusalCode.CATEGORY_NOT_FOUND)

            else -> PlanCategoryDecision.Accepted(newCategoryId)
        }
    }
}
