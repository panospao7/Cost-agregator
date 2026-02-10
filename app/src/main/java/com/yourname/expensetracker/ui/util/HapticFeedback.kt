package com.yourname.expensetracker.ui.util

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView

object AppHaptics {
    fun performStandard(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun performSuccess(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    fun performError(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
    }

    fun performHeavy(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}

@Composable
fun rememberHapticFeedback(): (HapticType) -> Unit {
    val view = LocalView.current
    return { type ->
        when (type) {
            HapticType.Standard -> AppHaptics.performStandard(view)
            HapticType.Success -> AppHaptics.performSuccess(view)
            HapticType.Error -> AppHaptics.performError(view)
            HapticType.Heavy -> AppHaptics.performHeavy(view)
        }
    }
}

enum class HapticType {
    Standard, Success, Error, Heavy
}
