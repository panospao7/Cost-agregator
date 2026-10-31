package com.yourname.expensetracker.domain.schedule

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import org.junit.Test

// Oracle: docs/features/loan-installments/domain-spec.md
//   INV-5 (schedule exclusivity), INV-16 (currency integrity), section 7.0 (declaration preconditions),
//   AE-5 (CURRENCY_MISMATCH link refusal), AE-13 (exclusivity), AE-22 (declaration refusals).
// Pure policy: explicit values only, no clock, no I/O.
class ExpenseScheduleLinkPolicyTest {

    private fun refused(reason: ScheduleLinkRefusal) = ScheduleLinkDecision.Refused(reason)

    private fun facts(
        isSpendingType: Boolean = true,
        isNotMine: Boolean = false,
        isSharedExpense: Boolean = false,
        hasSplit: Boolean = false,
        spendingExclusionReason: String? = null,
        relations: ExpenseScheduleRelations = ExpenseScheduleRelations()
    ) = PurchaseDeclarationFacts(
        isSpendingType = isSpendingType,
        isNotMine = isNotMine,
        isSharedExpense = isSharedExpense,
        hasSplit = hasSplit,
        spendingExclusionReason = spendingExclusionReason,
        relations = relations
    )

    // ---------------------------------------------------------------------------------------
    // INV-5 / AE-13: linking to an installment entry
    // ---------------------------------------------------------------------------------------

    @Test
    fun inv5_unlinkedExpense_mayLinkToInstallmentEntry_control() {
        val decision = ExpenseScheduleLinkPolicy.evaluateLink(
            ScheduleLinkTarget.INSTALLMENT_ENTRY,
            targetId = 3L,
            relations = ExpenseScheduleRelations()
        )

        assertThat(decision).isEqualTo(ScheduleLinkDecision.Allowed)
    }

    @Test
    fun ae13_expenseLinkedToRecurringOccurrence_linkToInstallmentEntry_isLinkedElsewhere() {
        val decision = ExpenseScheduleLinkPolicy.evaluateLink(
            ScheduleLinkTarget.INSTALLMENT_ENTRY,
            targetId = 3L,
            relations = ExpenseScheduleRelations(linkedRecurringOccurrenceId = 9L)
        )

        assertThat(decision).isEqualTo(refused(ScheduleLinkRefusal.LINKED_ELSEWHERE))
    }

    @Test
    fun ae13_expenseLinkedToEntry3_linkToEntry4_isRefused() {
        val decision = ExpenseScheduleLinkPolicy.evaluateLink(
            ScheduleLinkTarget.INSTALLMENT_ENTRY,
            targetId = 4L,
            relations = ExpenseScheduleRelations(linkedInstallmentEntryId = 3L)
        )

        assertThat(decision).isEqualTo(refused(ScheduleLinkRefusal.LINKED_ELSEWHERE))
    }

    @Test
    fun ae13_expenseLinkedToEntry3_linkToEntry3Again_isAlreadyLinkedToTarget() {
        val decision = ExpenseScheduleLinkPolicy.evaluateLink(
            ScheduleLinkTarget.INSTALLMENT_ENTRY,
            targetId = 3L,
            relations = ExpenseScheduleRelations(linkedInstallmentEntryId = 3L)
        )

        assertThat(decision).isEqualTo(ScheduleLinkDecision.AlreadyLinkedToTarget)
    }

    @Test
    fun inv5_planPurchaseRecord_cannotBeLinkedToInstallmentEntry() {
        val decision = ExpenseScheduleLinkPolicy.evaluateLink(
            ScheduleLinkTarget.INSTALLMENT_ENTRY,
            targetId = 3L,
            relations = ExpenseScheduleRelations(isPlanPurchaseRecord = true)
        )

        assertThat(decision).isEqualTo(refused(ScheduleLinkRefusal.LINKED_ELSEWHERE))
    }

    @Test
    fun inv5_planPurchaseRecordLinkedToSameEntry_isStillRefused_notAlreadyLinked() {
        // An expense that is both a purchase record and linked to the target entry violates
        // INV-5 already; the policy must not report it as a clean success.
        val decision = ExpenseScheduleLinkPolicy.evaluateLink(
            ScheduleLinkTarget.INSTALLMENT_ENTRY,
            targetId = 3L,
            relations = ExpenseScheduleRelations(linkedInstallmentEntryId = 3L, isPlanPurchaseRecord = true)
        )

        assertThat(decision).isEqualTo(refused(ScheduleLinkRefusal.LINKED_ELSEWHERE))
    }

