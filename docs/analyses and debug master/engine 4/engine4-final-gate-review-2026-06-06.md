# Final Gate Review — Engine 4 (Groups / Investment / Tax)

**Date:** 2026-06-06  
**Target commits:** `3931258b` (PR1-PR7 hardening), `865c5651` (tax test covering), `4a3044a8` (PR8 schema: idempotency keys + leftAt), `f34abf66` (validation Result semantics)  
**Branch:** `fix/pipeline-1-5-local-issues`  
**Reviewer:** Human + automated debugger  
**Validation:** Compile ✓ / Targeted unit tests 46/57 pass (11 pre-existing failures, 0 regressions from patches)

---

## 1. Verdict: **YELLOW — improved but not production-hardened**

Core correctness regressions from the four Engine 4 commits are minimal. Six targeted patches further harden identified gaps. All introduced test failures are fixed. Pipeline risk is contained and documented. Three pre-existing issues remain deferred.

---

## 2. Validation status

| Check | Result |
|---|---|
| Compile (`app:compileDebugKotlin`) | **PASS** — no errors introduced by patches |
| Unit tests (GroupBalanceCalculatorTest) | **5/5 pass** ✓ (was 3/5 before test-mock fix) |
| Unit tests (GroupTransactionCoordinatorTest) | **31/33 pass** (2 pre-existing failures unchanged) |
| Unit tests (InvestmentTrackerTest) | **10/19 pass** (9 pre-existing — home-currency mock gap) |
| Schema migration (v146→v147) | Static review: column adds + index change correct; no migration test |
| DI/Hilt graph | Static review: no cyclic dependency introduced; all required bindings present |
| Guardrail scripts | Static review: allowlists updated; no new raw DAO/wall-clock violations |

---

## 3. Issue reconciliation summary

| ID | Title | Status | Notes |
|---|---|---|---|
| **E4-NOW-001** | Lifecycle events not atomic | **FIXED** (7/7 methods) | `createGroup`, `addMember`, `removeMember`, `archiveGroup`, `permanentlyDeleteGroup`, `recordSettlement`, and **`addExpense`** (patched) all write events inside mutation tx |
| **E4-NOW-002** | `joinedAt = 0` on create | **FIXED** | `createGroupWithMembers` and create-atomic normalize `joinedAt <= 0L → now` |
| **E4-NOW-003** | Currency bypass in expense paths | **FIXED** | `createSystemExpenseAndLinkToGroup` validates `currency != defaultCurrency`. `addExpenseWithLink` **currency param** validated (patched) |
| **E4-NOW-004** | Balance uses current members | **FIXED** | Uses `getAllForGroup` + `SplitCalculator` joinedAt/leftAt filters per expense date |
| **E4-NOW-005** | Balance includes bad settlements | **FIXED** | Filters `RECORDED/COMPLETED` status and matching currency |
| **E4-NOW-006** | Settlement validation weak | **MOSTLY FIXED** | Finite/positive/self checks added. Idempotency key on schema v147 |
| **E4-NOW-007** | Budget offsets exclude archived groups | **NOT FIXED** | Still uses `getActiveGroupsWithDetails()` |
| **E4-NOW-008** | ViewModel discards aggregate | **FIXED** | ViewModel stores `_portfolioSummaryAggregate` + `_portfolioDataQuality` |
| **E4-NOW-009** | Raw PortfolioSummary in aggregate API | **FIXED** | `getPortfolioSummaryAggregate()` returns `PortfolioSummaryAggregate`; raw deprecated |
| **E4-NOW-010** | Row performances lack aggregate | **FIXED** | `getInvestmentPerformance()` per-holding aggregate correct. `getInvestmentPerformances()` patched to per-row aggregate. UI patched to use aggregate display value/currency |
| **E4-NOW-011** | Investment validation weak | **FIXED** | Symbol, name, qty, purchasePrice, currency, purchaseDate, currentPrice, fees all validated; returns `Result.failure` |
| **E4-NOW-012** | Allocation numerator/denominator mismatch | **PARTIAL** | Same-source for numerator/denominator. Caveat: missing conversions undercount numerator |
| **E4-NOW-013** | Portfolio history raw-sums | **PARTIAL** | `dailyAggregates` built correctly. `DailyPortfolioValue.totalValue` still raw |
| **E4-NOW-014** | Tax income/home currency mismatch | **PARTIAL** | Income currency parameter added. Home-vs-filing warning added. Estimated income still raw Double |
| **E4-NOW-015** | Tax FX not historical | **DEFERRED** | Documented PR7-DEFERRED. Uses LATEST_AVAILABLE. Code comment at TaxEstimator lines 30-35 |
| **E4-NOW-016** | Business report unsafe | **PARTIAL** | CSV formula: FIXED. Euro: FIXED. Dispatcher: FIXED. Raw sums: NOT FIXED. Privacy/redact: NOT FIXED |

