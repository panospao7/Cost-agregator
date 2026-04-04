package com.yourname.expensetracker.domain.naturallanguage

interface SpeechInputGateway {
    fun isAvailable(): Boolean
    fun startListening(onResult: (String) -> Unit)
    fun stopListening()
}
