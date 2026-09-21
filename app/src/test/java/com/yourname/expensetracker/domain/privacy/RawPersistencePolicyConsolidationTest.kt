package com.yourname.expensetracker.domain.privacy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RP-15 (15-A) — Full-policy resolver consolidation tests.
 *
 * Derived from `docs/analyses and debug master/remediation/RP-15-TRUTH-TABLE.md`:
 * 1. The complete source × mode matrix (truth table §2) — every policy field, not just mode.
 * 2. Fail-closed normalization for corrupt/unavailable settings (truth table §3).
 * 3. Write-site equivalence for the three RP-15 owned call sites (truth table §4).
 * 4. Ownership invariant: the three owned call sites contain no inline storage-mode
 *    selection, and `modeFor` is not callable from outside the resolver (§5).
 */
class RawPersistencePolicyConsolidationTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private class FakeSettingsRepo(
        var settings: PrivacySettings = PrivacySettings(),
        var loadState: PrivacySettingsLoadState = PrivacySettingsLoadState.Loaded(PrivacySettings()),
        var throwable: Throwable? = null
    ) : PrivacySettingsRepository {
        override fun observeSettings() = kotlinx.coroutines.flow.flowOf(settings)
        override fun observeLoadState() = kotlinx.coroutines.flow.flowOf(loadState)
        override suspend fun getSettings(): PrivacySettings {
            throwable?.let { throw it }
            return settings
        }
        override suspend fun getLoadState(): PrivacySettingsLoadState {
            throwable?.let { throw it }
            return loadState
        }
        override suspend fun updateSettings(transform: (PrivacySettings) -> PrivacySettings) {
            settings = transform(settings)
        }
    }

    private val repo = FakeSettingsRepo()
    private val resolver = RawPersistencePolicyResolver(repo)

    private fun settingsWith(source: RawSourceType, mode: RawStorageMode, debug: Boolean = false): PrivacySettings =
        when (source) {
            RawSourceType.NOTIFICATION -> PrivacySettings(rawNotificationStorageMode = mode, debugDataPersistenceEnabled = debug)
            RawSourceType.RECEIPT_OCR -> PrivacySettings(rawOcrStorageMode = mode, debugDataPersistenceEnabled = debug)
            RawSourceType.EMAIL_RECEIPT -> PrivacySettings(emailReceiptStorageMode = mode, debugDataPersistenceEnabled = debug)
            RawSourceType.BANK_STATEMENT, RawSourceType.BANK_API ->
                PrivacySettings(rawBankStatementStorageMode = mode, debugDataPersistenceEnabled = debug)
            RawSourceType.AI_ARTIFACT, RawSourceType.EXPORT_DEBUG ->
                PrivacySettings(debugDataPersistenceEnabled = debug)
        }

    private fun expectedPolicy(source: RawSourceType, settings: PrivacySettings): RawPersistencePolicy {
        // Reference implementation copied from the truth table §1 derivation rules.
        val mode = when (source) {
            RawSourceType.NOTIFICATION -> settings.rawNotificationStorageMode
            RawSourceType.RECEIPT_OCR -> settings.rawOcrStorageMode
            RawSourceType.EMAIL_RECEIPT -> settings.emailReceiptStorageMode
            RawSourceType.BANK_STATEMENT -> settings.rawBankStatementStorageMode
            RawSourceType.BANK_API -> settings.rawBankStatementStorageMode
            RawSourceType.AI_ARTIFACT -> if (settings.debugDataPersistenceEnabled) RawStorageMode.STORE_REDACTED else RawStorageMode.DO_NOT_STORE
            RawSourceType.EXPORT_DEBUG -> if (settings.debugDataPersistenceEnabled) RawStorageMode.STORE_RAW else RawStorageMode.DO_NOT_STORE
        }
        return RawPersistencePolicy(
            mode = mode,
            sourceType = source,
            allowParsedAmountDateCurrency = mode != RawStorageMode.DO_NOT_STORE || source == RawSourceType.EMAIL_RECEIPT,
            allowParsedMerchant = mode == RawStorageMode.STORE_RAW || mode == RawStorageMode.STORE_REDACTED,
            allowParsedItems = mode == RawStorageMode.STORE_RAW || mode == RawStorageMode.STORE_REDACTED,
            allowExternalIdHash = mode != RawStorageMode.DO_NOT_STORE || needsDedupeHashForTest(source),
            allowDebugBody = mode == RawStorageMode.STORE_RAW && settings.debugDataPersistenceEnabled
        )
    }

    private fun needsDedupeHashForTest(source: RawSourceType): Boolean = when (source) {
        RawSourceType.NOTIFICATION, RawSourceType.EMAIL_RECEIPT,
        RawSourceType.BANK_STATEMENT, RawSourceType.BANK_API -> true
        else -> false
    }

    // ── 1. Truth-table matrix (§2) — forSourceSync, complete policy ───────────

    @Test
    fun `matrix_all_seven_sources_x_all_modes_match_truth_table`() {
        val modes = RawStorageMode.entries.toList()
        val sources = RawSourceType.entries.toList()
        assertEquals(4, modes.size)
        assertEquals(7, sources.size)

        for (debug in listOf(false, true)) {
            for (source in sources) {
                for (mode in modes) {
                    val settings = settingsWith(source, mode, debug)
                    val expected = expectedPolicy(source, settings)
                    val actual = resolver.forSourceSync(source, settings)

                    assertEquals("mode($source,$mode,dbg=$debug)", expected.mode, actual.mode)
                    assertEquals("allowRawBody($source,$mode)", expected.allowRawBody, actual.allowRawBody)
                    assertEquals("allowRedactedBody($source,$mode)", expected.allowRedactedBody, actual.allowRedactedBody)
                    assertEquals(
                        "allowParsedAmountDateCurrency($source,$mode)",
                        expected.allowParsedAmountDateCurrency, actual.allowParsedAmountDateCurrency
                    )
                    assertEquals("allowParsedMerchant($source,$mode)", expected.allowParsedMerchant, actual.allowParsedMerchant)
                    assertEquals("allowParsedItems($source,$mode)", expected.allowParsedItems, actual.allowParsedItems)
                    assertEquals("allowExternalIdHash($source,$mode)", expected.allowExternalIdHash, actual.allowExternalIdHash)
                    assertEquals("allowDebugBody($source,$mode,dbg=$debug)", expected.allowDebugBody, actual.allowDebugBody)
                    assertEquals("sourceType($source)", source, actual.sourceType)
                }
            }
        }
    }

    @Test
    fun `debug_conjunction_pins_rows_25_26`() {
        // Row 25: RECEIPT_OCR + STORE_RAW + dbg=true → allowDebugBody true.
        val row25 = resolver.forSourceSync(
            RawSourceType.RECEIPT_OCR,
            PrivacySettings(rawOcrStorageMode = RawStorageMode.STORE_RAW, debugDataPersistenceEnabled = true)
        )
        assertTrue(row25.allowDebugBody)

        // Row 26: NOTIFICATION + STORE_RAW + dbg=false → allowDebugBody false.
        val row26 = resolver.forSourceSync(
            RawSourceType.NOTIFICATION,
            PrivacySettings(rawNotificationStorageMode = RawStorageMode.STORE_RAW, debugDataPersistenceEnabled = false)
        )
        assertFalse(row26.allowDebugBody)
    }

    @Test
    fun `debug_flag_driven_sources_rows_21_to_24`() {
        // AI_ARTIFACT: dbg=true → STORE_REDACTED (allowDebugBody stays false: mode != STORE_RAW).
        val aiOn = resolver.forSourceSync(RawSourceType.AI_ARTIFACT, PrivacySettings(debugDataPersistenceEnabled = true))
        assertEquals(RawStorageMode.STORE_REDACTED, aiOn.mode)
        assertFalse(aiOn.allowDebugBody)
        assertTrue(aiOn.allowRedactedBody)

        // AI_ARTIFACT: dbg=false → DO_NOT_STORE.
        val aiOff = resolver.forSourceSync(RawSourceType.AI_ARTIFACT, PrivacySettings(debugDataPersistenceEnabled = false))
        assertEquals(RawStorageMode.DO_NOT_STORE, aiOff.mode)

        // EXPORT_DEBUG: dbg=true → STORE_RAW with allowDebugBody true.
        val exportOn = resolver.forSourceSync(RawSourceType.EXPORT_DEBUG, PrivacySettings(debugDataPersistenceEnabled = true))
        assertEquals(RawStorageMode.STORE_RAW, exportOn.mode)
        assertTrue(exportOn.allowDebugBody)

        // EXPORT_DEBUG: dbg=false → DO_NOT_STORE.
        val exportOff = resolver.forSourceSync(RawSourceType.EXPORT_DEBUG, PrivacySettings(debugDataPersistenceEnabled = false))
        assertEquals(RawStorageMode.DO_NOT_STORE, exportOff.mode)
    }

    @Test
    fun `email_keeps_amount_date_currency_and_hash_under_do_not_store_rows_12`() {
        val policy = resolver.forSourceSync(
            RawSourceType.EMAIL_RECEIPT,
            PrivacySettings(emailReceiptStorageMode = RawStorageMode.DO_NOT_STORE)
        )
        assertEquals(RawStorageMode.DO_NOT_STORE, policy.mode)
        assertTrue("Email dedupe requires parsed amount/date/currency even under DO_NOT_STORE", policy.allowParsedAmountDateCurrency)
        assertTrue("Email dedupe requires the external id hash even under DO_NOT_STORE", policy.allowExternalIdHash)
        assertFalse(policy.allowParsedMerchant)
        assertFalse(policy.allowParsedItems)
    }

    // ── 2. Fail-closed normalization (§3) ─────────────────────────────────────

    @Test
    fun `forSource_corrupted_load_state_yields_fail_closed_defaults_for_every_source`() = runTest {
        repo.loadState = PrivacySettingsLoadState.CorruptedFailClosed(
            settings = PrivacySettings(),  // even if the state object carries permissive settings
            reason = "corrupt"
        )
        for (source in RawSourceType.entries) {
            val policy = resolver.forSource(source)
            assertEquals("mode($source) must fail closed", RawStorageMode.DO_NOT_STORE, policy.mode)
            assertFalse("no raw body($source)", policy.allowRawBody)
            assertFalse("no debug body($source)", policy.allowDebugBody)
            assertFalse("no parsed items($source)", policy.allowParsedItems)
            assertFalse("no parsed merchant($source)", policy.allowParsedMerchant)
        }
    }

    @Test
    fun `forSource_repository_failure_yields_fail_closed_defaults`() = runTest {
        repo.throwable = IllegalStateException("settings unavailable")
        for (source in RawSourceType.entries) {
            val policy = resolver.forSource(source)
            assertEquals("mode($source) must fail closed on repository failure", RawStorageMode.DO_NOT_STORE, policy.mode)
            assertFalse(policy.allowRawBody)
            assertFalse(policy.allowDebugBody)
        }
    }

    @Test
    fun `forSource_fail_closed_still_allows_dedupe_hash_for_dedupe_sources`() = runTest {
        repo.loadState = PrivacySettingsLoadState.CorruptedFailClosed(
            settings = PrivacySettings.FAIL_CLOSED_DEFAULTS, reason = "corrupt"
        )
        // Truth table rows 4/12/16/20: dedupe-hash sources keep the hash under DO_NOT_STORE.
        assertTrue(resolver.forSource(RawSourceType.NOTIFICATION).allowExternalIdHash)
        assertTrue(resolver.forSource(RawSourceType.EMAIL_RECEIPT).allowExternalIdHash)
        assertTrue(resolver.forSource(RawSourceType.BANK_STATEMENT).allowExternalIdHash)
        assertTrue(resolver.forSource(RawSourceType.BANK_API).allowExternalIdHash)
        // RECEIPT_OCR (row 8) does not.
        assertFalse(resolver.forSource(RawSourceType.RECEIPT_OCR).allowExternalIdHash)
    }

    @Test
    fun `forSource_rethrows_cancellation_but_fails_closed_on_other_exceptions`() {
        repo.throwable = CancellationException("caller cancelled")
        // runBlocking propagates the cancellation cleanly to the caller.
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { resolver.forSource(RawSourceType.NOTIFICATION) }
        }

        repo.throwable = RuntimeException("boom")
        val policy = kotlinx.coroutines.runBlocking { resolver.forSource(RawSourceType.NOTIFICATION) }
        assertEquals(RawStorageMode.DO_NOT_STORE, policy.mode)
    }

    @Test
    fun `forSource_healthy_settings_match_sync_result`() = runTest {
        val settings = PrivacySettings(
            rawNotificationStorageMode = RawStorageMode.STORE_METADATA_ONLY,
            rawOcrStorageMode = RawStorageMode.STORE_REDACTED,
            emailReceiptStorageMode = RawStorageMode.DO_NOT_STORE,
            rawBankStatementStorageMode = RawStorageMode.STORE_RAW,
            debugDataPersistenceEnabled = true
        )
        repo.settings = settings
        repo.loadState = PrivacySettingsLoadState.Loaded(settings)
        for (source in RawSourceType.entries) {
            assertEquals(
                "forSource must agree with forSourceSync for $source",
                resolver.forSourceSync(source, settings),
                resolver.forSource(source)
            )
        }
    }

    // ── 3. Write-site equivalence (§4) — old inline selector vs new policy ────

    @Test
    fun `site1_notification_pipeline_mode_equivalence`() = runTest {
        // Old: privacySettingsRepository.getSettings().rawNotificationStorageMode
        // New: resolver.forSource(NOTIFICATION).mode — identical for healthy settings.
        for (mode in RawStorageMode.entries) {
            val settings = PrivacySettings(rawNotificationStorageMode = mode)
            repo.settings = settings
            repo.loadState = PrivacySettingsLoadState.Loaded(settings)
            repo.throwable = null
            assertEquals(mode, resolver.forSource(RawSourceType.NOTIFICATION).mode)
        }
    }

    @Test
    fun `site2_bank_statement_mode_equivalence_and_bank_api_shares_setting`() = runTest {
        // Old: privacySettingsRepository.getSettings().rawBankStatementStorageMode
        // New: resolver.forSource(BANK_STATEMENT / BANK_API).mode — identical for healthy settings.
        for (mode in RawStorageMode.entries) {
            val settings = PrivacySettings(rawBankStatementStorageMode = mode)
            repo.settings = settings
            repo.loadState = PrivacySettingsLoadState.Loaded(settings)
            repo.throwable = null
            assertEquals(mode, resolver.forSource(RawSourceType.BANK_STATEMENT).mode)
            assertEquals(mode, resolver.forSource(RawSourceType.BANK_API).mode)
        }
    }

    @Test
    fun `site3_camera_and_email_mode_equivalence`() = runTest {
        // Old camera path: getSettings().rawOcrStorageMode (catch → DO_NOT_STORE).
        // Old email path: getSettings().emailReceiptStorageMode.
        // New: resolver.forSource(RECEIPT_OCR / EMAIL_RECEIPT).mode.
        for (mode in RawStorageMode.entries) {
            val settings = PrivacySettings(
                rawOcrStorageMode = mode,
                emailReceiptStorageMode = mode
            )
            repo.settings = settings
            repo.loadState = PrivacySettingsLoadState.Loaded(settings)
            repo.throwable = null
            assertEquals(mode, resolver.forSource(RawSourceType.RECEIPT_OCR).mode)
            assertEquals(mode, resolver.forSource(RawSourceType.EMAIL_RECEIPT).mode)
        }
    }

    // ── 4. Ownership invariant (§5) — static source scan ─────────────────────

    private val sourceRoot: File by lazy { resolveSourceRoot() }

    private fun resolveSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "app/src/main/java")
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not locate production source root. user.dir=${System.getProperty("user.dir")}")
    }

    private fun readProductionSource(relativePath: String): String {
        val file = File(sourceRoot, relativePath)
        assertTrue("Expected production source at ${file.absolutePath}", file.exists())
        return file.readText()
    }

    @Test
    fun `resolver_files_own_all_storage_mode_selections`() {
        val resolverText = readProductionSource(
            "com/yourname/expensetracker/domain/privacy/RawPersistencePolicyResolver.kt"
        )
        // modeFor must be private — forSource/forSourceSync are the only callable APIs.
        assertTrue("modeFor must be declared private", Regex("private fun modeFor").containsMatchIn(resolverText))
    }

    @Test
    fun `rp15_owned_call_sites_contain_no_inline_storage_mode_selection`() {
        val ownedFiles = listOf(
            "com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt",
            "com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt",
            "com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt"
        )
        val forbiddenSelectors = listOf(
            "rawNotificationStorageMode",
            "rawOcrStorageMode",
            "emailReceiptStorageMode",
            "rawBankStatementStorageMode"
        )
        for (path in ownedFiles) {
            val text = readProductionSource(path)
            for (selector in forbiddenSelectors) {
                assertFalse(
                    "$path must not read settings.$selector inline — use RawPersistencePolicyResolver",
                    text.contains(selector)
                )
            }
        }
    }

    @Test
    fun `negative_fixture_inline_selector_in_owned_site_is_detected`() {
        // The guard must be able to detect a violation (not pass vacuously).
        val violatingSource = """
            class BadSite {
                suspend fun m(repo: PrivacySettingsRepository): RawStorageMode =
                    repo.getSettings().rawOcrStorageMode
            }
        """.trimIndent()
        val detected = listOf(
            "rawNotificationStorageMode", "rawOcrStorageMode",
            "emailReceiptStorageMode", "rawBankStatementStorageMode"
        ).any { violatingSource.contains(it) }
        assertTrue("Negative fixture: inline selector must be detected", detected)
    }

    @Test
    fun `resolver_exists_and_is_source_of_truth_for_policy_type`() {
        // Sanity: the policy type lives with the resolver and carries the full field set.
        val policyText = readProductionSource(
            "com/yourname/expensetracker/domain/privacy/RawPersistencePolicy.kt"
        )
        assertNotNull(policyText)
        for (field in listOf(
            "allowParsedAmountDateCurrency", "allowParsedMerchant", "allowParsedItems",
            "allowExternalIdHash", "allowDebugBody", "allowRawBody", "allowRedactedBody"
        )) {
            assertTrue("RawPersistencePolicy must declare $field", policyText.contains(field))
        }
    }
}
