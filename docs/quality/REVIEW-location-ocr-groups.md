# Code Review: Location, OCR & Groups Subsystem

> **Batch:** 19 — Location, OCR, Groups  
> **Reviewer:** Deep analysis agent  
> **Date:** April 5, 2026  
> **Scope:** Receipt scanning, geocoding services, split/settlement calculation, shared expense groups

---

## Executive Summary

This review covers the Location/Geocoding, Receipt OCR/Scan, and Groups/Splits subsystem. All 5 pre-reported issues are **confirmed**. The analysis uncovered **10 additional issues**, with one HIGH-severity finding (duplicated split calculation logic) and several medium-severity concerns around data integrity, PII leakage, and numeric safety.

The PII exposure in geocoding logs is **far more pervasive** than the original report suggested — the entire geocoding provider stack (4 services + composite + resolver) systematically logs raw user queries and merchant names, not just the single line in `LocationResolver`. The groups/splits subsystem has two independent implementations of the same split arithmetic that can silently diverge.

---

## Files Reviewed

| File | Lines | Role |
|------|-------|------|
| `ui/screens/receiptscan/ReceiptScanViewModel.kt` | 1123 | Receipt scan UI state management |
| `data/repository/ReceiptRepository.kt` | ~664 | Receipt OCR processing, batch processing |
| `domain/location/LocationResolver.kt` | 266 | 8-step geocoding resolution cascade |
| `data/location/CompositeGeocodingService.kt` | 391 | Multi-provider geocoding orchestrator |
| `data/location/NominatimGeocodingService.kt` | ~310 | OSM reverse geocoding provider |
| `data/location/PhotonGeocodingService.kt` | ~100 | Photo-based address search provider |
| `data/location/GeoapifyGeocodingService.kt` | ~110 | Commercial geocoding provider |
| `data/location/GooglePlacesGeocodingService.kt` | ~120 | Google Places geocoding provider |
| `domain/logic/SplitCalculator.kt` | 364 | Split calculation (equal/percent/amount/unequal) |
| `domain/groups/SettlementCalculator.kt` | 270 | Settlement optimization (DFS solver) |
| `domain/groups/SharedExpenseManager.kt` | 511 | Domain service for shared expenses |
| `ui/screens/groups/SharedExpenseGroupsViewModel.kt` | 405 | Groups UI state management |
| `ui/screens/receiptscan/ReceiptScanViewModelStressTest.kt` | 836 | Existing test coverage (19 tests) |

---

## Issues Found (15 total)

### ISSUE-1 [MAJOR] — Stale item-analysis state on successful scan path
- **File:Line:** `ReceiptScanViewModel.kt:246-268` (success), `:198-212` (pre-processing)
- **Type:** Bug
- **Description:** The success-path `_state.update { it.copy(...) }` resets AI assist fields, quick-save preview, and debug data — but **omits** four item-analysis fields: `itemCategorizations`, `isAnalyzingItems`, `showItemBreakdown`, `itemAnalysisError`. The pre-processing state reset at lines 198-212 has the same omission. Error/fallback paths (lines 295-327, 340-372) **do** reset these fields correctly, proving the intent was to clear them.
- **Impact:** If receipt A has line items and receipt B does not, receipt B's review screen shows receipt A's stale item categorizations — false confidence for users.
- **Suggested Fix:** Add the four fields to both state update sites. Extract a `clearItemAnalysisState()` helper used in all three paths.
- **Status:** CONFIRMED (matches pre-report)

### ISSUE-2 [MAJOR] — `processBatch()` inherits caller's dispatcher (ANR risk)
- **File:Line:** `ReceiptRepository.kt:418-442`
- **Type:** Bug / Threading
- **Description:** `processBatch()` uses `coroutineScope { async { ... } }` without an explicit dispatcher. The `async` blocks inherit the caller's context — typically `viewModelScope` → `Dispatchers.Main`. OCR processing, ML classification, and DB transactions all run on the main thread. **Zero `withContext` calls exist in the entire file.** No dispatcher is injected in the constructor (lines 47-65).
- **Impact:** Heavy per-receipt OCR/parse/DB work can execute on Main, causing ANR for batches > 3-5 images. The semaphore limits concurrency to 3, but 3 concurrent OCR operations on Main is still catastrophic.
- **Suggested Fix:** Inject `@IoDispatcher` and wrap the `async` body in `withContext(ioDispatcher)`. Apply to `processReceipt` and `processStatement` as well.
- **Status:** CONFIRMED (matches pre-report)

