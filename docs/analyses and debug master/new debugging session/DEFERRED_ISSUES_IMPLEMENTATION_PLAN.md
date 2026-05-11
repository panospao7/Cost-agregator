# Implementation Plan — 9 Deferred Architectural Issues

> **Generated:** 2026-05-11  
> **Status:** Ready for implementation  
> **Total effort:** ~30-40 hours across 9 issues

---

## Recommended Implementation Order

| # | Issue | Effort | Dependencies | Priority |
|---|-------|--------|-------------|----------|
| 1 | P8-P1-02: PrivacySettings/AiSettings unification | S | None | HIGH (security) |
| 2 | P5-P1-05: Dashboard spending trend normalization | S | None | HIGH (user-visible) |
| 3 | P8-P1-06: Retention target registry | M | None | HIGH (privacy) |
| 4 | P6-P1-13: AccountBalanceProvider | M | None | MEDIUM |
| 5 | P8-P1-08: Purpose-aware redaction | M | None | MEDIUM |
| 6 | P5-P1-08: Budget-vs-actual engine wiring | M | None | MEDIUM |
| 7 | P8-P1-10: Geocoding gate enforcement | M | None | MEDIUM |
| 8 | P8-P1-12: Privacy-denied UI states | L | #1 | LOW |
| 9 | P7-P1-05: Restore semantic equivalence tests | L | None | LOW |

---

## Issue 1: P8-P1-02 — PrivacySettings/AiSettings Unification [S]

**Problem:** `CloudAiPrivacyGate` reads only `PrivacySettings.cloudAiEnabled`. `AiSettings.allowCloudAi` is separate. They can disagree.

**Fix:** Wire `EffectiveCloudAiPolicyResolver` (already exists) into `CloudAiPrivacyGate`.

**Files:** `domain/privacy/CloudAiPrivacyGate.kt`

**Steps:**
1. Add `EffectiveCloudAiPolicyResolver` as constructor dependency
2. Replace `settingsRepository.getSettings()` with `policyResolver.resolve()`
3. Replace `!settings.cloudAiEnabled` → `!policy.cloudAllowed`
4. Replace `!settings.receiptImageCloudEnabled` → `!policy.receiptImageUploadAllowed`
5. Replace `!settings.bankStatementAiEnabled` → `!policy.bankStatementCloudAllowed`
6. Replace `settings.redactBeforeCloud` → `policy.redactBeforeCloud`
7. Remove direct `settingsRepository` dependency

**Risk:** Very low — resolver already implements correct logic.

---

## Issue 2: P5-P1-05 — Dashboard Spending Trend Normalization [S]

**Problem:** `computeSpendingTrend()` sums `effectiveAmount` across currencies.

**Fix:** Inject `AnalyticsCurrencyNormalizer`, normalize expenses before grouping.

**Files:** `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`

**Steps:**
1. Add `AnalyticsCurrencyNormalizer` constructor param
2. Change `computeSpendingTrend` to `suspend`
3. At top: normalize purchase list via `normalizer.normalizeSnapshots(purchases, homeCurrency)`
4. Use normalized amounts in `daily[dayIdx] += normalizedAmount`
5. Handle excluded expenses (skip with 0.0)

**Risk:** Low — normalizer is battle-tested. Performance: 6 months of expenses need conversion.

---

## Issue 3: P8-P1-06 — Retention Target Registry [M]

**Problem:** `DataRetentionWorker` only purges notifications + OCR. Misses AI/email/debug.

**Fix:** Create `RetentionTarget` interface + registry with Hilt multibinding.

**Files to create:** `data/privacy/RetentionTarget.kt`  
**Files to modify:** `DataRetentionWorker.kt`, `PrivacySettings.kt`, `AiArtifactDao.kt`, `AiChatMessageDao.kt`, `EmailReceiptDao.kt`

**Steps:**
1. Define `RetentionTarget` interface: `suspend fun purge(cutoff: Long, now: Long): Int`
2. Add retention day fields to `PrivacySettings` (aiArtifact=90, aiChat=60, email=30, debug=7)
3. Add `purgeOlderThan(cutoff)` queries to AI/email DAOs
4. Create concrete targets: `AiArtifactRetentionTarget`, `AiChatRetentionTarget`, `EmailReceiptRetentionTarget`
5. Register via Hilt `@IntoSet` multibinding
6. Refactor worker to iterate `Set<RetentionTarget>`
7. Write audit event per target

**Risk:** Low — additive. Existing purge logic continues working.

---

## Issue 4: P6-P1-13 — AccountBalanceProvider [M]

**Problem:** Stress forecast uses 90-day net cashflow, not real balance.

**Fix:** Create provider interface with chain: Bank → Manual → NetCashflow fallback.

**Files to create:** `domain/forecasting/AccountBalanceProvider.kt`, `NetCashflowBalanceProvider.kt`, `ManualBalanceProvider.kt`  
**Files to modify:** `FinancialStressForecastEngine.kt`, DI module

**Steps:**
1. Define `AccountBalanceProvider` interface: `suspend fun currentBalance(currency: String): AccountBalanceResult?`
2. Extract current logic into `NetCashflowBalanceProvider`
3. Create `ManualBalanceProvider` reading from `CurrencySettingsRepository` (new pref key)
4. Create `AccountBalanceProviderChain` trying providers in priority order
5. Inject chain into engine, replace `resolveStartingBalanceBaseline()`
6. Set `StressForecastResult.mode` based on which provider succeeded

