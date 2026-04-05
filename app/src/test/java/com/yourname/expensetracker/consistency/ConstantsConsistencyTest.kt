package com.yourname.expensetracker.consistency

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator
import com.yourname.expensetracker.domain.budget.BudgetForecastingEngine
import com.yourname.expensetracker.domain.groups.SettlementCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Modifier

class ConstantsConsistencyTest : AnalyticsEngineTestBase() {

    @Test
    fun `all cross engine thresholds match documented values`() {
        val underPaceThreshold = readNumberConstant(
            owner = SpendingPaceCalculator::class.java,
            fieldName = "PACE_UNDER_THRESHOLD"
        ).toFloat()

        val settlementIterationLimit = readNumberConstant(
            owner = SettlementCalculator::class.java,
            fieldName = "DFS_ITERATION_LIMIT"
        ).toInt()

        val budgetMonitorWarningThresholdPercent = Budget(
            categoryId = null,
            amount = 1.0,
            period = BudgetPeriod.MONTHLY,
            startDate = 1L
        ).notifyAtCritical.toDouble() * 100.0

        assertApproxEquals(90f, underPaceThreshold, 0.0f)
        assertApproxEquals(90.0, budgetMonitorWarningThresholdPercent, 0.0)
        assertApproxEquals(underPaceThreshold.toDouble(), budgetMonitorWarningThresholdPercent, 0.0)

        assertEquals(100_000, settlementIterationLimit)

        assertEquals(3, BudgetForecastingEngine.MIN_HISTORY_MONTHS)
        assertApproxEquals(0.8, BudgetForecastingEngine.CONFIDENCE_THRESHOLD_HIGH, 0.0)
        assertApproxEquals(0.6, BudgetForecastingEngine.CONFIDENCE_THRESHOLD_MEDIUM, 0.0)
    }

    @Test
    fun `no duplicate constants drift across engines`() {
        val paceThresholdField = findField(
            owner = SpendingPaceCalculator::class.java,
            fieldName = "PACE_UNDER_THRESHOLD"
        )
        val settlementLimitField = findField(
            owner = SettlementCalculator::class.java,
            fieldName = "DFS_ITERATION_LIMIT"
        )

        assertNotNull(paceThresholdField)
        assertNotNull(settlementLimitField)

        assertTrue(
            "Pace threshold should be a compile-time static constant",
            Modifier.isStatic(paceThresholdField!!.modifiers)
        )
        assertTrue(
            "DFS iteration limit should be a compile-time static constant",
            Modifier.isStatic(settlementLimitField!!.modifiers)
        )

        // Sanity guard for confidence constants relationship.
        assertTrue(BudgetForecastingEngine.CONFIDENCE_THRESHOLD_HIGH > BudgetForecastingEngine.CONFIDENCE_THRESHOLD_MEDIUM)
        assertTrue(BudgetForecastingEngine.CONFIDENCE_THRESHOLD_MEDIUM > 0.0)
    }

    private fun readNumberConstant(owner: Class<*>, fieldName: String): Number {
        val field = findField(owner, fieldName)
            ?: error("Could not find field '$fieldName' in ${owner.name}")

        return when {
            Modifier.isStatic(field.modifiers) -> field.get(null) as Number
            else -> {
                val receiver = resolveCompanionOrSingletonInstance(owner, field)
                field.get(receiver) as Number
            }
        }
    }

    private fun findField(owner: Class<*>, fieldName: String): Field? {
        owner.declaredFields.firstOrNull { it.name == fieldName }?.let {
            it.isAccessible = true
            return it
        }

        owner.declaredClasses.forEach { nested ->
            findField(nested, fieldName)?.let { return it }
        }

        return null
    }

    private fun resolveCompanionOrSingletonInstance(owner: Class<*>, targetField: Field): Any {
        owner.declaredFields.firstOrNull { it.name == "Companion" }?.let { companionField ->
            companionField.isAccessible = true
            val companion = companionField.get(null)
            if (companion != null && targetField.declaringClass.isInstance(companion)) {
                return companion
            }
        }

        targetField.declaringClass.declaredFields.firstOrNull { it.name == "INSTANCE" }?.let { instanceField ->
            instanceField.isAccessible = true
            val instance = instanceField.get(null)
            if (instance != null) return instance
        }

        error("Could not resolve receiver for non-static field '${targetField.name}'")
    }
}
