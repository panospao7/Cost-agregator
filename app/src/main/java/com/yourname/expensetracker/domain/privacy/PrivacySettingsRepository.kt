package com.yourname.expensetracker.domain.privacy

import kotlinx.coroutines.flow.Flow

interface PrivacySettingsRepository {
    fun observeSettings(): Flow<PrivacySettings>
    fun observeLoadState(): Flow<PrivacySettingsLoadState>
    suspend fun getSettings(): PrivacySettings
    suspend fun getLoadState(): PrivacySettingsLoadState
    suspend fun updateSettings(transform: (PrivacySettings) -> PrivacySettings)
}
