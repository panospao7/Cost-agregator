package com.yourname.expensetracker.domain.installment

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanKind
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import org.junit.Test

// Oracle: docs/features/loan-installments/domain-spec.md
//   AE-30 (plan category source), INV-33 (AMD-3), section 6 Q13, section 7.0 (CATEGORY_REQUIRED and
//   CATEGORY_NOT_FOUND). OQ-19 and D-7 (a default category) are superseded by AMD-3: there is no
//   default category, nothing is preselected, and nothing is created.
// Fakes: the set of existing spending categories is a plain id set, as the caller reads it inside
// its transaction. Synthetic ids only.
class StandalonePlanDefaultCategoryPolicyTest {

    // The 20 shipped default categories, as synthetic ids 1..20. None is Loan, Mortgage, Bills or Debt.
    private val defaultCategoryIds: Set<Long> = (1L..20L).toSet()

    private fun refused(code: InstallmentRefusalCode) = PlanCategoryDecision.Refused(code)

    // ---------------------------------------------------------------------------------------
    // AE-30: the create-standalone-plan form preselects nothing
    // ---------------------------------------------------------------------------------------

    @Test
    fun ae30_createForm_preselectsNoCategory_withTheTwentyDefaultCategoriesOffered() {
        assertThat(StandalonePlanDefaultCategoryPolicy.preselectedCategoryId(defaultCategoryIds)).isNull()
    }

    @Test
    fun ae30_createForm_preselectsNoCategory_whateverIsOffered() {
        assertThat(StandalonePlanDefaultCategoryPolicy.preselectedCategoryId(emptyList())).isNull()
        assertThat(StandalonePlanDefaultCategoryPolicy.preselectedCategoryId(listOf(7L))).isNull()
        assertThat(StandalonePlanDefaultCategoryPolicy.preselectedCategoryId(listOf(1L, 2L, 3L))).isNull()
    }

    // ---------------------------------------------------------------------------------------
    // AE-30 / INV-33: standalone create
    // ---------------------------------------------------------------------------------------

    @Test
    fun ae30_standaloneSubmitWithoutCategory_isCategoryRequired() {
        assertThat(StandalonePlanDefaultCategoryPolicy.validateStandalone(null, defaultCategoryIds))
            .isEqualTo(refused(InstallmentRefusalCode.CATEGORY_REQUIRED))
    }

    @Test
    fun ae30_standaloneSubmitWithoutCategory_isCategoryRequired_evenWhenNoCategoriesExist() {
        // Required takes precedence over not-found: a missing pick is not a deleted pick.
        assertThat(StandalonePlanDefaultCategoryPolicy.validateStandalone(null, emptySet()))
            .isEqualTo(refused(InstallmentRefusalCode.CATEGORY_REQUIRED))
    }

    @Test
    fun ae30_standalonePickOfExistingCategory7_isAcceptedWithCategory7() {
        assertThat(StandalonePlanDefaultCategoryPolicy.validateStandalone(7L, defaultCategoryIds))
            .isEqualTo(PlanCategoryDecision.Accepted(7L))
    }

    @Test
    fun ae30_standalonePick_acceptsAnyExistingCategoryNotJustDefaults() {
        val withUserCategory = defaultCategoryIds + 4242L

        assertThat(StandalonePlanDefaultCategoryPolicy.validateStandalone(4242L, withUserCategory))
            .isEqualTo(PlanCategoryDecision.Accepted(4242L))
    }

    @Test
    fun ae30_category7DeletedBetweenPickAndSubmit_isCategoryNotFound() {
        val afterDeletion = defaultCategoryIds - 7L

        assertThat(StandalonePlanDefaultCategoryPolicy.validateStandalone(7L, afterDeletion))
            .isEqualTo(refused(InstallmentRefusalCode.CATEGORY_NOT_FOUND))
    }

    @Test
    fun ae30_standalonePickOfNeverExistingId_isCategoryNotFound() {
        assertThat(StandalonePlanDefaultCategoryPolicy.validateStandalone(999_999L, defaultCategoryIds))
            .isEqualTo(refused(InstallmentRefusalCode.CATEGORY_NOT_FOUND))
        assertThat(StandalonePlanDefaultCategoryPolicy.validateStandalone(7L, emptySet()))
            .isEqualTo(refused(InstallmentRefusalCode.CATEGORY_NOT_FOUND))
    }

