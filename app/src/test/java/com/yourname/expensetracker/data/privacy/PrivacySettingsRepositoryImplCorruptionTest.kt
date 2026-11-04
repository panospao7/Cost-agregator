package com.yourname.expensetracker.data.privacy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Real-boundary proof that corrupted privacy settings fail closed.
 *
 * Unlike a self-built DataStore, these tests drive the production
 * [PrivacySettingsRepositoryImpl] and its production
 * `preferencesDataStore(... corruptionHandler = ReplaceFileCorruptionHandler ...)` delegate:
 * invalid protobuf bytes are written to the real
 * `<filesDir>/datastore/privacy_settings.preferences_pb` BEFORE the first DataStore access,
 * and the repository's public API is asserted.
 *
 * Isolation: the `preferencesDataStore` delegate is a file-level singleton that caches the
 * DataStore (and its in-memory state) for the whole sandbox classloader, while Robolectric
 * gives every test a fresh application and `filesDir`. [resetPrivacyDataStoreSingleton]
 * clears the delegate's cached instance before and after each test so every test performs
 * its own first read against its own file. Each corruption test additionally asserts that
 * the production handler rewrote THIS test's file, which fails if the reset ever stops
 * working (so a stale cached state can never produce a false pass).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivacySettingsRepositoryImplCorruptionTest {

    private lateinit var context: Context
    private val workManager: WorkManager = mockk(relaxed = true)
    private val fakeTimeProvider = FakeTimeProvider(1716163200000L)
    private lateinit var repository: PrivacySettingsRepositoryImpl

    /** Bytes that are not a valid Preferences protobuf (tag 0 is an invalid protobuf tag). */
    private val invalidProtobufBytes =
        byteArrayOf(0x00, 0xFF.toByte(), 0xAB.toByte(), 0xCD.toByte())

    @Before
    fun setUp() {
        resetPrivacyDataStoreSingleton()
        context = ApplicationProvider.getApplicationContext()
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns workManager
        // Constructor reads WorkManager.getInstance(context) — mocked as in WorkerGatingTest.
        repository = PrivacySettingsRepositoryImpl(context, fakeTimeProvider)
    }

    @After
    fun tearDown() {
        unmockkAll()
        resetPrivacyDataStoreSingleton()
    }

    // ---------------------------------------------------------------------------------
    // Corrupted file -> getLoadState()
    // ---------------------------------------------------------------------------------

    @Test
    fun corrupted_datastore_file_getLoadState_is_corrupted_fail_closed_with_all_flags_denied() =
        runBlocking {
            val file = writeCorruptPrivacyFile()

            val state = repository.getLoadState()

            assertProductionHandlerReplacedFile(file)
            assertTrue(
                "Corrupted privacy file must load as CorruptedFailClosed but was $state",
                state is PrivacySettingsLoadState.CorruptedFailClosed
            )
            val settings = (state as PrivacySettingsLoadState.CorruptedFailClosed).settings
            assertFailClosedFlags(settings)
            assertEquals(PrivacySettings.FAIL_CLOSED_DEFAULTS, settings)

            // getSettings() (the effective settings consumers use) must agree.
            assertEquals(PrivacySettings.FAIL_CLOSED_DEFAULTS, repository.getSettings())
        }

    // ---------------------------------------------------------------------------------
    // Corrupted file -> observeLoadState() first emission
    // ---------------------------------------------------------------------------------

    @Test
    fun corrupted_datastore_file_observeLoadState_first_emission_is_corrupted_fail_closed() =
        runBlocking {
            val file = writeCorruptPrivacyFile()

            val first = repository.observeLoadState().first()

            assertProductionHandlerReplacedFile(file)
            assertTrue(
                "First observeLoadState emission must be CorruptedFailClosed but was $first",
                first is PrivacySettingsLoadState.CorruptedFailClosed
            )
            assertFailClosedFlags((first as PrivacySettingsLoadState.CorruptedFailClosed).settings)
            assertEquals(PrivacySettings.FAIL_CLOSED_DEFAULTS, first.settings)
        }

    // ---------------------------------------------------------------------------------
    // Corruption must be distinct from first run (and not permissive)
    // ---------------------------------------------------------------------------------

    /** PRIV-441-16: corruption is distinct from a genuine first run and is not permissive. */
    @Test
    fun corrupted_datastore_file_is_not_reported_as_first_run_or_loaded_and_not_permissive() =
        runBlocking {
            val file = writeCorruptPrivacyFile()

            val state = repository.getLoadState()
            val effective = repository.observeSettings().first()

            assertProductionHandlerReplacedFile(file)
            assertFalse(
                "Corruption must not masquerade as first run (permissive defaults)",
                state is PrivacySettingsLoadState.FirstRunDefault
            )
            assertFalse(
                "Corruption must not masquerade as a normally loaded state",
                state is PrivacySettingsLoadState.Loaded
            )
            // Normal / first-run defaults enable notification capture and STORE_RAW; the
            // effective settings after corruption must differ from them.
            assertNotEquals(PrivacySettings(), effective)
            assertFalse(effective.notificationCaptureEnabled)
            assertEquals(RawStorageMode.DO_NOT_STORE, effective.rawNotificationStorageMode)
            assertEquals(RawStorageMode.DO_NOT_STORE, effective.rawOcrStorageMode)
        }

    /**
     * PRIV-441-16 normal control: with NO corrupt file the same repository reports a genuine
     * first run with the normal (non fail-closed) defaults, so the assertions above discriminate.
     */
    @Test
    fun clean_datastore_without_file_is_first_run_default_not_corruption() = runBlocking {
        val state = repository.getLoadState()

        assertTrue(
            "No persisted file must be FirstRunDefault but was $state",
            state is PrivacySettingsLoadState.FirstRunDefault
        )
        assertEquals(PrivacySettings(), (state as PrivacySettingsLoadState.FirstRunDefault).settings)
        assertNotEquals(PrivacySettings.FAIL_CLOSED_DEFAULTS, state.settings)
    }

    // ---------------------------------------------------------------------------------
    // Saving after corruption (PRIV-6825-04): base is fail-closed, state becomes Loaded
    // ---------------------------------------------------------------------------------

    @Test
    fun updateSettings_after_corruption_transforms_from_fail_closed_base_and_marks_loaded() =
        runBlocking {
            val file = writeCorruptPrivacyFile()
            var transformInput: PrivacySettings? = null

            // Identity-like transform: no toggle changes, so no worker policy is triggered.
            repository.updateSettings { current ->
                transformInput = current
                current.copy()
            }

            // For this save test the rewrite may come from the save itself (not only from the
            // corruption handler); the fail-closed base is proven by the assertions below.
            assertProductionHandlerReplacedFile(file)
            assertEquals(
                "updateSettings must transform from FAIL_CLOSED_DEFAULTS, not permissive defaults",
                PrivacySettings.FAIL_CLOSED_DEFAULTS,
                transformInput
            )
            val after = repository.getLoadState()
            assertTrue(
                "After a successful save the load state must be Loaded but was $after",
                after is PrivacySettingsLoadState.Loaded
            )
            // The save must not resurrect STORE_RAW / capture-enabled defaults.
            assertEquals(
                PrivacySettings.FAIL_CLOSED_DEFAULTS,
                (after as PrivacySettingsLoadState.Loaded).settings
            )
        }

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------

    private fun assertFailClosedFlags(settings: PrivacySettings) {
        assertFalse("notificationCaptureEnabled must be denied", settings.notificationCaptureEnabled)
        assertFalse("cloudAiEnabled must be denied", settings.cloudAiEnabled)
        assertTrue("redactBeforeCloud must stay enabled", settings.redactBeforeCloud)
        assertFalse("receiptImageCloudEnabled must be denied", settings.receiptImageCloudEnabled)
        assertFalse("bankStatementAiEnabled must be denied", settings.bankStatementAiEnabled)
        assertFalse("externalGeocodingEnabled must be denied", settings.externalGeocodingEnabled)
        assertFalse(
            "backgroundLocationBackfillEnabled must be denied",
            settings.backgroundLocationBackfillEnabled
        )
        assertFalse("deviceGpsLocationEnabled must be denied", settings.deviceGpsLocationEnabled)
        assertFalse(
            "debugDataPersistenceEnabled must be denied",
            settings.debugDataPersistenceEnabled
        )
        assertTrue("encryptedBackupEnabled must stay enabled", settings.encryptedBackupEnabled)
        assertEquals(RawStorageMode.DO_NOT_STORE, settings.rawNotificationStorageMode)
        assertEquals(RawStorageMode.DO_NOT_STORE, settings.rawOcrStorageMode)
        assertEquals(RawStorageMode.DO_NOT_STORE, settings.emailReceiptStorageMode)
        assertEquals(RawStorageMode.DO_NOT_STORE, settings.rawBankStatementStorageMode)
    }

    /**
     * Writes invalid protobuf bytes to the repository's real DataStore file
     * (`<filesDir>/datastore/privacy_settings.preferences_pb`) before any DataStore access.
     */
    private fun writeCorruptPrivacyFile(): File {
        val file = File(File(context.filesDir, "datastore"), "privacy_settings.preferences_pb")
        file.parentFile!!.mkdirs()
        file.writeBytes(invalidProtobufBytes)
        assertArrayEquals(invalidProtobufBytes, file.readBytes())
        return file
    }

    /**
     * Proves the production DataStore really accessed THIS test's file: the garbage bytes
     * must no longer be on disk. For read-only tests the replacement can only come from the
     * production corruption handler (it rewrites the unreadable file with its sentinel
     * preferences); for the save test the successful `updateSettings` write also rewrites
     * the file, so there it proves the save reached this test's file. Fails if the DataStore
     * singleton was not reset (a stale cached instance would leave the garbage bytes untouched).
     */
    private fun assertProductionHandlerReplacedFile(file: File) {
        assertTrue("Privacy DataStore file must exist", file.exists())
        assertFalse(
            "Production corruption handler must have replaced the invalid bytes in this test's file",
            file.readBytes().contentEquals(invalidProtobufBytes)
        )
    }

    /**
     * Clears the cached DataStore held by the file-private `preferencesDataStore` delegate
     * (`privacySettingsDataStore$delegate` in the PrivacySettingsRepositoryImpl file class)
     * so the next access re-creates it against the current test's `filesDir`.
     */
    private fun resetPrivacyDataStoreSingleton() {
        val fileClass = Class.forName(
            "com.yourname.expensetracker.data.privacy.PrivacySettingsRepositoryImplKt"
        )
        val delegateField = fileClass.declaredFields.firstOrNull {
            it.name.startsWith("privacySettingsDataStore") && it.name.endsWith("\$delegate")
        }
        assertNotNull("privacySettingsDataStore delegate field not found", delegateField)
        delegateField!!.isAccessible = true
        val delegate = delegateField.get(null)
        assertNotNull("privacySettingsDataStore delegate is null", delegate)

        val instanceField = delegate!!.javaClass.declaredFields.firstOrNull { it.name == "INSTANCE" }
        assertNotNull("DataStore delegate INSTANCE cache field not found", instanceField)
        instanceField!!.isAccessible = true
        instanceField.set(delegate, null)
    }
}