    @Test
    fun inv5_entryAndRecurringBoth_sameEntryTarget_isRefused_notAlreadyLinked() {
        val decision = ExpenseScheduleLinkPolicy.evaluateLink(
            ScheduleLinkTarget.INSTALLMENT_ENTRY,
            targetId = 3L,
            relations = ExpenseScheduleRelations(linkedInstallmentEntryId = 3L, linkedRecurringOccurrenceId = 9L)
        )

        assertThat(decision).isEqualTo(refused(ScheduleLinkRefusal.LINKED_ELSEWHERE))
    }

    // ---------------------------------------------------------------------------------------
    // INV-5 / AE-13: linking to a recurring occurrence (the recurring auto-link must not claim)
    // ---------------------------------------------------------------------------------------

    @Test
    fun inv5_unlinkedExpense_mayLinkToRecurringOccurrence_control() {
        val decision = ExpenseScheduleLinkPolicy.evaluateLink(
            ScheduleLinkTarget.RECURRING_OCCURRENCE,
            targetId = 9L,
            relations = ExpenseScheduleRelations()
        )

        assertThat(decision).isEqualTo(ScheduleLinkDecision.Allowed)
        assertThat(ExpenseScheduleLinkPolicy.mayLinkToRecurringOccurrence(9L, ExpenseScheduleRelations())).isTrue()
    }

    @Test
    fun ae13_installmentExpenseLinkedToEntry_recurringAutoLinkMayNotClaimIt() {
        // AE-13: rule "Demo Electronics" 50.00 EUR monthly; the auto-recorded installment expense
        // is linked to its entry, so the recurring auto-link must not claim it.
        val relations = ExpenseScheduleRelations(linkedInstallmentEntryId = 3L)

        assertThat(ExpenseScheduleLinkPolicy.mayLinkToRecurringOccurrence(9L, relations)).isFalse()
        assertThat(
            ExpenseScheduleLinkPolicy.evaluateLink(ScheduleLinkTarget.RECURRING_OCCURRENCE, 9L, relations)
        ).isEqualTo(refused(ScheduleLinkRefusal.LINKED_ELSEWHERE))
    }

    @Test
    fun inv5_planPurchaseRecord_cannotBeLinkedToRecurringOccurrence() {
        val relations = ExpenseScheduleRelations(isPlanPurchaseRecord = true)

        assertThat(
            ExpenseScheduleLinkPolicy.evaluateLink(ScheduleLinkTarget.RECURRING_OCCURRENCE, 9L, relations)
        ).isEqualTo(refused(ScheduleLinkRefusal.LINKED_ELSEWHERE))
        assertThat(ExpenseScheduleLinkPolicy.mayLinkToRecurringOccurrence(9L, relations)).isFalse()
    }

    @Test
    fun pin_recurringUniquenessBetweenOccurrencesIsNotDecidedByThisPolicy() {
        // Spec INV-5 only covers exclusivity across the three roles. One occurrence per expense
        // stays with the recurring lifecycle, so an expense linked to a recurring occurrence is not
        // refused here when the target is another recurring occurrence.
        val relations = ExpenseScheduleRelations(linkedRecurringOccurrenceId = 9L)

        assertThat(
            ExpenseScheduleLinkPolicy.evaluateLink(ScheduleLinkTarget.RECURRING_OCCURRENCE, 10L, relations)
        ).isEqualTo(ScheduleLinkDecision.Allowed)
    }

    // ---------------------------------------------------------------------------------------
    // INV-5 exhaustive: after a permitted link the expense holds exactly one schedule role
    // ---------------------------------------------------------------------------------------

