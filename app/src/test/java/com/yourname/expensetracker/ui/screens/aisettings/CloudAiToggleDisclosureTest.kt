package com.yourname.expensetracker.ui.screens.aisettings

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * ai-fixes INV-25 (Z2): the background cloud briefing disclosure is shown directly under the
 * "Allow cloud AI" toggle, for both toggle states.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CloudAiToggleDisclosureTest {

    private val composeRule = createComposeRule()

    // testReleaseUnitTest does not merge ui-test-manifest (debugImplementation only), so
    // ComponentActivity must be registered with Robolectric before the compose rule starts.
    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(
            object : ExternalResource() {
                override fun before() {
                    val context = ApplicationProvider.getApplicationContext<Application>()
                    shadowOf(context.packageManager).addActivityIfNotPresent(
                        ComponentName(context, ComponentActivity::class.java)
                    )
                }
            }
        )
        .around(composeRule)

    private val disclosure =
        "While Enable AI, Dashboard briefing and Allow cloud AI are all on, " +
            "the daily briefing is generated in the background using cloud AI."

    @Test
    fun `disclosure line is shown under the toggle when cloud AI is off`() {
        composeRule.setContent { Column { CloudAiToggleWithDisclosure(allowCloudAi = false, onCheckedChange = {}) } }

        composeRule.onNodeWithTag(CLOUD_AI_DISCLOSURE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(disclosure).assertIsDisplayed()
        assertDisclosureBelowToggle()
    }

    @Test
    fun `disclosure line is shown under the toggle when cloud AI is on`() {
        composeRule.setContent { Column { CloudAiToggleWithDisclosure(allowCloudAi = true, onCheckedChange = {}) } }

        composeRule.onNodeWithTag(CLOUD_AI_DISCLOSURE_TAG).assertIsDisplayed()
        assertDisclosureBelowToggle()
    }

    private fun assertDisclosureBelowToggle() {
        val toggleBounds = composeRule.onNodeWithText("Allow cloud AI").getUnclippedBoundsInRoot()
        val disclosureBounds = composeRule.onNodeWithTag(CLOUD_AI_DISCLOSURE_TAG).getUnclippedBoundsInRoot()
        assertTrue(
            "disclosure must be laid out below the toggle label",
            disclosureBounds.top >= toggleBounds.bottom
        )
    }
}
