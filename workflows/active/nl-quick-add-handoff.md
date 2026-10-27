# nl-quick-add — feature handoff

Feature id: `nl-quick-add` (roadmap item 2 of the `ai-evolution` program; see `workflows/active/ai-evolution-handoff.md`)
Branch at start: release-1.0-remediation
Status: PLAN APPROVED — PARKED until Release 1.0 accepted + release-1.0-wt merged + ai-fixes B2d landed; next slice S0. DOCS-ONLY (Release 1.0 frozen; implementation post-1.0, after ai-fixes).

## Origin
Owner GO 2026-10-16 ("go quick-add"). Scope source: `docs/features/ai-evolution/idea-portfolio.md` B1 + `docs/features/ai-evolution/critique.md` §5 (reshape: deterministic parser is the product, Nano optional enhancer, text only, prefill into existing add-expense, raw text in memory only).

## Owner decisions carried in (from ai-evolution-handoff.md)
- Input languages: English + Greek, mixed by circumstance; Greeklish (e.g. "kafes 3.50") IN scope.
- Owner's phone has Gemini Nano; Nano = free on-device model for simple tasks; deterministic fallback required for non-AICore devices. No cloud needed.
- Foreign currency in text ("20 usd"): prefill the original currency and convert at save via the existing converter.
- Typed sentence: Option A CONFIRMED — used once to prefill, never persisted anywhere (not as note, not as history).
- Post-save insight: keep, but only via ai-fixes B2 (own setting/capability, on-device only, never cloud).
- Nothing saved until the user presses Save in the existing add-expense screen (TLC legal path).

## Dependencies / constraints
- ai-fixes (plan approved, parked) touches ManualExpenseRepository / insight (B2d) — nl-quick-add implementation after ai-fixes B2.
- AICore/ML Kit GenAI: foreground-only, per-app quota, Prompt API beta, structured output alpha, Greek not listed.

## Artifacts
| Stage | Path | Status |
|---|---|---|
| 1 Brief | docs/features/nl-quick-add/brief.md | v2 DONE (reshape pass used; further reshape → owner) — increment 1 only, T2, deterministic, Fill + per-field fill-if-not-edited, foreign fail-closed, no AI/schema/save-path change; 1 owner Q; 8 domain Qs. v1 archived brief-v1.md. v1: DONE (feature-strategist) — deterministic-first, prefill-only; verified gap: add-expense has NO currency control (Q2); 5 owner Qs, 10 domain Qs; precondition ai-fixes B2 |
| 2 Critique | docs/features/nl-quick-add/critique.md | DONE (feature-critic) — RESHAPE: inc 1 deterministic-only T2 (Fill action, per-field fill-if-not-edited, foreign currency fail-closed); currency control SPLIT OUT as T3 money feature; Nano DEFERRED to inc 2 after ai-fixes B2 |
| 3 Domain rules | docs/features/nl-quick-add/domain-spec.md | DONE (domain-modeler) — 20 INV, 46 AE, fixture taxonomy 132 cases/13 cats, schema NONE, 5 ODs (OD-1..5), risks R1-R4 |
| 4 UX flows | docs/features/nl-quick-add/ux-flows.md | DONE (ux-flow-designer) — line + Fill at top of sheet; live-region summary; Filled markers; removable category chip (R2); foreign hint; ~45 string keys EN/el/es; 6 UX-Qs |
| Gate 2 sheet | docs/features/nl-quick-add/gate2-decisions.md | DECIDED 2026-10-16 (all recommended) |
| 5 Impact & tier | docs/features/nl-quick-add/impact-map.md | DONE (impact-mapper) — T2 upper end (guard slice T3 by rule); schema NONE; db_access expected unchanged; direct VM/Sheet conflict with loan-installments S11; SHARD GAP: domain.quickadd not in any unit-test shard |
| 6 Slice plan | workflows/active/nl-quick-add-plan.md | DONE (feature-planner) — 8 slices S0, A, B, D, C, E, F, Z; T2 upper (E T3); H-1..H-4; N1-N11; APPROVED 2026-10-16 |

