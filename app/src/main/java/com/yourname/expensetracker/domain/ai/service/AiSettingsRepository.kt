package com.yourname.expensetracker.domain.ai.service

import com.yourname.expensetracker.domain.ai.model.AiSettings
import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for user-facing AI preferences.
 *
 * Backed by DataStore (not Room) so that disabling AI wipes no financial records.
 * All writes are expressed as a transform to avoid read-modify-write races.
 */
interface AiSettingsRepository {
    /** Live stream of the current settings. Emits immediately on first collection. */
    fun settings(): Flow<AiSettings>

    /** Apply [transform] to the current settings and persist the result. */
    suspend fun update(transform: (AiSettings) -> AiSettings)
}
