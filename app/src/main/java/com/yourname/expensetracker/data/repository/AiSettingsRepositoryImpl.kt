package com.yourname.expensetracker.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.aiDataStore: DataStore<Preferences> by preferencesDataStore(name = "ai_settings")

@Singleton
class AiSettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : AiSettingsRepository {

    // -------------------------------------------------------------------------
    // Keys
    // -------------------------------------------------------------------------

    private object Keys {
        val AI_ENABLED                  = booleanPreferencesKey("ai_enabled")
        val ALLOW_CLOUD_AI              = booleanPreferencesKey("ai_allow_cloud")
        val ALLOW_ON_DEVICE_AI          = booleanPreferencesKey("ai_allow_on_device")
        val DASHBOARD_BRIEFING_ENABLED  = booleanPreferencesKey("ai_dashboard_briefing_enabled")
        val REVIEW_EXPLANATION_ENABLED  = booleanPreferencesKey("ai_review_explanation_enabled")
        val PROACTIVE_BRIEFINGS         = booleanPreferencesKey("ai_proactive_briefings")
        val REDACT_BEFORE_CLOUD         = booleanPreferencesKey("ai_redact_before_cloud")
        val WIFI_ONLY_FOR_CLOUD         = booleanPreferencesKey("ai_wifi_only_for_cloud")
        val STORE_CONVERSATION_HISTORY  = booleanPreferencesKey("ai_store_conversation_history")
        val PREFERRED_MODE              = stringPreferencesKey("ai_preferred_mode")
    }

    // -------------------------------------------------------------------------
    // AiSettingsRepository
    // -------------------------------------------------------------------------

    override fun settings(): Flow<AiSettings> =
        context.aiDataStore.data.map { prefs -> prefs.toAiSettings() }

    override suspend fun update(transform: (AiSettings) -> AiSettings) {
        context.aiDataStore.edit { prefs ->
            val current = prefs.toAiSettings()
            val updated = transform(current)
            prefs[Keys.AI_ENABLED]                 = updated.aiEnabled
            prefs[Keys.ALLOW_CLOUD_AI]             = updated.allowCloudAi
            prefs[Keys.ALLOW_ON_DEVICE_AI]         = updated.allowOnDeviceAi
            prefs[Keys.DASHBOARD_BRIEFING_ENABLED] = updated.dashboardBriefingEnabled
            prefs[Keys.REVIEW_EXPLANATION_ENABLED] = updated.reviewExplanationEnabled
            prefs[Keys.PROACTIVE_BRIEFINGS]        = updated.proactiveBriefingsEnabled
            prefs[Keys.REDACT_BEFORE_CLOUD]        = updated.redactBeforeCloud
            prefs[Keys.WIFI_ONLY_FOR_CLOUD]        = updated.wifiOnlyForCloud
            prefs[Keys.STORE_CONVERSATION_HISTORY] = updated.storeConversationHistory
            prefs[Keys.PREFERRED_MODE]             = updated.preferredMode.name
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun Preferences.toAiSettings(): AiSettings = AiSettings(
        aiEnabled                 = this[Keys.AI_ENABLED]                 ?: false,
        allowCloudAi              = this[Keys.ALLOW_CLOUD_AI]             ?: false,
        allowOnDeviceAi           = this[Keys.ALLOW_ON_DEVICE_AI]         ?: true,
        dashboardBriefingEnabled  = this[Keys.DASHBOARD_BRIEFING_ENABLED] ?: false,
        reviewExplanationEnabled  = this[Keys.REVIEW_EXPLANATION_ENABLED] ?: false,
        proactiveBriefingsEnabled = this[Keys.PROACTIVE_BRIEFINGS]        ?: false,
        redactBeforeCloud         = this[Keys.REDACT_BEFORE_CLOUD]        ?: true,
        wifiOnlyForCloud          = this[Keys.WIFI_ONLY_FOR_CLOUD]        ?: false,
        storeConversationHistory  = this[Keys.STORE_CONVERSATION_HISTORY] ?: false,
        preferredMode             = this[Keys.PREFERRED_MODE]
            ?.let { runCatching { AiMode.valueOf(it) }.getOrNull() }
            ?: AiMode.AUTO
    )
}
