package com.yourname.expensetracker.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yourname.expensetracker.domain.ai.service.AiEngagementRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.aiEngagementDataStore: DataStore<Preferences> by preferencesDataStore(name = "ai_engagement")

@Singleton
class AiEngagementRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : AiEngagementRepository {

    private object Keys {
        val LAST_DELIVERED_DASHBOARD_BRIEFING = stringPreferencesKey("last_delivered_dashboard_briefing")
        val LAST_OPENED_DASHBOARD_BRIEFING = stringPreferencesKey("last_opened_dashboard_briefing")
    }

    override suspend fun getLastDeliveredDashboardBriefingKey(): String? =
        context.aiEngagementDataStore.data.first()[Keys.LAST_DELIVERED_DASHBOARD_BRIEFING]

    override suspend fun setLastDeliveredDashboardBriefingKey(targetKey: String) {
        context.aiEngagementDataStore.edit { prefs ->
            prefs[Keys.LAST_DELIVERED_DASHBOARD_BRIEFING] = targetKey
        }
    }

    override suspend fun getLastOpenedDashboardBriefingKey(): String? =
        context.aiEngagementDataStore.data.first()[Keys.LAST_OPENED_DASHBOARD_BRIEFING]

    override suspend fun setLastOpenedDashboardBriefingKey(targetKey: String) {
        context.aiEngagementDataStore.edit { prefs ->
            prefs[Keys.LAST_OPENED_DASHBOARD_BRIEFING] = targetKey
        }
    }
}
