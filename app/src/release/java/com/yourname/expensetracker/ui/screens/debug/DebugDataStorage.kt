package com.yourname.expensetracker.ui.screens.debug

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Release no-op stub for debug data persistence.
 * Debug data storage is completely disabled in release builds
 * for privacy and security.
 */
@Singleton
class DebugDataStorage @Inject constructor() {

    suspend fun save(debugData: DebugData) {
        // No-op in release builds
    }

    suspend fun load(): DebugData? {
        return null
    }

    suspend fun clear() {
        // No-op in release builds
    }
}
