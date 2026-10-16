# Impact map: nl-quick-add (increment 1 — typed, deterministic, fill-only)

Author: impact-mapper (stage 5, read-only). Persisted by the orchestrator. Date 2026-10-16.
Basis: domain-spec.md (binding), brief v2, handoff, ai-fixes-plan.md, loan-installments-plan.md. `ux-flows.md` absent at mapping time → UI file lists provisional. Spec §0 ledger not re-verified; named symbols re-confirmed.
Roots: `A` = `app/src/main/java/com/yourname/expensetracker`, `T` = `app/src/test/java/com/yourname/expensetracker`, `res` = `app/src/main/res/values{,-es,-el}/strings.xml`.

## 1. Production surface
- Segments: 9 Core Expense Management (`ui/screens/addexpense/AddExpenseSheet.kt`, `ManualExpenseRepository.kt`; CODEBASE_SEGMENTS.md:223-231) owns VM, Sheet, C3 fill contract. 6 Merchant Categorization (`CategorizationEngine`, `MerchantCategoryRepository`, `MerchantNormalizer`) — C2 reads from it. 32 Utilities (`domain/util/AmountUtils.kt`, :642) — reused read-only. `domain/quickadd/` is new; propose registering C1/C2 under Segment 9 with a boundary note (Segment 6 alternative for C2).

| Path | Kind | Notes |
|---|---|---|
| NEW `A/domain/quickadd/QuickAddParser.kt` + value objects (`QuickAddInput`, `FieldParse`, `QuickAddParseResult`, `FlagReason`, `LineHint`, vocabulary tables) | add | C1: pure Kotlin, no I/O/logging/Android |
| NEW `A/domain/quickadd/QuickAddLearnedCategoryLookup.kt` | add | C2: suspend, read-only; `CategorizationEngine.normalizedCanonicalNameForMerchant` (:514) + `MerchantCategoryRepository.getCategoryByNormalizedCanonical` (:49) — verified |
| `A/ui/screens/addexpense/AddExpenseViewModel.kt` | modify | C3: state (`fieldOrigins`, `fieldFlags`, `lineHints`, `lastFillOutcome`), `fill(line)` job, origin transitions in `updateAmount`/`updateMerchant`/`selectSuggestion`/`updateDate`/`selectCategory`/`selectPaymentMethod`, `reset()`; `:126-128` fix (CE rethrow, constant code, no throwable); `:439` save-error hygiene; `setInitialValuesIfBlank` (:463) consumed by Fill (INV-8); ctor gains deps |
| `A/ui/screens/addexpense/AddExpenseSheet.kt` | modify | `remember` line (never `rememberSaveable`), Fill, flags/hints, `SaveResult.Error` from string resource (:550) |
| `A/ui/util/ClipboardAmountParser.kt` or `setInitialValuesIfBlank` | modify iff OD-1 = yes | Canonicalise "12,50" before `sanitize`; `MainActivity.kt:616-623` untouched |
| `res` EN/es/el | modify | hints, flags, generic save error; es/el in same slice as key (MissingTranslation lint) |
| `docs/architecture/*`, `docs/features/FEATURES.md` | docs | §4 |

- Legal paths: no new path. Save stays VM → `ManualExpenseRepository.addManualExpense` → TLC `createExpenseDbOnlyV2` → `ExpenseDao.insertAtomic()`, `source = MANUAL_ENTRY` (LEGAL_PATHS.md §Expense Mutations :21-49). Fill = in-memory form write, not a mutation path. Learning on Save unchanged (`CategorizationEngine.learnMerchantCategory` → `MerchantCategoryRepository.insert`; FORBIDDEN rule :806-810 untouched).
- Engines: `CategorizationEngine` — only pure `normalizedCanonicalNameForMerchant`; `categorize*`/`normalize` banned at Fill. `MerchantNormalizer` R1: `normalize(autoCreate=false)` calls `linkAliasToCanonical` at `MerchantNormalizer.kt:100` (alias write); not edited here (spec F2 out of scope). ENGINE_INTERACTION_MAP rates MerchantNormalizer CRITICAL, CategorizationEngine HIGH — neither edited → consumer-regression-free provided the C2 guard holds.
- DAOs/schema: read-only `MerchantCategoryDao.getCategoryByNormalizedCanonical` (:50) via repository; `ExpenseRepository.searchMerchants` unchanged. No writes at Fill (INV-11; AE-40 is the proof). `androidTest/.../dao/MerchantCategoryDaoTest.kt:37-126` already pins ordering C2 relies on.
- DataStore/permissions/privacy: none (no DataStore, prefs, permission, worker, network, AI). Privacy surface: typed line (INV-12), diagnostics (INV-13): remove raw-text + throwable log at `AddExpenseViewModel.kt:128` and raw `e.message` at `:439-449`. `rememberSaveable`/`SavedStateHandle` = 0 hits under `ui/screens/addexpense` (baseline green).
- Hilt: VM ctor today `ManualExpenseRepository`, `ExpenseRepository`, `CategoryRepository`, `TimeProvider`, `CurrencySettingsRepository` (:73-79) → adds C2 (+ parser if injected). Ctor call sites: `T/ui/screens/addexpense/AddExpenseViewModelTest.kt:45`, `AddExpenseViewModelStressTest.kt:45`. Sheet uses `hiltViewModel()` (:82); only caller `MainActivity.kt:623`.
- Layering: ~60 `domain/**` files import `data.repository`; no layering guard found (UNVERIFIED beyond grepped tests) → C2 in `domain/quickadd` consistent.

