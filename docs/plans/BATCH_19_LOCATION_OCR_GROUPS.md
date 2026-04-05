# Batch 19: Location, OCR, Groups fixes (10 issues)

> **Last updated:** April 5, 2026 — expanded after deep code review  
> **Review reference:** `docs/quality/REVIEW-location-ocr-groups.md` (score: 52/100)

## Technical Plan (Advanced)

### Scope
- In:
  - **ISSUE-1 [MAJOR]**: Successful scan path does not reset item-analysis state in `ReceiptScanViewModel` (stale UI data leak). Also affects pre-processing state reset.
  - **ISSUE-2 [MAJOR]**: `ReceiptRepository.processBatch()` launches async work without explicit IO dispatcher, risking main-thread pressure. No dispatcher injected in constructor at all.
  - **ISSUE-3 [MAJOR]**: `LocationResolver` depends on concrete data-layer repositories (`ExpenseRepository`, `MerchantLocationRepository`).
  - **ISSUE-4 [MAJOR]**: `SplitCalculator` imports Room/data entities, coupling domain logic to persistence types. Root cause of duplicated split logic.
  - **ISSUE-5 [MAJOR — UPGRADED]**: Pervasive PII leakage across **entire geocoding stack** (16+ sites in 6 files, not 1 line).
  - **ISSUE-6 [MEDIUM] (NEW)**: No cancellation guard for in-flight item analysis — race condition when user scans rapidly.
  - **ISSUE-7 [HIGH] (NEW)**: Duplicated split calculation logic in `SplitCalculator` and `SharedExpenseManager` — can produce divergent results. `SplitCalculator.simplifyBalances` (greedy) vs `SettlementCalculator` (DFS) — two settlement algorithms.
  - **ISSUE-8 [MEDIUM] (NEW)**: `Int` overflow in cents conversion across `SplitCalculator`, `SettlementCalculator`, `SharedExpenseManager` — silently corrupts amounts > ~$21.4M.
  - **ISSUE-9 [MEDIUM] (NEW)**: `SettlementCalculator` DFS solver has unbounded exponential complexity — can hang for groups with 10+ members.
  - **ISSUE-10 [MEDIUM] (NEW)**: Non-atomic two-step group expense creation in `SharedExpenseGroupsViewModel` — orphans system expense on step-2 failure.
  - Focused unit/stress test updates to lock fixes.
- Out:
  - DB schema/migration changes.
  - New product features in map/OCR/groups.
  - Broad cleanup of all existing domain→data couplings outside these issues.
  - UI redesign.
  - Currency symbol hardcoding (`$`/`€` in formatters) — tracked as LOW, separate batch.
  - `Double` accumulation in balance calculations — tracked under ISSUE-8 scope.
  - `SharedExpenseManager` inconsistent dispatcher usage — tracked separately.
  - Hardcoded `"EUR"` in `ReceiptScanViewModel.saveExpenseInternal` — tracked separately.

### Complexity Assessment
- Estimated files touched: **18-24**
- Risk level: **high** (threading + architecture boundaries + shared group calculation paths + security/PII)
- Cross-module impact: **yes** (UI, domain, data, DI, tests)

---

### Batch Plan