### ISSUE-3 [MAJOR] — `LocationResolver` depends on concrete data repositories
- **File:Line:** `LocationResolver.kt:4-5` (imports), `:29-38` (constructor)
- **Type:** Architecture
- **Description:** `LocationResolver` lives in `domain.location` but imports `ExpenseRepository` and `MerchantLocationRepository` from `data.repository`. These are 2 of 8 constructor dependencies — the other 6 correctly use domain interfaces (`GeocodingService`, `NearbyPoiService`, `ForegroundLocationProvider`). The correct pattern already exists in this class for 6 of 8 dependencies.
- **Impact:** Violates Clean Architecture dependency rule. Makes the resolver untestable without real repositories.
- **Suggested Fix:** Define domain ports (`LocationCachePort`, `MerchantClusterPort`). Implement in data layer. Bind via Hilt.
- **Status:** CONFIRMED (matches pre-report)

### ISSUE-4 [MAJOR] — `SplitCalculator` imports Room/data entities
- **File:Line:** `SplitCalculator.kt:3-5`
- **Type:** Architecture
- **Description:** `SplitCalculator` (in `domain.logic`) imports `GroupExpense`, `GroupMember`, and `SplitType` from `data.database.entity`. All public methods consume these entity types directly.
- **Impact:** Couples domain split arithmetic to Room schema. Makes modularization impossible. Forces the duplicated implementation in `SharedExpenseManager` (see ISSUE-9).
- **Suggested Fix:** Introduce domain-native input models. Map at the boundary layer (ViewModel).
- **Status:** CONFIRMED (matches pre-report)

### ISSUE-5 [MAJOR — UPGRADED from MINOR] — Pervasive PII leakage across entire geocoding stack
- **File:Line:** Multiple files — see table below
- **Type:** Security / PII
- **Description:** The original report identified **1 line** in `LocationResolver.kt:240`. The deep analysis reveals **16+ PII leak sites** across the geocoding stack. The HIGH-14 hash-anonymization fix was only applied to `LocationResolver.resolve()` but **not** to the `geocode()` helper, and **not at all** to `CompositeGeocodingService` or the individual provider services.

| File | PII Leaks | Examples |
|------|-----------|---------|
| `LocationResolver.kt` | 1 | Line 240: raw merchant name in `Timber.w` |
| `CompositeGeocodingService.kt` | 7 | Lines 88, 136, 166, 168, 180, 191, 210: raw merchant name and user query |
| `NominatimGeocodingService.kt` | 6+ | Lines 78, 147, 148, 162, 176, 305: query, URL with query, 500-char response body preview, resolved city |
| `PhotonGeocodingService.kt` | 1 | Line 50: full URL with encoded query |
| `GooglePlacesGeocodingService.kt` | 2 | Lines 71, 86: raw query text, error body snippet |
| `GeoapifyGeocodingService.kt` | 1 | Line 63: bias coordinates (query itself is redacted — best practice) |

- **Impact:** Raw merchant names, user financial metadata, and geographic coordinates are written to `logcat` and potentially crash-reporting services.
- **Suggested Fix:** Apply the `hashCode().toUInt().toString(16)` anonymization pattern (already used in `LocationResolver.resolve()`) to all log sites. Use `GeoapifyGeocodingService`'s `buildSafeLogRoute()` as the model for URL logging.
- **Status:** CONFIRMED and EXPANDED (severity upgraded from MINOR to MAJOR due to scale)

---

