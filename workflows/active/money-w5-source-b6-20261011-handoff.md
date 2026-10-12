# Wave 5 Batch 6 source-boundary verification - 2026-10-11

Status: CONDITIONAL - approved bank/health slice implemented; compile and all 269
focused tests pass at the frozen fingerprint below. Local strict review passes.
Independent acceptance and whole-wave gates remain pending; not release/commit-ready.
The next scoped batch is Batch 7 (PDF policy/device evidence), not executed here.

## Authority / state

- Approved plan: workflows/active/money-w5-remaining-implementation-plan-20261009.md,
  Batch 6 source boundaries and R01-R12 original-review reconciliation.
- Previous packet: money-w5-savings-b5-20261011-handoff.md.
- No-further-subagents user amendment remains active. Discovery, implementation,
  test authoring and source review are local, not independent acceptance.
- Branch bug-fixes, HEAD a1c66173219d7d959b0d36859a68176aaf814242.
- Starting mixed tree: 150 tracked changed paths, 52 untracked paths.
  Preserve unrelated/prior Wave 5 hunks; nothing staged or committed.
- Scoped architecture segment/inventory, receipt/bank legal paths, engine impact
  entries, producer/validator/processor contracts and tests were read.
- No Room/entity/DAO/schema/migration, guard/runner, baseline or allowlist changes.
  R13 cancellation baseline ratchet remains unauthorized.

## Files touched so far

- app/src/main/java/com/yourname/expensetracker/domain/receipt/BankStatementParser.kt
- app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt
- app/src/test/java/com/yourname/expensetracker/domain/receipt/BankStatementParserTest.kt
- app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementSourceBoundaryTest.kt (new)
- app/src/main/java/com/yourname/expensetracker/domain/health/FinancialHealthScoreV2.kt
- app/src/test/java/com/yourname/expensetracker/domain/health/FinancialHealthScoreV2Test.kt
- This packet and money-w5-candidate-paths-20261008.md.

## Implementation in this slice

- Resolve bank home settings only to supported CurrencyCode values, with controlled
  invalid/read/empty/timeout diagnostics and existing cancellation propagation.
- Parser accepts unavailable home settings without guessing EUR. ParsedTransaction
  itself requires a three-letter code, so use the existing XXX unknown sentinel
  and UNKNOWN provenance for unresolved rows; no supported currency was added.
- Known-home legacy assumption labels retain ASSUMED_HOME_CURRENCY for compatibility;
  existing downstream Gate B does not accept them as explicit source evidence.
- Remove private EUR helper defaults. Explicit supported row evidence continues
  to parse independently of settings.
- Processor no longer aborts all rows merely for missing home settings. The
  validator's home parameter is verified informational-only, not a money fallback.
  Parser-derived explicit currencies are support-validated; unresolved/unsupported
  post-validation currencies become CURRENCY_UNKNOWN ledger skips before merchant
  work, dedupe or PendingReview insertion.
- Existing write barrier, DomainTransactionRunner, receipt writer, provenance,
  identity/cardinality, dedupe, privacy policy and finalization paths are retained.
  Mixed runs retain the existing failed-run semantics when unknown rows are skipped;
  only independently known rows can create reviews.
- Tests authored for invalid/empty/failed/timed-out/cancelled settings, explicit
  source independence, unknown rows, real processor block execution, mixed rows,
  unsupported validator currency and no actionable work on unknown currency.
  Mocked runner/DAOs do NOT establish actual Room atomicity.

## Original review blocker and approved correction

Initial LOCAL STRICT REVIEW VERDICT: FAIL for Batch 6 reconciliation (historical).

- R06/R14: FinancialHealthScoreV2.calculateRunwayScore currently rejects
  LEGACY_DEFAULT only when CurrencyCode.parse(goal.currency) != targetCurrency
  (app/src/main/java/com/yourname/expensetracker/domain/health/FinancialHealthScoreV2.kt,
  around line 402). Same-home legacy and other uncertain provenance continue into
  aggregateRunwayMoney and affect the runway score as known money.
- Exact matching labels do not prove an old goal's denomination. This contradicts
  the shared savings provenance policy fixed in Batch 4 and leaves original
  money/health acceptance incomplete.
- At the initial review, the remaining-plan Batch 6 file list named bank/receipt
  sources, not this health producer; health edits were held for scope approval.
  The proposed correction was to extend the batch narrowly to FinancialHealthScoreV2 and its
  tests, plus the shared savings currency policy only if an entity/domain adapter
  is required. Do not duplicate provenance business rules or introduce new FX policy.

