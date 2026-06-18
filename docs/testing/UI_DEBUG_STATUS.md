# UI Debug Status Tracker

Last updated: 2026-05-14

## Slice 1 — Navigation Core (100% complete)

| Issue | Description | Status | Commit |
|-------|-------------|--------|--------|
| S1-001 | Route inventory drift | ✅ Fixed | `8e084e6b` |
| S1-002 | NavigationController behavior lacks tests | ✅ Fixed | `8e084e6b` |
| S1-003 | FeatureIntegration quick actions not clickable | ✅ Fixed | `8e084e6b` |
| S1-004 | Deep links handled without auth gate | ✅ Fixed | `8731dc16` |
| S1-005 | Debug screen bypasses destination router | ✅ Fixed | `8731dc16` |
| S1-006 | Payload destinations fragile restore | ✅ Fixed | `8731dc16` |
| S1-007 | AppFabMenu/SmartFAB duplication | ✅ Fixed (deleted AppFabMenu) | `8731dc16` |
| S1-008 | Missing destination render coverage test | ✅ Fixed | `8e084e6b` |

---

## Slice 2 — Theme + Shared UI Primitives (83% complete)

| Issue | Description | Status | Commit |
|-------|-------------|--------|--------|
| S2-001 | Shared primitives bypass theme colors | ✅ Fixed | `e4c66f4d` |
| S2-002 | Buttons with null callbacks look clickable | ✅ Fixed | `e4c66f4d` |
| S2-003 | EmptyState/ErrorState not scroll-safe | ✅ Fixed | `e4c66f4d` |
| S2-004 | EmptyState/EnhancedEmptyState duplication | ✅ Fixed (EmptyState delegates) | `ff1a056f` |
| S2-005 | Loading skeleton accessibility noise | ✅ Fixed (parent semantics) | `c740f4cd` |
| S2-006 | Registry overwrites on duplicate registration | ✅ Fixed (merge semantics) | `e4c66f4d` |
| S2-007 | Empty-state actions hardcoded English strings | ✅ Fixed (@StringRes) | `c332c5b0` |
| S2-008 | Form amount input too naive for money | ✅ Fixed (AmountInputSanitizer) | `c740f4cd` |
| S2-009 | Form dialogs hardcoded defaults | ✅ Fixed (string resources) | `c740f4cd` |
| S2-010 | Theme Activity cast fragile | ✅ Fixed (safe findActivity) | `e4c66f4d` |
| S2-011 | Missing Compose tests for global components | ⏭️ Deferred (needs Compose test infra) | — |
| S2-012 | Documentation numbering drift | ⏭️ Deferred (trivial) | — |

---

## Slice 3 — Privacy/Security UI (75% complete)

| Issue | Description | Status | Commit |
|-------|-------------|--------|--------|
| S3-001 | PrivacyBlockedCard not wired into screens | ✅ Fixed | `d2197899` |
| S3-002 | PrivacyBlockedCard API too weak | ✅ Fixed (typed API, semantics) | `d2197899` |
| S3-003 | PrivacySettingsScreen ignores denied-state data | ✅ Fixed | `d2197899` |
| S3-004 | Privacy settings save failures silent | ✅ Fixed | `d2197899` |
| S3-005 | Cloud AI split-brain UX | ⏭️ Deferred (needs EffectivePolicy UI) | — |
| S3-006 | AiSettingsViewModel provider test not testable | ⏭️ Deferred (larger refactor) | — |
| S3-007 | API key save can silently delete stored key | ✅ Fixed (explicit removeApiKey) | `026c5ff8` |
| S3-008 | Backup screen no privacy preflight | ✅ Partial (error handling improved) | `d2197899` |
| S3-009 | Null input stream creates empty temp file | ✅ Fixed | `d2197899` |
| S3-010 | Restart action embedded in Compose | ✅ Fixed (extracted to callback) | `d2197899` |
| S3-011 | PrivacyGate audit contract conflicts | ⏭️ Deferred (docs-only) | — |
| S3-012 | New capabilities can fail open | ✅ Fixed (policy test) | `d2197899` |

---

## Slice 4 — Home/Dashboard (71% complete)