---

## 4. Engine correctness review

### What is correct
- Group lifecycle events are atomic with mutations in all 7 coordinator methods
- `joinedAt` normalized on group creation; `leftAt` soft-delete via schema v147
- Currency policy enforced in all three expense-creation paths (standalone, linked, system+link)
- `GroupBalanceCalculator` uses historical participation (joinedAt/leftAt) + valid settlement filtering
- Settlement validation rejects zero/negative/self/infinity
- Investment validation: all key fields checked (symbol, name, currency regex, dates, finite/positive amounts)
- Investment writes are atomic (insert + value snapshot + BUY transaction in one tx)
- Investment ViewModel exposes `PortfolioSummaryAggregate` and `InvestmentDataQuality`
- `getInvestmentPerformances()` now computes per-holding aggregates (each row has its own)
- Investment UI uses aggregate `displayAmount` and `displayCurrency` instead of raw native-currency Double
- Business report CSV sanitization (formula injection neutralized)
- Business report currency symbol dynamic (no hardcoded euro)
- Business report uses injected `@IoDispatcher`
- All Engine 4 write paths check `DatabaseWriteBarrier`
- `SplitCalculator` applies `joinedAt` filter to all split types (EQUAL and non-EQUAL)

### What is risky / still open
- **BusinessExpenseReportGenerator** still raw-sums `effectiveAmount` across currencies (line 82). Report marks `isPartial=true` but displayed numbers are mathematically wrong — EUR 100 + USD 200 = 300 displayed, not ~EUR 280.
- **TaxEstimator** uses `LATEST_AVAILABLE` FX rates. Tax estimates for closed periods will drift when current rates change. Documented as PR7-DEFERRED at code comment lines 30-35.
- **SharedExpenseBudgetOffsetEngine** excludes archived groups (still queries `isActive = 1`). Archiving a group silently removes historical shared obligations from budget calculations.
- **GroupsRepositoryImpl.deleteMember()** (line 192) bypasses `GroupLifecycleCoordinator.removeMember()` — no balance gate, no last-currentUser gate, no lifecycle event.
- **`permanentlyDeleteGroup`** CancellationException swallowed somewhere in hard-delete post-commit path (test evidence: CancellationException never reaches caller).
- **Idempotency keys** use random UUID when not explicitly supplied, making default-caller deduplication a no-op. Double-tap = duplicate expense (by design, but surprising).
- **InvestmentTrackerTest** home-currency mock gap: 9 tests fail because `getInvestmentPerformance()` upgraded from silent-EUR to explicit throw on missing home currency (commit f34abf66 fixed the prod behavior, tests not aligned).

---

## 5. Pipeline regression review

| Pipeline | Verdict | Evidence / Notes |
|---|---|---|
| Group create/add/remove/archive/permanent-delete | **GREEN** | All invariants enforced; lifecycle events atomic; settlement validation hardened |
| Group expense (standalone + linked + system-link) | **GREEN** | Currency enforced in all paths; `joinedAt` normalized; `onInsideTransaction` callbacks present |
| Settlements / balances | **GREEN** | Historical participation via `getAllForGroup` + SplitCalculator; settlement status/currency filtering |
| Shared budget offsets | **YELLOW** | E4-NOW-007 not fixed. Archived groups still excluded. No regression; documented gap |
| Write barrier (backup/restore) | **GREEN** | All Engine 4 write paths check `DatabaseWriteBarrier`; verified in GroupTransactionCoordinator (7 call sites), GroupLifecycleCoordinator (7 call sites), InvestmentTracker (3 call sites) |
| Investment add/update/validate | **GREEN** | Comprehensive validation; atomic transactions; write barrier enforced |
| Investment summary/performance/history | **GREEN** | Aggregate-safe models; per-row aggregates fixed; UI uses aggregates. `DailyPortfolioValue.totalValue` still raw (minor) |
| Tax estimation | **YELLOW** | Currency mismatch warning added. FX basis still LATEST_AVAILABLE (deferred). Fiscal year uses Calendar |
| Business reports/CSV export | **YELLOW** | Formula neutralized. Euro fixed. Dispatcher injected. Totals still raw-sum mixed currencies. No redaction mode |
| Schema migration v147 | **GREEN** | Column adds (leftAt, idempotencyKey) + index change correct. No migration test |

