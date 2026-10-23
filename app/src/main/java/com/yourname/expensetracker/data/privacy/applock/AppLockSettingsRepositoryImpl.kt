package com.yourname.expensetracker.data.privacy.applock

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yourname.expensetracker.domain.diagnostics.DiagnosticThrowableSummary
import com.yourname.expensetracker.domain.privacy.applock.AppLockPrivacySettings
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingField
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsRepository
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsWriteResult
import com.yourname.expensetracker.domain.privacy.applock.AppLockWriteRejection
import com.yourname.expensetracker.domain.privacy.applock.SettingsUnreadableCause
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber

private val LOAD_STATE_KEY = stringPreferencesKey("_app_lock_load_state")
private const val LOAD_STATE_NORMAL = "NORMAL"
private const val LOAD_STATE_CORRUPTED = "CORRUPTED"

/**
 * The app-lock / privacy-mode DataStore (OD-11a: dedicated store, its own corruption sentinel).
 *
 * The corruption handler writes a CORRUPTED sentinel so [toLoadState] can tell corruption from a
 * genuine first run; both look like empty preferences, only corruption carries the sentinel.
 * The name is unique among the app's DataStores and the file lives in the app-private
 * `datastore/` directory: it is never in Room and never in `.costbackup` (INV-1).
 */
@get:VisibleForTesting
internal val Context.appLockPrivacyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_lock_privacy_settings",
    corruptionHandler = ReplaceFileCorruptionHandler {
        mutablePreferencesOf(LOAD_STATE_KEY to LOAD_STATE_CORRUPTED)
    }
)

/**
 * Single writer and reader of [AppLockPrivacySettings] (INV-1, INV-2, INV-44, INV-46).
 *
 * - Every read returns a typed load state; unreadable stores are never collapsed to defaults.
 * - Read-check-write happens inside one DataStore `edit`, which is serialised and atomic, so a
 *   per-field write can never interleave with a review-save.
 * - A write returns [AppLockSettingsWriteResult.Committed] only after the durable commit.
 * - IO failures are mapped to typed outcomes; every other exception, including
 *   `CancellationException`, propagates.
 * - Diagnostics (`LOCK_SETTING_PERSIST_FAILED`) are recorded by the caller from the typed result.
 */
@Singleton
class AppLockSettingsRepositoryImpl @VisibleForTesting internal constructor(
    private val store: DataStore<Preferences>
) : AppLockSettingsRepository {

    @Inject
    constructor(@ApplicationContext context: Context) : this(context.appLockPrivacyDataStore)

    private object Keys {
        val DISCREET_NOTIFICATIONS = booleanPreferencesKey("discreet_notifications")
    }

    override fun observeLoadState(): Flow<AppLockSettingsLoadState> =
        store.data
            .map { prefs -> prefs.toLoadState() }
            .catch { error ->
                if (error is IOException) {
                    Timber.w("App-lock settings observe failed: class=%s", DiagnosticThrowableSummary.className(error))
                    emit(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.IO_ERROR))
                } else {
                    throw error
                }
            }

    override suspend fun getLoadState(): AppLockSettingsLoadState =
        try {
            store.data.first().toLoadState()
        } catch (e: IOException) {
            Timber.w("App-lock settings read failed: class=%s", DiagnosticThrowableSummary.className(e))
            AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.IO_ERROR)
        }

    override suspend fun setDiscreetNotifications(enabled: Boolean): AppLockSettingsWriteResult =
        try {
            val after = store.edit { prefs ->
                // INV-44: nothing is written while unreadable. An unchanged result is not rewritten
                // by DataStore, so the sentinel stays exactly as it was.
                if (prefs.toLoadState() is AppLockSettingsLoadState.Unreadable) return@edit
                prefs[Keys.DISCREET_NOTIFICATIONS] = enabled
                prefs[LOAD_STATE_KEY] = LOAD_STATE_NORMAL
            }
            when (val state = after.toLoadState()) {
                is AppLockSettingsLoadState.Loaded -> AppLockSettingsWriteResult.Committed(state.settings)
                is AppLockSettingsLoadState.FirstRunDefault -> AppLockSettingsWriteResult.Committed(state.settings)
                is AppLockSettingsLoadState.Unreadable ->
                    AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE)
            }
        } catch (e: IOException) {
            writeFailure(e, AppLockSettingField.DISCREET, rejectOnPersistentUnreadable = true)
        }

    override suspend fun reviewSave(record: AppLockPrivacySettings): AppLockSettingsWriteResult =
        try {
            // One atomic replace of the whole store: every field explicit, marker NORMAL. The
            // sentinel is cleared only if this commit succeeds (INV-44).
            val after = store.edit { prefs ->
                prefs.clear()
                prefs[Keys.DISCREET_NOTIFICATIONS] = record.discreetNotifications
                prefs[LOAD_STATE_KEY] = LOAD_STATE_NORMAL
            }
            val committed = (after.toLoadState() as? AppLockSettingsLoadState.Loaded)?.settings ?: record
            AppLockSettingsWriteResult.Committed(committed)
        } catch (e: IOException) {
            writeFailure(e, AppLockSettingField.REVIEW_SAVE, rejectOnPersistentUnreadable = false)
        }

    /**
     * Classifies an IO failure of a write. If the store cannot be read at all, the write is a
     * refusal against an unreadable store ([AppLockWriteRejection.UNREADABLE]); otherwise the commit
     * itself failed and the previous committed value stays in force.
     *
     * A review-save against a readable-but-persistently-unreadable store (CORRUPTED / INVALID_VALUE)
     * that fails to commit is a [AppLockSettingsWriteResult.Failed], never a refusal (AE-89).
     */
    private suspend fun writeFailure(
        error: IOException,
        field: AppLockSettingField,
        rejectOnPersistentUnreadable: Boolean
    ): AppLockSettingsWriteResult {
        Timber.w("App-lock settings write failed: field=%s class=%s", field, DiagnosticThrowableSummary.className(error))
        val state = getLoadState()
        val refused = state is AppLockSettingsLoadState.Unreadable &&
            (rejectOnPersistentUnreadable || !state.cause.isPersistent)
        return if (refused) {
            AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE)
        } else {
            AppLockSettingsWriteResult.Failed(field)
        }
    }

    /**
     * Load-state precedence:
     * 1. CORRUPTED sentinel -> Unreadable(CORRUPTED)
     * 2. A marker that is not a known token (or not a string) -> Unreadable(INVALID_VALUE)
     * 3. No marker and no real key -> FirstRunDefault (normal defaults)
     * 4. NORMAL marker or any real key present -> Loaded; an absent field takes its default and an
     *    invalid field takes its most restrictive value (spec 2.1).
     */
    private fun Preferences.toLoadState(): AppLockSettingsLoadState {
        val raw = asMap()
        val marker = raw[LOAD_STATE_KEY]
        if (marker != null) {
            when (marker) {
                LOAD_STATE_CORRUPTED -> {
                    return AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED)
                }
                LOAD_STATE_NORMAL -> Unit
                else -> return AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.INVALID_VALUE)
            }
        }
        val discreetRaw = raw[Keys.DISCREET_NOTIFICATIONS]
        if (marker == null && discreetRaw == null) {
            return AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings.DEFAULTS)
        }
        val discreet = when (discreetRaw) {
            null -> AppLockPrivacySettings.DEFAULT_DISCREET_NOTIFICATIONS
            is Boolean -> discreetRaw
            // Invalid persisted value: most restrictive (spec 2.1).
            else -> true
        }
        return AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreetNotifications = discreet))
    }
}