    @Test
    fun pin_standalonePickOfZeroOrNegativeId_isNotFoundUnlessPresentInTheExistingSet() {
        // The policy does not special-case sentinel ids; existence in the caller supplied set is
        // the only test (INV-33: must reference an existing spending category).
        assertThat(StandalonePlanDefaultCategoryPolicy.validateStandalone(0L, defaultCategoryIds))
            .isEqualTo(refused(InstallmentRefusalCode.CATEGORY_NOT_FOUND))
        assertThat(StandalonePlanDefaultCategoryPolicy.validateStandalone(-1L, defaultCategoryIds))
            .isEqualTo(refused(InstallmentRefusalCode.CATEGORY_NOT_FOUND))
    }

    // ---------------------------------------------------------------------------------------
    // AE-30 / INV-33: no plan operation creates or changes a category
    // ---------------------------------------------------------------------------------------

    @Test
    fun ae30_noPolicyOperationChangesTheExistingCategorySet_categoryCountUnchanged() {
        val categories = defaultCategoryIds.toMutableSet()
        val snapshot = categories.toSet()

        StandalonePlanDefaultCategoryPolicy.preselectedCategoryId(categories)
        StandalonePlanDefaultCategoryPolicy.validateStandalone(null, categories)
        StandalonePlanDefaultCategoryPolicy.validateStandalone(7L, categories)
        StandalonePlanDefaultCategoryPolicy.validateStandalone(999_999L, categories)
        StandalonePlanDefaultCategoryPolicy.validateEdit(InstallmentPlanKind.STANDALONE, null, categories)
        StandalonePlanDefaultCategoryPolicy.validateEdit(InstallmentPlanKind.STANDALONE, 999_999L, categories)
        StandalonePlanDefaultCategoryPolicy.validateEdit(InstallmentPlanKind.PURCHASE_ATTACHED, null, categories)
        StandalonePlanDefaultCategoryPolicy.validateEdit(InstallmentPlanKind.PURCHASE_ATTACHED, 999_999L, categories)

        assertThat(categories).hasSize(20)
        assertThat(categories).isEqualTo(snapshot)
    }

    @Test
    fun ae30_missingOrDeletedPick_isRefusedNotAcceptedWithASubstituteCategory() {
        // A refusal must not become an Accepted decision carrying a defaulted category.
        val missing = StandalonePlanDefaultCategoryPolicy.validateStandalone(null, defaultCategoryIds)
        val notFound = StandalonePlanDefaultCategoryPolicy.validateStandalone(999_999L, defaultCategoryIds)

        assertThat(missing).isInstanceOf(PlanCategoryDecision.Refused::class.java)
        assertThat(notFound).isInstanceOf(PlanCategoryDecision.Refused::class.java)
    }

    // ---------------------------------------------------------------------------------------
    // AE-30 / INV-33: purchase-attached plans copy the purchase category exactly
    // ---------------------------------------------------------------------------------------

    @Test
    fun ae30_purchaseWithCategory3_planCategoryIs3() {
        assertThat(StandalonePlanDefaultCategoryPolicy.purchaseAttachedCategoryId(3L)).isEqualTo(3L)
    }

    @Test
    fun ae30_purchaseWithNullCategory_planCategoryStaysNull_noFallback() {
        assertThat(StandalonePlanDefaultCategoryPolicy.purchaseAttachedCategoryId(null)).isNull()
    }

    @Test
    fun ae30_purchaseCategoryIsCopiedExactly_evenWhenNotInAnyExistingSet() {
        // Declaration performs no category check (spec 7.0: purchase-attached plans copy the
        // purchase category and have no category check). The copy is the identity function.
        for (id in listOf(1L, 3L, 20L, 4242L, 999_999L)) {
            assertThat(StandalonePlanDefaultCategoryPolicy.purchaseAttachedCategoryId(id)).isEqualTo(id)
        }
    }

    // ---------------------------------------------------------------------------------------
    // AE-30 / INV-33: standalone E3 edit
    // ---------------------------------------------------------------------------------------