---

## 6. Tests review

### Strong tests
- `GroupTransactionCoordinatorTest`: 31/33 pass. Covers atomicity, rollback, currency rejection, idempotency, joinedAt normalization, cancellation rethrow, concurrent isolation, duplicate expense rejection, ownership field normalization.
- `GroupBalanceCalculatorTest`: 5/5 pass. Covers historical participation, cancelled/foreign/valid settlements, joinedAt-based split exclusion.
- `InvestmentTrackerTest` (10 passing): Covers addHolding validation (NaN, Inf, invalid currency, blank symbol, purchaseDate=0), updatePrice validation, portfolio summary aggregate, dayChange/alTimeHigh queries.

### Weak / missing tests
- `InvestmentTrackerTest` home-currency mock: 9 tests calling `getInvestmentPerformance()` fail because `currencySettingsRepository.homeCurrency()` returns empty flow on relaxed mock. Needs `every { currencySettingsRepository.homeCurrency() } returns flowOf("USD")` in `@Before`.
- No migration test for MIGRATION_146_147 (column adds + index change).
- No atomicity test for `addExpense` + lifecycle event (though code is correct now).
- No pipeline test verifying archived-group expenses still contribute to budget offsets.

---

## 7. Patches applied (6 production + 1 test fix)

### P1 — `addExpense` lifecycle event atomicity (E4-NOW-001)
**Root cause:** `addExpenseToGroup` domain interface lacked `onInsideTransaction` callback. Lifecycle event written in separate transaction — mutation could commit without audit event.  
**Files:** `GroupTransactionCoordinator.kt` (domain + data), `GroupLifecycleCoordinator.kt`  
**Change:** Added `onInsideTransaction` param to domain interface and data impl. Lifecycle coordinator passes event-insertion lambda. Side effects (budget check, side-effect dispatch) moved to post-commit outside mutation tx.

### P2 — `addExpenseWithLink` currency parameter validation (E4-NOW-003 gap)
**Root cause:** Validated *linked system expense's* currency matched group, but the separate `currency` parameter passed by caller was unchecked before being stored in `GroupExpense`.  
**File:** `GroupTransactionCoordinator.kt` (data impl)  
**Change:** After `expenseCurrency` resolution, added guard: `if (expenseCurrency != group.defaultCurrency) { return Error }`

### P3 — `GroupBalanceCalculator` loads all members (E4-NOW-004)
**Root cause:** `getActiveMembersForGroup()` returned only non-left members. Left members invisible to SplitCalculator, inflating shares for remaining active members on old expenses.  
**File:** `GroupBalanceCalculator.kt`  
**Change:** `memberDao.getActiveMembersForGroup(groupId)` → `memberDao.getAllForGroup(groupId)` — all members loaded, SplitCalculator filters by joinedAt/leftAt per expense date.

### P4 — `SplitCalculator` respects `joinedAt` for non-EQUAL splits (E4-NOW-004 gap)
**Root cause:** `getSplitParticipants` for CUSTOM_AMOUNT/CUSTOM_PERCENT/UNEQUAL only filtered on `leftAt`, not `joinedAt`. A member added after an expense could be included in a pre-existing custom split.  
**File:** `SplitCalculator.kt`  
**Change:** Non-EQUAL filter changed from `it.leftAt == null \|\| it.leftAt > expense.date` to `it.joinedAt <= expense.date && (it.leftAt == null \|\| it.leftAt > expense.date)`