    @Test
    fun inv5_exhaustiveInstallmentLink_permittedOnlyWhenNoOtherRoleHeld() {
        val targetEntry = 3L
        val entryOptions = listOf<Long?>(null, 3L, 4L)
        val recurringOptions = listOf<Long?>(null, 9L)
        val purchaseOptions = listOf(false, true)

        for (entry in entryOptions) for (recurring in recurringOptions) for (purchase in purchaseOptions) {
            val relations = ExpenseScheduleRelations(entry, recurring, purchase)
            val decision = ExpenseScheduleLinkPolicy.evaluateLink(
                ScheduleLinkTarget.INSTALLMENT_ENTRY, targetEntry, relations
            )

            val otherRoles = (if (purchase) 1 else 0) +
                (if (recurring != null) 1 else 0) +
                (if (entry != null && entry != targetEntry) 1 else 0)
            val label = "entry=$entry recurring=$recurring purchase=$purchase"
            val expected = when {
                otherRoles > 0 -> refused(ScheduleLinkRefusal.LINKED_ELSEWHERE)
                entry == targetEntry -> ScheduleLinkDecision.AlreadyLinkedToTarget
                else -> ScheduleLinkDecision.Allowed
            }
            assertWithMessage(label).that(decision).isEqualTo(expected)
        }
    }

    @Test
    fun inv5_exhaustiveRecurringLink_permittedOnlyWhenNoInstallmentRoleHeld() {
        val entryOptions = listOf<Long?>(null, 3L)
        val recurringOptions = listOf<Long?>(null, 9L)
        val purchaseOptions = listOf(false, true)

        for (entry in entryOptions) for (recurring in recurringOptions) for (purchase in purchaseOptions) {
            val relations = ExpenseScheduleRelations(entry, recurring, purchase)
            val mayLink = ExpenseScheduleLinkPolicy.mayLinkToRecurringOccurrence(11L, relations)

            val holdsInstallmentRole = purchase || entry != null
            assertThat(mayLink).isEqualTo(!holdsInstallmentRole)
        }
    }

    // ---------------------------------------------------------------------------------------
    // AE-5 / INV-16: evaluateInstallmentLink currency integrity and type check
    // ---------------------------------------------------------------------------------------

    @Test
    fun ae5_usdExpenseLinkedToEurPlanEntry_isCurrencyMismatch() {
        // AE-5 plan C: plan currency EUR, user links a 56.00 USD expense.
        val decision = ExpenseScheduleLinkPolicy.evaluateInstallmentLink(
            planCurrency = "EUR",
            expenseCurrency = "USD",
            isSpendingType = true,
            targetEntryId = 21L,
            relations = ExpenseScheduleRelations()
        )

        assertThat(decision).isEqualTo(refused(ScheduleLinkRefusal.CURRENCY_MISMATCH))
    }

    @Test
    fun ae5_eurExpenseLinkedToUsdPlanEntry_isCurrencyMismatch() {
        val decision = ExpenseScheduleLinkPolicy.evaluateInstallmentLink(
            planCurrency = "USD",
            expenseCurrency = "EUR",
            isSpendingType = true,
            targetEntryId = 21L,
            relations = ExpenseScheduleRelations()
        )

        assertThat(decision).isEqualTo(refused(ScheduleLinkRefusal.CURRENCY_MISMATCH))
    }

    @Test
    fun inv16_sameCurrency_unlinkedExpense_isAllowed_control() {
        for (currency in listOf("EUR", "USD")) {
            val decision = ExpenseScheduleLinkPolicy.evaluateInstallmentLink(
                planCurrency = currency,
                expenseCurrency = currency,
                isSpendingType = true,
                targetEntryId = 21L,
                relations = ExpenseScheduleRelations()
            )

            assertThat(decision).isEqualTo(ScheduleLinkDecision.Allowed)
        }
    }

    @Test
    fun inv16_sameCurrency_relinkToSameEntry_isAlreadyLinkedToTarget() {
        val decision = ExpenseScheduleLinkPolicy.evaluateInstallmentLink(
            planCurrency = "EUR",
            expenseCurrency = "EUR",
            isSpendingType = true,
            targetEntryId = 21L,
            relations = ExpenseScheduleRelations(linkedInstallmentEntryId = 21L)
        )

        assertThat(decision).isEqualTo(ScheduleLinkDecision.AlreadyLinkedToTarget)
    }

