package com.yourname.expensetracker.domain.privacy

import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which dedicated [PrivacySettings]/[AiSettings] inputs gate a cloud capability.
 *
 * RP-15 (15-B) matrix — global, image, bank, and dedicated-setting inputs:
 *
 * | Capability                    | global (cloudAllowed) | image | bank | dedicated setting                              |
 * |-------------------------------|-----------------------|-------|------|------------------------------------------------|
 * | CLOUD_AI_RECEIPT_ASSIST       | required              | —     | —    | none → global-only (EXPLICIT product decision) |
 * | CLOUD_AI_ITEM_CATEGORIZATION  | required              | —     | —    | none → global-only (EXPLICIT product decision) |
 * | CLOUD_AI_WARRANTY_EXTRACTION  | required              | —     | —    | none → global-only (EXPLICIT product decision) |
 * | CLOUD_AI_DAILY_BRIEFING       | required              | —     | —    | none → global-only (EXPLICIT product decision) |
 * | CLOUD_AI_GENERAL              | required              | —     | —    | none → global-only (EXPLICIT product decision) |
 * | CLOUD_AI_RECEIPT_OCR          | required              | req.  | —    | receiptImageCloudEnabled (privacy + ai)        |
 * | RECEIPT_IMAGE_CLOUD_UPLOAD    | required              | req.¹ | —    | receiptImageCloudEnabled (privacy + ai)        |
 * | CLOUD_AI_BANK_STATEMENT       | required              | —     | req. | bankStatementAiEnabled                         |
 * | AI_BANK_STATEMENT_PARSING     | required              | —     | req. | bankStatementAiEnabled (alias)                 |
 * | anything else                 | FAIL CLOSED           | —     | —    | —                                              |
 *
 * ¹ RECEIPT_IMAGE_CLOUD_UPLOAD is additionally denied while redaction-before-cloud
 *   is required (existing policy, preserved).
 *
 * Where no dedicated setting exists, GLOBAL-ONLY is the explicit product decision
 * (documented in the RP-15 plan §15-B) — a per-capability toggle must NOT be
 * invented implicitly later.
 */
enum class CloudCapabilityInput {
    /** Gated by the global cloud flag only. */
    GLOBAL_ONLY,
    /** Gated by the global cloud flag + receipt-image-upload settings. */
    IMAGE,
    /** IMAGE semantics plus the redaction-before-cloud suppression. */
    IMAGE_WITH_REDACTION,
    /** Gated by the global cloud flag + the bank-statement AI setting. */
    BANK
}

/**
 * RP-15 (15-B): the EXPLICIT registry of cloud capabilities. Exhaustive —
 * non-cloud, unknown, and future capabilities are rejected (fail closed),
 * never implicitly allowed.
 */
private val CLOUD_CAPABILITIES: Map<PrivacyCapability, CloudCapabilityInput> = mapOf(
    PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST to CloudCapabilityInput.GLOBAL_ONLY,
    PrivacyCapability.CLOUD_AI_RECEIPT_OCR to CloudCapabilityInput.IMAGE,
    PrivacyCapability.RECEIPT_IMAGE_CLOUD_UPLOAD to CloudCapabilityInput.IMAGE_WITH_REDACTION,
    PrivacyCapability.CLOUD_AI_ITEM_CATEGORIZATION to CloudCapabilityInput.GLOBAL_ONLY,
    PrivacyCapability.CLOUD_AI_WARRANTY_EXTRACTION to CloudCapabilityInput.GLOBAL_ONLY,
    PrivacyCapability.CLOUD_AI_DAILY_BRIEFING to CloudCapabilityInput.GLOBAL_ONLY,
    PrivacyCapability.CLOUD_AI_GENERAL to CloudCapabilityInput.GLOBAL_ONLY,
    PrivacyCapability.CLOUD_AI_BANK_STATEMENT to CloudCapabilityInput.BANK,
    PrivacyCapability.AI_BANK_STATEMENT_PARSING to CloudCapabilityInput.BANK
)

