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

private val Context.savingsContributionHistoryDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "savings_contribution_history"
)

data class SavingsContributionEvent(
    val goalId: Long,
    val amount: Double,
    val timestamp: Long,
    val source: String? = null
)

class SavingsContributionHistoryRepository(
    private val dataStore: DataStore<Preferences>,
    private val timeProvider: TimeProvider
) {

    private val gson = Gson()

    suspend fun recordContribution(
        goalId: Long,
        amount: Double,
        timestamp: Long = timeProvider.now(),
        source: String? = null
    ): Boolean {
        if (goalId <= 0L) return false
        if (!amount.isFinite() || amount <= 0.0) return false
        if (timestamp <= 0L) return false

        val event = SavingsContributionEvent(
            goalId = goalId,
            amount = amount,
            timestamp = timestamp,
            source = source?.takeIf { it.isNotBlank() }
        )

        dataStore.edit { preferences ->
            val currentState = pruneState(readState(preferences), timeProvider.now())
            val updatedEvents = (currentState.events + event)
                .sortedBy { it.timestamp }
                .takeLast(MAX_EVENTS)

            writeState(preferences, currentState.copy(events = updatedEvents))
        }

        return true
    }

    suspend fun getAllContributions(): List<SavingsContributionEvent> {
        var events = emptyList<SavingsContributionEvent>()

        dataStore.edit { preferences ->
            val currentState = pruneState(readState(preferences), timeProvider.now())
            events = currentState.events.sortedBy { it.timestamp }
            writeState(preferences, currentState)
        }

        return events
    }

    suspend fun getContributionsBetween(
        startTimeInclusive: Long,
        endTimeExclusive: Long
    ): List<SavingsContributionEvent> {
        if (endTimeExclusive <= startTimeInclusive) return emptyList()

        return getAllContributions().filter { contribution ->
            contribution.timestamp >= startTimeInclusive && contribution.timestamp < endTimeExclusive
        }
    }

    suspend fun snapshotJson(): String? {
        return dataStore.data.first()[STATE_KEY]
    }

    companion object {
        private val STATE_KEY = stringPreferencesKey("savings_contribution_history_json")
        private const val RETENTION_DAYS = 730
        private const val MAX_EVENTS = 5000

        fun createDataStore(context: Context): DataStore<Preferences> {
            return context.savingsContributionHistoryDataStore
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
        if (state.events.isEmpty()) {
            preferences.remove(STATE_KEY)
        } else {
            preferences[STATE_KEY] = gson.toJson(state)
        }
    }

    private fun pruneState(state: PersistedState, referenceTime: Long): PersistedState {
        val oldestRetainedTimestamp = TimePeriodUtils.getStartOfDay(
            TimePeriodUtils.addDays(referenceTime, -RETENTION_DAYS)
        )

        return state.copy(
            events = state.events
                .filter { it.timestamp >= oldestRetainedTimestamp }
                .sortedBy { it.timestamp }
                .takeLast(MAX_EVENTS)
        )
    }

    private data class PersistedState(
        val events: List<SavingsContributionEvent> = emptyList()
    )
}
