# money-aggregates-sweep — handoff

Feature id: `money-aggregates-sweep` (Increment 1: fail-closed visible totals). Plan: workflows/active/money-aggregates-sweep-plan.md. Spec: docs/features/money-aggregates-sweep/.
Status (2026-10-23): Inc 1 IMPLEMENTED in worktree C:\Users\panos\Desktop\Cost-agregator-ma, branch feature/money-aggregates (cut from feature/money-correctness 681d49a3), uncommitted. G1 gate PASSED. Fresh reviewer-strict ACCEPT-WITH-MINORS.
Evidence: build/release-lead-notes/ma-record-20261022.md (main checkout).

## Slice statuses
| Slice | Status |
|---|---|
| S0 | done (preflight; OG-3 not triggered) |
| S1 GroupSettlementBalancePolicy.displayedBalances | done, validated, reviewed |
| S2 groups VM/screen | done, validated, reviewed (plural renamed money_total_partial_notice) |
| S3 recurring monthly total | done, validated, reviewed |
| S4 budget engine/VM/card/monitor + OG-1 | done, validated, reviewed |
| S5 anomaly cancellation + OG-2 | done, validated, reviewed |
| S6 LEGAL_PATHS / ENGINE_INTERACTION_MAP | done |
| G1 | PASSED: unit-tests 7759/0, static-guards, cancellation guard, lint |

## Accepted deviations
- BudgetMonitor EFFECTIVE_SPEND_UNAVAILABLE skip block uses the same broad catch (CE rethrow + DatabaseAccessBlockedException branch) as its sibling STATUS_SKIPPED blocks (plan R7 says no new broad catch).
- e2e/BudgetAlertPipelineTest fixture given a breakdown with effectiveSpend = spent (not in plan list).
- Recurring reuses SubscriptionTotalStatus from ui/screens/subscription.

## Owed / follow-ups
- Owner: device check with synthetic data and non-EUR home; decide BudgetSummaryCard health counts for null-breakdown budgets.
- privacy-guardian: BudgetRepository.kt Timber text "monitor/UI fall back to gross spend" (frozen by R4).
- MC inc 2 / F-4: over-budget slot uses gross percentUsed (can show negative over amount); offset FX basis.
- Optional: move SubscriptionTotalStatus to a shared ui/model; composable-level render tests; remove unused budget_spent_format.
- On merge: update app-lock-privacy-mode-plan.md:450 note (S5 landed; G-CANCEL-02 AnomalyAlertOrchestrator entry removed); app-lock S6 rebases onto BudgetMonitor/AnomalyAlertOrchestrator changes and re-checks cancellation.json counts.
- Next for this feature: group-settle-up (Inc 2) after merge; Inc 3 waits on owner gates.