1. Batch name: **ISSUE-5 — Systematic PII-safe logging across geocoding stack**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/domain/location/LocationResolver.kt` (1 leak)
     - `app/src/main/java/com/yourname/expensetracker/data/location/CompositeGeocodingService.kt` (7 leaks)
     - `app/src/main/java/com/yourname/expensetracker/data/location/NominatimGeocodingService.kt` (6+ leaks)
     - `app/src/main/java/com/yourname/expensetracker/data/location/PhotonGeocodingService.kt` (1 leak)
     - `app/src/main/java/com/yourname/expensetracker/data/location/GooglePlacesGeocodingService.kt` (2 leaks)
     - `app/src/main/java/com/yourname/expensetracker/data/location/GeoapifyGeocodingService.kt` (1 leak — bias coordinates)
   - objective:
     - Eliminate all raw merchant/query/address PII from log output across the entire geocoding pipeline.
   - root cause analysis:
     - The HIGH-14 fix anonymized logs in `LocationResolver.resolve()` but was **not propagated** to:
       - The `geocode()` private helper in the same file (line 240)
       - `CompositeGeocodingService` (7 sites: lines 88, 136, 166, 168, 180, 191, 210)
       - `NominatimGeocodingService` (lines 78, 147, 148, 162, 176 [500-char body preview], 305)
       - `PhotonGeocodingService` (line 50 — full URL with query)
       - `GooglePlacesGeocodingService` (lines 71, 86 — query + error body)
       - `GeoapifyGeocodingService` (line 63 — bias coordinates; query itself is already redacted)
     - `GeoapifyGeocodingService.buildSafeLogRoute()` is the **correct pattern** — query length only, no raw text. Was not adopted by other services.
   - implementation strategy:
     1. Extract a shared `LocationLogUtils` or extension function for anonymized query logging (`query.hashCode().toUInt().toString(16)`) — reuse the pattern from `LocationResolver.resolve()`.
     2. Apply to all 16+ log sites across 6 files.
     3. For URL logging, strip query parameters or use `buildSafeLogRoute()` pattern (log path + param lengths only).
     4. For `NominatimGeocodingService` line 176: remove the 500-char body preview entirely, or redact `display_name` fields.
     5. Verify no remaining raw-text interpolation via grep: `"$query"`, `"$name"`, `"$merchantName"`, `"$queryForLog"`.
   - dependencies:
     - Must be applied **before** ISSUE-3 (LocationResolver refactor) to avoid merge churn.
   - risks:
     - Reduced debug observability. Mitigate by keeping hash token + provider name + result count.
   - validation:
     - Grep audit: no raw `$query`, `$name`, `$merchantName`, `$queryForLog` in log statements.
     - Manual: trigger geocoding failure and inspect logcat for anonymized output.
   - estimated effort: **Medium** (mechanical but touches 6 files)

---

2. Batch name: **ISSUE-1 + ISSUE-6 — Receipt scan state reset + item analysis race guard**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt`
     - `app/src/test/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModelStressTest.kt`
   - objective:
     - Ensure every new scan starts with clean item-analysis state AND cancel in-flight analysis from previous scans.
   - root cause analysis:
     - **ISSUE-1:** Success path (lines 246-268) and pre-processing path (lines 198-212) omit `itemCategorizations`, `isAnalyzingItems`, `showItemBreakdown`, `itemAnalysisError` from state reset. Error paths (lines 295-327, 340-372) correctly reset them.
     - **ISSUE-6:** Line 273 launches a fire-and-forget `viewModelScope.launch` for `analyzeReceiptItems()` with no receipt-scoped job tracking. Rapid re-scan lets the old coroutine write stale results after the new scan starts.
   - implementation strategy:
     1. Extract `clearItemAnalysisFields()` helper. Apply in: (a) pre-processing reset, (b) success state, (c) existing error paths (already done — keep consistent).
     2. Track item-analysis `Job` as a class field. Cancel before launching new analysis.
     3. Add `receiptId`-based guard: inside `analyzeReceiptItems()`, check `_state.value.receiptId` matches the receipt being analyzed before writing results.
     4. Minor: replace `System.currentTimeMillis()` at line 336 with `timeProvider.now()`.
   - dependencies:
     - Independent.
   - risks:
     - Over-aggressive cancellation may suppress valid results for the correct receipt.
   - validation:
     - Unit: "successful scan clears stale item-analysis state" (new test).
     - Unit: "rapid re-scan cancels previous analysis" (new test).
     - Existing 19 stress tests remain green.
     - Manual: scan receipt A (with items) → immediately scan receipt B (no items) → confirm no stale breakdown.
   - estimated effort: **Medium**

---