User subsequently approved the narrow health correction with "ok". The health
producer already uses the domain SavingsGoal type: no shared-policy adapter or
policy edit is needed. Runway now uses SavingsGoalCurrencyPolicy.knownCurrencyOrNull
for every input balance before period/coverage neutral shortcuts, rejecting unknown,
legacy and assumed denominations with existing NORMALIZATION_FAILED. This includes
zero and completed goals and mixed portfolios. Explicit supported foreign balances
still use LATEST_AVAILABLE FX; upcoming bills retain FORECAST_DATE. Scoring weights,
rounding, known-input arithmetic, cancellation and history ownership are unchanged.
Successful health fixtures now declare explicit provenance instead of relying on
the legacy default. New tests cover uncertain provenance across EUR/USD/GBP and
zero/nonzero balances, mixed/finished goals, neutral shortcuts, explicit home money
and three-currency latest FX. Original mixed-bill FX assertions remain unchanged.

Repeated LOCAL STRICT SOURCE REVIEW VERDICT: PASS for the authored slice only.
Issues: None in the narrowed changes. Checks included surrounding call paths,
shared policy ownership, history no-write path, currency support, source identity,
pre-review bank skip placement, write barriers and controlled new diagnostics.
The parser read failure returns immediately, avoiding a false timeout diagnostic.
Independent strict/architecture/privacy acceptance is NOT RUN; the user amendment
requires local roles. Existing broader processor diagnostics/privacy concerns are
not represented as resolved by this narrow patch. Mocked transactions do not
establish Room atomicity. Compile and test execution remain pending below.

## Original-review reconciliation (not closure)

- R01: read buildMerchantBreakdown; projection uses each actual group's transaction
  IDs to select normalized inputs, not nullable/blank merchant-key equality.
- Tax: read ReceiptTaxAllocator and SourceCurrencyAllocation; source-money quality,
  adjustment checks, minor-unit remainder conservation and finite shares retained.
- Receipt parser: read detectCurrency; explicit unsupported monetary tokens return
  unresolved before supported tax/other currency evidence can override them.
- Receipt lifecycle: unresolved parsed total/currency drives money review, not
  automatic expense creation. Explicit create+link delegates to the transaction
  lifecycle owner; no new direct expense mutation path is introduced.
- R02-R05/R08-R10: existing money/pace/savings regression fixtures and prior packets
  cover typed empty versus excluded unavailable, stale recommendations, historical
  quality, chronological completion and 5000-event bounded conversion work.
  Earlier PASS evidence is historical after these bank source edits, not a fresh
  current-tree gate. Source/coverage reconciliation is not independent acceptance.
- R06 is corrected locally above, with focused health tests passing; independent
  acceptance remains pending. R07 cancellation
  ratchet/owner acceptance is still not authorized/closed. R11 PDF/device belongs
  to Batch 7. R12 final stable-fingerprint/whole-candidate acceptance stays pending.

## Validation

- Scoped git diff --check: PASS.
- Compile and fifteen targeted-unit-test profiles: PASS/0, 269 tests, as recorded
  in the fresh terminal evidence below. Full suite/shards, lint/app-check, static
  guards and connected/device validation: NOT RUN for this slice.
- No active runner lock was present at terminal evidence verification.
- Independent strict/architecture/privacy guardians: NOT RUN.
- Approved health correction, fixture repair, local strict re-review and serialized
  compile plus bank/receipt/health/original-review focused regressions are done
  locally. This is not independent acceptance or whole-candidate validation.
- A RUNNING record must be polled, not restarted. PASS needs terminal PASS/0,
  physical complete.marker and equal start/end/current byte fingerprints.

## Next action / constraints

1. Next scoped work is Batch 7 PDF policy/device evidence under its approved plan.
   Local Batch 6 focused gates are satisfied; independent acceptance is outstanding.
2. No Batch 7 source/device work was executed here. Follow its privacy/policy/device
   gates and ask before expensive broader validation; never infer missing gates PASS.
3. Batch 8 still owns atomic balance/history persistence, durable idempotence and
   honest recovery. Batch 5's session retry block is not durable acceptance.
4. No staging, commits, guard weakening or unauthorized R13 baseline edits.

## Initial execution / fixture correction

At fingerprint 0ea77e3616597ecbaee427a302fcdc4dd345beee7a67a3668c833c3c110e9af6:
- compile vr-20261011-194355-21b1229a: PASS/0, marker read; 9m11s.
- *HealthScore*Test* vr-20261011-195327-4610145c: PASS/0, marker read;
  36 tests passed, including all five new provenance/explicit-FX regressions.
- *BankStatementSourceBoundaryTest* vr-20261011-200512-da44ea50: FAIL/1,
  marker read; 2 passed, 2 failed, no false acceptance. Queue stopped before
  subsequent filters. Relaxed PendingReviewDao returned a non-null fabricated
  duplicate candidate, causing the two known-row fixtures to take the real
  duplicate-skip path instead of creating a review.

Correction is test-only: stub the nullable pending-duplicate query to return null
for this fresh-import harness. Existing expected review/ledger assertions remain;
explicit success now also asserts zero duplicates, and unknown rows verify no
pending-duplicate query. No production dedupe behavior or guard is weakened.
Local source re-review: PASS for this correction; focused re-execution pending.
The initial polling shell hit a transient result-file replacement gap; the live
health runner was polled to completion, never restarted or assumed successful.
These initial records are historical after the fixture/doc edit. Re-freeze and
repeat compile/health with bank/receipt/original-review focused filters serially.

