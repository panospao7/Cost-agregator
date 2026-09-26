# CL-05 post-implementation review — rp-27

**VERDICT: FAIL — NOT MERGEABLE.**

**Result: 2/9 DONE-CORRECT, 1 DONE-DEFECTIVE, 1 PARTIAL, 5 NOT-STARTED; 1 newly introduced regression.**

Evidence cutoff: 2026-09-26T00:17:53+03:00. Source reviewed at 213031886935185ad92e7468575985c47a7f687a, before this documentation-only commit. These verdicts assess the inspected work items; they do not certify missing independent guardian approvals or authorize merging an incomplete consumer sweep.

## 1. Governing scope and environment

- Governing document: docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-05-implementation-spec.md, read in full, including its GATE-LIFTED update, PR A/B split, nine work items, money constraints, and fences. The update supersedes the historical blocked/UNVERIFIED wording below it.
- Also inspected the scoped architecture references CODEBASE_SEGMENTS.md, CODEBASE_INVENTORY.md, LEGAL_PATHS.md, and ENGINE_INTERACTION_MAP.md; cluster-map.md addendum A5/A6; and verification-2026-09-25-wave2-s2.md dispositions. Source, not map method lists, determined the findings.
- Working directory: C:/Users/panos/Desktop/cost agregator/ExpenseTracker/build/worktrees/rp-27.
- git rev-parse --abbrev-ref HEAD: rp-27-wip.
- git merge-base --is-ancestor 0ea0316a HEAD: exit 0.
- bug-fixes: 0ea0316a1f7d5fac4587857f3645340e01592ede.
- git status --porcelain: empty at entry and at the final source snapshot. Index also empty. There was no uncommitted lane work to review, stage, commit, or discard. Every verdict row below is explicitly tagged committed.
- Reviewed the complete file-by-file diff bug-fixes..HEAD: 15 files, 1,379 insertions, 217 deletions; seven production files and eight test files. No source edits, subagents, validation executions, or JOURNAL.md edits were made by this reviewer. The only authorized write is this new review artifact.

Lane commits, oldest first:

| Commit | Date, local +03:00 | Scope |
|---|---|---|
| 7241e7c1 | 2026-09-25 22:19:54 | PR A core contract: WI-1/WI-2, five production files and four test files. |
| 6fb265d5 | 2026-09-25 23:19:10 | PR B starts with WI-3: detector, pipeline, and four test classes. |
| 21303188 | 2026-09-25 23:34:49 | WI-3 compile repair: persist unresolved currency using the existing blank sentinel; adjust two tests. |

The A/B code changes are separated by commit. That is not evidence of separate accepted PRs or of the required guardian gates before B. No CL-05 handoff/review approval record was found in the scoped workflow/campaign artifact inventory. Obtain the independent records rather than infer approval from commit messages or successful compilation.

Path abbreviations below: M/ = app/src/main/java/com/yourname/expensetracker/; T/ = app/src/test/java/com/yourname/expensetracker/. Line anchors refer to the reviewed source HEAD, not to this artifact's eventual commit.

## 2. Per-work-item verdicts

