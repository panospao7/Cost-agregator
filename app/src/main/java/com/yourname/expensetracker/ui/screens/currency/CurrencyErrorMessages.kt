package com.yourname.expensetracker.ui.screens.currency

import androidx.annotation.StringRes
import com.yourname.expensetracker.R

/**
 * Maps the bounded error codes emitted by [CurrencyManagementViewModel] to user-facing string
 * resources so raw codes are never displayed. Unknown codes fall back to the generic
 * "currency data unavailable" message.
 */
@StringRes
internal fun currencyErrorMessageRes(errorCode: String): Int = when (errorCode) {
    "CURRENCY_RATE_DOWNLOAD_DISABLED" -> R.string.currency_error_download_disabled
    "CURRENCY_RATE_REFRESH_UNAVAILABLE" -> R.string.currency_error_refresh_unavailable
    "CURRENCY_CONVERSION_UNAVAILABLE" -> R.string.currency_error_conversion_unavailable
    "CURRENCY_DATA_UNAVAILABLE" -> R.string.currency_error_data_unavailable
    "UNSUPPORTED_HOME_CURRENCY" -> R.string.currency_error_unsupported_home
    "HOME_SWITCH_WRITE_FAILED" -> R.string.currency_home_switch_error_write_failed
    else -> R.string.currency_error_data_unavailable
}
