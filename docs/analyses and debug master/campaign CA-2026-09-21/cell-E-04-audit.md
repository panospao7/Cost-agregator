# Cell E-04 Audit

- Date: 2026-09-22
- Pinned commit: 37601232b9778170c57a656a245b199ab6d7d965 (`git rev-parse --short HEAD` = `37601232`)
- Session: direct astra session
- Cell: E-04
- Mode: AUDIT, static only
- Production diff check: `git diff --stat 37601232..HEAD -- app config scripts` empty
- Final pin recheck: HEAD remains `37601232`; both the pin-to-HEAD production diff and uncommitted `app config scripts` diff are empty.
- Outcome: **4 discovery findings: 2 P0, 0 P1, 0 P2, 2 P3.** Independent Phase 2 verification is pending; these are not registry-confirmed findings.
- Path convention: abbreviated `domain/`, `data/`, `ui/` paths below are under `app/src/main/java/com/yourname/expensetracker/`; matching test package paths are under `app/src/test/java/com/yourname/expensetracker/`.

## Coverage

### Governing material
- [x] Master prompt §4 defect classes/finding schema/severity
- [x] Master prompt §2 campaign inputs / known-debt and intended-behavior sources
- [x] Coverage matrix E-04 row
- [x] Legal path sections: `Group Mutations`, `Business Reports / Tax`, `Shared Expense Management (Groups - Domain Facade)`, `Investment Mutations`, `Expense Mutations`
- [x] CODEBASE_SEGMENTS entries: Segments 15, 17, 24, 25
- [x] Targeted inventory entries and engine rows: InvestmentTracker, TaxEstimator, GroupTransactionCoordinator, SharedExpenseManager, GroupBalanceCalculator; group interaction subsection only (152-159). No large map read wholesale.

### Production files
- [x] SharedExpenseManager
- [x] SharedExpenseDataPortAdapter
- [x] data/database/GroupTransactionCoordinator.kt
- [x] GroupBalanceCalculator
- [x] SharedExpenseBudgetOffsetEngine
- [x] InvestmentTracker
- [x] TaxEstimator
- [x] BusinessExpenseReportGenerator

### Audit traversal
- [x] Callers and entry points traced
- [x] DAO/event/side-effect paths traced
- [x] Existing tests/guards inspected statically (not executed)
- [x] All 15 defect classes considered

## Findings

Discovery findings below require the campaign's independent Phase 2 verification; no runtime validation is claimed.

### CA-E-04-001 | Budget FX failures write merchant and financial payloads directly to Android logs | defect class 9 | severity P0

- Evidence at pin: `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseBudgetOffsetEngine.kt`, `calculateEffectiveBudgetSpend`, lines 117-131, 151-164, 168-182. A null conversion constructs a message containing the personal expense's merchant, effective amount, source/target currencies and date, then calls `android.util.Log.w`. Shared/reimbursement failures similarly log amounts, currencies and dates. These calls have no redaction, consent, or build-type condition. Lines 77-80 additionally pass an exception object to the log sink.
- Impact path: budget status calculation -> per-expense historical FX conversion -> missing rate -> financial payload written to logcat. This is a concrete local logging disclosure; no remote upload is asserted. P0 follows the governing rubric's privacy-leak category.
- Caller trace: `data/repository/BudgetRepository.kt` status assembly line 360 -> `computeAdjustedSpend`, lines 380-390 -> engine; also `ui/screens/budget/BudgetViewModel.kt`, status mapping lines 104-107 -> `calculateAdjustedSpend`, lines 156-162 -> engine.
- Existing tests/guards: `domain/groups/SharedExpenseBudgetOffsetEngineTest.kt` setup lines 31-58 returns successful conversions for every input; named tests cover linked-row exclusion, category filtering, malformed splits, query counts, empty input and repository failure, with no log-redaction assertion. No matching engine-specific privacy guard was found in the scoped config/scripts search. Tests/guards NOT RUN.
- Cross-cell impact: P-06 budget status and P-08 privacy/diagnostics. This concerns logging, not the legitimate user-facing expense data in a report.
- Old-ID cross-refs: none for this sink in the revalidated registry or engine-4 historical ledger. `E4-NOW-007` / `E4-CURRENT-011` (archived groups) and `E4-CURRENT-012` (aggregate provenance) are separate known issues and are excluded.

