package com.yourname.expensetracker.ui.applock

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.robolectric.Shadows.shadowOf

/**
 * Shared rule setup for the S19 Compose tests. `createComposeRule()` launches `androidx.activity.ComponentActivity`,
 * which only ui-test-manifest (debugImplementation) declares, so the activity is registered with Robolectric's
 * package manager first (same pattern as `AppLockPrivacySectionTest`). No new test dependency.
 */
abstract class AppLockComposeTestBase {

    private val registerActivityRule = object : ExternalResource() {
        override fun before() {
            val appContext = ApplicationProvider.getApplicationContext<Context>()
            shadowOf(appContext.packageManager)
                .addActivityIfNotPresent(ComponentName(appContext, ComponentActivity::class.java))
        }
    }

    protected val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    protected fun text(@StringRes id: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(id, *args)
}

/**
 * Records what happens to the protected-content slot: how often it ENTERED composition, how often it was disposed,
 * how often a `LaunchedEffect(Unit)` ran, and which `remember` instances it saw. Used for AE-38 and AE-113..115.
 */
class ContentSlotProbe {
    var entered = 0
    var disposed = 0
    var launched = 0
    val rememberedTokens: MutableList<Any> = mutableListOf()

    val distinctTokens: Int get() = rememberedTokens.distinct().size

    companion object {
        const val TAG = "probe_protected_content"
        const val PROBE_TEXT = "PROBE-AMOUNT-1234"
    }
}

/** The stand-in for `MainScreen`: it reports composition, disposal, effect runs and `remember` identity. */
@Composable
fun ProbeContent(probe: ContentSlotProbe) {
    DisposableEffect(Unit) {
        probe.entered++
        onDispose { probe.disposed++ }
    }
    LaunchedEffect(Unit) { probe.launched++ }
    val token = remember { Any() }
    SideEffect { probe.rememberedTokens += token }
    Text(ContentSlotProbe.PROBE_TEXT, modifier = Modifier.testTag(ContentSlotProbe.TAG))
}
