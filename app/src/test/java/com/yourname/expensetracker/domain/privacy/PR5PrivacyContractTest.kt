package com.yourname.expensetracker.domain.privacy

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.privacy.DefaultSensitiveHashingService
import com.yourname.expensetracker.domain.bank.BankApiConfig
import com.yourname.expensetracker.domain.bank.BankApiIntegration
import com.yourname.expensetracker.domain.bank.BankMovementType
import com.yourname.expensetracker.domain.bank.BankTransaction
import com.yourname.expensetracker.domain.diagnostics.NoOpOperationRunHandle
import com.yourname.expensetracker.domain.diagnostics.OperationRunHandle
import com.yourname.expensetracker.domain.diagnostics.OperationRunRecorder
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.data.database.entity.BankConnection
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * U-PR5-PRIVACY-CONTRACT acceptance tests:
 *
 * ## U-PRIVACY-01: RawStorageMode semantics
 * - sanitizeBankDescription_store_raw_preserves_text
 * - sanitizeBankDescription_store_redacted_replaces_text
 * - sanitizeBankDescription_metadata_only_returns_null
 * - sanitizeBankDescription_do_not_store_returns_null
 * - sanitizeBankDescription_null_input_returns_null_for_all_modes
 * - bankApiIntegration_uses_rawBankStatementStorageMode_not_rawOcrStorageMode
 * - rawPersistencePolicyResolver_bank_api_uses_rawBankStatementStorageMode
 * - rawPersistencePolicyResolver_bank_statement_uses_rawBankStatementStorageMode
 * - fail_closed_defaults_sets_bank_statement_mode_to_do_not_store
 *
 * ## U-PRIVACY-02: EffectiveCloudAiPolicy authoritative gate
 * - effectiveCloudAiPolicy_blocks_when_privacy_disables_cloud
 * - effectiveCloudAiPolicy_blocks_when_ai_settings_disables_cloud
 * - effectiveCloudAiPolicy_allows_when_both_enable_cloud
 *
 * ## U-PRIVACY-03: Retention/export redaction scope
 * - retention_registry_covers_pending_reviews_and_background_job_runs (tested via RetentionRegistryTest)
 */
class PR5PrivacyContractTest {

    // ── U-PRIVACY-01: RawContentSanitizer.sanitizeBankDescription ─────────────

    @Test
    fun sanitizeBankDescription_store_raw_preserves_text() {
        val result = RawContentSanitizer.sanitizeBankDescription("TRANSFER TO JOHN DOE", RawStorageMode.STORE_RAW)
        assertEquals("TRANSFER TO JOHN DOE", result)
    }

    @Test
    fun sanitizeBankDescription_store_redacted_replaces_text() {
        val result = RawContentSanitizer.sanitizeBankDescription("TRANSFER TO JOHN DOE", RawStorageMode.STORE_REDACTED)
        assertEquals("[REDACTED]", result)
    }

    @Test
    fun sanitizeBankDescription_metadata_only_returns_null() {
        val result = RawContentSanitizer.sanitizeBankDescription("TRANSFER TO JOHN DOE", RawStorageMode.STORE_METADATA_ONLY)
        assertNull(result)
    }

    @Test
    fun sanitizeBankDescription_do_not_store_returns_null() {
        val result = RawContentSanitizer.sanitizeBankDescription("TRANSFER TO JOHN DOE", RawStorageMode.DO_NOT_STORE)
        assertNull(result)
    }

    @Test
    fun sanitizeBankDescription_null_input_returns_null_for_all_modes() {
        for (mode in RawStorageMode.entries) {
            val result = RawContentSanitizer.sanitizeBankDescription(null, mode)
            assertNull("Mode $mode should return null for null input", result)
        }
    }

    // ── U-PRIVACY-01: BankApiIntegration uses rawBankStatementStorageMode ─────

    private lateinit var integration: BankApiIntegration
    private val connection = BankConnection(
        bankId = "revolut",
        bankName = "Revolut",
        countryCode = "EU",
        defaultCategoryId = 42L
    )

    private class BlockInvokingRecorder : OperationRunRecorder {
        override suspend fun start(operationType: String, actor: String?, metadata: SafeEventMetadata): OperationRunHandle = NoOpOperationRunHandle
        override suspend fun <T> runOperation(operationType: String, actor: String?, metadata: SafeEventMetadata, block: suspend (OperationRunHandle) -> T): T = block(NoOpOperationRunHandle)
        override suspend fun recoverStaleRunningOperationRuns(staleAgeMs: Long) = Unit
    }

    @Before
    fun setUp() {
        BankApiConfig.isStubMode = true
    }

