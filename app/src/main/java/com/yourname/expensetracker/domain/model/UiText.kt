package com.yourname.expensetracker.domain.model

/**
 * UiText is a sealed class that represents text that can be displayed in the UI.
 * This is intentionally a pure token/value object with no Android/Compose dependencies.
 * Resolution to platform strings is handled in the presentation layer.
 */
sealed class UiText {
    /**
     * Represents a string resource with optional format arguments.
     */
    data class StringResource(
        val resId: Int,
        val args: List<Any> = emptyList()
    ) : UiText()

    /**
     * Represents a platform-agnostic message key with optional format arguments.
     * The presentation layer maps keys to concrete resources.
     */
    data class MessageKey(
        val key: String,
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
        val resId: Int,
        val quantity: Int,
        val args: List<Any> = emptyList()
    ) : UiText()

    companion object {
        /**
         * Creates a UiText from a string resource ID.
         */
        fun from(resId: Int, vararg args: Any): UiText {
            return StringResource(resId, args.toList())
        }

        /**
         * Creates a UiText from a platform-agnostic message key.
         */
        fun fromKey(key: String, vararg args: Any): UiText {
            return MessageKey(key, args.toList())
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
        fun plural(resId: Int, quantity: Int, vararg args: Any): UiText {
            return PluralResource(resId, quantity, args.toList())
        }
    }
}