### CA-E-04-002 | Departed-member inputs reject custom splits or silently persist an equal share for UNEQUAL | defect class 11 | severity P0

- Evidence at pin: `data/repository/GroupsRepositoryImpl.kt`, `getActiveGroupsWithDetails`, lines 46-66 loads all historical members through `GroupMemberDao.getAllForGroups` (`data/database/dao/GroupMemberDao.kt`, lines 52-53, no leftAt predicate). `ui/screens/groups/SharedExpenseGroupsViewModel.kt`, `loadGroups`, lines 104-110 copies that list into the screen model. `ui/screens/groups/SharedExpenseGroupsScreen.kt`, lines 185-197 passes it unfiltered to `AddExpenseDialog`; lines 799-802, 816-827, 973-985 create a required input for every member. ViewModel `resolveGroupMembers` / `serializeAndValidateCustomSplits`, lines 349-360 and 391-398, validates against that same historical set. In contrast, `data/database/GroupTransactionCoordinator.kt`, `createSystemExpenseAndLinkToGroup`, lines 738-759 selects active members, and `validateCustomSplitPayloadFormat`, lines 1133-1141 rejects CUSTOM_AMOUNT/CUSTOM_PERCENT unless the entry count equals that active count.
- Impact path: open an active group containing two active members plus a departed member -> add an expense -> enter all three required custom amounts/percentages -> screen accepts the payload -> coordinator rejects it because it expects two entries. Leaving the departed member blank fails the dialog's required-field check. The same historical list also offers departed payers (screen lines 896-911), which the coordinator rejects. Keeping departed members for historical balances is correct; reusing that list for new expense inputs causes the defect.
- Stronger persisted-money impact: the dialog offers every `SplitType` (screen lines 930-954) and submits every input key (1038-1045). For UNEQUAL, the coordinator's count check is skipped (1133-1135), and `SplitCalculator.validateExpenseParticipants` returns immediately for non-EQUAL (lines 84-99). With active Alice/Bob and departed Carol, a EUR 90 payload `{Alice:10, Bob:80, Carol:0}` passes ViewModel validation against all three members. `resolveCurrentUserShare` (coordinator lines 1034-1070) supplies only active members to `SplitCalculator`; `CustomSplitParser.parseAndValidate` lines 88-92 rejects Carol's ID. `SplitCalculator.calculateUnequalSplit` lines 177-193 and fallback lines 217-228 silently substitute an equal split, EUR 45 each. The coordinator persists Alice's `myShareAmount=45` via `CreateExpenseRequest` (lines 827-840) and the original UNEQUAL JSON in the group row (855-868). The user requested EUR 10 but the stored effective share becomes EUR 45. Severity P0 follows the rubric's persisted money/data-corruption category; runtime reproduction remains pending.
- Caller trace: `SharedExpenseGroupsScreen.AddExpenseDialog` -> `SharedExpenseGroupsViewModel.addExpense` lines 218-266 -> `AddGroupExpenseUseCase.invokeAtomic` -> `GroupsRepositoryImpl.createSystemExpenseAndLinkToGroup` lines 152-177 -> coordinator -> GroupExpenseDao/system lifecycle path. The rejected custom amount/percentage path writes neither expense nor link.
- Existing tests/guards: `GroupTransactionCoordinatorTest.kt` lines 1224-1240 explicitly supports a persisted left member and checks active exclusion, but does not traverse the dialog; its custom-amount fixture around lines 745-756 uses the ordinary member set. `SharedExpenseGroupsViewModelTest.kt` covers add success/error and refresh, with no leftAt fixture found. `CustomSplitParser.kt` lines 122-126 requires exact membership, so it cannot compensate for the mismatched sets. Tests/guards NOT RUN.
- Cross-cell impact: group UI and E-04 group persistence. Reproduction requires supported historical `leftAt` state, not a concurrent write.
- Old-ID cross-refs: none for the new-entry UI/member-set mismatch. `E4-NOW-004` / `E4-CURRENT-007` concerned historical balance participation, whose all-member fix is preserved; `E4-CURRENT-006` concerned removal atomicity and is not restated.

