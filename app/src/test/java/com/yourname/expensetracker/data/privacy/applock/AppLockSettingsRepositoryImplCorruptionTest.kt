package com.yourname.expensetracker.data.privacy.applock

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.domain.privacy.applock.AppLockPrivacySettings
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsWriteResult
import com.yourname.expensetracker.domain.privacy.applock.AppLockWriteRejection
import com.yourname.expensetracker.domain.privacy.applock.AutoLockTimeout
import com.yourname.expensetracker.domain.privacy.applock.EffectivePrivacyValues
import com.yourname.expensetracker.domain.privacy.applock.SettingsUnreadableCause
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real-boundary proof that a corrupted app-lock settings file fails closed (INV-2, INV-44,
 * AE-51 store part: the CORRUPTED sentinel persists until an explicit re-save).
 *
 * Drives the production [AppLockSettingsRepositoryImpl] and its production
 * `preferencesDataStore(... corruptionHandler = ReplaceFileCorruptionHandler ...)` delegate:
 * invalid protobuf bytes are written to the real
 * `<filesDir>/datastore/app_lock_privacy_settings.preferences_pb` BEFORE the first DataStore access.
 *
 * Isolation: the delegate is a file-level singleton that caches the DataStore for the whole sandbox
 * classloader, while Robolectric gives every test a fresh `filesDir`. [resetDataStoreSingleton]
 * clears the cached instance before and after each test. A "cold start" inside a test is simulated
 * by [coldStartFromDisk]: a second repository over a copy of the bytes then on disk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppLockSettingsRepositoryImplCorruptionTest {

    private lateinit var context: Context
    private lateinit var repository: AppLockSettingsRepositoryImpl
    private val coldStartScopes = mutableListOf<CoroutineScope>()

    /** Bytes that are not a valid Preferences protobuf (tag 0 is an invalid protobuf tag). */
    private val invalidProtobufBytes =
        byteArrayOf(0x00, 0xFF.toByte(), 0xAB.toByte(), 0xCD.toByte())

    @Before
    fun setUp() {
        resetDataStoreSingleton()
        context = ApplicationProvider.getApplicationContext()
        repository = AppLockSettingsRepositoryImpl(context)
    }

    @After
    fun tearDown() {
        coldStartScopes.forEach { it.cancel() }
        coldStartScopes.clear()
        resetDataStoreSingleton()
    }

    @Test
    fun corrupted_file_getLoadState_is_unreadable_corrupted_never_defaults() = runBlocking {
        val file = writeCorruptFile()

        val state = repository.getLoadState()

        assertHandlerReplacedFile(file)
        assertEquals(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED), state)
    }

    @Test
    fun corrupted_file_observeLoadState_first_emission_is_unreadable_corrupted() = runBlocking {
        val file = writeCorruptFile()

        val first = repository.observeLoadState().first()

        assertHandlerReplacedFile(file)
        assertEquals(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED), first)
    }

    @Test
    fun corruption_is_not_first_run_not_loaded_and_effective_values_are_fail_closed() = runBlocking {
        val file = writeCorruptFile()

        val state = repository.getLoadState()

        assertHandlerReplacedFile(file)
        assertFalse(state is AppLockSettingsLoadState.FirstRunDefault)
        assertFalse(state is AppLockSettingsLoadState.Loaded)
        assertTrue(EffectivePrivacyValues.effectiveDiscreet(state))
        assertEquals(AppLockPrivacySettings.FAIL_CLOSED, EffectivePrivacyValues.effectiveSettings(state))
    }

    /** Normal control: with NO corrupt file the same repository reports a genuine first run. */
    @Test
    fun clean_store_without_file_is_first_run_not_corruption() = runBlocking {
        assertEquals(
            AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings.DEFAULTS),
            repository.getLoadState()
        )
    }

    @Test
    fun sentinel_persists_across_cold_starts_until_an_explicit_resave() = runBlocking {
        val file = writeCorruptFile()
        assertEquals(
            AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED),
            repository.getLoadState()
        )
        assertHandlerReplacedFile(file)

        // Cold starts 1 and 2 read the bytes now on disk: the handler-written sentinel is persisted,
        // so the state is not "healed" by having been read once.
        repeat(2) {
            assertEquals(
                AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED),
                coldStartFromDisk(file).getLoadState()
            )
        }
    }

    @Test
    fun per_field_write_after_corruption_is_rejected_and_sentinel_remains() = runBlocking {
        val file = writeCorruptFile()

        val result = repository.setDiscreetNotifications(false)

        assertHandlerReplacedFile(file)
        assertEquals(AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE), result)
        assertEquals(
            AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED),
            repository.getLoadState()
        )
        // Still corrupted after a cold start: the rejected write did not clear the sentinel.
        assertEquals(
            AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED),
            coldStartFromDisk(file).getLoadState()
        )
    }

    @Test
    fun reviewSave_after_corruption_clears_the_sentinel_and_survives_a_cold_start() = runBlocking {
        val file = writeCorruptFile()

        val result = repository.reviewSave(AppLockPrivacySettings(discreetNotifications = false))

        assertHandlerReplacedFile(file)
        assertEquals(
            AppLockSettingsWriteResult.Committed(AppLockPrivacySettings(discreetNotifications = false)),
            result
        )
        assertEquals(
            AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreetNotifications = false)),
            repository.getLoadState()
        )

        assertEquals(
            AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreetNotifications = false)),
            coldStartFromDisk(file).getLoadState()
        )
    }

    @Test
    fun corruption_effective_values_are_the_full_1b_fail_closed_record() = runBlocking {
        val file = writeCorruptFile()

        val state = repository.getLoadState()

        assertHandlerReplacedFile(file)
        val effective = EffectivePrivacyValues.effectiveSettings(state)
        assertTrue(effective.appLockEnabled)
        assertEquals(AutoLockTimeout.IMMEDIATE, effective.autoLockTimeout)
        assertTrue(effective.secureScreen)
        assertTrue(EffectivePrivacyValues.effectiveSecureScreen(state, resolving = false))
        assertTrue(EffectivePrivacyValues.effectiveLockEnabled(state))
    }

    @Test
    fun reviewSave_of_a_1b_record_after_corruption_clears_the_sentinel_and_survives_a_cold_start() = runBlocking {
        val file = writeCorruptFile()
        val record = AppLockPrivacySettings(
            discreetNotifications = false,
            appLockEnabled = true,
            autoLockTimeout = AutoLockTimeout.M5,
            secureScreen = true
        )

        val result = repository.reviewSave(record)

        assertHandlerReplacedFile(file)
        assertEquals(AppLockSettingsWriteResult.Committed(record), result)
        assertEquals(AppLockSettingsLoadState.Loaded(record), coldStartFromDisk(file).getLoadState())
    }

    @Test
    fun store_uses_its_own_file_and_never_touches_the_privacy_settings_store() = runBlocking {
        repository.setDiscreetNotifications(true)

        val dir = File(context.filesDir, "datastore")
        assertTrue(File(dir, "app_lock_privacy_settings.preferences_pb").exists())
        assertFalse(File(dir, "privacy_settings.preferences_pb").exists())
    }

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------

    /**
     * Simulates a cold start: a second repository over a COPY of the bytes currently on disk. (A second
     * DataStore on the same path would clash with the still-active production instance, so the bytes are
     * copied to a private file; the copy is a plain store with no corruption handler, so a sentinel can
     * only be seen if it was really persisted.)
     */
    private fun coldStartFromDisk(file: File): AppLockSettingsRepositoryImpl {
        val copy = File(context.cacheDir, "cold_start_${coldStartScopes.size}.preferences_pb")
        copy.parentFile!!.mkdirs()
        copy.writeBytes(file.readBytes())
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        coldStartScopes += scope
        return AppLockSettingsRepositoryImpl(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { copy })
        )
    }

    private fun writeCorruptFile(): File {
        val file = File(File(context.filesDir, "datastore"), "app_lock_privacy_settings.preferences_pb")
        file.parentFile!!.mkdirs()
        file.writeBytes(invalidProtobufBytes)
        assertArrayEquals(invalidProtobufBytes, file.readBytes())
        return file
    }

    /** The production handler must have rewritten THIS test's file (fails if the reset stops working). */
    private fun assertHandlerReplacedFile(file: File) {
        assertTrue("App-lock DataStore file must exist", file.exists())
        assertFalse(
            "Production corruption handler must have replaced the invalid bytes in this test's file",
            file.readBytes().contentEquals(invalidProtobufBytes)
        )
    }

    /**
     * Clears the cached DataStore held by the file-private `preferencesDataStore` delegate
     * (`appLockPrivacyDataStore$delegate` in the AppLockSettingsRepositoryImpl file class) so the
     * next access re-creates it against the current `filesDir` and re-reads the file from disk.
     */
    private fun resetDataStoreSingleton() {
        val fileClass = Class.forName(
            "com.yourname.expensetracker.data.privacy.applock.AppLockSettingsRepositoryImplKt"
        )
        val delegateField = fileClass.declaredFields.firstOrNull {
            it.name.startsWith("appLockPrivacyDataStore") && it.name.endsWith("\$delegate")
        }
        assertNotNull("appLockPrivacyDataStore delegate field not found", delegateField)
        delegateField!!.isAccessible = true
        val delegate = delegateField.get(null)
        assertNotNull("appLockPrivacyDataStore delegate is null", delegate)

        val instanceField = delegate!!.javaClass.declaredFields.firstOrNull { it.name == "INSTANCE" }
        assertNotNull("DataStore delegate INSTANCE cache field not found", instanceField)
        instanceField!!.isAccessible = true
        instanceField.set(delegate, null)
    }
}
