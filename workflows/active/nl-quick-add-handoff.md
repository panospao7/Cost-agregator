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
