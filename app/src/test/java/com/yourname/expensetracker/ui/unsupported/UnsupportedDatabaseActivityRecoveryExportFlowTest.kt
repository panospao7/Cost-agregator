package com.yourname.expensetracker.ui.unsupported

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.APP_DATABASE_SCHEMA_VERSION
import com.yourname.expensetracker.data.database.DatabaseOpenGate
import com.yourname.expensetracker.domain.privacy.applock.AuthLaunch
import com.yourname.expensetracker.domain.privacy.applock.AuthPromptCopy
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCallback
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthOutcome
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthenticator
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * J-20-1: the export flow in recovery mode (gate not blocking, persisted CRITICAL_RECOVERY_REQUIRED, non-empty main
 * database file) is the same auth-gated flow as the open-gate flow: the picker opens only from the export gate, and
 * only after the auth decision allows it. Driven through the existing test seams (`exportAuthRequiredForTest`,
 * `authenticatorFactoryForTest`), reset in [tearDown]. Real sentinel file and a real gate-Ok SQLite file are used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UnsupportedDatabaseActivityRecoveryExportFlowTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var context: Context
    private var scenario: ActivityScenario<UnsupportedDatabaseActivity>? = null

    private class FakeAuthenticator : DeviceAuthenticator {
        val authenticateIds = mutableListOf<Long>()
        private var callback: DeviceAuthCallback? = null

        override fun authenticate(attemptId: Long, copy: AuthPromptCopy, callback: DeviceAuthCallback): AuthLaunch {
            authenticateIds += attemptId
            this.callback = callback
            return AuthLaunch.LAUNCHED
        }

        override fun cancel(attemptId: Long) = Unit

        fun deliverToLatest(outcome: DeviceAuthOutcome) {
            checkNotNull(callback).onOutcome(authenticateIds.last(), outcome)
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseOpenGate.resetForTests()
        clearState()
        resetSeams()
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
        resetSeams()
        clearState()
        DatabaseOpenGate.resetForTests()
    }

    private fun resetSeams() {
        UnsupportedDatabaseActivity.exportAuthRequiredForTest = null
        UnsupportedDatabaseActivity.authenticatorFactoryForTest = null
        UnsupportedDatabaseActivity.exportAuthPromptTimeoutMillisForTest = null
    }

    private fun clearState() {
        DatabaseOpenGate.liveDatabaseFiles(context).forEach { it.delete() }
        File(context.noBackupFilesDir, RestoreMaintenanceMode.CRITICAL_SENTINEL_FILE).delete()
        context.getSharedPreferences(RestoreMaintenanceMode.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun createGateOkLiveDatabase() {
        val db = DatabaseOpenGate.liveDatabaseFiles(context).first()
        db.parentFile?.mkdirs()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(db, null).use {
            it.execSQL("CREATE TABLE synthetic (x INTEGER)")
            it.version = APP_DATABASE_SCHEMA_VERSION
        }
    }

    private fun persistCritical() {
        val sentinel = File(context.noBackupFilesDir, RestoreMaintenanceMode.CRITICAL_SENTINEL_FILE)
        sentinel.parentFile?.mkdirs()
        sentinel.writeBytes(byteArrayOf(1))
    }

    private fun recoveryModeWithDatabase() {
        createGateOkLiveDatabase()
        persistCritical()
        DatabaseOpenGate.resetForTests()
        assertNull("recovery mode runs with the open gate NOT blocking", DatabaseOpenGate.blockedReasonOrNull(context))
    }

    private fun launch(): UnsupportedDatabaseActivity {
        val intent = Intent(context, UnsupportedDatabaseActivity::class.java)
            .putExtra(UnsupportedDatabaseActivity.EXTRA_RECOVERY_EXPORT, true)
        val launched = ActivityScenario.launch<UnsupportedDatabaseActivity>(intent)
        scenario = launched
        var activity: UnsupportedDatabaseActivity? = null
        launched.onActivity { activity = it }
        assertNotNull(activity)
        composeRule.waitForIdle()
        return activity!!
    }

    private fun tap() {
        composeRule.onNodeWithText(context.getString(R.string.unsupported_db_export_button)).performClick()
        composeRule.waitForIdle()
    }

    private fun assertPickerOpenedExactlyOnce(activity: UnsupportedDatabaseActivity) {
        val first = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("the document picker was not opened", first)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, first!!.intent.action)
        assertNull("the picker opened more than once", shadowOf(activity).nextStartedActivityForResult)
    }

    @Test
    fun `j20_1 recovery mode shows the recovery copy and the export button`() {
        recoveryModeWithDatabase()
        UnsupportedDatabaseActivity.exportAuthRequiredForTest = { _ -> ExportAuthDecision.NotRequired }
        UnsupportedDatabaseActivity.authenticatorFactoryForTest = { _ -> FakeAuthenticator() }

        launch()

        composeRule.onNodeWithText(context.getString(R.string.unsupported_db_reason_recovery)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.unsupported_db_export_button)).assertExists()
    }

    @Test
    fun `j20_1 recovery mode with auth not required opens the picker once on tap`() {
        recoveryModeWithDatabase()
        val auth = FakeAuthenticator()
        UnsupportedDatabaseActivity.exportAuthRequiredForTest = { _ -> ExportAuthDecision.NotRequired }
        UnsupportedDatabaseActivity.authenticatorFactoryForTest = { _ -> auth }
        val activity = launch()

        tap()

        assertTrue(auth.authenticateIds.isEmpty())
        assertPickerOpenedExactlyOnce(activity)
    }

    @Test
    fun `j20_1 recovery mode with auth required opens the picker only after Succeeded`() {
        recoveryModeWithDatabase()
        val auth = FakeAuthenticator()
        UnsupportedDatabaseActivity.exportAuthRequiredForTest = { _ -> ExportAuthDecision.Required }
        UnsupportedDatabaseActivity.authenticatorFactoryForTest = { _ -> auth }
        val activity = launch()

        tap()

        assertEquals(1, auth.authenticateIds.size)
        assertNull("no picker before the prompt succeeds", shadowOf(activity).peekNextStartedActivityForResult())

        composeRule.runOnUiThread { auth.deliverToLatest(DeviceAuthOutcome.Succeeded) }
        composeRule.waitForIdle()

        assertPickerOpenedExactlyOnce(activity)
    }

    @Test
    fun `j20_1 recovery mode with a cancelled prompt shows the denied message and opens no picker`() {
        recoveryModeWithDatabase()
        val auth = FakeAuthenticator()
        UnsupportedDatabaseActivity.exportAuthRequiredForTest = { _ -> ExportAuthDecision.Required }
        UnsupportedDatabaseActivity.authenticatorFactoryForTest = { _ -> auth }
        val activity = launch()
        tap()

        composeRule.runOnUiThread { auth.deliverToLatest(DeviceAuthOutcome.Cancelled) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.unsupported_db_export_auth_denied)).assertExists()
        assertNull(shadowOf(activity).peekNextStartedActivityForResult())
    }

    @Test
    fun `j20_1 without a persisted critical mode no authenticator is built even with the recovery extra`() {
        createGateOkLiveDatabase()
        DatabaseOpenGate.resetForTests()
        var factoryCalls = 0
        UnsupportedDatabaseActivity.authenticatorFactoryForTest = { _ -> factoryCalls++; FakeAuthenticator() }
        val intent = Intent(context, UnsupportedDatabaseActivity::class.java)
            .putExtra(UnsupportedDatabaseActivity.EXTRA_RECOVERY_EXPORT, true)

        val activity = Robolectric.buildActivity(UnsupportedDatabaseActivity::class.java, intent).create().get()

        assertTrue(activity.isFinishing)
        assertFalse("the finish path must not build an authenticator", factoryCalls > 0)
    }
}
