package com.yourname.expensetracker.ui.navigation

import android.net.Uri

/**
 * Deep link parsing result with security classification.
 *
 * Separates parsing from execution so that:
 * 1. Parsing can be unit-tested without Android context
 * 2. Sensitive routes can be gated before navigation
 * 3. Unknown/malformed links are safely rejected
 */
sealed interface DeepLinkDecision {
    /** Safe to navigate immediately (no sensitive data exposed). */
    data class Allow(val destination: NavigationDestination) : DeepLinkDecision

    /** Contains sensitive financial data — requires user confirmation before navigating. */
    data class RequireConfirmation(
        val destination: NavigationDestination,
        val reason: String
    ) : DeepLinkDecision

    /** Unknown or malformed deep link — reject silently. */
    data object Reject : DeepLinkDecision
}

/**
 * Parses an expense tracker deep link URI into a navigation decision.
 *
 * Scheme: `expensetracker://`
 * Hosts: home, dashboard, activity, review, plan, add, analytics, map
 *
 * Security policy:
 * - `home`, `dashboard`, `plan` → Allow (no sensitive data)
 * - `activity` without expenseId → Allow (just opens list)
 * - `activity` with expenseId → RequireConfirmation (exposes specific transaction)
 * - `review` → RequireConfirmation (shows pending financial data)
 * - `add` → RequireConfirmation (can create financial records)
 * - `analytics`, `map` → Allow (aggregate data, not individual records)
 */
fun parseDeepLink(uri: Uri): DeepLinkDecision {
    if (uri.scheme != "expensetracker") return DeepLinkDecision.Reject

    return when (uri.host) {
        "home", "dashboard" -> DeepLinkDecision.Allow(NavigationDestination.Home)

        "activity" -> {
            val expenseId = uri.getQueryParameter("expenseId")?.toLongOrNull()
            if (expenseId != null) {
                DeepLinkDecision.RequireConfirmation(
                    destination = NavigationDestination.Transactions(initialExpenseId = expenseId),
                    reason = "View specific transaction"
                )
            } else {
                DeepLinkDecision.Allow(NavigationDestination.Transactions())
            }
        }

        "review" -> DeepLinkDecision.RequireConfirmation(
            destination = NavigationDestination.Review,
            reason = "Access pending review queue"
        )

        "plan" -> DeepLinkDecision.Allow(NavigationDestination.Budget)

        "add" -> DeepLinkDecision.RequireConfirmation(
            destination = NavigationDestination.AddExpense,
            reason = "Create financial record"
        )

        "analytics" -> DeepLinkDecision.Allow(
            NavigationDestination.Analytics(
                initialPeriod = uri.getQueryParameter("period")
            )
        )

        "map" -> DeepLinkDecision.Allow(
            NavigationDestination.SpendingMap(
                initialLocationQuery = uri.getQueryParameter("location")
            )
        )

        else -> DeepLinkDecision.Reject
    }
}
