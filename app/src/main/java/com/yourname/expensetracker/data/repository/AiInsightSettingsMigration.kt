package com.yourname.expensetracker.data.repository

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.yourname.expensetracker.domain.ai.service.InstallOrigin
import com.yourname.expensetracker.domain.ai.service.InstallProvenance
import kotlin.coroutines.cancellation.CancellationException
import timber.log.Timber

/**
 * Preference keys shared by [AiSettingsRepositoryImpl] and [AiInsightSettingsMigration].
 * Defined once so the migration and the mapper can never disagree on a key name.
 */
internal object AiSettingsMigrationKeys {
    val TRANSACTION_INSIGHT_ENABLED = booleanPreferencesKey("ai_transaction_insight_enabled")
    val DASHBOARD_BRIEFING_ENABLED = booleanPreferencesKey("ai_dashboard_briefing_enabled")
}

/**
 * The single resolver of `ai_transaction_insight_enabled` (INV-23, spec 4). Registered through
 * `produceMigrations` on the `ai_settings` DataStore, so DataStore runs it before the first read
 * and before the first edit. That ordering prevents `update()` (which writes every key) from
 * materializing the read fallback first. No startup coroutine is involved.
 *
 * Resolution, only while the key is absent:
 * 1. stored `ai_dashboard_briefing_enabled` present -> copy its value;
 * 2. otherwise [InstallProvenance]: NEW_INSTALL -> true, UPGRADE -> false, UNKNOWN -> false.
 *
 * Contract: an existing value is never overwritten (shouldMigrate is false once the key exists),
 * and the migration never throws because of provenance (any failure there is UNKNOWN). Only
 * coroutine cancellation is propagated.
 */
internal class AiInsightSettingsMigration(
    private val installProvenance: InstallProvenance
) : DataMigration<Preferences> {

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        !currentData.contains(AiSettingsMigrationKeys.TRANSACTION_INSIGHT_ENABLED)

    override suspend fun migrate(currentData: Preferences): Preferences {
        val resolved = resolve(currentData)
        val mutable = currentData.toMutablePreferences()
        mutable[AiSettingsMigrationKeys.TRANSACTION_INSIGHT_ENABLED] = resolved
        return mutable.toPreferences()
    }

    override suspend fun cleanUp() = Unit

    private fun resolve(currentData: Preferences): Boolean {
        val storedBriefing = currentData[AiSettingsMigrationKeys.DASHBOARD_BRIEFING_ENABLED]
        if (storedBriefing != null) return storedBriefing
        return when (classifySafely()) {
            InstallOrigin.NEW_INSTALL -> true
            InstallOrigin.UPGRADE, InstallOrigin.UNKNOWN -> false
        }
    }

    private fun classifySafely(): InstallOrigin =
        try {
            installProvenance.classify()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Timber.w("Install provenance failed during AI settings migration; using UNKNOWN")
            InstallOrigin.UNKNOWN
        }
}