    @Test
    fun bankApiIntegration_uses_rawBankStatementStorageMode_not_rawOcrStorageMode() = runTest {
        // Set rawOcrStorageMode to STORE_RAW but rawBankStatementStorageMode to DO_NOT_STORE.
        // If the fix is correct, bank descriptions should be null (DO_NOT_STORE), not raw.
        val settings = PrivacySettings(
            rawOcrStorageMode = RawStorageMode.STORE_RAW,
            rawBankStatementStorageMode = RawStorageMode.DO_NOT_STORE
        )
        integration = BankApiIntegration(
            timeProvider = FakeTimeProvider(),
            coordinator = mockk(relaxed = true),
            writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true),
            operationRunRecorder = BlockInvokingRecorder(),
            hashingService = DefaultSensitiveHashingService(),
            privacySettingsRepository = mockk(relaxed = true) {
                coEvery { getSettings() } returns settings
            }
        )

        val request = integration.mapTransactionToExpense(
            BankTransaction(
                id = "tx-1",
                date = 1_000L,
                amount = -50.0,
                currency = "EUR",
                merchant = "Coffee Shop",
                description = "Card purchase at Coffee Shop",
                reference = "REF-123",
                movementType = BankMovementType.PURCHASE
            ),
            connection,
            syncRunId = 1L
        )