    @Test
    fun ae30_standaloneEditToNull_isCategoryRequired() {
        assertThat(
            StandalonePlanDefaultCategoryPolicy.validateEdit(InstallmentPlanKind.STANDALONE, null, defaultCategoryIds)
        ).isEqualTo(refused(InstallmentRefusalCode.CATEGORY_REQUIRED))
    }

    @Test
    fun inv33_standaloneEditToAnotherExistingCategory_isAccepted_control() {
        assertThat(
            StandalonePlanDefaultCategoryPolicy.validateEdit(InstallmentPlanKind.STANDALONE, 12L, defaultCategoryIds)
        ).isEqualTo(PlanCategoryDecision.Accepted(12L))
    }

    @Test
    fun inv33_standaloneEditToDeletedCategory_isCategoryNotFound() {
        assertThat(
            StandalonePlanDefaultCategoryPolicy.validateEdit(
                InstallmentPlanKind.STANDALONE, 7L, defaultCategoryIds - 7L
            )
        ).isEqualTo(refused(InstallmentRefusalCode.CATEGORY_NOT_FOUND))
    }

    @Test
    fun inv33_standaloneEdit_followsTheSameRulesAsCreate() {
        val picks = listOf<Long?>(null, 7L, 999_999L)
        for (pick in picks) {
            assertThat(
                StandalonePlanDefaultCategoryPolicy.validateEdit(InstallmentPlanKind.STANDALONE, pick, defaultCategoryIds)
            ).isEqualTo(StandalonePlanDefaultCategoryPolicy.validateStandalone(pick, defaultCategoryIds))
        }
    }

    // ---------------------------------------------------------------------------------------
    // Interpretation 5 (spec silent): purchase-attached category edit
    // ---------------------------------------------------------------------------------------
    // Spec INV-33 states "never to null" only for STANDALONE and says purchase-attached plans copy
    // the purchase category (null stays null). It does not say what an E3 edit of a purchase-attached
    // plan may do. The S5 implementer chose: null is allowed (mirrors a purchase without a category)
    // and a non-null pick must still exist. These tests pin that choice so a change is deliberate.

    @Test
    fun interpretation_5_purchaseAttachedEditToNull_isAccepted() {
        assertThat(
            StandalonePlanDefaultCategoryPolicy.validateEdit(
                InstallmentPlanKind.PURCHASE_ATTACHED, null, defaultCategoryIds
            )
        ).isEqualTo(PlanCategoryDecision.Accepted(null))
    }

    @Test
    fun interpretation_5_purchaseAttachedEditToNull_isAccepted_evenWhenNoCategoriesExist() {
        assertThat(
            StandalonePlanDefaultCategoryPolicy.validateEdit(InstallmentPlanKind.PURCHASE_ATTACHED, null, emptySet())
        ).isEqualTo(PlanCategoryDecision.Accepted(null))
    }

    @Test
    fun interpretation_5_purchaseAttachedEditToExistingCategory_isAccepted() {
        assertThat(
            StandalonePlanDefaultCategoryPolicy.validateEdit(
                InstallmentPlanKind.PURCHASE_ATTACHED, 12L, defaultCategoryIds
            )
        ).isEqualTo(PlanCategoryDecision.Accepted(12L))
    }

    @Test
    fun interpretation_5_purchaseAttachedEditToMissingCategory_isCategoryNotFound() {
        assertThat(
            StandalonePlanDefaultCategoryPolicy.validateEdit(
                InstallmentPlanKind.PURCHASE_ATTACHED, 999_999L, defaultCategoryIds
            )
        ).isEqualTo(refused(InstallmentRefusalCode.CATEGORY_NOT_FOUND))
    }

    @Test
    fun interpretation_5_purchaseAttachedEdit_neverReturnsCategoryRequired() {
        val picks = listOf<Long?>(null, 7L, 999_999L)
        for (pick in picks) {
            val decision = StandalonePlanDefaultCategoryPolicy.validateEdit(
                InstallmentPlanKind.PURCHASE_ATTACHED, pick, defaultCategoryIds
            )

            assertThat(decision).isNotEqualTo(refused(InstallmentRefusalCode.CATEGORY_REQUIRED))
        }
    }
}
