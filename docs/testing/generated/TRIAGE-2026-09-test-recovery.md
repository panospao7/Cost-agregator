# TRIAGE — 2026-09 test recovery (rp-25-wip)

**Current rp-27 checkpoint (2026-09-26):** the latest targeted sweep has compile plus seven classes PASS (17 baseline cases covered), then MultiCurrencyAnalyticsTest FAIL at original filter 8. Its latest-lookup fixture correction is NOT RUN. The remaining 29 filters and four production/policy cases remain queued; the last full-suite baseline is still 75 failed events. See the main ledger latest-lookup section and updated 30-invocation resume plan.

Rows follow the pack format: `testClass.method | observed | suspected mechanism | class`
(class ∈ harness | stale | REAL-BUG-CANDIDATE). Rows here are recorded, NOT resolved —
resolution happens through the campaign pipeline (verification + wave planning) or an
explicit human decision. No assertion was changed to achieve any pass in this lane.

## From trusted-tests baseline (vr-20260924-195149: 116 pass / 1 fail)

| Test | Observed | Suspected mechanism | Class |
|---|---|---|---|
| CancellationSafetyArchitectureGuardTest.`every broad catch in suspend functions rethrows CancellationException` | CANCEL-01 violations at `DataRetentionWorker.kt:445`, `TransactionLifecycleCoordinator.kt:2323`, `AppStartupCoordinator.kt:414` — broad catch blocks in suspend functions without CancellationException rethrow | production violation of the repo's own cancellation-safety guard (AGENTS.md worker rule). All three exist at checkpoint ad412aec (pre-lane). Note: `AppStartupCoordinator.kt:414` sits inside the surface rp-22's CL-19 work touches — reviewer must check whether the lane fixes or worsens it | REAL-BUG-CANDIDATE (3 sites; → CL-17/CL-23 sweeps + rp-22 review R3) |


## From Phase A compile-repair execution (2026-09-24)

