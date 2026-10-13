package com.yourname.expensetracker.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yourname.expensetracker.data.database.dao.SavingsContributionLedgerDao
import com.yourname.expensetracker.data.database.entity.SavingsContributionLedgerEntry
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SavingsContributionHistoryRepositoryTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    @Test
    fun `recorded contributions survive repository recreation and range queries`() = runTest {
        val timeProvider = FakeTimeProvider.forDate(2026, 4, 15)
        val stateFile = createStateFile()
        val april10 = FakeTimeProvider.forDate(2026, 4, 10, 9, 0).now()
        val april14 = FakeTimeProvider.forDate(2026, 4, 14, 18, 30).now()

        val firstHandle = createRepository(stateFile, timeProvider)
        assertTrue(firstHandle.repository.recordContribution(1L, 25.0, april10, "manual"))
        assertTrue(firstHandle.repository.recordContribution(2L, 40.0, april14, "sweep"))
        assertNotNull(firstHandle.repository.snapshotJson())

        firstHandle.scope.cancel()
        firstHandle.scope.coroutineContext[Job]?.join()
        scopes.remove(firstHandle.scope)

        val recreatedHandle = createRepository(stateFile, timeProvider)
        val allEvents = recreatedHandle.repository.getAllContributions()
        val (monthStart, monthEnd) = TimePeriodUtils.getMonthRange(timeProvider.now())
        val aprilEvents = recreatedHandle.repository.getContributionsBetween(monthStart, monthEnd)

        assertEquals(2, allEvents.size)
        assertEquals(listOf(april10, april14), allEvents.map { it.timestamp })
        assertEquals(2, aprilEvents.size)
        assertEquals(65.0, aprilEvents.sumOf { it.amount }, 0.0001)
    }

    @Test
    fun `pruning removes stale events but preserves current month and streak history`() = runTest {
        val timeProvider = FakeTimeProvider.forDate(2026, 4, 15)
        val handle = createRepository(createStateFile(), timeProvider)
        val staleTimestamp = FakeTimeProvider.forDate(2023, 12, 1, 8, 0).now()
        val yesterday = FakeTimeProvider.forDate(2026, 4, 14, 8, 0).now()
        val today = FakeTimeProvider.forDate(2026, 4, 15, 8, 0).now()

        handle.repository.recordContribution(1L, 10.0, staleTimestamp, "manual")
        handle.repository.recordContribution(1L, 12.0, yesterday, "manual")
        handle.repository.recordContribution(1L, 15.0, today, "manual")

        val allEvents = handle.repository.getAllContributions()

        assertEquals(2, allEvents.size)
        assertFalse(allEvents.any { it.timestamp == staleTimestamp })
        assertEquals(listOf(yesterday, today), allEvents.map { it.timestamp })
    }

    @Test
    fun `reading history does not rewrite legacy datastore bytes`() = runTest {
        val timeProvider = FakeTimeProvider.forDate(2026, 4, 15)
        val handle = createRepository(createStateFile(), timeProvider)
        val staleTimestamp = FakeTimeProvider.forDate(2023, 12, 1, 8, 0).now()
        val before = "{ \"events\": [${validEventJson(staleTimestamp)}] }"
        handle.dataStore.edit { it[stateKey] = before }

        assertTrue(handle.repository.getAllContributions().isEmpty())
        assertEquals(before, handle.repository.snapshotJson())
    }

    @Test
    fun invalidLedgerEventsFailClosedWithoutChangingLegacyHistory() = runTest {
        val clock = FakeTimeProvider.forDate(2026, 4, 15)
        val valid = SavingsContributionLedgerEntry(
            idempotencyKey = "sweep-key", goalId = 1L, amount = 20.0,
            currency = "EUR", timestamp = clock.now(), source = "sweep"
        )
        val invalid = listOf(
            valid.copy(amount = Double.NaN), valid.copy(amount = -1.0),
            valid.copy(goalId = 0L), valid.copy(timestamp = 0L),
            valid.copy(currency = "ZZZ"), valid.copy(source = "uncontrolled payload")
        )
        invalid.forEach { entry ->
            val handle = createRepository(createStateFile(), clock, ledgerDao(listOf(entry)))
            val raw = "{\"events\":[${validEventJson(clock.now())}]}"
            handle.dataStore.edit { it[stateKey] = raw }
            val error = runCatching { handle.repository.getAllContributions() }.exceptionOrNull()
            assertTrue(error is SavingsContributionHistoryUnavailableException)
            assertEquals(raw, handle.repository.snapshotJson())
        }
    }

    @Test
    fun `history combines legacy events with authoritative ledger events`() = runTest {
        val timeProvider = FakeTimeProvider.forDate(2026, 4, 15)
        val ledgerTimestamp = timeProvider.now()
        val ledgerEntry = SavingsContributionLedgerEntry(
            id = 7L,
            idempotencyKey = "sweep-key",
            goalId = 2L,
            amount = 30.0,
            currency = "EUR",
            timestamp = ledgerTimestamp,
            source = "sweep",
            sweepPlanId = 8L
        )
        val handle = createRepository(
            createStateFile(),
            timeProvider,
            ledgerDao(listOf(ledgerEntry))
        )
        val manualTimestamp = ledgerTimestamp - 1_000L
        assertTrue(
            handle.repository.recordContribution(
                goalId = 1L,
                amount = 20.0,
                timestamp = manualTimestamp,
                source = "manual",
                currency = "EUR"
            )
        )

        val events = handle.repository.getAllContributions()

        assertEquals(listOf(1L, 2L), events.map { it.goalId })
        assertEquals(listOf("manual", "sweep"), events.map { it.source })
        assertEquals(listOf(manualTimestamp, ledgerTimestamp), events.map { it.timestamp })
    }

    @Test
    fun `invalid contributions are rejected without persisting`() = runTest {
        val timeProvider = FakeTimeProvider.forDate(2026, 4, 15)
        val handle = createRepository(createStateFile(), timeProvider)
        val (monthStart, monthEnd) = TimePeriodUtils.getMonthRange(timeProvider.now())

        assertFalse(handle.repository.recordContribution(0L, 10.0))
        assertFalse(handle.repository.recordContribution(1L, 0.0))
        assertFalse(handle.repository.recordContribution(1L, Double.NaN))

        assertTrue(handle.repository.getContributionsBetween(monthStart, monthEnd).isEmpty())
    }

    @Test
    fun `corrupt history is preserved and cannot be read as empty history`() = runTest {
        val timeProvider = FakeTimeProvider.forDate(2026, 4, 15)
        val handle = createRepository(createStateFile(), timeProvider)
        val key = stringPreferencesKey("savings_contribution_history_json")
        handle.dataStore.edit { preferences -> preferences[key] = "not-json" }

        assertThrows(SavingsContributionHistoryUnavailableException::class.java) {
            kotlinx.coroutines.runBlocking { handle.repository.getAllContributions() }
        }
        assertEquals("not-json", handle.repository.snapshotJson())
        assertFalse(handle.repository.recordContribution(1L, 10.0, currency = "EUR"))
        assertEquals("not-json", handle.repository.snapshotJson())
    }

    @Test
    fun nullAndIncompatibleHistoryShapesAreNotEmptyHistory() = runTest {
        val handle = createRepository(createStateFile(), FakeTimeProvider.forDate(2026, 4, 15))
        for (raw in listOf(
            "null", "[]", "{}", "", "{\"events\":null}", "{\"events\":{}}",
            "{\"events\":[null]}", "{\"events\":[true]}", "{\"events\":[1]}", "{\"events\":[{}]}",
            "{\"events\":[],\"isCorrupt\":true}", "{\"events\":[],\"isCorrupt\":null}",
            "{\"events\":[],\"isCorrupt\":\"false\"}", "{\"events\":[null],\"events\":[]}",
            "{\"events\":[]} {\"events\":[]}", "{events:[]}"
        )) assertCorruptAndUnchanged(handle, raw)
    }

    @Test
    fun invalidEventValuesAndScalarCoercionsAreRejectedBeforeCleanup() = runTest {
        val clock = FakeTimeProvider.forDate(2026, 4, 15)
        val handle = createRepository(createStateFile(), clock)
        val fields = listOf(
            "goalId" to listOf("null", "0", "-1", "1.5", "9223372036854775808", "\"1\"", "true"),
            "amount" to listOf("null", "0", "-1", "1e309", "NaN", "\"NaN\"", "\"1\"", "true", "[]"),
            "timestamp" to listOf("null", "0", "-1", "1.5", "9223372036854775808", "\"1\"", "true"),
            "source" to listOf("\"\"", "\" \"", "\"MANUAL\"", "\"unknown\"", "\"SECRET financial payload\"", "1", "true", "{}"),
            "currency" to listOf("\"\"", "\"ZZZ\"", "\"UNKNOWN\"", "\"SECRET currency\"", "1", "true", "{}")
        )
        for ((field, invalidValues) in fields) {
            for (value in invalidValues) {
                val event = validEventJson(clock.now())
                    .replace(Regex("\"$field\":(?:\"[^\"]*\"|[^,}]+)"), "\"$field\":$value")
                assertCorruptAndUnchanged(handle, "{\"events\":[$event]}")
            }
        }
        for (field in listOf("goalId", "amount", "timestamp")) {
            val event = validEventJson(clock.now())
                .replace(Regex("\"$field\":(?:\"[^\"]*\"|[^,}]+),?"), "")
                .replace(",}", "}")
            assertCorruptAndUnchanged(handle, "{\"events\":[$event]}")
        }
        val duplicateAmount = validEventJson(clock.now()).replace("\"amount\":10.0", "\"amount\":-1,\"amount\":10.0")
        assertCorruptAndUnchanged(handle, "{\"events\":[$duplicateAmount]}")
    }

    @Test
    fun everyEventIsValidatedBeforeRetentionAndEventCountPruning() = runTest {
        val clock = FakeTimeProvider.forDate(2026, 4, 15)
        val handle = createRepository(createStateFile(), clock)
        val valid = validEventJson(clock.now())
        val invalidStale = validEventJson(1L).replace("\"amount\":10.0", "\"amount\":-1")
        assertCorruptAndUnchanged(handle, "{\"events\":[$valid,$invalidStale]}")
        val overLimit = "{\"events\":[$invalidStale," + List(5000) { valid }.joinToString(",") + "]}"
        assertCorruptAndUnchanged(handle, overLimit)
    }

    @Test
    fun semanticCorruptionSurvivesRepositoryRecreationWithoutOverwrite() = runTest {
        val clock = FakeTimeProvider.forDate(2026, 4, 15)
        val file = createStateFile()
        val first = createRepository(file, clock)
        val raw = "{\"events\":[${validEventJson(clock.now())},null]}"
        assertCorruptAndUnchanged(first, raw)
        first.scope.cancel()
        first.scope.coroutineContext[Job]?.join()
        scopes.remove(first.scope)
        val recreated = createRepository(file, clock)
        val error = runCatching { recreated.repository.getAllContributions() }.exceptionOrNull()
        assertTrue(error is SavingsContributionHistoryUnavailableException)
        assertEquals(raw, recreated.repository.snapshotJson())
        assertFalse(recreated.repository.recordContribution(1L, 10.0, source = "manual", currency = "EUR"))
        assertEquals(raw, recreated.repository.snapshotJson())
    }

    @Test
    fun absentAndExplicitEmptyHistoryRemainGenuineEmptyHistory() = runTest {
        val handle = createRepository(createStateFile(), FakeTimeProvider.forDate(2026, 4, 15))
        assertTrue(handle.repository.getAllContributions().isEmpty())
        assertNull(handle.repository.snapshotJson())
        for (raw in listOf("{\"events\":[]}", "{\"events\":[],\"isCorrupt\":false}")) {
            handle.dataStore.edit { it[stateKey] = raw }
            assertTrue(handle.repository.getAllContributions().isEmpty())
            assertEquals(raw, handle.repository.snapshotJson())
            assertTrue(handle.repository.recordContribution(1L, 10.0, source = "manual", currency = "EUR"))
        }
    }

    @Test
    fun missingAndNullLegacyCurrencyStayUnknownButNotCorrupt() = runTest {
        val clock = FakeTimeProvider.forDate(2026, 4, 15)
        val handle = createRepository(createStateFile(), clock)
        for (currencyField in listOf("", ",\"currency\":null")) {
            handle.dataStore.edit {
                it[stateKey] = "{\"events\":[{\"goalId\":1,\"amount\":10.0,\"timestamp\":${clock.now()}$currencyField}]}"
            }
            val event = handle.repository.getAllContributions().single()
            assertNull(event.currency)
            assertNull(event.source)
            assertTrue(handle.repository.recordContribution(1L, 5.0, source = "manual", currency = "USD"))
            assertEquals(listOf(null, "USD"), handle.repository.getAllContributions().map { it.currency })
        }
    }

    @Test
    fun rejectedNewInputsCannotChangeExistingValidHistory() = runTest {
        val clock = FakeTimeProvider.forDate(2026, 4, 15)
        val handle = createRepository(createStateFile(), clock)
        assertTrue(handle.repository.recordContribution(1L, 10.0, source = "manual", currency = "EUR"))
        val before = handle.repository.snapshotJson()
        for (id in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertFalse(handle.repository.recordContribution(id, 10.0, source = "manual", currency = "EUR"))
        }
        for (amount in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFalse(handle.repository.recordContribution(1L, amount, source = "manual", currency = "EUR"))
        }
        for (timestamp in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertFalse(handle.repository.recordContribution(1L, 10.0, timestamp, "manual", "EUR"))
        }
        for (source in listOf("", " ", "MANUAL", "unknown", "SECRET financial payload", "x".repeat(500))) {
            assertFalse(handle.repository.recordContribution(1L, 10.0, source = source, currency = "EUR"))
        }
        for (currency in listOf("", "ZZZ", "SECRET currency")) {
            assertFalse(handle.repository.recordContribution(1L, 10.0, source = "manual", currency = currency))
        }
        assertEquals(before, handle.repository.snapshotJson())
    }

    @Test
    fun finitePositiveAndPositiveLongBoundariesRoundTripWithoutCoercion() = runTest {
        // Preserve the existing positive epoch-millis contract, not an invented
        // future-date cutoff. The injected retention reference keeps timestamp 1.
        val handle = createRepository(createStateFile(), FakeTimeProvider(1L))
        assertTrue(handle.repository.recordContribution(1L, Double.MIN_VALUE, 1L, "manual", "EUR"))
        assertTrue(handle.repository.recordContribution(Long.MAX_VALUE, Double.MAX_VALUE, Long.MAX_VALUE, "legacy", "GBP"))
        val events = handle.repository.getAllContributions()
        assertEquals(listOf(1L, Long.MAX_VALUE), events.map { it.goalId })
        assertEquals(listOf(1L, Long.MAX_VALUE), events.map { it.timestamp })
        assertEquals(Double.MIN_VALUE, events.first().amount, 0.0)
        assertEquals(Double.MAX_VALUE, events.last().amount, 0.0)
    }

    private val stateKey = stringPreferencesKey("savings_contribution_history_json")

    private fun validEventJson(timestamp: Long) =
        "{\"goalId\":1,\"amount\":10.0,\"timestamp\":$timestamp,\"source\":\"manual\",\"currency\":\"EUR\"}"

    private suspend fun assertCorruptAndUnchanged(handle: RepositoryHandle, raw: String) {
        handle.dataStore.edit { it[stateKey] = raw }
        val error = runCatching { handle.repository.getAllContributions() }.exceptionOrNull()
        assertTrue(error is SavingsContributionHistoryUnavailableException)
        assertEquals("SAVINGS_HISTORY_CORRUPT", error?.message)
        assertEquals(raw, handle.repository.snapshotJson())
        assertFalse(handle.repository.recordContribution(1L, 10.0, source = "manual", currency = "EUR"))
        assertEquals(raw, handle.repository.snapshotJson())
    }

    private fun createRepository(
        stateFile: File,
        timeProvider: FakeTimeProvider,
        ledgerDao: SavingsContributionLedgerDao? = null
    ): RepositoryHandle {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopes += scope
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { stateFile }
        )
        return RepositoryHandle(
            repository = SavingsContributionHistoryRepository(dataStore, timeProvider, ledgerDao),
            scope = scope,
            dataStore = dataStore
        )
    }

    private fun createStateFile(): File {
        return Files.createTempFile("savings-contribution-history", ".preferences_pb").toFile()
    }

    private fun ledgerDao(
        entries: List<SavingsContributionLedgerEntry>
    ): SavingsContributionLedgerDao = object : SavingsContributionLedgerDao {
        override suspend fun insert(entry: SavingsContributionLedgerEntry): Long =
            throw UnsupportedOperationException("read-only test ledger")

        override suspend fun getByIdempotencyKey(idempotencyKey: String): SavingsContributionLedgerEntry? =
            entries.firstOrNull { it.idempotencyKey == idempotencyKey }

        override suspend fun getAll(): List<SavingsContributionLedgerEntry> = entries
    }

    private data class RepositoryHandle(
        val repository: SavingsContributionHistoryRepository,
        val scope: CoroutineScope,
        val dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    )
}
