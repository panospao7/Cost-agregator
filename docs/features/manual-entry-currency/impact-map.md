# Impact map: manual-entry-currency (increment 1; 1b framed only)

Author: impact-mapper (stage 5, read-only). Persisted by the orchestrator. Date 2026-10-16.
`A` = `app/src/main/java/com/yourname/expensetracker`, `T` = `app/src/test/java/com/yourname/expensetracker`. [V] checked in source this pass; [U] not verified. Domain-spec ledger not re-verified. `ux-flows.md` absent at mapping time — re-check UI deltas.

## 0. Corrections to the spec (read first)
- **C-1 TLC path:** `A/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt` [V]. Ctor already injects `currencyConverter` (`:86`) and `currencySettingsRepository` (`:92`) → build `RateEligibilityEvaluator` from existing deps; 8 test files (9 occurrences) construct TLC → use a defaulted ctor param or build inside TLC.
- **C-2 OD-1 = A doesn't apply uniformly:** cap duplicated upstream of TLC (§1); notification and review-approval sources capped before TLC → owner must decide scope.
- **C-3 Setter wiring must NOT ship in the core slice:** today VM rounds HALF_UP to 2dp (`AddExpenseViewModel.kt:367-370`) and saves home only; flagged INV-13 rejects excess precision → a JPY/KRW/ISK-home user's "12.50" would be refused at TLC with no UI. Also `HOME_CURRENCY_UNRESOLVED` becomes new fail-closed manual behaviour. → slice (a) = types, evaluator, validator, TLC refusal, **no production setter**; setter + VM rounding removal land together in (b). Removing VM rounding changes all manual saves: EUR "12.345" (silently 12.35 today) becomes inline PrecisionExceeded — user-visible home-currency change.
- **C-4 No installment code exists** (`declareWithNewExpense`, `InstallmentPlan`, `installment` → 0 hits in `app/src/main`) [V] → part (d) converges on a plan, not code.

## 1. Production files per part
Segments: 16 Currency & Exchange (new policies, evaluator, preview use case); 9 (request, result, validator, TLC, ManualExpenseRepository); receipt segment (`ReceiptLifecycleCoordinator`, ReceiptScan VM/screen); add-expense UI.

### (a) Core — T3 (new files in shard-covered packages; no `test-shards.json` change)
| File | Change |
|---|---|
| `A/domain/currency/CurrencyAmountPolicy.kt` (new) | `fractionDigits` delegating to money-correctness `CurrencyFractionDigits.of` (INV-12; no second accessor; null behaviour = spec OD-6, OPEN; RF-10 2026-10-23) + per-currency cap table (OD-1); shard `domain-a-m` |
| `A/domain/currency/CurrencySelectionPolicy.kt` (new) | `selectable(home, mode)`, `NO_RATE_SOURCE = {BGN, RUB}` (OD-2), proposal → `Unset`; uses `SupportedCurrency` (`CurrencyConverter.kt:21`; HRK inactive `:48`; `activeCatalog` `:53`); `isActive` untouched |
| `A/domain/currency/RateEligibilityEvaluator.kt` (new) | wraps `CurrencyConverter.convertOutcome(…, TRANSACTION_DATE, atMillis, StaleRatePolicy.None)`; no converter change |
| `A/domain/transaction/CreateExpenseRequest.kt` (`:74`) | `userPresentRate: UserPresentRateRequirement? = null` (12 construction sites stay source-compatible) |
| `A/domain/transaction/CreateExpenseResult.kt` (`:10`) | `rateRefusal: RateRefusal? = null` on `ValidationFailed` |
| `A/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt` | flagged branch in conversion block `:529-585`, before dedup (`:587+`); `:554-578` unchanged for unflagged; never `convertAsOf` when flagged (INV-7); flagged snapshot via `roundSnapshot` (INV-7); refusal reuses `CREATE_VALIDATION_FAILED` event shape (`:406-427`), bounded codes. RF-10 (2026-10-23, after money-correctness 1b): conversion block now `:605-674`; unflagged converted/no-rate branch `:630-667` (sentinel triple + one `MISSING_RATE`) is the "unchanged for unflagged" part; dedup `:676+` |
| `A/domain/transaction/validation/TransactionValidator.kt` (`:87-93`) | raw `> 1_000_000` → `CurrencyAmountPolicy.cap`; add `AMOUNT_PRECISION_EXCEEDED` (flagged signal through `validate`/`validateFields`) |
| `A/data/repository/ManualExpenseRepository.kt` (`:114`) | remove raw cap → policy; reject foreign + recurrence (INV-16); entry zone + flag setter (setter in slice b per C-3) |

