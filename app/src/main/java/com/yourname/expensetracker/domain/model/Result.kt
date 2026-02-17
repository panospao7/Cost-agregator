package com.yourname.expensetracker.domain.model

/**
 * A generic class that holds a value with its loading status.
 * @param <T>
 */
sealed class Result<out T> {
    data class Success<out T>(val data: T) : Result<T>()
    data class Error(val exception: Throwable? = null, val message: String? = null) : Result<Nothing>()
    data object Duplicate : Result<Nothing>()
    object Loading : Result<Nothing>()
    
    override fun toString(): String {
        return when (this) {
            is Success<*> -> "Success[data=$data]"
            is Error -> "Error[exception=$exception, message=$message]"
            Duplicate -> "Duplicate"
            Loading -> "Loading"
        }
    }
}