    @Test
    fun inv16_currencyDriftedExpenseAlreadyLinkedToSameEntry_isCurrencyMismatch_notAlreadyLinked() {
        // A re-confirm of an entry whose linked expense currency drifted must not be a silent success.
        val decision = ExpenseScheduleLinkPolicy.evaluateInstallmentLink(
            planCurrency = "EUR",
            expenseCurrency = "USD",
            isSpendingType = true,
            targetEntryId = 21L,
            relations = ExpenseScheduleRelations(linkedInstallmentEntryId = 21L)
        )

        assertThat(decision).isEqualTo(refused(ScheduleLinkRefusal.CURRENCY_MISMATCH))
    }

    @Test
    fun pin_currencyComparisonIsExactStoredText_notCaseInsensitive() {
        // Spec INV-16 compares the stored ISO 4217 currency to the plan currency. Stored values are
        // upper case; a differently cased text is not equal and is refused rather than normalised.
        val decision = ExpenseScheduleLinkPolicy.evaluateInstallmentLink(
            planCurrency = "EUR",
            expenseCurrency = "eur",
            isSpendingType = true,
            targetEntryId = 21L,
            relations = ExpenseScheduleRelations()
        )

        assertThat(decision).isEqualTo(refused(ScheduleLinkRefusal.CURRENCY_MISMATCH))
    }

    @Test
    fun r4_nonSpendingExpense_cannotFulfilInstallment_isNotASpendingExpense() {
        val decision = ExpenseScheduleLinkPolicy.evaluateInstallmentLink(
            planCurrency = "EUR",
            expenseCurrency = "EUR",
            isSpendingType = false,
            targetEntryId = 21L,
            relations = ExpenseScheduleRelations()
        )

        assertThat(decision).isEqualTo(refused(ScheduleLinkRefusal.NOT_A_SPENDING_EXPENSE))
    }

    @Test
    fun inv5_installmentLink_exclusivityStillAppliesAfterTypeAndCurrencyPass() {
        val recurring = ExpenseScheduleLinkPolicy.evaluateInstallmentLink(
            planCurrency = "EUR",
            expenseCurrency = "EUR",
            isSpendingType = true,
            targetEntryId = 21L,
            relations = ExpenseScheduleRelations(linkedRecurringOccurrenceId = 9L)
        )
        val otherEntry = ExpenseScheduleLinkPolicy.evaluateInstallmentLink(
            planCurrency = "EUR",
            expenseCurrency = "EUR",
            isSpendingType = true,
            targetEntryId = 4L,
            relations = ExpenseScheduleRelations(linkedInstallmentEntryId = 3L)
        )

        assertThat(recurring).isEqualTo(refused(ScheduleLinkRefusal.LINKED_ELSEWHERE))
        assertThat(otherEntry).isEqualTo(refused(ScheduleLinkRefusal.LINKED_ELSEWHERE))
    }

    @Test
    fun pin_installmentLinkRefusalOrder_typeBeforeCurrencyBeforeExclusivity() {
        // The spec lists the three rules but does not order them for the link path. The policy
        // documents type, then currency, then exclusivity; pinned so a reorder is a deliberate change.
        val allWrong = ExpenseScheduleLinkPolicy.evaluateInstallmentLink(
            planCurrency = "EUR",
            expenseCurrency = "USD",
            isSpendingType = false,
            targetEntryId = 4L,
            relations = ExpenseScheduleRelations(linkedInstallmentEntryId = 3L)
        )
        val currencyAndLinked = ExpenseScheduleLinkPolicy.evaluateInstallmentLink(
            planCurrency = "EUR",
            expenseCurrency = "USD",
            isSpendingType = true,
            targetEntryId = 4L,
            relations = ExpenseScheduleRelations(linkedInstallmentEntryId = 3L)
        )

        assertThat(allWrong).isEqualTo(refused(ScheduleLinkRefusal.NOT_A_SPENDING_EXPENSE))
        assertThat(currencyAndLinked).isEqualTo(refused(ScheduleLinkRefusal.CURRENCY_MISMATCH))
    }

    // ---------------------------------------------------------------------------------------
    // Section 7.0 / AE-22: declaration preconditions (evaluateDeclaration)
    // ---------------------------------------------------------------------------------------

    @Test
    fun section70_cleanSpendingPurchase_declarationIsAllowed_control() {
        assertThat(ExpenseScheduleLinkPolicy.evaluateDeclaration(facts()))
            .isEqualTo(ScheduleLinkDecision.Allowed)
    }