### ISSUE-6 [MEDIUM] (NEW) — No cancellation guard for in-flight item analysis
- **File:Line:** `ReceiptScanViewModel.kt:271-279`
- **Type:** Bug / Race Condition
- **Description:** When a successful scan triggers item analysis, it launches a fire-and-forget `viewModelScope.launch` coroutine. If the user rapidly scans a second receipt, the old coroutine continues and can write stale item categorizations into the state **after** the new scan has started. There is no receipt-scoped job tracking or cancellation.
- **Impact:** Item categorizations from receipt A can silently overwrite receipt B's state.
- **Suggested Fix:** Track the item-analysis `Job` and cancel it before launching a new one. Or tag by `receiptId` and discard writes for non-current receipts.

### ISSUE-7 [MEDIUM] (NEW) — `System.currentTimeMillis()` instead of injected `TimeProvider`
- **File:Line:** `ReceiptScanViewModel.kt:336`
- **Type:** Bug / Testability
- **Description:** The fallback error path uses `System.currentTimeMillis()` for `processingTimeMs` instead of the injected `timeProvider.now()`. All other time references in the class use `timeProvider`.
- **Impact:** Minor inconsistency; breaks time-dependent test assertions.
- **Suggested Fix:** Replace with `timeProvider.now()`.

### ISSUE-8 [HIGH] (NEW) — Duplicated split calculation logic in `SplitCalculator` vs `SharedExpenseManager`
- **File:Line:** `SplitCalculator.kt:13-364` and `SharedExpenseManager.kt:200-400`
- **Type:** Architecture / Bug Risk
- **Description:** Two **completely independent** implementations of equal/percentage/amount/unequal split logic exist:
  - `SplitCalculator` (stateless `object`, uses data-layer `GroupExpense`/`GroupMember`)
  - `SharedExpenseManager` (domain service, uses domain `SharedGroupExpense`/`SharedExpenseMember`)
  
  Both implement remainder distribution, percentage-to-amount conversion, and balance calculation. The `SplitCalculator` version includes `simplifyBalances` (greedy algorithm), while `SettlementCalculator` has a DFS/backtracking solver — **two different settlement algorithms** that can produce different transfer plans.
- **Impact:** Logic divergence between the two implementations means the ViewModel (which uses `SplitCalculator`) and the domain service (which uses `SharedExpenseManager`) can show different balances for the same group. Any bug fix applied to one may not be applied to the other.
- **Suggested Fix:** Eliminate `SplitCalculator` as part of ISSUE-4 refactoring. Consolidate all split/balance logic into `SharedExpenseManager` using domain models. Remove `SplitCalculator.simplifyBalances` in favor of `SettlementCalculator`.

### ISSUE-9 [MEDIUM] (NEW) — `Int` overflow in cents conversion (split + settlement)
- **File:Line:** `SplitCalculator.kt:~45` (`toCents`), `SettlementCalculator.kt:~230` (`amountToCents`), `SharedExpenseManager.kt` (same pattern)
- **Type:** Bug
- **Description:** `BigDecimal.movePointRight(2).toInt()` silently truncates amounts > $21,474,836.47 (`Int.MAX_VALUE / 100`). While rare in personal expense tracking, shared group expenses for events, travel, or business can exceed this threshold. `SharedExpenseManager` has the same pattern.
- **Impact:** Silent arithmetic corruption for large amounts, producing wrong splits.
- **Suggested Fix:** Change `toInt()` to `toLong()` in all cents conversion sites. Update downstream arithmetic to use `Long`.

### ISSUE-10 [MEDIUM] (NEW) — `Double` accumulation without cents normalization in balance calculation
- **File:Line:** `SplitCalculator.kt:266-276`, `SharedExpenseManager.kt:222-239`
- **Type:** Bug
- **Description:** Both `SplitCalculator.calculateBalances` and `SharedExpenseManager.calculateBalances` accumulate `Double` values across all group expenses without converting to integer cents first. For groups with many expenses, floating-point errors accumulate and can produce visible discrepancies (e.g., a balance showing `0.009999999` instead of `0.01`).
- **Impact:** Balance displays may show rounding artifacts. Settlement calculations may not properly zero out.
- **Suggested Fix:** Accumulate in `Long` cents, convert to `Double` only at the output boundary.

