package com.yourname.expensetracker.domain.privacy

import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * U-PR5 completion acceptance tests.
 *
 * Verifies the remaining items from the U-PR5 implementation plan:
 *
 * 1. FAIL_CLOSED_DEFAULTS blocks bank statement storage
 *    — rawBankStatementStorageMode is DO_NOT_STORE in fail-closed defaults.
 *
 * 2. Cloud OCR gated by EffectiveCloudAiPolicy
 *    — CLOUD_AI_RECEIPT_OCR capability is checked via requireAllowed()
 *      before any cloud OCR call, and redactBeforeCloud is respected.
 *
 * 3. BankStatementLifecycleProcessor uses correct storage mode
 *    — RawPersistencePolicyResolver maps BANK_STATEMENT and BANK_API
 *      sources to rawBankStatementStorageMode (not rawOcrStorageMode).
 */
class UPR5CompletionTest {

    // ── U-PR5-ITEM-1: FAIL_CLOSED_DEFAULTS blocks bank statement storage ─────

    @Test
    fun fail_closed_defaults_blocks_bank_statement_storage() {
        // Contract: when privacy settings fail to load (DataStore corruption),
        // FAIL_CLOSED_DEFAULTS ensures bank statement data is NOT stored.
        val defaults = PrivacySettings.FAIL_CLOSED_DEFAULTS

        assertEquals(
            "FAIL_CLOSED_DEFAULTS must set rawBankStatementStorageMode to DO_NOT_STORE",
            RawStorageMode.DO_NOT_STORE,
            defaults.rawBankStatementStorageMode
        )

        // Also verify other related modes are DO_NOT_STORE for consistency
        assertEquals(RawStorageMode.DO_NOT_STORE, defaults.rawOcrStorageMode)
        assertEquals(RawStorageMode.DO_NOT_STORE, defaults.rawNotificationStorageMode)
        assertEquals(RawStorageMode.DO_NOT_STORE, defaults.emailReceiptStorageMode)

        // Cloud AI must be disabled
        assertFalse("Cloud AI must be disabled in FAIL_CLOSED_DEFAULTS", defaults.cloudAiEnabled)
        assertFalse(
            "Bank statement AI must be disabled in FAIL_CLOSED_DEFAULTS",
            defaults.bankStatementAiEnabled
        )
    }

    // ── U-PR5-ITEM-2: Cloud OCR gated by EffectiveCloudAiPolicy ──────────────

    @Test
    fun cloud_ocr_gated_by_effective_policy_when_cloud_disabled() = runTest {
        // Privacy settings: cloud AI disabled
        val privacyRepo = object : PrivacySettingsRepository {
            private val s = PrivacySettings(cloudAiEnabled = false)
            override fun observeSettings() = flowOf(s)
            override fun observeLoadState() = flowOf(PrivacySettingsLoadState.Loaded(s))
            override suspend fun getSettings() = s
            override suspend fun getLoadState() = PrivacySettingsLoadState.Loaded(s)
            override suspend fun updateSettings(
                transform: (PrivacySettings) -> PrivacySettings
            ) {}
        }
        val aiRepo = object : AiSettingsRepository {
            override fun settings() = flowOf(AiSettings(allowCloudAi = true))
            override suspend fun update(
                transform: (AiSettings) -> AiSettings
            ) {}
        }

        val resolver = EffectiveCloudAiPolicyResolver(privacyRepo, aiRepo)
        val policy = resolver.resolve()

        assertFalse("Policy must block cloud when privacy disables it", policy.cloudAllowed)

        // requireAllowed must throw for CLOUD_AI_RECEIPT_OCR
        try {
            policy.requireAllowed(PrivacyCapability.CLOUD_AI_RECEIPT_OCR)
            fail("Expected SecurityException when cloud AI is disabled")
        } catch (e: SecurityException) {
            assertTrue(
                "Error message must reference cloud AI being blocked",
                e.message!!.contains("Cloud AI blocked")
            )
        }
    }

    @Test
    fun cloud_ocr_gated_by_effective_policy_when_both_enabled() = runTest {
        // Both privacy and AI settings enable cloud AI
        val privacyRepo = object : PrivacySettingsRepository {
            private val s = PrivacySettings(
                cloudAiEnabled = true,
                receiptImageCloudEnabled = true
            )
            override fun observeSettings() = flowOf(s)
            override fun observeLoadState() = flowOf(PrivacySettingsLoadState.Loaded(s))
            override suspend fun getSettings() = s
            override suspend fun getLoadState() = PrivacySettingsLoadState.Loaded(s)
            override suspend fun updateSettings(
                transform: (PrivacySettings) -> PrivacySettings
            ) {}
        }
        val aiRepo = object : AiSettingsRepository {
            override fun settings() = flowOf(AiSettings(
                allowCloudAi = true,
                receiptImageCloudEnabled = true
            ))
            override suspend fun update(
                transform: (AiSettings) -> AiSettings
            ) {}
        }

        val resolver = EffectiveCloudAiPolicyResolver(privacyRepo, aiRepo)
        val policy = resolver.resolve()

        assertTrue("Policy must allow cloud when both settings enable it", policy.cloudAllowed)

        // requireAllowed must NOT throw for CLOUD_AI_RECEIPT_OCR
        policy.requireAllowed(PrivacyCapability.CLOUD_AI_RECEIPT_OCR)
        // No exception means success
    }

