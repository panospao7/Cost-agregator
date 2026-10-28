package com.yourname.expensetracker.domain.ai.policy

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiSettings
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AiPolicyTest {

    private lateinit var policy: AiPolicyImpl

    @Before
    fun setup() {
        policy = AiPolicyImpl()
    }

    private data class CloudRow(val name: String, val settings: AiSettings, val expected: Boolean)

    private data class CapabilityRow(
        val name: String,
        val settings: AiSettings,
        val capability: AiCapability,
        val expected: Boolean
    )

    private data class RedactRow(
        val name: String,
        val settings: AiSettings,
        val capabilities: List<AiCapability>,
        val expected: Boolean
    )

    // ── canUseCloud ───────────────────────────────────────────────────────────

    @Test
    fun `canUseCloud requires both aiEnabled and allowCloudAi and defaults to false`() {
        val rows = listOf(
            CloudRow("aiEnabled=false allowCloudAi=false", AiSettings(aiEnabled = false, allowCloudAi = false), false),
            CloudRow("aiEnabled=false allowCloudAi=true", AiSettings(aiEnabled = false, allowCloudAi = true), false),
            CloudRow("aiEnabled=true allowCloudAi=false", AiSettings(aiEnabled = true, allowCloudAi = false), false),
            CloudRow("aiEnabled=true allowCloudAi=true", AiSettings(aiEnabled = true, allowCloudAi = true), true),
            CloudRow("default AiSettings", AiSettings(), false)
        )
        assertEquals("canUseCloud table size guard", 5, rows.size)

        rows.forEach { row ->
            assertEquals("[${row.name}] canUseCloud", row.expected, policy.canUseCloud(row.settings))
        }
    }

    // ── canUseCloudFor ────────────────────────────────────────────────────────

    @Test
    fun `canUseCloudFor follows the per-capability toggle only when cloud is enabled`() {
        val warrantyEnabled = AiSettings(
            aiEnabled = true,
            allowCloudAi = true,
            receiptAssistEnabled = true,
            warrantyExtractionEnabled = true
        )
        val rows = listOf(
            CapabilityRow(
                "review explanation toggle off",
                AiSettings(aiEnabled = true, allowCloudAi = true, reviewExplanationEnabled = false),
                AiCapability.REVIEW_EXPLANATION,
                false
            ),
            CapabilityRow(
                "review explanation toggle on",
                AiSettings(aiEnabled = true, allowCloudAi = true, reviewExplanationEnabled = true),
                AiCapability.REVIEW_EXPLANATION,
                true
            ),
            CapabilityRow(
                "receipt extraction does not require image toggle",
                AiSettings(
                    aiEnabled = true,
                    allowCloudAi = true,
                    receiptAssistEnabled = true,
                    receiptImageCloudEnabled = false
                ),
                AiCapability.RECEIPT_EXTRACTION,
                true
            ),
            CapabilityRow(
                "warranty extraction toggle on",
                warrantyEnabled,
                AiCapability.WARRANTY_EXTRACTION,
                true
            ),
            CapabilityRow(
                "warranty extraction toggle off stays disabled even when receipt assist is enabled",
                warrantyEnabled.copy(warrantyExtractionEnabled = false),
                AiCapability.WARRANTY_EXTRACTION,
                false
            )
        )
        assertEquals("canUseCloudFor table size guard", 5, rows.size)

        rows.forEach { row ->
            assertEquals(
                "[${row.name}] canUseCloudFor(${row.capability})",
                row.expected,
                policy.canUseCloudFor(row.settings, row.capability)
            )
        }
    }

    // ── shouldAllowOnDevice ───────────────────────────────────────────────────

    @Test
    fun `shouldAllowOnDevice requires on-device toggle and capability toggle`() {
        val rows = listOf(
            CapabilityRow(
                "on-device disabled",
                AiSettings(aiEnabled = true, allowOnDeviceAi = false, receiptAssistEnabled = true),
                AiCapability.RECEIPT_EXTRACTION,
                false
            ),
            CapabilityRow(
                "on-device and capability enabled",
                AiSettings(aiEnabled = true, allowOnDeviceAi = true, receiptAssistEnabled = true),
                AiCapability.RECEIPT_EXTRACTION,
                true
            )
        )
        assertEquals("shouldAllowOnDevice table size guard", 2, rows.size)

        rows.forEach { row ->
            assertEquals(
                "[${row.name}] shouldAllowOnDevice(${row.capability})",
                row.expected,
                policy.shouldAllowOnDevice(row.settings, row.capability)
            )
        }
    }

    // ── TRANSACTION_INSIGHT (ai-fixes B2a, INV-16, AE-27) ─────────────────────

    @Test
    fun `canUseCloudFor TRANSACTION_INSIGHT is false for every combination of cloud-relevant settings`() {
        val booleans = listOf(false, true)
        var checked = 0
        for (aiEnabled in booleans) for (allowCloudAi in booleans) for (allowOnDeviceAi in booleans) {
            for (briefing in booleans) for (review in booleans) for (redact in booleans) {
                for (insight in booleans) {
                    val settings = AiSettings(
                        aiEnabled = aiEnabled,
                        allowCloudAi = allowCloudAi,
                        allowOnDeviceAi = allowOnDeviceAi,
                        dashboardBriefingEnabled = briefing,
                        reviewExplanationEnabled = review,
                        redactBeforeCloud = redact,
                        transactionInsightEnabled = insight
                    )
                    assertEquals(
                        "canUseCloudFor(TRANSACTION_INSIGHT) for $settings",
                        false,
                        policy.canUseCloudFor(settings, AiCapability.TRANSACTION_INSIGHT)
                    )
                    checked++
                }
            }
        }
        assertEquals("combination count guard", 128, checked)
    }

    @Test
    fun `shouldAllowOnDevice TRANSACTION_INSIGHT requires aiEnabled allowOnDeviceAi and the insight toggle`() {
        val rows = listOf(
            CapabilityRow(
                "ai off",
                AiSettings(aiEnabled = false, allowOnDeviceAi = true, transactionInsightEnabled = true),
                AiCapability.TRANSACTION_INSIGHT,
                false
            ),
            CapabilityRow(
                "on-device off",
                AiSettings(aiEnabled = true, allowOnDeviceAi = false, transactionInsightEnabled = true),
                AiCapability.TRANSACTION_INSIGHT,
                false
            ),
            CapabilityRow(
                "ai, on-device and insight toggle on",
                AiSettings(aiEnabled = true, allowOnDeviceAi = true, transactionInsightEnabled = true),
                AiCapability.TRANSACTION_INSIGHT,
                true
            ),
            CapabilityRow(
                "independent of dashboard briefing toggle (briefing off, insight on)",
                AiSettings(
                    aiEnabled = true,
                    allowOnDeviceAi = true,
                    dashboardBriefingEnabled = false,
                    transactionInsightEnabled = true
                ),
                AiCapability.TRANSACTION_INSIGHT,
                true
            ),
            CapabilityRow(
                "insight toggle off (default) with ai and on-device on",
                AiSettings(aiEnabled = true, allowOnDeviceAi = true),
                AiCapability.TRANSACTION_INSIGHT,
                false
            ),
            CapabilityRow(
                "insight toggle off even with briefing on",
                AiSettings(
                    aiEnabled = true,
                    allowOnDeviceAi = true,
                    dashboardBriefingEnabled = true,
                    transactionInsightEnabled = false
                ),
                AiCapability.TRANSACTION_INSIGHT,
                false
            )
        )
        assertEquals("TRANSACTION_INSIGHT shouldAllowOnDevice table size guard", 6, rows.size)

        rows.forEach { row ->
            assertEquals(
                "[${row.name}] shouldAllowOnDevice(${row.capability})",
                row.expected,
                policy.shouldAllowOnDevice(row.settings, row.capability)
            )
        }
    }

    // ── shouldRedact ──────────────────────────────────────────────────────────

    @Test
    fun `shouldRedact depends only on redactBeforeCloud and defaults to true`() {
        val rows = listOf(
            RedactRow(
                "redactBeforeCloud=true for every capability",
                AiSettings(redactBeforeCloud = true),
                AiCapability.entries.toList(),
                true
            ),
            RedactRow(
                "redactBeforeCloud=false for every capability",
                AiSettings(redactBeforeCloud = false),
                AiCapability.entries.toList(),
                false
            ),
            // redactBeforeCloud=true wins even if AI cloud is fully enabled
            RedactRow(
                "redact true with aiEnabled and allowCloudAi on",
                AiSettings(aiEnabled = true, allowCloudAi = true, redactBeforeCloud = true),
                listOf(AiCapability.REVIEW_EXPLANATION),
                true
            ),
            // redactBeforeCloud=false gives false even if AI is disabled
            RedactRow(
                "redact false with aiEnabled and allowCloudAi off",
                AiSettings(aiEnabled = false, allowCloudAi = false, redactBeforeCloud = false),
                listOf(AiCapability.REVIEW_EXPLANATION),
                false
            ),
            RedactRow(
                "default AiSettings",
                AiSettings(),
                listOf(AiCapability.REVIEW_EXPLANATION),
                true
            )
        )
        assertEquals("shouldRedact table size guard", 5, rows.size)

        rows.forEach { row ->
            row.capabilities.forEach { capability ->
                assertEquals(
                    "[${row.name}] shouldRedact for capability $capability",
                    row.expected,
                    policy.shouldRedact(row.settings, capability)
                )
            }
        }
    }
}