Raw cap inventory [V]:
| Site | What | Behaviour if OD-1 = A (validator only) |
|---|---|---|
| `TransactionValidator.kt:87` | every TLC create | changes for weak currencies > 1M on: CSV (`CsvExpenseImporter.kt:246`), JSON (`JsonExpenseImporter.kt:125/149`), `LegacyDataMigrationService.kt:272`, `GroupTransactionCoordinator.kt:828`, `BankApiIntegration.kt:663`, batch receipt `ReceiptLifecycleCoordinator.kt:1174`, receipt-scan, manual |
| `AppConfig.kt:12` `MAX_TRANSACTION_AMOUNT` | `NotificationProcessingPipeline.kt:962, 997` (currency unknown) | unchanged |
| `NotificationProcessingPipeline.kt:1135` | suppresses auto-accept > 1M | unchanged (weak-currency > 1M → review) |
| `AppParserRegistry.kt:69` | `require(amount <= 1_000_000)` in init (parse throws) | unchanged (JPY/KRW > 1M still throws) |
| `ReviewQueueRepository.kt:157` | review-approval cap; `resolvedCurrency` known `:147-154` | unchanged (approval refused > 1M while TLC accepts) |
| `AmountUtils.kt:153` `isValidAmount` | no main callers; tests only | no change (delegating breaks pinned tests) |
| `ManualExpenseRepository.kt:114` | manual cap | removed → policy |
| `AddExpenseViewModel.kt:297` | manual cap | removed → policy (slice b) |
- **RF-7 (new):** `ReceiptParser.kt:690` rejects OCR amounts > 50,000 (private `isValidAmount`) → JPY/KRW/HUF/ISK receipt totals silently dropped before review → route to receipt owner.
- 12 `CreateExpenseRequest(` sites source-compatible (Json ×2, Csv, LegacyDataMigration, GroupTransactionCoordinator, ManualExpenseRepository, NotificationProcessingPipeline, ReviewQueueRepository ×2, ReceiptScanViewModel `:1253`, BankApiIntegration, ReceiptLifecycleCoordinator `:1174`). `ValidationFailed` matched in 10 files (branch form) → defaulted field doesn't break them; unflagged callers never receive `rateRefusal` (INV-5).
- DAOs/schema: none; rates read-only via `CurrencyConverter`/`ExchangeRateDao` (`getRateAsOf` exists); home currency via `resolveHomeCurrency()`; no network at preview/save; refresh only existing `CurrencyRatesRepository.refresh(homeCurrency)` (`CurrencyRatesRepository.kt:13`; URL `CurrencyRatesRepositoryImpl.kt:39`; no consent gate today → R23 part 1 prerequisite; precedent `CurrencyManagementViewModel.kt:238-243`).
- Engines (ENGINE_INTERACTION_MAP `:14`, `:58-76`): **CurrencyConverter CRITICAL**, unchanged (read-only `convertOutcome`) → consumers unaffected by code; only "TransactionLifecycleCoordinator (create-time snapshot)" affected; flagged rows' snapshot may differ from analytics read-time conversion (OD-3). MoneyNormalizationEngine/MoneyAggregate unchanged; refusal → empty post-commit batch.