    @Test
    fun cloud_ocr_policy_respects_redactBeforeCloud() = runTest {
        // When redactBeforeCloud is true, the policy must reflect it
        val privacyRepo = object : PrivacySettingsRepository {
            private val s = PrivacySettings(
                cloudAiEnabled = true,
                redactBeforeCloud = true
            )
            override fun observeSettings() = flowOf(s)
            override fun observeLoadState() = flowOf(PrivacySettingsLoadState.Loaded(s))
            override suspend fun getSettings() = s
            override suspend fun getLoadState() = PrivacySettingsLoadState.Loaded(s)
            override suspend fun updateSettings(
                transform: (PrivacySettings) -> PrivacySettings
            ) {}
        }
        val aiRepo = object : AiSettingsRepository {
            override fun settings() = flowOf(AiSettings(
                allowCloudAi = true,
                redactBeforeCloud = false
            ))
            override suspend fun update(
                transform: (AiSettings) -> AiSettings
            ) {}
        }

        val resolver = EffectiveCloudAiPolicyResolver(privacyRepo, aiRepo)
        val policy = resolver.resolve()

        assertTrue("redactBeforeCloud must be true when privacy settings require it",
            policy.redactBeforeCloud)
        assertTrue("Cloud must be allowed when both settings enable it",
            policy.cloudAllowed)

        // requireAllowed must succeed since cloud is enabled
        policy.requireAllowed(PrivacyCapability.CLOUD_AI_RECEIPT_OCR)
    }

    @Test
    fun cloud_ocr_requireAllowed_uses_fail_closed_no_ai() = runTest {
        // failClosedNoAi() resolver must always block
        val resolver = EffectiveCloudAiPolicyResolver.failClosedNoAi()
        val policy = resolver.resolve()

        assertFalse(policy.cloudAllowed)
        assertTrue("redactBeforeCloud must be true in fail-closed",
            policy.redactBeforeCloud)

        try {
            policy.requireAllowed(PrivacyCapability.CLOUD_AI_RECEIPT_OCR)
            fail("Expected SecurityException from fail-closed resolver")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("Cloud AI blocked"))
        }
    }

    // ── U-PR5-ITEM-3: Bank statement processor uses correct storage mode ─────

    @Test
    fun bank_statement_processor_uses_correct_storage_mode() {
        // Contract: RawPersistencePolicyResolver maps BANK_STATEMENT and BANK_API
        // to rawBankStatementStorageMode, NOT rawOcrStorageMode.
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
                override suspend fun updateSettings(
                    transform: (PrivacySettings) -> PrivacySettings
                ) {}
            }
        )

        // BANK_STATEMENT should use rawBankStatementStorageMode (STORE_METADATA_ONLY),
        // NOT rawOcrStorageMode (STORE_RAW)
        val bankStatementPolicy = resolver.forSourceSync(
            RawSourceType.BANK_STATEMENT, settings
        )
        assertEquals(
            "BANK_STATEMENT must use rawBankStatementStorageMode",
            RawStorageMode.STORE_METADATA_ONLY,
            bankStatementPolicy.mode
        )
        assertFalse(
            "BANK_STATEMENT with STORE_METADATA_ONLY must not allow parsed merchant",
            bankStatementPolicy.allowParsedMerchant
        )

        // BANK_API should also use rawBankStatementStorageMode
        val bankApiPolicy = resolver.forSourceSync(
            RawSourceType.BANK_API, settings
        )
        assertEquals(
            "BANK_API must use rawBankStatementStorageMode",
            RawStorageMode.STORE_METADATA_ONLY,
            bankApiPolicy.mode
        )

        // RECEIPT_OCR should still use rawOcrStorageMode (STORE_RAW) — unaffected
        val ocrPolicy = resolver.forSourceSync(
            RawSourceType.RECEIPT_OCR, settings
        )
        assertEquals(
            "RECEIPT_OCR must still use rawOcrStorageMode",
            RawStorageMode.STORE_RAW,
            ocrPolicy.mode
        )
    }

    @Test
    fun bank_statement_processor_respects_do_not_store_mode() {
        // When rawBankStatementStorageMode is DO_NOT_STORE, bank statement
        // data must not be stored.
        val settings = PrivacySettings(
            rawBankStatementStorageMode = RawStorageMode.DO_NOT_STORE
        )

        val resolver = RawPersistencePolicyResolver(
            object : PrivacySettingsRepository {
                override fun observeSettings() = flowOf(settings)
                override fun observeLoadState() = flowOf(PrivacySettingsLoadState.Loaded(settings))
                override suspend fun getSettings() = settings
                override suspend fun getLoadState() = PrivacySettingsLoadState.Loaded(settings)
                override suspend fun updateSettings(
                    transform: (PrivacySettings) -> PrivacySettings
                ) {}
            }
        )

        val policy = resolver.forSourceSync(RawSourceType.BANK_STATEMENT, settings)

        assertEquals(RawStorageMode.DO_NOT_STORE, policy.mode)
        assertFalse("DO_NOT_STORE must not allow parsed merchant", policy.allowParsedMerchant)
        assertFalse("DO_NOT_STORE must not allow parsed items", policy.allowParsedItems)
        // Bank statements need dedup hash even under DO_NOT_STORE
        assertTrue("DO_NOT_STORE must allow external ID hash for dedup",
            policy.allowExternalIdHash)
    }
}