3. Batch name: **ISSUE-2 — `processBatch()` and `processReceipt()` dispatcher injection**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt`
     - DI module (if constructor injection changes)
     - `app/src/test/java/com/yourname/expensetracker/data/repository/ReceiptRepositoryStressTest.kt`
   - objective:
     - Guarantee heavy per-item batch processing runs on IO dispatcher, not inherited main context.
   - root cause analysis:
     - `processBatch()` (lines 418-442) uses `async {}` inheriting caller dispatcher (Main via `viewModelScope`).
     - **Zero** `withContext` calls in the entire file (664 lines).
     - **Zero** dispatchers in the constructor (lines 47-65).
     - `processReceipt` and `processStatement` also run on inherited dispatcher.
   - implementation strategy:
     1. Add `@IoDispatcher private val ioDispatcher: CoroutineDispatcher` to constructor.
     2. In `processBatch()`, wrap `async` body: `async(ioDispatcher) { ... }`.
     3. In `processReceipt()` and `processStatement()`, add `withContext(ioDispatcher)` around the heavy work.
     4. Keep cancellation semantics intact (`CancellationException` rethrow).
     5. Preserve semaphore-based concurrency limit and progress callback behavior.
     6. Consider switching to `supervisorScope` in `processBatch()` so one failure doesn't cancel all siblings.
   - dependencies:
     - Independent. Depends on existing `DispatchersModule` qualifier (`@IoDispatcher`).
   - risks:
     - Changing dispatcher behavior can alter callback timing/order assumptions.
     - Constructor signature change requires DI module update.
   - validation:
     - Existing `ReceiptRepositoryStressTest` batch tests remain green.
     - Add/adjust test to ensure progress emits expected count under dispatcher change.
     - Manual: batch import >20 images while interacting with UI; verify no freeze.
   - estimated effort: **Medium**

---

4. Batch name: **ISSUE-7 + ISSUE-4 — Eliminate `SplitCalculator`, consolidate split logic in domain**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/domain/logic/SplitCalculator.kt` (eliminate or gut)
     - `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseManager.kt` (becomes single source of truth)
     - `app/src/main/java/com/yourname/expensetracker/domain/groups/SettlementCalculator.kt` (single settlement solver)
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModel.kt` (update to use domain service)
     - New domain models if needed (or reuse `SharedGroupExpense`, `SharedExpenseMember`)
     - Tests for split algorithm parity
   - objective:
     - Single source of truth for split/balance/settlement calculations using domain models only. Zero data-entity imports in domain.
   - root cause analysis:
     - `SplitCalculator` (domain.logic) depends on data entities → forced `SharedExpenseManager` to reimplement the same arithmetic with domain types → **two diverging implementations**.
     - `SplitCalculator.simplifyBalances` (greedy) and `SettlementCalculator.calculateSettlements` (DFS) are **two different settlement algorithms** producing potentially different results.
     - ViewModel calls `SplitCalculator` directly, bypassing the domain service layer.
   - implementation strategy:
     1. **Phase A:** Define domain-only input types for split calculation (or reuse `SharedGroupExpense`, `SharedExpenseMember` which are already domain types).
     2. **Phase B:** Remove `SplitCalculator` entirely. Move any unique logic (if any) into `SharedExpenseManager`.
     3. **Phase C:** Update `SharedExpenseGroupsViewModel` to call `SharedExpenseManager` for splits and `SettlementCalculator` for settlements.
     4. **Phase D:** Remove `SplitCalculator.simplifyBalances`. Use `SettlementCalculator` as the single settlement solver.
     5. Preserve rounding and fallback behavior exactly — cent-level deterministic logic must remain unchanged.
     6. Also fix the duplicate `SplitType.toCustomSplitMode()` extension (exists in both ViewModel and SplitCalculator).
   - dependencies:
     - Subsumes original ISSUE-4.
     - Should be sequenced after ISSUE-1/2/5 (functional bugfixes) to reduce release risk.
   - risks:
     - Rounding behavior regressions from consolidation.
     - ViewModel data flow changes (going through domain service instead of direct utility).
   - validation:
     - Exhaustive parity tests: equal/custom percent/custom amount/unequal splits.
     - Edge cases: 0-amount, 1 member, 100 members, amounts that produce cent remainders.
     - Regression checks in groups screen flows (balances + settlement list).
     - Grep: `import com.yourname.expensetracker.data.database.entity` in `domain/` → 0 results.
   - estimated effort: **High**

---

5. Batch name: **ISSUE-8 — Int→Long cents conversion across calculators**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/domain/logic/SplitCalculator.kt` (if still exists after ISSUE-7)
     - `app/src/main/java/com/yourname/expensetracker/domain/groups/SettlementCalculator.kt`
     - `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseManager.kt`
   - objective:
     - Prevent silent arithmetic overflow for amounts > ~$21.4M.
   - root cause analysis:
     - `BigDecimal.movePointRight(2).toInt()` used in `toCents`, `amountToCents`, and percentage-to-amount conversion. `Int.MAX_VALUE / 100 = $21,474,836.47`.
   - implementation strategy:
     1. Change `toInt()` to `toLong()` in all cents conversion sites.
     2. Update all downstream arithmetic to use `Long` instead of `Int`.
     3. Update data classes (`BalanceInCents`, `SettlementCents`, etc.) from `Int` to `Long`.
   - dependencies:
     - If ISSUE-7 eliminates `SplitCalculator`, only `SettlementCalculator` and `SharedExpenseManager` need fixing.
   - risks:
     - Low — mechanical type widening.
   - validation:
     - Unit test with amount = $25,000,000 → verify correct cents value.
   - estimated effort: **Low**

