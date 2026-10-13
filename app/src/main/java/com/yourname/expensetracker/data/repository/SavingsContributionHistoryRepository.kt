package com.yourname.expensetracker.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.data.database.dao.SavingsContributionLedgerDao
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.first
import java.io.StringReader

private val Context.savingsContributionHistoryDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "savings_contribution_history"
)

data class SavingsContributionEvent(
    val goalId: Long,
    val amount: Double,
    val timestamp: Long,
    val source: String? = null,
    val currency: String? = null
)

class SavingsContributionHistoryUnavailableException : IllegalStateException("SAVINGS_HISTORY_CORRUPT")

class SavingsContributionHistoryRepository(
    private val dataStore: DataStore<Preferences>,
    private val timeProvider: TimeProvider,
    private val ledgerDao: SavingsContributionLedgerDao? = null
) {

    private val gson = Gson()

    suspend fun recordContribution(
        goalId: Long,
        amount: Double,
        timestamp: Long = timeProvider.now(),
        source: String? = null,
        currency: String? = null
    ): Boolean {
        val event = SavingsContributionEvent(
            goalId = goalId,
            amount = amount,
            timestamp = timestamp,
            source = source,
            currency = currency?.trim()?.uppercase()
        )
        if (!isValidEvent(event)) return false

        var recorded = false
        dataStore.edit { preferences ->
            val state = readState(preferences)
            if (state.isCorrupt) return@edit
            val currentState = pruneState(state, timeProvider.now())
            val updatedEvents = (currentState.events + event)
                .sortedBy { it.timestamp }
                .takeLast(MAX_EVENTS)

            writeState(preferences, currentState.copy(events = updatedEvents))
            recorded = true
        }

        return recorded
    }

    suspend fun getAllContributions(): List<SavingsContributionEvent> {
        val state = readState(dataStore.data.first())
        if (state.isCorrupt) throw SavingsContributionHistoryUnavailableException()
        val events = pruneState(state, timeProvider.now()).events.sortedBy { it.timestamp }

        val ledgerEvents = ledgerDao?.getAll().orEmpty().map { entry ->
            SavingsContributionEvent(
                goalId = entry.goalId,
                amount = entry.amount,
                timestamp = entry.timestamp,
                source = entry.source,
                currency = entry.currency
            )
        }
        if (ledgerEvents.any { !isValidEvent(it) }) {
            throw SavingsContributionHistoryUnavailableException()
        }
        return (events + ledgerEvents).sortedBy { it.timestamp }
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
        private val SUPPORTED_SOURCES = setOf("manual", "sweep", "legacy")

        fun createDataStore(context: Context): DataStore<Preferences> {
            return context.savingsContributionHistoryDataStore
        }
    }

    private fun readState(preferences: Preferences): PersistedState {
        val rawJson = preferences[STATE_KEY] ?: return PersistedState()
        return try {
            // Do not let Gson defaults or scalar coercions manufacture a compatible
            // event. Validate the entire stored history before retention/size pruning.
            JsonReader(StringReader(rawJson)).use { reader ->
                reader.beginObject()
                val fields = mutableSetOf<String>()
                var events: List<SavingsContributionEvent>? = null
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(fields.add(name)) { "SAVINGS_HISTORY_CORRUPT" }
                    when (name) {
                        "events" -> {
                            reader.beginArray()
                            val parsed = mutableListOf<SavingsContributionEvent>()
                            while (reader.hasNext()) parsed += readEvent(reader)
                            reader.endArray()
                            events = parsed
                        }
                        "isCorrupt" -> {
                            require(reader.peek() == JsonToken.BOOLEAN && !reader.nextBoolean()) { "SAVINGS_HISTORY_CORRUPT" }
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                require(reader.peek() == JsonToken.END_DOCUMENT) { "SAVINGS_HISTORY_CORRUPT" }
                PersistedState(events = requireNotNull(events) { "SAVINGS_HISTORY_CORRUPT" })
            }
        } catch (_: Exception) {
            PersistedState(isCorrupt = true)
        }
    }

    private fun readEvent(reader: JsonReader): SavingsContributionEvent {
        reader.beginObject()
        val fields = mutableSetOf<String>()
        var goalId: Long? = null
        var amount: Double? = null
        var timestamp: Long? = null
        var source: String? = null
        var currency: String? = null
        while (reader.hasNext()) {
            val name = reader.nextName()
            require(fields.add(name)) { "SAVINGS_HISTORY_CORRUPT" }
            when (name) {
                "goalId" -> goalId = reader.requiredNumber().toBigDecimal().longValueExact()
                "amount" -> amount = reader.requiredNumber().toDouble()
                "timestamp" -> timestamp = reader.requiredNumber().toBigDecimal().longValueExact()
                "source" -> source = reader.optionalString()
                "currency" -> currency = reader.optionalString()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val event = SavingsContributionEvent(
            goalId = requireNotNull(goalId) { "SAVINGS_HISTORY_CORRUPT" },
            amount = requireNotNull(amount) { "SAVINGS_HISTORY_CORRUPT" },
            timestamp = requireNotNull(timestamp) { "SAVINGS_HISTORY_CORRUPT" },
            source = source,
            currency = currency
        )
        require(isValidEvent(event)) { "SAVINGS_HISTORY_CORRUPT" }
        return event
    }

    private fun JsonReader.requiredNumber(): String {
        require(peek() == JsonToken.NUMBER) { "SAVINGS_HISTORY_CORRUPT" }
        return nextString()
    }

    private fun JsonReader.optionalString(): String? {
        if (peek() == JsonToken.NULL) {
            nextNull()
            return null
        }
        require(peek() == JsonToken.STRING) { "SAVINGS_HISTORY_CORRUPT" }
        return nextString()
    }

    private fun isValidEvent(event: SavingsContributionEvent): Boolean =
        event.goalId > 0L && event.amount.isFinite() && event.amount > 0.0 && event.timestamp > 0L &&
            (event.source == null || event.source in SUPPORTED_SOURCES) &&
            (event.currency == null || CurrencyCode.parse(event.currency)?.let { it in CurrencyCode.ALL_SUPPORTED } == true)

    private fun writeState(preferences: MutablePreferences, state: PersistedState) {
        if (state.isCorrupt) return
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
        val events: List<SavingsContributionEvent> = emptyList(),
        val isCorrupt: Boolean = false
    )
}