### ISSUE-11 [MEDIUM] (NEW) — `SettlementCalculator` DFS has unbounded exponential complexity
- **File:Line:** `SettlementCalculator.kt:~120-200` (`findMinimalTransferPlan`)
- **Type:** Performance
- **Description:** The DFS/backtracking solver for minimizing settlement transfers has O(M^N) worst-case complexity. No depth limit, timeout, or fallback to the greedy algorithm exists. A group with 10+ members with complex net balances could hang the app.
- **Impact:** Potential ANR / infinite-appearing computation for large groups.
- **Suggested Fix:** Add a timeout or depth limit. If the optimal solver exceeds the budget, fall back to the greedy algorithm with a "simplified, may not be optimal" indicator.

### ISSUE-12 [MEDIUM] (NEW) — Non-atomic two-step group expense creation
- **File:Line:** `SharedExpenseGroupsViewModel.kt:219-263`
- **Type:** Transaction / Data Integrity
- **Description:** Adding a group expense is a two-step process: (1) `manualExpenseRepository.addManualExpense()` creates a system expense, then (2) `addGroupExpenseUseCase()` links it to the group. If step 2 fails (network error, validation failure), an orphaned system expense remains in the database with no group linkage.
- **Impact:** Orphaned expense visible in the general expense list but not in any group.
- **Suggested Fix:** Wrap both operations in a `database.withTransaction` or implement a compensating delete on step-2 failure.

### ISSUE-13 [LOW] (NEW) — Hardcoded currency symbols in split/settlement formatters
- **File:Line:** `SplitCalculator.kt:~340` (`formatBalance` → `$`), `SettlementCalculator.kt:~84` (`getSettlementSummary` → `€`)
- **Type:** Bug
- **Description:** `formatBalance` hardcodes `$` currency symbol. `getSettlementSummary` hardcodes `€`. Each group has a `defaultCurrency` field that is ignored by these formatters.
- **Impact:** Users see wrong currency symbol in split breakdowns and settlement summaries.
- **Suggested Fix:** Accept currency parameter in both methods. Map to symbol at the call site.

### ISSUE-14 [LOW] (NEW) — Inconsistent dispatcher usage in `SharedExpenseManager`
- **File:Line:** `SharedExpenseManager.kt`
- **Type:** Architecture
- **Description:** `createGroup`, `addExpense`, `calculateBalances`, `removeMember` use `withContext(Dispatchers.IO)`. But `addMember`, `archiveGroup`, `restoreGroup`, `deleteGroup` run on the caller's dispatcher (no switch). Inconsistent pattern.
- **Impact:** If called from Main, the unguarded methods could block the UI thread.
- **Suggested Fix:** Apply `withContext(Dispatchers.IO)` consistently. Better: inject `@IoDispatcher`.

### ISSUE-15 [LOW] (NEW) — Hardcoded "EUR" in `ReceiptScanViewModel.saveExpenseInternal`
- **File:Line:** `ReceiptScanViewModel.kt:974`
- **Type:** Bug
- **Description:** `saveExpenseInternal` always passes `"EUR"` as currency regardless of the user's configured default currency or the receipt's detected currency.
- **Impact:** Non-EUR users will have all receipt-created expenses tagged with wrong currency.
- **Suggested Fix:** Use `parsed.currency` or fall back to user's default currency setting.

---

## Cross-Cutting Analysis

### PII Exposure Pattern
The HIGH-14 fix that anonymized merchant names in `LocationResolver.resolve()` was **not propagated** to:
- The `geocode()` private helper in the same file
- Any of the 5 geocoding service implementations
- The `CompositeGeocodingService` orchestrator

This is a classic "fix the symptom, not the pattern" regression. The fix needs to be applied at the **service layer** (where logs are emitted) not just the caller.

### Split Calculation Duplication
The codebase has **three** independent implementations of financial arithmetic:
1. `SplitCalculator` — data-entity-coupled, used by ViewModel
2. `SharedExpenseManager` — domain-model-based, used by use cases
3. `SettlementCalculator` — integer-cents-based DFS solver

