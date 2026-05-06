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
        "mbanking.NBG",
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

    private val REGEX_CURRENCY = Regex(
        pattern = """\d\s*[€$£¥]|[€$£¥]\s*\d|\d\s*(EUR|USD|GBP|CHF)|(EUR|USD|GBP|CHF)\s*\d""",
        options = setOf(RegexOption.IGNORE_CASE)
    )
    private val REGEX_AMOUNT = Regex("""\d+[.,]\d{2}""")

	val FINANCIAL_KEYWORDS = setOf(
        // English
        "paid", "spent", "purchase", "charged", "payment", "transaction", "amount",
        "card", "debit", "credit", "bank", "wallet", "deposit", "withdrawal", "transfer",
        // Greek
        "πληρωμ", "αγορ", "χρέωσ", "χρεώ", "συναλλαγ", "κάρτα", "μεταφορ",
        "κατάθεσ", "πιστωση", "πίστωση", "έμβασμα", "εμβασμα", "ανάληψ", "αναληψ",
        // Greeklish (Latin transliterations of Greek financial terms)
        "plirom", "agora", "xreos", "synallagi", "kart", "metora",
        "katathes", "pistosi", "emvasma", "analips"
    )

    /**
     * Deny-keyword list — notifications whose content (title, text, or bigText)
     * contains any of these words/phrases will be skipped even if they otherwise
     * match financial heuristics. Customizable by the user at runtime.
     *
     * PRV-2: Added to prevent accidental capture of sensitive non-financial
     * notifications (e.g. two-factor auth codes, password resets, promotional
     * messages) that happen to contain currency amounts or financial keywords.
     */
    private val DENY_KEYWORDS: Set<String> = setOf(
        // Security / authentication
        "2fa", "two-factor", "verification code", "auth code",
        "password reset", "login attempt", "security code", "one-time",
        // Promotional / non-transactional
        "promo code", "cashback offer",
        // Greek — security/promo only
        "κωδικός ασφαλείας", "κωδικ επαληθ", "συνδεση", "προσφορα"
    )

    /**
     * Returns true if the notification should be captured for expense tracking.
     *
     * PRV-2: Added deny-keyword check before heuristic matching. Any keyword
     * match in title/text/bigText causes the notification to be skipped.
     *
     * @param packageName App package that posted the notification
     * @param title Notification title
     * @param text Notification body text
     * @param bigText Big text content (if any)
     */
    fun shouldCapture(packageName: String, title: String?, text: String?, bigText: String?): Boolean {
        if (IGNORED_PACKAGES.contains(packageName)) return false

        // Finance apps bypass ALL heuristics — every notification is financial.
        // Deny keywords do NOT apply here: bank 2FA / promo filtering is handled
        // downstream by the parser and confidence router.
        if (FINANCE_PACKAGES.contains(packageName)) return true

        val content = listOf(title, text, bigText)
            .joinToString(separator = " ") { it.orEmpty() }
            .lowercase()

        // PRV-2: Deny-keyword check for non-finance packages (communication +
        // unknown) — prevents 2FA codes and promos from being captured.
        if (DENY_KEYWORDS.any { content.contains(it) }) return false

        // Communication apps (Gmail, Viber, SMS) and unknown packages both go
        // through the same heuristic gate: require amount + financial keyword.
        val hasAmount = REGEX_CURRENCY.containsMatchIn(content) || REGEX_AMOUNT.containsMatchIn(content)
        if (!hasAmount) return false

        return FINANCIAL_KEYWORDS.any { content.contains(it) }
    }
}
