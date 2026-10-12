package com.yourname.expensetracker.domain.savings

import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.model.GoalProtectionLevel
import com.yourname.expensetracker.domain.model.SavingsGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavingsGoalCurrencyPolicyTest {
    private fun goal(currency: String, provenance: String) = SavingsGoal(
        1L, "Goal", 100.0, 0.0, null, GoalProtectionLevel.WARNING, currency, provenance, 0L
    )

    @Test
    fun uncertainAndUnrecognizedProvenanceNeverProvesDenomination() {
        for (currency in listOf("EUR", "USD", "GBP")) {
            for (provenance in listOf("LEGACY_DEFAULT", "UNKNOWN", "", "ASSUMED_HOME_CURRENCY", "ASSUMED_LEGACY_EUR", "SECRET unknown")) {
                val goal = goal(currency, provenance)
                assertNull(SavingsGoalCurrencyPolicy.knownCurrencyOrNull(goal))
                assertTrue(SavingsGoalCurrencyPolicy.hasUnknownActiveGoals(listOf(goal)))
                assertFalse(SavingsGoalCurrencyPolicy.isRecommendationEligible(goal, CurrencyCode(currency)))
            }
        }
    }

    @Test
    fun supportedConfirmedCurrenciesRemainKnownAndOnlyAllocateSameCurrency() {
        for (currency in listOf("EUR", "USD", "GBP")) {
            for (provenance in listOf("EXPLICIT", "EXPLICIT_HOME_CURRENCY", "USER_CONFIRMED", "PARSED_FROM_SOURCE")) {
                val goal = goal(currency, provenance)
                assertEquals(CurrencyCode(currency), SavingsGoalCurrencyPolicy.knownCurrencyOrNull(goal))
                assertTrue(SavingsGoalCurrencyPolicy.isRecommendationEligible(goal, CurrencyCode(currency)))
                assertFalse(SavingsGoalCurrencyPolicy.isRecommendationEligible(goal, CurrencyCode.CHF))
            }
        }
    }

    @Test
    fun invalidCurrencyIsUnknownEvenWithExplicitProvenance() {
        for (currency in listOf("", "SECRET invalid", "ZZZ")) {
            assertNull(SavingsGoalCurrencyPolicy.knownCurrencyOrNull(goal(currency, "EXPLICIT")))
        }
    }

    @Test
    fun completedGoalsAreNotAllocationInputsButTheirDenominationRemainsUnknown() {
        val legacy = goal("EUR", "LEGACY_DEFAULT").copy(currentAmount = 100.0)
        assertFalse(SavingsGoalCurrencyPolicy.hasUnknownActiveGoals(listOf(legacy)))
        assertFalse(SavingsGoalCurrencyPolicy.isRecommendationEligible(legacy, CurrencyCode.EUR))
        assertNull(SavingsGoalCurrencyPolicy.knownCurrencyOrNull(legacy))
        assertFalse(SavingsGoalCurrencyPolicy.hasUnknownActiveGoals(emptyList()))
    }
}
