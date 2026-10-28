package com.yourname.expensetracker.data.privacy.applock

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yourname.expensetracker.domain.privacy.applock.AppLockPrivacySettings
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingField
import com.yourname.expensetracker.domain.privacy.applock.AutoLockTimeout
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsWriteResult
import com.yourname.expensetracker.domain.privacy.applock.AppLockWriteRejection
import com.yourname.expensetracker.domain.privacy.applock.SettingsUnreadableCause
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Store-level behaviour of [AppLockSettingsRepositoryImpl] over a real file-backed DataStore
 * (INV-2, INV-44, INV-46, spec 2.1 defaults). Fault injection wraps the same real store so the
 * "committed value kept" assertions run against real persisted bytes.
 *
 * Production-delegate corruption behaviour is covered by [AppLockSettingsRepositoryImplCorruptionTest].
 */
class AppLockSettingsRepositoryImplTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var real: DataStore<Preferences>
    private lateinit var faulty: FaultyStore
    private lateinit var repository: AppLockSettingsRepositoryImpl

    private val GATE_TIMEOUT_MS = 10_000L
    private val markerKey = stringPreferencesKey("_app_lock_load_state")
    private val discreetKey = booleanPreferencesKey("discreet_notifications")

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        real = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(tmp.root, "app_lock_test.preferences_pb") }
        )
        faulty = FaultyStore(real)
        repository = AppLockSettingsRepositoryImpl(faulty)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ---------------------------------------------------------------------------------
    // Defaults (spec 2.1)
    // ---------------------------------------------------------------------------------

    @Test
    fun empty_store_is_first_run_with_normal_defaults() = runBlocking {
        val state = repository.getLoadState()

        assertEquals(AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings.DEFAULTS), state)
        assertFalse((state as AppLockSettingsLoadState.FirstRunDefault).settings.discreetNotifications)
    }

    @Test
    fun absent_discreet_key_with_normal_marker_takes_the_field_default_and_is_loaded() = runBlocking {
        real.edit { it[markerKey] = "NORMAL" }

        assertEquals(
            AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreetNotifications = false)),
            repository.getLoadState()
        )
    }

    @Test
    fun unknown_future_key_without_marker_does_not_reset_to_unreadable_and_uses_defaults() = runBlocking {
        // A newer app version adding a key must never reset or poison the store (spec 2.1).
        real.edit { it[booleanPreferencesKey("some_future_field")] = true }

        assertEquals(
            AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings.DEFAULTS),
            repository.getLoadState()
        )
    }

    @Test
    fun invalid_discreet_value_maps_to_most_restrictive_true_and_is_loaded() = runBlocking {
        real.edit {
            it[markerKey] = "NORMAL"
            it[stringPreferencesKey("discreet_notifications")] = "not-a-boolean"
        }

        assertEquals(
            AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreetNotifications = true)),
            repository.getLoadState()
        )
    }

    @Test
    fun unknown_marker_token_is_unreadable_invalid_value_never_defaults() = runBlocking {
        real.edit {
            it[markerKey] = "SOMETHING_ELSE"
            it[discreetKey] = false
        }

        assertEquals(
            AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.INVALID_VALUE),
            repository.getLoadState()
        )
    }

    @Test
    fun non_string_marker_is_unreadable_invalid_value() = runBlocking {
        real.edit { it[booleanPreferencesKey("_app_lock_load_state")] = true }

        assertEquals(
            AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.INVALID_VALUE),
            repository.getLoadState()
        )
    }

    // ---------------------------------------------------------------------------------
    // AE-7 (store part): IO error is Unreadable(IO_ERROR), never defaults
    // ---------------------------------------------------------------------------------

    @Test
    fun io_error_on_read_is_unreadable_io_error() = runBlocking {
        faulty.failRead = IOException("disk")

        assertEquals(
            AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.IO_ERROR),
            repository.getLoadState()
        )
    }

    @Test
    fun io_error_on_observe_emits_unreadable_io_error_not_defaults() = runBlocking {
        faulty.failRead = IOException("disk")

        assertEquals(
            AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.IO_ERROR),
            repository.observeLoadState().first()
        )
    }

    @Test
    fun non_io_read_failure_propagates_and_is_not_swallowed() = runBlocking {
        faulty.failRead = IllegalStateException("boom")

        var thrown: Throwable? = null
        try {
            repository.getLoadState()
        } catch (e: IllegalStateException) {
            thrown = e
        }
        assertTrue("non-IO read failure must propagate", thrown is IllegalStateException)
    }

    @Test
    fun cancellation_during_read_propagates() = runBlocking {
        faulty.failRead = CancellationException("cancelled")

        var propagated = false
        try {
            repository.getLoadState()
        } catch (e: CancellationException) {
            propagated = true
        }
        assertTrue("CancellationException must propagate from getLoadState", propagated)
    }

    // S20-d (D4, owner item 51a): the unforced `setDiscreetNotifications` is retired, together with its store-level
    // cases. The equivalent behaviours stay covered by the setAppLockEnabled_* and forced-aware cases below.

    @Test
    fun observe_emits_committed_state_only_and_a_failed_write_changes_nothing() = runBlocking {
        // S20-d: ported from the retired unforced write to the forced-aware write (the lock is off, so it commits).
        repository.setDiscreetNotificationsIfNotForced(false)
        faulty.failWrite = IOException("disk full")
        repository.setDiscreetNotificationsIfNotForced(true)

        val observed = repository.observeLoadState().first()

        assertEquals(
            AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreetNotifications = false)),
            observed
        )
    }

    @Test
    fun observe_reflects_a_committed_write() = runBlocking {
        // S20-d: ported from the retired unforced write to the forced-aware write (the lock is off, so it commits).
        repository.setDiscreetNotificationsIfNotForced(true)

        assertEquals(
            AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreetNotifications = true)),
            repository.observeLoadState().first()
        )
    }

    // ---------------------------------------------------------------------------------
    // AE-85 / AE-89 (store part): review-save
    // ---------------------------------------------------------------------------------

    @Test
    fun reviewSave_clears_the_sentinel_with_one_atomic_complete_record() = runBlocking {
        real.edit {
            it[markerKey] = "CORRUPTED"
            it[booleanPreferencesKey("stale_garbage")] = true
        }

        val result = repository.reviewSave(AppLockPrivacySettings(discreetNotifications = false))

        assertEquals(
            AppLockSettingsWriteResult.Committed(AppLockPrivacySettings(discreetNotifications = false)),
            result
        )
        val stored = real.data.first().asMap().mapKeys { it.key.name }
        assertEquals(
            "review-save must replace the whole store with a complete record and marker NORMAL",
            mapOf(
                "_app_lock_load_state" to "NORMAL",
                "discreet_notifications" to false,
                "app_lock_enabled" to false,
                "auto_lock_timeout" to "M1",
                "secure_screen" to false
            ),
            stored
        )
        assertEquals(
            AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreetNotifications = false)),
            repository.getLoadState()
        )
    }

    @Test
    fun reviewSave_also_repairs_an_invalid_value_state() = runBlocking {
        real.edit { it[markerKey] = "BOGUS" }

        val result = repository.reviewSave(AppLockPrivacySettings(discreetNotifications = true))

        assertTrue(result is AppLockSettingsWriteResult.Committed)
        assertEquals(
            AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreetNotifications = true)),
            repository.getLoadState()
        )
    }

    @Test
    fun reviewSave_write_failure_leaves_store_and_sentinel_untouched() = runBlocking {
        real.edit { it[markerKey] = "CORRUPTED" }
        val before = real.data.first().asMap()
        faulty.failWrite = IOException("disk full")

        val result = repository.reviewSave(AppLockPrivacySettings(discreetNotifications = false))

        assertEquals(AppLockSettingsWriteResult.Failed(AppLockSettingField.REVIEW_SAVE), result)
        faulty.failWrite = null
        assertEquals(before, real.data.first().asMap())
        assertEquals(
            AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED),
            repository.getLoadState()
        )
    }

    @Test
    fun reviewSave_does_not_clear_the_sentinel_when_the_write_fails_then_succeeds_on_retry() = runBlocking {
        real.edit { it[markerKey] = "CORRUPTED" }
        faulty.failWrite = IOException("disk full")
        repository.reviewSave(AppLockPrivacySettings(discreetNotifications = true))
        faulty.failWrite = null

        val retry = repository.reviewSave(AppLockPrivacySettings(discreetNotifications = true))

        assertTrue(retry is AppLockSettingsWriteResult.Committed)
        assertEquals(
            AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreetNotifications = true)),
            repository.getLoadState()
        )
    }

    // ---------------------------------------------------------------------------------
    // 1b fields (S14): INV-2 invalid-value rules, INV-44 complete review record
    // ---------------------------------------------------------------------------------

    private val lockKey = booleanPreferencesKey("app_lock_enabled")
    private val timeoutKey = stringPreferencesKey("auto_lock_timeout")
    private val secureKey = booleanPreferencesKey("secure_screen")

    /**
     * S18 ii-d (D8): a review-save only writes over a persistent-unreadable store. This helper puts the real store into
     * CORRUPTED first (an existing record is kept underneath; review-save replaces the whole store), then commits the
     * record through the production review-save, so a test that needs a committed 1b record keeps doing exactly that.
     */
    private suspend fun commitViaReview(record: AppLockPrivacySettings) {
        real.edit { it[markerKey] = "CORRUPTED" }
        val result = repository.reviewSave(record)
        assertEquals(AppLockSettingsWriteResult.Committed(record), result)
    }

    @Test
    fun absent_1b_keys_with_other_keys_present_take_their_defaults_without_resetting_the_store() = runBlocking {
        // A 1a-era store (discreet only) opened by a 1b build must keep its value and default the rest.
        real.edit {
            it[markerKey] = "NORMAL"
            it[discreetKey] = true
        }

        assertEquals(
            AppLockSettingsLoadState.Loaded(
                AppLockPrivacySettings(
                    discreetNotifications = true,
                    appLockEnabled = false,
                    autoLockTimeout = AutoLockTimeout.M1,
                    secureScreen = false
                )
            ),
            repository.getLoadState()
        )
    }

    @Test
    fun a_lone_real_key_without_a_marker_is_loaded_with_only_that_field_set_never_first_run() = runBlocking {
        // Each of the four real keys alone (no marker), set to a non-default value: Loaded, the other
        // fields at their defaults. Never FirstRunDefault.
        val cases = listOf(
            Pair<(androidx.datastore.preferences.core.MutablePreferences) -> Unit, AppLockPrivacySettings>(
                { it[discreetKey] = true },
                AppLockPrivacySettings(discreetNotifications = true)
            ),
            Pair<(androidx.datastore.preferences.core.MutablePreferences) -> Unit, AppLockPrivacySettings>(
                { it[lockKey] = true },
                AppLockPrivacySettings(appLockEnabled = true)
            ),
            Pair<(androidx.datastore.preferences.core.MutablePreferences) -> Unit, AppLockPrivacySettings>(
                { it[timeoutKey] = "M15" },
                AppLockPrivacySettings(autoLockTimeout = AutoLockTimeout.M15)
            ),
            Pair<(androidx.datastore.preferences.core.MutablePreferences) -> Unit, AppLockPrivacySettings>(
                { it[secureKey] = true },
                AppLockPrivacySettings(secureScreen = true)
            )
        )

        cases.forEachIndexed { index, (write, expected) ->
            real.edit { prefs ->
                prefs.clear()
                write(prefs)
            }

            assertEquals(
                "case $index",
                AppLockSettingsLoadState.Loaded(expected),
                repository.getLoadState()
            )
        }
    }

    @Test
    fun valid_1b_values_are_read_back() = runBlocking {
        real.edit {
            it[markerKey] = "NORMAL"
            it[lockKey] = true
            it[timeoutKey] = "M15"
            it[secureKey] = true
        }

        assertEquals(
            AppLockSettingsLoadState.Loaded(
                AppLockPrivacySettings(
                    discreetNotifications = false,
                    appLockEnabled = true,
                    autoLockTimeout = AutoLockTimeout.M15,
                    secureScreen = true
                )
            ),
            repository.getLoadState()
        )
    }

    @Test
    fun invalid_lock_flag_makes_the_whole_state_unreadable_invalid_value_with_a_marker() = runBlocking {
        real.edit {
            it[markerKey] = "NORMAL"
            it[stringPreferencesKey("app_lock_enabled")] = "yes"
            it[discreetKey] = false
        }

        assertEquals(
            AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.INVALID_VALUE),
            repository.getLoadState()
        )
    }

    @Test
    fun invalid_lock_flag_without_a_marker_is_unreadable_never_first_run() = runBlocking {
        real.edit { it[stringPreferencesKey("app_lock_enabled")] = "yes" }

        assertEquals(
            AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.INVALID_VALUE),
            repository.getLoadState()
        )
    }

    @Test
    fun corrupted_sentinel_wins_over_an_invalid_lock_flag() = runBlocking {
        real.edit {
            it[markerKey] = "CORRUPTED"
            it[stringPreferencesKey("app_lock_enabled")] = "yes"
        }

        assertEquals(
            AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED),
            repository.getLoadState()
        )
    }

    @Test
    fun unknown_timeout_token_maps_to_immediate_and_is_loaded() = runBlocking {
        for (token in listOf("M2", "m1", "", "60000")) {
            real.edit {
                it.clear()
                it[markerKey] = "NORMAL"
                it[timeoutKey] = token
            }

            assertEquals(
                "token=$token",
                AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(autoLockTimeout = AutoLockTimeout.IMMEDIATE)),
                repository.getLoadState()
            )
        }
    }

    @Test
    fun non_string_timeout_value_maps_to_immediate() = runBlocking {
        real.edit {
            it[markerKey] = "NORMAL"
            it[booleanPreferencesKey("auto_lock_timeout")] = true
        }

        assertEquals(
            AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(autoLockTimeout = AutoLockTimeout.IMMEDIATE)),
            repository.getLoadState()
        )
    }

    @Test
    fun invalid_secure_screen_value_maps_to_true_and_is_loaded() = runBlocking {
        real.edit {
            it[markerKey] = "NORMAL"
            it[stringPreferencesKey("secure_screen")] = "maybe"
        }

        assertEquals(
            AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(secureScreen = true)),
            repository.getLoadState()
        )
    }

    @Test
    fun setDiscreet_is_rejected_while_the_lock_flag_is_invalid_and_writes_nothing() = runBlocking {
        real.edit {
            it[markerKey] = "NORMAL"
            it[stringPreferencesKey("app_lock_enabled")] = "yes"
        }
        val before = real.data.first().asMap()

        // S20-d: ported from the retired unforced write to the forced-aware write (same Unreadable rejection, INV-44).
        val result = repository.setDiscreetNotificationsIfNotForced(true)

        assertEquals(AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE), result)
        assertEquals(before, real.data.first().asMap())
    }

    @Test
    fun reviewSave_writes_a_complete_1b_record_and_reads_it_back() = runBlocking {
        val record = AppLockPrivacySettings(
            discreetNotifications = true,
            appLockEnabled = true,
            autoLockTimeout = AutoLockTimeout.IMMEDIATE,
            secureScreen = true
        )
        real.edit { it[markerKey] = "CORRUPTED" }   // S18 ii-d (D8): review-save needs a persistent-unreadable store

        val result = repository.reviewSave(record)

        assertEquals(AppLockSettingsWriteResult.Committed(record), result)
        assertEquals(
            mapOf(
                "_app_lock_load_state" to "NORMAL",
                "discreet_notifications" to true,
                "app_lock_enabled" to true,
                "auto_lock_timeout" to "IMMEDIATE",
                "secure_screen" to true
            ),
            real.data.first().asMap().mapKeys { it.key.name }
        )
        assertEquals(AppLockSettingsLoadState.Loaded(record), repository.getLoadState())
    }

    @Test
    fun reviewSave_repairs_an_invalid_lock_flag_state() = runBlocking {
        real.edit {
            it[markerKey] = "NORMAL"
            it[stringPreferencesKey("app_lock_enabled")] = "yes"
        }

        val result = repository.reviewSave(AppLockPrivacySettings(appLockEnabled = true))

        assertEquals(
            AppLockSettingsWriteResult.Committed(AppLockPrivacySettings(appLockEnabled = true)),
            result
        )
        assertEquals(
            AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(appLockEnabled = true)),
            repository.getLoadState()
        )
    }

    @Test
    fun reviewSave_write_failure_keeps_a_committed_1b_record() = runBlocking {
        val committed = AppLockPrivacySettings(appLockEnabled = true, autoLockTimeout = AutoLockTimeout.M5)
        commitViaReview(committed)
        faulty.failWrite = IOException("disk full")

        val result = repository.reviewSave(AppLockPrivacySettings())

        assertEquals(AppLockSettingsWriteResult.Failed(AppLockSettingField.REVIEW_SAVE), result)
        faulty.failWrite = null
        assertEquals(AppLockSettingsLoadState.Loaded(committed), repository.getLoadState())
    }

    // ---------------------------------------------------------------------------------
    // S18 ii-d (F4(a), D8): review-save is conditional on a persistent-unreadable store (INV-44)
    // ---------------------------------------------------------------------------------

    @Test
    fun reviewSave_over_a_loaded_store_is_rejected_not_under_review_and_writes_nothing() = runBlocking {
        val committed = AppLockPrivacySettings(
            discreetNotifications = true,
            appLockEnabled = true,
            autoLockTimeout = AutoLockTimeout.M5,
            secureScreen = true
        )
        commitViaReview(committed)
        val before = real.data.first().asMap()

        val result = repository.reviewSave(AppLockPrivacySettings())   // would be a full weakening if it wrote

        assertEquals(AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.NOT_UNDER_REVIEW), result)
        assertEquals("the committed record is not touched", before, real.data.first().asMap())
        assertEquals(AppLockSettingsLoadState.Loaded(committed), repository.getLoadState())
    }

    @Test
    fun reviewSave_over_a_first_run_default_store_is_rejected_not_under_review_and_writes_nothing() = runBlocking {
        assertEquals(
            AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings.DEFAULTS),
            repository.getLoadState()
        )

        val result = repository.reviewSave(AppLockPrivacySettings(appLockEnabled = true))

        assertEquals(AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.NOT_UNDER_REVIEW), result)
        assertTrue("nothing is written: the store is still empty", real.data.first().asMap().isEmpty())
        assertEquals(
            AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings.DEFAULTS),
            repository.getLoadState()
        )
    }

    @Test
    fun reviewSave_with_a_transient_io_error_store_is_rejected_and_writes_nothing() = runBlocking {
        // A transient IO_ERROR cannot be observed INSIDE the edit transform (the transform only ever sees data that
        // was read), so the transient case is a read failure that also fails the edit. The refusal comes from the
        // IO-failure mapping (UNREADABLE for a non-persistent cause), never Committed and never Failed(REVIEW_SAVE).
        commitViaReview(AppLockPrivacySettings(appLockEnabled = true, autoLockTimeout = AutoLockTimeout.M5))
        val before = real.data.first().asMap()
        faulty.failRead = IOException("disk")
        faulty.failWrite = IOException("disk")

        val result = repository.reviewSave(AppLockPrivacySettings())

        faulty.failRead = null
        faulty.failWrite = null
        assertEquals(AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE), result)
        assertEquals("nothing is written", before, real.data.first().asMap())
    }

    @Test
    fun reviewSave_with_a_failing_read_but_a_loaded_store_in_the_edit_is_rejected_not_under_review() = runBlocking {
        val committed = AppLockPrivacySettings(appLockEnabled = true)
        commitViaReview(committed)
        val before = real.data.first().asMap()
        faulty.failRead = IOException("disk")   // reads fail, the edit itself still runs over the real data

        val result = repository.reviewSave(AppLockPrivacySettings())

        faulty.failRead = null
        assertEquals(AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.NOT_UNDER_REVIEW), result)
        assertEquals(before, real.data.first().asMap())
    }

    @Test
    fun reviewSave_over_each_persistent_unreadable_cause_commits_the_full_record_and_marker() = runBlocking {
        val record = AppLockPrivacySettings(
            discreetNotifications = true,
            appLockEnabled = true,
            autoLockTimeout = AutoLockTimeout.M5,
            secureScreen = true
        )
        val expected = mapOf(
            "_app_lock_load_state" to "NORMAL",
            "discreet_notifications" to true,
            "app_lock_enabled" to true,
            "auto_lock_timeout" to "M5",
            "secure_screen" to true
        )
        val names = listOf("CORRUPTED", "INVALID_VALUE unknown marker", "INVALID_VALUE bare invalid lock flag")
        for (name in names) {
            real.edit { prefs ->
                prefs.clear()
                when (name) {
                    "CORRUPTED" -> {
                        prefs[markerKey] = "CORRUPTED"
                        prefs[booleanPreferencesKey("stale")] = true
                    }
                    "INVALID_VALUE unknown marker" -> {
                        prefs[markerKey] = "BOGUS"
                        prefs[discreetKey] = false
                    }
                    else -> prefs[stringPreferencesKey("app_lock_enabled")] = "yes"
                }
            }
            val causeBefore = repository.getLoadState()
            assertTrue("$name is persistent-unreadable before", causeBefore is AppLockSettingsLoadState.Unreadable)

            val result = repository.reviewSave(record)

            assertEquals(name, AppLockSettingsWriteResult.Committed(record), result)
            assertEquals(name, expected, real.data.first().asMap().mapKeys { it.key.name })
            assertEquals(name, AppLockSettingsLoadState.Loaded(record), repository.getLoadState())
        }
    }

    @Test
    fun reviewSave_a_second_stale_save_after_the_recovery_is_rejected_and_keeps_the_first_record() = runBlocking {
        real.edit { it[markerKey] = "CORRUPTED" }
        val first = AppLockPrivacySettings(appLockEnabled = true, autoLockTimeout = AutoLockTimeout.M5)
        assertEquals(AppLockSettingsWriteResult.Committed(first), repository.reviewSave(first))

        val stale = repository.reviewSave(AppLockPrivacySettings())

        assertEquals(AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.NOT_UNDER_REVIEW), stale)
        assertEquals(AppLockSettingsLoadState.Loaded(first), repository.getLoadState())
    }

    /**
     * S18 ii-d D8 race: the review-save check must read INSIDE the same edit transform as the write (pattern of S18
     * i-b2 ISSUE-3, [forcedAware_check_reads_inside_the_same_edit_as_a_concurrent_lock_commit]).
     *
     * Construction: a lock write (setAppLockEnabled) over a CORRUPTED store is refused (INV-44), so it cannot be the
     * concurrent writer. The closest legal writer that turns the in-edit state readable is another review-save (a
     * second review card). The store is seeded CORRUPTED; review-save A (lock on, timeout M5) is held open inside its
     * edit by the [GatedStore]; review-save B (all defaults, lock off) is issued and queues behind it. While A is held
     * a plain read still sees CORRUPTED, which is exactly what a check done BEFORE the edit would see (and would then
     * overwrite A). On release A commits first; B's transform must then see Loaded and reject NOT_UNDER_REVIEW, leaving
     * the lock key that A wrote ON.
     */
    @Test
    fun reviewSave_check_reads_inside_the_same_edit_as_a_concurrent_review_save() = runBlocking {
        real.edit { it[markerKey] = "CORRUPTED" }
        val gated = GatedStore(real)
        val gatedRepository = AppLockSettingsRepositoryImpl(gated)
        val recordA = AppLockPrivacySettings(appLockEnabled = true, autoLockTimeout = AutoLockTimeout.M5)
        try {
            val saveA = async { gatedRepository.reviewSave(recordA) }
            withTimeout(GATE_TIMEOUT_MS) { gated.heldEntered.await() }   // A is open at its transform

            val saveB = async { gatedRepository.reviewSave(AppLockPrivacySettings()) }
            withTimeout(GATE_TIMEOUT_MS) { gated.secondCalled.await() }  // B has been issued
            assertFalse("B must not complete while A is held", saveB.isCompleted)
            assertEquals(
                "a pre-edit read still sees the store as corrupted",
                AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED),
                gatedRepository.getLoadState()
            )

            gated.gate.complete(Unit)
            val resultA = withTimeout(GATE_TIMEOUT_MS) { saveA.await() }
            val resultB = withTimeout(GATE_TIMEOUT_MS) { saveB.await() }

            assertEquals(AppLockSettingsWriteResult.Committed(recordA), resultA)
            assertEquals(
                "B sees the recovered store inside its own edit",
                AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.NOT_UNDER_REVIEW),
                resultB
            )
            assertEquals("the lock key written by A is unchanged by B", true, real.data.first()[lockKey])
            assertEquals("M5", real.data.first()[timeoutKey])
            assertEquals(AppLockSettingsLoadState.Loaded(recordA), gatedRepository.getLoadState())
        } finally {
            gated.gate.complete(Unit)   // a failed assertion must fail fast, never hang runBlocking
        }
    }

    // ---------------------------------------------------------------------------------
    // S18 (i-a): setAppLockEnabled (INV-3, INV-44, INV-46)
    // ---------------------------------------------------------------------------------

    @Test
    fun setAppLockEnabled_true_commits_and_reads_back_enabled() = runBlocking {
        val result = repository.setAppLockEnabled(true)

        assertEquals(
            AppLockSettingsWriteResult.Committed(AppLockPrivacySettings(appLockEnabled = true)),
            result
        )
        assertEquals(
            AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(appLockEnabled = true)),
            repository.getLoadState()
        )
        assertEquals(true, real.data.first()[lockKey])
        assertEquals("NORMAL", real.data.first()[markerKey])
    }

    @Test
    fun setAppLockEnabled_false_after_true_commits_and_reads_back_disabled() = runBlocking {
        repository.setAppLockEnabled(true)

        val result = repository.setAppLockEnabled(false)

        assertEquals(
            AppLockSettingsWriteResult.Committed(AppLockPrivacySettings(appLockEnabled = false)),
            result
        )
        assertEquals(
            AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(appLockEnabled = false)),
            repository.getLoadState()
        )
        assertEquals(false, real.data.first()[lockKey])
    }

    @Test
    fun setAppLockEnabled_false_on_first_run_still_writes_marker_so_it_is_loaded_not_first_run() = runBlocking {
        val result = repository.setAppLockEnabled(false)

        assertEquals(
            AppLockSettingsWriteResult.Committed(AppLockPrivacySettings(appLockEnabled = false)),
            result
        )
        assertEquals(
            AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(appLockEnabled = false)),
            repository.getLoadState()
        )
    }

    @Test
    fun setAppLockEnabled_from_first_run_writes_only_the_lock_flag_and_the_normal_marker() = runBlocking {
        repository.setAppLockEnabled(true)

        assertEquals(
            mapOf(
                "_app_lock_load_state" to "NORMAL",
                "app_lock_enabled" to true
            ),
            real.data.first().asMap().mapKeys { it.key.name }
        )
    }

    @Test
    fun setAppLockEnabled_leaves_the_other_fields_untouched() = runBlocking {
        val record = AppLockPrivacySettings(
            discreetNotifications = true,
            appLockEnabled = false,
            autoLockTimeout = AutoLockTimeout.M15,
            secureScreen = true
        )
        commitViaReview(record)

        val result = repository.setAppLockEnabled(true)

        assertEquals(AppLockSettingsWriteResult.Committed(record.copy(appLockEnabled = true)), result)
        assertEquals(
            mapOf(
                "_app_lock_load_state" to "NORMAL",
                "discreet_notifications" to true,
                "app_lock_enabled" to true,
                "auto_lock_timeout" to "M15",
                "secure_screen" to true
            ),
            real.data.first().asMap().mapKeys { it.key.name }
        )
    }

    @Test
    fun setAppLockEnabled_while_sentinel_present_is_rejected_unreadable_and_writes_nothing() = runBlocking {
        real.edit { it[markerKey] = "CORRUPTED" }
        val before = real.data.first().asMap()

        val result = repository.setAppLockEnabled(true)

        assertEquals(AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE), result)
        assertEquals(before, real.data.first().asMap())
        assertEquals(
            AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED),
            repository.getLoadState()
        )
    }

    @Test
    fun setAppLockEnabled_while_marker_is_invalid_is_rejected_unreadable_and_writes_nothing() = runBlocking {
        real.edit { it[markerKey] = "BOGUS" }
        val before = real.data.first().asMap()

        val result = repository.setAppLockEnabled(true)

        assertEquals(AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE), result)
        assertEquals(before, real.data.first().asMap())
        assertEquals(
            AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.INVALID_VALUE),
            repository.getLoadState()
        )
    }

    @Test
    fun setAppLockEnabled_while_lock_flag_is_invalid_is_rejected_and_does_not_overwrite_it() = runBlocking {
        real.edit {
            it[markerKey] = "NORMAL"
            it[stringPreferencesKey("app_lock_enabled")] = "yes"
        }
        val before = real.data.first().asMap()

        val result = repository.setAppLockEnabled(false)

        assertEquals(AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE), result)
        assertEquals(before, real.data.first().asMap())
        assertEquals(
            AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.INVALID_VALUE),
            repository.getLoadState()
        )
    }

    @Test
    fun setAppLockEnabled_when_the_read_fails_with_io_error_is_rejected_unreadable() = runBlocking {
        faulty.failRead = IOException("disk")
        faulty.failWrite = IOException("disk")

        val result = repository.setAppLockEnabled(true)

        assertEquals(AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE), result)
    }

    @Test
    fun setAppLockEnabled_write_io_failure_while_sentinel_present_is_rejected_not_failed_and_keeps_the_sentinel() = runBlocking {
        real.edit { it[markerKey] = "CORRUPTED" }
        val before = real.data.first().asMap()
        faulty.failWrite = IOException("disk full")

        val result = repository.setAppLockEnabled(true)

        assertEquals(AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE), result)
        faulty.failWrite = null
        assertEquals(before, real.data.first().asMap())
    }

    @Test
    fun setAppLockEnabled_write_io_failure_is_typed_failed_and_keeps_the_committed_value() = runBlocking {
        repository.setAppLockEnabled(false)
        val before = real.data.first().asMap()
        faulty.failWrite = IOException("disk full")

        val result = repository.setAppLockEnabled(true)

        assertEquals(AppLockSettingsWriteResult.Failed(AppLockSettingField.APP_LOCK_ENABLED), result)
        faulty.failWrite = null
        assertEquals(before, real.data.first().asMap())
        assertEquals(false, real.data.first()[lockKey])
        assertEquals(
            AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(appLockEnabled = false)),
            repository.getLoadState()
        )
    }

    @Test
    fun setAppLockEnabled_write_io_failure_from_first_run_writes_nothing() = runBlocking {
        faulty.failWrite = IOException("disk full")

        val result = repository.setAppLockEnabled(true)

        assertEquals(AppLockSettingsWriteResult.Failed(AppLockSettingField.APP_LOCK_ENABLED), result)
        faulty.failWrite = null
        assertEquals(emptyMap<Preferences.Key<*>, Any>(), real.data.first().asMap())
        assertEquals(
            AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings.DEFAULTS),
            repository.getLoadState()
        )
    }

    @Test
    fun setAppLockEnabled_write_io_failure_then_retry_commits() = runBlocking {
        faulty.failWrite = IOException("disk full")
        repository.setAppLockEnabled(true)
        faulty.failWrite = null

        val retry = repository.setAppLockEnabled(true)

        assertEquals(
            AppLockSettingsWriteResult.Committed(AppLockPrivacySettings(appLockEnabled = true)),
            retry
        )
    }

    @Test
    fun setAppLockEnabled_cancellation_during_write_propagates_and_is_not_a_typed_failure() = runBlocking {
        faulty.failWrite = CancellationException("cancelled")

        var propagated = false
        try {
            repository.setAppLockEnabled(true)
        } catch (e: CancellationException) {
            propagated = true
        }
        assertTrue("CancellationException must propagate from setAppLockEnabled", propagated)
    }

    @Test
    fun setAppLockEnabled_non_io_write_failure_propagates() = runBlocking {
        faulty.failWrite = IllegalStateException("boom")

        var thrown: Throwable? = null
        try {
            repository.setAppLockEnabled(true)
        } catch (e: IllegalStateException) {
            thrown = e
        }
        assertTrue("non-IO write failure must propagate", thrown is IllegalStateException)
    }

    // ---------------------------------------------------------------------------------
    // S18 (i-b1): setAutoLockTimeout (INV-3, INV-44, INV-46)
    // ---------------------------------------------------------------------------------


    @Test
    fun setAutoLockTimeout_commits_and_reads_back_the_timeout() = runBlocking {
        val result = repository.setAutoLockTimeout(AutoLockTimeout.M5)

        assertEquals(
            AppLockSettingsWriteResult.Committed(AppLockPrivacySettings(autoLockTimeout = AutoLockTimeout.M5)),
            result
        )
        assertEquals(
            AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(autoLockTimeout = AutoLockTimeout.M5)),
            repository.getLoadState()
        )
        assertEquals("M5", real.data.first()[timeoutKey])
        assertEquals("NORMAL", real.data.first()[markerKey])
    }

    @Test
    fun setAutoLockTimeout_from_first_run_writes_only_the_timeout_and_the_normal_marker() = runBlocking {
        repository.setAutoLockTimeout(AutoLockTimeout.S30)

        assertEquals(
            mapOf(
                "_app_lock_load_state" to "NORMAL",
                "auto_lock_timeout" to "S30"
            ),
            real.data.first().asMap().mapKeys { it.key.name }
        )
    }

    @Test
    fun setAutoLockTimeout_leaves_the_other_fields_untouched() = runBlocking {
        val record = AppLockPrivacySettings(
            discreetNotifications = true,
            appLockEnabled = true,
            autoLockTimeout = AutoLockTimeout.M1,
            secureScreen = false
        )
        commitViaReview(record)

        val result = repository.setAutoLockTimeout(AutoLockTimeout.M15)

        assertEquals(
            AppLockSettingsWriteResult.Committed(record.copy(autoLockTimeout = AutoLockTimeout.M15)),
            result
        )
        assertEquals(
            AppLockSettingsLoadState.Loaded(record.copy(autoLockTimeout = AutoLockTimeout.M15)),
            repository.getLoadState()
        )
    }

    @Test
    fun setAutoLockTimeout_every_entry_round_trips() = runBlocking {
        for (timeout in AutoLockTimeout.entries) {
            val result = repository.setAutoLockTimeout(timeout)
            assertEquals("$timeout", timeout, (result as AppLockSettingsWriteResult.Committed).settings.autoLockTimeout)
            assertEquals(
                "$timeout",
                AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(autoLockTimeout = timeout)),
                repository.getLoadState()
            )
        }
    }

    @Test
    fun setAutoLockTimeout_while_sentinel_present_is_rejected_unreadable_and_writes_nothing() = runBlocking {
        real.edit { it[markerKey] = "CORRUPTED" }
        val before = real.data.first().asMap()

        val result = repository.setAutoLockTimeout(AutoLockTimeout.M5)

        assertEquals(AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE), result)
        assertEquals(before, real.data.first().asMap())
        assertEquals(
            AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED),
            repository.getLoadState()
        )
    }

    @Test
    fun setAutoLockTimeout_while_marker_is_invalid_is_rejected_unreadable_and_writes_nothing() = runBlocking {
        real.edit { it[markerKey] = "BOGUS" }
        val before = real.data.first().asMap()

        val result = repository.setAutoLockTimeout(AutoLockTimeout.M5)

        assertEquals(AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE), result)
        assertEquals(before, real.data.first().asMap())
    }

    @Test
    fun setAutoLockTimeout_when_the_read_fails_with_io_error_is_rejected_unreadable() = runBlocking {
        faulty.failRead = IOException("disk")
        faulty.failWrite = IOException("disk")

        val result = repository.setAutoLockTimeout(AutoLockTimeout.M5)

        assertEquals(AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE), result)
    }

    @Test
    fun setAutoLockTimeout_write_io_failure_while_sentinel_present_is_rejected_not_failed_and_keeps_the_sentinel() = runBlocking {
        real.edit { it[markerKey] = "CORRUPTED" }
        val before = real.data.first().asMap()
        faulty.failWrite = IOException("disk full")

        val result = repository.setAutoLockTimeout(AutoLockTimeout.M5)

        assertEquals(AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE), result)
        faulty.failWrite = null
        assertEquals(before, real.data.first().asMap())
    }

    @Test
    fun setAutoLockTimeout_write_io_failure_is_typed_failed_and_keeps_the_committed_value() = runBlocking {
        repository.setAutoLockTimeout(AutoLockTimeout.M1)
        val before = real.data.first().asMap()
        faulty.failWrite = IOException("disk full")

        val result = repository.setAutoLockTimeout(AutoLockTimeout.M15)

        assertEquals(AppLockSettingsWriteResult.Failed(AppLockSettingField.AUTO_LOCK_TIMEOUT), result)
        faulty.failWrite = null
        assertEquals(before, real.data.first().asMap())
        assertEquals("M1", real.data.first()[timeoutKey])
    }

    @Test
    fun setAutoLockTimeout_write_io_failure_from_first_run_writes_nothing() = runBlocking {
        faulty.failWrite = IOException("disk full")

        val result = repository.setAutoLockTimeout(AutoLockTimeout.M5)

        assertEquals(AppLockSettingsWriteResult.Failed(AppLockSettingField.AUTO_LOCK_TIMEOUT), result)
        faulty.failWrite = null
        assertEquals(emptyMap<Preferences.Key<*>, Any>(), real.data.first().asMap())
        assertEquals(
            AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings.DEFAULTS),
            repository.getLoadState()
        )
    }

    @Test
    fun setAutoLockTimeout_write_io_failure_then_retry_commits() = runBlocking {
        faulty.failWrite = IOException("disk full")
        repository.setAutoLockTimeout(AutoLockTimeout.M5)
        faulty.failWrite = null

        val retry = repository.setAutoLockTimeout(AutoLockTimeout.M5)

        assertEquals(
            AppLockSettingsWriteResult.Committed(AppLockPrivacySettings(autoLockTimeout = AutoLockTimeout.M5)),
            retry
        )
    }

    @Test
    fun setAutoLockTimeout_cancellation_during_write_propagates_and_is_not_a_typed_failure() = runBlocking {
        faulty.failWrite = CancellationException("cancelled")

        var propagated = false
        try {
            repository.setAutoLockTimeout(AutoLockTimeout.M5)
        } catch (e: CancellationException) {
            propagated = true
        }
        assertTrue("CancellationException must propagate from setAutoLockTimeout", propagated)
    }

    @Test
    fun setAutoLockTimeout_non_io_write_failure_propagates() = runBlocking {
        faulty.failWrite = IllegalStateException("boom")

        var thrown: Throwable? = null
        try {
            repository.setAutoLockTimeout(AutoLockTimeout.M5)
        } catch (e: IllegalStateException) {
            thrown = e
        }
        assertTrue("non-IO write failure must propagate", thrown is IllegalStateException)
    }

    // ---------------------------------------------------------------------------------
    // S18 (i-b1) privacy review F1: setAutoLockTimeoutIfNotWeakening (check and write in ONE store edit)
    // ---------------------------------------------------------------------------------

    private suspend fun seedRecord(lockEnabled: Boolean, timeout: AutoLockTimeout): AppLockPrivacySettings {
        val record = AppLockPrivacySettings(appLockEnabled = lockEnabled, autoLockTimeout = timeout)
        commitViaReview(record)
        return record
    }

    @Test
    fun conditionalTimeout_lengthening_with_the_lock_on_is_rejected_and_writes_nothing() = runBlocking {
        seedRecord(lockEnabled = true, timeout = AutoLockTimeout.M1)
        val before = real.data.first().asMap()

        val result = repository.setAutoLockTimeoutIfNotWeakening(AutoLockTimeout.M5)

        assertEquals(AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.WOULD_WEAKEN), result)
        assertEquals(before, real.data.first().asMap())
        assertEquals("M1", real.data.first()[timeoutKey])
    }

    @Test
    fun conditionalTimeout_shortening_or_equal_with_the_lock_on_is_committed() = runBlocking {
        val record = seedRecord(lockEnabled = true, timeout = AutoLockTimeout.M5)

        val shorter = repository.setAutoLockTimeoutIfNotWeakening(AutoLockTimeout.S30)
        val equal = repository.setAutoLockTimeoutIfNotWeakening(AutoLockTimeout.S30)

        assertEquals(
            AppLockSettingsWriteResult.Committed(record.copy(autoLockTimeout = AutoLockTimeout.S30)),
            shorter
        )
        assertEquals(shorter, equal)
        assertEquals("S30", real.data.first()[timeoutKey])
    }

    @Test
    fun conditionalTimeout_lengthening_with_the_lock_off_is_committed() = runBlocking {
        val record = seedRecord(lockEnabled = false, timeout = AutoLockTimeout.M1)

        val result = repository.setAutoLockTimeoutIfNotWeakening(AutoLockTimeout.M15)

        assertEquals(
            AppLockSettingsWriteResult.Committed(record.copy(autoLockTimeout = AutoLockTimeout.M15)),
            result
        )
        assertEquals("M15", real.data.first()[timeoutKey])
    }

    @Test
    fun conditionalTimeout_from_first_run_commits_with_the_lock_off_default() = runBlocking {
        val result = repository.setAutoLockTimeoutIfNotWeakening(AutoLockTimeout.M15)

        assertEquals(
            AppLockSettingsWriteResult.Committed(AppLockPrivacySettings(autoLockTimeout = AutoLockTimeout.M15)),
            result
        )
        assertEquals("NORMAL", real.data.first()[markerKey])
    }

    @Test
    fun conditionalTimeout_with_an_invalid_stored_timeout_and_the_lock_on_reads_it_as_immediate() = runBlocking {
        seedRecord(lockEnabled = true, timeout = AutoLockTimeout.M5)
        real.edit { it[timeoutKey] = "GARBAGE" }
        val before = real.data.first().asMap()

        val longer = repository.setAutoLockTimeoutIfNotWeakening(AutoLockTimeout.S30)

        assertEquals(AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.WOULD_WEAKEN), longer)
        assertEquals(before, real.data.first().asMap())
        val equal = repository.setAutoLockTimeoutIfNotWeakening(AutoLockTimeout.IMMEDIATE)
        assertTrue(equal is AppLockSettingsWriteResult.Committed)
    }

    @Test
    fun conditionalTimeout_while_sentinel_present_is_rejected_unreadable_and_writes_nothing() = runBlocking {
        real.edit { it[markerKey] = "CORRUPTED" }
        val before = real.data.first().asMap()

        val result = repository.setAutoLockTimeoutIfNotWeakening(AutoLockTimeout.S30)

        assertEquals(AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE), result)
        assertEquals(before, real.data.first().asMap())
    }

    @Test
    fun conditionalTimeout_when_the_read_fails_with_io_error_is_rejected_unreadable() = runBlocking {
        faulty.failRead = IOException("disk")
        faulty.failWrite = IOException("disk")

        val result = repository.setAutoLockTimeoutIfNotWeakening(AutoLockTimeout.S30)

        assertEquals(AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE), result)
    }

    @Test
    fun conditionalTimeout_write_io_failure_is_typed_failed_and_keeps_the_committed_value() = runBlocking {
        seedRecord(lockEnabled = true, timeout = AutoLockTimeout.M5)
        val before = real.data.first().asMap()
        faulty.failWrite = IOException("disk full")

        val result = repository.setAutoLockTimeoutIfNotWeakening(AutoLockTimeout.S30)

        assertEquals(AppLockSettingsWriteResult.Failed(AppLockSettingField.AUTO_LOCK_TIMEOUT), result)
        faulty.failWrite = null
        assertEquals(before, real.data.first().asMap())
    }

    @Test
    fun conditionalTimeout_cancellation_during_write_propagates() = runBlocking {
        faulty.failWrite = CancellationException("cancelled")

        var propagated = false
        try {
            repository.setAutoLockTimeoutIfNotWeakening(AutoLockTimeout.S30)
        } catch (e: CancellationException) {
            propagated = true
        }
        assertTrue("CancellationException must propagate from the conditional write", propagated)
    }

    // ---------------------------------------------------------------------------------
    // S18 (i-b2): forced-aware discreet and secure-screen writes (INV-48, AE-103, INV-44)
    // ---------------------------------------------------------------------------------

    private data class ForcedAwareField(
        val field: AppLockSettingField,
        val key: androidx.datastore.preferences.core.Preferences.Key<Boolean>,
        val write: suspend (Boolean) -> AppLockSettingsWriteResult
    )

    private fun forcedAwareFields(): List<ForcedAwareField> = listOf(
        ForcedAwareField(AppLockSettingField.DISCREET, discreetKey) { repository.setDiscreetNotificationsIfNotForced(it) },
        ForcedAwareField(AppLockSettingField.SECURE_SCREEN, secureKey) { repository.setSecureScreenIfNotForced(it) }
    )

    @Test
    fun forcedAware_with_the_lock_on_either_value_is_rejected_forced_and_writes_nothing() = runBlocking {
        for (f in forcedAwareFields()) {
            seedRecord(lockEnabled = true, timeout = AutoLockTimeout.M1)
            val before = real.data.first().asMap()

            assertEquals(
                "${f.field} true",
                AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.FORCED),
                f.write(true)
            )
            assertEquals(
                "${f.field} false",
                AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.FORCED),
                f.write(false)
            )
            assertEquals("${f.field}", before, real.data.first().asMap())
        }
    }

    @Test
    fun forcedAware_with_the_lock_off_commits_the_value_and_keeps_the_other_fields() = runBlocking {
        for (f in forcedAwareFields()) {
            val record = seedRecord(lockEnabled = false, timeout = AutoLockTimeout.M5)

            val result = f.write(true)

            val expected = if (f.field == AppLockSettingField.DISCREET) {
                record.copy(discreetNotifications = true)
            } else {
                record.copy(secureScreen = true)
            }
            assertEquals("${f.field}", AppLockSettingsWriteResult.Committed(expected), result)
            assertEquals("${f.field}", true, real.data.first()[f.key])
            assertEquals("${f.field}", "M5", real.data.first()[timeoutKey])
        }
    }

    @Test
    fun forcedAware_from_first_run_commits_with_the_lock_off_default() = runBlocking {
        val result = repository.setSecureScreenIfNotForced(true)

        assertEquals(
            AppLockSettingsWriteResult.Committed(AppLockPrivacySettings(secureScreen = true)),
            result
        )
        assertEquals("NORMAL", real.data.first()[markerKey])
    }

    @Test
    fun forcedAware_while_sentinel_present_is_rejected_unreadable_and_writes_nothing() = runBlocking {
        for (f in forcedAwareFields()) {
            real.edit { it[markerKey] = "CORRUPTED" }
            val before = real.data.first().asMap()

            assertEquals(
                "${f.field}",
                AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE),
                f.write(true)
            )
            assertEquals("${f.field}", before, real.data.first().asMap())
        }
    }

    @Test
    fun forcedAware_when_the_read_fails_with_io_error_is_rejected_unreadable() = runBlocking {
        for (f in forcedAwareFields()) {
            faulty.failRead = IOException("disk")
            faulty.failWrite = IOException("disk")

            assertEquals(
                "${f.field}",
                AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE),
                f.write(true)
            )
        }
    }

    @Test
    fun forcedAware_write_io_failure_is_typed_failed_for_its_own_field() = runBlocking {
        for (f in forcedAwareFields()) {
            seedRecord(lockEnabled = false, timeout = AutoLockTimeout.M5)
            val before = real.data.first().asMap()
            faulty.failWrite = IOException("disk full")

            assertEquals("${f.field}", AppLockSettingsWriteResult.Failed(f.field), f.write(true))
            faulty.failWrite = null
            assertEquals("${f.field}", before, real.data.first().asMap())
        }
    }

    @Test
    fun forcedAware_cancellation_during_write_propagates() = runBlocking {
        for (f in forcedAwareFields()) {
            faulty.failWrite = CancellationException("cancelled")

            var propagated = false
            try {
                f.write(true)
            } catch (e: CancellationException) {
                propagated = true
            }
            assertTrue("${f.field} CancellationException must propagate", propagated)
            faulty.failWrite = null
        }
    }

    /**
     * S18 i-b2 ISSUE-3: the forced check must read INSIDE the same edit transform as the write. A real DataStore
     * serialises edits, so a first edit (the lock commit) is held open at its transform; the forced-aware write
     * is then started and queues behind it. Releasing the gate lets the lock commit first; the queued edit's
     * transform must then see the lock on and reject FORCED, leaving its key unchanged. A check done before the
     * edit (a pre-read of committed data, which a held edit does not block) would see the lock off and write.
     */
    @Test
    fun forcedAware_check_reads_inside_the_same_edit_as_a_concurrent_lock_commit() = runBlocking {
        for (f in forcedAwareFields()) {
            commitViaReview(
                AppLockPrivacySettings(
                    discreetNotifications = true,
                    appLockEnabled = false,
                    autoLockTimeout = AutoLockTimeout.M5,
                    secureScreen = true
                )
            )
            assertEquals("${f.field} seeded", true, real.data.first()[f.key])
            val gated = GatedStore(real)
            val gatedRepository = AppLockSettingsRepositoryImpl(gated)
            try {
                val lockWrite = async { gatedRepository.setAppLockEnabled(true) }
                withTimeout(GATE_TIMEOUT_MS) { gated.heldEntered.await() }   // the lock edit is open at its transform

                val forcedWrite = async { when (f.field) {
                    AppLockSettingField.DISCREET -> gatedRepository.setDiscreetNotificationsIfNotForced(false)
                    else -> gatedRepository.setSecureScreenIfNotForced(false)
                } }
                withTimeout(GATE_TIMEOUT_MS) { gated.secondCalled.await() }       // the second edit has been issued
                assertFalse("${f.field} the queued edit must not complete while the lock edit is held", forcedWrite.isCompleted)

                gated.gate.complete(Unit)
                val lockResult = withTimeout(GATE_TIMEOUT_MS) { lockWrite.await() }
                val forcedResult = withTimeout(GATE_TIMEOUT_MS) { forcedWrite.await() }

                assertTrue("${f.field} lock commit: $lockResult", lockResult is AppLockSettingsWriteResult.Committed)
                assertEquals(
                    "${f.field} the queued write sees the committed lock inside its own edit",
                    AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.FORCED),
                    forcedResult
                )
                assertEquals("${f.field} key unchanged", true, real.data.first()[f.key])
                assertEquals("${f.field} lock on", true, real.data.first()[lockKey])
            } finally {
                gated.gate.complete(Unit)   // a failed assertion must fail fast, never hang runBlocking
            }
        }
    }

    /**
     * Holds the FIRST edit open inside its transform (after signalling [heldEntered]) until [gate] completes, and
     * signals [secondCalled] when the second edit is issued. Reads are not blocked, like a real held edit.
     */
    private class GatedStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        val gate = CompletableDeferred<Unit>()
        val heldEntered = CompletableDeferred<Unit>()
        val secondCalled = CompletableDeferred<Unit>()
        private val calls = AtomicInteger(0)

        override val data: Flow<Preferences> get() = delegate.data

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val n = calls.incrementAndGet()
            if (n == 2) secondCalled.complete(Unit)
            if (n != 1) return delegate.updateData(transform)
            return delegate.updateData { prefs ->
                heldEntered.complete(Unit)
                gate.await()
                transform(prefs)
            }
        }
    }

    /** Wraps a real store so reads and/or writes can be made to fail on demand. */
    private class FaultyStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        @Volatile
        var failRead: Throwable? = null

        @Volatile
        var failWrite: Throwable? = null

        override val data: Flow<Preferences>
            get() {
                val failure = failRead
                return if (failure == null) delegate.data else flow { throw failure }
            }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            failWrite?.let { throw it }
            return delegate.updateData(transform)
        }
    }
}
