# Feature plan: money-correctness (inc 1a read side + inc 1b write side)

Author: feature-planner (stage 6, read-only, 13 tool calls). Persisted by the orchestrator. Date 2026-10-16.
Status: AWAITING OWNER PLAN APPROVAL (Gate 3). Post-1.0 only. No `app/` edits until S0 passes.

- Risk tier: T3 every slice except 1a-4 (T2, home-switch warning) and 1b-3 (tests + release note only) (impact-map §7, G2-16).
- Basis: `docs/features/money-correctness/impact-map.md` (trusted), `gate2-decisions.md` (G2-1..G2-16 DECIDED = Recommended), `domain-spec.md` (INV-1..22, AE-1..26, D-1..9, OD-1/2 resolved by G2-1/G2-2), `ux-flows.md` §2–§8. Format per `manual-entry-currency-plan.md`.
- Schema: NONE (no version bump, migration, snapshot, index, `migration-tests`); any schema/index need → MC-N4.
- Legal paths: no new write path. CV compare = new read-only path owned by `ComparableHomeValue`; snapshots stay on TLC (`ExpenseDao.insertAtomic()` create, full-row update) — 1b adds rounding + sentinel; home-currency write stays `setHomeCurrency`, now behind a confirmation in `CurrencyManagementViewModel`; Rescue keeps its structural exception, same shape.
- Roots: `A` = `app/src/main/java/com/yourname/expensetracker`; `T` = `app/src/test/java/com/yourname/expensetracker`; `res` = `app/src/main/res/values{,-el,-es}/strings.xml`; Docs = `docs/architecture/{LEGAL_PATHS,ENGINE_INTERACTION_MAP,CODEBASE_SEGMENTS}.md`. RS reviewer-strict, AG architecture-guardian, PSG privacy-security-guardian.
- Validation: validation-runner only, serial: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile <p> [-TestFilter "<f>"] [-Shard <s>] [-GuardId <g>]`; poll; RUNNING ≠ failure; missing/stale/errored ≠ PASS; one `-TestFilter` per run unless S0 confirms multi-pattern; no tests in unsharded `com.yourname.expensetracker.currency` (G2-14).
- Global order: Release 1.0 (R16/R16c/R17/R19/R23 p1 merged from `release-1.0-wt`) → ai-fixes → **S0 → 1a-1 → 1a-2 → 1a-3b → 1a-4 → 1a-5 → 1a-G → 1b-1 → 1b-2 (∥ 1b-3) → 1b-G/Z** → MEC S0/A1/A2 → loan S1..S10. MEC A2 must not start until 1b fully landed (MEC N7); MEC amendment for G2-1 recorded.

## Slice table
| ID | Title | Tier | Prod files | Depends on | Reviewers | Key FG |
|---|---|---|---|---|---|---|
| S0 | Pre-flight (no `app/` edits), query-plan measurement, baselines | — | 0 | Release 1.0 accepted + `release-1.0-wt` merged; ai-fixes landed | — | — |
| 1a-1 | CV rule owner + SQL mirror const + shared fixture + parity (CV/bounds) + assistant golden test (pins pre-1a SQL) + docs | T3 | 2 new + 1 mod + docs | S0 | RS + AG + PSG | 17, 19, 20, 26 |
| 1a-2 | `ExpenseRepository` builder: required `AmountSemantics`, home-comparable WHERE/ORDER BY/tiebreak, wildcard escape in Transactions modes, new count `@RawQuery` + yml row (G2-3) | T3 | 2 mod + 1 config (+1 VM line) | 1a-1 | RS + AG + PSG | 12, 13, 17, 19, 20, 26 |
| 1a-3a | Typed read-time home-currency observable (interface default; bounded codes, G2-9) | T3 | 1 new + 2 mod | S0 (code ∥ 1a-1/1a-2; validations serial) | RS + AG + PSG | 10, 16, 17, 19, 20 |
| 1a-3b | Transactions VM + Screen + FilterSheet + `Expense.normalizedEffectiveAmount` delegate + strings | T3 | 4–5 + res | 1a-2, 1a-3a | RS + AG + PSG | 10, 12, 16, 17, 19, 20, 26 |
| 1a-4 | Home-switch warning (Loading-aware CMVM state machine + dialog + optional predicted count) | T2 | 2 + res | 1a-2, 1a-3a, 1a-3b (res order) | cheap reviewer + PSG | 10, 12, 16, 19, 20, 26 |
| 1a-5 | Rescue M5: nullable H, pure value-choice fn; method names + guard keys kept | T3 | 1 | S0 (code ∥ any 1a; docs serialised) | RS + AG | 13, 17, 19, 20, 26 |
| 1a-G | 1a part gate (validation + fresh RS) | — | 0 | 1a-1..1a-5 | RS (fresh) | all |
| 1b-1 | Single fraction-digits accessor + `roundSnapshot` (HALF_EVEN); `SourceCurrencyAllocation` delegates | T3 | 2 new + 1 mod + docs | 1a-G | RS + AG + PSG | 17, 19, 20, 26 |
| 1b-2 | TLC: no-rate sentinel + `MISSING_RATE` (create + update; from precompute outside txn), converted rounding, Timber removal, `CurrencyConverter.kt:231` log fix (G2-8) | T3 | 2 mod + docs | 1b-1; MEC A2 + loan S3/S6a not in flight | RS + AG + PSG | 10, 13, 14, 16, 17, 19, 20, 26 |
| 1b-3 | Export/accounting pass-through tests (AE-25) + release note (G2-12); no prod code | tests only | 0 | 1a-G (∥ 1b-2) | cheap reviewer; covered by 1b RS | 19, 20 |
| 1b-G / Z | 1b part gate + closeout (inventory regen, handoff, MEC RF-10 notice) | — | 0 | 1b-1..1b-3 | RS final (fresh) | all |

Sequential-only files: `ExpenseDao.kt` (1a-1 → 1a-2; vs loan schema bump); `TransactionsViewModel.kt` + tests (1a-2 arity → 1a-3b); parity test + fixture (1a-1 → 1a-2); `res` ×3 (1a-3b → 1a-4); Docs ×3 (1a-1 → 1a-2 → 1a-3b → 1a-4 → 1a-5 → 1b-1 → 1b-2; vs MEC A1/A2, loan); `TransactionLifecycleCoordinator.kt` (1b-2 only; vs MEC A2, loan S3/S6a); `CurrencySettingsRepository*` (1a-3a only).
Why split: 1a-3 → 3a (data/domain, behaviour-neutral, parallel-capable) + 3b (UI) to stay ≤ ~8 prod files; 1b-3 disjoint from 1b-2.
Releasability: behaviour-neutral = 1a-1, 1a-2 (interim `RawEffective` at the single call site), 1a-3a, 1a-5 (disabled path), 1b-1. **1a ships as a unit after 1a-G** (3b without 4 would let a home switch silently unrank history).

## Slices

### S0 — Pre-flight (no production edits)
1. Base: Release 1.0 accepted; `release-1.0-wt` merged into `bug-fixes` (R16, R16c, R17, R19, R23 p1); ai-fixes landed (incl. B1a `db_ownership_policy.yml` regeneration); branch `feature/money-correctness`.
2. In-flight: MEC S0/A1/A2 not started (any MEC slice on TLC `:529-585` → MC-N2); loan S3/S6a not in flight on TLC/`ExpenseDao`; record loan schema 151→152 status (`ExpenseDao` companion `:26-89` textual merge).
3. Re-verify lines (release worktree edited Transactions files), record deltas, material drift → MC-N1: `TransactionsViewModel.kt:201-204, 211, 246-249, 283, 292-293, 443-474, 663, 779-803, 813-831, 833-848, 889-914`; `TransactionsScreen.kt:135, 285-303, 522-541, 959-1023, 2136`; `TransactionFilterSheet.kt:280-288` (R16c); `ExpenseRepository.kt:49-73, 197-228, 238-301, 303-400`; `ExpenseDao.kt:26-89`; `CurrencySettingsRepositoryImpl.kt:42-45, 104-118`; `CurrencyManagementViewModel.kt:27-29, 66-72, 148-182`; `CurrencyManagementScreen.kt:150-157, 178, 315, 653`; `FinancialRescueCoordinator.kt:92, 319, 363, 418-420`; `RescueActivity.kt:47-49`; `Expense.kt:35-51, 146-148, 176-185`; TLC `:295-324, 529-585, 566-585, 988, 1002-1023, 1099-1126, 2545-2547`; `CurrencyConverter.kt:231`; `DiagnosticReasonCode.kt:17`; `AccountingExporters.kt:135, 189`; `ExpenseExportMapper.kt:44-47`; `ExportOptionsViewModel.kt:690-693, 755-760`; `strings.xml:229`; `LEGAL_PATHS.md:24-61, 534-552`; `CODEBASE_SEGMENTS.md:357-381`.
4. G2-13 query plan + timing on synthetic DB (10^4 and 10^5 rows from current Room schema JSON `app/schemas/...`, CREATE + indices): `EXPLAIN QUERY PLAN` + timing for ALL page (LIMIT 50, OFFSET 0/5000) under AMOUNT_DESC/ASC with min/max; `COUNT(*) … AND CV IS NULL`; DATE_DESC/ASC with `e.id` tiebreak (must still use `date` index). Method: sqlite3 CLI in scratchpad (debugger role; no Gradle, no repo writes); fallback D-4. Proposed thresholds (owner may adjust): page or count > 200 ms at 10^5 → 1a-3b applies ≈300 ms debounce + lazy count (G2-13); date plan losing index or still slow → MC-N4 (no index).
5. Confirm `Expense.id` is `INTEGER PRIMARY KEY` (rowid alias).
6. Diagnostics: `DiagnosticReasonCode` exhaustive `when` usages (only if G2-7 reuse fails); sink API behind `emitHomeCurrencyUnavailableDiagnosticBestEffort` — does `MISSING_RATE` accept metadata `{operation, reason}`, `stage`, `entityId`; registry/allow-list (→ MC-N9).
7. Guard config: does a new DAO `@RawQuery` need more than the G2-3 yml row (`db_ownership_policy.yml`, `db_artifact_sync` artifacts, `db_access_v2.json`)? does db_access evidence hash TLC/Rescue bodies? do PII-logging guard baselines reference TLC `:573-577` or `CurrencyConverter.kt:231`? (→ MC-N3, MC-N11, H-3)
8. Layering: any guard forbidding `data/database/entity` → `domain/core/money` import (G2-5 delegate; → MC-N7).
9. Tests: non-relaxed mockk of `CurrencySettingsRepository` in `TransactionsViewModelTest`; the 14 `any()` arity sites; TLC test `:611` (`9.2`); exhaustive grep for tests pinning `baseCurrency "EUR"` on no-rate rows incl. `T/scenarios/*DbContractTest`, recurring, importer (→ MC-N10); `CurrencySettingsRepositoryImpl` DataStore test harness; `SavingsSweepAcceptanceCoordinatorTest` Robolectric Room seeding in `data` shard (→ MC-N12).
10. Converter + dialog: `CurrencyConverter.kt` + tests paths (not unsharded); CMVM ctor/DI, whether injecting `ExpenseRepository` is a plain `@Inject` change (→ MC-N13).
11. Strings: plurals location; `action_got_it` exists?; key at `strings.xml:229`; MissingTranslation lint config; Spanish `many` plural.
12. Shards/runner: `data` covers `data.repository.*`, `data.rescue.*`; `runtime-ui` covers `ui.screens.*`; multi-pattern `-TestFilter`; `compile` profile; release-notes location.
13. Baselines (serial). Required: `static-guards`; `registered-guard -GuardId db_access` then `db_artifact_sync`; `lint`; `unit-test-shard` `domain-a-m`, `domain-n-z`, `data`, `runtime-ui`, `architecture-contracts`. Recommended: `targeted-unit-test` `"*ExpenseRepositoryTest*"`, `"*ExecuteFinancialQueryUseCaseTest*"`, `"*TransactionsViewModel*"`, `"*TransactionLifecycleCoordinator*"`, `"*CurrencyManagementViewModel*"`.
- Done when: base confirmed; deltas, query plan, timings in handoff; D-items answered; baselines recorded; fired MC-N items escalated.

### 1a-1 — CV rule owner + SQL mirror + parity + assistant golden (T3)
- INV-1, 2, 3, 4, 5 (Kotlin comparator), 6, 7 (CV membership/bounds), 11 (pinned before any builder change). AE-1..5, 9, 10, 17 (domain + SQL-constant parity), AE-13 (golden SQL text + args, pre-1a). D-1, D-2, D-7.
- Source: NEW `A/domain/core/money/ComparableHomeValue.kt` (object: `comparableValue(e, home: CurrencyCode)` branches 1–4 in order, `-0.0`→`0.0`; ownership-adjusted `snapshotValue(e)` (branch 3 without `== H`, for date header); INV-5 comparator (None last, CV direction, `date DESC`, `id DESC`); `withinBounds`; pure). NEW `A/domain/core/money/ComparableValue.kt` (sealed `Value`/`None` + `NoComparableValueReason`). F5: no `CurrencyCode(` text in `domain/core/**` incl. KDoc (use `CurrencyCode.parse`/`parseOr`/`CurrencyCode.EUR`). `ExpenseDao.kt` companion: `COMPARABLE_HOME_VALUE_E_SQL` (spec §2.3; may interpolate `EFFECTIVE_AMOUNT_E_SQL`), exactly two `?` both H; KDoc points to owner; no `@Query`/`@RawQuery`; no `NULLS LAST`.
- Docs: `LEGAL_PATHS.md` Money/Currency `:534-552` §15 COMPARE block (owner + SQL mirror + parity test) + FORBIDDEN "raw-amount fallback", "interpolating H into SQL"; `ENGINE_INTERACTION_MAP.md` new `ComparableHomeValue` row; `CODEBASE_SEGMENTS.md` Segment 16 `:357-381` two files.
- Tests: NEW `T/domain/core/money/ComparableHomeValueFixture.kt` (AE-1..10, 15, 16, 17, AE-26 read part; owned by rule); NEW `ComparableHomeValueTest.kt` (domain-a-m; branch order, reasons, `-0.0`, lowercase `usd`, negatives, AE-6 two H values); NEW `T/data/repository/ComparableHomeValueSqlParityTest.kt` (Robolectric `@Config(sdk=[28])` + `Room.inMemoryDatabaseBuilder`, data shard, precedent `SavingsSweepAcceptanceCoordinatorTest.kt:39-49`; `SELECT id, CV` over the constant; Value/NULL membership, values, inclusive bounds vs Kotlin owner; H never interpolated, `?` count 2, arg order); NEW `T/data/repository/ExpenseRepositoryAssistantSqlGoldenTest.kt` (exact SQL + args from mocked DAO for `getAssistantExpensesFiltered`/`CountFiltered` with/without min/max/search/category/type; green on unchanged code).
- Read-only; no DAO write; assistant untouched. Depends S0. Gates RS, AG (domain/core rules, mirror placement), PSG (no content in reasons).
- Validation: `targeted-unit-test` `"*ComparableHomeValue*"`, `"*ExpenseRepositoryAssistantSqlGolden*"`, `"*Engine5PrimitiveGuardTest*"`, `"*MoneyBoundaryGuardTest*"`; shards `domain-a-m`, `data`; `registered-guard` `db_access` then `db_artifact_sync` (unchanged); `static-guards`.
- Done when: AEs green on both mirrors; golden green on pre-1a code; guards unchanged; docs reference existing symbols.

### 1a-2 — Shared builder home-comparable mode + count query (T3)
- INV-2, 4, 5, 6, 7 (full SQL order), 8 (ALL SQL count), 11. AE-1, 2, 4, 6, 8 (offsets 0/50/100), 9, 10, 13 (golden unchanged), 15, 16, 17, 26 (`max=18.40` excludes `18.400000000000002`). D-4, D-5. G2-3, G2-4, G2-5 (enum kept).
- Source: `ExpenseRepository.kt`: `AmountSemantics` sealed `RawEffective`, `HomeComparable(home: CurrencyCode)`, and (H-1) `AmountOpsDisabled`; `buildExpenseDynamicQueryParts` (`:303-400`) required param, no default; `RawEffective` byte-identical (WHERE `:356-364`, `SortOrder.sql`, COUNT guard `:386`); `HomeComparable`: §2.3 WHERE `CV >= ?`/`CV <= ?`, four ORDER BY templates with `(CV IS NULL) ASC` + `date`/`id` tiebreak, args in textual `?` order (WHERE, ORDER BY, LIMIT, OFFSET); search `LIKE ? ESCAPE '\'` escaping `%`, `_`, `\` **only** in non-`RawEffective` modes (G2-4); `getExpensesPagedDynamic` (`:197-228`) required `amountSemantics`; NEW `countExpensesWithoutHomeValue(filter, home: CurrencyCode): Int` (same WHERE without min/max + `CV IS NULL`; sort ignored; used by VM ALL tab and CMVM candidate H, empty filter); `SortOrder.AMOUNT_*.sql` kept with KDoc "assistant-only (RawEffective)" (G2-5). `ExpenseDao.kt`: NEW `@RawQuery suspend fun countExpensesWithoutHomeValueDynamic(query: SupportSQLiteQuery): Int`. `config/guards/db_raw_query_classification.yml`: one `classification: read` row (G2-3; nothing else). `TransactionsViewModel.kt:816`: interim `AmountSemantics.RawEffective` (identical behaviour; replaced in 1a-3b). Docs: LEGAL_PATHS COMPARE names the count method; ENGINE map builder-semantics note.
- Tests: update `ExpenseRepositoryTest.kt:253-276, 290-309, 317-361` (new param; strengthen `:274` to full ORDER BY); extend parity test (builder-driven AE-1..10, 15, 16, 17, 26; AE-8 paging concatenation; counts AE-1 (1), AE-2 (0), AE-6 (1/1), AE-9 (0); wildcard escape; `AmountOpsDisabled` emits no amount SQL/args); mechanical arity `TransactionsViewModelTest.kt:191, 202`, `TransactionsViewModelStressTest.kt:65, 176, 185, 200, 210, 228, 286`; `ExpenseRepositoryAssistantSqlGoldenTest.kt` + `ExecuteFinancialQueryUseCaseTest.kt` **unchanged** (any edit → MC-N5).
- Read-only; `ExecuteFinancialQueryUseCase` untouched (F1); new `@RawQuery` read-classified. Depends 1a-1. Gates RS, AG (shared builder, assistant isolation), PSG (count integer only); RMG not needed.
- Validation: `targeted-unit-test` `"*ExpenseRepositoryTest*"`, `"*ComparableHomeValue*"`, `"*ExpenseRepositoryAssistantSqlGolden*"`, `"*ExecuteFinancialQueryUseCaseTest*"`, `"*TransactionsViewModel*"`; shards `data`, `domain-n-z`, `runtime-ui`; `db_access` then `db_artifact_sync` (only expected new-method delta cleared by yml row; else MC-N3); `static-guards`.
- Done when: golden + use-case tests byte-identical and green; parity green; guards clean.

### 1a-3a — Typed read-time home currency (T3, behaviour-neutral)
- §1.4; INV-9 (source); G2-9/RF-11 (bounded codes); AE-11 (repository part: no raw `Failed.reason` crosses the interface).
- Source: NEW `A/domain/currency/ReadHomeCurrencyState.kt` (`Loading`, `Available(code: CurrencyCode, firstRunDefault)`, `Unavailable(ReadHomeFailure)`; `ReadHomeFailure { READ_ERROR, TIMEOUT, INVALID_CODE }`). `CurrencySettingsRepository.kt`: `observeReadHomeCurrency(): Flow<ReadHomeCurrencyState>` (provisional) with **interface default body** (maps `homeCurrency()`; invalid → `INVALID_CODE`; error → `READ_ERROR`; CE rethrown) → six fakes compile unchanged. `CurrencySettingsRepositoryImpl.kt` (`:42-45, :104-118`): override reading DataStore prefs directly; unset → `Available(EUR, firstRunDefault=true)`; never emits Loading/TIMEOUT (consumer applies).
- Tests: NEW `T/data/repository/CurrencySettingsRepositoryImplReadHomeTest.kt` (unset, valid, invalid, error; no raw text). `HomeCurrencyResolutionTest` + fakes unchanged (`AnalyticsTestCompat.kt:82`, TLC test `:651`, `HomeCurrencyResolutionTest.kt:37/56/75`, `CanonicalMultiCurrencyFixture.kt:340`, `CurrencyRatesRepositoryImplTest.kt:400`).
- Read-only interface extension; `setHomeCurrency` untouched; docs deferred to 1a-3b. Code ∥ 1a-1/1a-2. Gates RS, AG (interface default, DI), PSG.
- Validation: `"*CurrencySettingsRepositoryImpl*"`, `"*HomeCurrencyResolutionTest*"`, `"*CurrencyRatesRepositoryImplTest*"`; shards `data`, `domain-a-m`; `static-guards`.
- Done when: fakes compile unchanged; bounded mapping + CE propagation tested.

### 1a-3b — Transactions VM + UI on the rule owner (T3, user-visible)
- INV-2, 5, 6, 7 (MONTH mirror), 8, 9, 10, 19, 22. AE-1 (MONTH), 6, 8 (VM paging), 11 (incl. 5 s TIMEOUT under virtual time; no arg equals a placeholder), 12, 15 (VM smoke), 16. §2.4 date header; §5.1. G2-5, G2-10 (`CountUnavailable`, filtered-empty, banner currency), G2-13 (debounce/lazy if S0 says).
- Source: `TransactionsViewModel.kt`: remove `_homeCurrency` (`:201-204`); `ReadHomeCurrencyState` (Loading prepended, 5 s TIMEOUT), `AmountOrderingState`, `TransactionsAmountUi` (`Inactive`/`Ready`/`CountPending`/`CountUnavailable`/`HomeLoading`/`HomeUnavailable`, filtered-empty flag); H state in both combines (`:211`, `:283`); in-memory sort (`:292-293`) + filter (`:889-904`) via `ComparableHomeValue`; ALL `loadPagedExpensesPage` (`:813-831`) passes `HomeComparable(H)` or `AmountOpsDisabled` in Loading/Unavailable (H-1), interim `RawEffective` removed; INV-10 query key `(H, sort, filters, search)` on `loadMore` (`:443-474`), initial load and count, stale discarded; H change resets paging; `refreshPagedExpensesAfterMutation` (`:833-848`) recomputes count; time-range tabs count in memory over full period list; collectors rethrow CE. `TransactionsScreen.kt`: `:135` retargeted; sort menu `:285-303` amount items disabled with hint + content description; notice (`Role.Button`, polite live region); divider `heading()`; explanation dialog ("Got it"); paused/Loading notice + Try Again; banner "Min: EUR 15.00" (+ " (paused)"); filtered-empty variant of `EmptyTransactionsState` (`:959-1023`); header `:522-541` via delegate. `TransactionFilterSheet.kt` (`:280-288`): disabled-state hint. `Expense.kt:176-185`: `normalizedEffectiveAmount` delegates to `ComparableHomeValue.snapshotValue` (G2-5; layering forbids → MC-N7). Optional `TransactionsAmountUi.kt`. `res` ×3: `transactions_amount_*`, `transactions_sort_amount_disabled_hint`, `transactions_filter_min/max_money_format`, `transactions_filter_paused_suffix`, `transactions_empty_filtered_*`, `action_got_it` if absent (ux §8); EN/el/es; Spanish `many`; owner reviews Greek. Docs: LEGAL_PATHS COMPARE "H from typed read-time resolution `observeReadHomeCurrency`, bound as parameter; Loading/Unavailable → amount ops disabled" + FORBIDDEN "amount ops against a placeholder home"; ENGINE map typed observable row + VM consumer; SEGMENTS Segment 16 `ReadHomeCurrencyState.kt`.
- Tests: NEW `T/ui/screens/transactions/TransactionsViewModelAmountOrderingTest.kt` (runtime-ui; AE-1, 6, 8, 11, 12, 15, 16; INV-9/10; count lifecycle; `CountUnavailable`; CE; VM never passes `RawEffective`); semantic updates `TransactionsViewModelTest.kt`, `TransactionsViewModelStressTest.kt` (stub typed observable).
- VM → repository only (FG-12); no writes. Depends 1a-2, 1a-3a. Gates RS, AG, PSG (INV-22).
- Validation: `"*TransactionsViewModel*"`, `"*ComparableHomeValue*"`, `"*Engine5PrimitiveGuardTest*"`; shards `runtime-ui`, `architecture-contracts`; `static-guards`; `lint`.
- Done when: no raw fallback in any Transactions amount path (RS grep `normalizedEffectiveAmount`/`effectiveAmount` in sort/filter/count); AEs green; lint clean, no baseline change.

### 1a-4 — Home-switch warning (T2)
- INV-12, 22; AE-14; D-9; §5.2; G2-6 (Loading-aware), G2-10 (UX-Q-1 optional count with candidate H, no filters; UX-Q-3 "for now").
- Source: `CurrencyManagementViewModel.kt`: replace `"EUR"` placeholder (`:27-29, 66-72`) with `observeReadHomeCurrency`; `HomeSwitchUi` `Idle`/`PendingConfirm(target, predictedCount: Int?)`/`Writing`/`Error(WRITE_FAILED | UNSUPPORTED_HOME_CURRENCY)`; `request`, `confirm` (exactly one `setHomeCurrency`, then today's invalidation), `cancel`; second confirm while Writing ignored; CE rethrown; predicted count via `ExpenseRepository.countExpensesWithoutHomeValue(empty, candidate)`, omitted on failure (inject `ExpenseRepository`, H-2). `CurrencyManagementScreen.kt`: `:178`, `:315` call `request`; dialog per `ConversionDialog` (`:653`); error banner `:150-157`. `res` ×3 `currency_home_switch_*`. Docs: LEGAL_PATHS Money/Currency "home write = `setHomeCurrency` only via CMVM confirm".
- Tests: extend `CurrencyManagementViewModelTest.kt` (AE-14 all branches incl. Loading/Unavailable → dialog; same code → no dialog/write; unsupported → today's error; write failure; CE) + `CurrencyManagementScreenValidationTest.kt`.
- Existing setting write, gated. Depends 1a-2, 1a-3a, 1a-3b (res order). Gates cheap reviewer + PSG; escalate to RS if DI change > one `@Inject` param (MC-N13).
- Validation: `"*CurrencyManagementViewModel*"`, `"*CurrencyManagementScreenValidationTest*"`; shard `runtime-ui`; `lint`; `static-guards`.
- Done when: both entry points gated; no write on cancel; owner reviewed Greek.

### 1a-5 — Rescue M5 (T3)
- INV-13 (exception), INV-20; AE-7; G2-2.
- Source: `FinancialRescueCoordinator.kt`: `createFreshRoomDatabaseAndImport(snapshot, home: CurrencyCode? = null)` → `importExpenses(…, home)`; extract `:418-420` into pure `internal fun rescueSnapshotFields(currency, amount, home)` (Identity if `home != null && currency == home`, else `0.0/""/0.0`); keep method names + `execSQL` in `importExpenses`. `RescueActivity.kt` unchanged (default null). Docs: LEGAL_PATHS FORBIDDEN "writing base fields outside TLC (Rescue restore excepted: identity/sentinel only)".
- Tests: NEW `T/data/rescue/FinancialRescueSnapshotFieldsTest.kt` (AE-7 null, EUR, other).
- Existing structural exception, same keys (`db_structural_exceptions.yml:136-142`, `_expected_methods.yml:128+`, `db_access_allowlist.yml:545`, `db_access.json:6`). Code ∥ any 1a; docs serialised. Gates RS, AG.
- Validation: `"*FinancialRescue*"`; shard `data`; `db_access` then `db_artifact_sync` (unchanged; drift → MC-N3/N14); `static-guards`.
- Done when: exemptions exact; AE-7 green.

### 1a-G — 1a part gate
Serial: `unit-test-shard` `architecture-contracts` → `domain-a-m` → `domain-n-z` → `data` → `runtime-ui` → `static-guards` → `registered-guard` `db_access` → `db_artifact_sync` → `lint` → `app-check` → `unit-tests` (last). Then fresh-session RS acceptance (not the discovery reviewer). 1a releasable as a unit.

### 1b-1 — Digits accessor + `roundSnapshot` (T3, behaviour-neutral)
- INV-16; INV-18 (helper); AE-23 helper cases (1.025→1.02, 0.135→0.14, 2.03×0.5→1.02, JPY 3025/3024/3026, HUF floor 0.01, 0.005→0.01, 0.0061→0.01, BHD 1.234/0.001, NaN/Inf/0/−1 → `NON_FINITE_OR_NON_POSITIVE`, `XXX` → `DIGITS_UNAVAILABLE`); AE-24 helper; G2-1/OD-1.
- Source: NEW `A/domain/core/money/CurrencyFractionDigits.kt` (single accessor over `java.util.Currency.defaultFractionDigits`; null on unknown or < 0); NEW `A/domain/core/money/SnapshotRounding.kt` (`roundSnapshot(value, home): SnapshotRounding` = `Rounded`/`Unavailable(reason)`; HALF_EVEN, `BigDecimal.valueOf`, one-minor-unit floor; pure; F5). `SourceCurrencyAllocation.kt:29-35` delegates (behaviour identical; else defer, no N). `CurrencyFormatter.kt:96` untouched. Docs: LEGAL_PATHS SNAPSHOT rounding line + FORBIDDEN "a second snapshot rounding rule"; ENGINE map helper row; SEGMENTS Segment 16 two files.
- Tests: NEW `SnapshotRoundingTest.kt`, NEW `CurrencyFractionDigitsTest.kt` (EUR 2, JPY 0, KRW 0, BHD 3, XXX unavailable); `SourceCurrencyAllocation` tests unchanged.
- No consumer yet. Depends 1a-G. Gates RS, AG (single accessor, MEC INV-12 owner), PSG.
- Validation: `"*SnapshotRounding*"`, `"*CurrencyFractionDigits*"`, `"*SourceCurrencyAllocation*"`, `"*Engine5PrimitiveGuardTest*"`, `"*MoneyBoundaryGuardTest*"`; shard `domain-a-m`; `static-guards`.
- Done when: AE-23 helper green; exactly one `defaultFractionDigits` use in `domain/core/money` (RS grep).

### 1b-2 — TLC snapshot outcomes + diagnostics + log fixes (T3)
- INV-13, 14, 15, 17. AE-18, 19 (identity exact incl. legacy 10.125 on update), 20 (no `MISSING_RATE` regression), 21 (create; recurring materialisation; installment → loan S7a, no installment code), 22, 23 TLC parts (10.125→10.12; 2.03×0.5→1.02; rate stored unrounded 0.923456; rounding Unavailable → sentinel + `MISSING_RATE(reason=SNAPSHOT_ROUNDING_UNAVAILABLE)`), 26. D-6, D-8. G2-7, G2-8.
- Source: TLC create `:529-585`: converted `:566-571` via `roundSnapshot`; no-rate `:572-578` → explicit `0.0/""/0.0` + one best-effort `MISSING_RATE` (stage `createExpense`, metadata `{operation, reason}` only), mirroring `:295-324`, CE rethrown; remove `Timber.w` `:573-577`; identity `:579-585` unchanged. Update: precompute `:1002-1023` emits `MISSING_RATE` (stage `updateExpense`, `entityId`) **outside** `database.withTransaction`; outcomes `:1099-1126` apply rounding + sentinel. Audit JSON `:2545-2547` value-only. Hunks within these ranges (loan S3 `:508-526` adjacent). `CurrencyConverter.kt:231`: missing-rate log carries no currency pair/amount (G2-8). Docs: LEGAL_PATHS SNAPSHOT block (TLC only; sentinel + `MISSING_RATE`/`HOME_CURRENCY_UNAVAILABLE`) + UPDATE `:52-61` "full-row update re-snapshots against current H"; ENGINE map TLC snapshot note + F9 consumers.
- Tests: update `TransactionLifecycleCoordinatorTest.kt` (`:307-321` sentinel triple + one `MISSING_RATE`; `:611` `9.2` per S0; `:614, 640` counts/rounding; `:819-821` identity unchanged); `TransactionLifecycleCoordinatorUpdateTest.kt` `:172-211, 752-780, 797`; new AE-18..23 cases + Timber capture (no "USD"/"20"/"EUR"); CE rethrow from converter + sink; converter log test in existing `T/domain/currency/` converter test class (never `T/currency/`).
- TLC sole snapshot writer; `insertAtomic` unchanged; no new event type (FG-14). Depends 1b-1; MEC A2 + loan S3/S6a not in flight (MC-N2). Gates RS, AG, PSG.
- Validation: `"*TransactionLifecycleCoordinator*"`, `"*CurrencyConver*"`, `"*ManualExpenseRepository*"`, `"*ReviewQueueRepository*"`; shards `domain-n-z`, `domain-a-m`, `data`, `architecture-contracts`; `db_access` then `db_artifact_sync` (unchanged); `static-guards`.
- Done when: every outcome writes all three fields; one diagnostic per attempt; no PII in logs; no migration/rewrite of existing rows (RS grep); guard deltas none or only H-3 shrinks.

### 1b-3 — Export cells + release note (no prod code)
- INV-21; AE-25 (CSV + JSON; re-import ignores base columns); G2-12; F8.
- Tests: extend `ExportOptionsViewModelTest.kt`, `AccountingExportPolicyTest.kt`/`AccountingExportRepositoryTest.kt` (accounting CSV + FreshBooks empty `homeCurrency`); `CsvEscapingTest`, `ExportImportRoundtripTest` unchanged, re-run.
- Docs: release note "Rows without an exchange rate now export empty base currency and home currency cells instead of EUR (also accounting CSV and FreshBooks exports)" (location per S0).
- Depends 1a-G (∥ 1b-2). Gates cheap reviewer; 1b RS.
- Validation: `"*ExportOptionsViewModel*"`, `"*AccountingExport*"`, `"*CsvEscapingTest*"`, `"*ExportImportRoundtripTest*"`; shards `domain-a-m`, `runtime-ui`.
- Done when: AE-25 green; release note present.

### 1b-G / Z — 1b part gate + closeout
1b-G repeats the 1a-G sequence, then fresh-session RS final acceptance. Z (no `app/` edits): regenerate `docs/architecture/CODEBASE_INVENTORY.md`; update handoff; notify MEC S0 to apply RF-10 (MEC domain-spec AE-21, AE-29, §5 → `""`; MEC INV-12 owner text; INV-15 interim ends — MEC plan amendment exists); hand follow-ups to owners.

## Guard / config edit assignment
| Edit | Slice | Approval |
|---|---|---|
| `config/guards/db_raw_query_classification.yml` +1 `read` row (`countExpensesWithoutHomeValueDynamic`) | 1a-2 | G2-3 |
| PII-logging guard entries pointing at removed log lines (shrink only, if any) | 1b-2 | H-3 |
| Rescue exemptions, `db_access_allowlist.yml`, `db_access.json`, `db_ownership_policy.yml`, `test-shards.json`, registered guards, money/time guard scripts | none | any need → stop (MC-N3/N6/N14/N15) |

## Owner plan-gate checklist
- [ ] Base ready (Release 1.0 + `release-1.0-wt` merged; ai-fixes landed); S0 before any `app/` edit.
- [ ] Order + parallel windows: 1a-3a ∥ 1a-1/1a-2 and 1a-5 ∥ 1a (code only); 1b-3 ∥ 1b-2; validations always serial.
- [ ] H-1..H-3 decided.
- [ ] S0 performance thresholds (200 ms at 10^5 rows) accepted/adjusted; fallback debounce/lazy count only, never an index.
- [ ] Behaviour accepted: rows without a home value sort last / hidden by bounds with a count; date-sort ties by id; home-switch warning on both entry points incl. while H Loading; HALF_EVEN snapshot rounding on new converted rows only; empty export cells for no-rate rows (release note).
- [ ] 1a ships as a unit after 1a-G; 1b after 1b-G; MEC A2 waits for 1b.
- [ ] Greek reviewed at 1a-3b and 1a-4; Spanish native review follow-up.
- [ ] No guard/baseline growth beyond the G2-3 yml row.

## Human decisions required
- **H-1** (spec gap, §1.5 closed set): ALL-tab mode when H Loading/Unavailable. Rec: third closed variant `AmountSemantics.AmountOpsDisabled` (ignores min/max, forces `date DESC, id DESC`, no amount SQL/args, test-pinned). Alt: reuse `RawEffective` with bounds stripped + DATE_DESC forced + tiebreak when paged (no spec change, but `RawEffective` reachable from Transactions).
- **H-2** plan deviations: (1) interim `RawEffective` at `TransactionsViewModel.kt:816` in 1a-2 (identical behaviour), removed in 1a-3b; (2) 1a-3 split 3a/3b; (3) search escaping in all non-`RawEffective` modes; (4) CMVM injects `ExpenseRepository` (read-only) for UX-Q-1 count; (5) `SourceCurrencyAllocation` delegation in 1b-1, `CurrencyFormatter` out of scope; (6) G2-5 delegate creates entity → `domain/core/money` import — if S0 finds it forbidden, fallback: delete `normalizedEffectiveAmount`, its four consumers call the owner (MC-N7).
- **H-3** pre-approve **shrink-only** removal of PII-logging guard/baseline entries pointing at the deleted log lines (TLC `:573-577`, `CurrencyConverter.kt:231`); any other guard-config change still stops (MC-N11).

## New stop-and-ask items (MC-N)
MC-N1 release drift in Transactions files beyond compared lines (`AmountInputSanitizer` `:663`/`:2136`; R16c min/max parsing) · MC-N2 overlapping slice in flight (MEC A2/any MEC slice on TLC `:529-585`; loan S3/S6a on TLC/`ExpenseDao`; loan schema bump during 1a-1/1a-2) · MC-N3 db_access/db_artifact_sync finding beyond G2-3 row (new DAO method needs `db_ownership_policy`/artifact entry; evidence hashes TLC/Rescue bodies; other drift) · MC-N4 any schema/index need (incl. still slow after debounce/lazy, or date plan losing `date` index) · MC-N5 INV-11 broken (assistant golden or `ExecuteFinancialQueryUseCaseTest` needs edit) · MC-N6 `Engine5PrimitiveGuardTest`/`MoneyBoundaryGuardTest`/money-time guard conflict needing guard edit/exception · MC-N7 entity → `domain/core` import forbidden · MC-N8 typed observable can't use interface default, needs Hilt module change, or a fake needs non-mechanical change · MC-N9 sink can't carry `MISSING_RATE` `{operation, reason}`, needs registry, or reuse forces new enum value · MC-N10 tests outside impact-map list pin `'EUR'` for no-rate rows, or updating needs deletion · MC-N11 log removal needs guard edits beyond H-3 shrink · MC-N12 Robolectric Room parity test infeasible in `data` shard · MC-N13 CMVM count injection needs new module/binding · MC-N14 Rescue change can't keep exemption keys exact · MC-N15 a new test needs unsharded `currency` package or `test-shards.json` edit.

## Risk register
| # | Risk | Mitigation / slice |
|---|---|---|
| R1 | Assistant SQL drifts (INV-11) | golden test green **before** builder change (1a-1); required `AmountSemantics`; MC-N5 |
| R2 | SQL/Kotlin mirrors diverge | shared fixture owned by rule; real-SQLite parity (1a-1/1a-2) |
| R3 | Placeholder/guessed H reaches a query | typed state; Loading/TIMEOUT; AE-11 no placeholder arg; `AmountOpsDisabled` |
| R4 | Paging race/duplicates on H/sort change | INV-10 query keys on `loadMore` + count; AE-8/AE-12 |
| R5 | Count/amount-sort cost on large DBs | S0 EXPLAIN + timing; debounce/lazy; MC-N4 |
| R6 | Count ≠ visible rows | wildcard escape in Transactions modes (G2-4); count re-run on refresh |
| R7 | Privacy leak (raw `Failed.reason`, logs, dialog/count) | bounded codes (1a-3a); log fixes (1b-2); PSG on 1a-1/2/3/4, 1b-1/2 |
| R8 | Shared-file contention (ExpenseDao/loan, TLC/MEC A2 + loan S3, res, docs, release Transactions edits) | order; S0 re-verification; MC-N1/N2 |
| R9 | Rescue exemption keys drift | defaulted param, same names, `execSQL` in place; guards after slice |
| R10 | Rounding/sentinel visible in exports, header totals, audit | release note (1b-3); AE-25/26; existing rows never rewritten |
| R11 | CE swallowed in new collectors/emitters | FG-10 tests in 1a-3a/3b/4, 1b-2 |
| R12 | FG-26 docs before symbols | docs with symbols; serial doc edits |
| R13 | MissingTranslation / Spanish plurals | EN/el/es same slice; `many`; lint per UI slice |
| R14 | Partial 1a release hides history unannounced | 1a ships as unit after 1a-G |
| R15 | `NULLS LAST` unsupported on device SQLite | `(CV IS NULL) ASC` only |
| R16 | Two digits accessors (MEC INV-12) | 1b-1 single accessor; `SourceCurrencyAllocation` + MEC A1 delegate |

## Final acceptance gate (per part: 1a-G then 1b-G; validation-runner, serial; poll; stale/missing ≠ PASS)
1 `unit-test-shard -Shard architecture-contracts` · 2 `domain-a-m` · 3 `domain-n-z` · 4 `data` · 5 `runtime-ui` · 6 `static-guards` · 7 `registered-guard db_access` then `db_artifact_sync` (vs S0: only G2-3 delta) · 8 `lint` · 9 `app-check` · 10 `unit-tests` (last). No `migration-tests`/connected tests. After both parts, fresh-session reviewer-strict final acceptance (separate from discovery + slice reviewers).

## Deferred
Inc 2: `resnapshotConversion` legal path + WorkManager job; analytics alignment; F-4 budget fallback; RF-9. Inc 3: export null/at-export-time home; SRH-7. Out of scope: RF-3 (assistant preview min/max, dead deprecated query) → ai-fixes/assistant-follow-ups (rebase on 1a); RF-4 date-header signing; UX-Q-5 localized sort labels; optional `transactionsRead` diagnostic; optional architecture test forbidding raw fallback in `ui/screens/transactions/`; `CurrencyFormatter` digits delegation. G2-15 follow-ups: `ManualRecurringExpenseViewModel.kt:108-116`, `CsvExpenseImporter.kt:238-239`, mixed-currency header signing, unsharded-packages gap, Spanish native review.

## UNVERIFIED for S0
D-1 base/branch/every cited line · D-2 MEC, loan, ai-fixes status · D-3 release worktree edits beyond compared lines (`TransactionFilterSheet` R16c) · D-4 G2-13 method (sqlite3 CLI; fallback plan-shape-only Robolectric test in 1a-2 at 10^4 rows + device timing at 1a-G) + results · D-5 `id` rowid alias · D-6 sink API + `MISSING_RATE` metadata/registration; `DiagnosticReasonCode` exhaustive `when` · D-7 guard implications of new DAO method; db_access hashing of TLC/Rescue; PII-guard entries for the two log lines · D-8 entity → `domain/core` import allowed · D-9 CMVM ctor/DI + test sites · D-10 `CurrencySettingsRepositoryImpl` test harness; DataStore unset detection · D-11 tests pinning `'EUR'` for no-rate rows; TLC `:611`; `UpdateTest` path; JSON importer/backup restore of `""` · D-12 `CurrencyConverter.kt` + test paths/packages · D-13 plurals, `action_got_it`, `strings.xml:229`, lint MissingTranslation config · D-14 shard coverage `data.rescue.*`, `ui.screens.*`; Robolectric in `data`; multi-pattern `-TestFilter`; `compile` profile · D-15 release-notes location · D-16 `SourceCurrencyAllocation` unknown-code behaviour vs accessor.