| Work item | Verdict | Committed-or-uncommitted | Diff/source evidence and execution disposition |
|---|---|---|---|
| WI-1 — catalog and typed validation | **DONE-CORRECT** | committed — 7241e7c1, unchanged at HEAD | M/domain/currency/CurrencyConverter.kt establishes the union catalog, keeps inactive HRK recognizable, and validates source/target before identity (333–388); legacy entry points use the same catalog without replacing their lookup/TTL policies. M/data/repository/CurrencyRatesRepositoryImpl.kt:74–108 uses catalog/provider intersection and preserves publicationMillis storage. M/ui/screens/currency/CurrencyManagementViewModel.kt rejects inactive/unknown selection without mutation and clears failed conversion state using controlled errors. Human evidence: rates 11/11, settings VM 8/8, semantics 30/30. |
| WI-2 — explicit display boundary | **DONE-CORRECT** | committed — 7241e7c1, unchanged at HEAD | M/data/repository/MultiCurrencyRepository.kt:418–523 validates home/source/target/counts/finite inputs, produces known-target empty zero, delegates typed latest buckets, preserves partial aggregates, and detects all-failed by failure-to-input correspondence. M/domain/currency/CurrencyConverter.kt:489–538 reverses the captured positive finite quote without lookup/rounding, retaining provenance. Controlled compatibility exception is additive in MoneyAggregateResult.kt. Human evidence: repository 35/35 plus semantics 30/30. |
| WI-3 — ambiguous notification currency | **DONE-DEFECTIVE** | committed — 6fb265d5 + 21303188 | The intended kr candidate-home flow, nullable transport, blank persistence sentinel, duplicate-query guards, and approval guard are present. However, the new case-insensitive unbounded alias pass can falsely interpret the English word 'from' as CHF and override an otherwise correct dollar/home resolution: regression R1 below. No successful WI-3 test execution is evidenced at HEAD; the older three attempts failed during production compilation, and the latest detector run remains STARTING. |
| WI-4 — bill reminders | **NOT-STARTED** | committed baseline — no lane hunk | M/domain/reminder/BillReminderManager.kt:162–172 still sums raw source-currency monthly equivalents into Double. M/ui/screens/reminder/BillRemindersViewModel.kt:27–28,46–53 still exposes Double and catches failures as zero. BillRemindersScreen.kt:191 labels reminder.amount with live homeCurrency. No lane contract-test delta or human run for this WI. |
| WI-5 — budget recommendation units | **NOT-STARTED** | committed baseline — no lane hunk | M/domain/budget/BudgetAutopilotEngine.kt:180–214 caps/compares against raw budget.amount; models at 473–521 lack captured displayCurrency/sourceAmountToApply. Both M/ui/screens/budget/BudgetViewModel.kt:328 and :385 still write recommendedBudget directly. BudgetScreen.kt:1241,1252 still formats against live homeCurrency. The new reverse-quote helper is not integrated here. No lane tests/execution for the requested consumer behavior. |
| WI-6 — empty analytics currency | **NOT-STARTED** | committed baseline — no lane hunk | M/domain/analytics/AdvancedAnalyticsEngine.kt:610–623 and :744–750 still call defaultDisplayCurrency() inside both empty factories; call sites :547/:684 do not thread the resolved parameter. Nested weekend/percentile values therefore retain the old problem. No lane tests/execution. |
| WI-7 — recurring insight source labels | **NOT-STARTED** | committed baseline — no lane hunk | M/domain/analytics/InsightsEngine.kt:752–779 still pairs pattern.averageAmount with the adapter's displayCurrency instead of validated pattern.currency; :507 renders with the legacy homeCurrency argument. No added invalid-source warning transport or real-recurring-engine integration coverage. No lane tests/execution. |
| WI-8 — filing-currency category totals | **NOT-STARTED** | committed baseline — no lane hunk | M/domain/tax/TaxEstimator.kt:353–375 still consumes raw category totals, computes a residual Uncategorized amount, and wraps raw sums as filing-currency singleCurrency aggregates. M/data/repository/BusinessExpenseRepository.kt:57–59 retains the suppressed deprecated query. The correct existing projection at M/data/database/dao/ExpenseDao.kt:2518–2530 remains available but is not adopted. No lane tax/repository/Room boundary tests or execution. |
| WI-9 — A6 returned-warning redaction | **PARTIAL** | committed baseline preserved — no lane hunk | M/domain/groups/SharedExpenseBudgetOffsetEngine.kt:128,159,175 already returns only MISSING_RATE strings; :183/:212–214 preserve outer partiality and count. Existing T/domain/groups/SharedExpenseBudgetOffsetEngineTest.kt:83–168 asserts the exact three-code list and real-converter log safety. This is preserved remediation, not a newly introduced privacy breach. Required human execution is absent, and individual/mixed/zero-warning/cancellation boundary coverage remains incomplete. No production redaction patch is justified by the inspected warning producers. |

### Boundary-test assessment