| Issue | Description | Status | Commit |
|-------|-------------|--------|--------|
| S4-001 | HomeScreen monolithic composable | ⏭️ Deferred (large extraction, no user impact) | — |
| S4-002 | HomeViewModel too many responsibilities | ⏭️ Deferred (large extraction, no user impact) | — |
| S4-003 | Widget render else branch (silent failures) | ✅ Fixed (contract test) | `a08ad164` |
| S4-004 | Widget ID/config/style mapping drift | ✅ Fixed (DashboardWidgetMetaContractTest) | `a6cfcd73` |
| S4-005 | SafeToSpend no-budget misleading | ✅ Fixed | `c0148aac` |
| S4-006 | Calendar.getInstance in composable | ✅ Fixed (UiTimeUtils.dayRange) | `c0148aac` |
| S4-007 | Currency reactivity bug (category trends) | ✅ Fixed | `c0148aac` |
| S4-008 | Cross-widget financial invariants not enforced | ⏭️ Deferred (partially covered by golden tests) | — |
| S4-009 | Totals drill-down state machine fragile | ✅ Fixed (breadcrumb stack) | `2fe8c76c` |
| S4-010 | Totals/category errors invisible | ✅ Fixed (error + retry in TotalsDashboardCard) | `026c5ff8` |
| S4-011 | Widget config mutations synchronous | ✅ Fixed (async viewModelScope) | `2fe8c76c` |
| S4-012 | Widget edit overlay lacks boundary UX | ✅ Fixed (disabled at boundaries) | `a6cfcd73` |
| S4-013 | Unsafe Array casts in combine | ✅ Documented (Kotlin coroutines limit) | `026c5ff8` |
| S4-014 | Recommendation navigation error handling | ✅ Fixed (NoOp + exception handling) | `2fe8c76c` |

---

## Slice 5 — Transactions + Manual Add (83% complete)

| Issue | Description | Status | Commit |
|-------|-------------|--------|--------|
| S5-001 | TransactionsScreen monolithic | ⏭️ Deferred (large extraction, no user impact) | — |
| S5-002 | Initial filter can leave stale filters | ✅ Fixed (clearRouteFilter) | `2fe8c76c` |
| S5-003 | Tab label disagrees with date-range filter | ✅ Fixed (filter chip above tabs) | `a6cfcd73` |
| S5-004 | Mixed-currency list totals incorrect | ✅ Fixed (shows "Mixed currencies") | `2fe8c76c` |
| S5-005 | homeCurrency placeholder EUR can leak into saves | ✅ Fixed | `3943ad10` |
| S5-006 | Amount input sanitizer weak | ✅ Fixed (AmountInputSanitizer wired) | `026c5ff8` |
| S5-007 | Save not idempotency-safe (double-tap) | ✅ Fixed | `3943ad10` |
| S5-008 | Merchant suggestion no failure handling | ✅ Fixed (try/catch, clears on error) | `a6cfcd73` |
| S5-009 | Suggestion amount formatting locale-sensitive | ✅ Fixed (Locale.US + sanitizer) | `a6cfcd73` |
| S5-010 | Transfer type update not atomic | ✅ Fixed (always clears transfer fields) | `026c5ff8` |
| S5-011 | Ownership/shared validation inconsistent | ✅ Fixed (OwnershipValidator extracted) | `026c5ff8` |
| S5-012 | Mutation dialogs close before persistence result | ✅ Fixed (close on successMessage) | `2fe8c76c` |

---

## Summary

| Slice | Total Issues | Fixed | Deferred | Coverage |
|-------|-------------|-------|----------|----------|
| 1 — Navigation | 8 | 8 | 0 | **100%** |
| 2 — Shared UI | 12 | 10 | 2 | **83%** |
| 3 — Privacy | 12 | 9 | 3 | **75%** |
| 4 — Dashboard | 14 | 10 | 4 | **71%** |
| 5 — Transactions | 12 | 10 | 2 | **83%** |

## Deferred items rationale

- S2-011: Requires Compose test infrastructure setup
- S2-012: Trivial docs numbering fix
- S3-005/006: Require deeper ViewModel refactors
- S3-011: Documentation-only fix
- S4-001/002: Large extraction refactors (no user impact)
- S4-008: Partially covered by AnalyticsDashboardBudgetParityGoldenTest
- S5-001: Large extraction refactor (no user impact)
