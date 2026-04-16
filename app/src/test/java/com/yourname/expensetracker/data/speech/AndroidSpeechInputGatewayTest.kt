package com.yourname.expensetracker.data.speech

import android.content.Context
import android.content.pm.PackageManager
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.domain.naturallanguage.SpeechInputError
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidSpeechInputGatewayTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `denied permission surfaces error without starting recognizer`() {
        var receivedError: SpeechInputError? = null

        val gateway = AndroidSpeechInputGateway(
            context = context,
            permissionChecker = { _, _ -> PackageManager.PERMISSION_DENIED },
            recognitionAvailabilityChecker = { true },
            recognizerFactory = { throw AssertionError("Recognizer should not be created") }
        )

        gateway.startListening(onResult = {}, onError = { receivedError = it })

        assertEquals(SpeechInputError.PermissionDenied, receivedError)
    }

    @Test
    fun `startup failure surfaces error without crashing`() {
        val recognizer = mockk<SpeechRecognizer>()
        every { recognizer.setRecognitionListener(any()) } just runs
        every { recognizer.startListening(any()) } throws IllegalStateException("startup failed")

        var receivedError: SpeechInputError? = null
        val gateway = AndroidSpeechInputGateway(
            context = context,
            permissionChecker = { _, _ -> PackageManager.PERMISSION_GRANTED },
            recognitionAvailabilityChecker = { true },
            recognizerFactory = { recognizer }
        )

        gateway.startListening(onResult = {}, onError = { receivedError = it })

        assertTrue(receivedError is SpeechInputError.StartupFailure)
        assertEquals("startup failed", (receivedError as SpeechInputError.StartupFailure).throwable.message)
    }

    @Test
    fun `recognizer listener error is forwarded`() {
        val recognizer = mockk<SpeechRecognizer>()
        val listenerSlot = slot<RecognitionListener>()
        every { recognizer.setRecognitionListener(capture(listenerSlot)) } just runs
        every { recognizer.startListening(any()) } just runs

        var receivedError: SpeechInputError? = null
        val gateway = AndroidSpeechInputGateway(
            context = context,
            permissionChecker = { _, _ -> PackageManager.PERMISSION_GRANTED },
            recognitionAvailabilityChecker = { true },
            recognizerFactory = { recognizer }
        )

        gateway.startListening(onResult = {}, onError = { receivedError = it })
        listenerSlot.captured.onError(7)

        assertEquals(SpeechInputError.RecognizerError(7), receivedError)
        verify(exactly = 1) { recognizer.startListening(any()) }
    }
}
