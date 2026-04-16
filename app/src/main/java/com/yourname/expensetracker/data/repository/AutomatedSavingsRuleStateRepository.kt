package com.yourname.expensetracker.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.Locale

private val Context.automatedSavingsRuleStateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "automated_savings_rule_state"
)

class AutomatedSavingsRuleStateRepository(
    private val dataStore: DataStore<Preferences>,
    private val timeProvider: TimeProvider
) {

    private val gson = Gson()

    suspend fun reserveWeeklyNoSpendReward(ruleStableKey: String, weekStart: Long): Boolean {
        if (ruleStableKey.isBlank()) return false

        var reserved = false
        dataStore.edit { preferences ->
            val currentState = pruneState(readState(preferences), timeProvider.now())
            val alreadyReserved = currentState.weeklyRewards.any {
                it.ruleStableKey == ruleStableKey && it.weekStart == weekStart
            }

            val updatedState = if (alreadyReserved) {
                currentState
            } else {
                reserved = true
                currentState.copy(
                    weeklyRewards = currentState.weeklyRewards + WeeklyRewardReservation(
                        ruleStableKey = ruleStableKey,
                        weekStart = weekStart,
                        reservedAt = timeProvider.now()
                    )
                )
            }

            writeState(preferences, updatedState)
        }

        return reserved
    }

    suspend fun reserveWeeklyNoSpendRewardWithinMonthlyCap(
        ruleStableKey: String,
        weekStart: Long,
        yearMonth: String,
        requestedAmount: Double,
        maximumPerMonth: Double?
    ): WeeklyRewardReservationResult {
        if (ruleStableKey.isBlank()) return WeeklyRewardReservationResult(false, 0.0)
        if (!requestedAmount.isFinite() || requestedAmount <= 0.0) {
            return WeeklyRewardReservationResult(false, 0.0)
        }
        if (maximumPerMonth != null && (!maximumPerMonth.isFinite() || maximumPerMonth <= 0.0)) {
            return WeeklyRewardReservationResult(false, 0.0)
        }

        var reservationResult = WeeklyRewardReservationResult(false, 0.0)
        dataStore.edit { preferences ->
            val currentTime = timeProvider.now()
            val currentState = pruneState(readState(preferences), currentTime)
            val alreadyReserved = currentState.weeklyRewards.any {
                it.ruleStableKey == ruleStableKey && it.weekStart == weekStart
            }

            val updatedState = if (alreadyReserved) {
                currentState
            } else {
                val existingUsage = currentState.monthlyCapUsage.firstOrNull {
                    it.ruleStableKey == ruleStableKey && it.yearMonth == yearMonth
                }
                val usedAmount = existingUsage?.consumedAmount ?: 0.0
                val allowedAmount = maximumPerMonth
                    ?.let { minOf(requestedAmount, (it - usedAmount).coerceAtLeast(0.0)) }
                    ?: requestedAmount

                if (allowedAmount > 0.0) {
                    reservationResult = WeeklyRewardReservationResult(true, allowedAmount)
                    val updatedMonthlyCapUsage = if (maximumPerMonth != null) {
                        val updatedUsage = MonthlyCapUsage(
                            ruleStableKey = ruleStableKey,
                            yearMonth = yearMonth,
                            consumedAmount = usedAmount + allowedAmount,
                            updatedAt = currentTime
                        )
                        currentState.monthlyCapUsage
                            .filterNot { it.ruleStableKey == ruleStableKey && it.yearMonth == yearMonth } + updatedUsage
                    } else {
                        currentState.monthlyCapUsage
                    }

                    currentState.copy(
                        weeklyRewards = currentState.weeklyRewards + WeeklyRewardReservation(
                            ruleStableKey = ruleStableKey,
                            weekStart = weekStart,
                            reservedAt = currentTime
                        ),
                        monthlyCapUsage = updatedMonthlyCapUsage
                    )
                } else {
                    currentState
                }
            }

            writeState(preferences, updatedState)
        }

        return reservationResult
    }

    suspend fun hasWeeklyNoSpendRewardReservation(ruleStableKey: String, weekStart: Long): Boolean {
        var reserved = false
        dataStore.edit { preferences ->
            val currentState = pruneState(readState(preferences), timeProvider.now())
            reserved = currentState.weeklyRewards.any {
                it.ruleStableKey == ruleStableKey && it.weekStart == weekStart
            }
            writeState(preferences, currentState)
        }
        return reserved
    }

    suspend fun getMonthlyConsumed(ruleStableKey: String, yearMonth: String): Double {
        var consumed = 0.0
        dataStore.edit { preferences ->
            val currentState = pruneState(readState(preferences), timeProvider.now())
            consumed = currentState.monthlyCapUsage
                .firstOrNull { it.ruleStableKey == ruleStableKey && it.yearMonth == yearMonth }
                ?.consumedAmount
                ?: 0.0
            writeState(preferences, currentState)
        }
        return consumed
    }

    suspend fun consumeMonthlyAmountWithinCap(
        ruleStableKey: String,
        yearMonth: String,
        requestedAmount: Double,
        maximumPerMonth: Double
    ): Double {
        if (ruleStableKey.isBlank()) return 0.0
        if (!requestedAmount.isFinite() || requestedAmount <= 0.0) return 0.0
        if (!maximumPerMonth.isFinite() || maximumPerMonth <= 0.0) return 0.0

        var allowedAmount = 0.0
        dataStore.edit { preferences ->
            val currentState = pruneState(readState(preferences), timeProvider.now())
            val existingUsage = currentState.monthlyCapUsage.firstOrNull {
                it.ruleStableKey == ruleStableKey && it.yearMonth == yearMonth
            }

            val usedAmount = existingUsage?.consumedAmount ?: 0.0
            val remainingAllowance = (maximumPerMonth - usedAmount).coerceAtLeast(0.0)
            allowedAmount = minOf(requestedAmount, remainingAllowance)

            val updatedState = if (allowedAmount > 0.0) {
                val updatedUsage = MonthlyCapUsage(
                    ruleStableKey = ruleStableKey,
                    yearMonth = yearMonth,
                    consumedAmount = usedAmount + allowedAmount,
                    updatedAt = timeProvider.now()
                )
                currentState.copy(
                    monthlyCapUsage = currentState.monthlyCapUsage
                        .filterNot { it.ruleStableKey == ruleStableKey && it.yearMonth == yearMonth } + updatedUsage
                )
            } else {
                currentState
            }

            writeState(preferences, updatedState)
        }

        return allowedAmount
    }

    suspend fun snapshotJson(): String? {
        return dataStore.data.first()[STATE_KEY]
    }

    companion object {
        private val STATE_KEY = stringPreferencesKey("automated_savings_rule_state_json")
        private const val WEEKS_TO_KEEP = 2
        private const val MONTHS_TO_KEEP = 2

        fun createDataStore(context: Context): DataStore<Preferences> {
            return context.automatedSavingsRuleStateDataStore
        }

        internal fun buildYearMonthKey(timestamp: Long): String {
            val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
            return String.format(
                Locale.US,
                "%04d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1
            )
        }
    }

    private fun readState(preferences: Preferences): PersistedState {
        val rawJson = preferences[STATE_KEY] ?: return PersistedState()
        return try {
            gson.fromJson(rawJson, PersistedState::class.java) ?: PersistedState()
        } catch (_: Exception) {
            PersistedState()
        }
    }

    private fun writeState(preferences: MutablePreferences, state: PersistedState) {
        if (state.weeklyRewards.isEmpty() && state.monthlyCapUsage.isEmpty()) {
            preferences.remove(STATE_KEY)
        } else {
            preferences[STATE_KEY] = gson.toJson(state)
        }
    }

    private fun pruneState(state: PersistedState, referenceTime: Long): PersistedState {
        val currentWeekStart = TimePeriodUtils.getWeekRange(referenceTime).first
        val oldestRetainedWeekStart = TimePeriodUtils.addDays(currentWeekStart, -7 * (WEEKS_TO_KEEP - 1))
        val retainedMonths = (0 until MONTHS_TO_KEEP)
            .map { offset -> buildYearMonthKey(TimePeriodUtils.addMonths(referenceTime, -offset)) }
            .toSet()

        return state.copy(
            weeklyRewards = state.weeklyRewards.filter { it.weekStart >= oldestRetainedWeekStart },
            monthlyCapUsage = state.monthlyCapUsage.filter { it.yearMonth in retainedMonths }
        )
    }

    private data class PersistedState(
        val weeklyRewards: List<WeeklyRewardReservation> = emptyList(),
        val monthlyCapUsage: List<MonthlyCapUsage> = emptyList()
    )

    private data class WeeklyRewardReservation(
        val ruleStableKey: String,
        val weekStart: Long,
        val reservedAt: Long
    )

    private data class MonthlyCapUsage(
        val ruleStableKey: String,
        val yearMonth: String,
        val consumedAmount: Double,
        val updatedAt: Long
    )
}

data class WeeklyRewardReservationResult(
    val reserved: Boolean,
    val allowedAmount: Double
)