- **WI-1:** Read the added provider-parity/missing-quote tests, selection rejection and stale-result tests, Turkish-locale tests, unknown-before-identity tests, and existing latest/historical policy and publication-date tests. The actual catalog retains the required offered/supported union. Coverage improvement: provider parity derives its membership oracle from the production catalog; an independent fixed union assertion would better catch later accidental removals. The new invalid-code matrix tests typed/latest legacy conversion, not an explicit invalid-code convertAsOf matrix; its historical implementation was also inspected.
- **WI-2:** Read the EUR100 + USD100 = EUR190/count=3 test, empty/zero/net-zero/zero-count cases, partial and all-failed cases, invalid source/target/amount/count cases, filing-target independence, home-resolution/cache/cancellation tests, and reverse-quote/invalid-output tests. The reverse test uses an equivalent EUR/USD 1.1 round trip rather than the spec's literal USD/EUR 0.9 example. Partial results retain source buckets/counts; all-failed Unavailable retains metadata. The adapter does not mistake a legitimate zero or zero transaction count for failure.
- **WI-3:** Added detector tests cover each SEK/NOK/DKK home, EUR/null/unknown home, explicit Scandinavian prefix/suffix precedence, case/whitespace, qualified dollars, symbols, and bare-dollar ambiguity. Helper/PAN scoring assertions were retained with explicit known resolutions. Pipeline tests add normal/oversized blank-sentinel reviews, no unknown-currency duplicate queries, source-link calls, and cancellation. The approval test asserts an error and no transition/create for blank currency. Missing: alias word-boundary negatives and a real-detector-to-pipeline case exposing R1; successful human execution of all four classes. The pipeline tests mock the detector and consequently cannot detect R1.
- **WI-4–WI-8:** There is no lane implementation or test delta for these consumer contracts. Their enumerated mixed-currency, snapshot/rate-change, home-change, empty-locale, real-recurring-engine, filing-target, Uncategorized/count-conservation, and Room-window cases remain required. Preserve existing frequency, Apply-All rollback, hierarchy/clamp ownership, yearly numeric, and 75+150=225 oracles rather than weakening them during implementation.
- **WI-9:** The two existing named redaction tests cover all three failures together and real-converter bounded logging. Other existing success/empty tests assert numeric totals, but not the full empty-warning/count-zero/partiality contract. No individual-failure, mixed-success/failure, or cancellation-specific tests were found in this class. Extend only these missing boundaries, reuse the two existing tests, and obtain human execution evidence; do not duplicate tests or patch already-safe warning strings merely to create a diff.

## 3. Regression findings

### R1 — MAJOR / P2 — word fragments become explicit CHF currency

**Introduced in 6fb265d5; present at HEAD.**

- Location: M/domain/notification/money/NotificationMoneySignalDetector.kt:25,72–92, especially the unbounded alias regex at :75 and new IGNORE_CASE at :76.
- Static counterexample: bestTransactionAmount('Payment $42 from account at ACME', 'CAD'). The explicit ISO pass finds no code. The alias pass matches '42 fr' at the beginning of '42 from', interprets Fr as CHF, and returns EXPLICIT_UNAMBIGUOUS_SYMBOL with confidence 0.90 before the bare-dollar candidate branch is reached. The intended result is CAD with AMBIGUOUS_SYMBOL_RESOLVED_BY_HOME, not CHF.
- Baseline comparison: at bug-fixes, the case-insensitive ISO/alias pass requires a trailing word boundary and therefore cannot match Fr inside lowercase 'from'; the subsequent symbol pass is case-sensitive and also does not match it. The bare-dollar branch can then resolve CAD. The change broadens the false-positive set; this is not merely a pre-existing kr ambiguity.
- Pipeline impact: with parser-null input, the same message satisfies the unchanged dollar currency hint and payment transaction hint at NotificationProcessingPipeline.kt:985. The independently scored amount remains 42, but the false CHF code is carried into known-currency duplicate queries and persisted at :490. 'at ACME' supplies a merchant hint. The approval guard correctly rejects blanks, but cannot reject this nonblank false currency: an ordinary manual approval need not supply a currency override. This can create a wrongly labeled expense; it is not an auto-approval claim.
- Minimal repair direction: give alphabetic aliases token boundaries while preserving attached punctuation symbols and qualified dollar aliases, then add negative word-fragment cases and a real-detector pipeline regression. Do not broaden into a numeric/parser-registry rewrite.
- Verification level: source/diff-derived counterexample, **not an executed test**. No validation was run by this reviewer.

**Regression count: 1.** The unimplemented WI-4–WI-8 mechanisms are existing defects left open, not five additional newly introduced regressions. The nullable String compilation failure in 6fb265d5 was repaired in 21303188 and is not counted as a current source regression.

## 4. Focused preservation and fence checks