## Fresh terminal evidence - after fixture correction

LOCAL VALIDATION VERDICT: PASS for compile and the fifteen named focused filters.
LOCAL STRICT REVIEW VERDICT: PASS for this authored slice; issues: None.
Independent strict/architecture/privacy review: NOT RUN (local-only user amendment).
This does not close full-suite, static-guard, device or whole-wave acceptance.

Commands were serialized through scripts/validation-runner.ps1:
- powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile compile
- Same wrapper with -Action Start -Profile targeted-unit-test -TestFilter '<filter below>'.
- Each live run was polled with -Action Wait; no concurrent validation or RUNNING restart.

| Profile/filter | Run ID | Tests passed | Result / exit |
|---|---|---:|---|
| `compile` | `vr-20261011-200937-4ec3dcf3` | - | PASS / 0 |
| `*BankStatementSourceBoundaryTest*` | `vr-20261011-201007-8c33d225` | 4 | PASS / 0 |
| `*HealthScore*Test*` | `vr-20261011-201927-6944a369` | 36 | PASS / 0 |
| `*BankStatementParserTest*` | `vr-20261011-202102-d36026a2` | 30 | PASS / 0 |
| `*ValidateBankStatementTransactionsUseCaseTest*` | `vr-20261011-202246-582059cf` | 28 | PASS / 0 |
| `*ReceiptParserMoneyBoundaryTest*` | `vr-20261011-202427-872d4556` | 22 | PASS / 0 |
| `*ReceiptLifecycleCoordinatorTest*` | `vr-20261011-202605-6c8c9e5b` | 35 | PASS / 0 |
| `*ProcessReceiptUseCaseHomeCurrencyTest*` | `vr-20261011-202752-fba1b02a` | 7 | PASS / 0 |
| `*AnalyticsViewModelInsightsTest*` | `vr-20261011-202936-fed8be4a` | 8 | PASS / 0 |
| `*ReceiptTaxAllocatorTest*` | `vr-20261011-203120-15420d56` | 4 | PASS / 0 |
| `*SourceCurrencyAllocationTest*` | `vr-20261011-203258-244076be` | 11 | PASS / 0 |
| `*MoneyAggregateBuilderNormalizedRowsTest*` | `vr-20261011-203436-f1b3c74d` | 7 | PASS / 0 |
| `*GoldenMasterVerificationTest.*empty*` | `vr-20261011-203613-a877f4dc` | 2 | PASS / 0 |
| `*ComputeDashboardWidgetsUseCasePaceWiringTest*` | `vr-20261011-203757-ea987536` | 18 | PASS / 0 |
| `*SavingsGoalsViewModelTest*` | `vr-20261011-203941-2744f518` | 32 | PASS / 0 |
| `*SavingsGamificationEngineTest*` | `vr-20261011-204116-d3d3f1bd` | 25 | PASS / 0 |

Total: 16 terminal records (one compile + fifteen focused filters), 269 tests
passed, 0 failed, 0 skipped. Every result.json and complete.marker was physically
read; all markers contained VALIDATION_COMPLETE, status=PASS, exit_code=0.
All result start/end byte fingerprints and the current fingerprint matched:
4730cd5c9ec6174611f60a06f5f63b8f04a532e52da80af950bff69a354de65d.

Per-row durable paths:
- build/validation-runs/<run-id>/result.json
- build/validation-runs/<run-id>/stdout.log
- build/validation-runs/<run-id>/stderr.log
- build/validation-runs/<run-id>/complete.marker

Logs support current-tree health provenance, explicit FX/scoring, bank source
boundaries/identity, receipt unsupported-source rejection, legal lifecycle review,
merchant ID ownership, source tax conservation, empty zero/quality boundaries,
pace ownership, stale savings recommendations, chronological completion and
bounded contribution conversion tests. Mocked transaction/DAO tests still do not
prove real Room atomicity or device behavior.

The earlier two bank fixture failures remain recorded above; their corrected
fresh rerun passes all four tests. No production dedupe or assertion was relaxed.
No source or test files changed after this final frozen queue. This evidence
append and the candidate-manifest status update are documentation-only and alter
the overall worktree fingerprint; these gates are explicitly pre-doc-append,
not a final whole-candidate freeze. The six slice source/test SHA-256 values are
checked unchanged after appending this packet. No active validation is left.

Remaining risks: independent acceptance; full suite/shards and static guards;
Batch 7 PDF/device evidence; Batch 8 atomic durable balance/history persistence;
R13 ratchet authority and R12 whole-candidate freeze/review. Nothing is staged or
committed; no new schema, baseline, guard, allowlist or runner edits in this slice.
