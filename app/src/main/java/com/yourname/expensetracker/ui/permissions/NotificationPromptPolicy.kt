package com.yourname.expensetracker.ui.permissions

/**
 * R24b: pure policy for the POST_NOTIFICATIONS prompt (API 33+).
 *
 * Owner decision: ask once per install; no automatic re-prompts afterwards. When the system will
 * no longer show its permission dialog, the in-app dialog button opens the app notification
 * settings instead.
 */
object NotificationPromptPolicy {
    const val TIRAMISU_SDK = 33

    /** True only for the single automatic prompt: API 33+, not granted, never asked before. */
    fun shouldAutoPrompt(granted: Boolean, sdkInt: Int, alreadyAsked: Boolean): Boolean =
        sdkInt >= TIRAMISU_SDK && !granted && !alreadyAsked

    /**
     * True when, after a system permission request returned denied, the system evidently did not
     * (or will no longer) show its dialog: no rationale was available before the request and none
     * is available after it. The caller then shows the in-app dialog once more in "open settings"
     * mode. A first-time denial has rationaleAfter == true and therefore never qualifies.
     */
    fun shouldOfferSettingsAfterDenial(
        rationaleBefore: Boolean,
        rationaleAfter: Boolean,
        granted: Boolean
    ): Boolean = !granted && !rationaleBefore && !rationaleAfter
}