## 2. Tests, guards, FG-IDs
| Test | Location/format | Covers | FG |
|---|---|---|---|
| Parser golden suite (132 target, spec §12) | NEW `T/domain/quickadd/QuickAddParserGoldenTest.kt`; table inline or `src/test/resources/quickadd/` (NOT `golden/`, which belongs to `integration-golden` shard) | AE-1..18, taxonomy A-M, INV-5/6/19; INV-5 round-trip `sanitize(T)==T`; DST/clock-injection fixture | FG-17, FG-08 |
| Parser purity/import boundary | same package | INV-14/19 | FG-16 |
| C2 test | NEW `T/domain/quickadd/QuickAddLearnedCategoryLookupTest.kt` | null key rows, category not in picker, exception → Absent, CE rethrown | FG-10 |
| VM fill contract | extend `AddExpenseViewModelTest.kt`; ctor update in `AddExpenseViewModelStressTest.kt` | AE-20..31 | FG-12, FG-17 |
| Save path | VM test + existing ManualExpenseRepository/TLC tests | AE-41, AE-42/43, AE-5 | FG-13, FG-17 |
| Write-free proof | AE-40: JVM fakes `verify(exactly = 0)` on write methods, or connected in-memory Room (JVM Room harness UNVERIFIED; ai-fixes D-8) | INV-11 | FG-13 |
| Privacy | extend `T/golden/PrivacyDoNotStoreTest.kt` or VM test: AE-50 `state.toString()` has no line text; AE-51 capturing Timber tree (precedent `TransactionsViewModelTest`, `ReceiptScanViewModelTest`); AE-52..54 | INV-12/13/15 | FG-16 |
| OD-1 | extend `T/ui/util/ClipboardAmountParserTest.kt` + VM `setInitialValuesIfBlank` case | "12,50" → "12.50" not "1250" | FG-17 |
| Pins | `MerchantKeyGeneratorTest.kt` (AE-4), `AmountUtilsTest.kt`, `CategorizationEngineTest.kt` (purity) | | |
| CancellationSafety | `T/architecture/CancellationSafetyArchitectureGuardTest.kt:150` has file-wide KNOWN_VIOLATIONS `AddExpenseViewModel.kt` (`LAUNCH_CE_NO_RETHROW`, expires 2026-12-31) | removal-only after CE fixes (:126, :439, new Fill catch); whether file still flagged after fix UNVERIFIED (scanner flags broad catches in `suspend fun`, :295-298); planner decides | FG-06, FG-10 |

