# UI Debug Status Tracker

Last updated: 2026-05-14

## Slice 1 — Navigation Core

| Issue | Description | Status | Commit |
|-------|-------------|--------|--------|
| S1-001 | Route inventory drift (no contract test) | ✅ Fixed | `8e084e6b` |
| S1-002 | NavigationController behavior lacks tests | ✅ Fixed | `8e084e6b` |
| S1-003 | FeatureIntegration quick actions not clickable | ✅ Fixed | `8e084e6b` |
| S1-004 | Deep links handled without auth gate | ✅ Fixed | `8731dc16` |
| S1-005 | Debug screen bypasses destination router | ✅ Fixed | `8731dc16` |
| S1-006 | Payload destinations fragile restore | ✅ Fixed | `8731dc16` |
| S1-007 | AppFabMenu/SmartFAB duplication | ✅ Fixed (deleted AppFabMenu) | `8731dc16` |
| S1-008 | Missing destination render coverage test | ✅ Fixed (via FeatureConfig contract) | `8e084e6b` |

**Tests added:** NavigationControllerBehaviorTest (13), FeatureConfigNavigationContractTest (7), DeepLinkParserTest (11), DestinationPersistencePolicyTest (4)

---

## Slice 2 — Theme + Shared UI Primitives

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
| S2-012 | Documentation numbering drift | ⏭️ Deferred (low priority) | — |

**Tests added:** EmptyStateRegistryCompletenessTest (2)
**Production fixes:** Theme colors, scroll safety, button states, registry merge, Activity cast, skeleton a11y, amount sanitizer, form defaults, EmptyState deduplication, action string localization, 4 missing empty state registrations

---

## Slice 3 — Privacy/Security UI

| Issue | Description | Status | Commit |
|-------|-------------|--------|--------|
| S3-001 | PrivacyBlockedCard not wired into screens | ✅ Fixed (upgraded + wired into PrivacySettings) | `d2197899` |
| S3-002 | PrivacyBlockedCard API too weak | ✅ Fixed (typed PrivacyBlocked, semantics, testTag) | `d2197899` |
| S3-003 | PrivacySettingsScreen ignores denied-state data | ✅ Fixed (shows blocked cards) | `d2197899` |
| S3-004 | Privacy settings save failures silent | ✅ Fixed (errorMessage exposed) | `d2197899` |
| S3-005 | Cloud AI split-brain UX | ⏭️ Deferred (needs EffectivePolicy UI) | — |
| S3-006 | AiSettingsViewModel provider test not testable | ⏭️ Deferred (needs CloudProviderConnectionTester extraction) | — |
| S3-007 | API key save can silently delete stored key | ⏭️ Deferred (needs AiSettingsViewModel refactor) | — |
| S3-008 | Backup screen no privacy preflight | ✅ Partial (pattern documented, error handling improved) | `d2197899` |
| S3-009 | Null input stream creates empty temp file | ✅ Fixed (explicit error on null) | `d2197899` |
| S3-010 | Restart action embedded in Compose | ✅ Fixed (extracted to callback) | `d2197899` |
| S3-011 | PrivacyGate audit contract conflicts | ⏭️ Deferred (docs-only fix needed) | — |
| S3-012 | New capabilities can fail open | ✅ Fixed (PrivacyCapabilityHandlingPolicyTest) | `d2197899` |

**Tests added:** PrivacyCapabilityHandlingPolicyTest (3)
**Production fixes:** PrivacyBlockedCard upgraded, PrivacySettingsViewModel typed blocked list + error, BackupRestore null stream fix + restart callback

---

## Summary

| Slice | Total Issues | Fixed | Deferred | Coverage |
|-------|-------------|-------|----------|----------|
| 1 — Navigation | 8 | 8 | 0 | 100% |
| 2 — Shared UI | 12 | 5 | 7 | 42% (high-priority done) |
| 3 — Privacy | 12 | 8 | 4 | 67% |

