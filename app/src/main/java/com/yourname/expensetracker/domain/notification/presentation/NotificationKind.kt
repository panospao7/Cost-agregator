package com.yourname.expensetracker.domain.notification.presentation

/**
 * Closed registry of the app notification kinds the content policy governs (INV-12, AMD-17,
 * domain-spec 2.5). Exactly six. The capture foreground-service notification is not a kind: it keeps
 * its constant generic text and never goes through the policy.
 *
 * A kind identifies the discreet constants only; no literal copy lives in the domain layer (the data
 * layer maps a kind to string resources, TECH-2, INV-5).
 *
 * [stableToken] is the literal value the data layer stamps as the hidden kind marker (TECH-12). It is
 * written out per kind on purpose, never derived from [name], so renaming a constant or shrinking
 * cannot change what earlier releases wrote into active notifications. A test pins every token.
 */
enum class NotificationKind(val stableToken: String) {
    BILL("BILL"),
    ANOMALY("ANOMALY"),
    BUDGET("BUDGET"),
    WARRANTY("WARRANTY"),
    BRIEFING("BRIEFING"),
    RECEIPT_MATCH("RECEIPT_MATCH")
}