Proposed static guards (spec §7.5) — each NEW → stop-and-ask:
- G-1 no `rememberSaveable`/`SavedStateHandle` under `ui/screens/addexpense`.
- G-2 `domain/quickadd/**` imports no `android.*`, `timber`, `domain/ai`, `data.*` (except C2's repository read); C1 no `data.*`.
- G-3 no `Timber.*(e, …)` with string interpolation in `AddExpenseViewModel`.
- G-4 C2 references none of `categorize`, `normalize(`, `getCategoryForMerchant`, `MerchantNormalizer`.
Placement: NEW `T/architecture/QuickAddPrivacyArchitectureGuardTest.kt` (shard `architecture-contracts`), not `SourceScanningArchitectureGuardTest.kt` (serialised through ai-fixes B1a/B1b/B2d/B3a). Conventions: `SourceTextSanitizer` stripping, seeded-violation fixture per guard, non-vacuous scan assertion, no allowlist entries. FG-03, FG-06, FG-08, FG-09, FG-16, FG-23.

FG in scope: FG-06, FG-08/09, FG-23, FG-10, FG-12, FG-13, FG-16, FG-17, FG-19, FG-20, FG-26. Not in scope: FG-21, FG-11, FG-14/15.

## 3. Schema and db_access
- Schema NONE; `migration-tests`, room-migration-guardian not required; `ExpenseSource` stays MANUAL_ENTRY.
- `config/guards/db_ownership_policy.yml` tracks DAO write sites (read ops ~1 occurrence); `MerchantCategoryRepository` rows only `insert` (:1193-1212). C2 holds no DAO (calls a repository read) → no new DAO call site / policy row expected; VM injects no DAO. Expect `registered-guard db_access` + `db_artifact_sync` unchanged, no baseline growth (`config/baselines/db_access_v2.json`). `config/db_access_allowlist.yml` superseded — do not edit. Detector read semantics UNVERIFIED; any finding = N2-style stop.

## 4. Architecture docs (FG-26; docs ship with code)
- `CODEBASE_SEGMENTS.md`: Segment 9 (:223-250) new `domain/quickadd/*` + boundary note; Segment 6 (:159-171) C2 reference.
- `LEGAL_PATHS.md`: §Expense Mutations CREATE (:21-49) note that UI prefill aids (clipboard, quick-add Fill, merchant suggestion) are in-memory form writes; §Categorization/Merchant Learning (:785-811) stale "No persistent side effects"/"Read-only" for `categorize*` vs V13 → correct; add read-only lookup entry + FORBIDDEN line (Fill-time/read-only callers must not use `categorize*`/`normalize`/`getCategoryForMerchant`).
- `ENGINE_INTERACTION_MAP.md`: MerchantNormalizer rows (:15, :78-80) note alias writes on `normalize(autoCreate=false)`; CategorizationEngine rows (:16, :92-94) new read-only consumer.
- `CODEBASE_INVENTORY.md` regeneration (method unresolved, ai-fixes D-10). `FEATURES.md` quick-add entry. `hilt-bindings-map.md` only if a binding is added.

## 5. Risk tiers
| Part | Tier | Why |
|---|---|---|
| C1 parser | T2 | No persistence; money-adjacent (amount/currency/date, foreign fail-closed) → reviewer-strict |
| C2 lookup | T2 | Read-only; R1 trap → privacy-security-guardian |
| C3 VM fill contract + Sheet | T2 | One segment; concurrency/overwrite risk (R3) → reviewer-strict |
| Save-error hygiene + `:128` fix | T2 | Diagnostics privacy; no cloud/permission/worker |
| OD-1 clipboard fix | T2 money-adjacent | ×100 defect; self-contained → reviewer-strict |
| OD-2 `Result.Error` branch | T2 | VM-only mapping |
| New static guards | T3 by rule | stop-and-ask; architecture-guardian; isolated slice |
| **Overall** | **T2 (upper end)** | No schema, new write path or worker; guard slice is the only T3 element |
Gates: reviewer-strict on C3 + C1 amount/currency rules; privacy-security-guardian on C2, diagnostics, G-1..G-4; architecture-guardian on guard slice + docs; room-migration-guardian not required.

## 6. Validation (validation-runner only, serial)
- C1: `targeted-unit-test -TestFilter "*QuickAddParser*"`.
- C2: `"*QuickAddLearnedCategoryLookup*"`, pins `"*MerchantKeyGeneratorTest*"`, `"*CategorizationEngineTest*"`.
- C3 + Sheet + hygiene: `"*AddExpenseViewModel*"`; `"*PrivacyDoNotStoreTest*"` if touched.
- OD-1: `"*ClipboardAmountParserTest*"`, `"*AddExpenseViewModel*"`.
- Guards: `"*QuickAddPrivacyArchitectureGuardTest*"`, `"*CancellationSafetyArchitectureGuardTest*"` (if entry removed), then `static-guards`.
- `lint` after any `res` change. DB confirmation: `registered-guard -GuardId db_access`, then `db_artifact_sync` (expect unchanged). Optional `connected-test-class` if AE-40 is androidTest.
- Slice-end: `unit-test-shard -Shard runtime-ui`, `-Shard architecture-contracts`. Bundle: `app-check`.
- **SHARD FINDING:** `config/validation/test-shards.json` lists explicit package filters; no `com.yourname.expensetracker.domain.quickadd.*` entry → new-package tests run in NO `unit-test-shard`, only full `unit-tests`. No shard-coverage check in the runner (`validation-runner.ps1` only loads the file at :78). Fix: add `domain.quickadd.*` to `domain-n-z` (validation-config edit = stop-and-ask) or place tests in an already-sharded package. Recommend the config edit.

## 7. Conflicts and sequencing
| Other work | Shared files | Conflict |
|---|---|---|
| ai-fixes (approved, parked) | `SourceScanningArchitectureGuardTest.kt` (B1a, B1b, B2d, B3a); `res` (A, B1b, B1c, B2e, B3b); `CancellationSafetyArchitectureGuardTest.kt` (removals only; AddExpenseViewModel not in ai-fixes list); `ManualExpenseRepository.kt` (B2d) | New guard file; serialise `res`; quick-add after ai-fixes B2d. VM/Sheet not in ai-fixes write sets. |
| loan-installments (T3; S11/S12a/S12b) | `AddExpenseViewModel.kt`/`AddExpenseSheet.kt` (S11 installments sub-state + submit branch, plan :455-456), `TransactionsScreen`, `BudgetScreen`; GR-08 regeneration (Room v152 slice) | **Direct conflict on VM + Sheet** → never parallel; one lands first, the other rebases. Quick-add additive state/functions; loan adds submit branch + sub-state; plain save path unchanged per loan plan. `AddExpenseViewModelTest`/`AddExpenseInstallmentsTest` ctor sites change. |
| manual-entry-currency (T3) | VM + Sheet (currency control) | inc 1b depends on it; not this increment |
| release-1.0-wt | No AddExpense match in release handoff; loan-installments-handoff.md:144 records release worktree "actively editing AddExpenseViewModel" | Treat VM as hot until merge; S0 diff check |

Recommended order (post-1.0, after ai-fixes): S0 (base merged; re-verify `:126-128`, `:439-449`, `:463-484`) → A hygiene (`:128` fix, CE rethrow, save-error, OD-2, CancellationSafety removal) → B parser C1 + golden tests + shard filter → C C2 + VM fill contract + Sheet (es/el) → D OD-1 clipboard fix (independent; any time after S0) → E static guards G-1..G-4 (T3 by rule) → docs/closeout. Before loan-installments S11 (or rebase it).

## 8. Open items for Gate 2
- OD-1 clipboard comma-decimal fix as own slice (rec yes).
- OD-2 hygiene for VM `Result.Error` (rec yes, VM only).
- OD-3/OD-4/OD-5 recommended defaults.
- Guards G-1..G-4 (new; stop-and-ask): file (rec NEW `QuickAddPrivacyArchitectureGuardTest.kt`); G-1/G-3 scoped to `ui/screens/addexpense` vs repo-wide.
- Test-shard config: add `domain.quickadd.*` to `domain-n-z` (validation-config edit; stop-and-ask).
- Remove `AddExpenseViewModel.kt` from CancellationSafety KNOWN_VIOLATIONS in slice A (removals-only precedent; guard edit needs sign-off).
- LEGAL_PATHS §Categorization correction + ENGINE_INTERACTION_MAP V13 note (architecture doc rewrites).
- C1/C2 package ownership: Segment 9 vs new segment entry.
- Sequencing vs loan-installments S11 and manual-entry-currency.
- Accepted risks R2 (category false positive), R3 (concurrency), R4 (locale parsing) with UX mitigation.
- AE-40 proof form (JVM fakes vs connected).

## Unverified
Spec U1..U7 (Sheet edit-mode, learning distinguishing user vs auto category, saved merchant display form, diagnostic sink, `categories` flow, IME flags); db_access read semantics; hilt map path; domain→data layering guard. Re-confirmed: `AddExpenseViewModel.kt:126-128`, `:439`, `:463`, ctor; `MerchantNormalizer.kt:100`; `CategorizationEngine.kt:514`; `MerchantCategoryRepository.kt:49`; `MerchantCategoryDao.kt:50`.