data class EffectiveCloudAiPolicy(
    val cloudAllowed: Boolean,
    val reason: String?,
    val redactBeforeCloud: Boolean,
    val receiptImageUploadAllowed: Boolean,
    val bankStatementCloudAllowed: Boolean
) {
    /**
     * P8-PR1 (NEW-P8-005), tightened by RP-15 (15-B).
     *
     * Checks whether the specific [capability] is allowed, exhaustively for the
     * EXPLICITLY REGISTERED cloud capabilities only (see [CLOUD_CAPABILITIES]).
     *
     * ## Failure semantics (fail closed)
     * - Global cloud flag off → [PrivacyGateReasonCodes.CLOUD_AI_DISABLED].
     * - Capability-specific denial → the bounded code for that input column
     *   ([PrivacyGateReasonCodes.RECEIPT_IMAGE_UPLOAD_DISABLED],
     *   [PrivacyGateReasonCodes.IMAGE_UPLOAD_REDACTION_REQUIRED],
     *   [PrivacyGateReasonCodes.BANK_STATEMENT_AI_DISABLED]).
     * - Non-cloud, unknown, or future capabilities →
     *   [PrivacyGateReasonCodes.CAPABILITY_NOT_CLOUD]. They are NOT cloud
     *   capabilities and must never pass a cloud check.
     *
     * ## Reason-code contract
     * The [SecurityException] message is a bounded controlled code from
     * [PrivacyGateReasonCodes] — never exception-derived or settings-derived text.
     *
     * @throws SecurityException with a controlled reason code when blocked.
     */
    fun requireAllowed(capability: PrivacyCapability) {
        val input = CLOUD_CAPABILITIES[capability]
            ?: throw SecurityException(PrivacyGateReasonCodes.CAPABILITY_NOT_CLOUD)
        if (!cloudAllowed) {
            throw SecurityException(PrivacyGateReasonCodes.CLOUD_AI_DISABLED)
        }
        val allowed = when (input) {
            CloudCapabilityInput.GLOBAL_ONLY -> true
            CloudCapabilityInput.IMAGE -> receiptImageUploadAllowed
            CloudCapabilityInput.IMAGE_WITH_REDACTION -> receiptImageUploadAllowed && !redactBeforeCloud
            CloudCapabilityInput.BANK -> bankStatementCloudAllowed
        }
        if (!allowed) {
            throw SecurityException(
                when (input) {
                    CloudCapabilityInput.IMAGE -> PrivacyGateReasonCodes.RECEIPT_IMAGE_UPLOAD_DISABLED
                    CloudCapabilityInput.IMAGE_WITH_REDACTION ->
                        if (receiptImageUploadAllowed) PrivacyGateReasonCodes.IMAGE_UPLOAD_REDACTION_REQUIRED
                        else PrivacyGateReasonCodes.RECEIPT_IMAGE_UPLOAD_DISABLED
                    else -> PrivacyGateReasonCodes.BANK_STATEMENT_AI_DISABLED
                }
            )
        }
    }
}

@Singleton
class EffectiveCloudAiPolicyResolver @Inject constructor(
    private val privacySettingsRepository: PrivacySettingsRepository,
    private val aiSettingsRepository: AiSettingsRepository
) {
    suspend fun resolve(): EffectiveCloudAiPolicy {
        val privacy = privacySettingsRepository.getSettings()
        val ai = aiSettingsRepository.settings().first()

        val cloudAllowed: Boolean
        val reason: String?

        when {
            !privacy.cloudAiEnabled -> {
                cloudAllowed = false
                reason = "Privacy settings: cloud AI disabled"
            }
            !ai.allowCloudAi -> {
                cloudAllowed = false
                reason = "AI settings: cloud AI disabled"
            }
            else -> {
                cloudAllowed = true
                reason = null
            }
        }

        val redactBeforeCloud = privacy.redactBeforeCloud || ai.redactBeforeCloud

        val receiptImageUploadAllowed = cloudAllowed &&
            privacy.receiptImageCloudEnabled &&
            ai.receiptImageCloudEnabled

        val bankStatementCloudAllowed = cloudAllowed &&
            privacy.bankStatementAiEnabled

        return EffectiveCloudAiPolicy(
            cloudAllowed = cloudAllowed,
            reason = reason,
            redactBeforeCloud = redactBeforeCloud,
            receiptImageUploadAllowed = receiptImageUploadAllowed,
            bankStatementCloudAllowed = bankStatementCloudAllowed
        )
    }

    companion object {
        /**
         * Returns a fail-closed resolver for use in secondary test constructors.
         * Always redacts, never allows image upload.
         */
        fun failClosedForTest(aiSettingsRepository: AiSettingsRepository): EffectiveCloudAiPolicyResolver {
            val failClosedRepo = object : PrivacySettingsRepository {
                private val settings = PrivacySettings.FAIL_CLOSED_DEFAULTS
                override fun observeSettings() = flowOf(settings)
                override fun observeLoadState() = flowOf(PrivacySettingsLoadState.FirstRunDefault(settings))
                override suspend fun getSettings() = settings
                override suspend fun getLoadState() = PrivacySettingsLoadState.FirstRunDefault(settings)
                override suspend fun updateSettings(transform: (PrivacySettings) -> PrivacySettings) {}
            }
            return EffectiveCloudAiPolicyResolver(failClosedRepo, aiSettingsRepository)
        }

        /**
         * Fail-closed resolver when no [AiSettingsRepository] is available (test constructors).
         * Returns policy with cloudAllowed=false and redactBeforeCloud=true.
         */
        fun failClosedNoAi(): EffectiveCloudAiPolicyResolver {
            val noOpAiRepo = object : AiSettingsRepository {
                override fun settings() = flowOf(AiSettings())
                override suspend fun update(transform: (AiSettings) -> AiSettings) {}
            }
            return failClosedForTest(noOpAiRepo)
        }
    }
}