package com.yourname.expensetracker.data.privacy

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R23: the exchange-rate download opt-in is OFF by default, fails closed on
 * corruption defaults, and round-trips through the real DataStore-backed repository.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivacySettingsRepositoryImplExchangeRateTest {

    private lateinit var context: Context
    private val workManager: WorkManager = mockk(relaxed = true)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns workManager
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `exchange rate download defaults to false`() {
        assertFalse(PrivacySettings().exchangeRateDownloadEnabled)
    }

    @Test
    fun `fail closed defaults keep exchange rate download disabled`() {
        assertFalse(PrivacySettings.FAIL_CLOSED_DEFAULTS.exchangeRateDownloadEnabled)
    }

    @Test
    fun `missing key reads false and enabled value round trips through the repository`() = runTest {
        context.privacySettingsDataStore.edit { it.clear() }
        val repository = PrivacySettingsRepositoryImpl(context, FakeTimeProvider(1716163200000L))

        // Key absent (nothing persisted yet) must read as disabled.
        assertFalse(repository.getSettings().exchangeRateDownloadEnabled)

        repository.updateSettings { it.copy(exchangeRateDownloadEnabled = true) }
        assertTrue(repository.getSettings().exchangeRateDownloadEnabled)

        repository.updateSettings { it.copy(exchangeRateDownloadEnabled = false) }
        assertFalse(repository.getSettings().exchangeRateDownloadEnabled)
    }

    @Test
    fun `upgrade datastore with other privacy keys but no exchange key reads exchange download false`() = runTest {
        // Simulate an upgraded install: older privacy keys persisted, the R23 key never written.
        // Seed the shared process-wide delegate directly so the result does not depend on
        // which test first bound the DataStore instance.
        context.privacySettingsDataStore.edit { prefs ->
            prefs.clear()
            prefs[booleanPreferencesKey("cloud_ai_enabled")] = true
            prefs[booleanPreferencesKey("notification_capture_enabled")] = true
        }

        val repository = PrivacySettingsRepositoryImpl(context, FakeTimeProvider(1716163200000L))
        val settings = repository.getSettings()

        assertTrue(settings.cloudAiEnabled)
        assertFalse(settings.exchangeRateDownloadEnabled)
    }
}
