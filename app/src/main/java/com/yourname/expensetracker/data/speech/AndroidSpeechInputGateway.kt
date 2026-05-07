package com.yourname.expensetracker.data.speech

import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.yourname.expensetracker.domain.naturallanguage.SpeechInputError
import com.yourname.expensetracker.domain.naturallanguage.SpeechInputGateway
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

// TODO (W35): Add destroy() called from ViewModel onCleared.
// Handle already-listening, partial results, timeout, permission revoked.
@Singleton
class AndroidSpeechInputGateway @Inject constructor(
    @ApplicationContext private val context: Context
) : SpeechInputGateway {

    internal constructor(
        context: Context,
        permissionChecker: (Context, String) -> Int,
        recognitionAvailabilityChecker: (Context) -> Boolean,
        recognizerFactory: (Context) -> SpeechRecognizer
    ) : this(context) {
        this.permissionChecker = permissionChecker
        this.recognitionAvailabilityChecker = recognitionAvailabilityChecker
        this.recognizerFactory = recognizerFactory
    }

    private var recognizer: SpeechRecognizer? = null
    private var permissionChecker: (Context, String) -> Int = ContextCompat::checkSelfPermission
    private var recognitionAvailabilityChecker: (Context) -> Boolean = SpeechRecognizer::isRecognitionAvailable
    private var recognizerFactory: (Context) -> SpeechRecognizer = SpeechRecognizer::createSpeechRecognizer

    override fun isAvailable(): Boolean = recognitionAvailabilityChecker(context)

    override fun startListening(
        onResult: (String) -> Unit,
        onError: (SpeechInputError) -> Unit
    ) {
        if (!hasRecordAudioPermission()) {
            onError(SpeechInputError.PermissionDenied)
            return
        }

        if (!isAvailable()) {
            onError(SpeechInputError.RecognizerUnavailable)
            return
        }

        try {
            val activeRecognizer = recognizer ?: recognizerFactory(context).also {
                recognizer = it
            }

            activeRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onError(error: Int) {
                    onError(SpeechInputError.RecognizerError(error))
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.firstOrNull()?.let(onResult)
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            activeRecognizer.startListening(intent)
        } catch (exception: SecurityException) {
            onError(SpeechInputError.PermissionDenied)
        } catch (throwable: RuntimeException) {
            onError(SpeechInputError.StartupFailure(throwable))
        }
    }

    override fun stopListening() {
        recognizer?.stopListening()
    }

    private fun hasRecordAudioPermission(): Boolean {
        return permissionChecker(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
}
