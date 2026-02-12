package com.yourname.expensetracker.domain.intelligence

import kotlinx.coroutines.flow.StateFlow

interface ITransactionClassifier {
    suspend fun initialize()
    suspend fun predict(text: String): Float
    suspend fun train(text: String, isTransaction: Boolean)
    fun retrainFromCorrections()
    fun getStats(): ClassifierStats
    val stats: StateFlow<ClassifierStats>
}