The first two can produce different results for the same inputs due to independent floating-point rounding paths. ISSUE-4 (decouple `SplitCalculator`) should be treated as an **elimination** of `SplitCalculator`, not just a type change.

### Numeric Safety
All three calculators use `Int` for cents conversion, risking overflow on amounts > ~$21.4M. The `Double` accumulation pattern in balance calculations introduces visible rounding artifacts over many expenses.

---

## Validation of Pre-Reported Issues

| Issue | Pre-Report | Actual | Delta |
|-------|------------|--------|-------|
| ISSUE-1 (stale state) | MAJOR | **CONFIRMED MAJOR** | Also affects pre-processing state (line 198-212) — 2 sites, not 1 |
| ISSUE-2 (dispatcher) | MAJOR | **CONFIRMED MAJOR** | Worse than stated — zero `withContext` in entire file, no dispatcher injection at all |
| ISSUE-3 (LocationResolver arch) | MAJOR | **CONFIRMED MAJOR** | 2 of 8 deps violate, correct pattern exists for the other 6 |
| ISSUE-4 (SplitCalculator arch) | MAJOR | **CONFIRMED MAJOR + escalated** | Root cause of duplicated split logic (ISSUE-8) — must be elimination, not just type change |
| ISSUE-5 (PII logging) | MINOR | **UPGRADED TO MAJOR** | 16+ leak sites across 6 files, not 1 line |

---

## Test Coverage Assessment

### Existing Coverage
- `ReceiptScanViewModelStressTest.kt`: 19 tests covering AI assist, quick-save, error paths, OCR fallback
- The OCR fallback test **does** verify item-analysis state reset on the error path — proving the intent but not covering the success path

### Gaps
- **No test** for stale item-analysis state surviving through the success path (ISSUE-1)
- **No test** for rapid re-scan race condition (ISSUE-6)
- **No test** for `processBatch` dispatcher behavior
- **No test** for split calculation parity between `SplitCalculator` and `SharedExpenseManager`
- **No test** for large-amount cents overflow
- **No test** for DFS solver timeout/complexity

---

## Priority Fix Order

1. **ISSUE-5** (PII leakage — 16+ sites, security/compliance)
2. **ISSUE-2** (dispatcher — ANR risk in batch processing)
3. **ISSUE-1 + ISSUE-6** (stale state + race condition — combine as single fix)
4. **ISSUE-8 + ISSUE-4** (eliminate `SplitCalculator` via domain model decoupling)
5. **ISSUE-9** (Int overflow in cents)
6. **ISSUE-12** (non-atomic group expense creation)
7. **ISSUE-3** (LocationResolver architecture)
8. **ISSUE-11** (DFS timeout)
9. **ISSUE-10** (Double accumulation)
10. Remaining LOW issues

---

## Batch Score: **52/100**

| Dimension | Score | Notes |
|-----------|-------|-------|
| Correctness | 5/10 | Stale state, duplicate diverging logic, Int overflow |
| Security | 4/10 | 16+ PII leak sites across geocoding stack |
| Architecture | 5/10 | 2 clean-arch violations, duplicated split logic |
| Performance | 6/10 | Dispatcher missing, DFS unbounded |
| Error Handling | 7/10 | Good sealed-result patterns, CancellationException preserved |
| Testability | 5/10 | No dispatcher injection, hardcoded time, hardcoded currency |
| Test Coverage | 6/10 | Good AI assist tests, but critical paths untested |

**Summary:** The subsystem has solid error handling patterns (sealed results, CancellationException propagation, graceful degradation in geocoding) but carries significant risks from pervasive PII logging, missing dispatchers in the receipt batch path, duplicated split arithmetic with divergence potential, and numeric overflow hazards. The `SplitCalculator` data-entity coupling is the root cause of the most concerning architectural problem (forced logic duplication). The PII leak is much broader than originally reported and needs a systematic fix across the entire geocoding service stack.