| Test | Observed | Suspected mechanism | Class |
|---|---|---|---|
| KeystoreInstallationSecretHashingTest.`corpus_amount_and_currency_strings_are_not_falsely_redacted_as_phone` | `"12345.67"` in "Payment of 12345.67 at a shop" gets redacted to `[REDACTED_PHONE]` | privacy redactor phone-number heuristic over-matches numeric amounts — real over-redaction of financial text in cloud payloads | REAL-BUG-CANDIDATE (privacy/redaction; relates to E-01/CL-17 surfaces' data quality) |
| KeystoreInstallationSecretHashingTest.`real phone numbers are still redacted` | FAILED (assertion) | same heuristic family as row above — under/over-match regression in phone detection | REAL-BUG-CANDIDATE (same root cause as row 1) |
| KeystoreInstallationSecretHashingTest.`keystore_provider_class_exists_with_expected_contract` | expected `class InstallationSecretKeyProvider`, was `interface` | test asserts implementation SHAPE (class vs interface) that legitimately changed; assertion update requires a human "update the contract" decision, not a silent coder edit | STALE (decision needed) |
| BackupRestoreViewModelPrivacyDenialTest.`backup privacy denial converges on typed blocked state without message matching` | repository throws `PrivacyDeniedException(ENCRYPTED_BACKUP)`; VM sets reasonCode `PRIVACY_GATE_FAILURE`, test expects `ENCRYPTED_BACKUP_DISABLED` | denial reason-code contract mismatch on the backup path — either the VM lost the specific denial mapping (regression) or the contract was deliberately coarsened (test stale). Surface = CL-18's own files; decision belongs to privacy-security-guardian | REAL-BUG-CANDIDATE (CL-18 surface) |
| BackupRestoreViewModelPrivacyDenialTest.`restore privacy denial converges on typed blocked state` | same shape as row above on the restore path (`privacyBlocked` null where typed state expected) | same denial-contract mismatch family | REAL-BUG-CANDIDATE (CL-18 surface) |
| SpendingMapViewModelPrivacyDenialTest — 4 tests (`gate denial…`, `permission race…`, `permitted fetch…`, `fail-closed…`) | `IllegalStateException: gpsPrivacyBlocked was never set` from the test's await helper | location privacy-denial paths never set the typed blocked state the tests (and presumably UI) expect — privacy UX state gap | REAL-BUG-CANDIDATE (privacy/I-05 surface) |
| ReviewViewModelPrivacyDenialTest — 3 tests | `NoSuchElementException: Expected at least one element` at `ReviewViewModel.kt:249` (`flow.first()` on an empty flow) | flow under denial never emits — needs reading whether producer or consumer is wrong; could be harness (missing stub) or production gap | REAL-BUG-CANDIDATE (unclassified) |

## Phase B baseline (vr-20260924-200526, full `unit-tests` profile — PARTIAL: OOM at ~90min)

Result: 5,230 PASSED / 214 FAILED before `java.lang.OutOfMemoryError: Java heap space` killed the
test JVM (the single-JVM full run cannot complete — the tail of the suite never executed; a sharded
re-run gives the true total). Failure decomposition at the OOM point:

| Family | Count | Classification |
|---|---|---|
| AssertionError | 128 | behavioral — wave-coupled (fix after their cluster's wave changes behavior) or stale (needs per-row triage) |
| OutOfMemoryError | 33 | CASUALTIES of the dying JVM — not real failures; expect most to pass on fresh JVMs via `unit-test-shard` runs |
| IllegalStateException / MockKException / NoSuchElementException / NPE | 26 | mixed harness-fixable (relaxed-mock gaps, Phase A pattern) vs real — per-class inspection |
| Misc (InvocationTarget/UnsupportedOp/Security) | 3 | per-case |

Top failing classes: BankStatementParserTest (17), MoneyBoundaryGuardTest (9),
ExpenseCategoryClassifierTest (7), Keystore (5), FinancialStressForecastEngine (5),
BudgetMonitorStress (5), plus ~100 more classes with ≤4 each (106 distinct classes total).

## Harness repairs applied in this lane (test-side only, intent preserved)

| File | Repair | Effect |
|---|---|---|
| BackupRestoreViewModelPrivacyDenialTest | stub `context.cacheDir` → real tmpdir (relaxed mock returned a mock File with null path → NPE in `createTempFile`) | permitted-restore test stops crashing before its assertions |
| BackupRestoreViewModelPrivacyDenialTest | stub `contentResolver.openInputStream(any())` → real empty stream (relaxed mock stream's `read()` never returns −1 → infinite staging copy = 40-min test-JVM hang) | whole class now completes; hang eliminated |

Both stubs previously MASKED failures as crashes/hangs; the permitted-restore test now executes
its real assertions. Security-exception test's own throwing stub overrides the setup default.

## Phase A execution record (all via validation-runner, rp-25-wip)

| Run | Filter | Result |
|---|---|---|
| vr-20260924-182230 | probe `*RestoreJournalDurabilityTest` | FAIL → 9 compile errors (the expected 5 files) |
| vr-20260924-183616 | probe after repairs | **PASS** — test sources compile at HEAD (first time) |
| vr-20260924-184837 | `*PrivacyDenialTest` | FAIL: 12 failed / 8 passed (pre-existing behavioral) |
| vr-20260924-185304 | `*KeystoreInstallationSecretHashingTest` | FAIL: 3 failed / 9 passed (repaired tests PASS) |
| vr-20260924-185436 | `*LegacyDataConsistencyCheckerTest` | **PASS 5/5** |
| vr-20260924-185627 | `*ReceiptLifecycleCoordinatorTest` | **PASS 28/28** |
| vr-20260924-185958 / 191718 | `*BackupRestoreViewModelPrivacyDenialTest` | TIMEOUT ×2 (pre-stub hang — see harness table) |
| vr-20260924-193441 | `*BackupRestoreViewModelPrivacyDenialTest` after stream stub | FAIL: 2 failed (denial-contract, triaged) / 5 passed — hang eliminated |

Verdict summary: **5 files compile-repaired, 0 assertions changed, 0 tests deleted/ignored.**
Phase A goal (compile + execute) met. Remaining failures are triaged signal, not noise.

## rp-27 additional source findings - 2026-09-26

Source/diff analysis only after `vr-20260926-150117-5ed02288` (TIMEOUT). No new tests or guards were run, and no production change was made for these findings. Full authored-repair details and user-run commands are in `UNIT-TESTS-FAILURES-2026-09-26.md`, section `Further test-only repairs - 2026-09-26`.

| Test / surface | Observed | Mechanism / disposition | Class |
|---|---|---|---|
| `BankStatementParserTest.revolut refund row is classified as DEPOSIT` | Recorded expected one transaction, actual zero | `preFilterRows` performs substring header matching; header token `AM` matches `AMAZON` and discards the legitimate `Refund Amazon` row before classification. Keep the failing test and merchant. Production repair needs transaction/header-context and substring regression coverage. | REAL-BUG-CANDIDATE (source-identified parser row loss; owning intake lane) |
| `AccountingExportRepository.exportExpenses` | Source review during the PDF fixture repair | Broad Exception catch has no cancellation rethrow and puts `e.message` in ExportResult. Cancellation can become an ordinary export failure; uncontrolled exception text can escape the export boundary. Needs cancellation and safe-error tests plus export/privacy review. Unchanged in this pass. | REAL-BUG-CANDIDATE (cancellation/privacy; export lane) |
| `StringDistanceUtilsStressTest.stress - levenshtein performance with 1000 char strings` | Recorded runtime exceeded the 50 ms assertion while computing distance 1000 | No isolated new measurement. Contention/JIT/GC versus genuine performance regression remains unclassified. Distance assertion, workload and deadline remain unchanged; do not silently relax or remove them. | REAL-BUG-CANDIDATE (performance, unconfirmed) |
| `AccountingExportRepositoryTest` real PDF output case | Desktop JVM test invoked Android PdfDocument without an appropriate runtime | Moved the real repository/exporter output assertions to `AccountingExportPdfRepositoryInstrumentedTest`; added a distinct JVM delegation/byte-forwarding test. The local native runtime has no Windows library. Device-backed real-PDF validation is required and NOT RUN; JVM green alone cannot close this case. | harness (platform separation authored; validation pending) |

Additional harness finding: RawPersistencePolicyConsolidationTest flags the explanatory comment naming the replaced settings property in BankStatementLifecycleProcessor, not a live settings read. A draft using the existing sanitizer was withdrawn on source review because that sanitizer also erases executable string-template expressions. The original ownership guard remains unchanged and red pending a template-aware, non-weakening scanner repair; no allowlist or baseline exception was added.

The earlier OOM-only classifications are provisional, not proof that every affected test is merely an infrastructure casualty. Subsequent fixture defects and timeouts were found independently; only fresh isolated runs and a terminal full-suite run can establish the remaining failure set.


## rp-27 completed-suite refresh - 2026-09-26

Persisted `run vr-20260926-181440-25aa269d` completed with terminal FAIL, exit 1, no timeout. Exact Gradle/test-event totals are **7,130 completed = 6,760 passed + 75 failed + 295 skipped**, with 36 failing classes (not 76 test failures). HEAD in the result and current source is `2544298d`. The single OutOfMemory occurrence is a passing defensive-test name, not an OOM event. Completion in this run does not prove permanent memory recovery, repair causality, or non-regression of newly visible tests.

A bounded follow-up authored **test-only** repairs in MoneyBoundaryGuardTest (9 recorded failures), DailyAverageFlowTest (1), BackupRestoreRoundtripGoldenTest (1), WorkerRestoreBarrierIdempotencyGoldenTest (1), and RecommendationCacheServiceTest (2). Manifest fail-closed behavior, calendar-day denominator, typed maintenance-mode semantics, actual blocked write/no-mutation behavior, and controllable-clock TTL behavior were preserved or strengthened. No production source, golden values, policy manifest, baseline, allowlist or exclusion was changed. The missing-manifest and all-non-NORMAL-mode controls add coverage; the former constant no-mutation/TTL claims now observe the behavior they name.

**Validation NOT RUN for these edits; no failures closed.** The other 61 recorded cases in 31 classes remain open, without assuming all are outdated tests. The complete 75-case inventory, source diagnoses, validator-edit observations, scoped file list and serial validation commands are in `UNIT-TESTS-FAILURES-2026-09-26.md` under `Completed-suite baseline and next repair pass - 2026-09-26`. Existing parser/export production findings remain open. Independent strict/privacy gates and user validation remain pending. No subagents, builds/tests/guards, commits, pushes or merges were performed by this follow-up.

## rp-27 remaining-test clusters - 2026-09-26

A further **test-only** pass authored repairs for 30 baseline failure events in 12 classes: CrossSourceVerification (4), GoldenMasterVerification (4), EffectiveAmountPipelineIntegration (1), GoldenAnalyticsDataset (1), TransactionTargetedUpdateSideEffects (4), ReceiptProcessingPipeline (3), CsvImportRfc4180 (1), ExportOptionsViewModel (1 initialization failure), RecommendationStateManager (2), ReviewViewModelPrivacyDenial (3), SpendingMapViewModelPrivacyDenial (4), and BackupRestoreViewModelPrivacyDenial (2). Real normalization/legal-path wiring, exact money and CSV assertions, nonzero/negative-control data, typed privacy states and fixture scope ownership were preserved or strengthened.

The earlier empty-stream backup fixture allowed a null-blocked-state assertion to pass on format rejection without reaching restore. It now supplies a real v1 header at the mocked repository boundary and requires actual repository invocation plus successful terminal state; this is not encrypted-backup integration coverage. The OCR error test similarly now proves that the configured OCR exception, not Uri.parse's unrelated JVM null failure, caused the outcome. The map fixture now lets the Main scheduler run while awaiting IO-driven state rather than blocking it with sleep.

**NOT RUN; no failures closed.** Cumulatively, these two completed-suite follow-ups target **44 of 75 recorded failures across 17 classes**, leaving 31 cases in 19 classes without a repair. These are authored-work counts, not a new suite failure total. Exact baseline remains 7,130 test events / 75 failures in `vr-20260926-181440-25aa269d`. Export class initialization recovery may reveal additional failures.

Production follow-ups (source evidence only, unchanged) were added to the main ledger: swallowed cancellation at post-commit event emission; arbitrary exception detail entering side-effect failure metadata/Throwable logs; stale map denial state after later GPS success; and unvalidated exception reason strings at the backup UI boundary. Existing parser/export findings and guard-scanner debt remain open. No assertion weakening, exclusion, baseline/allowlist growth, production edit or commit was made. Manual source/diff read-back is complete; independent strict/privacy review and all validation remain pending.

See `UNIT-TESTS-FAILURES-2026-09-26.md`, section `Remaining-test repair clusters - 2026-09-26`, for all file paths, independent oracles, production evidence, the unchanged 75-case inventory, and consolidated serialized user-run commands. No subagents, builds, tests, lint or guards were executed in this pass.

## rp-27 final remaining-inventory pass - 2026-09-26

Reviewed the final 31-case / 19-class queue. Authored **27 additional baseline-case repairs across 15 test classes**, plus five complementary deduplication/scanner tests and stronger accidental-pass controls. The cumulative candidate count is **71 cases / 32 classes, all NOT RUN**, not a new suite verdict. Full paths, scope limitations, original 75 case names/log anchors and the latest 37-filter serial validation plan are in `UNIT-TESTS-FAILURES-2026-09-26.md`, section `Final remaining-inventory repairs - 2026-09-26` and its following consolidated commands.

Remaining unrepaired cases are intentionally visible:
- BankStatementParser: AM substring filtering discards Refund Amazon (production).
- CategorizationPipelineIntegration: AmountUtils validates grouped digits before removing a currency prefix; raw amount input is also logged by rejection branches (production / privacy follow-up).
- TransactionLifecycleCoordinatorDbContract: STANDARD dedupe writes one duplicate event inside the transaction and another in outer insert-conflict handling (production; keep expected two total events, not three).
- DbGuardPolicyFixture: pinned 406 versus 412 policy entries, now traced to six exact RP-14 RetentionModule additions in `fea8cdfe`; FG-06/FG-07 approval remains required before altering the pinned count.

The earlier privacy-guard comment false positive now has a **test-local comment-only, executable-template-aware masker** with positive/negative and malformed-input controls. All source ownership targets/selectors remain unchanged; no blanket string/template erasure, exception or policy growth was introduced. AIML-21 semantic-span tests now separately cover retaining distinct spans and collapsing generation-time shifts; absolute/custom-window aliasing remains a product-contract follow-up rather than being silently certified safe.

All new code edits are test-side; the production Kotlin fingerprint matches the start of this pass. The pre-existing worktree still contains earlier production edits. No builds/tests/Gradle/lint/guards, subagents, commits, pushes or merges were performed. Source/diff read-back is not runtime validation or an independent reviewer/guardian approval. Existing production findings remain in the main ledger; no baseline failure is marked closed.

## rp-27 MultiCurrency latest-lookup follow-up - 2026-09-26

Read persisted results from compile `vr-20260926-195154-ff840103` through `vr-20260926-200442-9dbc1093`. Eight gates passed: compile plus seven test classes. MultiCurrencyAnalyticsTest then had 3 passed / 1 failed. The seven class passes cover 17 baseline cases; privacy-denial-specific filters later in the list were not reached. Full run IDs and counts are in the main failure ledger.

Cause: the earlier fixture stubbed ExchangeRateStore.getRate, but convertMultiple selects LATEST_AVAILABLE and calls getLatestRateForPair through convertOutcome. The fresh USD rate was therefore unused. Corrected those stubs, made the store strict and added a real-converter EUR 140 / single JPY MISSING_RATE control. The exact JPY-only repository message, aggregate DAO assertion and no-raw-scan contract remain; zero legacy/historical lookup assertions were added. No production change or acceptance of the spurious USD failure.

Correction NOT RUN; no coder validation, subagents or commits. Main ledger now has a resume block: retry original filter 8, then the 29 unrun filters, with stop-on-fail. Four unrepaired production/policy cases and broader diagnostics remain separate. Earlier all-NOT-RUN checkpoint paragraphs are historical, not the current targeted validation record.