**Risk:** Low — fallback chain ensures no regression.

---

## Issue 5: P8-P1-08 — Purpose-Aware Redaction [M]

**Problem:** `DefaultCloudPayloadRedactor` applies same rules regardless of `CloudPayloadPurpose`.

**Fix:** Create per-purpose rule sets controlling which fields are preserved/redacted.

**Files to create:** `data/privacy/PurposeRedactionRules.kt`  
**Files to modify:** `DefaultCloudPayloadRedactor.kt`, `CloudPiiSanitizer.kt`

**Steps:**
1. Define `RedactionRuleSet` data class (redactEmails, redactPhones, redactAmounts, redactMerchants, etc.)
2. Create `PurposeRedactionRules.forPurpose(purpose)` mapping
3. Add `sanitizeText(raw, maxChars, rules: RedactionRuleSet)` overload to `CloudPiiSanitizer`
4. Update `DefaultCloudPayloadRedactor.redactText()` to look up rules by purpose
5. Test each purpose preserves expected fields

**Risk:** Medium — loosening redaction increases cloud exposure. Each rule needs privacy review.

---

## Issue 6: P5-P1-08 — Budget-vs-Actual Engine Wiring [M]

**Problem:** Dashboard budget statuses use raw BudgetRepository without normalized comparison.

**Fix:** Wire `BudgetVsActualEngine` into `DashboardContractsAdapter.observeBudgetStatuses()`.

**Files:** `data/repository/DashboardContractsAdapter.kt`

**Steps:**
1. Add `AnalyticsCurrencyNormalizer`, `BudgetVsActualEngine`, `ExpenseRepository` as constructor deps
2. In `observeBudgetStatuses()`, combine budget flow with month-expenses flow
3. Normalize expenses via normalizer
4. Call `BudgetVsActualEngine.compute(normalizedInput, budgetSnapshots, homeCurrency)`
5. Map results back to `BudgetStatusSnapshot`

**Risk:** Medium — reactive Flow combination needs careful handling to avoid infinite loops.

---

## Issue 7: P8-P1-10 — Geocoding Gate Enforcement [M]

**Problem:** Individual geocoding services can be injected directly, bypassing privacy gate.

**Fix:** Wrapper pattern + visibility restriction.

**Files to create:** `data/location/PrivacyAwareGeocodingService.kt`  
**Files to modify:** `di/ServiceModule.kt`, individual geocoding services

**Steps:**
1. Create `PrivacyAwareGeocodingService` wrapping `CompositeGeocodingService` with gate check
2. Bind `GeocodingService` interface to wrapper in DI
3. Mark individual services with `@Named` qualifiers (can't use `internal` in single module)
4. Add `GeocodingResult.PrivacyBlocked` variant
5. Write architecture test verifying no direct injection outside `data.location`

**Risk:** Medium — DI binding changes may break existing injection sites.

---

## Issue 8: P8-P1-12 — Privacy-Denied UI States [L]

**Problem:** Privacy denials show generic failures instead of clear "blocked by privacy" messages.

**Fix:** Create `PrivacyDeniedStateComputer` + `PrivacyBlockedBanner` composable + propagate through ViewModels.

**Files to create:** `domain/privacy/PrivacyDeniedStateComputer.kt`, `ui/components/PrivacyBlockedBanner.kt`  
**Files to modify:** `PrivacySettingsViewModel.kt`, `ReviewViewModel.kt`, `SpendingMapViewModel.kt`, `PrivacySettingsScreen.kt`

**Steps:**
1. Create `PrivacyDeniedStateComputer` computing blocked capabilities from settings
2. Add `deniedCapabilities` to `PrivacySettingsUiState`
3. Create `PrivacyBlockedBanner` composable
4. In feature ViewModels, map `PrivacyDecision.Denied` → UI state with `PrivacyBlocked`
5. Show banner in feature screens when privacy-denied
6. In PrivacySettingsScreen, show affected features per toggle

**Risk:** Low (additive UI). Medium scope (multiple screens).

---

## Issue 9: P7-P1-05 — Restore Semantic Equivalence Tests [L]

**Problem:** Restore verification only checks row counts, not semantic correctness.

**Fix:** Embed aggregate checksums in backup manifest, verify after restore.

**Files to create:** `data/backup/SemanticEquivalenceVerifier.kt`, `data/backup/BackupManifestSnapshot.kt`  
**Files to modify:** `BackupVerifier.kt`, backup creation code

**Steps:**
1. Define `BackupManifestSnapshot` (category totals, monthly totals, link counts, etc.)
2. At backup creation, compute and embed snapshot in manifest JSON
3. Create `SemanticEquivalenceVerifier` that re-queries restored DB and compares
4. Wire into `BackupVerifier` as "Tier 0 SEMANTIC" pass
5. Allow ±0.01 tolerance for floating-point
6. Skip gracefully for legacy backups without snapshot
7. Write test fixture with known DB + manifest

**Risk:** Medium — older backups won't have snapshot (must be optional). Performance on large DBs.

---

## Summary

| Effort | Count | Issues |
|--------|-------|--------|
| S (2-3h) | 2 | #1, #2 |
| M (3-5h) | 5 | #3, #4, #5, #6, #7 |
| L (6-10h) | 2 | #8, #9 |
| **Total** | **9** | **~35 hours** |

All issues are independent (except #8 depends on #1 for full effect). Can be implemented in any order.