    @Test
    fun ae22_incomeTypePurchase_isNotASpendingExpense() {
        assertThat(ExpenseScheduleLinkPolicy.evaluateDeclaration(facts(isSpendingType = false)))
            .isEqualTo(refused(ScheduleLinkRefusal.NOT_A_SPENDING_EXPENSE))
    }

    @Test
    fun ae22_notMinePurchase_isNotMine() {
        assertThat(ExpenseScheduleLinkPolicy.evaluateDeclaration(facts(isNotMine = true)))
            .isEqualTo(refused(ScheduleLinkRefusal.NOT_MINE))
    }

    @Test
    fun ae22_sharedPurchase_isSharedNotSupported() {
        assertThat(ExpenseScheduleLinkPolicy.evaluateDeclaration(facts(isSharedExpense = true)))
            .isEqualTo(refused(ScheduleLinkRefusal.SHARED_NOT_SUPPORTED))
    }

    @Test
    fun section70_splitPurchase_isSplitNotSupported() {
        assertThat(ExpenseScheduleLinkPolicy.evaluateDeclaration(facts(hasSplit = true)))
            .isEqualTo(refused(ScheduleLinkRefusal.SPLIT_NOT_SUPPORTED))
    }

    @Test
    fun ae22_secondDeclaration_purchaseRecordOfExistingPlan_isAlreadyDeclared() {
        val decision = ExpenseScheduleLinkPolicy.evaluateDeclaration(
            facts(relations = ExpenseScheduleRelations(isPlanPurchaseRecord = true))
        )

        assertThat(decision).isEqualTo(refused(ScheduleLinkRefusal.ALREADY_DECLARED))
    }

    @Test
    fun section70_exclusionReasonSet_isAlreadyDeclared() {
        val decision = ExpenseScheduleLinkPolicy.evaluateDeclaration(
            facts(spendingExclusionReason = "INSTALLMENT_PURCHASE")
        )

        assertThat(decision).isEqualTo(refused(ScheduleLinkRefusal.ALREADY_DECLARED))
    }

    @Test
    fun pin_anyNonNullExclusionReason_isAlreadyDeclared_evenWhenLinkedToReversedEntry() {
        // Spec 7.0 reads: spendingExclusionReason = null and not already a plan purchase,
        // otherwise ALREADY_DECLARED. An INSTALLMENT_REVERSED flag (linked to a reversed entry)
        // is a non-null reason, so the literal spec rule yields ALREADY_DECLARED, not LINKED_ELSEWHERE.
        val decision = ExpenseScheduleLinkPolicy.evaluateDeclaration(
            facts(
                spendingExclusionReason = "INSTALLMENT_REVERSED",
                relations = ExpenseScheduleRelations(linkedInstallmentEntryId = 5L)
            )
        )

        assertThat(decision).isEqualTo(refused(ScheduleLinkRefusal.ALREADY_DECLARED))
    }

    @Test
    fun section70_purchaseLinkedToInstallmentEntry_isLinkedElsewhere() {
        val decision = ExpenseScheduleLinkPolicy.evaluateDeclaration(
            facts(relations = ExpenseScheduleRelations(linkedInstallmentEntryId = 5L))
        )

        assertThat(decision).isEqualTo(refused(ScheduleLinkRefusal.LINKED_ELSEWHERE))
    }

    @Test
    fun section70_purchaseLinkedToRecurringOccurrence_isLinkedElsewhere() {
        val decision = ExpenseScheduleLinkPolicy.evaluateDeclaration(
            facts(relations = ExpenseScheduleRelations(linkedRecurringOccurrenceId = 9L))
        )

        assertThat(decision).isEqualTo(refused(ScheduleLinkRefusal.LINKED_ELSEWHERE))
    }