---

6. Batch name: **ISSUE-9 — SettlementCalculator DFS timeout / depth limit**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/domain/groups/SettlementCalculator.kt`
   - objective:
     - Prevent unbounded computation for large groups.
   - root cause analysis:
     - `findMinimalTransferPlan` uses DFS/backtracking with O(M^N) complexity. No iteration limit, timeout, or fallback.
   - implementation strategy:
     1. Add an iteration counter. If it exceeds a threshold (e.g., 100,000 iterations), abort DFS.
     2. Fall back to greedy algorithm (sort debtors/creditors, match greedily) which is O(N log N).
     3. Optionally tag the result as "approximate" when fallback is used.
   - dependencies:
     - Independent. If ISSUE-7 eliminates `SplitCalculator.simplifyBalances`, the greedy fallback can be derived from it.
   - risks:
     - Greedy algorithm may produce more transfers than optimal — acceptable tradeoff.
   - validation:
     - Unit test: 15 members with complex debts → verify result within 500ms.
     - Unit test: verify greedy fallback produces valid (all balances zeroed) result.
   - estimated effort: **Medium**

---

7. Batch name: **ISSUE-10 — Non-atomic group expense creation**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModel.kt`
   - objective:
     - Prevent orphaned system expenses when group linkage fails.
   - root cause analysis:
     - `addExpense()` (lines 219-263) creates a system expense via `manualExpenseRepository.addManualExpense()`, then links it to the group via `addGroupExpenseUseCase()`. Step-2 failure leaves an orphaned expense.
   - implementation strategy:
     1. **Option A (preferred):** Wrap both operations in a domain-level transaction use case that rolls back on failure.
     2. **Option B:** Add a compensating delete: if step 2 fails, delete the system expense created in step 1.
     3. Preserve the existing error state updates for user feedback.
   - dependencies:
     - Independent.
   - risks:
     - Transaction coordination across repositories may be complex.
     - Compensating delete adds complexity but is simpler to implement.
   - validation:
     - Unit test: mock step 2 failure → verify step 1 expense is not persisted (or is cleaned up).
   - estimated effort: **Medium**

---

