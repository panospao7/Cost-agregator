package com.yourname.expensetracker.ui.util

import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseAccessType
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.domain.model.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Shared test factory for the exception thrown by the maintenance write barrier. */
fun blockedWriteException(operation: String = "UiWriteGuardTest.write"): DatabaseAccessBlockedException =
    DatabaseAccessBlockedException(
        accessType = DatabaseAccessType.WRITE,
        operation = DatabaseAccessOperation(operation),
        mode = RestoreMaintenanceMode.Mode.RESTORE_STAGING
    )

/** R18b: [guardedWrite] handles ONLY the write-barrier exception. */
class UiWriteGuardTest {

    @Test
    fun `success returns the block value and never reports blocked`() = runTest {
        var reported: UiText? = null

        val result = guardedWrite(onBlocked = { reported = it }) { 42L }

        assertEquals(42L, result)
        assertNull(reported)
    }

    @Test
    fun `blocked write reports the bounded maintenance message and returns null`() = runTest {
        val reported = mutableListOf<UiText>()

        val result = guardedWrite(onBlocked = { reported += it }) {
            throw blockedWriteException()
        }

        assertNull(result)
        assertEquals(
            listOf<UiText>(UiText.StringResource(R.string.ui_write_blocked_maintenance)),
            reported
        )
    }

    @Test
    fun `blocked message carries no raw exception text`() = runTest {
        var reported: UiText? = null

        guardedWrite(onBlocked = { reported = it }) {
            throw blockedWriteException("SecretRepository.secretOperation")
        }

        assertTrue(reported is UiText.StringResource)
        assertEquals(emptyList<Any>(), (reported as UiText.StringResource).args)
    }

    @Test
    fun `cancellation propagates and is not reported`() = runTest {
        var reported = false
        val cancellation = CancellationException("cancelled")

        try {
            guardedWrite(onBlocked = { reported = true }) { throw cancellation }
            fail("CancellationException must propagate")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }
        assertTrue(!reported)
    }

    @Test
    fun `other exceptions propagate and are not reported`() = runTest {
        var reported = false
        val failure = IllegalStateException("not a barrier failure")

        try {
            guardedWrite(onBlocked = { reported = true }) { throw failure }
            fail("unrelated exceptions must propagate")
        } catch (e: IllegalStateException) {
            assertSame(failure, e)
        }
        assertTrue(!reported)
    }
}