- **kr semantics:** Detector :119–135 resolves only a normalized SEK/NOK/DKK home, preserves the candidate set and 0.70/0.45 confidence/provenance, and leaves EUR/null/unknown home unresolved. True ISO tokens are processed before aliases. R1 is a separate alias-precedence defect, not evidence that the basic kr candidate predicate is wrong.
- **Blank sentinel and explicit approval:** PendingReview.kt:68 is a non-null String. Pipeline :382/:490 translates nullable unresolved transport to the existing empty-string sentinel, not to EUR/home. ReviewQueueRepository.kt:146–153 accepts only a nonblank explicit override or nonblank suggestion and returns before status mutation/expense creation otherwise. The production approval repository, entity, and schema are unchanged. Do not 'fix' the spec's literal null wording by adding a schema change or currency guess. A valid explicit override remains the approval path.
- **Duplicates/source links:** Both fallback branches skip currency-equality expense/pending queries only when currency is unknown. Raw-fingerprint/insert dedupe, writeBarrier.runWrite plus database.withTransaction (:311–318), review upsert, source linking (:397/:505), markProcessed, and deferred post-commit diagnostics remain in place. No direct ExpenseDao insert or capture-identity rewrite was added.
- **Privacy:** New error/warning text is controlled; no newly added raw exception message/path/stack trace or notification payload logging was found in the production hunks. Existing pending-review sanitization is still called at :391–392/:499–500 through the fail-closed resolver at :865–869. Existing out-of-hunk logging is not certified wholesale by this delta review.
- **Cancellation:** New VM catches explicitly rethrow CancellationException, including fallback staleness reads. Currency resolution in the pipeline is not wrapped in a swallowing catch; enclosing process/processBatch rethrows at :200/:227. No new cancellation-swallowing hunk was identified. Human WI-3 execution remains missing.
- **Money isolation:** Existing 24-hour legacy-latest versus explicitly selected typed policy and historical lookup behavior remain distinct. reverseDisplayQuote divides by the captured rate without formatting, provider lookup, or intermediate rounding; source/target and quote provenance are retained. The new display adapter uses LATEST_AVAILABLE/Latest; it does not replace historical APIs. No floating-point representation migration, recurrence-factor change, stored-amount relabeling, or CL-16 clamp edit occurred.
- **A6:** All three caller-visible BudgetSpendBreakdown warning producers contain only MISSING_RATE. Count/list/outer partiality logic is preserved. This checks returned content, not merely log sinks. Existing fallback/aggregate-provenance behavior is outside the warning-only permission and was not modified. Execution/coverage gaps keep WI-9 PARTIAL.
- **A5:** FENCED OUT. No redactor or Keystore test is in the lane diff. The independent verification artifact at :121–125 confirms CA-W1-003 as real and assigns the redaction-heuristic fix outside CL-05, with owner/wave assignment to the decision register. The governing CL-05 fence resolves the older cluster-map alternative 'fold into sweep or verify standalone'; this review neither fixes nor declares that external defect resolved.
- **Other fences:** All 15 changed paths are within the governing A/B allowlists. No worker, privacy gate, cloud, backup/restore, entity/schema/migration, DAO query, DI module, guard/config/baseline/allowlist, redactor, tax-rate, or unrelated UI file changed. MoneyAggregateBuilder, CurrencyCode, recurring producer, and production ReviewQueueRepository remain read-only dependencies. No removed/ignored test or relaxed numeric assertion was found; the VM error assertion was changed from raw exception text to the intended exact controlled code.

## 5. Human-executed validation evidence

Discovered directories by LastWriteTime descending, equivalent to the requested ls -t build/validation-runs. Read result.json, test/terminal stdout.log records, and relevant stderr.log diagnostics; checked completion markers. **This reviewer did not invoke the validation runner, Gradle, tests, lint, or guards.**

Every run below has its evidence under build/validation-runs/<run-id>/result.json, stdout.log, stderr.log, and complete.marker. The STARTING run is the explicit exception: stdout.log and complete.marker were absent at cutoff, finished_at/exit_code were null, and zero tests were evidenced.

Exact command reconstruction: each persisted command is 'gradlew.bat ' + the task/selector below + ' --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon'. PR A selectors include literal leading/trailing asterisks as shown; PR B selectors do not. These are recorded commands, not commands executed in this review.

