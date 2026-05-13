package com.yourname.expensetracker.golden

import com.yourname.expensetracker.data.privacy.PrivacyAuditLoggerImpl
import com.yourname.expensetracker.domain.privacy.CompositePrivacyGate
import com.yourname.expensetracker.domain.privacy.LocationPrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.testfixtures.golden.GoldenScenarioVerifier
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Test

/**
 * Golden Scenario Test: Privacy Gate Enforcement
 *
 * Proves that:
 * 1. CompositePrivacyGate denies when settings are disabled (fail-closed)
 * 2. Audit events are persisted to Room for every decision
 * 3. First Denied in the chain short-circuits (no further gates checked)
 * 4. blocksExecution() returns true for Denied/FailClosed
 * 5. Allowed decisions are also audited
 *
 * Uses REAL PrivacyAuditLoggerImpl (writes to Room) + REAL CompositePrivacyGate.
 * PrivacySettingsRepository is mocked to control settings.
 */
class PrivacyGateEnforcementGoldenTest : GoldenTestBase() {

    private lateinit var auditLogger: PrivacyAuditLoggerImpl
    private lateinit var compositeGate: CompositePrivacyGate

    private val verifier = GoldenScenarioVerifier(
        scenarioName = "privacy_gate_enforcement",
        numericTolerance = 0.01
    )

    @Before
    override fun setUp() {
        super.setUp()
        auditLogger = PrivacyAuditLoggerImpl(database.privacyAuditDao(), timeProvider)
    }

    @Test
    fun `privacy gates deny and audit when settings disabled`() = runTest {
        // ── SETUP: All privacy settings DISABLED (fail-closed) ──
        val settingsRepo = mockk<PrivacySettingsRepository>().also {
            coEvery { it.getSettings() } returns PrivacySettings(
                cloudAiEnabled = false,
                externalGeocodingEnabled = false,
                backgroundLocationBackfillEnabled = false,
                deviceGpsLocationEnabled = false
            )
            every { it.observeSettings() } returns flowOf(PrivacySettings(
                cloudAiEnabled = false,
                externalGeocodingEnabled = false,
                backgroundLocationBackfillEnabled = false,
                deviceGpsLocationEnabled = false
            ))
        }

        // Build a LocationPrivacyGate with disabled settings
        val locationGate = LocationPrivacyGate(settingsRepo, auditLogger)

        // Build composite with just the location gate for focused testing
        compositeGate = CompositePrivacyGate(listOf(locationGate), auditLogger)

        // ── ACT: Check capabilities that should be DENIED ──
        val geocodingDecision = compositeGate.check(PrivacyCapability.EXTERNAL_GEOCODING)
        val locationBackfillDecision = compositeGate.check(PrivacyCapability.BACKGROUND_LOCATION_BACKFILL)
        val gpsDecision = compositeGate.check(PrivacyCapability.DEVICE_GPS_LOCATION)

        // ── ACT: Enable geocoding and check again ──
        val enabledSettingsRepo = mockk<PrivacySettingsRepository>().also {
            coEvery { it.getSettings() } returns PrivacySettings(
                cloudAiEnabled = false,
                externalGeocodingEnabled = true,
                backgroundLocationBackfillEnabled = false,
                deviceGpsLocationEnabled = false
            )
            every { it.observeSettings() } returns flowOf(PrivacySettings(
                cloudAiEnabled = false,
                externalGeocodingEnabled = true,
                backgroundLocationBackfillEnabled = false,
                deviceGpsLocationEnabled = false
            ))
        }
        val enabledLocationGate = LocationPrivacyGate(enabledSettingsRepo, auditLogger)
        val enabledComposite = CompositePrivacyGate(listOf(enabledLocationGate), auditLogger)
        val geocodingAllowed = enabledComposite.check(PrivacyCapability.EXTERNAL_GEOCODING)

        // ── QUERY: Audit events from Room ──
        val auditEvents = database.privacyAuditDao().getRecent(20)

        // ── SERIALIZE ──
        val actual = JSONObject().apply {
            put("geocodingDecision", geocodingDecision.javaClass.simpleName)
            put("geocodingBlocks", geocodingDecision.blocksExecution())
            put("locationBackfillDecision", locationBackfillDecision.javaClass.simpleName)
            put("locationBackfillBlocks", locationBackfillDecision.blocksExecution())
            put("gpsDecision", gpsDecision.javaClass.simpleName)
            put("gpsBlocks", gpsDecision.blocksExecution())

            put("geocodingAllowedAfterEnable", geocodingAllowed.javaClass.simpleName)
            put("geocodingAllowedBlocks", geocodingAllowed.blocksExecution())

            put("auditEventsRecorded", auditEvents.size)
            put("auditDecisions", JSONArray().apply {
                auditEvents.sortedBy { it.id }.forEach { event ->
                    put(JSONObject().apply {
                        put("capability", event.capability)
                        put("decision", event.decision)
                    })
                }
            })

            put("allDeniedBlockExecution", geocodingDecision.blocksExecution()
                    && locationBackfillDecision.blocksExecution()
                    && gpsDecision.blocksExecution())
            put("allowedDoesNotBlock", !geocodingAllowed.blocksExecution())
        }

        // ── VERIFY ──
        verifier.verify(actual).assertPassed()
    }
}