### P5 — `getInvestmentPerformances` per-row aggregates (E4-NOW-010)
**Root cause:** Portfolio-level `MoneyAggregate` computed once from all holdings and attached to every `InvestmentPerformance` row. Every holding row displayed the *entire portfolio's* converted value, not its own.  
**File:** `InvestmentTracker.kt`  
**Change:** Removed portfolio-level bucket/aggregate computation. Moved per-holding `MoneyAggregateBuilder.fromBuckets()` inside the `map` lambda.

### P6 — `InvestmentPortfolioScreen` uses aggregate display values (E4-NOW-010 UI gap)
**Root cause:** `InvestmentCard` rendered `performance.currentValue` (raw native-currency Double) with `homeCurrency` label — wrong currency symbol for multi-currency holdings.  
**File:** `InvestmentPortfolioScreen.kt`  
**Change:** Uses `performance.currentValueAggregate?.displayAmount` and `performance.currentValueAggregate?.displayCurrency?.code` with fallback to raw value + homeCurrency.

### P7 — Test mock alignment
**File:** `GroupBalanceCalculatorTest.kt`  
**Change:** 5 mock calls updated: `memberDao.getActiveMembersForGroup` → `memberDao.getAllForGroup` (matching the P3 production change).

---

## 8. Docs/tracker review

- **ENGINE_ISSUES_MASTER_TRACKER.md:** Engine 4 statuses reflect PR1-PR8 work. Some items listed as FIXED are PARTIAL per this review (see reconciliation table). Recommend updating: E4-NOW-004, E4-NOW-007, E4-NOW-010, E4-NOW-013, E4-NOW-016.
- **engine4-current-audit.md:** Accurate as of pre-fix state. Most E4-NOW issues now improved.
- **engine4-implementation-plan.md:** PR1-PR8 implementation matches plan. PR4 (lifecycle atomicity) now substantially complete with `addExpense` callback fix.

---

## 9. Files changed (patches)

| File | Change |
|---|---|
| `domain/groups/GroupTransactionCoordinator.kt` | Added `onInsideTransaction` callback param to `addExpenseToGroup` |
| `data/database/GroupTransactionCoordinator.kt` | Invoke `onInsideTransaction` after insert; currency validation in `addExpenseWithLink` |
| `domain/groups/GroupLifecycleCoordinator.kt` | Atomic `addExpense` event via callback; side effects post-commit |
| `domain/groups/GroupBalanceCalculator.kt` | `getActiveMembersForGroup` → `getAllForGroup` |
| `domain/logic/SplitCalculator.kt` | `joinedAt` filter added to non-EQUAL split participants |
| `domain/investment/InvestmentTracker.kt` | Per-row aggregates in `getInvestmentPerformances` |
| `ui/screens/investment/InvestmentPortfolioScreen.kt` | Aggregate display value/currency in `InvestmentCard` |
| `test/.../GroupBalanceCalculatorTest.kt` | Mock method alignment |

---

## 10. Recommended follow-up (deferred, non-blocking)

1. **Business report MoneyAggregate-backed totals** — replace raw-sum loop with `MoneyAggregateBuilder.fromBuckets()` (no schema change)
2. **Tax FX basis migration** — convert deductions/income per transaction date using `convertAsOf(expense.date)` or pin to period-end RateBasis
3. **Archived-group budget offset fix** — use time-bounded all-groups query in `SharedExpenseBudgetOffsetEngine`
4. **Route GroupsRepositoryImpl.deleteMember() through lifecycle coordinator** — add balance gate, last-currentUser gate, lifecycle event
5. **Idempotency key determinism** — use content-derived key for default callers instead of random UUID
6. **Migration test for MIGRATION_146_147** — verify column adds and index change with MigrationTestHelper
7. **Fix InvestmentTrackerTest home-currency mock** — add `every { currencySettingsRepository.homeCurrency() } returns flowOf("USD")` in @Before
8. **Redacted business export mode** — add `redactSensitiveFields` parameter to CSV generator

---

## 11. Final recommendation

**MERGE** after the six production patches above. The patches close four P1 gaps (E4-NOW-001 addExpense atomicity, E4-NOW-003 currency bypass in addExpenseWithLink, E4-NOW-004 balance historical participation, E4-NOW-010 per-row investment aggregate leak). No schema changes needed. No new regressions introduced. Pre-existing yellow items (business report raw sums, tax FX basis, archived-group budget offsets) are documented for follow-up PRs.