        // Notes should be null because DO_NOT_STORE nulls both description and reference
        assertNull("Notes must be null when rawBankStatementStorageMode=DO_NOT_STORE", request.notes)
    }

    @Test
    fun bankApiIntegration_redacts_description_when_store_redacted() = runTest {
        val settings = PrivacySettings(
            rawBankStatementStorageMode = RawStorageMode.STORE_REDACTED
        )
        integration = BankApiIntegration(
            timeProvider = FakeTimeProvider(),
            coordinator = mockk(relaxed = true),
            writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true),
            operationRunRecorder = BlockInvokingRecorder(),
            hashingService = DefaultSensitiveHashingService(),
            privacySettingsRepository = mockk(relaxed = true) {
                coEvery { getSettings() } returns settings
            }
        )

        val request = integration.mapTransactionToExpense(
            BankTransaction(
                id = "tx-2",
                date = 2_000L,
                amount = -25.0,
                currency = "EUR",
                merchant = "Store",
                description = "Sensitive description",
                reference = "REF-456",
                movementType = BankMovementType.PURCHASE
            ),
            connection,
            syncRunId = 2L
        )

        // Notes should contain [REDACTED] for both description and reference
        assertNotNull(request.notes)
        assertTrue("Notes must contain [REDACTED]", request.notes!!.contains("[REDACTED]"))
        assertFalse("Notes must NOT contain raw description", request.notes!!.contains("Sensitive description"))
    }

    // ── U-PRIVACY-01: RawPersistencePolicyResolver uses rawBankStatementStorageMode ──

    @Test
    fun rawPersistencePolicyResolver_bank_api_uses_rawBankStatementStorageMode() {
        val settings = PrivacySettings(
            rawOcrStorageMode = RawStorageMode.STORE_RAW,
            rawBankStatementStorageMode = RawStorageMode.DO_NOT_STORE
        )
        val resolver = RawPersistencePolicyResolver(
            object : PrivacySettingsRepository {
                override fun observeSettings() = flowOf(settings)
                override fun observeLoadState() = flowOf(PrivacySettingsLoadState.Loaded(settings))
                override suspend fun getSettings() = settings
                override suspend fun getLoadState() = PrivacySettingsLoadState.Loaded(settings)
                override suspend fun updateSettings(transform: (PrivacySettings) -> PrivacySettings) {}
            }
        )
        val policy = resolver.forSourceSync(RawSourceType.BANK_API, settings)
        assertEquals(RawStorageMode.DO_NOT_STORE, policy.mode)
    }

    @Test
    fun rawPersistencePolicyResolver_bank_statement_uses_rawBankStatementStorageMode() {
        val settings = PrivacySettings(
            rawOcrStorageMode = RawStorageMode.STORE_RAW,
            rawBankStatementStorageMode = RawStorageMode.STORE_METADATA_ONLY
        )
        val resolver = RawPersistencePolicyResolver(
            object : PrivacySettingsRepository {
                override fun observeSettings() = flowOf(settings)
                override fun observeLoadState() = flowOf(PrivacySettingsLoadState.Loaded(settings))
                override suspend fun getSettings() = settings
                override suspend fun getLoadState() = PrivacySettingsLoadState.Loaded(settings)
                override suspend fun updateSettings(transform: (PrivacySettings) -> PrivacySettings) {}
            }
        )
        val policy = resolver.forSourceSync(RawSourceType.BANK_STATEMENT, settings)
        assertEquals(RawStorageMode.STORE_METADATA_ONLY, policy.mode)
    }

    // ── U-PRIVACY-01: FAIL_CLOSED_DEFAULTS ────────────────────────────────────

    @Test
    fun fail_closed_defaults_sets_bank_statement_mode_to_do_not_store() {
        assertEquals(
            RawStorageMode.DO_NOT_STORE,
            PrivacySettings.FAIL_CLOSED_DEFAULTS.rawBankStatementStorageMode
        )
    }

    @Test
    fun default_settings_sets_bank_statement_mode_to_store_redacted() {
        assertEquals(
            RawStorageMode.STORE_REDACTED,
            PrivacySettings().rawBankStatementStorageMode
        )
    }

    // ── U-PRIVACY-02: EffectiveCloudAiPolicy ──────────────────────────────────

    @Test
    fun effectiveCloudAiPolicy_blocks_when_privacy_disables_cloud() = runTest {
        val privacyRepo = object : PrivacySettingsRepository {
            private val s = PrivacySettings(cloudAiEnabled = false)
            override fun observeSettings() = flowOf(s)
            override fun observeLoadState() = flowOf(PrivacySettingsLoadState.Loaded(s))
            override suspend fun getSettings() = s
            override suspend fun getLoadState() = PrivacySettingsLoadState.Loaded(s)
            override suspend fun updateSettings(transform: (PrivacySettings) -> PrivacySettings) {}
        }
        val aiRepo = object : com.yourname.expensetracker.domain.ai.service.AiSettingsRepository {
            override fun settings() = flowOf(com.yourname.expensetracker.domain.ai.model.AiSettings(allowCloudAi = true))
            override suspend fun update(transform: (com.yourname.expensetracker.domain.ai.model.AiSettings) -> com.yourname.expensetracker.domain.ai.model.AiSettings) {}
        }
        val resolver = EffectiveCloudAiPolicyResolver(privacyRepo, aiRepo)
        val policy = resolver.resolve()

        assertFalse(policy.cloudAllowed)
        assertNotNull(policy.reason)
        assertTrue(policy.reason!!.contains("Privacy settings"))
    }

    @Test
    fun effectiveCloudAiPolicy_blocks_when_ai_settings_disables_cloud() = runTest {
        val privacyRepo = object : PrivacySettingsRepository {
            private val s = PrivacySettings(cloudAiEnabled = true)
            override fun observeSettings() = flowOf(s)
            override fun observeLoadState() = flowOf(PrivacySettingsLoadState.Loaded(s))
            override suspend fun getSettings() = s
            override suspend fun getLoadState() = PrivacySettingsLoadState.Loaded(s)
            override suspend fun updateSettings(transform: (PrivacySettings) -> PrivacySettings) {}
        }
        val aiRepo = object : com.yourname.expensetracker.domain.ai.service.AiSettingsRepository {
            override fun settings() = flowOf(com.yourname.expensetracker.domain.ai.model.AiSettings(allowCloudAi = false))
            override suspend fun update(transform: (com.yourname.expensetracker.domain.ai.model.AiSettings) -> com.yourname.expensetracker.domain.ai.model.AiSettings) {}
        }
        val resolver = EffectiveCloudAiPolicyResolver(privacyRepo, aiRepo)
        val policy = resolver.resolve()

        assertFalse(policy.cloudAllowed)
        assertNotNull(policy.reason)
        assertTrue(policy.reason!!.contains("AI settings"))
    }

    @Test
    fun effectiveCloudAiPolicy_allows_when_both_enable_cloud() = runTest {
        val privacyRepo = object : PrivacySettingsRepository {
            private val s = PrivacySettings(cloudAiEnabled = true)
            override fun observeSettings() = flowOf(s)
            override fun observeLoadState() = flowOf(PrivacySettingsLoadState.Loaded(s))
            override suspend fun getSettings() = s
            override suspend fun getLoadState() = PrivacySettingsLoadState.Loaded(s)
            override suspend fun updateSettings(transform: (PrivacySettings) -> PrivacySettings) {}
        }
        val aiRepo = object : com.yourname.expensetracker.domain.ai.service.AiSettingsRepository {
            override fun settings() = flowOf(com.yourname.expensetracker.domain.ai.model.AiSettings(allowCloudAi = true))
            override suspend fun update(transform: (com.yourname.expensetracker.domain.ai.model.AiSettings) -> com.yourname.expensetracker.domain.ai.model.AiSettings) {}
        }
        val resolver = EffectiveCloudAiPolicyResolver(privacyRepo, aiRepo)
        val policy = resolver.resolve()

        assertTrue(policy.cloudAllowed)
        assertNull(policy.reason)
    }

    @Test
    fun effectiveCloudAiPolicy_requireAllowed_throws_when_blocked() = runTest {
        val resolver = EffectiveCloudAiPolicyResolver.failClosedNoAi()
        val policy = resolver.resolve()

        assertFalse(policy.cloudAllowed)
        try {
            policy.requireAllowed(PrivacyCapability.CLOUD_AI_DAILY_BRIEFING)
            fail("Expected SecurityException from requireAllowed()")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("Cloud AI blocked"))
        }
    }
}
