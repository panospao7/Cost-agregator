# Impact map — QF-1 "release truth" (UF-20, UF-21, UF-56) — 2026-10-22

Source: impact-mapper (Sonnet, read-only, no shell), persisted by orchestrator. **[git, orchestrator]** none of the
QF-1/QF-2 files (DeferredFeatureGate, FeatureConfig, NavigationController, MainActivity, *Negotiation*,
*PriceProtection*, DashboardFollowThrough, HomeScreen, strings, CategoryBreakdown, Warranty*, SubscriptionManagement,
EmptyState*, MoneyRadar, NaturalLanguage*) is dirty in the main worktree or in `Cost-agregator-mc`.

## Headline
1. MainActivity's deferred redirect is **inline** (`if (deferredFeaturesAvailable) … else navigateHome()` at
   Investment ~L802-815, Challenges L842-858, Tax ~L968); `shouldRedirectHome`/`isDeferredDestination` are test-only.
2. **No other entry point** to PriceProtection/BillNegotiation: only `FeatureConfig` + save-token restore (no deep
   links, shortcuts, manifest, NavigationTargetResolver, Home links). Menu filter + restore filter already make it
   unreachable in release → the MainActivity branch is defence-in-depth and the only conflict-bearing edit (**optional**).
3. UF-56: text fix trivial; the raw comparison uses `ExpenseDao.getAmountsForPercentileCalc` (`EFFECTIVE_AMOUNT_SQL`,
   unconverted, pooled across currencies) → fixing it touches dirty/money-sweep files → **T3, defer**. QF-1 = text only.
4. **New EN-only strings fail `:app:lintDebug`** (baseline has 2223 keyed MissingTranslation entries; any new key without
   el/es is a new error). Only the `lint` profile catches it. → Q-T is effectively decided: **EN+EL+ES per new key**,
   or baseline growth (FG-07, needs approval). Do not edit values of already-baselined strings — add new keys.

## Write sets
### UF-20 (a) hide via DeferredFeatureGate — T1
- `ui/navigation/DeferredFeatureGate.kt`: L21 add "price" to `DEFERRED_FEATURE_IDS`; L24-28 add "price_protection" to
  `DEFERRED_SAVE_TOKENS`; L36-39 `|| destination is NavigationDestination.PriceProtection`; KDoc L9/L21.
- `ui/navigation/NavigationController.kt` L213: `price_protection` restore `.takeIf { deferredAvailable }`.
- `FeatureConfig.kt`, `HomeScreen.kt`: no edit (filter picks up the id; menu already passes `DeferredFeatureGate.available`).
- Optional `MainActivity.kt` L778-782 inline branch (copy Challenges L842-858).
- Data/use cases stay alive: `ReceiptSideEffectPlanner.kt:541` still calls `findBetterDeals` (result discarded; no surface).
- Tests: `DeferredFeatureGateTest` (L13 exact id set, L22-29 tokens, L32-62 extend) — must update;
  `NavigationRouteContractTest` (L298-316, L333-347 add); `FeatureConfigNavigationContractTest` (auto-extends);
  optional `DeepLinkParserTest:64-72`.

### UF-20 (b) strip invented outputs — T1/T2
- `PriceProtectionTracker.kt`: delete `findCoupons` (L346-364), `getCreditCardBenefits` + helpers (L366-436), trim
  `getDealsCouponsAndBenefits` (L438-476), types L593-626. VM L72-76, L122-126. Screen L605-666, `CouponCard` L756-810,
  `CreditCardBenefitCard` L812-868, SIMULATED literal → string resource.
- Tests: delete ~9 in `PriceProtectionTrackerTest` (L204, L217-272) → **FG-19 justification + owner approval (deleting tests)**;
  update `PriceProtectionViewModelTest`.
- **Gap:** (b) alone does not make it honest — "Competitor Store" deals, simulated drops, "active for 30 days", hardcoded
  return windows remain.

### UF-21 negotiation honesty — T2
- `SmartBillNegotiationEngine.kt`: new `NegotiationAnalysis(opportunities, skippedNonEurCount)` + `analyze()`; keep
  `analyzeNegotiationOpportunities() = analyze().opportunities` (≈15 test call sites). Count non-EUR skips only (L42-51).
  **Do not change `recordNegotiationOutcome` signature** (pinned in `config/guards/db_ownership_policy.yml:4179-4215`).
  `minBy` fallback (L124) out of scope.
