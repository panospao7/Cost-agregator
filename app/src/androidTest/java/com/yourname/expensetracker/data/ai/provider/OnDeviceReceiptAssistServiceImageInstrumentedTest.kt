package com.yourname.expensetracker.data.ai.provider

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistInput
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Builds the real SDK request without downloading or invoking an AI model. */
@RunWith(AndroidJUnit4::class)
class OnDeviceReceiptAssistServiceImageInstrumentedTest {
    @Test
    fun validPngIsAttachedOnlyInImageAnalysisMode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val imageFile = File.createTempFile("receipt-assist-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            imageFile.outputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            val input = ReceiptAssistInput(
                receiptId = 7L,
                rawOcrText = "TEST RECEIPT\nTOTAL 12.34",
                imagePath = imageFile.absolutePath,
                imageMimeType = "image/png",
                parsedMerchant = null,
                parsedTotal = null,
                parsedDate = null,
                parsedTaxAmount = null,
                currency = "EUR",
                lineItemsJson = null,
                currentTimeMs = 1_000L,
                isImageAnalysisMode = true
            )
            val service = OnDeviceReceiptAssistService()

            assertNotNull(
                "Image analysis must attach an SDK image to the generated request",
                service.buildRequestForTest(input).image
            )
            assertNull(
                "An existing image must not be attached in text-only mode",
                service.buildRequestForTest(input.copy(isImageAnalysisMode = false)).image
            )
        } finally {
            bitmap.recycle()
            imageFile.delete()
        }
    }
}
