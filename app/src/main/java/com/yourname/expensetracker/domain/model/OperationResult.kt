package com.yourname.expensetracker.domain.model

sealed class OperationResult<out T> {
    data class Success<out T>(val data: T) : OperationResult<T>()
    data class Error(val message: String, val throwable: Throwable? = null) : OperationResult<Nothing>()
    data object Duplicate : OperationResult<Nothing>()
}