### CA-E-04-003 | Dormant settlement-aware balance API applies repayments with reversed signs | defect class 13 | severity P3

- Evidence at pin: `domain/groups/GroupBalanceCalculator.kt`, `calculateMemberBalance`, lines 42-59 computes a creditor-positive balance (`paidTotal - owedShareTotal`) but then subtracts payments made and adds payments received. `domain/groups/SettlementCalculator.kt`, `calculateSettlements`, lines 73-98 maps negative balances to payers and positive balances to recipients; `recordSettlement` documentation/arguments at lines 160-181 uses from=payer and to=recipient. Thus repayment must move each balance toward zero, not away from it.
- Impact path: hypothetical consumer reads a EUR 100 equal expense paid by Alice for Alice/Bob -> initial balances +50/-50 -> a recorded EUR 50 Bob-to-Alice settlement -> API returns +100/-100 instead of 0/0. This API is dormant at the pin; no live balance-screen defect is asserted.
- Caller trace: NONE-FOUND. Production symbol/call searches find only the `GroupBalanceCalculator` declaration and `calculateMemberBalance` definition. Current groups UI calls `SplitCalculator.calculateBalances` directly (`SharedExpenseGroupsViewModel.kt`, lines 139-143), and no production settlement-recording caller was found. Class/severity follow the governing zero-caller rule; underlying arithmetic defect is class 7.
- Existing tests/guards: `domain/groups/GroupBalanceCalculatorTest.kt` lines 54-58 pins creditor-positive semantics; `valid settlement still updates balance`, lines 125-142, checks only `settlementsPaid` / `settlementsReceived`, never the resulting net balances. Cancelled/foreign settlement tests check exclusion. No repayment-to-zero test is present in that fully read file. Tests/guards NOT RUN.
- Cross-cell impact: E-04 settlement math; relevant before wiring this API or a persistent settlement UI.
- Old-ID cross-refs: none for the sign inversion. `E4-NOW-005` / `E4-CURRENT-008` covered settlement status/currency filters, which are present at lines 53-56 and do not fix signs.

### CA-E-04-004 | Dormant tax category MoneyAggregates regressed to relabeled raw sums | defect class 13 | severity P3

- Evidence at pin: `domain/tax/TaxEstimator.kt`, `getTaxYearSummary`, lines 353-375 obtains currency-less `BusinessCategoryTotal` rows and wraps their totals in `MoneyAggregate.singleCurrency(... filingCurrency ...)`. `data/repository/BusinessExpenseRepository.kt`, `getExpensesByCategory`, lines 57-59 suppresses the ERROR deprecation and delegates to `data/database/dao/ExpenseDao.kt`, `getBusinessExpensesByCategory`, lines 2478-2493, whose SQL groups by category only and excludes null categories. The typed map also omits the null-category remainder that lines 364-367 add to the legacy map.
- Impact path: hypothetical annual-summary consumer -> a category with EUR 100 + USD 100 -> raw 200 labeled as filing currency with a successful single-currency aggregate, without conversion or an FX failure. Null-category deductions disappear from the typed category breakdown. This is not asserted as a currently displayed tax-screen error.
- Caller trace: NONE-FOUND for `getTaxYearSummary` across production Kotlin. `TaxConfigurationViewModel.kt` lines 110-115 calls only `estimateTaxes`. The typed yearly API is classified as dormant under class 13; underlying defect classes are 7/15.
- Existing tests/guards: `domain/tax/TaxEstimatorTest.kt`, lines 236-270 and 274-296 mock currency-less repository category totals and assert only the deprecated `categorizedDeductions` map. They do not check the typed map, missing null category, or currency conversion. The DAO has an ERROR deprecation but repository suppression bypasses it. Tests/guards NOT RUN.
- Cross-cell impact: E-01 money aggregate integrity; business/tax reports if this yearly API is wired.
- Old-ID cross-refs: clearly worse than `E4-CURRENT-026`, which tracked the raw legacy map while recommending `categorizedDeductionsAggregate` as the safe alternative. Read-only history evidence: blame attributes lines 353, 369-374 to `1e3d0333870b2bb49c110c2f0200747607a6be2b` (2026-08-30); that commit's parent uses `getBusinessCategoryCurrencyTotals` and `MoneyAggregateBuilder.fromBuckets` with each row's currency/count. The pin replaces this previously safe typed path with the raw repository projection. This is a demonstrated historical regression, not an assertion that a September remediation wave introduced it.