**Deferred items rationale:**
- S2-011: Requires Compose test infrastructure setup
- S2-012: Trivial docs numbering fix
- S3-005/006/007: Require deeper ViewModel refactors
- S3-011: Documentation-only fix

---

## Slice 4 — Home/Dashboard

| Issue | Description | Status | Commit |
|-------|-------------|--------|--------|
| S4-001 | HomeScreen monolithic composable | ⏭️ Deferred (large extraction) | — |
| S4-002 | HomeViewModel too many responsibilities | ⏭️ Deferred (large extraction) | — |
| S4-003 | Widget render else branch (silent failures) | ⏭️ Deferred (risky without full test coverage) | — |
| S4-004 | Widget ID/config/style mapping drift | ⏭️ Deferred | — |
| S4-005 | SafeToSpend no-budget misleading | ✅ Fixed | `c0148aac` |
| S4-006 | Calendar.getInstance in composable | ✅ Acceptable (computes from widget dateMs) | — |
| S4-007 | Currency reactivity bug (category trends) | ✅ Fixed | `c0148aac` |
| S4-008 | Cross-widget financial invariants not enforced | ⏭️ Deferred (needs fixture) | — |
| S4-009 | Totals drill-down state machine fragile | ⏭️ Deferred | — |
| S4-010 | Totals/category errors invisible | ⏭️ Deferred | — |
| S4-011 | Widget config mutations synchronous | ⏭️ Deferred | — |
| S4-012 | Widget edit overlay lacks boundary UX | ⏭️ Deferred | — |
| S4-013 | Unsafe Array casts in combine | ⏭️ Deferred | — |
| S4-014 | Recommendation navigation error handling | ⏭️ Deferred | — |

**Tests:** All 24 HomeViewModelRecommendationTest pass.

---

## Updated Summary

| Slice | Total Issues | Fixed | Deferred | Coverage |
|-------|-------------|-------|----------|----------|
| 1 — Navigation | 8 | 8 | 0 | 100% |
| 2 — Shared UI | 12 | 5 | 7 | 42% |
| 3 — Privacy | 12 | 8 | 4 | 67% |
| 4 — Dashboard | 14 | 2 | 12 | 14% (critical UX + currency fixed) |

---

## Slice 5 — Transactions + Manual Add

| Issue | Description | Status | Commit |
|-------|-------------|--------|--------|
| S5-001 | TransactionsScreen monolithic | ⏭️ Deferred (extraction) | — |
| S5-002 | Initial filter can leave stale filters | ⏭️ Deferred | — |
| S5-003 | Tab label disagrees with date-range filter | ⏭️ Deferred (UX) | — |
| S5-004 | Mixed-currency list totals incorrect | ⏭️ Deferred (needs ViewModel conversion) | — |
| S5-005 | homeCurrency placeholder EUR can leak into saves | ✅ Fixed | `3943ad10` |
| S5-006 | Amount input sanitizer weak | ⏭️ Deferred (needs shared sanitizer) | — |
| S5-007 | Save not idempotency-safe (double-tap) | ✅ Fixed | `3943ad10` |
| S5-008 | Merchant suggestion no failure handling | ⏭️ Deferred | — |
| S5-009 | Suggestion amount formatting locale-sensitive | ⏭️ Deferred | — |
| S5-010 | Transfer type update not atomic | ⏭️ Deferred (needs new repo method) | — |
| S5-011 | Ownership/shared validation inconsistent | ⏭️ Deferred | — |
| S5-012 | Mutation dialogs close before persistence result | ⏭️ Deferred | — |

**Production fixes:** EUR placeholder save blocked, double-tap guard added.

---

## Updated Summary

| Slice | Total Issues | Fixed | Deferred | Coverage |
|-------|-------------|-------|----------|----------|
| 1 — Navigation | 8 | 8 | 0 | 100% |
| 2 — Shared UI | 12 | 10 | 2 | 83% |
| 3 — Privacy | 12 | 8 | 4 | 67% |
| 4 — Dashboard | 14 | 3 | 11 | 21% |
| 5 — Transactions | 12 | 2 | 10 | 17% (critical data fixes) |