- `BillNegotiationViewModel.kt` L24-44: `skippedNonEurCount` StateFlow (0 in catch).
- `BillNegotiationScreen.kt`: static note "reference prices, Greece, EUR, approximate" near L100-117; skipped banner;
  conditional honest empty copy at L126 / ~L700-720 (new key; keep baselined `bill_negotiation_empty_subtitle`).
- Tests: add `analyze()` cases to `NegotiationEngineTest` (non-EUR gate at L747); new `BillNegotiationViewModelTest`.
  Keep CE rethrow (engine allowlisted `CATCH_WITHOUT_CE_RETHROW`, `CancellationSafetyArchitectureGuardTest.kt:134`).
- "Hide for no-EUR users" = data-dependent menu filter (HomeScreen/VM + `FeatureConfig.availableFeatures` signature;
  briefing S3/S4 conflict) → **not recommended**.

### UF-56 text fix — T2
- `DashboardFollowThroughEngine.kt:150` → `CurrencyFormatter.formatMoney(transaction.amount, transaction.currency)`.
  Locale-dependent output → `DashboardFollowThroughEngineTest.kt:120` (pins "€250.00") must pin locale or compute
  expectation; add non-EUR case. Comparison (L73) deferred. Shared-expense full-amount display noted, unchanged.
- ai-fixes B2d runs `DashboardFollowThroughEngineTest` (ai-fixes-plan:128,131) — coordinate the test file.

## Guards (FG-IDs)
FG-12 (UI/DAO ownership), FG-13 (engine in `db_access.json` + pinned signature — confirm with static-guards), FG-10
(cancellation), FG-17 (hardcoded currency — fix is consistent), FG-07 (no baseline growth), FG-19 (test deletions in
option b), FG-20 (lint). Nav contracts are unit tests. No Room/schema/worker/privacy surface; no LEGAL_PATHS change.

## Strings
Recommended: a new per-slice `strings_release_truth.xml` in values / values-el / values-es (app-lock TECH-7 convention,
`app-lock-privacy-mode-plan.md:103,224`) to avoid hot `strings.xml` contention. Greek + Spanish copy needed.

## Conflicts
- `MainActivity.kt`: app-lock S10 (W3, `handleIntent` ~170-251) + deep-link micro-slice + QF-2. QF-1 hunk (L778-782) is a
  disjoint region but a second writer → **recommend omitting the MainActivity hunk** (headline 2), or land first from tip.
- `strings.xml`: avoided via per-slice files. `HomeScreen.kt`: untouched unless the non-EUR hide option is chosen.
- loan S10a adds an `installments` FeatureConfig item behind a compile-time constant — align with DeferredFeatureGate.
- `ai-evolution-handoff.md:97` already lists the € literal as known.

## Tier and validation
Batch T2 (engine output model + money formatting). Cheap independent review + architecture-guardian (FG-12/13) on the
engine edit; no reviewer-strict unless the UF-56 comparison enters scope or a colliding MainActivity hunk is added.
Validation (one batch, validation-runner): `compile` → `targeted-unit-test`
`*DeferredFeatureGateTest* *FeatureConfigNavigationContractTest* *NavigationRouteContractTest* *DeepLinkParserTest*
*DestinationPersistencePolicyTest* *NegotiationEngineTest* *BillNegotiationViewModelTest* *DashboardFollowThroughEngineTest*
*CancellationSafetyArchitectureGuardTest*` (+ `*PriceProtection*` only for option b) → `lint` (required: new strings) →
`static-guards` once (engine file is guard-referenced).

## Owner questions
1. UF-20: (a) hide only [rec] or also (b) strip (deletes tests → approval)?
2. Include the MainActivity render branch, or menu + restore filtering only [rec: omit]?
3. UF-21: note + honest empty state, no hiding [rec]; `minBy` fallback deferred [rec]?
4. Skipped count: non-EUR only [rec]?
5. UF-56: text-only now, comparison → money sweep [rec]?
6. Strings: EN+EL+ES in per-slice `strings_release_truth.xml` [rec]; who supplies Greek/Spanish copy?
7. Order: QF-1 before deep-link micro-slice and app-lock W3; QF-2 after?
