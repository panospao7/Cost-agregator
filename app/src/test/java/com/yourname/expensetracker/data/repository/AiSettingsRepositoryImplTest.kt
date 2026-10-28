package com.yourname.expensetracker.data.repository

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.service.InstallOrigin
import com.yourname.expensetracker.domain.ai.service.InstallProvenance
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * B1c (INV-7, INV-22): the [AiSettings] data class is the single source of defaults and the
 * DataStore mapper only falls back to it. The B1c mapper tests run directly on [Preferences]
 * values (no file, no Android runtime). The B2b tests (INV-23) drive the real
 * [AiInsightSettingsMigration] through a real file-backed [PreferenceDataStoreFactory] store in a
 * [TemporaryFolder]; no shared singleton state or Android runtime is involved, and the production
 * `ai_settings` delegate wiring is pinned by a source scan.
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
        // The "never rewritten" half is pinned by the INV-7 tests below (spec 4 rule 5), which
        // run the real AiInsightSettingsMigration over a seeded store.
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

    // ── B2b: transaction insight key + DataMigration (INV-22, INV-23, AE-33..37) ──────────────

    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private var fileCounter = 0

    private val insightKey = booleanPreferencesKey("ai_transaction_insight_enabled")
    private val briefingKey = booleanPreferencesKey("ai_dashboard_briefing_enabled")

    @After
    fun tearDownStores() {
        scopes.forEach { it.cancel() }
    }

    private class FixedProvenance(private val origin: InstallOrigin) : InstallProvenance {
        var calls = 0
        override fun classify(): InstallOrigin {
            calls++
            return origin
        }
    }

    private class ThrowingProvenance : InstallProvenance {
        override fun classify(): InstallOrigin = throw IllegalStateException("package manager failure")
    }

    /** Simulates values already stored by an earlier app version; runs before the real migration. */
    private fun seed(block: (MutablePreferences) -> Unit) = object : DataMigration<Preferences> {
        override suspend fun shouldMigrate(currentData: Preferences) = true
        override suspend fun migrate(currentData: Preferences): Preferences {
            val mutable = currentData.toMutablePreferences()
            block(mutable)
            return mutable.toPreferences()
        }
        override suspend fun cleanUp() = Unit
    }

    private fun store(vararg migrations: DataMigration<Preferences>): DataStore<Preferences> {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes += scope
        return PreferenceDataStoreFactory.create(
            scope = scope,
            migrations = migrations.toList(),
            produceFile = { File(tmp.root, "ai_settings_${fileCounter++}.preferences_pb") }
        )
    }

    @Test
    fun `B2b insight defaults to false in model and mapper fallback`() {
        assertFalse(AiSettings().transactionInsightEnabled)
        assertFalse(map(emptyPreferences()).transactionInsightEnabled)
    }

    @Test
    fun `B2b stored explicit insight key is mapped`() {
        assertTrue(map(preferencesOf(insightKey to true)).transactionInsightEnabled)
        assertFalse(map(preferencesOf(insightKey to false)).transactionInsightEnabled)
    }

    @Test
    fun `AE-33 empty store with NEW_INSTALL stores insight true`() = runBlocking {
        val provenance = FixedProvenance(InstallOrigin.NEW_INSTALL)
        val ds = store(AiInsightSettingsMigration(provenance))
        val repo = AiSettingsRepositoryImpl({ ds })

        val settings = repo.settings().first()

        assertTrue(settings.transactionInsightEnabled)
    }

    @Test
    fun `AE-33 key is stored explicitly by the migration`() = runBlocking {
        val ds = store(AiInsightSettingsMigration(FixedProvenance(InstallOrigin.NEW_INSTALL)))

        assertEquals(true, ds.data.first()[insightKey])
    }

    @Test
    fun `AE-34 upgrade with stored briefing true stores insight true`() = runBlocking {
        val ds = store(
            seed { it[briefingKey] = true },
            AiInsightSettingsMigration(FixedProvenance(InstallOrigin.UPGRADE))
        )

        assertEquals(true, ds.data.first()[insightKey])
    }

    @Test
    fun `AE-34 upgrade with stored briefing false stores insight false`() = runBlocking {
        val provenance = FixedProvenance(InstallOrigin.NEW_INSTALL)
        val ds = store(
            seed { it[briefingKey] = false },
            AiInsightSettingsMigration(provenance)
        )

        // Stored briefing wins over provenance (rule 2a): provenance is not even consulted.
        assertEquals(false, ds.data.first()[insightKey])
        assertEquals(0, provenance.calls)
    }

    @Test
    fun `AE-35 empty store with UPGRADE stores insight false`() = runBlocking {
        val ds = store(AiInsightSettingsMigration(FixedProvenance(InstallOrigin.UPGRADE)))

        assertEquals(false, ds.data.first()[insightKey])
    }

    @Test
    fun `UNKNOWN provenance stores insight false`() = runBlocking {
        val ds = store(AiInsightSettingsMigration(FixedProvenance(InstallOrigin.UNKNOWN)))

        assertEquals(false, ds.data.first()[insightKey])
    }

    @Test
    fun `AE-36 stored insight false with briefing true is never re-derived`() = runBlocking {
        val provenance = FixedProvenance(InstallOrigin.NEW_INSTALL)
        val migration = AiInsightSettingsMigration(provenance)
        val prefs = preferencesOf(insightKey to false, briefingKey to true)

        assertFalse(migration.shouldMigrate(prefs))

        val ds = store(
            seed {
                it[insightKey] = false
                it[briefingKey] = true
            },
            migration
        )
        val stored = ds.data.first()
        assertEquals(false, stored[insightKey])
        assertEquals(true, stored[briefingKey])
        assertEquals(0, provenance.calls)
    }

    @Test
    fun `AE-36 stored insight true is never overwritten by an UPGRADE provenance`() = runBlocking {
        val ds = store(
            seed { it[insightKey] = true },
            AiInsightSettingsMigration(FixedProvenance(InstallOrigin.UPGRADE))
        )

        assertEquals(true, ds.data.first()[insightKey])
    }

    @Test
    fun `AE-37 provenance throws stores insight false and settings emits no exception`() = runBlocking {
        val ds = store(AiInsightSettingsMigration(ThrowingProvenance()))
        val repo = AiSettingsRepositoryImpl({ ds })

        val settings = repo.settings().first()

        assertFalse(settings.transactionInsightEnabled)
    }

    @Test
    fun `AE-37 provenance throws leaves key stored as false`() = runBlocking {
        val ds = store(AiInsightSettingsMigration(ThrowingProvenance()))

        assertEquals(false, ds.data.first()[insightKey])
    }

    @Test
    fun `migration runs before the first edit so update does not materialize the read fallback`() = runBlocking {
        val ds = store(AiInsightSettingsMigration(FixedProvenance(InstallOrigin.NEW_INSTALL)))
        val repo = AiSettingsRepositoryImpl({ ds })

        // First interaction with the store is a write, with no prior read.
        repo.update { it.copy(aiEnabled = false) }

        val stored = ds.data.first()
        assertEquals("migration result must survive the first update()", true, stored[insightKey])
        assertEquals(false, stored[booleanPreferencesKey("ai_enabled")])
        assertTrue(repo.settings().first().transactionInsightEnabled)
    }

    @Test
    fun `update writes the user toggle and a later migration pass does not re-derive it`() = runBlocking {
        val ds = store(AiInsightSettingsMigration(FixedProvenance(InstallOrigin.NEW_INSTALL)))
        val repo = AiSettingsRepositoryImpl({ ds })

        repo.update { it.copy(transactionInsightEnabled = false) }

        assertEquals(false, ds.data.first()[insightKey])
        assertFalse(repo.settings().first().transactionInsightEnabled)
        assertFalse(AiInsightSettingsMigration(FixedProvenance(InstallOrigin.NEW_INSTALL)).shouldMigrate(ds.data.first()))
    }

    @Test
    fun `absent key triggers shouldMigrate and cleanUp leaves stored preferences unchanged`() = runBlocking {
        val provenance = FixedProvenance(InstallOrigin.NEW_INSTALL)
        val migration = AiInsightSettingsMigration(provenance)

        assertTrue(migration.shouldMigrate(emptyPreferences()))
        assertFalse(migration.shouldMigrate(preferencesOf(insightKey to false)))

        val ds = store(
            seed {
                it[warrantyKey] = true
                it[briefingKey] = false
            },
            migration
        )
        val before = ds.data.first().asMap()
        migration.cleanUp()
        val after = ds.data.first().asMap()

        assertEquals(before, after)
        assertEquals(false, after[insightKey])
    }

    // ── INV-7 / spec 4 rule 5: the warranty flag is never rewritten by the migration ──────────

    private val unrelatedSeed: (MutablePreferences) -> Unit = {
        it[booleanPreferencesKey("ai_enabled")] = false
        it[booleanPreferencesKey("ai_wifi_only_for_cloud")] = true
        it[stringPreferencesKey("ai_preferred_mode")] = "CLOUD"
    }

    private suspend fun assertOnlyInsightKeyAdded(
        warranty: Boolean,
        origin: InstallOrigin,
        expectedInsight: Boolean
    ) {
        val ds = store(
            seed {
                it[warrantyKey] = warranty
                unrelatedSeed(it)
            },
            AiInsightSettingsMigration(FixedProvenance(origin))
        )

        val expected = preferencesOf(
            warrantyKey to warranty,
            booleanPreferencesKey("ai_enabled") to false,
            booleanPreferencesKey("ai_wifi_only_for_cloud") to true,
            stringPreferencesKey("ai_preferred_mode") to "CLOUD",
            insightKey to expectedInsight
        ).asMap()

        assertEquals("warranty=$warranty origin=$origin", expected, ds.data.first().asMap())
    }

    @Test
    fun `INV-7 stored warranty true is not rewritten on NEW_INSTALL`() = runBlocking {
        assertOnlyInsightKeyAdded(warranty = true, origin = InstallOrigin.NEW_INSTALL, expectedInsight = true)
    }

    @Test
    fun `INV-7 stored warranty true is not rewritten on UPGRADE`() = runBlocking {
        assertOnlyInsightKeyAdded(warranty = true, origin = InstallOrigin.UPGRADE, expectedInsight = false)
    }

    @Test
    fun `INV-7 stored warranty false is not rewritten on NEW_INSTALL`() = runBlocking {
        assertOnlyInsightKeyAdded(warranty = false, origin = InstallOrigin.NEW_INSTALL, expectedInsight = true)
    }

    @Test
    fun `INV-7 stored warranty false is not rewritten on UPGRADE`() = runBlocking {
        assertOnlyInsightKeyAdded(warranty = false, origin = InstallOrigin.UPGRADE, expectedInsight = false)
    }

    // ── Production wiring guard (INV-23): the real delegate registers the migration ───────────

    @Test
    fun `ai_settings delegate declares produceMigrations with AiInsightSettingsMigration over PackageInstallProvenance`() {
        val source = locateProductionFile("com/yourname/expensetracker/data/repository/AiSettingsRepositoryImpl.kt")
            .readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

        val delegate = Regex(
            """by\s+preferencesDataStore\s*\((.*?)\n\)""",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(source).map { it.groupValues[1] }
            .singleOrNull { it.contains("\"ai_settings\"") }
            ?: error("Expected exactly one preferencesDataStore delegate named ai_settings")

        val wiring = Regex(
            """produceMigrations\s*=\s*\{\s*(\w+)\s*->\s*listOf\(\s*AiInsightSettingsMigration\(\s*""" +
                """PackageInstallProvenance\(\s*\1\s*\)\s*\)\s*\)\s*\}"""
        )
        assertTrue(
            "ai_settings delegate must declare produceMigrations = { ctx -> listOf(" +
                "AiInsightSettingsMigration(PackageInstallProvenance(ctx))) }",
            wiring.containsMatchIn(delegate)
        )
    }

    private fun locateProductionFile(relativeToJavaRoot: String): File {
        val userDir = System.getProperty("user.dir") ?: "."
        val candidates = listOf(
            File("src/main/java", relativeToJavaRoot),
            File("app/src/main/java", relativeToJavaRoot),
            File(userDir, "src/main/java/$relativeToJavaRoot"),
            File(userDir, "app/src/main/java/$relativeToJavaRoot")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Could not locate $relativeToJavaRoot. user.dir=$userDir")
    }
}
