package com.yourname.expensetracker.domain.privacy

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkPrivacyGateTest {

    private fun gateWith(state: PrivacySettingsLoadState): Pair<NetworkPrivacyGate, PrivacySettingsRepository> {
        val repository = mockk<PrivacySettingsRepository>()
        coEvery { repository.getLoadState() } returns state
        coEvery { repository.getSettings() } returns when (state) {
            is PrivacySettingsLoadState.Loaded -> state.settings
            is PrivacySettingsLoadState.FirstRunDefault -> state.settings
            is PrivacySettingsLoadState.CorruptedFailClosed -> state.settings
        }
        return NetworkPrivacyGate(repository) to repository
    }

    @Test
    fun `enabled setting allows exchange rate download`() = runTest {
        val (gate, _) = gateWith(
            PrivacySettingsLoadState.Loaded(PrivacySettings(exchangeRateDownloadEnabled = true))
        )

        assertEquals(PrivacyDecision.Allowed, gate.check(PrivacyCapability.EXCHANGE_RATE_DOWNLOAD))
    }

    @Test
    fun `disabled setting denies with the bounded reason code`() = runTest {
        val (gate, _) = gateWith(
            PrivacySettingsLoadState.Loaded(PrivacySettings(exchangeRateDownloadEnabled = false))
        )

        assertEquals(
            PrivacyDecision.Denied(PrivacyGateReasonCodes.EXCHANGE_RATE_DOWNLOAD_DISABLED),
            gate.check(PrivacyCapability.EXCHANGE_RATE_DOWNLOAD)
        )
    }

    @Test
    fun `default settings on first run deny exchange rate download`() = runTest {
        val (gate, _) = gateWith(PrivacySettingsLoadState.FirstRunDefault(PrivacySettings()))

        assertEquals(
            PrivacyDecision.Denied(PrivacyGateReasonCodes.EXCHANGE_RATE_DOWNLOAD_DISABLED),
            gate.check(PrivacyCapability.EXCHANGE_RATE_DOWNLOAD)
        )
    }

    @Test
    fun `corrupted load state fails closed even if the settings claim enabled`() = runTest {
        val (gate, _) = gateWith(
            PrivacySettingsLoadState.CorruptedFailClosed(
                settings = PrivacySettings(exchangeRateDownloadEnabled = true),
                reason = "DataStore corruption detected"
            )
        )

        assertEquals(
            PrivacyDecision.FailClosed(PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE),
            gate.check(PrivacyCapability.EXCHANGE_RATE_DOWNLOAD)
        )
    }

    @Test
    fun `every other capability is not applicable and settings are not read`() = runTest {
        val (gate, repository) = gateWith(
            PrivacySettingsLoadState.Loaded(PrivacySettings(exchangeRateDownloadEnabled = true))
        )

        for (capability in PrivacyCapability.entries) {
            if (capability == PrivacyCapability.EXCHANGE_RATE_DOWNLOAD) continue
            assertEquals(
                "capability=$capability",
                PrivacyDecision.NotApplicable,
                gate.check(capability)
            )
        }
        coVerify(exactly = 0) { repository.getLoadState() }
    }
}
