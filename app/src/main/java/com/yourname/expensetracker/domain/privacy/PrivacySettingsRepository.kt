package com.yourname.expensetracker.domain.privacy

import kotlinx.coroutines.flow.Flow

interface PrivacySettingsRepository {
    fun observeSettings(): Flow<PrivacySettings>
    suspend fun getSettings(): PrivacySettings
    suspend fun updateSettings(transform: (PrivacySettings) -> PrivacySettings)
}
