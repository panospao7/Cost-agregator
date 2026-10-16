# Feature plan: nl-quick-add (increment 1 — typed, deterministic, fill-only)

Author: feature-planner (stage 6). Persisted by the orchestrator. Date 2026-10-16.
Status: AWAITING OWNER PLAN APPROVAL (Gate 3). No `app/` edits until S0 passes. Implementation only after Release 1.0 accepted + `release-1.0-wt` merged + ai-fixes B2d landed; must land before loan-installments S11.

- Risk tier: **T2 (upper end)**; slice E T3 by rule (new guards; impact map §5, G2-18).
- Basis: `docs/features/nl-quick-add/impact-map.md` (trusted), `gate2-decisions.md` (G2-1..G2-18 DECIDED = Recommended), `domain-spec.md` §1–§3, §5, §7, §11–§13, `ux-flows.md` §2–§11, `workflows/active/ai-fixes-plan.md` (format, shard/guard facts).
- Schema: none (no Room entity/column/version/migration/snapshot; `migration-tests`, RMG not required; any need → N2).
- Legal paths: none new. Save: VM → `ManualExpenseRepository.addManualExpense` → TLC `createExpenseDbOnlyV2` → `ExpenseDao.insertAtomic()`, `source = MANUAL_ENTRY`. Fill = in-memory form write. Learning on Save unchanged.
- Roots: `A` = `app/src/main/java/com/yourname/expensetracker`, `T` = `app/src/test/java/com/yourname/expensetracker`, `res` = `app/src/main/res/values{,-el,-es}/strings.xml`. RS = reviewer-strict, PSG = privacy-security-guardian, AG = architecture-guardian, CR = cheap reviewer.
- Validation: validation-runner only, serial: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile <p> [-TestFilter "<f>"] [-Shard <s>] [-GuardId <g>]`. Poll run ID; RUNNING ≠ failure; missing/stale/errored ≠ PASS. One filter per run unless S0 D-10 confirms multi-pattern.

## Slice table
| ID | Title | Tier | Prod files | Depends on | Reviewers | Key FG |
|---|---|---|---|---|---|---|
| S0 | Pre-flight (no `app/` edits) | — | 0 | Release 1.0 accepted; `release-1.0-wt` merged; ai-fixes B2d landed | — | — |
| A | Add-expense hygiene: `:128` fix, CE rethrow, bounded save/`Result.Error`/validation messages, error visibility, CancellationSafety removal | T2 | 2 + res | S0 | PSG + AG (removal) + CR; owner reviews Greek | 06, 10, 12, 16, 17 |
| B | Parser C1 + 132-case golden suite + `domain-n-z` shard entry + Segment 9 doc | T2 | 5 new + 1 config + 1 doc | S0 (parallel with A allowed; disjoint) | RS (money rules) + AG (config/doc) | 08, 16, 17, 26 |
| D | OD-1 clipboard comma-decimal fix (reuses C1 amount rule) | T2 money-adjacent | 1 | B; A (shared VM test) | RS | 17 |
| C | C2 read-only lookup + VM fill contract (no UI entry yet) + LEGAL_PATHS/ENGINE_MAP/SEGMENTS docs | T2 upper | 3 (2 new) + 3 docs | A, B, D | RS + PSG + AG (docs) | 10, 12, 13, 16, 17, 26 |
| E | Guards G-1..G-4 in NEW `QuickAddPrivacyArchitectureGuardTest.kt` | T3 by rule | 0 (1 new test) | C | AG + PSG | 03, 06, 08, 09, 16, 23 |
| F | Quick-add Sheet UI + strings EN/el/es + empty-category caption + FEATURES.md | T2 | 3 (2 new) + res + 1 doc | C, E | CR + PSG; owner reviews Greek | 12, 16, 17, 26 |
| Z | Closeout + bundle acceptance gate | — | 0 | F | RS final (fresh session) | 19, 20, 26 + all |

Order: S0 → A (B alongside) → D → C → E → F → Z; validation always serial.
Shared files (sequential only): `AddExpenseViewModel.kt` (A → C); `AddExpenseSheet.kt` (A → F); `AddExpenseViewModelTest.kt` (A → D → C); `res` ×3 (A → F; serialise vs ai-fixes/loan); `CODEBASE_SEGMENTS.md` (B → C); `LEGAL_PATHS.md`, `ENGINE_INTERACTION_MAP.md` (C only; serialise vs loan); `CancellationSafetyArchitectureGuardTest.kt` (A, removal only); `config/validation/test-shards.json` (B only).

## Slices

### S0 — Pre-flight (no production edits)
1. Base: Release 1.0 accepted; `release-1.0-wt` merged into `bug-fixes`; ai-fixes landed through B2d (edits `ManualExpenseRepository.kt`); branch `feature/nl-quick-add`.
2. Nobody on VM/Sheet: loan S11 not started (no `AddExpenseInstallmentsTest`, no installments sub-state); manual-entry-currency not in flight → else N5.
3. Re-check lines vs landed code; record deltas in handoff; material change → N8: `AddExpenseViewModel.kt` ctor `:73-79`, `:126-128`, `:415-425`, `:439-449`, `:463-484`; `AddExpenseSheet.kt` `:82`, `:90-95`, `:97-107`, `:550`; `MainActivity.kt:616-623`; `ClipboardAmountParser.kt:8-22`; `AmountInputSanitizer.kt:33-57`.
4. Answer D-1..D-13 (below).
5. Baselines (serial). Required: `targeted-unit-test -TestFilter "*CancellationSafetyArchitectureGuardTest*"`, `static-guards`, `lint` (MissingTranslation baseline), `unit-test-shard -Shard runtime-ui`. Recommended: `targeted-unit-test -TestFilter "*AddExpenseViewModel*"`, `registered-guard -GuardId db_access`, `registered-guard -GuardId db_artifact_sync`.
- Done when: base confirmed, S11 not started, deltas recorded, D-items answered, baselines recorded (green or pre-existing red with reason), fired N-items escalated.

### A — Add-expense hygiene (T2)
- INV-13 (suggestion search + save), INV-15 (save coroutine + suggestion search), OD-2/G2-2; AE-51 (search/save-failure parts), AE-52, AE-53; AE-5 tail (empty amount → localized "Enter a valid amount").
- Source: `A/ui/screens/addexpense/AddExpenseViewModel.kt`:
  - `:126-128` suggestion search: rethrow CE first; else log exactly `MERCHANT_SUGGESTION_SEARCH_FAILED` (no throwable/query/merchant); clear suggestions as today (§7.3).
  - `:439-449` save catch: rethrow CE; else log exactly `ADD_EXPENSE_SAVE_FAILED` (no throwable); `saveResult` = `UiText.StringResource(add_expense_error_save_failed)`; `isSaving` cleared on every exit.
  - `:415-425` `Result.Error`: same bounded resource; never `result.message` or joined validation errors (VM only; repo unchanged); unexpected/`Result.Loading` → `save_failed`.
  - 4 validation messages (merchant required, amount invalid, amount too large, date future) + currency-loading message → `UiText.StringResource` (H-4).
  - Every other scanner-flagged catch (S0 D-3) gets CE rethrow, no behaviour change. Bounded codes = private VM constants (C adds `QUICK_ADD_CATEGORY_LOOKUP_FAILED`).
- `A/ui/screens/addexpense/AddExpenseSheet.kt`: render `UiText` (`:550`); on Error/Duplicate `scrollState.animateScrollTo(max)` + Card `liveRegion = Assertive`; leading glyph hidden from TalkBack (ux §9).
- `res` ×3 (EN/el/es same slice): `add_expense_error_save_failed`, `_currency_loading`, `_merchant_required`, `_amount_invalid`, `_amount_too_large`, `_date_future`; no exception-carrying format args.
- Tests: `T/ui/screens/addexpense/AddExpenseViewModelTest.kt` (capturing Timber tree per `TransactionsViewModelTest`/`ReceiptScanViewModelTest`): AE-52 (`SQLiteException` with SQL → exactly the code); AE-53 (`IllegalStateException` → generic resource, `isSaving == false`, code logged); save cancellation (CE propagates, no Error, `isSaving == false`); `Result.Error` with repo message → bounded resource; validation messages asserted as resource ids. `AddExpenseViewModelStressTest.kt` only if it asserts message text. `T/architecture/CancellationSafetyArchitectureGuardTest.kt`: remove `AddExpenseViewModel.kt` KNOWN_VIOLATIONS entry (`:150`), removal only (G2-8).
- Legal path preserved (catch bodies + result mapping only). Gates: PSG, AG (removal), CR; owner reviews Greek.
- Validation: `targeted-unit-test` `"*AddExpenseViewModel*"`, `"*CancellationSafetyArchitectureGuardTest*"`; `lint`; `static-guards`; `unit-test-shard -Shard runtime-ui`; `-Shard architecture-contracts`.
- Done when: no `e.message`/`result.message`/throwable/interpolated text in VM logs or UI; KNOWN_VIOLATIONS entry removed + guard green (else N6; never extended); no new MissingTranslation; green.

### B — Parser C1 + golden suite + shard entry (T2)
- C1 per spec §1, §2.1–2.5, §4. INV-3 (decision), 4, 5, 6, 10(a), 14, 19. AE-1..AE-18 at parser level (category Absent at parser level; learned-category parts of AE-1/3/4 and save tails of AE-5/8 in C). §12 taxonomy A–M, 132 cases.
- Source (all NEW in `A/domain/quickadd/`, names provisional; pure Kotlin: no `android.*`, `timber`, `data.*`, `domain.ai`; no clock reads, no `Locale.getDefault()` (use `Locale.ROOT`); no logging; may call `AmountUtils.parseAmount`; must not call ui-layer `AmountInputSanitizer`):
  - `QuickAddParser.kt` — `parse(input: QuickAddInput): QuickAddParseResult` (`@Inject` ctor or `object`; no Hilt module).
  - `QuickAddModels.kt` — `QuickAddInput`, `CategoryRef`, `FieldParse`, `AmountText`, `ResolvedDate`, `MerchantText`, `CategoryMatch` + `CategorySource` {EXACT_NAME, LEARNED}, `QuickAddParseResult` (no raw line/tokens/currency/foreign amount), `FlagReason`, `LineHint`.
  - `QuickAddAmountRules.kt` — §4.5/§4.6; amount canonicaliser as a separately callable pure function, single owner of the rule (D reuses it).
  - `QuickAddDateRules.kt` — §4.7/§4.8; LocalDate arithmetic in the input zone; java.time gap/overlap handling.
  - `QuickAddVocabulary.kt` — §4.4 tables + embedded non-catalog ISO list; no runtime `java.util.Currency`.
- `config/validation/test-shards.json`: add `com.yourname.expensetracker.domain.quickadd.*` to `domain-n-z` (G2-7) only, format per S0 D-4.
- `docs/architecture/CODEBASE_SEGMENTS.md` Segment 9 (`:223-250`): register `domain/quickadd/*` + boundary note (G2-10).
- Tests: NEW `T/domain/quickadd/QuickAddParserGoldenTest.kt` (+ optional `QuickAddParserGoldenFixtures.kt`): inline Kotlin tables; NBSP, U+202F, U+2009, U+2212 as `\u` escapes; not under `src/test/resources/golden/`; each case maps (line, home, now, zone, categories) → per-field `FieldParse` + `lineHints` with fixed CTX-A/CTX-B; every amount `Value` asserts `AmountInputSanitizer.sanitize(T) == T` and `parseAmount(T)` expected (INV-5); AE-14/15 explicit zones + `now`. NEW `T/domain/quickadd/QuickAddParserPurityTest.kt`: same input twice equal; identical under `Locale.setDefault` tr-TR and el-GR (INV-19); identical when default `TimeZone` changes.
- No persistence, no production caller → releasable. Gates: RS (amount/currency/date; foreign fails closed), AG (config/doc).
- Validation: `targeted-unit-test` `"*QuickAddParser*"`, `"*AmountUtilsTest*"` (pin); `unit-test-shard -Shard domain-n-z` (report must list both new classes); `static-guards`.
- Done when: per-category counts match §12; all AE-10..18 present; no expected value changed to match code against spec (→ N11); shard picks up package; green.

### D — OD-1 clipboard fix (T2, G2-1)
- Goal: clipboard "12,50" → "12.50", never "1250"; INV-5 for the clipboard channel; clipboard currency handling unchanged; `MainActivity.kt:616-623` untouched.
- Source: `A/ui/util/ClipboardAmountParser.kt` — route matched string through B's canonicaliser; Value → canonical text; Flagged/Absent → no prefill (fail closed: "1.500", "3,500", >2 decimals no longer prefill; H-3).
- Tests: `T/ui/util/ClipboardAmountParserTest.kt` ("12,50" → "12.50"; "12.50" → "12.50"; "1.234,56" → "1234.56"; ambiguous → null; list every changed existing expectation in slice report); `AddExpenseViewModelTest.kt` one case `setInitialValuesIfBlank("12.50")` → "12.50".
- One-shot prefill channel keeps its shape (V4). Depends on B (helper; deviation from impact map "independent", H-2) and A (shared VM test file). Gate: RS.
- Validation: `targeted-unit-test` `"*ClipboardAmountParserTest*"`, `"*AddExpenseViewModel*"`, `"*QuickAddParser*"` (only if helper visibility changed); `unit-test-shard -Shard runtime-ui`.
- Done when: "12,50" → "12.50"; no amount from rounding/truncation; green.

### C — C2 lookup + VM fill contract (T2 upper)
- C2 + C3 per spec §1, §2.6–2.9, §4.11, §5. INV-1, 2, 3 (apply), 7, 8, 9, 10(b), 11, 12 (line only as `fill(line)` argument), 13, 15 (Fill job), 16, 17, 18. G2-11 (`categoryFillSource`, `clearCategory()`), G2-13 (`lastFillOutcome` survives edits). AE-1/3/4 (learned parts), AE-5 save tail ("18" → exactly one `addManualExpense` in home currency), AE-9, AE-20..AE-31, AE-40 (per H-1), AE-41, AE-42/43 (VM level: Duplicate shown, fields kept), AE-50 (`state.toString()`), AE-51 (Fill part).
- Source:
  - NEW `A/domain/quickadd/QuickAddLearnedCategoryLookup.kt` (suspend, read-only): key `CategorizationEngine.normalizedCanonicalNameForMerchant(m)`; read `MerchantCategoryRepository.getCategoryByNormalizedCanonical(key)`; use only if `categoryId` ∈ `input.categories`; null key/no row/not in picker → Absent; non-CE exception → failure result, not logged (G-2 bans Timber here); CE rethrown; never `categorize*`, `normalize(`, `getCategoryForMerchant`, `MerchantNormalizer`, `traceDecision`; `@Inject` ctor, no module.
  - NEW `A/ui/screens/addexpense/QuickAddFillContract.kt` (provisional): enums `QuickAddField`, `FieldOrigin`, `OutcomeCode`, `CategoryFillSource`; pure §5.3 apply table + §5.4 outcome function; single owner of fillability.
  - `AddExpenseViewModel.kt`: ctor + parser + C2; enum-only state (`fieldOrigins`, `fieldFlags`, `lineHints`, `lastFillOutcome`, `categoryFillSource`; no String); `fill(line)` per §5.2: (1) INV-17 gate, (2) mark one-shot channel consumed, (3) cancel previous Fill job + `searchJob`, hide suggestions, (4) launch parse then C2 only under §4.11, (5) apply in one `_state.update` re-evaluating fillability (learned category only if same apply writes merchant Value), (6) clear `saveResult` + `amountError`/`merchantError` of written fields, (7) log one outcome constant. Fill catch: rethrow CE; else log `QUICK_ADD_CATEGORY_LOOKUP_FAILED`, category stays Absent. §5.1 origin transitions in `updateAmount`, `updateMerchant`, `selectSuggestion` (amount only if blank + unflagged), `updateDate`, `selectCategory`, `selectPaymentMethod`. NEW `clearCategory()` (null, origin USER, never refilled). `setInitialValuesIfBlank` sets PREFILL, no-op after any Fill. `reset()` clears all + cancels Fill job. Fill never touches notes, transactionType, recurring, transfer, ownership, homeCurrency.
  - Docs (FG-26, G2-9): `LEGAL_PATHS.md` §Expense Mutations CREATE (`:21-49`) prefill-aids note; §Categorization (`:785-811`) correct stale "No persistent side effects"/"Read-only"; read-only lookup entry + FORBIDDEN line. `ENGINE_INTERACTION_MAP.md` MerchantNormalizer (`:15`, `:78-80`) alias-write note; CategorizationEngine (`:16`, `:92-94`) new read-only consumer. `CODEBASE_SEGMENTS.md` Segment 6 (`:159-171`) C2 ref + fill-contract file in Segment 9.
- Tests: NEW `T/domain/quickadd/QuickAddLearnedCategoryLookupTest.kt` (AE-4 real key fn; null key; not-in-picker → Absent; repo exception → failure, CE rethrown; `verify(exactly = 0)` on every collaborator write). NEW `T/ui/screens/addexpense/QuickAddFillContractTest.kt` (full apply table 3 results × 4 origins × 5 fields incl. blank-text rule; AE-30 idempotence; §5.4 outcomes incl. `NOTHING_RECOGNISED`, `LINE_TOO_LONG`). `AddExpenseViewModelTest.kt` (listed AEs; AE-29 suspended fake lookup; AE-21 clipboard after Fill ignored; Fill never calls `searchMerchants`; `clearCategory` never refilled; capturing Timber: only §7.2 strings, no throwable, no part of the line). `AddExpenseViewModelStressTest.kt` ctor + Fill-vs-edit races if harness allows. AE-40 per H-1.
- Legal path: Fill writes form state only; AE-41 exactly one `addManualExpense`; existing ManualExpenseRepository/TLC tests pin `insertAtomic` + `MANUAL_ENTRY`.
- Gates: RS (fill contract, concurrency), PSG (C2, INV-11/12/13), AG (docs).
- Validation: `targeted-unit-test` `"*QuickAddLearnedCategoryLookup*"`, `"*QuickAddFillContract*"`, `"*AddExpenseViewModel*"`, `"*MerchantKeyGeneratorTest*"`, `"*CategorizationEngineTest*"`, `"*ManualExpenseRepository*"`; `unit-test-shard -Shard runtime-ui`, `-Shard domain-n-z`; `registered-guard -GuardId db_access`, then `db_artifact_sync` (expect unchanged; finding → N1); `static-guards`; `connected-test-class` only if H-1 chooses connected.
- Done when: listed AEs green; no String in state additions, no VM field holds the line; DB guards unchanged, no baseline growth; docs landed with symbols.

### E — Static guards G-1..G-4 (T3 by rule, pre-approved G2-6)
- Covers INV-12 (static part of AE-50), INV-13 (G-3), INV-14 (G-2, AE-54), INV-11/spec R2 (G-4). No source edits.
- NEW `T/architecture/QuickAddPrivacyArchitectureGuardTest.kt`: G-1 no `rememberSaveable`/`SavedStateHandle` under `ui/screens/addexpense`; G-2 `domain/quickadd/**` imports nothing from `android.*`, `timber`, `domain.ai`, nothing from `data.*` except the lookup file's read deps, parser files no `data.*` at all; G-3 no `Timber.*(e, …)` and no interpolated Timber message in `AddExpenseViewModel`; G-4 lookup file references none of `categorize`, `normalize(`, `getCategoryForMerchant`, `MerchantNormalizer`. Conventions: `SourceTextSanitizer` stripping; seeded-violation fixture per guard; non-vacuous scans (count > 0, named files present); no allowlist entries; G-1/G-3 scoped to add-expense; no existing guard file edited.
- Lands before F so the Sheet UI is built under G-1. Gates: AG + PSG; any scope widening/allowlist/existing-guard edit = stop.
- Validation: `targeted-unit-test` `"*QuickAddPrivacyArchitectureGuardTest*"`; `unit-test-shard -Shard architecture-contracts`; `static-guards`.
- Done when: 4 guards green on real tree; each seeded violation fails.

### F — Quick-add Sheet UI + strings (T2)
- C4 per spec §1: INV-12 holder (`remember` only), INV-17 disabled states, INV-20 visibility; ux §2–§11. G2-11 chip + Remove → `clearCategory`; G2-12 single "Fields you edited were kept." line; G2-13 summary stable after edits; G2-14 empty-category caption in every add flow; G2-15 IME Done = Fill, autocorrect off, not Password. After Fill hide keyboard + clear focus. Line hidden in Sheet edit mode if any (U2).
- Source: `AddExpenseSheet.kt` (hosts section; "Filled" markers + flags in amount/merchant `supportingText`; captions under date/payment/category; category chip row + Remove; empty-category caption). NEW `A/ui/screens/addexpense/QuickAddSection.kt` (line `remember`, max 200, counter from 180; Fill button 48dp + content description + disabled state description; summary `liveRegion = Polite`; line hints). NEW `A/ui/screens/addexpense/QuickAddUiMapper.kt` (pure: state enums + form values → `UiText` list; never receives the line). `res` ×3: all `quick_add_*` + `add_expense_category_auto_hint` (ux §11); es shows English example. `docs/features/FEATURES.md` quick-add entry.
- Tests: NEW `T/ui/screens/addexpense/QuickAddUiMapperTest.kt` (each ux §3 state → expected resource ids; Flagged shows flag never "Filled"; kept-edits line iff any origin USER; foreign hint persists after typed amount; summary stable after edits; no output contains the line).
- Gates: CR + PSG (copy never echoes the line; content descriptions); owner reviews Greek (G2-15).
- Validation: `targeted-unit-test` `"*QuickAddUiMapper*"`, `"*AddExpenseViewModel*"`, `"*QuickAddPrivacyArchitectureGuardTest*"`; `lint`; `unit-test-shard -Shard runtime-ui`; `static-guards`.
- Done when: G-1 green; no new MissingTranslation; mapper tests prove every Fill-written field and flag visible; owner Greek sign-off recorded.

### Z — Closeout
- Regenerate `docs/architecture/CODEBASE_INVENTORY.md` (method per ai-fixes D-10); update handoff; no `app/` edits.
- Record follow-ups (G2-17): "(€)"/"Euro" label → manual-entry-currency; spec §15 F2–F5 (F1 fixed by D); runner shard-coverage gap; native Spanish review.
- Run bundle gate; final RS in a fresh session separate from discovery reviewers.

## Guard / config edit assignment
| Edit | Slice | Approval |
|---|---|---|
| `test-shards.json` `domain-n-z` += `domain.quickadd.*` | B | G2-7 |
| CancellationSafety KNOWN_VIOLATIONS: remove `AddExpenseViewModel.kt` | A | G2-8 (removal only) |
| NEW `QuickAddPrivacyArchitectureGuardTest.kt` G-1..G-4 | E | G2-6 |
| `db_ownership_policy.yml`, `db_access_v2.json`, GR-08, `SourceScanningArchitectureGuardTest.kt` | none | any need → N1 / stop |

## Owner plan-gate checklist
- [ ] Base ready (Release 1.0 accepted, `release-1.0-wt` merged, ai-fixes through B2d); S0 before any `app/` edit.
- [ ] Order S0 → A (B alongside) → D → C → E → F → Z; quick-add before loan S11, which rebases (G2-16).
- [ ] UI split out of C into F (app releasable after C: Fill has no entry point yet).
- [ ] H-1..H-4 decided.
- [ ] Greek copy review at A and F; Spanish native review = follow-up unless a reviewer is available.
- [ ] Guard scope as in E; no allowlists.

## Human decisions required
- **H-1 AE-40 proof form.** Rec: JVM write-counting fakes (`verify(exactly = 0)` on every write method of `MerchantCategoryRepository`, `ExpenseRepository`, `ManualExpenseRepository`, `CategoryRepository`) backed by G-4; literal row-count test only if S0 D-9 finds an existing JVM Room harness (no new dependency); optional connected in-memory Room test (`connected-test-class`, AVD `wave5-api35`).
- **H-2 D depends on B** (deviation from impact map "independent"): D reuses C1's canonicaliser to avoid duplicating the §4.5 rule.
- **H-3 D fail-closed behaviour change:** ambiguous clipboard values ("1.500", "3,500", >2 decimals) no longer prefill (today they may prefill a wrong amount).
- **H-4 fifth message in A:** `add_expense_error_currency_loading` (ux §11) included besides G2-2's four.

## New stop-and-ask items
- N1 any `db_access`/`db_artifact_sync` finding, baseline growth, signature drift. Never grow baseline / edit policy without approval.
- N2 any slice needs a Room schema change.
- N3 lint clearable only by baseline refresh.
- N4 new Gradle dependency (e.g. Compose upgrade for `autoCorrectEnabled`, Robolectric, JVM Room harness).
- N5 loan S11 or manual-entry-currency already started/landed on VM/Sheet.
- N6 CancellationSafety removal needs out-of-scope edits, or entry enforced as expired (2026-12-31) before A lands; never extend.
- N7 S0 finds `ui.screens.addexpense.*`, `ui.util.*` or `architecture.*` tests in no shard (second config edit).
- N8 base drift materially changes A (e.g. `:128` already fixed, VM restructured).
- N9 `SaveResult`/validation-message types used outside add-expense (UiText change ripples).
- N10 C2 only buildable on a key function that isn't pure or logs.
- N11 golden fixture/AE contradicts §4 rules → back to spec author; never adjust expected values quietly.

## Risk register
| # | Risk | Mitigation / slice |
|---|---|---|
| R1 | VM/Sheet actively edited (release-wt, loan S11, manual-entry-currency) | S0 diff, ordering, N5, N8 |
| R2 | Category false match becomes learned mapping (accepted) | visible chip + Remove (F); fixture group L |
| R3 | Fill concurrency overwrites user edits (accepted) | INV-18, AE-21, AE-29, stress test; RS in C |
| R4 | Locale amount/date parsing (accepted) | 132 fixtures; tr-TR/el-GR purity (B) |
| R5 | C2 built on side-effecting `categorize`/`normalize` | spec contract, G-4, AE-40, PSG |
| R6 | New package in no shard | B shard edit + shard-report check |
| R7 | CancellationSafety entry file-wide, expires 2026-12-31 | S0 D-3; A removes; N6 |
| R8 | MissingTranslation lint errors | es/el same slice as key |
| R9 | Spec vs fixtures disagree | N11 |
| R10 | Ambiguous clipboard values stop prefilling | H-3; D report test list |
| R11 | Empty-category caption changes plain add flow (G2-14) | neutral styling; owner reconfirms |
| R12 | FG-26 doc guard fails on not-yet-existing symbols | docs land with symbols (B, C, F) |
| R13 | Line leaks via `toString`, logs, content descriptions | enum-only state; AE-50/51; G-1/G-3; mapper never receives line |
| R14 | IME personalised learning can't be disabled (accepted) | copy makes no promise; INV-12/13 |
| R15 | Tests depend on system TZ/locale | injected `now`/zone; purity test |
| R16 | `test-shards.json`/`res` conflicts with ai-fixes/loan | serialise; B only config edit |
| R17 | Hilt graph changes from new ctor deps | `@Inject` ctors, no module; `app-check` |

## Final bundle acceptance gate (validation-runner, serial; poll; stale/missing ≠ PASS)
1. `unit-test-shard -Shard architecture-contracts`
2. `unit-test-shard -Shard domain-n-z`
3. `unit-test-shard -Shard domain-a-m` (pins)
4. `unit-test-shard -Shard data` (save-path pins)
5. `unit-test-shard -Shard runtime-ui`
6. `static-guards`
7. `registered-guard -GuardId db_access`, then `db_artifact_sync` (unchanged vs S0 baseline, no growth)
8. `lint`
9. `connected-test-class` only if H-1 chose connected
10. `app-check`
11. `unit-tests` (last)
`migration-tests` not required. Then fresh-session reviewer-strict final acceptance.

## Deferred
Increment 1b currency (needs manual-entry-currency); time-of-day tokens; `skippedUserEdited` set; quick-add marker on expense rows; voice/AI parsing (increment 2); spec §15 F2–F5; runner shard-coverage check; "(€)"/"Euro" label.

## UNVERIFIED for S0
- D-1 base/branch + line re-check (S0 step 3).
- D-2 loan S11 / manual-entry-currency status.
- D-3 every CancellationSafety-flagged catch in `AddExpenseViewModel.kt` (`:150`, rules `:295-298`) + expiry enforcement.
- D-4 `test-shards.json` entry format; `runtime-ui` covers `ui.screens.addexpense.*` + `ui.util.*`; `architecture-contracts` covers `architecture.*`.
- D-5 declarations/consumers of `SaveResult` + validation-message types; `UiText.StringResource` path.
- D-6 `ClipboardAmountParser` consumers and current handling of "12,50", "1.500", "€12,50".
- D-7 spec U1..U7 (Sheet edit mode; learning user vs auto category; saved merchant display form; diagnostic sink; `categories` flow shape; IME flags / Compose version for `autoCorrectEnabled`).
- D-8 `CategorizationEngine`/`MerchantCategoryRepository` injectable (no module edit); `hilt-bindings-map.md` path.
- D-9 JVM Room/Robolectric harness (ai-fixes D-8) → H-1.
- D-10 multi-pattern `-TestFilter`; CODEBASE_INVENTORY regeneration (ai-fixes D-9/D-10).
- D-11 `db_access` treatment of a repository read from `domain/quickadd`.
- D-12 JVM Compose UI test harness (else F = mapper tests only).
- D-13 names of existing ManualExpenseRepository/TLC dedup + insertAtomic pin tests (AE-41/42/43).
