package com.yourname.expensetracker.service

/**
 * Pure filter logic for deciding whether a notification should be captured.
 * Extracted for unit testing without Android/StatusBarNotification dependencies.
 */
object NotificationFilter {

    /**
     * Finance-app packages that are always captured unconditionally —
     * every notification from these apps is assumed to be financial.
     */
    val FINANCE_PACKAGES: Set<String> = setOf(
        "com.revolut.revolut",
        "com.google.android.apps.walletnfcrel",
        "com.google.android.apps.nbu.paisa.user",
        "gr.nbg.mobilebanking",
        "com.eurobank.mobile",
        "gr.alpha.mobile",
        "com.winbank.mobile"
    )

    /**
     * Communication/carrier packages (email, SMS, messaging) that CAN relay
     * financial alerts but also carry unrelated personal content.
     * These must go through heuristic checks — they are NOT unconditionally captured.
     */
    val COMMUNICATION_PACKAGES: Set<String> = setOf(
        "com.viber.voip",
        "com.google.android.gm",
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging"
    )

    /**
     * Union of finance + communication packages retained for backward-compat
     * callers that enumerate "all interesting" packages (e.g. service binding).
     */
    val MONITORED_PACKAGES: Set<String> = FINANCE_PACKAGES + COMMUNICATION_PACKAGES

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
    fun shouldCapture(packageName: String, title: String?, text: String?, bigText: String?): Boolean {
        if (IGNORED_PACKAGES.contains(packageName)) return false

        // Finance apps bypass heuristics — every notification is financial
        if (FINANCE_PACKAGES.contains(packageName)) return true

        // Communication apps (Gmail, Viber, SMS) and unknown packages both go
        // through the same heuristic gate: require amount + financial keyword.
        val content = listOf(title, text, bigText)
            .joinToString(separator = " ") { it.orEmpty() }
            .lowercase()

        val hasAmount = REGEX_CURRENCY.containsMatchIn(content) || REGEX_AMOUNT.containsMatchIn(content)
        if (!hasAmount) return false

        return FINANCIAL_KEYWORDS.any { content.contains(it) }
    }
}