## Coverage checkpoint 1

- Full primary reads already performed: `SharedExpenseManager.kt` (526 lines), `SharedExpenseDataPortAdapter.kt` (239), `GroupBalanceCalculator.kt` (67), `SharedExpenseBudgetOffsetEngine.kt` (287), `BusinessExpenseReportGenerator.kt` (318). After context compaction their earlier line references are not used for findings without a fresh extract.
- `data/database/GroupTransactionCoordinator.kt` fully re-read in contiguous ranges 1-365, 366-710, 711-1173: all group/member/expense entry points, validation, transaction boundaries, ownership/create DB-only calls, rollback signals, idempotency lookup, deletion event, and post-commit runner.
- `InvestmentTracker.kt` remainder 215-629 read after the earlier 1-214 read: aggregates, performance, allocation, history, and clock helpers. `TaxEstimator.kt` read through 441; deductible/income builders and year-summary category source traced into the business repository/ExpenseDao queries.
- `GroupsRepositoryImpl.kt` fully read (1-290): live UI path delegates create/add/link/archive to the coordinator; member removal rechecks payer and split references inside a transaction; aggregate reader includes all historical members.
- Known-debt reconciliation expanded to `engine_4_groups_investment_tax_debug_report.yaml` (issue headings and relevant bodies) and archived engine-4 current audit/final gate. Do not duplicate `E4-CURRENT-006` member removal race, `E4-CURRENT-010` historical hard-delete cleanup, `E4-CURRENT-016/018/019` investment FX/history/allocation, `E4-CURRENT-023/025/026` tax FX/raw income/category map, or `E4-CURRENT-027/028` business report raw totals/privacy. Specific residuals are investigated separately only if new or clearly worse.
- Archive history verified: engine line 84 calls `getActiveGroupsWithDetails`; repository lines 46-59 begins from `ExpenseGroupDao.getActive`. `E4-NOW-007` / `E4-CURRENT-011` remains present, not a new finding.

## Final coverage and disposition

