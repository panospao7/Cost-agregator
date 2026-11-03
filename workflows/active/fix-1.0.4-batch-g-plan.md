# fix-1.0.4 Batch G: search filters and Add Expense restore

Planner: feature-planner (Sonnet high), anchored at ec950a6e. No schema, guard, baseline, allowlist or pinned-count change.

## Findings that do not need a fix now
- **E-04-1: not reproducible.** `SharedExpenseManager` divides by all members, but `SharedExpenseDataPortAdapter.addExpense` never forwards that value. The persisted share comes from `GroupTransactionCoordinator.resolveCurrentUserShare`, which applies joinedAt/leftAt through `SplitCalculator`. The Manager value is dead data.
- **P-07-1, P-07-2: already fixed.** `BackupVerifier.TABLE_TIERS` covers all entity tables and `BackupVerifierTableCoverageTest` pins that. Typed `SemanticAggregates` replaced the empty query map.
- **E-02-3: debug builds only.** Carbon is behind `DeferredFeatureGate`. Fix it when the feature is un-deferred.
- **J-09-1: real, owner decision.** The briefing is generated only by the midnight worker. Any fix adds a new cloud AI trigger. It must go through a one-shot worker run under `WorkerExecutionGuard` and the `CLOUD_AI_DAILY_BRIEFING` capability, never from Home directly.

## Decisions (owner item 116: recommended defaults taken; recorded for overrule)
1. A typed marker that differs from home ("over $50" with EUR home) is an extraction signal only; the number is treated as home currency. The search chip shows the number applied.
2. J-01-1 enforces the declared EPHEMERAL policy for all four destinations: AddExpense, ScanReceipt, Assistant, Debug. After process death they restore to the previous screen. The draft is not persisted, keeping the "never persisted" privacy rule.

## Lane A, `Cost-agregator-rsv`, `feature/fix104-g-search`, FL-03R (T2, money-adjacent, separate test-author)
Source: `domain/naturallanguage/NaturalLanguageSearchEngine.kt`. Tests: `NaturalLanguageSearchEngineAmountTest.kt`, new `NaturalLanguageSearchEngineFilterTest.kt`.
Fix: one-line regex; mask full dates; require a currency marker or comparison keyword; reject numbers before time units; word boundaries on comparison keywords; comparison attaches to the nearest preceding keyword; comma with exactly 3 digits is thousands, otherwise decimal.
Traps in the newly live filter (:346-424): "between EUR 10 and EUR 50" must not hit the date pattern at :173; `find { it != amount }` makes "between 20 and 20" match everything; duplicate O(n squared) lambdas at :376-387 and :409-421; EXACTLY compares doubles (use cents); `mapNotNull` at :352-372 drops unrated rows even with no amount filter, which is reachable today; types `ExtractedAmount` and `AmountComparison` stay unchanged.
Deferred, overlap: surfacing `failedCurrencyConversions` in the UI.
Filter: `*NaturalLanguageSearchEngine*,*NaturalLanguageSearchViewModel*`.

## Lane B, `Cost-agregator-sfa`, `feature/fix104-g-nav`, J-01-1 (UI, one writer with tests)
Source: `ui/navigation/NavigationController.kt`, `DestinationPersistencePolicy.kt` only if needed. Tests: new `ui/navigation/NavigationRestorePolicyTest.kt`.
Root cause: `DestinationPersistencePolicy.EPHEMERAL` is declared but not enforced on restore, so a fresh VM reopens the Add Expense sheet and re-reads the clipboard.
Fix: pure restore helper; an EPHEMERAL current destination falls back to the last non-ephemeral back-stack entry, then `previousMainTab`, then Home; EPHEMERAL entries leave the restored stack; token layer untouched.
Traps: share the `previousMainTab` mapping; `InstallmentsFeature.shouldRedirectHome` and `DeferredFeatureGate` still apply; helper is idempotent; deep-link add is unaffected.
Filter: `*NavigationRestorePolicyTest,*DestinationPersistencePolicyTest,*NavigationRouteContractTest,*InstallmentsFeatureGateTest,*DeepLinkApplier*`.

## Also merged with this batch
Batch F review minors: `feature/fix104-g-receipt` (`Cost-agregator-we`), `feature/fix104-g-savings` (`Cost-agregator-sgd`).