### (b) Add-expense UI — T3 (money save path + replaced rounding)
| File | Change |
|---|---|
| `A/ui/screens/addexpense/AddExpenseViewModel.kt` | currency state (only `homeCurrency` today `:63`, `:93-95`; saves home `:276-281, :377-381`); remove cap `:297` + rounding `:367-370`; entry-zone capture; preview via evaluator + policy; refusal mapping; hide repeat for foreign. No new flagged `catch` (nl-quick-add A removes KNOWN_VIOLATIONS entry) |
| `A/ui/screens/addexpense/AddExpenseSheet.kt` | control, preview, blocked state, refresh action; home symbol `:202-209`; date-picker zone `:841-855` (RF-2 open) |
| new use case (`A/domain/usecase/…` or `A/domain/currency`) | VM must not touch DAOs (FG-12); evaluator is DAO-free over `CurrencyConverter` |
| `res values{,-el,-es}/strings.xml` | refusal/preview strings; serialise with ai-fixes, nl-quick-add, loan |
| `A/data/repository/ManualExpenseRepository.kt` | flag setter |

### (c) Receipt review — T3 (receipt lifecycle)
| File | Change |
|---|---|
| `A/ui/screens/receiptscan/ReceiptScanViewModel.kt` | has `editCurrency` (`:83`), `updateCurrency` (`:561`); flag setter at request build (`:1253-1266`) + preview; `Unset` for unselectable OCR currency (`:461`); new `SaveReceiptResult` variant (`:164-170`; maps `DuplicateTransactionException` at `:1301`) |
| `A/ui/screens/receiptscan/ReceiptScanScreen.kt` | private `CurrencyPicker` (`:883`, `:1385`) → filter by `CurrencySelectionPolicy` + preview/blocked UI |
| `A/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt` (`:1583-1668`) | today `:1629-1630` maps `ValidationFailed` → `IllegalArgumentException`, transaction commits with no link; add payload-free `RateNotEligibleException` (modelled on `DuplicateTransactionException` `:1668`) when `rateRefusal != null`; do NOT touch batch `:1174-1222` (OD-5) |
Single-receipt path has exactly one production caller (`ReceiptScanViewModel.kt:1269`) [V].

### (d) Loan S11 convergence — T3 (money; edits approved loan plan)
S11 (`loan-installments-plan.md:452-467`) edits AddExpenseSheet/VM (shared with b); `AddExpenseInstallmentsTest.kt` not yet existing. Convergence: S11's EUR/USD control → general control in `mode = INSTALLMENT` (options ∩ {EUR, USD}); `declareWithNewExpense` sets `userPresentRate(entryZone)`; its FX-unavailable refusal = TLC refusal. No extra file here if S11 built after (a)/(b) (OD-4). Loan-plan edits needed: S11 write set, D-8 answer, S6a `AUTO_RECORD_DEFERRED_FX` (RF-3).

## 2. Legal paths
| Operation | Path | New/existing |
|---|---|---|
| Expense create (manual) | `ManualExpenseRepository.addManualExpense` → `createExpenseDbOnlyV2` → TLC | existing + flag |
| Expense create (receipt) | `ReceiptLifecycleCoordinator.createExpenseAndLinkReceipt` → TLC | existing |
| Installment declare | planned plan coordinator → TLC | planned (loan) |
| Rate refresh | `CurrencyRatesRepository.refresh` | existing, user-started |
| Eligibility | `RateEligibilityEvaluator` | new read-only rule owner |
No new write path. §18 text → "Expense Mutations" (`:21`) and "Money / Currency" (`:534`).