8. Batch name: **ISSUE-3 — Decouple `LocationResolver` from concrete data repositories (ports/adapters)**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/domain/location/LocationResolver.kt`
     - `app/src/main/java/com/yourname/expensetracker/domain/location/*` (new contracts file for resolver data ports + models)
     - `app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt` (or adapter)
     - `app/src/main/java/com/yourname/expensetracker/data/repository/MerchantLocationRepository.kt` (or adapter)
     - `app/src/main/java/com/yourname/expensetracker/di/*` (new binds module for location ports)
     - `app/src/test/java/com/yourname/expensetracker/domain/location/LocationResolverTest.kt`
     - `app/src/test/java/com/yourname/expensetracker/domain/location/LocationResolverStressTest.kt`
   - objective:
     - Make `LocationResolver` depend on domain contracts, not data-layer concrete classes.
   - root cause analysis:
     - Resolver imports concrete repos directly (`LocationResolver.kt:4-5`) and receives them in constructor (`:29-38`).
     - 6 of 8 constructor dependencies already use domain interfaces correctly — the pattern exists.
   - implementation strategy:
     1. Define minimal domain-facing resolver ports:
        - `LocationCachePort`: cache lookup, correction lookup, save location, area key resolution
        - `MerchantClusterPort`: cluster lookup
     2. Define lightweight domain models for cache hit/correction/cluster.
     3. Refactor constructor and call sites to use ports.
     4. Provide data-layer implementations and bind with Hilt.
     5. Update tests to mock new ports.
     6. Keep behavior parity for resolution priority and cache/correction semantics.
   - dependencies:
     - Apply ISSUE-5 first (same file, reduces churn).
   - risks:
     - Contract under-specification can cause behavior drift.
     - DI misbinding can break runtime injection.
   - validation:
     - All existing location resolver tests pass after refactor.
     - Grep: `import com.yourname.expensetracker.data.` in `domain/location/` → 0 results.
     - Manual smoke: resolve marker in Map flow, run backfill worker.
   - estimated effort: **High**

---

### Additional Issues Tracked (not in this batch)

| ID | Severity | Type | Location | Tracking |
|----|----------|------|----------|----------|
| ISSUE-11 | LOW | Bug | `SplitCalculator.kt:~340`, `SettlementCalculator.kt:~84` — hardcoded `$`/`€` currency symbols | Future cosmetic fix |
| ISSUE-12 | LOW | Architecture | `SharedExpenseManager.kt` — inconsistent dispatcher usage (`addMember`, `archiveGroup`, `restoreGroup`, `deleteGroup` missing IO switch) | Future consistency pass |
| ISSUE-13 | LOW | Bug | `ReceiptScanViewModel.kt:974` — hardcoded `"EUR"` currency | Currency batch |
| ISSUE-14 | LOW | Bug | `ReceiptScanViewModel.kt:336` — `System.currentTimeMillis()` instead of `timeProvider.now()` | Folded into ISSUE-1 fix |
| ISSUE-15 | MEDIUM | Bug | `SplitCalculator.kt:266-276`, `SharedExpenseManager.kt:222-239` — `Double` accumulation without cents normalization | Folded into ISSUE-8 scope |

---

### Dependencies
- Recommended execution order (safe, low-conflict):
  1. **ISSUE-5** (PII logging — security/compliance, touches many files, do first) — P0
  2. **ISSUE-1 + ISSUE-6** (scan state reset + race guard) — P0
  3. **ISSUE-2** (dispatcher injection — ANR prevention) — P0
  4. **ISSUE-8** (Int→Long cents — quick numeric safety fix) — P1
  5. **ISSUE-9** (DFS timeout) — P1
  6. **ISSUE-10** (non-atomic expense creation) — P1
  7. **ISSUE-7 + ISSUE-4** (eliminate SplitCalculator, consolidate logic — largest refactor) — P1
  8. **ISSUE-3** (LocationResolver ports/adapters — after ISSUE-5) — P2
- Cross-issue notes:
  - ISSUE-5 and ISSUE-3 both edit `LocationResolver.kt`; ISSUE-5 first avoids merge churn.
  - ISSUE-7 subsumes ISSUE-4 — they should be one work item.
  - ISSUE-8 may be partially resolved by ISSUE-7 (if `SplitCalculator` is eliminated).
  - ISSUE-2 may require constructor signature updates in tests if dispatcher is injected.

### Rollback / Safety
- Ship as **separate commits per issue** to allow targeted rollback.
- For ISSUE-7+4 and ISSUE-3 (architectural refactors):
  - keep domain contracts minimal and additive first,
  - preserve external behavior before removing old wiring,
  - gate with existing tests + targeted new parity tests.
- For ISSUE-2:
  - preserve cancellation and progress semantics to avoid partial regressions.
  - consider `supervisorScope` instead of `coroutineScope` for batch isolation.
- For ISSUE-5:
  - ensure log sanitization cannot accidentally reintroduce raw query through string templates.
  - verify `GeoapifyGeocodingService.buildSafeLogRoute()` pattern is adopted consistently.

### Acceptance Criteria
- [ ] ISSUE-5: Zero raw merchant/query/address strings in logcat output from geocoding stack. Grep audit clean.
- [ ] ISSUE-1: Successful scan path always clears item-analysis fields (`itemCategorizations`, `isAnalyzingItems`, `showItemBreakdown`, `itemAnalysisError`) before showing new receipt state. Pre-processing state also cleared.
- [ ] ISSUE-6: In-flight item analysis is cancelled when a new scan starts. Receipt-ID guard prevents stale writes.
- [ ] ISSUE-1+6: New `ReceiptScanViewModel` tests for stale-state and rapid-rescan scenarios pass.
- [ ] ISSUE-2: `processBatch()` heavy work executes on explicit IO dispatcher path; no inherited-main async execution remains.
- [ ] ISSUE-2: Batch progress and cancellation behavior remain correct in stress tests.
- [ ] ISSUE-3: `LocationResolver` constructor no longer depends on `ExpenseRepository` or `MerchantLocationRepository` concrete classes.
- [ ] ISSUE-3: Domain location ports + DI bindings are in place and resolver tests pass.
- [ ] ISSUE-7+4: `SplitCalculator` eliminated (or fully delegating to `SharedExpenseManager`). Zero data-entity imports in `domain/` packages.
- [ ] ISSUE-7+4: Single source of truth for split/balance/settlement calculations. No duplicated algorithms.
- [ ] ISSUE-8: All cents conversion uses `Long`, not `Int`. Unit test verifies amounts > $25M.
- [ ] ISSUE-9: DFS solver aborts after iteration limit and falls back to greedy. Unit test verifies 15-member group completes < 500ms.
- [ ] ISSUE-10: Group expense creation failure does not leave orphaned system expense.
- [ ] Full targeted test suite for modified modules passes, and manual smoke checks for scan batch, map resolution, and groups balances are clean.

---

### Effort Summary

| Issue | Severity | Effort | Risk |
|-------|----------|--------|------|
| ISSUE-5 | MAJOR | Medium | Low (mechanical log changes) |
| ISSUE-1+6 | MAJOR | Medium | Medium (race guard complexity) |
| ISSUE-2 | MAJOR | Medium | Medium (dispatcher change affects timing) |
| ISSUE-7+4 | HIGH+MAJOR | High | High (consolidating divergent logic) |
| ISSUE-8 | MEDIUM | Low | Low (Int→Long widening) |
| ISSUE-9 | MEDIUM | Medium | Low (fallback algorithm) |
| ISSUE-10 | MEDIUM | Medium | Medium (cross-repo transaction) |
| ISSUE-3 | MAJOR | High | Medium (ports/adapters + DI) |
| **Total** | | **~5-7 dev-days** | |

---

### Assumptions & Unknowns (explicit)
- Assumption: Injected dispatcher approach is acceptable for ISSUE-2 (preferred for testability).
- Assumption: Resolver port extraction is limited to `LocationResolver` only, not global cleanup of all domain→data dependencies.
- Assumption: `SharedExpenseManager` becomes the single authority for split calculations (ISSUE-7).
- Unknown: Whether product/security policy requires one-way salted hash for merchant log tokens (current codebase uses non-cryptographic hash-style anonymization).
- Unknown: Whether there are unpublished instrumentation tests asserting exact log strings.
- Unknown: Exact iteration threshold for DFS timeout (100K suggested, needs profiling).
- Unknown: Whether the greedy settlement fallback quality is acceptable to product (may show more transfers).