- Additional end-to-end reads: `domain/logic/SplitCalculator.kt` (448 lines: historical participation, custom parsing/fallback, cents/remainder allocation, creditor-positive balances, legacy greedy settlements); `domain/groups/SettlementCalculator.kt` (448: currency gate, cents normalization, bounded DFS/greedy fallback, dormant record helper); `GroupBalanceCalculatorTest.kt` (144).
- Owning DAOs read fully: `GroupExpenseDao.kt` (84), `GroupMemberDao.kt` (165), `ExpenseGroupDao.kt` (114), `InvestmentDao.kt` (70), `InvestmentValueDao.kt` (44), `InvestmentTransactionDao.kt` (15). Read and checked insert conflict behavior, all-vs-active membership queries, current-user transaction/key, group filters, link lookup/idempotency, cascade-related cleanup queries, portfolio query scope and history window bounds.
- Read budget: 18 distinct source/test files covered end-to-end; the repeated full reads of TaxEstimator and GroupBalanceCalculator bring the conservative full-read count to **20/20**. Source reading stopped at that limit. Other call-site, test, shared-lifecycle, entity, guard and documentation reads were bounded extracts. Truncated outputs were followed with narrower ranges where needed; unobserved output was not used as finding evidence.
- Supporting extracts: `CustomSplitParser` exact-membership and finite/value validation; group/member/settlement entities and FKs/indexes; `GroupSettlementDao` read/write declarations; `AddGroupExpenseUseCase`; live groups/budget/investment/tax ViewModel calls; add-expense dialog input construction; ExpenseDao purchase-only business aggregates, raw category query and currency-aware alternative; business repository projection; ownership policy entries; cancellation baseline/allowlist; named tests for all primary areas.
- Group expense creation traced through `TransactionLifecycleCoordinator.createExpenseDbOnlyV2` (853-857) -> request-to-entity ownership mapping (469-474) -> `ExpenseDao.insertAtomic` (coordinator 658-675; DAO 97-99) plus CREATED event/source links inside its transaction. The outer group transaction inserts the link and runs the returned post-commit batch only after commit (group coordinator 868-915). The wrong share in CA-E-04-002 is therefore actually passed to storage; no downstream recalculation repairs it. Existing ownership linkage uses the DB-only update API, not the standalone side-effect-dispatching API.
- Hard deletion traced through cleanup, BULK_UPDATED event inside `RoomDomainTransactionRunner`, `RoomTransactionLifecycleEventWriter.write(context, event)` -> TransactionEventDao insert, followed by bulk side-effect planning. A linked-ID snapshot remains outside the deletion transaction (coordinator 969 versus 972), but the hard-delete chain terminates in the unused SharedExpenseManager facade; no live UI hard-delete caller was found. This is retained as a residual reconciliation note under `E4-CURRENT-010` / `P2-CURRENT-008`, not inflated into a fresh live-race finding. Current UI deletion archives through GroupsRepository.
- Investment read UI uses `getPortfolioSummaryAggregate` and per-holding `getInvestmentPerformance`, not the deprecated raw summary. `addHolding` checks the barrier and inserts holding, initial value and BUY record in one Room transaction; no production caller was found. `updatePrice` exists only as a DAO declaration, while LEGAL_PATHS still describes a tracker method: known zero-inbound disposition is recorded in `GR-14A9_ZERO_INBOUND_TRIAGE.yml` (74-83); do not invent a missing live price-update failure. Allocation/history APIs also have no production callers at this pin; their old currency/time limitations remain known debt.
- Business report generator: purchase-only filtering, mixed-currency warning/partial marker, raw category/project totals, missing receipts, mileage and CSV sanitization inspected. No production consumer found, consistent with the P-12 cell's coverage record. Existing raw-sum/export concerns (`E4-CURRENT-027/028`, `E4-NOW-016`) are not new findings.
- Cancellation findings were not reissued: InvestmentTracker's suspending `runCatching` is explicitly allowlisted under MIT-034, and `config/baselines/cancellation.json` already tracks InvestmentTracker G-CANCEL-02 and TaxEstimator G-CANCEL-01/02. Broad barrier check-vs-lease concerns likewise belong to the known universal barrier debt.

### All-class audit ledger