| Run ID | Recorded task/selector | Revision | Result / exit | Actual test evidence |
|---|---|---|---|---|
| vr-20260925-192632-6aaa4757 | :app:testDebugUnitTest --tests *CurrencyRatesRepositoryImplTest* | 7241e7c1 | PASS / 0 | 11 PASSED records; BUILD SUCCESSFUL. |
| vr-20260925-194235-1ce77a5b | :app:testDebugUnitTest --tests *CurrencyManagementViewModelTest* | 7241e7c1 | PASS / 0 | 8 PASSED records; BUILD SUCCESSFUL. |
| vr-20260925-194530-5bab821c | :app:testDebugUnitTest --tests *ConversionSemanticsHardeningTest* | 7241e7c1 | PASS / 0 | 30 PASSED records; BUILD SUCCESSFUL. |
| vr-20260925-194646-4ca71d48 | :app:compileDebugKotlin | 7241e7c1 | PASS / 0 | Compilation only; zero test evidence. |
| vr-20260925-195745-494d8324 | :app:testDebugUnitTest --tests *MultiCurrencyRepositoryTest* | 7241e7c1 | PASS / 0 | 35 PASSED records; BUILD SUCCESSFUL. |
| vr-20260925-202048-9e05453b | :app:testDebugUnitTest --tests NotificationMoneySignalDetectorTest | 6fb265d5 | FAIL / 1 | Production compilation failed before tests. |
| vr-20260925-202355-f1d592de | :app:testDebugUnitTest --tests NotificationProcessingPipelineOversizedAmountTest | 6fb265d5 | FAIL / 1 | Production compilation failed before tests. |
| vr-20260925-202641-3dbb3e6a | :app:testDebugUnitTest --tests NotificationProcessingPipelineReliabilityTest | 6fb265d5 | FAIL / 1 | Production compilation failed before tests. |
| vr-20260925-203649-fe38e3bf | :app:compileDebugKotlin | 21303188 | PASS / 0 | Compilation only; not evidence that WI-3 tests executed or even that test-source compilation passed. |
| vr-20260925-204226-a0a270f6 | :app:testDebugUnitTest --tests NotificationMoneySignalDetectorTest | 21303188 | STARTING / null | No stdout or completion marker; NOT PASS. |

All terminal runs recorded equal start/end worktree fingerprints, e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855. The STARTING run recorded that start fingerprint and no end fingerprint. Revision identity is reported separately; the fingerprint alone is not a source-revision identifier.

The three failed B attempts report E_COMMAND_FAILED and String? versus String errors at NotificationProcessingPipeline.kt:380/:486. The blank-sentinel follow-up repaired that production compilation issue; the later compile PASS does not retroactively make those test attempts pass. No ReviewQueueRepositoryTest run, WI-4–WI-8 run, or SharedExpenseBudgetOffsetEngineTest/A6 run exists in this discovered evidence set.

The five production and four test files belonging to PR A were explicitly compared from 7241e7c1 to reviewed HEAD: no changes. Its four successful class runs therefore support the unchanged core code (84 passing test records total). They do not establish consumer-sweep completion or replace required guardian/review records.

## 6. File-by-file delta inventory

Every row is **committed**; all hunks were read, including the follow-up sentinel repair.

| File | Review disposition |
|---|---|
| M/data/repository/CurrencyRatesRepositoryImpl.kt | Catalog replaces priority whitelist; provider/publication/security path preserved. WI-1. |
| M/data/repository/MultiCurrencyRepository.kt | Validated home boundary, controlled failure text, explicit-target display adapter; general builder untouched. WI-2. |
| M/data/repository/NotificationProcessingPipeline.kt | Nullable result/candidate transport, guarded currency dedupe, blank persistence sentinel; transaction/provenance/sanitization boundaries preserved. WI-3; receives R1's false nonblank currency. |
| M/domain/core/money/MoneyAggregateResult.kt | Additive controlled reason enum and compatibility exception; no global result redesign. WI-2. |
| M/domain/currency/CurrencyConverter.kt | Union catalog, validation before identity, canonical legacy codes, controlled typed failures, captured-quote reversal. WI-1/WI-2. |
| M/domain/notification/money/NotificationMoneySignalDetector.kt | ISO/alias split and normalized candidate home; R1 in the new alias pass. WI-3. |
| M/ui/screens/currency/CurrencyManagementViewModel.kt | Active selector, invalid-choice no-write guard, typed conversion, stale-result clearing, controlled errors, cancellation rethrows. WI-1. |
| T/data/repository/CurrencyRatesRepositoryImplTest.kt | Adds provider/catalog and missing-quote coverage; existing publication/security/privacy oracles retained. 11 passing records. |
| T/data/repository/MultiCurrencyRepositoryTest.kt | Adds display/count/partiality/home/cache/cancellation contracts without removing existing numeric assertions. 35 passing records. |
| T/data/repository/NotificationProcessingPipelineOversizedAmountTest.kt | Explicit known-resolution fixtures; retains amount/PAN scoring; adds nullable provenance. No successful execution evidenced. |
| T/data/repository/NotificationProcessingPipelineReliabilityTest.kt | Known/unknown currency dedupe, source-link and cancellation assertions; mocked detector hides R1. No successful execution evidenced. |
| T/data/repository/ReviewQueueRepositoryTest.kt | Adds blank-currency rejection before transition/create. No execution evidence found. |
| T/domain/currency/ConversionSemanticsHardeningTest.kt | Adds catalog/locale/invalid-code/reverse-quote cases, preserves TTL/publication cases. 30 passing records. |
| T/domain/notification/money/NotificationMoneySignalDetectorTest.kt | New six-method class covers intended symbols/candidate resolution but lacks alias word-fragment negatives. Latest run unfinished. |
| T/ui/screens/currency/CurrencyManagementViewModelTest.kt | Adds catalog/rejection/normalization/stale-result cases; replaces unsafe error-text expectation with controlled constant. 8 passing records. |

