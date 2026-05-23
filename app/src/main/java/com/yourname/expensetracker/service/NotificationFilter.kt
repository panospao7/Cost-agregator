package com.yourname.expensetracker.service

/**
 * Pure filter logic for deciding whether a notification should be captured.
 * Extracted for unit testing without Android/StatusBarNotification dependencies.
 *
 * ## P2-09: Finance filter v2
 * Finance packages no longer pass solely because of currency amounts.
 * Balance-only, account-info, FX-rate, security, and promotional notifications
 * are explicitly rejected even if they contain currency-looking text.
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
        pattern = """\d\s*[€$£¥]|[€$£¥]\s*\d|\d\s*(EUR|USD|GBP|CHF|PLN|RON|TRY|CAD|AUD|JPY|SEK|NOK|DKK|HUF|CZK)|(EUR|USD|GBP|CHF|PLN|RON|TRY|CAD|AUD|JPY|SEK|NOK|DKK|HUF|CZK)\s*\d""",
        options = setOf(RegexOption.IGNORE_CASE)
    )
    private val COMBINED_CURRENCY_REGEX = REGEX_CURRENCY
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
     * match financial heuristics.
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
     * P2-09: Finance-specific deny keywords — notifications from finance apps
     * matching these are rejected even if they contain currency amounts.
     * This prevents balance-only, account-summary, FX-rate, and promotional
     * finance notifications from being captured as expenses.
     */
    private val FINANCE_DENY_KEYWORDS: Set<String> = setOf(
        // Balance / account info
        "balance", "available balance", "account balance", "υπόλοιπο",
        "υπολοιπο", "διαθέσιμο υπόλοιπο", "διαθέσιμο", "available",
        // Account / statement
        "statement", "monthly summary", "account summary", "λογαριασμός",
        "λογαριασμο", "monthly statement", "e-statement",
        // FX / currency rate
        "exchange rate", "fx rate", "currency rate", "ισοτιμία", "ισοτιμια",
        "rate changed", "buy rate", "sell rate",
        // Incoming / credit (usually not an expense)
        "incoming transfer", "received", "credited", "deposit",
        "salary", "refund", "cashback", "εισερχόμενο", "κατάθεση",
        "καταθεση", "μισθός", "μισθο",
        // Security / auth
        "security", "login", "logged in", "new device", "otp",
        "verification", "authenticate",
        // Promotional
        "offer", "promo", "promotion", "reward", "discount",
        "deal", "προσφορά", "προσφορα",
        // Payment failed / declined
        "declined", "failed", "unsuccessful", "απορρίφθηκε",
        "αποτυχία", "αποτυχια", "ακυρώθηκε", "ακυρωθηκε"
    )

    /**
     * P2-09: Strong expense signals — these keywords indicate an actual
     * debit/purchase/expense transaction, not just any financial activity.
     */
    private val EXPENSE_SIGNAL_KEYWORDS: Set<String> = setOf(
        "paid", "spent", "purchase", "purchased", "charged", "card payment",
        "pos", "contactless", "debit", "withdrawn", "withdrawal", "payment",
        "πληρωμή", "πληρωμη", "αγορά", "αγορα", "χρέωση", "χρεωση",
        "κάρτα", "καρτα", "αναληψη", "ανάληψη", "ανάληψ"
    )

    /**
     * Returns true if the notification should be captured for expense tracking.
     *
     * P2-09: For finance packages, requires an actual expense signal or
     * review-worthy transaction signal — not just any currency amount.
     * Balance-only, account-info, FX-rate, security, and promotional
     * notifications from finance apps are rejected.
     *
     * @param packageName App package that posted the notification
     * @param title Notification title
     * @param text Notification body text
     * @param bigText Big text content (if any)
     */
    fun shouldCapture(packageName: String, title: String?, text: String?, bigText: String?): Boolean {
        return decide(packageName, title, text, bigText).capture
    }

    /**
     * Structured filter decision with reason, confidence, and direction.
     *
     * Returns a [NotificationFilterDecision] that includes why the notification
     * was captured or rejected, enabling richer diagnostics and analytics.
     *
     * @param packageName App package that posted the notification
     * @param title Notification title
     * @param text Notification body text
     * @param bigText Big text content (if any)
     */
    fun decide(packageName: String, title: String?, text: String?, bigText: String?): NotificationFilterDecision {
        if (IGNORED_PACKAGES.contains(packageName)) {
            return NotificationFilterDecision(
                capture = false,
                reason = NotificationFilterReason.IGNORED_PACKAGE,
                confidence = 1.0f,
                direction = TransactionDirection.UNKNOWN,
                hasMoneySignal = false
            )
        }

        val combined = listOfNotNull(title, text, bigText).joinToString(" ").lowercase()
        val hasAmount = COMBINED_CURRENCY_REGEX.containsMatchIn(combined) ||
            REGEX_AMOUNT.containsMatchIn(combined)

        // === Finance package path (P2-09) ===
        if (FINANCE_PACKAGES.contains(packageName)) {
            // Step 1: Hard-deny for security, promo, balance, account, FX, incoming
            if (FINANCE_DENY_KEYWORDS.any { combined.contains(it) }) {
                return NotificationFilterDecision(
                    capture = false,
                    reason = NotificationFilterReason.SECURITY_OR_AUTH,
                    confidence = 1.0f,
                    direction = TransactionDirection.UNKNOWN,
                    hasMoneySignal = hasAmount
                )
            }
            if (DENY_KEYWORDS.any { combined.contains(it) }) {
                return NotificationFilterDecision(
                    capture = false,
                    reason = NotificationFilterReason.SECURITY_OR_AUTH,
                    confidence = 1.0f,
                    direction = TransactionDirection.UNKNOWN,
                    hasMoneySignal = hasAmount
                )
            }

            // Step 2: Must have an amount/currency signal
            if (!hasAmount) {
                return NotificationFilterDecision(
                    capture = false,
                    reason = NotificationFilterReason.NO_AMOUNT,
                    confidence = 1.0f,
                    direction = TransactionDirection.UNKNOWN,
                    hasMoneySignal = false
                )
            }

            // Step 3: Must have an expense signal or a review-worthy transaction signal.
            // Finance packages no longer pass solely because of currency amounts.
            val hasExpenseSignal = EXPENSE_SIGNAL_KEYWORDS.any { combined.contains(it) }
            val hasTransactionSignal = combined.contains("transaction") ||
                combined.contains("transfer") || combined.contains("payment") ||
                combined.contains("sent") || combined.contains("purchase")
            if (!hasExpenseSignal && !hasTransactionSignal) {
                return NotificationFilterDecision(
                    capture = false,
                    reason = NotificationFilterReason.NO_TRANSACTION_SIGNAL,
                    confidence = 1.0f,
                    direction = TransactionDirection.UNKNOWN,
                    hasMoneySignal = hasAmount
                )
            }

            return NotificationFilterDecision(
                capture = true,
                reason = if (hasExpenseSignal) NotificationFilterReason.ALLOW_STRONG_EXPENSE
                         else NotificationFilterReason.ALLOW_REVIEWABLE_FINANCIAL_SIGNAL,
                confidence = 0.8f,
                direction = TransactionDirection.UNKNOWN,
                hasMoneySignal = hasAmount
            )
        }

        // === Communication / unknown package path ===
        // PRV-2: Deny-keyword check for non-finance packages
        if (DENY_KEYWORDS.any { combined.contains(it) }) {
            return NotificationFilterDecision(
                capture = false,
                reason = NotificationFilterReason.SECURITY_OR_AUTH,
                confidence = 1.0f,
                direction = TransactionDirection.UNKNOWN,
                hasMoneySignal = hasAmount
            )
        }

        // Communication apps and unknown packages require amount + financial keyword
        if (!hasAmount) {
            return NotificationFilterDecision(
                capture = false,
                reason = NotificationFilterReason.NO_AMOUNT,
                confidence = 1.0f,
                direction = TransactionDirection.UNKNOWN,
                hasMoneySignal = false
            )
        }

        val hasFinancialKeyword = FINANCIAL_KEYWORDS.any { combined.contains(it) }
        if (!hasFinancialKeyword) {
            return NotificationFilterDecision(
                capture = false,
                reason = NotificationFilterReason.NO_TRANSACTION_SIGNAL,
                confidence = 1.0f,
                direction = TransactionDirection.UNKNOWN,
                hasMoneySignal = hasAmount
            )
        }

        return NotificationFilterDecision(
            capture = true,
            reason = NotificationFilterReason.ALLOW_REVIEWABLE_FINANCIAL_SIGNAL,
            confidence = 0.8f,
            direction = TransactionDirection.UNKNOWN,
            hasMoneySignal = hasAmount
        )
    }
}