## Human decisions
- 2026-10-16 Gate 1 = RESHAPE. Owner (verbatim): "recommended" → critique §5 Q1-Q6 all recommended:
  1. Currency control SPLIT OUT as separate T3 money feature `manual-entry-currency`; quick-add inc 1 fails closed on foreign
     currency (amount empty + hint); inc 1b maps currency markers into that control later. (Supersedes earlier "prefill original
     currency" for inc 1 — goal kept, delivered in 1b.)
  2. Manual foreign expense with no stored rate for its date → BLOCK save with "no exchange rate for this date" (rule for the
     manual-entry-currency feature).
  3. Nano DEFERRED to increment 2 (after ai-fixes B2); inc 1 deterministic-only, no AI capability/toggle.
  4. Line at top of add-expense + explicit Fill action; fills only fields not edited by the user; clipboard-prefilled amount counts
     as not edited (overwritable).
  5. Inc 1 deterministic categories shown visibly and learned as today; inc 2 Nano categories learned only if user taps them.
  6. Generic words accepted as merchants for inc 1; revisit if noisy.
  Owner explicitly confirmed (verbatim): "yes verify the bug" → read-only debugger verification of the possible pre-existing no-rate money defect (already launched;
  alongside; read-only, no app changes).
  Single allowed strategist reshape pass now runs with these answers.
- 2026-10-16 Brief v2 confirmation. Owner (verbatim): "recommended" → Brief v2 CONFIRMED (Gate 1 closed → GO, increment 1 only, T2);
  Q1 YES: add-expense save-error hygiene IN scope (rethrow CancellationException; bounded localized error replacing raw e.message
  at AddExpenseViewModel.kt:439-447); verified no-rate P3 filed under manual-entry-currency (no Release 1.0 action).
- 2026-10-16 Gate 2 DECIDED. Owner (verbatim): "recommended" → all G2-1..G2-18 recommended (docs/features/nl-quick-add/gate2-decisions.md):
  OD-1 clipboard 12,50→1250 fix as own slice; OD-2 + UX-Q-5 hygiene (Result.Error, 4 validation messages, scroll + assertive);
  OD-3/4 flag; OD-5 adjacent-only block; APPROVED stop-and-asks: guards G-1..G-4 in NEW QuickAddPrivacyArchitectureGuardTest.kt,
  test-shards.json domain.quickadd.* → domain-n-z, CancellationSafety KNOWN_VIOLATIONS removal for AddExpenseViewModel.kt (removal only);
  LEGAL_PATHS/ENGINE map corrections; Segment 9 ownership; UX-Q-1..4,6 yes; empty-category caption in all add flows;
  sequencing after ai-fixes B2d, before loan-installments S11 (loan rebases); follow-ups list; T2 upper (guard slice T3); risks accepted.
- 2026-10-16 Gate 3 PLAN APPROVED. Owner (verbatim): "recommended, approved". Recorded: 8 slices + order (S0 → A (B alongside) → D → C → E → F → Z);
  H-1 AE-40 via JVM write-counting fakes + G-4 (row-count test only if existing JVM Room harness; connected optional);
  H-2 D depends on B (single amount-rule owner); H-3 ambiguous clipboard values stop prefilling (accepted);
  H-4 add_expense_error_currency_loading included. N1-N11 stop-and-asks stand.

## Next action
When Release 1.0 is accepted, release-1.0-wt merged, and ai-fixes landed through B2d: run S0 pre-flight (branch feature/nl-quick-add; D-1..D-13; baselines via validation-runner). Must land before loan-installments S11. No app/ edits before that.

## Open risks
- No-rate foreign-currency behaviour VERIFIED (debugger, 2026-10-16): docs/features/manual-entry-currency/no-rate-verification.md —
  PARTIALLY CONFIRMED + already KNOWN P3 (ledger :231-232/:244-245, deferred to 1.0.x). Only Transactions amount sort + min/max filter
  use the raw fallback; totals/dashboard/analytics/budgets/headers do not. Likely frequent because rate refresh is gated on cloud AI
  (HZ01b2-F1 P2; release batch R23 part 1). Not a quick-add item; routed to manual-entry-currency / money-correctness 1.0.1 pending owner.
- Merchant text logged on suggestion failure (AddExpenseViewModel.kt:128) — handle in quick-add slice.

## Domain-spec findings needing owner (Gate 2)
- OD-1 PRE-EXISTING BUG: clipboard prefill "12,50" → amount "1250" (sanitizer drops commas) — fix in own slice? (rec yes)
- OD-2 same error hygiene for VM Result.Error branch (:415-425, raw repository exception.message) (rec yes, VM only)
- OD-3 bare leading number + word ("2 καφέδες", "15 taxi") flagged as quantity (rec flag)
- OD-4 weekday name == today's weekday → flagged, date stays today (rec flag)
- OD-5 ISO codes outside catalog ("thb") block amount only when adjacent/attached (rec yes)
- R1: MerchantNormalizer.normalize(autoCreate=false) NOT side-effect-free (alias row write + raw-name log, MerchantNormalizer.kt:96-104; categorize() logs raw merchant :185) → Fill lookup must use only normalizedCanonicalNameForMerchant + getCategoryByNormalizedCanonical; §7.5 proposes static guards (new guards = stop-and-ask).
- R2: English default category names match ordinary words ("taxi home 12" → Home) → wrong learned mapping risk (UX visibility).
- Modeler judgement calls for UX to revisit: category word removed from merchant only if something remains; 92-day year-inference window; time tokens ignored.
- Spec §15: five pre-existing defects outside this increment (route to follow-ups).

## Impact-map findings needing owner (Gate 2)
- New guards G-1..G-4 in NEW QuickAddPrivacyArchitectureGuardTest.kt (stop-and-ask).
- Validation-config edit: add domain.quickadd.* to domain-n-z in config/validation/test-shards.json (stop-and-ask). General gap: runner has no shard-coverage check — new packages silently skipped by sharded runs (also worth checking for ai-fixes/loan new packages).
- Remove AddExpenseViewModel.kt from CancellationSafety KNOWN_VIOLATIONS (removal only; guard edit sign-off).
- LEGAL_PATHS §Categorization "no persistent side effects" stale vs MerchantNormalizer alias writes → doc correction.
- Sequencing vs loan-installments S11 (same VM/Sheet).

## Slice statuses
| Slice | Status |
|---|---|
| S0, A, B, D, C, E, F, Z | planned |

## Approved global post-1.0 build order (manual-entry-currency Gate 2 G2-1, 2026-10-16)
Release 1.0 (incl. R16, R23 part 1) → ai-fixes → money-correctness-1.0.x (if specced; else after MEC a) → MEC core (a, setter-less)
→ loan S1..S10 (S3/S6a serialised vs TLC work) → nl-quick-add inc 1 → MEC (b) add-expense UI → MEC (c) receipt review
→ loan S11 on the general currency control (plan edits at loan S0: S11 write set, D-8, S6a AUTO_RECORD_DEFERRED_FX) → nl-quick-add inc 1b → MEC inc 1b.
Source: docs/features/manual-entry-currency/impact-map.md §9.

## 2026-10-27 START (owner-approvals item 51b)
- The owner approved starting increment 1 ("I approve").
- Worktree: C:\Users\panos\Desktop\Cost-agregator-nlq.
- Branch: feature/nl-quick-add, from origin/feature/ai-evolution e6967db1. That tip contains Release 1.0.2 (bug-fixes 0a52b066) and ai-fixes merged through Z2 (8d8ad26d), so the S0.1 base preconditions are met.
- The plan header has been corrected as in the main checkout.
- S0.2: manual-entry-currency (feature/manual-entry-currency, which hosts loan-installments) is NOT merged into this base. The owner-confirmed build order is nl-quick-add increment 1, then MEC B1/B2/C, then loan S11. Loan S11 has not started. MEC/loan rebase onto quick-add later.
- Next: S0 discovery (D-1..D-13, line re-checks), then the S0 baselines through the shared serial queue.

## S0 discovery (2026-10-27, scout; orchestrator-persisted, condensed)
Base: feature/nl-quick-add from e6967db1.
- D-1 line anchors. AddExpenseViewModel.kt (500 lines):
  - ctor :73-79 and :126-128 are unchanged. Both A defects are present: `catch (e: Exception)` at :126 with `Timber.w(e, "...for: $sanitized")` at :128, and no CancellationException rethrow.
  - Result.Error moved :415-425 → :418-427 (`result.message ?: ...` at :420).
  - The save catch moved :439-449 → :442-452 (`e.message` at :444, no CancellationException rethrow).
  - setInitialValuesIfBlank moved to :466-495; reset is at :457-464.
  - DynamicString / String error sites for A (H-4): :280, :287, :295, :300, :309, :317, :323, :345 (ownershipResult.message), :415 ("Duplicate expense"), :431.
  - `SaveResult.Error(val message: String)` is at :69, and merchantError/amountError are `String?` (:49-50). A therefore changes the state TYPES (to UiText), not only the catch bodies.
  - AddExpenseSheet.kt (867 lines): prefill block :98-108. The error render is :551-563 (`stringResource(add_expense_error_format, result.message)` at :559); the plan's :550 is stale. scrollState is at :88. There is no liveRegion yet.
  - MainActivity is A/ui/MainActivity.kt. The AddExpense block is :607-619 (plan said :616-623). The FAB clipboard chip at :953-958 is a second ClipboardAmountParser consumer.
  - ClipboardAmountParser.kt :8-22 and AmountInputSanitizer.kt :33-57 match.
  - MATERIAL: none (ai-fixes B2d edited only ManualExpenseRepository: :254 RECOMMENDATION_JOB_TIMEOUT_MS, :256-257 CE-first bounded catch; addManualExpense unchanged).
  - PLAN-vs-CODE: OD-1 (slice D) may be partly absorbed already. The VM's setInitialValuesIfBlank (:485-491, R16) parses through AmountUtils.parseAmount and formats "%.2f", so "12,50" already becomes "12.50" in the VM prefill. What remains for D: H-3 fail-closed behaviour and canonicalisation inside ClipboardAmountParser itself.
  - Existing pins that D would change:
    - AddExpenseViewModelTest :254 / :261 / :268 (clipboard locale cases);
    - ClipboardAmountParserTest :14-38 (raw "1,234.56", "11,234.56").
- D-2:
  - Loan S11 code is absent: no installment references in app/src on this base.
  - mec (feature/manual-entry-currency, merge-base bcd8e921) does NOT touch ui/screens/addexpense (orchestrator-confirmed `git diff e6967db1...HEAD --stat`: only ManualExpenseRepository.kt +33/-2 and values/strings.xml +298).
  - Future rebase conflicts:
    - ManualExpenseRepository imports, ctor tail (currencySettingsRepository) and companion object, plus mec's pre-B2d recommendation catch.
    - mec's bounded `Result.Error(RECURRING_FOREIGN_CURRENCY_ERROR_CODE)` would be hidden by A's "every Result.Error maps to a generic resource" rule. A mapping decision is needed when MEC lands its VM edits.
- D-3 CancellationSafetyArchitectureGuardTest:
  - The KNOWN_VIOLATIONS entry for AddExpenseViewModel.kt is at :145 (plan :150): LAUNCH_CE_NO_RETHROW, MIT-034, expiry 2026-12-31.
  - The scanner is at :218-327 (plan :295-298); the stale-entry check is at :343-355; the expiry is enforced via guardToday().
  - The VM has exactly 2 broad catches (:126, :442), both in launch lambdas; there is no suspend fun. Removing the entry is therefore expected green.
  - Caution for C: a new suspend fun in the VM would bring all its broad catches into the scan.
- D-4: test-shards.json format is `"shards": {"<name>": {"filters": ["com.yourname.expensetracker.<pkg>.*"]}}`, alphabetical.
  - B inserts `domain.quickadd.*` in domain-n-z between provenance (:57) and receipt (:58).
  - runtime-ui covers ui.* and architecture-contracts covers architecture.*. N7 does not fire.
- D-5: SaveResult is used only in the VM, the Sheet and AddExpenseViewModelTest. UiText.StringResource is at domain/model/UiText.kt:12, with `from(resId, vararg)` at :45. add_expense_error_format exists only in values/strings.xml:1944; el/es have no add_expense_error* keys.
- D-6: ClipboardAmountParser has 2 production consumers (MainActivity :611-612 and :953-958); it returns the raw match. Clipboard currency symbols are ignored, which is unchanged by design.
- D-7:
  - Compose BOM is 2024.11.00 (KeyboardOptions.autoCorrectEnabled is available).
  - The Sheet has no edit mode.
  - `categories` is a StateFlow (WhileSubscribed, emptyList initial), so C must cope with empty.
  - The VM logs via Timber directly, and the test has no capturing tree yet (A adds one).
- D-8: CategorizationEngine (@Singleton @Inject; normalizedCanonicalNameForMerchant at :514-517) and MerchantCategoryRepository (@Singleton @Inject; getCategoryByNormalizedCanonical at :49-51) are injectable with no module edit.
  - N10 purity (canonicalizer, greeklishNormalizer) is UNCONFIRMED and needs a deeper read.
  - Extra docs list AddExpenseViewModel ctor deps: VIEWMODEL_INJECTION_MAP.md:66-67, DEPENDENCY_MAP.md:1272, route-viewmodel-map.md:144.
- D-9: Robolectric 4.11.1 and Room scenario tests exist (T/scenarios/*DbContractTest). Whether H-1 can use a literal row-count JVM test is UNCONFIRMED (in-memory Room?).
- D-10:
  - The runner accepts comma-separated -TestFilter (max 50; scripts/validation-runner.ps1:355-370). The "one filter per run" caveat is dropped.
  - CODEBASE_INVENTORY.md has no regeneration script, so it is a manual snapshot refresh at Z.
- D-11: domain code already reads MerchantCategoryRepository (CategorizationEngine, TransactionSideEffectPlanner); there are allowlist entries db_access_allowlist.yml:516 and db_ownership_policy.yml:1193. The effect of a domain/quickadd read is UNCONFIRMED; settle it empirically with the db_access run after C.
- D-12: JVM Compose UI tests exist (createComposeRule in AppLockPrivacySectionTest, CloudAiToggleDisclosureTest; Robolectric; ui-test-junit4), so F may add a Sheet-level Compose test.
- D-13:
  - Existing tests: ManualExpenseRepositoryRecommendationTest only. Dedup/insertAtomic/MANUAL_ENTRY pins live in T/scenarios/TransactionLifecycleCoordinatorDbContractTest and T/domain/transaction/lifecycle/TransactionLifecycleCoordinator{,Update,ConflictResolution}Test.
  - Add `*TransactionLifecycleCoordinator*` to C's filter.
- Deltas: AddExpenseViewModelStressTest does not exist, so it is dropped from A/C. Stale doc anchors:
  - LEGAL_PATHS Categorization :972-998 (stale "Read-only"/"No persistent side effects" at :978/:980);
  - ENGINE map details :89- / :103-;
  - SEGMENTS Segment 9 :224-253, Segment 6 :160-172.
- N-items: none fired.
  - N1 waits on the db_access baseline.
  - N6 is clear until 2026-12-31.
  - N8 is borderline (stale Sheet anchor; OD-1 partly absorbed). The planner reconciles slice D.
  - N10 is unconfirmed.

### S0 baselines (2026-10-27, validation-runner via the shared serial queue; tree 3ef7ab9d), all PASS
- targeted `*CancellationSafetyArchitectureGuardTest*,*AddExpenseViewModel*`: vr-20261027-200307-dfda8730
- unit-test-shard runtime-ui: vr-20261027-200454-427d317f
- registered-guard db_access: vr-20261027-200712-ac487ecc
- registered-guard db_artifact_sync: vr-20261027-201016-030187ad
- static-guards: vr-20261027-201034-31b832fa
- lint (MissingTranslation baseline): vr-20261027-202652-bf8e329a
- Next: the S0 reconciliation amendment (feature-planner, running), then slice A.

### S0 PASSED (2026-10-27)
- The S0 reconciliation amendment is persisted in the plan (owner-approvals item 54).
- Next: slice A (add-expense hygiene), then B (parser + golden suite), D (reduced) and C, one implementer per slice in sequence.
- Validation: a batched A+B targeted run, then the shards.

### Slice A implementation (2026-10-27, implementer; NOT validated)
Files changed (all in the nlq worktree):
- `app/src/main/java/com/yourname/expensetracker/ui/screens/addexpense/AddExpenseViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/addexpense/AddExpenseSheet.kt`
- `app/src/main/res/values/strings.xml`, `values-el/strings.xml`, `values-es/strings.xml` (new keys only)
- `app/src/test/java/com/yourname/expensetracker/architecture/CancellationSafetyArchitectureGuardTest.kt` (removed the `AddExpenseViewModel.kt` KNOWN_VIOLATIONS entry only)
- `app/src/test/java/com/yourname/expensetracker/ui/screens/addexpense/AddExpenseViewModelTest.kt`

New string keys (EN text; el/es are DRAFT, owner native sign-off pending):
- `add_expense_error_save_failed`: "Couldn't save the expense. Please try again."
- `add_expense_error_currency_loading`: "Loading currency settings…"
- `add_expense_error_merchant_required`: "Merchant name is required"
- `add_expense_error_amount_invalid`: "Enter a valid amount"
- `add_expense_error_amount_too_large`: "Amount is too large"
- `add_expense_error_date_future`: "Date cannot be in the future"
- `add_expense_error_format` ("❌ %1$s") is unchanged and still used by the Sheet error card.

Implementation notes:
- State: `merchantError`/`amountError` are `UiText?`; `SaveResult.Error(val message: UiText)`.
- Bounded log codes are private VM companion constants: `MERCHANT_SUGGESTION_SEARCH_FAILED` (Timber.w) and `ADD_EXPENSE_SAVE_FAILED` (Timber.e); no throwable and no text is attached.
- Both broad catches have a preceding sibling `catch (e: CancellationException) { throw e }`.
- The save coroutine has a `finally` that clears `isSaving` (and resets a still-running mutation to idle) on any exit, including cancellation.
- `private fun mapSaveError(result: Result.Error): UiText` is the MEC seam. It is a `when (result.message)` whose only branch today is `else -> save_failed`. Unexpected `Result.Loading` also maps to `save_failed`.
- A private `AddExpenseState.withSaveError(UiText)` helper sets `isSaving=false`, `saveResult` and `mutation`.
- Transfer (2) and OwnershipValidator messages are `UiText.DynamicString` of the existing English constants. OwnershipValidator is not edited.
- Duplicate: the VM's mutation message now uses `UiText.StringResource(R.string.error_duplicate_transaction)`. This is the resource the Sheet's Duplicate card already uses. The mutation message is not rendered anywhere.
- No `suspend fun` was added to the VM.
- Sheet: `MerchantFieldWithSuggestions.error` is now `UiText?`. Merchant and amount `supportingText` use `UiText.asString()`. A `LaunchedEffect(state.saveResult)` waits one frame, then `scrollState.animateScrollTo(maxValue)` on Error/Duplicate. Both Cards are `semantics(mergeDescendants = true) { liveRegion = Assertive }`.
- Error card: the "❌ %1$s" wrapper is KEPT, as ux-flows §9 requires. The Text carries `contentDescription = <resolved text only>`, so TalkBack does not announce the glyph. The Duplicate card text is unchanged.

VERIFY-AT-IMPL outcomes:
- Sheet :180 component: `MerchantFieldWithSuggestions`, a public `@Composable` in the same file. Its `error` param was `String?` and is now `UiText?`. It has no other callers.
- UiTextExtensions :42: `@Composable fun UiText.asString(): String`. It is composable, so it is only called inside composition (the `supportingText` lambdas and the Card content). The non-composable `asString(Context)` overload is at :15.
- Duplicate resource: the Sheet uses `R.string.error_duplicate_transaction`. It is now reused by the VM mutation.
- String assertions on merchantError/amountError existed in AddExpenseViewModelTest (3 places). They were updated to the UiText equivalents.
- The CancellationSafety scanner only inspects broad catches inside `suspend fun` bodies; the VM has none, so the removed entry is expected green. The rethrow is in place regardless.

Tests (AddExpenseViewModelTest): capturing Timber tree added (plant in setup, uproot in tearDown). New cases:
- AE-52 suggestion-search failure logs exactly the code
- suggestion-search cancellation is not logged
- AE-53 IllegalStateException
- AE-53 SQLiteException
- AE-53 save cancellation
- `Result.Error` with a message
- `Result.Error` with no message (seam default)
- `Result.Loading`
- `Result.Duplicate`
- three validation-resource cases: amount too large, future date, currency loading
No test was deleted.

Validation to request: `targeted-unit-test -TestFilter "*AddExpense*,*CancellationSafetyArchitectureGuardTest*"`, then lint (MissingTranslation), `unit-test-shard -Shard runtime-ui`, `-Shard architecture-contracts`, `static-guards`.

### Slice B implementation (2026-10-27, implementer; NOT validated)
Nothing was compiled or run. Slice A files were not touched.

Files (all in the nlq worktree, root `A` = `app/src/main/java/com/yourname/expensetracker`, `T` = `app/src/test/java/com/yourname/expensetracker`):
- NEW `A/domain/quickadd/QuickAddParser.kt` (`class QuickAddParser @Inject constructor()`, `parse(QuickAddInput): QuickAddParseResult`; no Hilt module)
- NEW `A/domain/quickadd/QuickAddModels.kt`, `QuickAddAmountRules.kt`, `QuickAddDateRules.kt`, `QuickAddVocabulary.kt`
- `config/validation/test-shards.json`: `com.yourname.expensetracker.domain.quickadd.*` added to `domain-n-z` after `domain.provenance.*` (one line, nothing else)
- `docs/architecture/CODEBASE_SEGMENTS.md`: Segment 9 lists the 5 files and has a `domain/quickadd/*` boundary note (G2-10). No claim about the clipboard consumer until D lands.
- NEW `T/domain/quickadd/QuickAddParserGoldenFixtures.kt`, `QuickAddParserGoldenTest.kt`, `QuickAddParserPurityTest.kt`

Canonicaliser (single owner of the amount rule; D calls it from `ui/util/ClipboardAmountParser`):
- `QuickAddAmountRules.canonicalize(core: String): FieldParse<AmountText>` (public object, pure; BigDecimal; does not call `AmountUtils.parseAmount` or `AmountInputSanitizer`).
- `core` = money token text with currency markers removed. It performs R0..R7 itself: Value(AmountText("12.50")), Flagged(AMOUNT_*), or Absent for "".
- D usage: pass the regex-matched numeric substring; Value -> `value.text`; Flagged/Absent -> no prefill.

Implementation notes:
- Purity: no clock, no system zone (zone and now arrive in `QuickAddInput`), `Locale.ROOT` only, no logging, no `java.util.Currency`. Imports: `java.*`, `javax.inject.Inject`, and `domain.categorization.GreeklishNormalizer` (instantiated directly; pure; single owner of the Greeklish fold per spec 4.11). No `android.*`, `timber`, `data.*`, `domain.ai`.
- `QuickAddInput` and `MerchantText` redact in `toString()`. The result carries no line, tokens, currency or foreign amount.
- The parser emits all `LineHint`s, including NOTHING_RECOGNISED (all five fields Absent and the line non-blank). A blank line gives Absent everywhere and NO hint. C must not add NOTHING_RECOGNISED a second time.
- Payment: own enum `QuickAddPayment {CARD, CASH}` so the parser files need no `data.*` import (plan G-2). C maps it to `PaymentMethod`.
- Order inside the pipeline: classification, then the non-catalog ISO adjacency pass, then the leading-integer quantity rule (so "20 thb taxi" is foreign, not a quantity).

Golden suite (132 cases), per-category counts vs spec 12:
A 9/9, B 14/14, C 16/16, D 16/16, E 14/14, F 10/10, G 12/12, H 5/5, I 5/5, J 7/7, K 9/9, L 7/7, M 8/8 (total 132/132). `taxonomyCountsMatchSpecSection12` asserts the counts and unique ids.

N11 candidates / spec ambiguities (expected values NOT adjusted; owner/spec author to confirm):
1. AE-12 "20 abc" -> "20.00", merchant "abc" CONTRADICTS spec 4.2 / OD-3 / G2-3 (leading bare integer followed by TEXT is AMOUNT_QUANTITY, e.g. "15 taxi"). The code follows OD-3 (Gate 2 decided). The table pins "abc 20" -> 20.00 / merchant "abc" (D14) and does NOT pin the literal "20 abc". Under the code "20 abc" is Flagged(AMOUNT_QUANTITY).
2. Spec 8 says emoji-only gives FILL_NONE + NOTHING_RECOGNISED, but spec 4.3 rule 8 and 4.10 (and the same sentence's parenthetical) make an emoji token TEXT and therefore the merchant. M04 pins merchant Value(emoji), no hint. Under 5.4 that outcome is FILL_PARTIAL, not FILL_NONE.
3. Spec 4.1 step 3 regex: the space character class was lost in the file (renders as spaces). Implemented as U+00A0, U+202F, U+2009 (the three the task and plan name). U+2007 (figure space) is NOT joined.
4. Spec 4.2 numeric-date regex allows only 2- or 4-digit years, yet 4.7 says a 3-digit year is DATE_INVALID. The shape accepts 2..4 digit years so the 3-digit case can be reached. A 1-digit year is not a date token (falls to a money candidate, AMOUNT_UNCLEAR).
5. "Attached" markers (spec 4.6) are implemented as prefix OR suffix for every marker kind, including the attached-only `e`, `ε` and the alphabetic symbols (examples in the spec are one-sided only).
6. Taxonomy H lists "χθες 5/3"; the table uses the AE-13 literal "taxi 9 χθες 15/10" (same behaviour). F has no "Δευ" case (the vocabulary has it; only "Δευτ." is pinned) because of the count budget.
7. Non-catalog ISO list = full ISO 4217 alphabetic codes minus the 28 catalog codes. Words that are also codes (cup, pen, all, top, mad, bob...) block the amount when adjacent to the number (OD-5 accepted residual). Detached catalog codes include english-word collisions such as "try" (TRY), as specified.
8. R5 wording: for a single dot or comma with exactly 3 digits R3 fires first; for 4+ fraction digits ("3.5055") R5 gives UNCLEAR. "0.000"-style zeros hit R3 before R6.

Tests:
- Golden: every case compared as a rendered string (readable diff, all failures listed); INV-5 on every amount Value under en-US, el-GR, tr-TR, de-DE default locales (`sanitize(T) == T`, `parseAmount(T)` equals the decimal); INV-6 (epoch on localDate, <= now, whole seconds); required AE lines present (AE-10..18); direct canonicaliser table with `parseAmount` agreement on Values and `parseAmount == null` on R4 rows; locale independence of the canonicaliser; embedded catalog codes and symbols vs `SupportedCurrency.catalog`; toString redaction.
- Purity: same input twice (also across two parser instances); identical results under tr-TR and el-GR defaults; identical when the default TimeZone changes; Turkish dotless-i hazard lines. Locale and TimeZone restored in `finally`.
- Hand-computed expectations (DST gap/overlap, 92-day edge) were derived from the spec text and must be confirmed by the first run.

Residual risk: first compile and run are unverified. Likely first-run failure sites are the hand-derived golden strings (check `everyGoldenCaseMatchesItsExpectedFields`, which lists every mismatch) and Kotlin compile details.

Validation to request (B): `targeted-unit-test -TestFilter "*QuickAddParser*,*AmountUtilsTest*"`, then `unit-test-shard -Shard domain-n-z` (report must list QuickAddParserGoldenTest and QuickAddParserPurityTest), then `static-guards`. Batched with A: `*AddExpense*,*CancellationSafetyArchitectureGuardTest*,*QuickAddParser*,*AmountUtilsTest*`.

### Slices A+B pre-validation fixes (2026-10-27, implementer; NOT validated)
Nothing was compiled or run.
- A: `AddExpenseViewModelTest` tearDown is now `try { Timber.uproot(logTree) } finally { super.tearDown() }`. The `Result.Error` with a repository message test now also asserts `loggedLines.isEmpty()` (nothing logged on that branch).
- B emoji-only line (owner-approvals item 57, N11 item 2 resolved): `QuickAddParser.deriveMerchant` (the single place the merchant is assembled) now returns Absent unless the merchant contains at least one Unicode letter or digit (`Character.isLetterOrDigit` over code points, checked after the 100-char cut). Emoji plus letters stays a merchant. Golden M04 changed to no merchant plus `NOTHING_RECOGNISED`. No other golden row had a letterless merchant (checked by search; C16 "taxi 15💰" keeps merchant "taxi"), so no other expectation changed.
- B "20 abc" (N11 item 1 resolved): OD-3 prevails (Flagged AMOUNT_QUANTITY); no code change. Clarification appended to `docs/features/nl-quick-add/domain-spec.md` (also records N11 items 3, 4, 5, 7 as accepted interpretations).
- Validation to request: unchanged from the Slice B entry above (the batched targeted run covers `*AddExpense*` and `*QuickAddParser*`).

### Slice B review fixes (2026-10-28, implementer; NOT validated)
Nothing was compiled or run. Slice A files were not touched. First validation run: only `QuickAddParserGoldenTest.canonicalizeIsTheSingleOwnerOfTheAmountRule` failed ("canonicalize(1,234,567) expected V(1234567.00) but was F(AMOUNT_OVER_LIMIT)"); the test was wrong, not the code.

Files: `A/domain/quickadd/QuickAddParser.kt`, `QuickAddAmountRules.kt`, `QuickAddVocabulary.kt`; `T/domain/quickadd/QuickAddParserGoldenFixtures.kt`, `QuickAddParserGoldenTest.kt`, NEW `QuickAddParserForeignCurrencyTest.kt`; `docs/features/nl-quick-add/domain-spec.md` (Clarification 2026-10-28); `docs/architecture/CODEBASE_SEGMENTS.md` (Segment 9 order).

- ISSUE-1 (test): `1,234,567` now expects `F(AMOUNT_OVER_LIMIT)` (spec 4.5 R7); added `CanonRow("1,000,000", V(1000000.00))`.
- ISSUE-2 (owner-approvals item 58, currency fails closed, recorded in the spec clarification):
  - (a) any TEXT token, or MONEY core, containing a code point of category Sc is foreign unless it is exactly a home marker; an unknown symbol token next to a money candidate is consumed as a never-home marker (so it leaves the merchant);
  - (b) US$, CA$, AU$, $US are covered by (a); they are NOT added as markers, so they are foreign even for home USD;
  - (c) `splitNumberLike` skips one leading minus-class char before the prefix marker and keeps it in the core: "taxi -$5" is FOREIGN (priority) not only NOT_POSITIVE; "-€5" with home EUR is NOT_POSITIVE;
  - (d) the adjacency pass (renamed `markAdjacentCurrencyWords`) also covers the alphabetic catalog symbols Fr R kr lei Ft kn Kč zł лв (new `QuickAddVocabulary.adjacentAlphabeticSymbol`); a home marker never blocks, `kr` is never home; away from the number they stay text;
  - (e) `ISO_4217_CODES` completed (added BOV CHE CHW CLF COU MXV SVC USN UYI UYW VED XAG XAU XBA XBB XBC XBD XCG XDR XPD XPT XSU XTS XUA XXX ZWG); catalog codes are still filtered out.
  - Pinned in the NEW `QuickAddParserForeignCurrencyTest` (the 132-case table and its section 12 counts are intact; no golden row changed expectation).
- ISSUE-3: `deriveMerchant` keeps all text tokens when category removal would leave no letter or digit. "groceries 🛒 42.30" gives category Groceries and merchant "groceries 🛒" (the spec 4.11 non-blank wording read through the item-57 letter-or-digit rule).
- ISSUE-4: raw invisible/special characters removed from the source. The editing tooling decodes a literal `\uXXXX` in file content into the raw character, so instead of `\u` escapes the characters are built from code points (named constants, e.g. `0x00A0.toChar()`): fixtures (B04-B06, C01, C13, K01, K02), golden test (required list, canon rows), foreign-currency test, `QuickAddAmountRules` (minus class, U+FFFF poison), `QuickAddParser` (grouping spaces, punctuation, dashes) and the `×` quantity unit in `QuickAddVocabulary`. Regex-level `\\u00A0` / `\\u00D7` escapes were already in place. Meaning unchanged. A final grep over `domain/quickadd/*.kt` finds none of U+00A0, U+202F, U+2009, U+2212, U+00D7, U+037E, U+0387, U+FFFF, U+2011/2013/2014, U+FE63, U+FF0D, U+0661/0662, U+00B7, U+2026. Visible Greek, currency symbols and emoji in test strings stay literal.
- ISSUE-6: stale N11 comment fixed; "20 abc" (AMOUNT_QUANTITY, merchant "abc") pinned in the foreign-currency test class; INV-5 also asserts `AmountInputSanitizer.parseOrNull(T)` under the 4 locales; NEW brute-force test `canonicalizeValuesAlwaysAgreeWithParseAmountOnEveryShortString` (Value implies parseAmount agrees; the converse is not required by design); the toString test also asserts on `result.toString()`.
- ISSUE-7: spec clarification records that NOTHING_RECOGNISED is emitted by the parser (C1) and not re-derived by C3 (section 5.4 owner moved).
- ISSUE-8: Segment 9 lists the five quickadd entries after the domain/transaction/lifecycle entries.

Validation to request: `targeted-unit-test -TestFilter "*AddExpense*,*CancellationSafetyArchitectureGuardTest*,*QuickAddParser*,*AmountUtilsTest*"`, then `unit-test-shard -Shard domain-n-z`, then `static-guards`.
- 2026-10-28 static-guards known_good_state FAIL (vr-20261027-213956-60eab3e8): active_db_gate 21→22 DB_SIGNATURE_UNRESOLVED from QuickAddParser.addToken(buffer: StringBuilder); fixed by FQN kotlin.text.StringBuilder (no guard/baseline change).

### Slices A + B ACCEPTED (2026-10-28)
- Gates:
  - Slice A: privacy-security-guardian + architecture-guardian (combined) ACCEPT-WITH-CONDITIONS; F1 and F2 applied.
  - Slice B: reviewer-strict ACCEPT-WITH-CONDITIONS; ISSUE-1..8 applied (owner-approvals 57, 58).
- Validation:
  - final tree, all PASS: targeted A+B vr-20261027-212616-88ce3332; domain-n-z vr-20261027-213103-d67badba; runtime-ui vr-20261027-213249-bdc82a3c; architecture-contracts vr-20261027-213451-0b626489; lint vr-20261027-213537-703ba75a;
  - after the QuickAddParser FQN fix: targeted *QuickAddParser* vr-20261027-220938-0a2470a4; known_good_state vr-20261027-221241-e11ca90d; static-guards vr-20261027-221600-56cf13ee.
  - history: the first static-guards (vr-20261027-213956-60eab3e8) FAILED on known_good_state 21→22; fixed without any baseline change.
- The el/es strings are DRAFTS (owner sign-off pending). TalkBack pass on the Error and Duplicate cards is a device item.
- Committed.
- Next: slice D (reduced: canonical clipboard parser plus H-3 fail-closed), then C, E, F, Z.