## 7. Remaining steps to mergeable

1. **Repair R1 within WI-3's existing fence**, retaining the valid kr/home and qualified-dollar behavior; add focused detector and real-detector pipeline coverage. No source repair was made in this review.
2. **Complete WI-4 through WI-8** against the actual A API, one scoped batch at a time. In particular, implement budget sourceAmountToApply and both Apply guards before treating the reverse helper as a shipped consumer fix. Do not absorb CL-16 clamp or CL-15 universal-classifier work.
3. **Finish A6 revalidation:** retain the safe production producers, fill only missing boundary tests, and obtain a successful human SharedExpenseBudgetOffsetEngineTest run including the two existing redaction methods.
4. **Resolve the already-existing STARTING run through the human/validation owner**, respecting the single-run lock; do not launch a duplicate or call an unfinished run PASS. Obtain terminal result/stdout evidence for all four WI-3 classes after repair, then the remaining specified consumer classes and separate compile evidence. Re-run affected core contracts if later work changes them. No validation was started here.
5. **Supply independent gate records:** architecture-guardian/reviewer-strict for A and B, privacy-security-guardian for notification/error/A6 boundaries, and room-migration-guardian confirmation that WI-8 reuses the existing projection without schema changes. This single reviewer did not impersonate or delegate those gates. Any required money/static-guard evidence must follow the runner and FINAL_CI_GUARD_ACCEPTANCE_GATE.md (FG-17; fail-closed/no-weakening FG-03/FG-06/FG-07/FG-23), not bypass checks or expand baselines.
6. Re-review the final complete delta and execution records. Keep A/B review boundaries; do not mark the lane or campaign complete based on this partial implementation or its compile PASS.

## 8. Cross-lane flag only — CL-15 / rp-29

- The actual additive API is MultiCurrencyRepository.aggregateDisplayAmounts(amounts, transactionCounts, targetCurrency): MoneyAggregateResult. Latest-rate display use is explicit; historical APIs remain separate. Unknown codes fail against SupportedCurrency before typed buckets; do not globally tighten CurrencyCode or remove CL-27's failed-source XXX representation.
- The local all-failed rule at MultiCurrencyRepository.kt:505 uses one failure per input bucket. It must remain valid for zero-amount, net-zero, and zero-transaction-count successes. MoneyAggregateBuilder.kt:138–234 and MoneyAggregate.kt:37 are unchanged: the universal all-failed quality/classification work still belongs to CL-15. Do not mistake this narrow adapter for completion of CA-E-01-004 or count-based universal semantics.
- All-failed adapter output retains aggregate metadata; partial Available retains full source/failure buckets and counts. Preserve this correspondence when changing result semantics, rather than using displayAmount == 0 or transactionCount == 0 as success/failure classifiers.
- MoneyDisplayUnavailableException(reasonCode) is the new controlled compatibility bridge; reverseDisplayQuote uses the captured quote and no second lookup. These are available core primitives, but WI-4–WI-8 have not adopted them. CL-15's spec writer must not assume the consumer sweep is complete or this lane mergeable.
- No CL-15 source, specification, campaign status, or JOURNAL.md was changed by this review.
