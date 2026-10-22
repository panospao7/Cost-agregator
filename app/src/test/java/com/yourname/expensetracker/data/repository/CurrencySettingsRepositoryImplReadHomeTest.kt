package com.yourname.expensetracker.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.ReadHomeCurrencyState
import com.yourname.expensetracker.domain.currency.ReadHomeFailure
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * MC 1a-3a: typed read-time home currency.
 *
 * Production-boundary tests drive [CurrencySettingsRepositoryImpl] against its real
 * `currency_settings` DataStore file (same Robolectric + delegate-reset pattern as
 * PrivacySettingsRepositoryImplCorruptionTest). Interface-default tests prove the bounded
 * mapping and CancellationException propagation for repositories that do not override.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CurrencySettingsRepositoryImplReadHomeTest {

    private lateinit var context: Context
    private lateinit var repository: CurrencySettingsRepositoryImpl

    /** Bytes that are not a valid Preferences protobuf (tag 0 is an invalid protobuf tag). */
    private val invalidProtobufBytes =
        byteArrayOf(0x00, 0xFF.toByte(), 0xAB.toByte(), 0xCD.toByte())

    @Before
    fun setUp() {
        resetCurrencyDataStoreSingleton()
        context = ApplicationProvider.getApplicationContext()
        repository = CurrencySettingsRepositoryImpl(context, FakeTimeProvider(1716163200000L))
    }

    @After
    fun tearDown() {
        resetCurrencyDataStoreSingleton()
    }

    // ---------------------------------------------------------------------------------
    // CurrencySettingsRepositoryImpl override
    // ---------------------------------------------------------------------------------

    @Test
    fun impl_unset_emits_available_eur_first_run_default() = runBlocking {
        val state = repository.observeReadHomeCurrency().first()

        assertEquals(ReadHomeCurrencyState.Available(CurrencyCode.EUR, firstRunDefault = true), state)
    }

    @Test
    fun impl_valid_stored_code_emits_available_not_first_run() = runBlocking {
        repository.setHomeCurrency("USD")

        val state = repository.observeReadHomeCurrency().first()

        assertEquals(
            ReadHomeCurrencyState.Available(CurrencyCode.parse("USD")!!, firstRunDefault = false),
            state
        )
    }

    @Test
    fun impl_stored_eur_is_not_reported_as_first_run() = runBlocking {
        repository.setHomeCurrency("EUR")

        val state = repository.observeReadHomeCurrency().first()

        assertEquals(ReadHomeCurrencyState.Available(CurrencyCode.EUR, firstRunDefault = false), state)
    }

    @Test
    fun impl_invalid_stored_code_emits_unavailable_invalid_code_without_raw_text() = runBlocking {
        val raw = "not-a-code"
        repository.setHomeCurrency(raw)

        val state = repository.observeReadHomeCurrency().first()

        assertEquals(ReadHomeCurrencyState.Unavailable(ReadHomeFailure.INVALID_CODE), state)
        assertFalse(
            "Raw stored value must not appear in the state",
            state.toString().contains(raw, ignoreCase = true)
        )
    }

    @Test
    fun impl_unreadable_datastore_emits_unavailable_read_error_without_raw_text() = runBlocking {
        val file = File(File(context.filesDir, "datastore"), "currency_settings.preferences_pb")
        file.parentFile!!.mkdirs()
        file.writeBytes(invalidProtobufBytes)

        val state = repository.observeReadHomeCurrency().first()

        assertEquals(ReadHomeCurrencyState.Unavailable(ReadHomeFailure.READ_ERROR), state)
        assertFalse(
            "Exception text must not appear in the state",
            state.toString().contains("Corruption", ignoreCase = true)
        )
    }

    @Test
    fun impl_never_emits_loading_or_timeout() = runBlocking {
        val states = listOf(
            repository.observeReadHomeCurrency().first(),
            run {
                repository.setHomeCurrency("GBP")
                repository.observeReadHomeCurrency().first()
            },
            run {
                repository.setHomeCurrency("xx9")
                repository.observeReadHomeCurrency().first()
            }
        )

        states.forEach { state ->
            assertFalse("Loading is consumer-applied", state is ReadHomeCurrencyState.Loading)
            if (state is ReadHomeCurrencyState.Unavailable) {
                assertFalse("TIMEOUT is consumer-applied", state.reason == ReadHomeFailure.TIMEOUT)
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Interface default body
    // ---------------------------------------------------------------------------------

    @Test
    fun default_valid_code_maps_to_available() = runBlocking {
        val state = fakeRepository(flowOf("USD")).observeReadHomeCurrency().first()

        assertEquals(
            ReadHomeCurrencyState.Available(CurrencyCode.parse("USD")!!, firstRunDefault = false),
            state
        )
    }

    @Test
    fun default_blank_maps_to_first_run_default() = runBlocking {
        val state = fakeRepository(flowOf("")).observeReadHomeCurrency().first()

        assertEquals(ReadHomeCurrencyState.Available(CurrencyCode.EUR, firstRunDefault = true), state)
    }

    @Test
    fun default_invalid_code_maps_to_invalid_code_without_raw_text() = runBlocking {
        val raw = "not-a-code"
        val state = fakeRepository(flowOf(raw)).observeReadHomeCurrency().first()

        assertEquals(ReadHomeCurrencyState.Unavailable(ReadHomeFailure.INVALID_CODE), state)
        assertFalse(state.toString().contains(raw, ignoreCase = true))
    }

    @Test
    fun default_read_error_maps_to_read_error_without_raw_text() = runBlocking {
        val secret = "secret-datastore-detail"
        val state = fakeRepository(flow<String> { throw RuntimeException(secret) })
            .observeReadHomeCurrency()
            .first()

        assertEquals(ReadHomeCurrencyState.Unavailable(ReadHomeFailure.READ_ERROR), state)
        assertFalse(state.toString().contains(secret))
    }

    @Test
    fun default_rethrows_cancellation_exception_and_does_not_map_it() = runBlocking {
        val repo = fakeRepository(flow<String> { throw CancellationException("cancelled") })

        try {
            repo.observeReadHomeCurrency().first()
            fail("CancellationException must propagate, not be mapped to a state")
        } catch (e: CancellationException) {
            assertNotNull(e)
        }
        Unit
    }

    @Test
    fun default_never_emits_loading_or_timeout() = runBlocking {
        val states = listOf(
            fakeRepository(flowOf("EUR")).observeReadHomeCurrency().first(),
            fakeRepository(flowOf("")).observeReadHomeCurrency().first(),
            fakeRepository(flowOf("bad")).observeReadHomeCurrency().first(),
            fakeRepository(flow<String> { throw IllegalStateException("x") })
                .observeReadHomeCurrency()
                .first()
        )

        states.forEach { state ->
            assertTrue(
                "Only Available/Unavailable may be emitted by a repository",
                state is ReadHomeCurrencyState.Available || state is ReadHomeCurrencyState.Unavailable
            )
            if (state is ReadHomeCurrencyState.Unavailable) {
                assertFalse(state.reason == ReadHomeFailure.TIMEOUT)
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------

    private fun fakeRepository(home: Flow<String>): CurrencySettingsRepository =
        object : CurrencySettingsRepository {
            override fun homeCurrency() = home
            override suspend fun setHomeCurrency(currencyCode: String) {}
            override fun lastRateUpdate() = flowOf(0L)
            override suspend fun setLastRateUpdate(timestamp: Long) {}
            override suspend fun areRatesStale(thresholdMs: Long) = false
            override fun emergencyBuffer() = flowOf(500.0)
            override suspend fun setEmergencyBuffer(amount: Double) {}
            override suspend fun clear() {}
        }

    /**
     * Clears the cached DataStore held by the file-private `preferencesDataStore` delegate
     * (`currencyDataStore$delegate` in the CurrencySettingsRepositoryImpl file class) so the
     * next access re-creates it against the current test's `filesDir`.
     */
    private fun resetCurrencyDataStoreSingleton() {
        val fileClass = Class.forName(
            "com.yourname.expensetracker.data.repository.CurrencySettingsRepositoryImplKt"
        )
        val delegateField = fileClass.declaredFields.firstOrNull {
            it.name.startsWith("currencyDataStore") && it.name.endsWith("\$delegate")
        }
        assertNotNull("currencyDataStore delegate field not found", delegateField)
        delegateField!!.isAccessible = true
        val delegate = delegateField.get(null)
        assertNotNull("currencyDataStore delegate is null", delegate)

        val instanceField = delegate!!.javaClass.declaredFields.firstOrNull { it.name == "INSTANCE" }
        assertNotNull("DataStore delegate INSTANCE cache field not found", instanceField)
        instanceField!!.isAccessible = true
        instanceField.set(delegate, null)
    }
}