| Class | Static checks and disposition |
|---|---|
| 1 Legal path | Traced live group use case/repository -> data coordinator -> expense lifecycle DB-only API/DAO/event. SharedExpenseManager is a dormant facade, not the actual UI entry. No new bypass asserted from that naming difference. Investment writer and tax/report read paths inspected. |
| 2 Barrier | Entry checks on group/adapter/investment writes; nested expense ownership uses runWrite. No new restore bypass established; universal check/lease race not restated. |
| 3 Atomicity/TOCTOU | Create/link rollback and group/member insert transactions inspected; live repository member removal rechecks inside transaction. Dormant adapter removal and hard-delete residuals reconciled to known debt. |
| 4 Idempotency | Group-scoped key lookups occur in transactions; nullable keys generate UUIDs; linked-expense unique index and duplicate lookup inspected. No invented retry caller or new duplicate claim. |
| 5 Cancellation | Coordinator catches explicitly rethrow CE. Investment/tax catches and runCatching inspected and reconciled to existing cancellation baselines. |
| 6 Side-effect timing | Link/create return action batches; runner is invoked after outer commit. Hard-delete event is transactional and bulk effects follow commit. No new pre-commit execution found. |
| 7 Money/currency | Cents split allocation, historical participant filtering, settlement signs, single-currency group writes, per-currency investment/tax aggregates, partial conversions and report raw sums inspected. Findings 002/003/004; known report/FX debt excluded. |
| 8 Time | joinedAt/leftAt versus expense date; half-open budget and DAO date ranges; portfolio local-day iteration/history bounds; tax fiscal-period helpers inspected. Existing investment/tax time/FX limitations are not new IDs. |
| 9 Privacy | Concrete raw budget logging is finding 001. CSV sanitizer and ordinary report output distinguished from diagnostic logging; no remote transmission inferred. |
| 10 Workers | N/A to the scoped implementation: none of the primary files is a worker or schedules one. Shared post-commit worker internals belong to their owning cells and were not wholesale audited. |
| 11 Integrity | Group FKs/link uniqueness/current-user materialized key, all-vs-active participants and payload consistency checked. Finding 002 identifies a new write that invokes a legacy-data fallback. |
| 12 Errors | Coordinator rollback signals, false/error results, adapter propagation, conversion partial warnings/fallbacks inspected. The blocked custom modes and silent UNEQUAL substitution are covered by 002. |
| 13 Wiring/dead | Production caller searches distinguish live budget/group/read-portfolio paths from dormant manager/balance/settlement/year-summary/report APIs. Findings 003/004 are P3, not hypothetical runtime severities. |
| 14 Tests | Settlement test omits net assertions; tax tests assert legacy maps; budget fixture always converts; left-member custom UI-to-coordinator integration absent in inspected tests. These gaps support findings, without claiming test failures or duplicating general test debt. |
| 15 Fix-regression | Historical diff/blame proves the typed tax category path regressed (004). Group historical-member correction was checked for downstream consequences (002), without assigning an unproven introducing commit. No exhaustive wave-history audit claimed. |

### Validation and limits

- **NOT RUN:** builds, tests, lint, static guards, emulator/device exercises. User requested static audit only.
- Static discovery covered all eight named primary files and the relevant DAO/event/side-effect boundaries. Pure UI outside the mutation inputs, unrelated package data models, full MoneyAggregate/CurrencyConverter internals, whole transaction lifecycle implementation, migration history and worker internals were not exhaustively reviewed; they are cross-cell surfaces, not implied passes.
- No production or test files changed. Only this campaign report and JOURNAL were written. No independent reviewer/guardian gate was run; findings require the campaign's separate verifier.

## Provenance notes

- Architecture intent extracted before source review: E-04 row in `docs/architecture/COVERAGE_MATRIX.md`; legal-path sections listed above; segment entries 15/17/24/25 in `docs/architecture/CODEBASE_SEGMENTS.md`.
- Known behavior/debt checked: `E4-NOW-007` was located in archived engine-4 history after the initial registry search; current archive behavior is verified above. Group lifecycle wrapper absence is documented as intended (`GR-14u36`). Tax deferred design is not itself a finding; 004 records a concrete regression in its dormant typed API.
- Auditor: astra-cell-auditor, direct astra session; no subagents invoked (user requested direct cell audit).
- Final timestamp: 2026-09-22 06:42:18 UTC.
- Pinned source: `37601232b9778170c57a656a245b199ab6d7d965`; final HEAD/diff recheck matched.
- Status: static discovery finished; **4 findings awaiting independent verification**, no implementation or validation-completion claim.
