package com.yourname.expensetracker.domain.naturallanguage

interface SpeechInputGateway {
    fun isAvailable(): Boolean
    fun startListening(
        onResult: (String) -> Unit,
        onError: (SpeechInputError) -> Unit = {}
    )
    fun stopListening()
}

sealed class SpeechInputError {
    data object PermissionDenied : SpeechInputError()
    data object RecognizerUnavailable : SpeechInputError()
    data class RecognizerError(val code: Int) : SpeechInputError()
    data class StartupFailure(val throwable: Throwable) : SpeechInputError()
}
