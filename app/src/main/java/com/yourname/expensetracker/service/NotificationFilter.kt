package com.yourname.expensetracker.service

/**
 * Pure filter logic for deciding whether a notification should be captured.
 * Extracted for unit testing without Android/StatusBarNotification dependencies.
 */
object NotificationFilter {

    val MONITORED_PACKAGES: Set<String> = setOf(
        "com.revolut.revolut",
        "com.google.android.apps.walletnfcrel",
        "com.google.android.apps.nbu.paisa.user",
        "gr.nbg.mobilebanking",
        "com.eurobank.mobile",
        "gr.alpha.mobile",
        "com.winbank.mobile",
        "com.viber.voip",
        "com.google.android.gm",
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging"
    )

    val IGNORED_PACKAGES: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.whatsapp",
        "com.facebook.orca",
        "com.instagram.android",
        "com.snapchat.android",
        "com.google.android.youtube"
    )

    private val REGEX_CURRENCY = Regex("""[€$£¥]|(EUR|USD|GBP|CHF)""")
    private val REGEX_AMOUNT = Regex("""\d+[.,]\d{2}""")

    private val FINANCIAL_KEYWORDS = setOf(
        "paid", "spent", "purchase", "charged", "payment", "transaction", "amount",
        "card", "debit", "credit", "bank", "wallet",
        "πληρωμ", "αγορ", "χρέωσ", "συναλλαγ", "κάρτα", "μεταφορ"
    )

    /**
     * Returns true if the notification should be captured for expense tracking.
     *
     * @param packageName App package that posted the notification
     * @param title Notification title
     * @param text Notification body text
     * @param bigText Big text content (if any)
     */
    fun shouldCapture(packageName: String, title: String, text: String, bigText: String): Boolean {
        if (IGNORED_PACKAGES.contains(packageName)) return false
        if (MONITORED_PACKAGES.contains(packageName)) return true

        // Discovery Mode: Heuristic check for unmonitored packages
        val content = (title + " " + text + " " + bigText).lowercase()

        val hasAmount = REGEX_CURRENCY.containsMatchIn(content) || REGEX_AMOUNT.containsMatchIn(content)
        if (!hasAmount) return false

        return FINANCIAL_KEYWORDS.any { content.contains(it) }
    }
}
