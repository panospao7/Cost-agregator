package com.yourname.expensetracker.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

private val Context.aiDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ai_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

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
        val ASSISTANT_ENABLED           = booleanPreferencesKey("ai_assistant_enabled")
        val QUERY_INTERPRETATION_ENABLED = booleanPreferencesKey("ai_query_interpretation_enabled")
        val DASHBOARD_BRIEFING_ENABLED  = booleanPreferencesKey("ai_dashboard_briefing_enabled")
        val REVIEW_EXPLANATION_ENABLED  = booleanPreferencesKey("ai_review_explanation_enabled")
        val RECEIPT_ASSIST_ENABLED      = booleanPreferencesKey("ai_receipt_assist_enabled")
        val WARRANTY_EXTRACTION_ENABLED = booleanPreferencesKey("ai_warranty_extraction_enabled")
        val RECEIPT_IMAGE_CLOUD_ENABLED = booleanPreferencesKey("ai_receipt_image_cloud_enabled")
        val RECEIPT_ITEM_CATEGORIZATION_ENABLED = booleanPreferencesKey("ai_receipt_item_categorization_enabled")
        val CATEGORIZATION_FALLBACK_ENABLED = booleanPreferencesKey("ai_categorization_fallback_enabled")
        val DEDUPE_JUDGE_ENABLED        = booleanPreferencesKey("ai_dedupe_judge_enabled")
        val PROACTIVE_BRIEFINGS         = booleanPreferencesKey("ai_proactive_briefings")
        val RECEIPT_QUICK_SAVE          = booleanPreferencesKey("ai_receipt_quick_save")
        val REVIEW_QUICK_APPROVE        = booleanPreferencesKey("ai_review_quick_approve")
        val REDACT_BEFORE_CLOUD         = booleanPreferencesKey("ai_redact_before_cloud")
        val WIFI_ONLY_FOR_CLOUD         = booleanPreferencesKey("ai_wifi_only_for_cloud")
        val STORE_CONVERSATION_HISTORY  = booleanPreferencesKey("ai_store_conversation_history")
        val PREFERRED_MODE              = stringPreferencesKey("ai_preferred_mode")
    }

    // -------------------------------------------------------------------------
    // AiSettingsRepository
    // -------------------------------------------------------------------------

    override fun settings(): Flow<AiSettings> =
        context.aiDataStore.data
            .catch { error ->
                when (error) {
                    is IOException -> {
                        Timber.e(error, "AI settings DataStore read failed; using empty preferences")
                        emit(emptyPreferences())
                    }

                    else -> throw error
                }
            }
            .map { prefs -> prefs.toAiSettings() }

    override suspend fun update(transform: (AiSettings) -> AiSettings) {
        context.aiDataStore.edit { prefs ->
            val current = prefs.toAiSettings()
            val updated = transform(current)
            prefs[Keys.AI_ENABLED]                 = updated.aiEnabled
            prefs[Keys.ALLOW_CLOUD_AI]             = updated.allowCloudAi
            prefs[Keys.ALLOW_ON_DEVICE_AI]         = updated.allowOnDeviceAi
            prefs[Keys.ASSISTANT_ENABLED]          = updated.assistantEnabled
            prefs[Keys.QUERY_INTERPRETATION_ENABLED] = updated.queryInterpretationEnabled
            prefs[Keys.DASHBOARD_BRIEFING_ENABLED] = updated.dashboardBriefingEnabled
            prefs[Keys.REVIEW_EXPLANATION_ENABLED] = updated.reviewExplanationEnabled
            prefs[Keys.RECEIPT_ASSIST_ENABLED]     = updated.receiptAssistEnabled
            prefs[Keys.WARRANTY_EXTRACTION_ENABLED] = updated.warrantyExtractionEnabled
            prefs[Keys.RECEIPT_IMAGE_CLOUD_ENABLED] = updated.receiptImageCloudEnabled
            prefs[Keys.RECEIPT_ITEM_CATEGORIZATION_ENABLED] = updated.receiptItemCategorizationEnabled
            prefs[Keys.CATEGORIZATION_FALLBACK_ENABLED] = updated.categorizationFallbackEnabled
            prefs[Keys.DEDUPE_JUDGE_ENABLED]       = updated.dedupeJudgeEnabled
            prefs[Keys.PROACTIVE_BRIEFINGS]        = updated.proactiveBriefingsEnabled
            prefs[Keys.RECEIPT_QUICK_SAVE]         = updated.receiptQuickSaveEnabled
            prefs[Keys.REVIEW_QUICK_APPROVE]       = updated.reviewQuickApproveEnabled
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
        aiEnabled                 = this[Keys.AI_ENABLED]                 ?: true,
        allowCloudAi              = this[Keys.ALLOW_CLOUD_AI]             ?: false,
        allowOnDeviceAi           = this[Keys.ALLOW_ON_DEVICE_AI]         ?: true,
        assistantEnabled          = this[Keys.ASSISTANT_ENABLED]          ?: false,
        queryInterpretationEnabled = this[Keys.QUERY_INTERPRETATION_ENABLED] ?: false,
        dashboardBriefingEnabled  = this[Keys.DASHBOARD_BRIEFING_ENABLED] ?: false,
        reviewExplanationEnabled  = this[Keys.REVIEW_EXPLANATION_ENABLED] ?: false,
        receiptAssistEnabled      = this[Keys.RECEIPT_ASSIST_ENABLED]      ?: false,
        receiptImageCloudEnabled  = this[Keys.RECEIPT_IMAGE_CLOUD_ENABLED] ?: false,
        receiptItemCategorizationEnabled = this[Keys.RECEIPT_ITEM_CATEGORIZATION_ENABLED] ?: false,
        categorizationFallbackEnabled = this[Keys.CATEGORIZATION_FALLBACK_ENABLED] ?: false,
        dedupeJudgeEnabled        = this[Keys.DEDUPE_JUDGE_ENABLED]        ?: false,
        proactiveBriefingsEnabled = this[Keys.PROACTIVE_BRIEFINGS]        ?: false,
        receiptQuickSaveEnabled   = this[Keys.RECEIPT_QUICK_SAVE]         ?: false,
        reviewQuickApproveEnabled = this[Keys.REVIEW_QUICK_APPROVE]       ?: false,
        redactBeforeCloud         = this[Keys.REDACT_BEFORE_CLOUD]        ?: true,
        wifiOnlyForCloud          = this[Keys.WIFI_ONLY_FOR_CLOUD]        ?: false,
        storeConversationHistory  = this[Keys.STORE_CONVERSATION_HISTORY] ?: false,
        preferredMode             = this[Keys.PREFERRED_MODE]
            ?.let { runCatching { AiMode.valueOf(it) }.getOrNull() }
            ?: AiMode.AUTO,
        warrantyExtractionEnabled = this[Keys.WARRANTY_EXTRACTION_ENABLED] ?: true
    )
}
