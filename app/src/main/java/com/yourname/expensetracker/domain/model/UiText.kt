package com.yourname.expensetracker.domain.model

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * UiText is a sealed class that represents text that can be displayed in the UI.
 * It abstracts away whether the text is a string resource or a dynamic value,
 * allowing ViewModels to remain agnostic of the Android Context while still
 * supporting internationalization.
 *
 * This solves the architectural problem where ViewModels need to emit user-facing
 * text (like error messages) without having direct access to string resources.
 *
 * Usage in ViewModel:
 * ```kotlin
 * _uiState.value = _uiState.value.copy(
 *     error = UiText.StringResource(R.string.error_network)
 * )
 * ```
 *
 * Usage in Compose:
 * ```kotlin
 * Text(text = uiState.error.asString())
 * // or
 * Text(text = stringResource(uiState.error))
 * ```
 */
sealed class UiText {
    /**
     * Represents a string resource with optional format arguments.
     */
    data class StringResource(
        @StringRes val resId: Int,
        val args: List<Any> = emptyList()
    ) : UiText()

    /**
     * Represents a dynamic string value (not from resources).
     * Use sparingly - prefer StringResource for user-facing text.
     */
    data class DynamicString(val value: String) : UiText()

    /**
     * Represents a plural string resource with quantity and optional format arguments.
     */
    data class PluralResource(
        @PluralsRes val resId: Int,
        val quantity: Int,
        val args: List<Any> = emptyList()
    ) : UiText()

    /**
     * Returns the resolved string value.
     * In Compose, use asString() extension instead.
     */
    fun asString(context: Context): String {
        return when (this) {
            is StringResource -> {
                if (args.isEmpty()) {
                    context.getString(resId)
                } else {
                    context.getString(resId, *args.toTypedArray())
                }
            }
            is DynamicString -> value
            is PluralResource -> {
                if (args.isEmpty()) {
                    context.resources.getQuantityString(resId, quantity, quantity)
                } else {
                    context.resources.getQuantityString(resId, quantity, *args.toTypedArray())
                }
            }
        }
    }

    companion object {
        /**
         * Creates a UiText from a string resource ID.
         */
        fun from(@StringRes resId: Int, vararg args: Any): UiText {
            return StringResource(resId, args.toList())
        }

        /**
         * Creates a UiText from a raw string.
         * Use only for truly dynamic content (e.g., server responses).
         */
        fun from(value: String): UiText {
            return DynamicString(value)
        }

        /**
         * Creates a UiText from a plural resource.
         */
        fun plural(@PluralsRes resId: Int, quantity: Int, vararg args: Any): UiText {
            return PluralResource(resId, quantity, args.toList())
        }
    }
}

/**
 * Compose extension to resolve UiText to a String.
 * This should be used in Composables instead of asString(context).
 */
@Composable
fun UiText.asString(): String {
    return when (this) {
        is UiText.StringResource -> {
            if (args.isEmpty()) {
                stringResource(resId)
            } else {
                stringResource(resId, *args.toTypedArray())
            }
        }
        is UiText.DynamicString -> value
        is UiText.PluralResource -> {
            if (args.isEmpty()) {
                pluralStringResource(resId, quantity, quantity)
            } else {
                pluralStringResource(resId, quantity, *args.toTypedArray())
            }
        }
    }
}

/**
 * Extension to convert a nullable UiText to a nullable String in Compose.
 */
@Composable
fun UiText?.asStringOrNull(): String? {
    return this?.asString()
}

/**
 * Extension to convert a nullable UiText to a String with a default value in Compose.
 */
@Composable
fun UiText?.asStringOrDefault(default: String): String {
    return this?.asString() ?: default
}
