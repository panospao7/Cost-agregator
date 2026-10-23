package com.yourname.expensetracker.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiSettings
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1c (INV-7, INV-22): the [AiSettings] data class is the single source of defaults and the
 * DataStore mapper only falls back to it. The mapper is exercised directly on [Preferences]
 * values, so no DataStore file, Android runtime or shared singleton state is involved.
 */
class AiSettingsRepositoryImplTest {

    private val repository = AiSettingsRepositoryImpl(mockk<Context>(relaxed = true))

    private val warrantyKey = booleanPreferencesKey("ai_warranty_extraction_enabled")

    private fun map(prefs: Preferences): AiSettings = with(repository) { prefs.toAiSettings() }

    @Test
    fun `AE-7 fresh install defaults warranty extraction to false in model and mapper`() {
        assertFalse(AiSettings().warrantyExtractionEnabled)
        assertFalse(map(emptyPreferences()).warrantyExtractionEnabled)
    }

    @Test
    fun `AE-8 stored explicit warranty true survives mapping`() {
        // The "never rewritten" half is pinned with the B2b DataMigration (spec §4.5 item 5).
        assertTrue(map(preferencesOf(warrantyKey to true)).warrantyExtractionEnabled)
    }

    @Test
    fun `AE-8 stored explicit warranty false is honored`() {
        assertFalse(map(preferencesOf(warrantyKey to false)).warrantyExtractionEnabled)
    }

    @Test
    fun `AE-38 emptyPreferences maps to AiSettings defaults`() {
        assertEquals(AiSettings(), map(emptyPreferences()))
    }

    @Test
    fun `AE-38 invalid stored preferred mode falls back to the model default`() {
        val prefs = preferencesOf(stringPreferencesKey("ai_preferred_mode") to "NOT_A_MODE")

        assertEquals(AiSettings().preferredMode, map(prefs).preferredMode)
        assertEquals(AiMode.AUTO, map(prefs).preferredMode)
    }

    @Test
    fun `AE-38 explicit stored values override defaults without changing other fields`() {
        val prefs = preferencesOf(
            booleanPreferencesKey("ai_enabled") to false,
            booleanPreferencesKey("ai_redact_before_cloud") to false
        )

        assertEquals(
            AiSettings(aiEnabled = false, redactBeforeCloud = false),
            map(prefs)
        )
    }
}
