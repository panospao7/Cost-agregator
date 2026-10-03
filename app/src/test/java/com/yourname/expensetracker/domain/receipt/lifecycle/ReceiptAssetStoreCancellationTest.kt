package com.yourname.expensetracker.domain.receipt.lifecycle

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.InputStream

class ReceiptAssetStoreCancellationTest {

    private class IdentityCancellation(message: String) :
        CancellationException(message), CopyableThrowable<IdentityCancellation> {
        override fun createCopy(): IdentityCancellation? = null
    }

    @Test
    fun `computeUriHash propagates cancellation from the content stream`() = runTest {
        val context = mockk<Context>()
        val resolver = mockk<ContentResolver>()
        val uri = mockk<Uri>(relaxed = true)
        val cancellation = IdentityCancellation("cancelled")
        val stream = object : InputStream() {
            override fun read(): Int = throw cancellation
        }

        every { context.contentResolver } returns resolver
        every { resolver.openInputStream(uri) } returns stream

        val store = ReceiptAssetStore(
            context = context,
            timeProvider = object : TimeProvider {
                override fun now(): Long = 1L
            }
        )

        val thrown = runCatching { store.computeUriHash(uri) }.exceptionOrNull()

        assertSame(cancellation, thrown)
    }
}