    @Test
    fun section70_declarationPrecedence_firstFailingRuleWins() {
        // Spec 7.0 order: spending type, not mine, shared, split, already declared, linked elsewhere.
        val everythingWrong = facts(
            isSpendingType = false,
            isNotMine = true,
            isSharedExpense = true,
            hasSplit = true,
            spendingExclusionReason = "INSTALLMENT_PURCHASE",
            relations = ExpenseScheduleRelations(
                linkedInstallmentEntryId = 5L,
                linkedRecurringOccurrenceId = 9L,
                isPlanPurchaseRecord = true
            )
        )
        val expectedOrder = listOf(
            ScheduleLinkRefusal.NOT_A_SPENDING_EXPENSE,
            ScheduleLinkRefusal.NOT_MINE,
            ScheduleLinkRefusal.SHARED_NOT_SUPPORTED,
            ScheduleLinkRefusal.SPLIT_NOT_SUPPORTED,
            ScheduleLinkRefusal.ALREADY_DECLARED,
            ScheduleLinkRefusal.LINKED_ELSEWHERE
        )

        var current = everythingWrong
        for ((index, expected) in expectedOrder.withIndex()) {
            assertThat(ExpenseScheduleLinkPolicy.evaluateDeclaration(current))
                .isEqualTo(refused(expected))
            // Clear the failing rule and expect the next one in spec order to surface.
            current = when (index) {
                0 -> current.copy(isSpendingType = true)
                1 -> current.copy(isNotMine = false)
                2 -> current.copy(isSharedExpense = false)
                3 -> current.copy(hasSplit = false)
                4 -> current.copy(
                    spendingExclusionReason = null,
                    relations = current.relations.copy(isPlanPurchaseRecord = false)
                )
                else -> current.copy(relations = ExpenseScheduleRelations())
            }
        }
        assertThat(ExpenseScheduleLinkPolicy.evaluateDeclaration(current))
            .isEqualTo(ScheduleLinkDecision.Allowed)
    }

    @Test
    fun section70_declaration_neverReportsAlreadyLinkedToTarget() {
        // Declaration has no target; the only successful outcome is Allowed.
        val decision = ExpenseScheduleLinkPolicy.evaluateDeclaration(facts())

        assertThat(decision).isNotEqualTo(ScheduleLinkDecision.AlreadyLinkedToTarget)
    }

    // ---------------------------------------------------------------------------------------
    // Bounded codes
    // ---------------------------------------------------------------------------------------

    @Test
    fun everyScheduleLinkRefusal_isReachableFromThePolicy() {
        val reached = mutableSetOf<ScheduleLinkRefusal>()
        fun record(decision: ScheduleLinkDecision) {
            if (decision is ScheduleLinkDecision.Refused) reached += decision.reason
        }

        record(ExpenseScheduleLinkPolicy.evaluateDeclaration(facts(isSpendingType = false)))
        record(ExpenseScheduleLinkPolicy.evaluateDeclaration(facts(isNotMine = true)))
        record(ExpenseScheduleLinkPolicy.evaluateDeclaration(facts(isSharedExpense = true)))
        record(ExpenseScheduleLinkPolicy.evaluateDeclaration(facts(hasSplit = true)))
        record(
            ExpenseScheduleLinkPolicy.evaluateDeclaration(
                facts(relations = ExpenseScheduleRelations(isPlanPurchaseRecord = true))
            )
        )
        record(
            ExpenseScheduleLinkPolicy.evaluateLink(
                ScheduleLinkTarget.INSTALLMENT_ENTRY, 4L, ExpenseScheduleRelations(linkedInstallmentEntryId = 3L)
            )
        )
        record(
            ExpenseScheduleLinkPolicy.evaluateInstallmentLink(
                "EUR", "USD", true, 4L, ExpenseScheduleRelations()
            )
        )

        assertThat(reached).containsExactlyElementsIn(ScheduleLinkRefusal.entries)
    }

    @Test
    fun everyScheduleLinkRefusal_mapsToTheInstallmentRefusalCodeOfTheSameName() {
        for (refusal in ScheduleLinkRefusal.entries) {
            val mapped = InstallmentRefusalCode.fromScheduleRefusal(refusal)

            assertThat(mapped.name).isEqualTo(refusal.name)
        }
    }

    @Test
    fun policy_isDeterministic_sameInputsGiveEqualDecisions() {
        val relations = ExpenseScheduleRelations(linkedInstallmentEntryId = 3L)

        val first = ExpenseScheduleLinkPolicy.evaluateLink(ScheduleLinkTarget.INSTALLMENT_ENTRY, 4L, relations)
        val second = ExpenseScheduleLinkPolicy.evaluateLink(ScheduleLinkTarget.INSTALLMENT_ENTRY, 4L, relations)

        assertThat(first).isEqualTo(second)
    }
}