## 3. Schema: NONE
No Room version/entity/DAO/migration/snapshot; no `exchange_rates` change or cleanup (INV-22). Migration tests, room-migration-guardian, `db_artifact_sync` change not required. `db_access` expected unchanged (refusal events use TLC's existing `transactionEventDao.insert`, best-effort). Inc 1b: ~38k rows / 90 days + privacy review (not this increment).

## 4. Tests
Extend: `T/domain/transaction/lifecycle/TransactionLifecycleCoordinatorTest.kt` (flagged refusal/success, unflagged regression; AE-3, 13, 14, 21, 22, 29); `T/scenarios/TransactionLifecycleCoordinatorDbContractTest.kt` (refusal writes exactly two events, no expense); `T/domain/receipt/lifecycle/ReceiptLifecycleCoordinatorTest.kt` (`RateNotEligibleException`; receipt/links/events unchanged; AE-24); `AddExpenseViewModelTest.kt` + `AddExpenseViewModelStressTest.kt` (rounding/cap expectations change); `ReceiptScanViewModelTest.kt` + `ReceiptScanViewModelStressTest.kt`; `T/domain/currency/CurrencyConversionTest.kt`; `T/data/repository/CurrencyRatesRepositoryImplTest.kt`; `CancellationSafetyArchitectureGuardTest.kt`.
New: `T/domain/currency/CurrencyAmountPolicyTest.kt` (all 28 catalog digits + cap table); `CurrencySelectionPolicyTest.kt`; `RateEligibilityEvaluatorTest.kt` (AE-1..12, 25, 34, 37; DST AE-10; `America/New_York` AE-11); `T/domain/transaction/validation/TransactionValidatorTest.kt` (none exists today; AE-18/19; per-caller cap relaxation); `T/data/repository/ManualExpenseRepositoryTest.kt` (INV-16, flag); architecture `T/architecture/ManualEntryCurrencySetterAllowListGuardTest.kt` (AE-35; `architecture-contracts` + `trusted`). Optional instrumented ICU digits check.
Guards: one new JUnit architecture test (setter allow-list) = sign-off item (nl-quick-add G2-6 precedent); nothing weakened; no baseline/allowlist edit; nothing in `scripts/ci/guard_registry.py`.
Money guards (`scripts/verify_money_boundaries.py`): G-MONEY-11 forbids `CurrencyCode("")` / blank displayCurrency sentinel; G-MONEY-19/18 `StaleRatePolicy.None` only with `RateBasis.TRANSACTION_DATE` (`:245-248`); G-MONEY-16 dashboard only. Time guard (`verify_time_boundaries.py`): evaluator must not call `now()`/`Clock.systemDefaultZone`.

## 5. FG in scope
FG-10 (a, b, c: CE rethrow, AE-33); FG-12 (b, c: no DAO in VMs); FG-13 (a: db_access unchanged); FG-14 (a: refusal events via TLC best-effort path, no new `LifecycleEventType`); FG-15 (c: receipt links untouched); FG-16 (a, b, c: INV-19 bounded codes; RF-1 `:397`); FG-17 (a, b: money/time); FG-18 (a: DI); FG-19, FG-20, FG-26 (all). FG-21 not touched.

## 6. Architecture docs (FG-26; land with symbols)
`LEGAL_PATHS.md` Expense Mutations (`:21`) §18 "USER-PRESENT RATE REQUIREMENT" + FORBIDDEN lines; Money/Currency (`:534`) "ELIGIBLE RATE → RateEligibilityEvaluator", "AMOUNT digits/cap → CurrencyAmountPolicy". `ENGINE_INTERACTION_MAP.md` `:14` CurrencyConverter row note; `:58-76` TLC entry note; new RateEligibilityEvaluator row (tier follows CurrencyConverter CRITICAL; read-only). `CODEBASE_SEGMENTS.md` Segment 16 (`:353-397`) three new files; Segment 9 (`:243`) request/result fields.

## 7. Risk tiers
| Part | Tier | Gates |
|---|---|---|
| (a) core | **T3** (money, TLC + result contracts, OD-1 cap change, guard addition) | planner-first, reviewer-strict, architecture-guardian, privacy-security-guardian (RF-1, INV-19) |
| (b) add-expense UI | **T3** (replaces rounding on all manual saves; save path; currency state) | reviewer-strict, architecture-guardian |
| (c) receipt review | **T3** (receipt lifecycle + exception contract) | reviewer-strict, architecture-guardian |
| (d) loan convergence | **T3** (cross-feature money; edits approved loan plan) | reviewer-strict, architecture-guardian |
room-migration-guardian not needed; no workers.

## 8. Validation (validation-runner only, serial; `-TestFilter`/`-Shard`, not the loan plan's `-Filter`/`-ShardName`)
Shard coverage: `domain.currency.*` → `domain-a-m`; `domain.transaction.*`, `domain.receipt.*`, `domain.usecase.*` → `domain-n-z`; `data.*` → `data`; `ui.*` → `runtime-ui`; `architecture.*` → `architecture-contracts` (+ trusted). No `test-shards.json` edit.
- (a): `targeted-unit-test` `"*CurrencyAmountPolicy*"`, `"*CurrencySelectionPolicy*"`, `"*RateEligibilityEvaluator*"`, `"*TransactionValidator*"`, `"*TransactionLifecycleCoordinator*"`, `"*ManualExpenseRepository*"`, `"*CurrencyConver*"`; `unit-test-shard` `domain-a-m`, `domain-n-z`, `data`, `architecture-contracts`; `registered-guard` `db_access` then `db_artifact_sync`; `static-guards`.
- (b): `"*AddExpenseViewModel*"`, `"*CancellationSafetyArchitectureGuardTest*"`, `"*ManualEntryCurrencySetterAllowListGuardTest*"`; shards `runtime-ui`, `architecture-contracts`; `lint`; `static-guards`.
- (c): `"*ReceiptScanViewModel*"`, `"*ReceiptLifecycleCoordinator*"`, `"*ReceiptLinkServiceNestedFailure*"`; shards `runtime-ui`, `domain-n-z`; `registered-guard db_access`; `lint`.
- (d): `"*AddExpense*"` + loan's `*TransactionLifecycleCoordinator*`; shard `runtime-ui`; `lint`.
- Bundle: `static-guards`, `unit-tests`, `lint`; `app-check` if gate requires.

## 9. Conflicts and sequencing
- Release (`release-1.0-assurance-handoff.md:236-256`): **R16** (comma decimals incl. R16c) edits same VM/screens → start after R16 merges (INV-13 assumes post-R16 parser). **R23 part 1** hard prerequisite for UI + refresh (else INV-20 has no gate). **R21 V-C4** future-consumer note only.
- nl-quick-add: A, D, C, F edit AddExpense VM/Sheet; its S0 requires manual-entry-currency not in flight on VM/Sheet (N5) and loan S11 not started; also edits strings ×3, LEGAL_PATHS, ENGINE map, SEGMENTS, `test-shards.json` (B only).
- ai-fixes: no AddExpense/ReceiptScan/TLC/`CreateExpenseRequest`; shares only strings, LEGAL_PATHS, SEGMENTS, ENGINE map.
- loan-installments: **S3** (`:178-205`) edits TLC + `ExpenseDao` (`setSpendingExclusionDbOnly`, amount/currency edit lock on flagged rows); **S6a** adds `ExpenseSource.INSTALLMENT_PLAN` create → TLC exhaustive `when (request.source)` `:508-526` must change; **S11** edits VM/Sheet; Room v152 slice.
- money-correctness-1.0.x (not specced at mapping time; 1b landed on this branch, RF-10 2026-10-23): owns the TLC unflagged no-rate branch (was `:554-578`; now `:630-667`, sentinel triple `0.0/""/0.0` + one bounded `MISSING_RATE`) + snapshot rounding (`roundSnapshot`) + digits accessor (`CurrencyFractionDigits.of`); MEC flagged branch in the same block (was `:529-585`; now `:605-674`).

**Recommended global post-1.0 order:**
1. Release 1.0 batches incl. R16, R23 part 1.
2. ai-fixes (owner order; touches only shared docs/strings among the hot files).
3. money-correctness-1.0.x, then MEC (a) (same TLC block). If money-correctness isn't specced by then, MEC (a) first (adds a flagged branch beside the existing one; money-correctness rebases) — owner choice at Gate 2.
4. MEC (a) core (no UI, no production setter, C-3) → prerequisite for loan S11 (OD-4).
5. loan S1..S10 on non-shared files in parallel with the above; S3, S6a serialised against (a) and money-correctness (TLC).
6. nl-quick-add inc 1 (A, D, C, F) — before MEC (b) and loan S11.
7. MEC (b) add-expense UI with setter.
8. MEC (c) receipt review (after a; after b recommended).
9. MEC (d) / loan S11 built directly on the general control (no interim EUR/USD control).
10. nl-quick-add inc 1b after (b).
11. MEC inc 1b (ECB history) with R23 setting + privacy review.
Shared-file owners by phase: AddExpense VM/Sheet: nl-quick-add A/D/C/F → MEC (b) → loan S11. TLC/`CreateExpenseRequest`/`CreateExpenseResult`: money-correctness, MEC (a), loan S3/S6a. ReceiptScan VM + `ReceiptLifecycleCoordinator`: MEC (c) only (R16c earlier). `test-shards.json`: nl-quick-add B only. LEGAL_PATHS, strings: everyone, serialised.

## 10. Open items for Gate 2
- **OD-1 cap scope** (merge C-2): A (validator, manual repo, VM) / **A + `ReviewQueueRepository.kt:157` (rec; currency known there)** / flagged-only. Route `AppParserRegistry.kt:69`, `NotificationProcessingPipeline.kt:962/997/1135`, `AppConfig.MAX_TRANSACTION_AMOUNT` to parser/notification owners. Leave `AmountUtils.isValidAmount`.
- OD-2 BGN/RUB feature-local list (rec). OD-3 snapshot vs analytics divergence accepted, aligned in money-correctness. OD-4 core before loan S11 (rec; loan-plan edit). OD-5 batch receipt review unflagged.
- **S-1** setter allow-list architecture test (guard addition). **S-2** removing VM HALF_UP rounding: home-currency "12.345" now rejected, not rounded. **S-3** core slice ships setter-less. **S-4** editing approved loan plan (S11, S6a, D-8, `AUTO_RECORD_DEFERRED_FX`). **S-5** RF-1 (merchant/amount/currency in `CREATE_ATTEMPTED` reason, TLC `:397`; refused attempts add rows) — privacy guardian: fix in (a) or route to money-correctness. **S-6** refusal event commits while expense doesn't — "audit event commits, no row" vs rollback.
- Routed: RF-2 date-picker zone (`AddExpenseSheet.kt:841-855`) → debugger before (b); RF-3 loan planner; RF-4 done (§1); RF-5 money-correctness (resolved in 1b: `Timber.w` replaced by one bounded `MISSING_RATE`; RF-10); RF-6 `receipt.parsedDate` zone (`ReceiptScanViewModel.kt:416`) → slice (c) check; **RF-7** `ReceiptParser.kt:690` 50,000 OCR cap → receipt owner.

## 11. Unverified
Controlled-diagnostic sink API for `MANUAL_SAVE_BLOCKED_NO_RATE` (`TLC:549` emitter seen, signature unread); `myShareAmount` currency semantics; add-expense edit-mode currency (INV-23); receipt review state after process death; list converted-amount formatting call site (AE-38); `receipt.parsedDate` zone; on-device ICU vs JVM `java.util.Currency` digits; backup-restored non-ECB rate rows; whether `transaction_events` is exported/backed up (RF-1); `ux-flows.md` content.
