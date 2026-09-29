# Independent complete-scope Wave-2 review — September 27, 2026

## Verdict and review boundaries

**VERDICT: FAIL — Wave 2 does not yet justify blanket technical closure or merge approval.** All seven requested clusters and A1–A8 were considered. This is one independent reviewer’s source/evidence assessment, not an implementation pass, not a replacement for human-run validation, and not an impersonated architecture/privacy guardian approval.

The review found a newly failing cloud-proof acceptance test, an exact-path exemption-policy gap, an original OCR finding only partially covered, and a newly authored forecast success test inconsistent with its production path. The latest Money Radar class also fails three happy-path fixtures; the independently traced cause contradicts the newest journal routing diagnosis. Several fixes have meaningful passing execution evidence, but the full static-guard profile remains FAIL/exit 2, with missing runtime coverage and unresolved infrastructure debt. A historical human waiver for CL-05 is respected; it is neither technical proof nor a waiver for CL-09/15/21/22/23 or A2/A7.

No reviewer subagents were used. No Gradle, pytest, compilation, lint, guard, or validation-runner process was started by this reviewer. No application, test, configuration, baseline, allowlist, existing campaign record, or git-history write was made. Existing concurrent human validation was observed read-only. Any report-file creation must occur only after checking validation is idle; otherwise this report remains unsaved rather than perturbing a live fingerprint.

### Scope and authoritative inputs

Read AGENTS.md and local scope/review-strict/fix-ci guidance, then bounded architecture ownership/inventory/legal-path/engine-map sections. Reviewed original campaign audit findings and verification material, cluster-map/findings-ledger, stage3-wave2-spec-prompts.md, stage3-wave2-coder-prompts.md, decision-register material and human decisions, relevant specifications and review records, CL-05-review.md section 8, STATE.md/JOURNAL.md, and the four requested current handoffs. Coder-authored specs and self-reviews were treated as assertions, not substitutes for original requirements or independent gates.

Paths below use M = app/src/main/java/com/yourname/expensetracker; T = app/src/test/java/com/yourname/expensetracker; C = docs/analyses and debug master/campaign CA-2026-09-21. Source line anchors refer to the inspected checkout. Original-finding sources are C/cell-<cell>-audit.md at the named finding, supplemented by its findings-ledger and verification disposition. CI policy references are to FINAL_CI_GUARD_ACCEPTANCE_GATE.md by FG-ID only, particularly FG-03, FG-06, FG-07 and FG-23.

## 1. Reviewed snapshot and provenance

Branch: bug-fixes. HEAD: a48076322e57f3d312f34cf6a71139efc4fcc48b. Initial index was empty; 90 tracked files were modified and 47 files were untracked (137 dirty paths). The full byte manifest is appended. A branch name or HEAD alone is not the review identity.

Initial read-only snapshot at 2026-09-27T12:05:22.3607384+03:00:
- Custom HEAD/status/dirty-byte snapshot SHA-256: 2e85f01d7cb7561c7cc99a5246887ebdde6692481ee2f99ed577f5961f2f6655.
- Runner-compatible dirty-worktree fingerprint S0: 4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56.
- Rechecks through 12:25 found no dirty-path byte changes.

One subsequent human/validator-side repair was observed during review, between completed failed validation and its rerun: T/data/repository/DatabaseBackupRepositoryImplTest.kt:1790 switched the new statistics cancellation test to its already-existing non-copying IdentityCancellationException. The strict assertSame remains. No production change accompanied that repair. Its source and sentinel definition (:391–393, createCopy returns null) were reread. At 12:38:55 and 12:42:50+03 the 137-path snapshot was S1:
- e42476881bee5aca837972f74099f387a41e1e9b17cd7523fa1816ddfb275a5d.
- Changed test SHA-256: 8b1e487cdd17eaa41bf33b82a9087215cf61ac1ef06d4b808a48069f57e6e5f3.

This is a reviewed, bounded snapshot transition, not permission to attach S0 passes to arbitrary later edits. The evidence appendix identifies each tested HEAD and dirty fingerprint separately. A new review report itself changes a future runner fingerprint and must not be confused with the pre-report source snapshot. Git reported an inaccessible .pytest_cache during untracked enumeration; do not interpret ignored/cache enumeration as a complete filesystem inventory.

### Final observed documentation-only snapshot transition

At 2026-09-27T12:52:26.1203131+03:00 the human appended a validation handback to C/JOURNAL.md. No application/test bytes changed from S1. This produced S2 dirty fingerprint 0db5789214bc233439569e7377981fc60613fcd2b18dd044afc1c5ff3f6d9c11. JOURNAL SHA-256 became a3516b3f481f84fac8ffa58e3d9f0aea68a413bb4674ea6ea420e42e4d38a18c. The global validation lock was absent at that observation. The newest journal diagnosis is preserved as history, but corrected above against source and actual failure evidence.

### Correct comparison ranges

Verified ancestry rather than relying on navigation hints:
- Audit pin: 37601232b9778170c57a656a245b199ab6d7d965.
- CL-27 merge: 0ea0316a1f7d5fac4587857f3645340e01592ede; first parent 91adfb1c9d1e0cabcaa97d60c78f5fecdcec697e; lane parent ae324a6a5946d656b7b833134a86504a38c3b321. Its implementation comparison is 91adfb1c..0ea0316a.
- CL-05 merge: 03f197d162d81e5a8821e2d82b116d71b04b9e99; first parent CL-27 merge, lane parent 41a94a926e431bd5b42b021a0696e91e59f4ebb0. Its comparison is 0ea0316a..03f197d1.
- Remaining five clusters/A2/A7: a4807632..reviewed dirty worktree, including later test repairs. They are not committed.
- CL-05 production repair commit 3b7cabfe and test recovery c8f4c383 already contain A1/A5 and the first four validator-side corrections. They are not newly introduced by the remaining batch. HEAD a4807632 adds the human close record after the CL-05 merge.

Historical tested revisions ac9e5748, ae20fdcd, 459a4651, 7241e7c1, 315abb30 and 2544298d were verified as ancestors. Historical dirty fingerprints still require their own qualification; ancestry does not prove equivalence of all dirty source bytes.

### Manifest discrepancies and unrelated changes

The handoff file manifest lists 131 paths, not the complete 137-path dirty state. Missing from it: .codex/agents/explorer-lite.toml; .codex/agents/orchestrator.toml; C/JOURNAL.md; docs/analyses and debug master/CL-29-wave1-implementation-report.md; docs/architecture/COVERAGE_MATRIX.md; docs/prompts/master-cross-codebase-audit-campaign-prompt-v3.md. JOURNAL contains human validation updates; the other five are not silently adopted as remaining-Wave-2 implementation. All were preserved.

The first four validator-side corrections were committed in CL-05: CloudPiiSanitizer raw regex, SemanticKeywordMatcher raw regex, HistoricalSpendingDistributionBoundaryTest interface import, and ReceiptRepositoryStatementDuplicateTest typed any<Uri>() matcher. The remaining batch retains the assertNull import and human-authorized explicit empty UI-DAO allowlist. The latter creates zero exemptions and is not an allowlist broadening. Later receipt, recurring, and backup identity-sentinel repairs are separately acknowledged; they must not disappear behind the stale handoff.

## 2. Evidence-backed issues

### W2-R1 — P2: the new cloud-proof engine rejects an accepted prepared string-template helper

At scripts/guardrails/test_cloud_payload_proof.py:106–113, the new positive case returns a string interpolation of prepared.text from serialize(). The shared proof reports an unproved POST at line 14 instead of accepting it. This is an **observed failure**, not merely a speculative counterexample: build/ci/static-guards/guard_tests.log:4361–4372, run vr-20260927-084139-95dbc7df.

Source mechanism: scripts/guardrails/cloud_payload_proof.py:481–491 obtains the return-expression start from a whitespace match over masked source. Masking the quoted template prefix allows that whitespace match to consume original-expression characters before the live interpolation. Parsing at that offset leaves a trailing fragment and rejects the otherwise approved expression. This is a false positive in the new proof, not evidence of a production payload leak. Do not suppress the case, weaken provenance checks, or narrow recursive collection. Preserve both its positive prepared template and negative rawInput template assertions.

C/JOURNAL.md:100 calls test_cloud_payload_proof PASSED, but the actual new module has this failure. A production guard-script PASS and several successful provider fixtures do not make the entire proof module pass.

### W2-R2 — P2: cancellation exemptions still permit basename/suffix matching

scripts/verify_cancellation_boundaries.py:256–264 rejects malformed paths but then accepts actual.endswith('/' + expected). Thus an entry path Service.kt matches /repo/app/src/main/java/Service.kt. The new test helper defaults to Service.kt, and scripts/test_verify_cancellation_allowlist_scope.py:109–117 expressly expects that match.

Exact rule, owner, receiver, method and parameter matching are substantial improvements; they do not satisfy the exact repository-relative path requirement in FINAL_CI_GUARD_ACCEPTANCE_GATE.md FG-06. This is a remaining enforcement gap, now codified by a new positive test. No actual production exception-list broadening is alleged. Fix the path contract and corresponding fixtures without adding exceptions or increasing baselines (FG-06/FG-07/FG-23).

### W2-R3 — P2 residual: CA-P-03-003 is narrowed to the statement path, leaving generic PDF receipt intake without partiality

Original C/cell-P-03-audit.md:67–75 expressly includes both statement processing and the camera/PDF route. The statement implementation now carries OcrResult.isPartial into PDF_PARTIAL, import completion status, summary metadata and the review UI. However M/data/repository/ReceiptRepository.kt:162–169,300–306 carries only page counts, and M/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt:338–359 discards those counts when constructing the generic receipt lifecycle result. Its saved-event path (:585–622) still derives processing status without OCR partiality. No pagesProcessed/totalPages/failedPages consumption was found in that coordinator.

Consequently, successfully parsed surviving pages can still appear as an ordinary successful generic PDF receipt. No explicit approved deferral of that original surface was located. This is not an assertion that the repaired bank-statement path still reports COMPLETED. The new OcrResultPartialTest and BankStatementCompletionStatusTest prove helpers; the ReviewViewModel test mocks a prebuilt partial result. They do not establish a real partial OCR → persisted import ledger → UI integration. Keep this original finding PARTIAL until the omitted path is addressed or explicitly dispositioned and the integration evidence is supplied.

### W2-R4 — P2 test/contract blocker: explicit-currency forecast success is contradicted by its real baseline path

T/domain/forecasting/FinancialStressForecastEngineTest.kt:111–119 supplies EUR explicitly, makes homeCurrency() throw, then requires an available three-horizon result and zero settings reads. Production M/domain/forecasting/FinancialStressForecastEngine.kt:90–101 resolves the explicit currency but then calls resolveStartingBalanceBaseline(); :678–680 unconditionally calls resolveDisplayCurrency(null), reading those settings again. The outer catch now correctly returns an unavailable result, so the new positive test cannot satisfy its asserted contract on this source.

This is a source-demonstrated contradiction, **not a claim that this test was executed by the reviewer**. The double-resolution predates the remaining batch; the newly authored acceptance test is nevertheless unsatisfied. The same pre-existing baseline helper also has runCatching/getOrDefault(0.0) fallbacks (:681–695), which deserve separate failure/cancellation scrutiny rather than claiming that every internal forecast failure is now represented as unavailable. Do not remove the success-control test or weaken its assertion merely to get green; decide and implement the intended explicit-currency contract with a focused test.

### W2-R5 — Blocking closure evidence: full guards remain failed, not waived

The latest inspected full static run vr-20260927-084139-95dbc7df has exit 2, 17 passing legs, six violation legs, and two infrastructure-error legs. Its recursive pytest execution completed with 15 failed, 4302 passed, 28 skipped. Completion, a compiler PASS, and a guard-name match are not full acceptance. FINAL_CI_GUARD_ACCEPTANCE_GATE.md FG-03 forbids treating unknown/infra/skipped required evidence as GREEN; FG-06/FG-07/FG-23 prohibit repairing the report by widening exemptions or weakening the checks.

This does not mean all 15 failures are new application regressions. Their attribution is separated below. The campaign explicitly required a full guard run before CL-22 merge; performing that run enumerates blockers but does not clear them.

## 3. Independent adjudication of failed validation and prior diagnoses

### The 15 recursive Python failures are not one homogeneous regression

Evidence: build/ci/static-guards/guard_tests.log:4357–4487 from vr-20260927-084139-95dbc7df. Counts below account for all 15 failures; no failed test is discarded from the gate.

| Count | Failure family | Independent disposition |
|---:|---|---|
| 1 | test_ci_workflow_evidence_gate::test_evidence_gate_pin_is_caller_stated_from_rev_parse_head | Old test demands an exact echo command; workflow now emits the pin with printf after validating HEAD/base. Stale assertion introduced by the workflow refactor, not missing pin semantics. Still a required failing test. |
| 1 | test_cloud_payload_proof::test_string_template_dependencies_are_not_erased_as_constant_literals | Newly added positive prepared-template case actually fails. New proof defect W2-R1; JOURNAL's module-PASS claim is wrong. |
| 1 | test_db_guard_run_cache::test_callable_cache_never_shares_resolutions_across_indexes | Fixture passes owner Repo while parser finds com.example.Repo; parser :1409 skips nonmatching owner, giving [] before the intended cache assertion. Test and parser bytes are identical in retained rp-27. Newly collected pre-existing fixture/API mismatch, not demonstrated cache contamination. |
| 1 | test_migrate_db_policy_seed_rows::test_combined_seed_file_concatenates_all_twenty_seven_batch_seed_files | Combined-versus-batch seed mismatch, first reported around RetentionModule entry index 183. The relevant files were not changed by remaining Wave 2. Requires exact artifact reconciliation, not assertion relaxation. |
| 6 | test_migrate_db_policy_signatures distribution / candidate bytes / accounting bytes / verify happy path / verify distribution / seedless regeneration | One stale expected resolved count is 53 versus actual 55. Other failures show candidate entry 376 and accounting record 14 drift. The retained rp-27 db_artifact_sync.log has the identical serialized lengths and first-difference positions; this is not evidence that the new receipt/warranty CAS caused that drift. Still blocking. |
| 1 | test_verify_cloud_payload_boundaries::test_prepared_cloud_payload_path_passes | Old synthetic compliant fixture lacks the canonical toRequestBody import now required by the stricter proof. It must be made representative while retaining the security contract; this failure alone is not a production serializer regression. |
| 4 | test_verify_db_access_boundaries structural manifest/active policy and test_verify_db_access_v2 default/mismatch strictness | The same four failures already exist in retained rp-27, including 412 versus 406 and infrastructure exit 2. Do not misattribute them to the new CAS queries or accept them as PASS. |

The two full-suite infrastructure legs are known_good_state and db_access. Their persisted findings JSON identifies **M/data/database/dao/OperationRunEventDao.kt** as DB_ROOM_QUERY_UNCLASSIFIABLE. It does not identify the changed receipt or warranty DAO. The retained rp-27 findings JSON names the same DAO, and its DAO, SQL classifier, callable parser and cache test have identical SHA-256 bytes to the reviewed checkout. This corrects the plausible but unsupported diagnosis that the new warranty EXISTS or receipt null-safe CAS predicate caused the infrastructure failure.

The known-good-state scorecard remains 3 PASS / 2 FAIL / 1 INFRA / 1 SKIP; missing freshness stamp is not a pass. The six failing full-suite legs are time_boundaries, db_artifact_sync, cancellation, event_writers, raw_money_aggregates and guard_tests. The time violations still point to RestoreMaintenanceMode.confirmDefaultWorkerSchedules, not the new monotonic drain adapter.

Ratchet output must be interpreted with the new detector protocol: cancellation baseline 64 versus current 170, NEW 111; event-writer baseline 14 versus current 43, NEW 43. These are not proof that 111/43 new application bugs were authored by the batch: count preservation and event-rule identities expose previously collapsed debt. Conversely, calling the entire old violation set unchanged without comparing like-for-like detector identities is not sufficient. Obtain the prescribed base/head detector evidence and adjudication under FINAL_CI_GUARD_ACCEPTANCE_GATE.md FG-07/FG-23; do not regenerate a larger baseline or broaden allowlists to erase the difference.

### Worker logger diagnosis

- vr-20260927-065901-f24c074f failed compileDebugUnitTestKotlin; it did not execute the asserted runtime timeout diagnosis.
- vr-20260927-070515-61dd968f failed two existing timeout-helper tests: classifyDiagnostic_timeout_returns_TIMEOUT and retry_persists_terminal_reason_code_and_classifies_diagnostic.
- vr-20260927-071601-23e3f70b failed only classifyDiagnostic_timeout_returns_TIMEOUT. The new persistence-attribution assertions passed in the persisted test output.
- The retry test is not a new Wave-2 attribution test. An unchanged timer fixture and a similar earlier-tree failure support investigating the harness, but do not independently prove the asserted CPU/timer-environment mechanism. Keep the class FAIL until trustworthy evidence clears it; do not remove or weaken its assertions.

### Receipt, recurring and backup identity repairs

The old receipt baseline PASS ran nine tests and did not include eventFailureRollsBackSuggestion. It was not an equivalent-test comparison and did not establish a CAS production regression. The current service has no exception-wrapping branch. The repair adopts an existing non-copying CopyableThrowable sentinel, retains assertSame and rollback/event assertions, and adds actual caller-cancellation coverage. Receipt repair PASS is vr-20260927-083526-ba86299f; later recurring sentinel repair PASS is vr-20260927-090429-574892fa. These are distinct snapshots.

During this review the new backup statistics cancellation identity test failed at :1792 in vr-20260927-093252-e17f7df2 on S0. The subsequent one-line S1 fixture repair reused the file's existing IdentityCancellationException (:391–393), preserving the strict identity assertion. Its rerun vr-20260927-093848-79b37e05 completed **PASS**, exit 0, matching S1 start/end fingerprints, at 2026-09-27T09:45:42.1394118Z. No production unwrapping or global coroutine setting was added. This supplies repository evidence, not automatic PASS for every statistics UI consumer.

The pytest collection repair uses the canonical mediation_analysis.models import and a module-identity assertion. Recursive selection remains intact; no skip, narrowed discovery or fallback import was introduced. The later completed collection revealed real failures rather than justifying the old collection failure as acceptable.

### Additional current-snapshot blocker: Money Radar fixture contract, not the recorded routing diagnosis

Run vr-20260927-094905-1a35bdce FAILED on S1 at 2026-09-27T09:50:18.4462931Z. Three existing tests fail: due-earlier-today (:250), scalar merchant placeholder (:494), and merged-recurring deduplication (:508). The new required-signal failure/cancellation tests pass.

Independent source adjudication contradicts the newest JOURNAL entry's proposed missing merged-provider dependency: all three failing tests explicitly stub mergedRecurringPatternsProvider.getConfirmedPatterns() (:242–244,484–486,500–502), and that dependency/call already exists at HEAD. The mock ExpenseRepository is strict (:144), and those three cases do not stub getTotalDepositsForPeriod. When bills exist, calculateDueBillsScore calls getMonthlyIncome (:339–340); that method now propagates the failed income read (:409–411) instead of catching it as zero. The outer catch correctly produces an unavailable result with empty reasons/bills. The neighboring passing bill-window test explicitly supplies a 5000 income stub (:229).

Therefore the source-supported repair direction is to supply a truthful required-income success fixture in these three happy-path tests while retaining their original assertions, then rerun the class. Do not blindly add another recurring-provider stub or change the production due-date filter on this evidence. No test or production repair was made by this reviewer. The class remains FAIL, not 'environmental' or silently waived.


## 4. Riding issues A1–A8

| Issue | Disposition | Verified source/evidence and remaining limits |
|---|---|---|
| A1 | ALREADY_FIXED | All three sites landed in 3b7cabfe: DataRetentionWorker audit catch (:445), TransactionLifecycleCoordinator catch (:2324), AppStartupCoordinator asset-resume catch (:499) rethrow CancellationException. These are CL-05 provenance, not new remaining-batch edits. DataRetentionWorkerTest passed vr-20260927-073349-ab86c269; no blanket current cancellation-guard PASS is claimed. |
| A2 | IMPLEMENTED_UNVERIFIED | PrivacyDeniedException has a bounded reasonCode property, not parsed message text; unknown codes normalize to PRIVACY_GATE_FAILURE. Backup producer distinguishes encrypted-backup denial from fail-closed operational failure, and UI consumes the typed property. PrivacyDeniedExceptionTest passed vr-20260927-092218-e662e35e on S0; the repaired backup repository class passed on S1. A2-denial-contract.md is authored decision documentation, not independent privacy approval. Current backup UI execution and explicit required decision/guardian disposition remain outstanding. |
| A3 | OUT_OF_SCOPE_WITH_APPROVED_REASON for the bounded implementation, not closed | cluster-map.md:506 places CA-W1-001 SpendingMapViewModel denial state as a Wave-3/CL-18-adjacent verification candidate. Its recorded four failures are not silently converted to fixed. No unrelated map/UI implementation was attempted. |
| A4 | IMPLEMENTED_UNVERIFIED | Current T/ui/screens/review/ReviewViewModelPrivacyDenialTest.kt:49 supplies aiSettingsRepository.settings() = flowOf(AiSettings()). That resolves the observed missing-flow fixture setup at source level, but does not prove all denial behavior; fresh equivalent execution is required. cluster-map.md:507 still describes the candidate as needing verification. |
| A5 | ALREADY_FIXED, with current scoped evidence | CloudPiiSanitizer repair landed in CL-05. T/data/privacy/KeystoreInstallationSecretHashingTest.kt:242–336 checks exact preservation of decimal/localized amounts and identifiers, real/local/labelled phones, adjacent multiline amount/date content, and dedicated SSN/card markers. Actual current class PASS vr-20260927-092715-b38abb28 on S0. This is evidence for the tested heuristic corpus, not a mathematical guarantee over every number format. |
| A6 | ALREADY_FIXED | SharedExpenseBudgetOffsetEngine.kt:128,159,175 returns MISSING_RATE only; counters/outer partiality remain. Tests assert exact returned code lists, individual/mixed/no-warning cases, cancellation and real-converter log privacy. Historical PASS vr-20260926-085716-8666e672; no need to manufacture a production redaction patch. Current cross-A7 rerun remains useful and is not inferred from historical evidence. |
| A7 | IMPLEMENTED_UNVERIFIED | GroupSettlementBalancePolicy.kt:25–52 filters exact group/currency and RECORDED/COMPLETED; outgoing settlement adds to creditor-positive balance, incoming subtracts. SharedExpenseManager and actual SharedExpenseGroupsViewModel use the shared policy. GroupsRepositoryImpl requires settlement DAO and chunks SELECT history in 500 IDs; the port adapter returns only minimal settlement fields, not notes. SettlementCalculator consumes already-adjusted balances once; dormant writer is not revived. Tests cover partial/full/over-settlement, departed members, wrong group/currency/status, real suggestions, read failure/cancellation and 501-group chunking. No current complete A7 runtime/guardian gate was found. |
| A8 | ALREADY_FIXED / preserved approved money contract | The test now preserves both successful and failed source buckets: 2+3=5, failure count 3, retention 0.7, confidence 0.513333. Source assertions were read at BudgetForecastingEngineTest.kt:978–1018. Historical CL-27 approval/waiver is not extended to new clusters. CL-15 preserves bucket-success, not nonzero-total, semantics. |

## 5. Architecture and cross-cluster assessment

### Preserved in the inspected changes

- Receipt suggestion, recurring activation and transaction-linked work remain on their established lifecycle/transaction paths. Affected-row results decide events and success metrics; repeated/missing/stale cases are explicit. No new direct forbidden UI/worker DAO writer was introduced by the reviewed scope.
- Receipt and recurring mutation/event/derived-state work remains atomic. Existing restore admission/write barriers are retained. No entity/schema change was found; a return type or conditional SQL predicate alone does not require a Room version bump or destructive migration.
- Merchant backfill keeps its repository boundary and controlled false/no-op result. Its public operation does not silently change into a new lifecycle bypass merely because the DAO now accepts the expected merchant.
- Money core source conservation survives CL-15 all-failed classification. CL-05's explicit-target adapter and captured reverse quote retain source identity/counts. Empty, converted zero, net-zero and zero-count successful builder cases are distinct from no successful conversion.
- The five actual hybrid wrappers are wired through AiModule/HybridRouter. Query preparation is a mandatory dependency of the real cloud provider, with one prepared payload reused for audit and transport.
- Statistics consumers were followed in both backup and debug UI; nullable/typed failure changes are not left only in the repository. Radar/stress UI avoids rendering unavailable values as ordinary financial scores.
- Settlement support is a bounded read/aggregation change; it does not activate a deprecated settlement-writing facade or duplicate the sign rule in independent UI math.

### Limits and source/document discrepancies

- ENGINE_INTERACTION_MAP.md describes a broader HybridRouter relationship than actual DI: specialized dedupe and the old unbound receipt-assist facade are not the five newly routed services. Source wins for this review; the discrepancy is reported, not silently used to assert additional coverage.
- WarrantyExpirationWorker's pre-existing WRK16 global notification permission admission can prevent unrelated expiry/pruning work, and the underlying Android notification posting has existing SecurityException debt. The original warranty eligibility audit explicitly fences broader worker debt separately. Do not claim CL-09 fixed notification permission boundaries globally, but do not label that unchanged policy a newly introduced CAS defect either.
- FinancialStressForecastEngine's baseline helper has pre-existing repeated currency resolution, zero fallbacks and cancellation-sensitive runCatching. The top-level error-state repair does not establish universal internal failure transparency.
- A matched guard name, authored test, self-review PASS, historical human merge decision, compiler PASS or helper-only test is not sufficient evidence for an original end-to-end finding.
- Current handoffs/STATE/JOURNAL are temporally inconsistent. Preserve their historical records; do not overwrite them to create an artificial all-green narrative. This report supplies corrections and snapshot-qualified evidence instead.

## 6. Finding-by-finding traceability matrix

Exactly 34 original findings are covered: CL-27 3; CL-05 7; CL-23 2; CL-09 4; CL-21 2; CL-15 8; CL-22 8. Each entry below records original source, segment/legal route, acceptance, implementation and consumers, meaningful assertions, actual execution/snapshot and residual risk.

FIXED_AND_VERIFIED means the **scoped original requirement** has source plus meaningful execution evidence identified here; it is not a blanket merge, guardian or whole-suite PASS. IMPLEMENTED_UNVERIFIED includes plausible repairs with older dirty-snapshot evidence, missing consumer execution or a still-failing required class. PARTIAL means an original/approved acceptance surface remains unmet. Historical CL-05 statuses do not rescind the recorded human merge decision.

Disposition totals: IMPLEMENTED_UNVERIFIED 20; FIXED_AND_VERIFIED 10; PARTIAL 4. These are original-finding dispositions, separate from the A1–A8 table and the overall FAIL verdict.

### CL-27

#### CA-E-01-001 — IMPLEMENTED_UNVERIFIED

- **Original requirement/source:** C/cell-E-01-audit.md, finding CA-E-01-001
- **Owning segment(s):** 16
- **Legal execution path and consumers:** Currency settings/refresh → CurrencyRatesRepositoryImpl → ExchangeRateStore → CurrencyConverter historical consumers
- **Intended acceptance:** Persist provider publication date independently of retrieval time; reject missing, malformed or conflicting dates before mutation; preserve historical lookup boundaries.
- **Current implementation / line anchors:** M/data/repository/CurrencyRatesRepositoryImpl.kt:74–156 parses the unique ECB publication date and writes its UTC-midnight value as validDate, while retrieval time remains lastUpdated. CL-05 catalog changes preserve this distinction.
- **Tests and meaningful assertions:** T/data/repository/CurrencyRatesRepositoryImplTest.kt:54–102 asserts next-day fetch, repeated-download keys, direct/derived pair dates and before/on-publication historical lookups; :154–191 checks invalid dates/no writes and locale/timezone independence.
- **Execution evidence and tested snapshot:** Historical PASS: vr-20260925-171931-47926751 and vr-20260925-175945-5e4e4397 (clean ancestor snapshots); later CL-05 PASS vr-20260926-080920-e28d7960 (historical dirty snapshot). Full commands/HEAD/fingerprints in evidence appendix.
- **Residual risk / missing evidence:** Original defect is repaired in inspected source and historically exercised; no dedicated rerun of the final dirty cross-cluster snapshot was found. Existing historical-rate policy was not redesigned.

#### CA-E-01-003 — FIXED_AND_VERIFIED

- **Original requirement/source:** C/cell-E-01-audit.md, finding CA-E-01-003
- **Owning segment(s):** 16,32; consumers 2,8
- **Legal execution path and consumers:** TimePeriodUtils.formatMonthKey → history grouping/ranges → budget/analytics consumers
- **Intended acceptance:** Month keys must remain ASCII yyyy-MM under localized-digit locales and across year boundaries.
- **Current implementation / line anchors:** M/domain/util/TimePeriodUtils.kt:827–874 uses Locale.ROOT for the canonical key and shared range generation. Read-only git comparison ac9e5748..HEAD showed no change in this source or its acceptance test.
- **Tests and meaningful assertions:** T/domain/util/TimePeriodUtilsT4CBatch1Test.kt:389–403 checks US, Arabic and Persian numbering locales, exact ASCII keys and December→January range, restoring the locale afterward.
- **Execution evidence and tested snapshot:** PASS vr-20260925-165808-5fc94533, clean ac9e5748 snapshot; source/test byte-equivalent committed versions remain current.
- **Residual risk / missing evidence:** Scoped verification of the month-key finding, not a current whole-suite PASS.

#### CA-E-01-005 — IMPLEMENTED_UNVERIFIED

- **Original requirement/source:** C/cell-E-01-audit.md, finding CA-E-01-005
- **Owning segment(s):** 16; consumers 1,2,8
- **Legal execution path and consumers:** Repositories → MoneyNormalizationEngine / MoneyAggregateBuilder → MoneyAggregate → budget confidence and quality consumers
- **Intended acceptance:** Preserve failed source amounts/counts; denominator equals all eligible input transactions without double-counting failures; keep filtered transactions excluded.
- **Current implementation / line anchors:** M/domain/core/money/MoneyAggregateBuilder.kt:150–240 adds source buckets before conversion; MoneyNormalizationEngine.kt:117–159,186–264 conserves excluded source amounts/counts; MoneyAggregate.totalTransactionCount sums preserved buckets once.
- **Tests and meaningful assertions:** T/domain/core/money/CurrencyNormalizationBehavioralTest.kt:167–309 checks mixed, stale, invalid and missing-date sources and filtered denominators. T/domain/budget/BudgetForecastingEngineTest.kt:978–1018 uses 2 included + 3 excluded = 5 and asserts confidence 0.513333, partiality and excluded count 3.
- **Execution evidence and tested snapshot:** Historical PASS vr-20260925-170358-3b577919 and vr-20260925-172610-3ac61c0e. Current S0 builder class PASS vr-20260927-092821-7611d18a supplies additional cross-cluster evidence, but is not a rerun of all normalization/confidence consumers.
- **Residual risk / missing evidence:** A8 correction is preserved; no denominator regression found. Final-snapshot normalization and downstream confidence tests remain required.

### CL-05

#### CA-P-01-006 — IMPLEMENTED_UNVERIFIED

- **Original requirement/source:** C/cell-P-01-audit.md, finding CA-P-01-006
- **Owning segment(s):** 3,16
- **Legal execution path and consumers:** Notification listener/intake → NotificationProcessingPipeline parser-null branches → detector → PendingReview → ReviewQueueRepository.approveReview → transaction lifecycle
- **Intended acceptance:** Only actual ISO codes are explicit; kr/$ ambiguities resolve solely within their candidate home-currency set or stay unresolved; approval cannot silently invent currency.
- **Current implementation / line anchors:** M/domain/notification/money/NotificationMoneySignalDetector.kt:39–176 separates ISO codes and symbols. Normal/oversized fallback branches preserve unresolved identity; ReviewQueueRepository.kt:146–153 rejects null/blank currency without explicit correction. Blank storage sentinel reflects the non-null entity contract, not inferred EUR/SEK.
- **Tests and meaningful assertions:** T/domain/notification/money/NotificationMoneySignalDetectorTest.kt:20–144 asserts SEK/NOK/DKK home resolution, unresolved foreign home, ISO precedence and currency-prefixed aliases. Pipeline reliability/oversized and review approval assertions were inspected for stored blank currency and explicit correction.
- **Execution evidence and tested snapshot:** Historical PASS vr-20260926-081824-5de27712 and vr-20260926-103839-214b6c78; historical dirty snapshots recorded in appendix. Earlier compile/infra failures are not relabeled passes.
- **Residual risk / missing evidence:** No current source defect found. End-to-end notification fallback/approval evidence for S1 was not established by detector-only passes.

#### CA-P-04-006 — IMPLEMENTED_UNVERIFIED

- **Original requirement/source:** C/cell-P-04-audit.md, finding CA-P-04-006
- **Owning segment(s):** 7,36,16
- **Legal execution path and consumers:** BillRemindersScreen → Hilt ViewModel → BillReminderManager → recurring repository → explicit-target display aggregate
- **Intended acceptance:** Display source-currency reminder amounts honestly; convert monthly-equivalent totals into resolved home currency; preserve unavailable/partial/empty states.
- **Current implementation / line anchors:** M/domain/reminder/BillReminderManager.kt:173 onward normalizes frequency then calls aggregateDisplayAmounts. M/ui/screens/reminder/BillRemindersScreen.kt:89–100 renders aggregate currency or unavailable dash; individual reminders use their stored currency. ViewModel observes home changes with latest-result semantics.
- **Tests and meaningful assertions:** T/domain/reminder/BillReminderManagerTest.kt:140–224 covers partial/unavailable results, cancellation, failed settings and genuine empty zero. BillRemindersViewModelTest verifies home-currency refresh and stale-result behavior; mixed-source aggregation expectations were inspected.
- **Execution evidence and tested snapshot:** Historical PASS vr-20260926-145424-b954044f (manager), vr-20260926-145330-2573dac1 (ViewModel), at b752c024... historical dirty snapshot.
- **Residual risk / missing evidence:** Source contract is implemented. Mocked aggregate fixtures do not themselves prove FX math; use real shared-adapter/converter evidence as well as current consumer reruns.

#### CA-P-06-001 — IMPLEMENTED_UNVERIFIED

- **Original requirement/source:** C/cell-P-06-audit.md, finding CA-P-06-001
- **Owning segment(s):** 2,16
- **Legal execution path and consumers:** BudgetScreen → BudgetViewModel → BudgetAutopilotEngine/history → captured quote reverse conversion → BudgetRepository transaction → DAO
- **Intended acceptance:** Compare history, current budget, caps and deltas in one display currency; persist recommendation in original budget currency using the captured quote; reject changed source identity.
- **Current implementation / line anchors:** M/domain/budget/BudgetAutopilotEngine.kt:89–124,294 onward retains display and source values. M/domain/currency/CurrencyConverter.kt:489 reverseDisplayQuote does not fetch a new quote. BudgetViewModel single/all apply reads current source currency and writes sourceAmountToApply, with preflight before bulk writes.
- **Tests and meaningful assertions:** T/domain/budget/BudgetAutopilotEngineTest.kt:268–318 asserts USD100 ↔ EUR90, source write amount USD100 and one quote. T/ui/screens/budget/BudgetViewModelAutopilotQualityTest.kt:194–261 asserts actual source amount/currency and no writes for changed currency. Missing quote and stale history are tested.
- **Execution evidence and tested snapshot:** Historical PASS vr-20260926-082548-bae0b42a and vr-20260926-082854-3893766f; no final-snapshot full apply-path run located.
- **Residual risk / missing evidence:** No new unit mismatch found. Unrelated amount/date CAS or budget-algorithm redesign was not inferred from this currency-only requirement.

#### CA-E-01-002 — IMPLEMENTED_UNVERIFIED

- **Original requirement/source:** C/cell-E-01-audit.md, finding CA-E-01-002
- **Owning segment(s):** 16,30
- **Legal execution path and consumers:** Currency UI/settings → rate refresh → shared SupportedCurrency catalog → legacy and typed conversion → shared display adapter
- **Intended acceptance:** Unify advertised, refreshed and convertible currencies; retain known inactive historical identity; reject unknown identities even for empty requests.
- **Current implementation / line anchors:** M/domain/currency/CurrencyConverter.kt:57–63,157–158,262–263,343–352 shares catalog validation. CurrencyRatesRepositoryImpl.kt:78 onward uses the catalog; CurrencyManagementViewModel uses active entries. MultiCurrencyRepository.kt:466–526 is the explicit-target adapter with validated counts/amounts and typed unavailability.
- **Tests and meaningful assertions:** T/data/repository/CurrencyRatesRepositoryImplTest.kt:105–150 tests every active provider currency through actual typed/legacy converter and omitted supported currency as MISSING_RATE, not unsupported. CurrencyManagementViewModelTest, ConversionSemanticsHardeningTest and MultiCurrencyRepositoryTest cover invalid/empty/partial/metadata behavior.
- **Execution evidence and tested snapshot:** Historical clean PASS at 7241e7c1: vr-20260925-192632-6aaa4757, -194235-1ce77a5b, -194530-5bab821c, -195745-494d8324; later consumer repair changed the lane.
- **Residual risk / missing evidence:** Catalog coherence is source-grounded; historical core-contract evidence is not a blanket pass for later consumers or the dirty remaining batch.

#### CA-E-02-002 — IMPLEMENTED_UNVERIFIED

- **Original requirement/source:** C/cell-E-02-audit.md, finding CA-E-02-002
- **Owning segment(s):** 8,16
- **Legal execution path and consumers:** AnalyticsViewModel normalized input → AdvancedAnalyticsEngine core → empty factories → analytics screen
- **Intended acceptance:** Empty and all-excluded results retain supplied currency, zero counts and warnings without consulting locale.
- **Current implementation / line anchors:** M/domain/analytics/AdvancedAnalyticsEngine.kt:544,607,683,743 passes required displayCurrency into both empty factories and nested currency-bearing models.
- **Tests and meaningful assertions:** T/domain/analytics/AdvancedAnalyticsEngineNormalizedTest.kt:156–203 uses JPY under Locale.US/ENGLISH/ROOT and asserts nested currencies, zero counts and exact warning preservation.
- **Execution evidence and tested snapshot:** Historical PASS vr-20260926-083014-edce2f73, historical dirty fingerprint 5f28b0dba9d7... at 6547b05a.
- **Residual risk / missing evidence:** Original factories are fixed; final-snapshot analytics rerun remains absent. Do not attribute unrelated default-locale overloads to this normalized path.

#### CA-E-02-005 — IMPLEMENTED_UNVERIFIED

- **Original requirement/source:** C/cell-E-02-audit.md, finding CA-E-02-005
- **Owning segment(s):** 7,8,16
- **Legal execution path and consumers:** AnalyticsViewModel → InsightsEngine → real RecurringExpenseEngine/manual repository → snapshot → legacy insight rendering
- **Intended acceptance:** Preserve manual rule source amount/currency or actually convert; reject unknown source with typed warning; do not double-convert detected normalized patterns.
- **Current implementation / line anchors:** M/domain/analytics/InsightsEngine.kt:316–392,512,757–818 retains validated per-pattern source currency, merges INVALID_TRANSACTION_CURRENCY warnings and formats recurring descriptions with item currency.
- **Tests and meaningful assertions:** T/domain/analytics/InsightsEngineValidationTest.kt:491–579 installs the real recurring engine; asserts USD100 stays USD100 under EUR analytics, rendered description is not EUR, unknown rule excluded with preserved existing warning, detected EUR unchanged and cancellation propagated.
- **Execution evidence and tested snapshot:** Historical PASS vr-20260926-084759-816a50a2, after earlier failing vr-20260926-083125-bc3ebec0.
- **Residual risk / missing evidence:** Source-label option is explicitly allowed by the original finding/spec. No need to require FX for honestly source-labeled obligations; current full-path rerun is still missing.

#### CA-E-04-004 — IMPLEMENTED_UNVERIFIED

- **Original requirement/source:** C/cell-E-04-audit.md, finding CA-E-04-004
- **Owning segment(s):** 17,16
- **Legal execution path and consumers:** Dormant getTaxYearSummary → BusinessExpenseRepository existing category/currency projection → explicit filing-currency adapter
- **Intended acceptance:** Restore typed per-category conversion including null/explicit Uncategorized counts; derive legacy map from typed map; expose partiality/unavailability rather than relabeled raw sums.
- **Current implementation / line anchors:** M/domain/tax/TaxEstimator.kt:329–414 validates filing currency, consumes existing currency/category projection, targets filing not home, derives legacy values and propagates category failures. BusinessExpenseRepository adds only a read-through; existing ExpenseDao query coalesces null categories.
- **Tests and meaningful assertions:** T/domain/tax/TaxEstimatorTest.kt:302–440 checks Uncategorized, invalid/empty currency, EUR100+USD100 mapping to EUR190 and required-category unavailability. FX adapter is mocked in the tax unit test; independent adapter tests supply the conversion contract.
- **Execution evidence and tested snapshot:** Historical PASS vr-20260926-085219-6b4df888 and -105201-3f5990ac.
- **Residual risk / missing evidence:** Dormant API remains dormant; no new UI or schema was invented. Optional constructor default fails closed if manually omitted; production Hilt wiring uses the repository dependency. Final-snapshot evidence is pending.

### CL-23

#### CA-P-09-002 — IMPLEMENTED_UNVERIFIED

- **Original requirement/source:** C/cell-P-09-audit.md, finding CA-P-09-002
- **Owning segment(s):** 12,29
- **Legal execution path and consumers:** WorkerExecutionGuard → WorkerRunLogger terminal persistence → fallback terminal diagnostic sink
- **Intended acceptance:** Persistence failure class must describe the failed diagnostic write, not the original worker exception; preserve original failure separately and bound persisted fields.
- **Current implementation / line anchors:** M/domain/workers/WorkerRunLogger.kt:168–183 derives NotDurableFailure class from its own error. Six terminal callers use that mapping; WorkerExecutionGuard fallback and FileWorkerTerminalDiagnosticSink retain bounded class/reason fields.
- **Tests and meaningful assertions:** WorkerRunLoggerTest adds successful-worker/failed-worker/retry persistence attribution assertions. Persisted stdout shows the new attribution cases pass; original timeout helper cases remain failing in the same class.
- **Execution evidence and tested snapshot:** vr-20260927-070515-61dd968f FAIL with two timeout-fixture failures; -071601-23e3f70b FAIL with classifyDiagnostic_timeout_returns_TIMEOUT. New attribution cases pass, but the class is not PASS.
- **Residual risk / missing evidence:** Implementation and focused assertions support the fix; required complete class evidence remains red. Environmental attribution is not independently proven merely by unchanged fixture code.

#### CA-P-09-003 — IMPLEMENTED_UNVERIFIED

- **Original requirement/source:** C/cell-P-09-audit.md, finding CA-P-09-003
- **Owning segment(s):** 12,18
- **Legal execution path and consumers:** Restore/maintenance admission → WorkerDrainController → WorkerLeaseRegistryImpl → monotonic clock/leases
- **Intended acceptance:** Wall-clock adjustments must not change drain budget; handle nonpositive/short budgets, release, cancellation and monotonic wrap correctly without weakening admission.
- **Current implementation / line anchors:** M/domain/workers/WorkerLeaseRegistryImpl.kt:60–80 compares elapsed monotonic nanoseconds and delays min(50ms, remaining budget). Existing SystemMonotonicTimeProvider and DI binding are used; restore admission/reset/lease release paths remain intact.
- **Tests and meaningful assertions:** WorkerLeaseRegistryTest tests wall-clock jumps, 7ms deadline, release before timeout, empty/nonpositive cases, cancellation retaining active lease and short elapsed time across Long wrap. WorkerRestoreRegression/WorkerExecutionGuard/MaintenanceOperationRunner tests cover integration invariants.
- **Execution evidence and tested snapshot:** Prior snapshot PASS: vr-20260927-072820-45f3207d, -072938-3d8cbcfd, -073103-32381c10, -073228-41d809da; fingerprint 15333c28... .
- **Residual risk / missing evidence:** Passing earlier-snapshot classes are strong evidence, not a fresh S1 worker/restore profile. No wall-clock deadline remains in this drain method.

### CL-09

#### CA-P-03-001 — IMPLEMENTED_UNVERIFIED

- **Original requirement/source:** C/cell-P-03-audit.md, finding CA-P-03-001
- **Owning segment(s):** 38,4
- **Legal execution path and consumers:** ReceiptMatchingWorker/UI → ReceiptMatchLifecycleService → Room transaction → ScannedReceiptDao conditional suggestion + lifecycle event
- **Intended acceptance:** Never overwrite a resolved/rejected/deleted receipt from a stale suggestion; identical retries produce no write/event; affected rows control event and metrics; rollback atomically on failure/cancellation.
- **Current implementation / line anchors:** M/data/database/dao/ScannedReceiptDao.kt:116–131 requires unlinked eligible status and null-safe changed suggestion/confidence. ReceiptMatchLifecycleService.kt:43–71 returns Boolean within the same transaction; worker counts only true. UI callers reload state after ignored false results.
- **Tests and meaningful assertions:** Real-Room ReceiptMatchLifecycleServiceTest covers linked/rejected/missing/retry/null confidence, unchanged row/event on failure, suspended failure, non-copying cancellation identity and actual caller cancellation. ReceiptMatchingWorkerTest covers false result/success-only metrics.
- **Execution evidence and tested snapshot:** PASS vr-20260927-083526-ba86299f (d3dc6ad2...); worker PASS -085550-6ddce29c (42cf053a...). Old pre-batch nine-test PASS did not contain the new identity assertion.
- **Residual risk / missing evidence:** No production exception wrapping found. Earlier passing repair snapshots are explicit; final-snapshot class rerun is still a gap. Do not undo the conditional transaction based on the invalid old baseline comparison.

#### CA-P-04-005 — FIXED_AND_VERIFIED

- **Original requirement/source:** C/cell-P-04-audit.md, finding CA-P-04-005
- **Owning segment(s):** 7
- **Legal execution path and consumers:** RecurringRuleLifecycleCoordinator.activate → Room transaction → ManualRecurringExpenseDao affected-row update → derived schedule + event
- **Intended acceptance:** Read activation state inside transaction; missing/already-active/zero-row updates produce no duplicate schedules/events; rollback derived updates on error/cancellation.
- **Current implementation / line anchors:** M/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt:218–269 reads inside transaction and checks setActiveStatus != 1; DAO method returns affected count and excludes already-correct state.
- **Tests and meaningful assertions:** RecurringRuleLifecycleCoordinatorTest uses real Room for repeated/missing/concurrent activation and derived/event rollback; later identity fixture uses established non-copying sentinel, without relaxing assertions.
- **Execution evidence and tested snapshot:** PASS vr-20260927-090429-574892fa, exact S0; preceding -085720-38d8280e failed the new identity fixture. S1 changes only an unrelated backup test.
- **Residual risk / missing evidence:** Scoped to activation and inspected interactions. No schema change, direct forbidden writer, or production transaction rewrite required.

#### CA-P-09-001 — IMPLEMENTED_UNVERIFIED

- **Original requirement/source:** C/cell-P-09-audit.md, finding CA-P-09-001
- **Owning segment(s):** 19,6,9,12
- **Legal execution path and consumers:** MerchantKeyBackfillWorker → ExpenseRepository guarded read/write → ExpenseDao CAS
- **Intended acceptance:** Backfill only still-null key with unchanged merchant; deleted/changed/already-enriched rows must be benign false outcomes; count only successful writes and continue later batches.
- **Current implementation / line anchors:** M/data/database/dao/ExpenseDao.kt:2322 onward adds expected merchant and null-key predicate. ExpenseRepository.kt:1021–1027 rechecks current row under barrier and returns count==1. Worker preserves failed-vs-skipped distinction and fresh batch reads.
- **Tests and meaningful assertions:** ExpenseRepositoryMerchantKeyBackfillTest uses real Room for stale merchant, existing key, missing row and successful CAS. MerchantKeyBackfillWorkerTest asserts false CAS is skipped rather than error, successful counts and later fresh reads. Android constructor fixture was updated to the real signature.
- **Execution evidence and tested snapshot:** No dedicated new repository/worker PASS located for reviewed S0/S1; production compilation alone is insufficient.
- **Residual risk / missing evidence:** Public repository operation remains the legal two-argument path; internal SQL receives expected merchant. Existing batch budget behavior was not silently redesigned.

#### CA-E-05-008 — IMPLEMENTED_UNVERIFIED

- **Original requirement/source:** C/cell-E-05-audit.md, finding CA-E-05-008
- **Owning segment(s):** 34,12,11
- **Legal execution path and consumers:** WarrantyExpirationWorker → delivery claim/read eligibility → optional notification → claimed-to-sent result
- **Intended acceptance:** Only active existing parent warranties may be claimed/notified; recheck after claim; zero affected rows/no eligibility mean no post and no success metric.
- **Current implementation / line anchors:** M/data/database/dao/WarrantyReminderDeliveryDao.kt:55–103 enforces ACTIVE parent with EXISTS and exposes claimed eligibility. M/service/warranty/WarrantyExpirationWorker.kt:211 skips ineligible deliveries with bounded reason before posting.
- **Tests and meaningful assertions:** WarrantyReminderDeliveryDaoTest covers missing/inactive parent and active→inactive after claim with real Room. WarrantyExpirationWorkerTest checks no notification/metric for post-claim ineligibility and actual successful post control.
- **Execution evidence and tested snapshot:** Targeted new warranty class execution not located for S0/S1.
- **Residual risk / missing evidence:** OS posting cannot be made atomically transactional with Room. Existing WRK16 global notification permission admission and underlying SecurityException handling are separate pre-existing debt, not repaired by this CAS patch.

### CL-21

#### CA-P-08-002 — FIXED_AND_VERIFIED

- **Original requirement/source:** C/cell-P-08-audit.md, finding CA-P-08-002
- **Owning segment(s):** 20,26,28
- **Legal execution path and consumers:** InterpretFinancialQueryUseCase → DI cloud query provider → PrivacyGate/key → CloudPayloadPolicy → audit + actual HTTP body
- **Intended acceptance:** No unprepared query text reaches POST; gate/key failures stop preparation/transport; audit and serialized body use the same prepared payload; cancellation propagates.
- **Current implementation / line anchors:** M/data/ai/provider/CloudQueryInterpretationService.kt:43–49,83–126 requires policy, prepares QUERY text and serializes/audits that object. PrivacyModule binds the real default policy; user-case fallback remains bounded.
- **Tests and meaningful assertions:** CloudQueryPreparedPayloadTest captures actual request body and audit identity, rejects raw sentinel text, verifies zero network for gate/key denial and cancellation propagation; existing query provider tests were adjusted rather than bypassed.
- **Execution evidence and tested snapshot:** PASS vr-20260927-091246-8a67e2a5 and -091428-7d4a0dec, exact S0. S1 production/provider tests unchanged.
- **Residual risk / missing evidence:** This query provider is verified; that does not imply all nine providers have run the newly authored transport matrix.

#### CA-P-08-004 — FIXED_AND_VERIFIED

- **Original requirement/source:** C/cell-P-08-audit.md, finding CA-P-08-004
- **Owning segment(s):** 20,30; consumers 5,10,26
- **Legal execution path and consumers:** Hilt AiModule → five production hybrid services → HybridRouter → selected local/cloud provider
- **Intended acceptance:** Route actual injected services through one router, read current settings, select one path consistently, preserve cancellation/no hidden failover.
- **Current implementation / line anchors:** M/domain/ai/HybridRouter.kt:62–68 and five Hybrid* service implementations perform real delegation. AiModule.kt:123–162 wires the router into categorization, dashboard, query, review and receipt-item services.
- **Tests and meaningful assertions:** HybridRouterIntegrationTest exercises real wrappers with settings changes and provider call counts; HybridRouterTest covers selection/error/cancellation; HybridServiceDelegationTest and receipt-item service tests cover production-facing delegation.
- **Execution evidence and tested snapshot:** PASS exact S0: vr-20260927-091552-943e7d39, -091710-a79db7ef, -091844-eaa72d24, -092002-accaa2ab.
- **Residual risk / missing evidence:** Engine interaction documentation still overstates router coverage for specialized dedupe/unbound receipt-assist paths. Source wiring, not the stale map, defines what this fix covers.

### CL-15

#### CA-P-03-003 — PARTIAL

- **Original requirement/source:** C/cell-P-03-audit.md, finding CA-P-03-003
- **Owning segment(s):** 4,3; statement/import consumers
- **Legal execution path and consumers:** ReceiptOcrService → ReceiptRepository → statement or generic receipt lifecycle → ledger/outcome → review UI
- **Intended acceptance:** Failed/capped OCR pages must produce truthful partiality throughout downstream persisted state and UI; keep cancellation and page isolation.
- **Current implementation / line anchors:** M/domain/receipt/ReceiptOcrService.kt:81–96 and PDF result now distinguish successful pages/failedPages. BankStatementLifecycleProcessor.kt:449–454,854,885,957 carries partiality into event/status/result; ReviewViewModel labels partial statement results. Generic receipt coordinator still drops it (W2-R3).
- **Tests and meaningful assertions:** OcrResultPartialTest checks failed/capped/complete cases; BankStatementCompletionStatusTest checks status truth table; ReviewViewModelPrivacyDenialTest mocks a partial result and asserts the label. No real OCR→ledger→UI partial import regression was located.
- **Execution evidence and tested snapshot:** PASS on S1: OcrResultPartialTest vr-20260927-094544-785c78ec; ReceiptOcrRetryIsolationTest -094656-966b1b92; BankStatementCompletionStatusTest -094802-9d804870. These are helper/isolation passes, not the missing real OCR-to-ledger integration or generic receipt partiality.
- **Residual risk / missing evidence:** Original camera/PDF surface omitted without approved deferral; helper-only assertions do not meet original end-to-end requirement.

#### CA-P-05-003 — IMPLEMENTED_UNVERIFIED

- **Original requirement/source:** C/cell-P-05-audit.md, finding CA-P-05-003
- **Owning segment(s):** 10,31; inputs 1,2,35
- **Legal execution path and consumers:** Home/dashboard widget composition → ComputeMoneyRadarUseCase → required signals → MoneyRadarWidget
- **Intended acceptance:** Required signal failure must not become healthy/default score; represent neutral unavailable result with no score; preserve valid zero and cancellation.
- **Current implementation / line anchors:** M/domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt:29,139–158,306–320 introduces UNAVAILABLE and nullable score; required nested failures propagate into that result. MoneyRadarWidget.kt:46–68 renders neutral unavailable content.
- **Tests and meaningful assertions:** ComputeMoneyRadarUseCaseTest exercises each required source failure, missing/error Monte Carlo, income failure, cancellation and successful control. Assertions check absent score/unavailable urgency, not merely non-null result.
- **Execution evidence and tested snapshot:** Current S1 run vr-20260927-094905-1a35bdce FAIL, exit 1: three existing happy-path tests fail; new required-signal failure/cancellation tests pass. The failing cases already stub merged recurring patterns but omit the strict income dependency. Final report distinguishes this fixture contract gap from the journal's unsupported missing-provider/filter diagnosis.
- **Residual risk / missing evidence:** Required-income failures now correctly make the result unavailable. Existing happy-path fixtures need explicit truthful income stubs; preserve bill/formatting assertions and rerun the entire class. This is not evidence that merged-recurring routing was newly introduced by this batch.

#### CA-P-05-004 — IMPLEMENTED_UNVERIFIED

- **Original requirement/source:** C/cell-P-05-audit.md, finding CA-P-05-004
- **Owning segment(s):** 10,23,31
- **Legal execution path and consumers:** ComputeDashboardWidgetsUseCase → optional savings subcall → own bounded timeout → remaining widgets
- **Intended acceptance:** Only the optional subcall own timeout may be omitted; parent/caller cancellation must propagate and sibling core widgets remain usable.
- **Current implementation / line anchors:** M/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt:1122–1146 uses withTimeoutOrNull(3000L), rethrows CancellationException and emits only bounded ordinary-failure diagnostics.
- **Tests and meaningful assertions:** ComputeDashboardWidgetsUseCasePaceWiringTest uses virtual time for own 3000ms omission with other widgets preserved, caller 1000ms timeout propagation, cancellation identity and ordinary failure.
- **Execution evidence and tested snapshot:** Dedicated current class run not located.
- **Residual risk / missing evidence:** No swallowing of parent timeout was found in the changed wrapper; broader widget return-type behavior remains outside this bounded change.

#### CA-P-06-005 — PARTIAL

- **Original requirement/source:** C/cell-P-06-audit.md, finding CA-P-06-005
- **Owning segment(s):** 1,10
- **Legal execution path and consumers:** Dashboard/stress caller → FinancialStressForecastEngine → UI card
- **Intended acceptance:** Settings or calculation failure must not fabricate a three-horizon forecast/high-risk recommendation; publish explicit unavailable state and preserve cancellation.
- **Current implementation / line anchors:** M/domain/forecasting/FinancialStressForecastEngine.kt:90–101 and catch path now produce failure metadata, empty horizons, null risk and no recommendations. FinancialStressForecastCard handles unavailable before horizon selection. Baseline helper :678–695 remains inconsistent with the new explicit-currency test.
- **Tests and meaningful assertions:** FinancialStressForecastEngineTest adds settings/calculation failures, cancellation identity and successful explicit-currency empty-data control. The last control (:111–119) conflicts with the real settings reread (W2-R4).
- **Execution evidence and tested snapshot:** No execution of the new current class located; static contradiction is reported as such.
- **Residual risk / missing evidence:** Top-level fabricated failure forecast is removed, but new success acceptance case is unsatisfied and internal pre-existing fallback failures are not universally surfaced.

#### CA-P-07-002 — IMPLEMENTED_UNVERIFIED

- **Original requirement/source:** C/cell-P-07-audit.md, finding CA-P-07-002
- **Owning segment(s):** 18,29
- **Legal execution path and consumers:** Backup/debug ViewModels → DatabaseBackupRepository.getDatabaseStats → read barrier → four DAO counts → bounded unavailable UI
- **Intended acceptance:** Blocked/failed statistics reads must not return successful zero counts; check admission before DAO access; clear stale UI data, preserve real empty zeros and cancellation.
- **Current implementation / line anchors:** M/data/repository/DatabaseBackupRepositoryImpl.kt:2736–2762 checks read barrier and throws DatabaseStatsUnavailableException(READ_BLOCKED/QUERY_FAILED). BackupRestoreViewModel and DebugViewModel clear stale stats; their screens render unavailable instead of numeric zero.
- **Tests and meaningful assertions:** DatabaseBackupRepositoryImplTest:1755–1808 checks all blocked modes/no DAO, sanitized query failure/no cause, cancellation identity and valid 0/7 counts. DebugViewModelDatabaseStatsTest and backup denial/UI tests check unavailable state and propagation.
- **Execution evidence and tested snapshot:** S0 run vr-20260927-093252-e17f7df2 FAIL at the new cancellation identity assertion. S1 sentinel repair rerun vr-20260927-093848-79b37e05 PASS, exit 0, matching S1 fingerprints, finished 2026-09-27T09:45:42.1394118Z. Backup/debug UI classes still need current evidence.
- **Residual risk / missing evidence:** No application wrapping is present in getDatabaseStats; this fixture failure is not proof of production cancellation regression. Both UI consumers require current execution, not repository-only compile.

#### CA-P-07-009 — FIXED_AND_VERIFIED

- **Original requirement/source:** C/cell-P-07-audit.md, finding CA-P-07-009
- **Owning segment(s):** 18
- **Legal execution path and consumers:** Backup verifier → required semantic integrity queries → import validation summary → restore decision
- **Intended acceptance:** Required query exceptions/empty/null/invalid count results must invalidate semantic verification, not become zero violations; cancellation propagates.
- **Current implementation / line anchors:** M/data/backup/BackupVerifier.kt:480–495 makes all three required semantic checks fail closed with controlled descriptions/class-only logging and errors in the summary.
- **Tests and meaningful assertions:** T/data/backup/BackupVerifierRequiredSemanticQueryTest.kt uses real SQLite schema omissions plus proxy cursor failures for required query errors, empty/null/negative counts, valid zero and cancellation.
- **Execution evidence and tested snapshot:** PASS vr-20260927-093131-51164d19, exact S0.
- **Residual risk / missing evidence:** No permissive fallback or schema migration introduced. Broader restore class has separate identity-fixture evidence; required-query PASS does not clear that class.

#### CA-P-12-003 — FIXED_AND_VERIFIED

- **Original requirement/source:** C/cell-P-12-audit.md, finding CA-P-12-003
- **Owning segment(s):** 18,9
- **Legal execution path and consumers:** CSV importer → first RFC4180 record/header → lifecycle-based import
- **Intended acceptance:** A malformed first record is an error before any expense mutation, not empty-success; retain valid empty/comment input semantics.
- **Current implementation / line anchors:** M/util/CsvExpenseImporter.kt:106 onward distinguishes malformed header read from end-of-input and returns ImportResult.Error before processing rows.
- **Tests and meaningful assertions:** T/util/CsvImportRfc4180Test.kt adds malformed first-record cases with zero expense writes and valid controls; assertions distinguish Error from Success(0).
- **Execution evidence and tested snapshot:** PASS vr-20260927-092928-ae9b8fa3, exact S0.
- **Residual risk / missing evidence:** No parsing bypass or direct DAO writer added.

#### CA-E-01-004 — FIXED_AND_VERIFIED

- **Original requirement/source:** C/cell-E-01-audit.md, finding CA-E-01-004
- **Owning segment(s):** 16; consumers 1,2,8,17,36
- **Legal execution path and consumers:** Both MoneyAggregateBuilder entrypoints → money quality → adapter/forecast/consumer gates
- **Intended acceptance:** All failed input buckets must yield UNAVAILABLE while preserving source/failure/count metadata; successful zero, net-zero, zero-count and empty inputs must remain legitimate successes.
- **Current implementation / line anchors:** M/domain/core/money/MoneyAggregateBuilder.kt:117–135 and150–240 uses failure/success bucket cardinality, not numeric display total or transaction-count proxy, for total failure. CL-27 source preservation remains intact.
- **Tests and meaningful assertions:** MoneyAggregateBuilderRestrictionTest adds both overloads, all failures, mixed success, actual converted zero, identity zero, cancelling sums, zero-count successful buckets and empty inputs; assertions inspect quality plus preserved metadata. BudgetForecastingEngineDiagnosticsTest checks unavailable spend cannot insert a forecast.
- **Execution evidence and tested snapshot:** PASS vr-20260927-092821-7611d18a, exact S0; downstream forecast diagnostic test has historical PASS but no new full consumer sweep.
- **Residual risk / missing evidence:** Scoped builder fix verified. CL-05-review section 8 constraints are respected; unrelated normalizer legacy policies were not silently redefined.

### CL-22

#### CA-I-04-001 — IMPLEMENTED_UNVERIFIED

- **Original requirement/source:** C/cell-I-04-audit.md, finding CA-I-04-001
- **Owning segment(s):** 39
- **Legal execution path and consumers:** CI evidence-gate workflow → pin/base validation → capture run 1 + run 2 → comparison
- **Intended acceptance:** Supply one caller-resolved, validated base-ref to both captures and retain exact HEAD pin/full history; fail closed on absent/invalid base.
- **Current implementation / line anchors:** .github/workflows/ci.yml resolves PR/push/manual comparison base, validates nonzero 40-hex commit existence/ancestry, and passes same --base-ref to both captures. scripts/ci/test_wave2_guard_wiring.py covers these arguments.
- **Tests and meaningful assertions:** New wiring tests pass in recursive guard suite; old scripts/ci/test_ci_workflow_evidence_gate.py still requires an exact echo line even though equivalent pin output now uses printf, and fails.
- **Execution evidence and tested snapshot:** vr-20260927-084139-95dbc7df: new wiring tests PASS, old workflow assertion FAIL. No actual GitHub two-capture execution evidence inspected.
- **Residual risk / missing evidence:** The old failure is a stale literal assertion, not evidence that HEAD/base pinning is missing. It still needs a truthful non-weakened test update and actual required CI evidence.

#### CA-I-04-002 — PARTIAL

- **Original requirement/source:** C/cell-I-04-audit.md, finding CA-I-04-002
- **Owning segment(s):** 39,20,28
- **Legal execution path and consumers:** Static suite cloud/privacy detectors → shared Kotlin executable-source/provenance proof → every actual POST
- **Intended acceptance:** Reject comment/marker/unused preparation and prove the posted body depends on canonical prepared payload; accept legal helpers/serialization and reject raw interpolation.
- **Current implementation / line anchors:** scripts/guardrails/cloud_payload_proof.py performs bounded backward expression, binding, helper, mutation and import proof; both cloud/privacy detectors call it. Helper return-expression parsing :481–491 rejects a valid prepared interpolation.
- **Tests and meaningful assertions:** scripts/guardrails/test_cloud_payload_proof.py covers adversarial comment/raw/helper/mutable/alias/import/template cases and current providers. Positive template assertion :112 fails; legacy compliant fixture additionally lacks required serialization import.
- **Execution evidence and tested snapshot:** Observed FAIL in vr-20260927-084139-95dbc7df, guard_tests.log:4361–4372 and4445–4449, although production detector legs pass.
- **Residual risk / missing evidence:** W2-R1 prevents acceptance. This bounded proof is not a full Kotlin control-flow engine; successful provider-name fixtures do not prove all possible variants.

#### CA-I-04-003 — FIXED_AND_VERIFIED

- **Original requirement/source:** C/cell-I-04-audit.md, finding CA-I-04-003
- **Owning segment(s):** 39
- **Legal execution path and consumers:** Protocol-v1 detector output → guard_ratchet occurrence extraction → baseline/current comparison
- **Intended acceptance:** Detect additional same-rule/file occurrences and distinguish event-writer rules; preserve exact count identities, reject baseline growth and malformed counts.
- **Current implementation / line anchors:** scripts/ci/guard_ratchet.py:280 onward preserves occurrences; :523,548–549 uses Counter; event output carries specific rule identity. Existing baselines were not rewritten.
- **Tests and meaningful assertions:** scripts/ci/test_guard_ratchet_occurrences.py asserts growth in already-baselined file fails, true removals are distinguished, event categories remain separate, legacy entries count once and invalid explicit counts fail.
- **Execution evidence and tested snapshot:** New occurrence module executes successfully in vr-20260927-084139-95dbc7df. Current ratchets visibly report cancellation growth and event identity differences rather than hiding them.
- **Residual risk / missing evidence:** Fixing detection does not authorize accepting all newly visible debt. FG-07/FG-23 baseline/detector-migration proof and adjudication remain blocking broader requirements.

#### CA-I-04-004 — FIXED_AND_VERIFIED

- **Original requirement/source:** C/cell-I-04-audit.md, finding CA-I-04-004
- **Owning segment(s):** 39
- **Legal execution path and consumers:** Static suite and evidence capture → recursive pytest scripts selection → nested parser/proof/source-scope tests
- **Intended acceptance:** Collect all recursive guard tests, including previously omitted modules; import errors/failed/skipped required evidence cannot become PASS.
- **Current implementation / line anchors:** scripts/ci/run_static_guard_suite.py:179 selects pytest scripts recursively; capture_db_guard_evidence.py uses equivalent coverage with cache disabled and required-module checks. mediation_analysis/test_models.py now imports canonical package without reducing selection.
- **Tests and meaningful assertions:** Suite/capture wiring tests assert recursive arguments and mandatory modules; actual run reaches nested structural/barrier/source tests and new modules. Canonical model identity assertion guards against unrelated top-level models binding.
- **Execution evidence and tested snapshot:** vr-20260927-084139-95dbc7df completed 4345 outcomes: 4302 passed, 15 failed, 28 skipped; prior run stopped at collection error.
- **Residual risk / missing evidence:** Selection/collection defect repaired and demonstrated. All-test acceptance is still FAIL; 28 skipped cases are not converted to passing coverage.

#### CA-I-04-005 — IMPLEMENTED_UNVERIFIED

- **Original requirement/source:** C/cell-I-04-audit.md, finding CA-I-04-005
- **Owning segment(s):** 39,12
- **Legal execution path and consumers:** Python worker detector + Kotlin architecture test → executable doWork proof
- **Intended acceptance:** Discover qualified CoroutineWorker declarations and reject comment-only/dead/helper markers; require executed typed guard result returned through bridge.
- **Current implementation / line anchors:** scripts/verify_worker_boundaries.py:343 onward and T/architecture/WorkerEntryPointProof.kt / WorkerSourceMask.kt inspect executable entrypoint shape rather than raw marker strings. Existing production guard/lease/barrier wiring remains untouched.
- **Tests and meaningful assertions:** scripts/test_verify_worker_entrypoint_proof.py and T/architecture/WorkerEntryPointProofTest.kt cover comments, qualified supertypes, dead/helper paths and valid assigned result/bridge patterns.
- **Execution evidence and tested snapshot:** Python new module and worker detector PASS within vr-20260927-084139-95dbc7df; no execution of new Kotlin proof class/architecture class located for S0/S1.
- **Residual risk / missing evidence:** Both independent implementations need runtime evidence. Bounded syntax proof should not be advertised as complete semantic equivalence.

#### CA-I-04-006 — PARTIAL

- **Original requirement/source:** C/cell-I-04-audit.md, finding CA-I-04-006
- **Owning segment(s):** 39,12
- **Legal execution path and consumers:** Cancellation scanner executable catches/runCatching → resolved callable + active rule → allowlist matcher
- **Intended acceptance:** An exemption must match the active rule and exact callable, and unresolved identity must not borrow authorization; path must remain exact per FG-06.
- **Current implementation / line anchors:** scripts/verify_cancellation_boundaries.py resolves callable and active rule, rejecting unknown symbols, but :256–264 still allows suffix/basename path identity.
- **Tests and meaningful assertions:** scripts/test_verify_cancellation_allowlist_scope.py proves cross-rule/overload/unknown-symbol isolation yet :11,109–117 positively permits filename-only Service.kt.
- **Execution evidence and tested snapshot:** New test module passes in full guard run; its path-positive assertion demonstrates why passing tests do not prove FG-06 compliance.
- **Residual risk / missing evidence:** Original rule/symbol widening is repaired; exact-path gap W2-R2 remains. No new exceptions or baseline expansion permitted.

#### CA-I-04-007 — FIXED_AND_VERIFIED

- **Original requirement/source:** C/cell-I-04-audit.md, finding CA-I-04-007
- **Owning segment(s):** 39
- **Legal execution path and consumers:** Allowlist compliance loader → all configured YAML inputs → infrastructure/violation exit
- **Intended acceptance:** Missing parser/file, malformed/duplicate YAML keys and wrong shapes must be infrastructure failures; explicit empty list must be valid without adding exemptions.
- **Current implementation / line anchors:** scripts/verify_allowlist_compliance.py:164–213 onward rejects unparseable/invalid inputs. Human authorized comments-only ui_dao_allowlist.yml → explicit [] with zero exemptions.
- **Tests and meaningful assertions:** scripts/test_verify_allowlist_compliance_fail_closed.py covers absent dependency/files, malformed/duplicate/wrong shape input and explicit-empty control. Existing metadata restrictions remain.
- **Execution evidence and tested snapshot:** Isolated PASS vr-20260927-075548-69c06619; full-suite allowlist leg and adversarial module PASS in -084139-95dbc7df.
- **Residual risk / missing evidence:** Authorization is specific to [] and is recorded in JOURNAL, not authority to broaden policy. The separate owner-grace date is October 1, 2026; later maintenance is not a reason to weaken checks.

#### CA-P-08-005 — IMPLEMENTED_UNVERIFIED

- **Original requirement/source:** C/cell-P-08-audit.md, finding CA-P-08-005
- **Owning segment(s):** 20,28,39
- **Legal execution path and consumers:** Real provider entrypoints → prepared payload/audit → captured HTTP transport
- **Intended acceptance:** Acceptance tests must call real provider serializers and inspect actual posted bytes, not only test CloudPayloadPolicy in isolation.
- **Current implementation / line anchors:** T/data/ai/provider/CloudProviderTransportPayloadTest.kt supplies a transport capture matrix over nine real provider entrypoints; existing CloudProviderPreparedPayloadTest documentation now states its narrower policy-only scope.
- **Tests and meaningful assertions:** Matrix asserts exact approved text/image bytes and audit identity, raw sentinel absence, and no HTTP/preparation on denied/fail-closed/missing-key paths. It uses actual provider entry methods rather than a stand-in policy method.
- **Execution evidence and tested snapshot:** New all-provider transport test execution not located in current evidence. Query-only actual-provider tests separately passed under CL-21.
- **Residual risk / missing evidence:** Source is materially better than marker/name coverage; authored matrix is not executed evidence. Run the full provider class before acceptance.

## 7. Human-only serialized validation handback

These are proposed commands, **NOT RUN by this reviewer**. Obtain human authorization and complete the corresponding source/test repairs plus strict review before rerunning a known failing contract. Preserve positive and negative assertions, recursive discovery, guard strength and exact exception scope. Required guardian decisions are not supplied by this independent report.

Run from the repository root. The checked-in `scripts/vrun.ps1` is the human convenience wrapper around `scripts/validation-runner.ps1` Start/Wait; it blocks on one durable run. Do not invoke Gradle, pytest or guard scripts directly, and do not run these blocks concurrently. The wrapper’s documented HUMAN_REQUEST overlap reason concerns deduplication, not permission to bypass the global live-run lock. Stop on every nonzero/unknown/stale/infra result. If a wrapper reaches its waiting deadline, poll its existing run ID rather than starting a duplicate.

Existing RUNNING/STARTING run, if any (replace only the run ID with the actual persisted ID):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Wait -RunId '<existing-run-id>' -MaxWaitSeconds 300
```

### 7.1 Remaining/current failing runtime contracts

First repair the three Radar happy-path income fixtures without altering their assertions; resolve the explicit-currency forecast contract; independently investigate the logger timeout fixture. The following class names were verified to exist. Wildcards deliberately retain every matching class, including duplicate simple names; do not narrow selection to evade a failing test. The first failure stops the sequence.

```powershell
$filters = @(
    '*ComputeMoneyRadarUseCaseTest',
    '*ComputeDashboardWidgetsUseCasePaceWiringTest',
    '*FinancialStressForecastEngineTest',
    '*DebugViewModelDatabaseStatsTest',
    '*BackupRestoreViewModelPrivacyDenialTest',
    '*ReviewViewModelPrivacyDenialTest',
    '*ExpenseRepositoryMerchantKeyBackfillTest',
    '*MerchantKeyBackfillWorkerTest',
    '*WarrantyReminderDeliveryDaoTest',
    '*WarrantyExpirationWorkerTest',
    '*GroupSettlementBalancePolicyTest',
    '*GroupBalanceCalculatorTest',
    '*SharedExpenseManagerSettlementTest',
    '*SharedExpenseSettlementReadTest',
    '*GroupsRepositoryImplTest',
    '*SharedExpenseGroupsViewModelTest',
    '*GroupTransactionCoordinatorTest',
    '*CloudProviderTransportPayloadTest',
    '*WorkerEntryPointProofTest',
    '*WorkerGuardArchitectureGuardTest',
    '*WorkerRunLoggerTest'
)
foreach ($filter in $filters) {
    pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter $filter
    if ($LASTEXITCODE -ne 0) { throw "Validation stopped for $filter (exit $LASTEXITCODE); inspect the persisted result, do not start another run." }
}
```

### 7.2 Refresh committed money/currency interactions when required by the final snapshot

Historical CL-27/CL-05 evidence is retained below, not erased. These targeted reruns are recommended where final changed consumers or snapshot freshness require renewed cross-cluster evidence, rather than claiming the historical waiver grants a current all-green result.

```powershell
$filters = @(
    '*CurrencyRatesRepositoryImplTest',
    '*ConversionSemanticsHardeningTest',
    '*CurrencyNormalizationBehavioralTest',
    '*MoneyAggregateBuilderRestrictionTest',
    '*MoneyAggregateConversionScenarioTest',
    '*BudgetForecastingEngineTest',
    '*BudgetForecastingEngineDiagnosticsTest',
    '*MultiCurrencyRepositoryTest',
    '*MultiCurrencyAnalyticsTest',
    '*BillReminderManagerTest',
    '*BillRemindersViewModelTest',
    '*BudgetAutopilotEngineTest',
    '*BudgetViewModelAutopilotQualityTest',
    '*AdvancedAnalyticsEngineNormalizedTest',
    '*InsightsEngineValidationTest',
    '*TaxEstimatorTest',
    '*NotificationMoneySignalDetectorTest',
    '*SharedExpenseBudgetOffsetEngineTest'
)
foreach ($filter in $filters) {
    pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter $filter
    if ($LASTEXITCODE -ne 0) { throw "Validation stopped for $filter (exit $LASTEXITCODE); inspect the persisted result, do not start another run." }
}
```

### 7.3 Compile and complete guards, separately

After reviewed repairs and explicit human approval:

```powershell
pwsh scripts/vrun.ps1 -Worktree . -Profile compile
if ($LASTEXITCODE -ne 0) { throw "Compile did not pass; stop and inspect its result." }
pwsh scripts/vrun.ps1 -Worktree . -Profile static-guards -MaxTotalMinutes 130
if ($LASTEXITCODE -ne 0) { throw "Full guards did not pass; retain every violation and infrastructure failure." }
```

Only if separately required/authorized as an expensive broader check: `pwsh scripts/vrun.ps1 -Worktree . -Profile app-check`. This does not replace full static-guards or the full unit suite. No proposed command is retrospective evidence of PASS.

Still missing is a real partial OCR → persisted import ledger → review UI test and coverage of the generic PDF receipt surface. Existing helper classes cannot substitute for that integration. Do not invent a passing run or a nonexistent test name; add that coverage only in a subsequent explicitly authorized implementation task.

For each future run retain command, run ID, status, exit code, result/stdout/stderr/marker paths, HEAD and matching start/end fingerprints. Any human edit, including this new report, changes the next worktree identity. Associate results with their actual snapshot, not just bug-fixes/a4807632.

## 8. Persisted validation evidence ledger

Read-only evidence, not reviewer execution. Result records preserve failures and stale/unknown states rather than selecting only passing runs. Exact underlying Gradle commands are recorded for provenance only; agents/humans must use the runner, not copy them as direct execution commands. Dirty fingerprints are byte/path identities, not proof that every untracked/cache file was discoverable.

### 8.1 Main checkout, September 27, 2026 — 37 records

For each main run, sibling `stdout.log`, `stderr.log` and `complete.marker` are in its result directory. Completion-marker presence is recorded separately; a marker is not itself PASS.

#### vr-20260927-065201-c65ae48d — PASS / exit 0

- Profile: `compile`; completion marker present: true.
- Command: `gradlew.bat :app:compileDebugKotlin --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `411e56e48b6074b18f9f5e1740d2a1f17c6ceb3441bf8e0d649c4be419c8c055`
- End fingerprint: `411e56e48b6074b18f9f5e1740d2a1f17c6ceb3441bf8e0d649c4be419c8c055`
- Started / finished (UTC): `2026-09-27T06:52:02.0197499Z` / `2026-09-27T06:58:57.6098284Z`
- Result: `build/validation-runs/vr-20260927-065201-c65ae48d/result.json`; failure code: `null`

#### vr-20260927-065901-f24c074f — FAIL / exit 1

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *WorkerRunLoggerTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `411e56e48b6074b18f9f5e1740d2a1f17c6ceb3441bf8e0d649c4be419c8c055`
- End fingerprint: `411e56e48b6074b18f9f5e1740d2a1f17c6ceb3441bf8e0d649c4be419c8c055`
- Started / finished (UTC): `2026-09-27T06:59:01.5658306Z` / `2026-09-27T07:03:00.1745726Z`
- Result: `build/validation-runs/vr-20260927-065901-f24c074f/result.json`; failure code: `E_COMMAND_FAILED`

#### vr-20260927-070515-61dd968f — FAIL / exit 1

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *WorkerRunLoggerTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `9c341b04d713bd711c0421e756536aaa602090bf20f4839159fb6e7ee4fee583`
- End fingerprint: `9c341b04d713bd711c0421e756536aaa602090bf20f4839159fb6e7ee4fee583`
- Started / finished (UTC): `2026-09-27T07:05:15.5582329Z` / `2026-09-27T07:10:31.2140114Z`
- Result: `build/validation-runs/vr-20260927-070515-61dd968f/result.json`; failure code: `E_COMMAND_FAILED`

#### vr-20260927-071601-23e3f70b — FAIL / exit 1

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *WorkerRunLoggerTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `9c341b04d713bd711c0421e756536aaa602090bf20f4839159fb6e7ee4fee583`
- End fingerprint: `9c341b04d713bd711c0421e756536aaa602090bf20f4839159fb6e7ee4fee583`
- Started / finished (UTC): `2026-09-27T07:16:01.1581057Z` / `2026-09-27T07:17:15.4660987Z`
- Result: `build/validation-runs/vr-20260927-071601-23e3f70b/result.json`; failure code: `E_COMMAND_FAILED`

#### vr-20260927-072820-45f3207d — PASS / exit 0

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *WorkerLeaseRegistryTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `15333c28a9fd2b6b0676bc0dedb7ac031203bb10ded91861976872f8483a016e`
- End fingerprint: `15333c28a9fd2b6b0676bc0dedb7ac031203bb10ded91861976872f8483a016e`
- Started / finished (UTC): `2026-09-27T07:28:20.1448896Z` / `2026-09-27T07:29:35.1755260Z`
- Result: `build/validation-runs/vr-20260927-072820-45f3207d/result.json`; failure code: `null`

#### vr-20260927-072938-3d8cbcfd — PASS / exit 0

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *WorkerRestoreRegressionTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `15333c28a9fd2b6b0676bc0dedb7ac031203bb10ded91861976872f8483a016e`
- End fingerprint: `15333c28a9fd2b6b0676bc0dedb7ac031203bb10ded91861976872f8483a016e`
- Started / finished (UTC): `2026-09-27T07:29:38.8462894Z` / `2026-09-27T07:31:00.6636188Z`
- Result: `build/validation-runs/vr-20260927-072938-3d8cbcfd/result.json`; failure code: `null`

#### vr-20260927-073103-32381c10 — PASS / exit 0

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *WorkerExecutionGuardTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `15333c28a9fd2b6b0676bc0dedb7ac031203bb10ded91861976872f8483a016e`
- End fingerprint: `15333c28a9fd2b6b0676bc0dedb7ac031203bb10ded91861976872f8483a016e`
- Started / finished (UTC): `2026-09-27T07:31:03.5439918Z` / `2026-09-27T07:32:24.9289077Z`
- Result: `build/validation-runs/vr-20260927-073103-32381c10/result.json`; failure code: `null`

#### vr-20260927-073228-41d809da — PASS / exit 0

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *MaintenanceOperationRunnerTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `15333c28a9fd2b6b0676bc0dedb7ac031203bb10ded91861976872f8483a016e`
- End fingerprint: `15333c28a9fd2b6b0676bc0dedb7ac031203bb10ded91861976872f8483a016e`
- Started / finished (UTC): `2026-09-27T07:32:28.1587112Z` / `2026-09-27T07:33:44.7943332Z`
- Result: `build/validation-runs/vr-20260927-073228-41d809da/result.json`; failure code: `null`

#### vr-20260927-073349-ab86c269 — PASS / exit 0

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *DataRetentionWorkerTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `15333c28a9fd2b6b0676bc0dedb7ac031203bb10ded91861976872f8483a016e`
- End fingerprint: `15333c28a9fd2b6b0676bc0dedb7ac031203bb10ded91861976872f8483a016e`
- Started / finished (UTC): `2026-09-27T07:33:49.1400346Z` / `2026-09-27T07:35:20.5898390Z`
- Result: `build/validation-runs/vr-20260927-073349-ab86c269/result.json`; failure code: `null`

#### vr-20260927-073522-2816bf62 — FAIL / exit 1

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *ReceiptMatchLifecycleServiceTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `15333c28a9fd2b6b0676bc0dedb7ac031203bb10ded91861976872f8483a016e`
- End fingerprint: `15333c28a9fd2b6b0676bc0dedb7ac031203bb10ded91861976872f8483a016e`
- Started / finished (UTC): `2026-09-27T07:35:22.6393252Z` / `2026-09-27T07:36:42.8726606Z`
- Result: `build/validation-runs/vr-20260927-073522-2816bf62/result.json`; failure code: `E_COMMAND_FAILED`

#### vr-20260927-074529-57e4e7b1 — FAIL / exit 2

- Profile: `static-guards`; completion marker present: true.
- Command: `python.exe scripts/ci/run_static_guard_suite.py --output-dir build/ci/static-guards`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `79c3efcc005047a63e4d99296275212c64b32ad78f5bd7e5c0e54a3510c3c9c0`
- End fingerprint: `79c3efcc005047a63e4d99296275212c64b32ad78f5bd7e5c0e54a3510c3c9c0`
- Started / finished (UTC): `2026-09-27T07:45:29.9814310Z` / `2026-09-27T07:48:25.9983676Z`
- Result: `build/validation-runs/vr-20260927-074529-57e4e7b1/result.json`; failure code: `E_COMMAND_FAILED`

#### vr-20260927-075548-69c06619 — PASS / exit 0

- Profile: `registered-guard`; completion marker present: true.
- Command: `python.exe scripts/ci/run_registered_guard.py --guard-id allowlist_compliance --context direct --root . --ci-mode`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `99500e751f345da729934594eb91b44aa17f67382b0f61f66ba33bc5563defa6`
- End fingerprint: `99500e751f345da729934594eb91b44aa17f67382b0f61f66ba33bc5563defa6`
- Started / finished (UTC): `2026-09-27T07:55:48.7240273Z` / `2026-09-27T07:55:54.3700608Z`
- Result: `build/validation-runs/vr-20260927-075548-69c06619/result.json`; failure code: `null`

#### vr-20260927-083526-ba86299f — PASS / exit 0

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *ReceiptMatchLifecycleServiceTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `d3dc6ad2b25ddeead2fdbfaf2117fccb1d8394c45abb91e22d23d8c266478c1a`
- End fingerprint: `d3dc6ad2b25ddeead2fdbfaf2117fccb1d8394c45abb91e22d23d8c266478c1a`
- Started / finished (UTC): `2026-09-27T08:35:26.9201043Z` / `2026-09-27T08:40:41.3064170Z`
- Result: `build/validation-runs/vr-20260927-083526-ba86299f/result.json`; failure code: `null`

#### vr-20260927-084139-95dbc7df — FAIL / exit 2

- Profile: `static-guards`; completion marker present: true.
- Command: `python.exe scripts/ci/run_static_guard_suite.py --output-dir build/ci/static-guards`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `d3dc6ad2b25ddeead2fdbfaf2117fccb1d8394c45abb91e22d23d8c266478c1a`
- End fingerprint: `d3dc6ad2b25ddeead2fdbfaf2117fccb1d8394c45abb91e22d23d8c266478c1a`
- Started / finished (UTC): `2026-09-27T08:41:39.5806036Z` / `2026-09-27T08:54:08.3572750Z`
- Result: `build/validation-runs/vr-20260927-084139-95dbc7df/result.json`; failure code: `E_COMMAND_FAILED`

#### vr-20260927-085550-6ddce29c — PASS / exit 0

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *ReceiptMatchingWorkerTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `42cf053a0935a8931f7802a23843bb3fe3ec27458c020b39259e80bc61622e25`
- End fingerprint: `42cf053a0935a8931f7802a23843bb3fe3ec27458c020b39259e80bc61622e25`
- Started / finished (UTC): `2026-09-27T08:55:50.5322572Z` / `2026-09-27T08:57:16.8947469Z`
- Result: `build/validation-runs/vr-20260927-085550-6ddce29c/result.json`; failure code: `null`

#### vr-20260927-085720-38d8280e — FAIL / exit 1

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *RecurringRuleLifecycleCoordinatorTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `42cf053a0935a8931f7802a23843bb3fe3ec27458c020b39259e80bc61622e25`
- End fingerprint: `42cf053a0935a8931f7802a23843bb3fe3ec27458c020b39259e80bc61622e25`
- Started / finished (UTC): `2026-09-27T08:57:20.2634532Z` / `2026-09-27T08:58:42.1142597Z`
- Result: `build/validation-runs/vr-20260927-085720-38d8280e/result.json`; failure code: `E_COMMAND_FAILED`

#### vr-20260927-090429-574892fa — PASS / exit 0

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *RecurringRuleLifecycleCoordinatorTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- End fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- Started / finished (UTC): `2026-09-27T09:04:29.8534552Z` / `2026-09-27T09:12:41.1559243Z`
- Result: `build/validation-runs/vr-20260927-090429-574892fa/result.json`; failure code: `null`

#### vr-20260927-091246-8a67e2a5 — PASS / exit 0

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *CloudQueryPreparedPayloadTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- End fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- Started / finished (UTC): `2026-09-27T09:12:46.3364641Z` / `2026-09-27T09:14:25.0269572Z`
- Result: `build/validation-runs/vr-20260927-091246-8a67e2a5/result.json`; failure code: `null`

#### vr-20260927-091428-7d4a0dec — PASS / exit 0

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *CloudQueryInterpretationServiceTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- End fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- Started / finished (UTC): `2026-09-27T09:14:28.8842872Z` / `2026-09-27T09:15:49.9113944Z`
- Result: `build/validation-runs/vr-20260927-091428-7d4a0dec/result.json`; failure code: `null`

#### vr-20260927-091552-943e7d39 — PASS / exit 0

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *HybridRouterIntegrationTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- End fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- Started / finished (UTC): `2026-09-27T09:15:52.8858201Z` / `2026-09-27T09:17:06.4588310Z`
- Result: `build/validation-runs/vr-20260927-091552-943e7d39/result.json`; failure code: `null`

#### vr-20260927-091710-a79db7ef — PASS / exit 0

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *HybridRouterTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- End fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- Started / finished (UTC): `2026-09-27T09:17:10.7316819Z` / `2026-09-27T09:18:39.4874183Z`
- Result: `build/validation-runs/vr-20260927-091710-a79db7ef/result.json`; failure code: `null`

#### vr-20260927-091844-eaa72d24 — PASS / exit 0

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *HybridServiceDelegationTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- End fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- Started / finished (UTC): `2026-09-27T09:18:44.3942894Z` / `2026-09-27T09:20:00.2820564Z`
- Result: `build/validation-runs/vr-20260927-091844-eaa72d24/result.json`; failure code: `null`

#### vr-20260927-092002-accaa2ab — PASS / exit 0

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *HybridReceiptItemCategorizationServiceTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- End fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- Started / finished (UTC): `2026-09-27T09:20:02.0998946Z` / `2026-09-27T09:21:13.6336337Z`
- Result: `build/validation-runs/vr-20260927-092002-accaa2ab/result.json`; failure code: `null`

#### vr-20260927-092218-e662e35e — PASS / exit 0

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *PrivacyDeniedExceptionTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- End fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- Started / finished (UTC): `2026-09-27T09:22:18.2950526Z` / `2026-09-27T09:23:29.0202320Z`
- Result: `build/validation-runs/vr-20260927-092218-e662e35e/result.json`; failure code: `null`

#### vr-20260927-092333-e74c1d9e — PASS / exit 0

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *PrivacyCapabilityPolicyTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- End fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- Started / finished (UTC): `2026-09-27T09:23:33.9208458Z` / `2026-09-27T09:24:42.4880841Z`
- Result: `build/validation-runs/vr-20260927-092333-e74c1d9e/result.json`; failure code: `null`

#### vr-20260927-092445-97ba6ecd — PASS / exit 0

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *CompositePrivacyGateTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- End fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- Started / finished (UTC): `2026-09-27T09:24:45.6922446Z` / `2026-09-27T09:26:07.5949009Z`
- Result: `build/validation-runs/vr-20260927-092445-97ba6ecd/result.json`; failure code: `null`

#### vr-20260927-092609-52f90278 — PASS / exit 0

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *BackupPrivacyGateOwnershipTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- End fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- Started / finished (UTC): `2026-09-27T09:26:09.8650343Z` / `2026-09-27T09:27:14.0813416Z`
- Result: `build/validation-runs/vr-20260927-092609-52f90278/result.json`; failure code: `null`

#### vr-20260927-092715-b38abb28 — PASS / exit 0

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *KeystoreInstallationSecretHashingTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- End fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- Started / finished (UTC): `2026-09-27T09:27:15.7820186Z` / `2026-09-27T09:28:18.1106598Z`
- Result: `build/validation-runs/vr-20260927-092715-b38abb28/result.json`; failure code: `null`

#### vr-20260927-092821-7611d18a — PASS / exit 0

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *MoneyAggregateBuilderRestrictionTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- End fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- Started / finished (UTC): `2026-09-27T09:28:21.6068008Z` / `2026-09-27T09:29:24.5876163Z`
- Result: `build/validation-runs/vr-20260927-092821-7611d18a/result.json`; failure code: `null`

#### vr-20260927-092928-ae9b8fa3 — PASS / exit 0

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *CsvImportRfc4180Test --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- End fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- Started / finished (UTC): `2026-09-27T09:29:28.0850641Z` / `2026-09-27T09:30:32.1269754Z`
- Result: `build/validation-runs/vr-20260927-092928-ae9b8fa3/result.json`; failure code: `null`

#### vr-20260927-093131-51164d19 — PASS / exit 0

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *BackupVerifierRequiredSemanticQueryTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- End fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- Started / finished (UTC): `2026-09-27T09:31:31.4398497Z` / `2026-09-27T09:32:50.3279981Z`
- Result: `build/validation-runs/vr-20260927-093131-51164d19/result.json`; failure code: `null`

#### vr-20260927-093252-e17f7df2 — FAIL / exit 1

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *DatabaseBackupRepositoryImplTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- End fingerprint: `4a47b63691c6b39a628ff6920df84a5b5da2e4ce8e721c9a18574892bc0a4f56`
- Started / finished (UTC): `2026-09-27T09:32:52.4671587Z` / `2026-09-27T09:35:50.7446984Z`
- Result: `build/validation-runs/vr-20260927-093252-e17f7df2/result.json`; failure code: `E_COMMAND_FAILED`

#### vr-20260927-093848-79b37e05 — PASS / exit 0

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *DatabaseBackupRepositoryImplTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `e42476881bee5aca837972f74099f387a41e1e9b17cd7523fa1816ddfb275a5d`
- End fingerprint: `e42476881bee5aca837972f74099f387a41e1e9b17cd7523fa1816ddfb275a5d`
- Started / finished (UTC): `2026-09-27T09:38:48.6983238Z` / `2026-09-27T09:45:42.1394118Z`
- Result: `build/validation-runs/vr-20260927-093848-79b37e05/result.json`; failure code: `null`

#### vr-20260927-094544-785c78ec — PASS / exit 0

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *OcrResultPartialTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `e42476881bee5aca837972f74099f387a41e1e9b17cd7523fa1816ddfb275a5d`
- End fingerprint: `e42476881bee5aca837972f74099f387a41e1e9b17cd7523fa1816ddfb275a5d`
- Started / finished (UTC): `2026-09-27T09:45:44.6869249Z` / `2026-09-27T09:46:53.9716707Z`
- Result: `build/validation-runs/vr-20260927-094544-785c78ec/result.json`; failure code: `null`

#### vr-20260927-094656-966b1b92 — PASS / exit 0

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *ReceiptOcrRetryIsolationTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `e42476881bee5aca837972f74099f387a41e1e9b17cd7523fa1816ddfb275a5d`
- End fingerprint: `e42476881bee5aca837972f74099f387a41e1e9b17cd7523fa1816ddfb275a5d`
- Started / finished (UTC): `2026-09-27T09:46:56.6385936Z` / `2026-09-27T09:47:59.4651518Z`
- Result: `build/validation-runs/vr-20260927-094656-966b1b92/result.json`; failure code: `null`

#### vr-20260927-094802-9d804870 — PASS / exit 0

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *BankStatementCompletionStatusTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `e42476881bee5aca837972f74099f387a41e1e9b17cd7523fa1816ddfb275a5d`
- End fingerprint: `e42476881bee5aca837972f74099f387a41e1e9b17cd7523fa1816ddfb275a5d`
- Started / finished (UTC): `2026-09-27T09:48:02.5122339Z` / `2026-09-27T09:49:03.2980212Z`
- Result: `build/validation-runs/vr-20260927-094802-9d804870/result.json`; failure code: `null`

#### vr-20260927-094905-1a35bdce — FAIL / exit 1

- Profile: `targeted-unit-test`; completion marker present: true.
- Command: `gradlew.bat :app:testDebugUnitTest --tests *ComputeMoneyRadarUseCaseTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`
- Start fingerprint: `e42476881bee5aca837972f74099f387a41e1e9b17cd7523fa1816ddfb275a5d`
- End fingerprint: `e42476881bee5aca837972f74099f387a41e1e9b17cd7523fa1816ddfb275a5d`
- Started / finished (UTC): `2026-09-27T09:49:05.3695132Z` / `2026-09-27T09:50:18.4462931Z`
- Result: `build/validation-runs/vr-20260927-094905-1a35bdce/result.json`; failure code: `E_COMMAND_FAILED`

### 8.2 Retained CL-27/CL-05 money/currency runs — 38 records

These historical lanes are evidence, not current reviewer worktrees. No branch/worktree was created or changed. Clean ancestor results are distinguished from historical dirty runs. Current byte equivalence was specifically established only where stated in the matrix; a historical PASS is not automatically transplanted onto S2. Sibling logs belong to each listed result directory.

#### vr-20260925-164059-2e7f8ff2 — FAIL / exit 1

- Command: `gradlew.bat :app:testDebugUnitTest --tests *CurrencyRatesRepositoryImplTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `ac9e5748ed4411dc187cd398b794fac055ccbf64`
- Start fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- End fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- Result: `build/worktrees/rp-26/build/validation-runs/vr-20260925-164059-2e7f8ff2/result.json`

#### vr-20260925-165641-394dc397 — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *ConversionSemanticsHardeningTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `ac9e5748ed4411dc187cd398b794fac055ccbf64`
- Start fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- End fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- Result: `build/worktrees/rp-26/build/validation-runs/vr-20260925-165641-394dc397/result.json`

#### vr-20260925-165808-5fc94533 — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *TimePeriodUtilsT4CBatch1Test --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `ac9e5748ed4411dc187cd398b794fac055ccbf64`
- Start fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- End fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- Result: `build/worktrees/rp-26/build/validation-runs/vr-20260925-165808-5fc94533/result.json`

#### vr-20260925-170240-bd04df17 — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *MoneyAggregateBuilderRestrictionTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `ac9e5748ed4411dc187cd398b794fac055ccbf64`
- Start fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- End fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- Result: `build/worktrees/rp-26/build/validation-runs/vr-20260925-170240-bd04df17/result.json`

#### vr-20260925-170358-3b577919 — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests com.yourname.expensetracker.domain.core.money.CurrencyNormalizationBehavioralTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `ac9e5748ed4411dc187cd398b794fac055ccbf64`
- Start fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- End fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- Result: `build/worktrees/rp-26/build/validation-runs/vr-20260925-170358-3b577919/result.json`

#### vr-20260925-171931-47926751 — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *CurrencyRatesRepositoryImplTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `ae20fdcd466c1f2ab7355293bb458ec9184b5fd1`
- Start fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- End fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- Result: `build/worktrees/rp-26/build/validation-runs/vr-20260925-171931-47926751/result.json`

#### vr-20260925-172610-3ac61c0e — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *MoneyAggregateConversionScenarioTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `ae20fdcd466c1f2ab7355293bb458ec9184b5fd1`
- Start fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- End fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- Result: `build/worktrees/rp-26/build/validation-runs/vr-20260925-172610-3ac61c0e/result.json`

#### vr-20260925-175839-f58b8551 — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests BudgetForecastingEngineDiagnosticsTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `459a4651aa326c57bfee650ce42498dd61d7a681`
- Start fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- End fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- Result: `build/worktrees/rp-26/build/validation-runs/vr-20260925-175839-f58b8551/result.json`

#### vr-20260925-175945-5e4e4397 — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests CurrencyRatesRepositoryImplTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `459a4651aa326c57bfee650ce42498dd61d7a681`
- Start fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- End fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- Result: `build/worktrees/rp-26/build/validation-runs/vr-20260925-175945-5e4e4397/result.json`

#### vr-20260925-183428-56851761 — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *BudgetForecastingEngineDiagnosticsTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `3286c3760386deaa1556b5153311f3574c5ec9eb`
- Start fingerprint: `68221cf429d52fdcf324a1bab340f04be255d3e80fd2271f5b8b80296c4046dc`
- End fingerprint: `68221cf429d52fdcf324a1bab340f04be255d3e80fd2271f5b8b80296c4046dc`
- Result: `build/worktrees/rp-26/build/validation-runs/vr-20260925-183428-56851761/result.json`

#### vr-20260925-192632-6aaa4757 — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *CurrencyRatesRepositoryImplTest* --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `7241e7c1056022e9bbf429828780e13ec26a9c85`
- Start fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- End fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260925-192632-6aaa4757/result.json`

#### vr-20260925-194235-1ce77a5b — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *CurrencyManagementViewModelTest* --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `7241e7c1056022e9bbf429828780e13ec26a9c85`
- Start fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- End fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260925-194235-1ce77a5b/result.json`

#### vr-20260925-194530-5bab821c — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *ConversionSemanticsHardeningTest* --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `7241e7c1056022e9bbf429828780e13ec26a9c85`
- Start fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- End fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260925-194530-5bab821c/result.json`

#### vr-20260925-195745-494d8324 — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *MultiCurrencyRepositoryTest* --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `7241e7c1056022e9bbf429828780e13ec26a9c85`
- Start fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- End fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260925-195745-494d8324/result.json`

#### vr-20260925-202048-9e05453b — FAIL / exit 1

- Command: `gradlew.bat :app:testDebugUnitTest --tests NotificationMoneySignalDetectorTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `6fb265d5e25cba949fc3776ca4cad2b704dea287`
- Start fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- End fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260925-202048-9e05453b/result.json`

#### vr-20260925-204226-a0a270f6 — INFRA_FAILURE / exit null

- Command: `gradlew.bat :app:testDebugUnitTest --tests NotificationMoneySignalDetectorTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `213031886935185ad92e7468575985c47a7f687a`
- Start fingerprint: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- End fingerprint: `null`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260925-204226-a0a270f6/result.json`

#### vr-20260925-212915-4f043232 — FAIL / exit 1

- Command: `gradlew.bat :app:testDebugUnitTest --tests NotificationMoneySignalDetectorTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `6547b05a26a68cd45c7c83254eedad3bc6dd7827`
- Start fingerprint: `7666d54d2ba9dfff593861c85b79bd08983ef7843fdb49d80aac95e8b2f18353`
- End fingerprint: `63fea58ff9fae7d7230711d94abe1dce22aa9a32179bf88a2cc53eb7774565d4`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260925-212915-4f043232/result.json`

#### vr-20260926-075021-30177ece — FAIL / exit 1

- Command: `gradlew.bat :app:testDebugUnitTest --tests *CurrencyRatesRepositoryImplTest* --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `6547b05a26a68cd45c7c83254eedad3bc6dd7827`
- Start fingerprint: `2bcc1883dd5350a8b5ebc6efc5a48a842810b5d6ad38781bdb5170be6c854b16`
- End fingerprint: `2bcc1883dd5350a8b5ebc6efc5a48a842810b5d6ad38781bdb5170be6c854b16`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260926-075021-30177ece/result.json`

#### vr-20260926-080920-e28d7960 — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *CurrencyRatesRepositoryImplTest* --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `6547b05a26a68cd45c7c83254eedad3bc6dd7827`
- Start fingerprint: `5f28b0dba9d7a81951c10e09b7c922947b1ae07a164092d7fc6d29b5b6a2218e`
- End fingerprint: `5f28b0dba9d7a81951c10e09b7c922947b1ae07a164092d7fc6d29b5b6a2218e`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260926-080920-e28d7960/result.json`

#### vr-20260926-081650-858a5ede — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *CurrencyManagementViewModelTest* --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `6547b05a26a68cd45c7c83254eedad3bc6dd7827`
- Start fingerprint: `5f28b0dba9d7a81951c10e09b7c922947b1ae07a164092d7fc6d29b5b6a2218e`
- End fingerprint: `5f28b0dba9d7a81951c10e09b7c922947b1ae07a164092d7fc6d29b5b6a2218e`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260926-081650-858a5ede/result.json`

#### vr-20260926-081824-5de27712 — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *NotificationMoneySignalDetectorTest* --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `6547b05a26a68cd45c7c83254eedad3bc6dd7827`
- Start fingerprint: `5f28b0dba9d7a81951c10e09b7c922947b1ae07a164092d7fc6d29b5b6a2218e`
- End fingerprint: `5f28b0dba9d7a81951c10e09b7c922947b1ae07a164092d7fc6d29b5b6a2218e`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260926-081824-5de27712/result.json`

#### vr-20260926-082315-9477d7ce — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *BillReminderManagerTest* --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `6547b05a26a68cd45c7c83254eedad3bc6dd7827`
- Start fingerprint: `5f28b0dba9d7a81951c10e09b7c922947b1ae07a164092d7fc6d29b5b6a2218e`
- End fingerprint: `5f28b0dba9d7a81951c10e09b7c922947b1ae07a164092d7fc6d29b5b6a2218e`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260926-082315-9477d7ce/result.json`

#### vr-20260926-082439-2f817cad — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *BillRemindersViewModelTest* --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `6547b05a26a68cd45c7c83254eedad3bc6dd7827`
- Start fingerprint: `5f28b0dba9d7a81951c10e09b7c922947b1ae07a164092d7fc6d29b5b6a2218e`
- End fingerprint: `5f28b0dba9d7a81951c10e09b7c922947b1ae07a164092d7fc6d29b5b6a2218e`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260926-082439-2f817cad/result.json`

#### vr-20260926-082548-bae0b42a — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *BudgetAutopilotEngineTest* --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `6547b05a26a68cd45c7c83254eedad3bc6dd7827`
- Start fingerprint: `5f28b0dba9d7a81951c10e09b7c922947b1ae07a164092d7fc6d29b5b6a2218e`
- End fingerprint: `5f28b0dba9d7a81951c10e09b7c922947b1ae07a164092d7fc6d29b5b6a2218e`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260926-082548-bae0b42a/result.json`

#### vr-20260926-082854-3893766f — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *BudgetAutopilotUiContractTest* --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `6547b05a26a68cd45c7c83254eedad3bc6dd7827`
- Start fingerprint: `5f28b0dba9d7a81951c10e09b7c922947b1ae07a164092d7fc6d29b5b6a2218e`
- End fingerprint: `5f28b0dba9d7a81951c10e09b7c922947b1ae07a164092d7fc6d29b5b6a2218e`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260926-082854-3893766f/result.json`

#### vr-20260926-083014-edce2f73 — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *AdvancedAnalyticsEngineNormalizedTest* --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `6547b05a26a68cd45c7c83254eedad3bc6dd7827`
- Start fingerprint: `5f28b0dba9d7a81951c10e09b7c922947b1ae07a164092d7fc6d29b5b6a2218e`
- End fingerprint: `5f28b0dba9d7a81951c10e09b7c922947b1ae07a164092d7fc6d29b5b6a2218e`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260926-083014-edce2f73/result.json`

#### vr-20260926-083125-bc3ebec0 — FAIL / exit 1

- Command: `gradlew.bat :app:testDebugUnitTest --tests *InsightsEngineValidationTest* --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `6547b05a26a68cd45c7c83254eedad3bc6dd7827`
- Start fingerprint: `5f28b0dba9d7a81951c10e09b7c922947b1ae07a164092d7fc6d29b5b6a2218e`
- End fingerprint: `5f28b0dba9d7a81951c10e09b7c922947b1ae07a164092d7fc6d29b5b6a2218e`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260926-083125-bc3ebec0/result.json`

#### vr-20260926-084759-816a50a2 — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *InsightsEngineValidationTest* --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `315abb3046841f6f30dd94bbdae92214debbcaf1`
- Start fingerprint: `9b17546371e2a96187a347298f280737b61c5bd26e93d33ceea7835ba519f159`
- End fingerprint: `9b17546371e2a96187a347298f280737b61c5bd26e93d33ceea7835ba519f159`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260926-084759-816a50a2/result.json`

#### vr-20260926-085219-6b4df888 — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *TaxEstimatorTest* --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `315abb3046841f6f30dd94bbdae92214debbcaf1`
- Start fingerprint: `9b17546371e2a96187a347298f280737b61c5bd26e93d33ceea7835ba519f159`
- End fingerprint: `9b17546371e2a96187a347298f280737b61c5bd26e93d33ceea7835ba519f159`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260926-085219-6b4df888/result.json`

#### vr-20260926-085716-8666e672 — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *SharedExpenseBudgetOffsetEngineTest* --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `315abb3046841f6f30dd94bbdae92214debbcaf1`
- Start fingerprint: `9b17546371e2a96187a347298f280737b61c5bd26e93d33ceea7835ba519f159`
- End fingerprint: `9b17546371e2a96187a347298f280737b61c5bd26e93d33ceea7835ba519f159`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260926-085716-8666e672/result.json`

#### vr-20260926-103839-214b6c78 — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *NotificationMoneySignalDetectorTest* --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `315abb3046841f6f30dd94bbdae92214debbcaf1`
- Start fingerprint: `0db57e20bbb206f6107da59a299da5d8d58eb744af4a4f7fda31e2545fccb9b5`
- End fingerprint: `0db57e20bbb206f6107da59a299da5d8d58eb744af4a4f7fda31e2545fccb9b5`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260926-103839-214b6c78/result.json`

#### vr-20260926-105201-3f5990ac — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *TaxEstimatorTest* --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `315abb3046841f6f30dd94bbdae92214debbcaf1`
- Start fingerprint: `0db57e20bbb206f6107da59a299da5d8d58eb744af4a4f7fda31e2545fccb9b5`
- End fingerprint: `0db57e20bbb206f6107da59a299da5d8d58eb744af4a4f7fda31e2545fccb9b5`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260926-105201-3f5990ac/result.json`

#### vr-20260926-105314-29924bd6 — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *BillRemindersViewModelTest* --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`
- HEAD: `315abb3046841f6f30dd94bbdae92214debbcaf1`
- Start fingerprint: `0db57e20bbb206f6107da59a299da5d8d58eb744af4a4f7fda31e2545fccb9b5`
- End fingerprint: `0db57e20bbb206f6107da59a299da5d8d58eb744af4a4f7fda31e2545fccb9b5`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260926-105314-29924bd6/result.json`

#### vr-20260926-144720-9e586b39 — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *KeystoreInstallationSecretHashingTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `2544298d76645495fc010cd3494bd19739ca61e3`
- Start fingerprint: `b752c024943fba4c24785d76fc8f7396d373487c06614a6f5119a72917afc694`
- End fingerprint: `b752c024943fba4c24785d76fc8f7396d373487c06614a6f5119a72917afc694`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260926-144720-9e586b39/result.json`

#### vr-20260926-145330-2573dac1 — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *BillRemindersViewModelTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `2544298d76645495fc010cd3494bd19739ca61e3`
- Start fingerprint: `b752c024943fba4c24785d76fc8f7396d373487c06614a6f5119a72917afc694`
- End fingerprint: `b752c024943fba4c24785d76fc8f7396d373487c06614a6f5119a72917afc694`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260926-145330-2573dac1/result.json`

#### vr-20260926-145424-b954044f — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *BillReminderManagerTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `2544298d76645495fc010cd3494bd19739ca61e3`
- Start fingerprint: `b752c024943fba4c24785d76fc8f7396d373487c06614a6f5119a72917afc694`
- End fingerprint: `b752c024943fba4c24785d76fc8f7396d373487c06614a6f5119a72917afc694`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260926-145424-b954044f/result.json`

#### vr-20260926-200442-9dbc1093 — FAIL / exit 1

- Command: `gradlew.bat :app:testDebugUnitTest --tests *MultiCurrencyAnalyticsTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `2544298d76645495fc010cd3494bd19739ca61e3`
- Start fingerprint: `4adb1ee717c3083f496f3ca47cbdd51ffc50a1e294f1122cc6be274bf0152756`
- End fingerprint: `4adb1ee717c3083f496f3ca47cbdd51ffc50a1e294f1122cc6be274bf0152756`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260926-200442-9dbc1093/result.json`

#### vr-20260926-202135-8e39a225 — PASS / exit 0

- Command: `gradlew.bat :app:testDebugUnitTest --tests *MultiCurrencyAnalyticsTest --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`
- HEAD: `2544298d76645495fc010cd3494bd19739ca61e3`
- Start fingerprint: `f1008cf75ad860ec8d667f2166c8a793a886143648d6cc7e69be3e744ef1e4de`
- End fingerprint: `f1008cf75ad860ec8d667f2166c8a793a886143648d6cc7e69be3e744ef1e4de`
- Result: `build/worktrees/rp-27/build/validation-runs/vr-20260926-202135-8e39a225/result.json`

## 9. Pre-report dirty-tree byte manifest and final safety boundary

Latest captured source snapshot: 2026-09-27T12:57:18.3853602+03:00. HEAD `a48076322e57f3d312f34cf6a71139efc4fcc48b`. Runner-compatible dirty fingerprint S2: `0db5789214bc233439569e7377981fc60613fcd2b18dd044afc1c5ff3f6d9c11`. The report itself is intentionally excluded from this pre-report identity. Index was empty; 137 dirty paths comprise 90 modified tracked files and 47 untracked files. Source/test bytes are unchanged from S1 except for the already documented S0→S1 identity fixture repair; S1→S2 changes only JOURNAL.md.

### 9.1 Exact porcelain status before report creation

```text
 M .codex/agents/orchestrator.toml
 M .github/workflows/ci.yml
 M app/src/androidTest/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorkerTest.kt
 M app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt
 M app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridCategorizationAssistService.kt
 M app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridDashboardBriefingService.kt
 M app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridQueryInterpretationService.kt
 M app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridReceiptItemCategorizationService.kt
 M app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridReviewExplanationService.kt
 M app/src/main/java/com/yourname/expensetracker/data/backup/BackupVerifier.kt
 M app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt
 M app/src/main/java/com/yourname/expensetracker/data/database/dao/GroupSettlementDao.kt
 M app/src/main/java/com/yourname/expensetracker/data/database/dao/ManualRecurringExpenseDao.kt
 M app/src/main/java/com/yourname/expensetracker/data/database/dao/ScannedReceiptDao.kt
 M app/src/main/java/com/yourname/expensetracker/data/database/dao/WarrantyReminderDeliveryDao.kt
 M app/src/main/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorker.kt
 M app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt
 M app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt
 M app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepository.kt
 M app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImpl.kt
 M app/src/main/java/com/yourname/expensetracker/data/repository/SharedExpenseDataPortAdapter.kt
 M app/src/main/java/com/yourname/expensetracker/domain/ai/HybridRouter.kt
 M app/src/main/java/com/yourname/expensetracker/domain/backup/DatabaseBackupRepository.kt
 M app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilder.kt
 M app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt
 M app/src/main/java/com/yourname/expensetracker/domain/groups/GroupBalanceCalculator.kt
 M app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseManager.kt
 M app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpensePort.kt
 M app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyDeniedException.kt
 M app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrService.kt
 M app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt
 M app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptMatchLifecycleService.kt
 M app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt
 M app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt
 M app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt
 M app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerLeaseRegistryImpl.kt
 M app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRunLogger.kt
 M app/src/main/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt
 M app/src/main/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorker.kt
 M app/src/main/java/com/yourname/expensetracker/ui/components/FinancialStressForecastCard.kt
 M app/src/main/java/com/yourname/expensetracker/ui/components/dashboard/MoneyRadarWidget.kt
 M app/src/main/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreScreen.kt
 M app/src/main/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModel.kt
 M app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugScreen.kt
 M app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugViewModel.kt
 M app/src/main/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModel.kt
 M app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt
 M app/src/main/java/com/yourname/expensetracker/util/CsvExpenseImporter.kt
 M app/src/main/res/values/strings.xml
 M app/src/test/java/com/yourname/expensetracker/architecture/WorkerGuardArchitectureGuardTest.kt
 M app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationServiceTest.kt
 M app/src/test/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinatorTest.kt
 M app/src/test/java/com/yourname/expensetracker/data/database/dao/WarrantyReminderDeliveryDaoTest.kt
 M app/src/test/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorkerTest.kt
 M app/src/test/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImplTest.kt
 M app/src/test/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImplTest.kt
 M app/src/test/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilderRestrictionTest.kt
 M app/src/test/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngineTest.kt
 M app/src/test/java/com/yourname/expensetracker/domain/privacy/CloudProviderPreparedPayloadTest.kt
 M app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptMatchLifecycleServiceTest.kt
 M app/src/test/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinatorTest.kt
 M app/src/test/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCasePaceWiringTest.kt
 M app/src/test/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCaseTest.kt
 M app/src/test/java/com/yourname/expensetracker/domain/workers/WorkerLeaseRegistryTest.kt
 M app/src/test/java/com/yourname/expensetracker/domain/workers/WorkerRestoreRegressionTest.kt
 M app/src/test/java/com/yourname/expensetracker/domain/workers/WorkerRunLoggerTest.kt
 M app/src/test/java/com/yourname/expensetracker/e2e/NotificationExpenseDashboardPipelineTest.kt
 M app/src/test/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorkerTest.kt
 M app/src/test/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorkerTest.kt
 M app/src/test/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModelPrivacyDenialTest.kt
 M app/src/test/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModelTest.kt
 M app/src/test/java/com/yourname/expensetracker/ui/screens/receiptmatching/ReceiptMatchingViewModelTest.kt
 M app/src/test/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModelPrivacyDenialTest.kt
 M app/src/test/java/com/yourname/expensetracker/util/CsvImportRfc4180Test.kt
 M "docs/analyses and debug master/campaign CA-2026-09-21/JOURNAL.md"
 M "docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-05-review.md"
 M docs/prompts/master-cross-codebase-audit-campaign-prompt-v3.md
 M scripts/allowlists/ui_dao_allowlist.yml
 M scripts/ci/capture_db_guard_evidence.py
 M scripts/ci/guard_ratchet.py
 M scripts/ci/run_static_guard_suite.py
 M scripts/ci/test_capture_db_guard_evidence.py
 M scripts/ci/test_run_static_guard_suite.py
 M scripts/db_guard/mediation_analysis/test_models.py
 M scripts/verify_allowlist_compliance.py
 M scripts/verify_cancellation_boundaries.py
 M scripts/verify_cloud_payload_boundaries.py
 M scripts/verify_event_writers.py
 M scripts/verify_privacy_boundaries.py
 M scripts/verify_worker_boundaries.py
?? .codex/agents/explorer-lite.toml
?? app/src/main/java/com/yourname/expensetracker/domain/backup/DatabaseStatsUnavailableException.kt
?? app/src/main/java/com/yourname/expensetracker/domain/groups/GroupSettlementBalancePolicy.kt
?? app/src/test/java/com/yourname/expensetracker/architecture/WorkerEntryPointProof.kt
?? app/src/test/java/com/yourname/expensetracker/architecture/WorkerEntryPointProofTest.kt
?? app/src/test/java/com/yourname/expensetracker/architecture/WorkerSourceMask.kt
?? app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudProviderTransportPayloadTest.kt
?? app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudQueryPreparedPayloadTest.kt
?? app/src/test/java/com/yourname/expensetracker/data/ai/provider/HybridRouterIntegrationTest.kt
?? app/src/test/java/com/yourname/expensetracker/data/backup/BackupVerifierRequiredSemanticQueryTest.kt
?? app/src/test/java/com/yourname/expensetracker/data/repository/ExpenseRepositoryMerchantKeyBackfillTest.kt
?? app/src/test/java/com/yourname/expensetracker/data/repository/SharedExpenseSettlementReadTest.kt
?? app/src/test/java/com/yourname/expensetracker/domain/ai/HybridRouterTest.kt
?? app/src/test/java/com/yourname/expensetracker/domain/groups/GroupSettlementBalancePolicyTest.kt
?? app/src/test/java/com/yourname/expensetracker/domain/groups/SharedExpenseManagerSettlementTest.kt
?? app/src/test/java/com/yourname/expensetracker/domain/privacy/PrivacyDeniedExceptionTest.kt
?? app/src/test/java/com/yourname/expensetracker/domain/receipt/OcrResultPartialTest.kt
?? app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementCompletionStatusTest.kt
?? app/src/test/java/com/yourname/expensetracker/ui/screens/debug/DebugViewModelDatabaseStatsTest.kt
?? "docs/analyses and debug master/CL-29-wave1-implementation-report.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/A2-denial-contract.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/A7-group-balances-self-review.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/A7-group-balances-spec.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-09-self-review.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-09-spec.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-15-self-review.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-15-spec.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-21-self-review.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-21-spec.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-22-self-review.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-22-spec.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-23-self-review.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-23-spec.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/remaining-wave2-plan.md"
?? docs/architecture/COVERAGE_MATRIX.md
?? scripts/ci/test_guard_ratchet_occurrences.py
?? scripts/ci/test_wave2_guard_wiring.py
?? scripts/guardrails/cloud_payload_proof.py
?? scripts/guardrails/test_cloud_payload_proof.py
?? scripts/test_cloud_payload_guard_integration.py
?? scripts/test_verify_allowlist_compliance_fail_closed.py
?? scripts/test_verify_cancellation_allowlist_scope.py
?? scripts/test_verify_worker_entrypoint_proof.py
?? workflows/active/wave2-remaining-files.md
?? workflows/active/wave2-remaining-handoff.md
?? workflows/active/wave2-remaining-validation.md
?? workflows/active/wave2-validation-repair-20260927.md
```

### 9.2 SHA-256 and size for every dirty path

| Path | Bytes | SHA-256 |
|---|---:|---|
| `.codex/agents/explorer-lite.toml` | 1676 | `04645298e64b0ec47241bf29c66e94c9e4c09d2360d402c2d2253b9ce20bdf9b` |
| `.codex/agents/orchestrator.toml` | 13776 | `ca156e28f1ca3c02559af0f62035131af2671afdd2b4f289ba41b5166200401c` |
| `.github/workflows/ci.yml` | 18844 | `4289990a1fd3d0d83ac35161bbb4b1286bab65eff66a682207ce5d9a1c63041c` |
| `app/src/androidTest/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorkerTest.kt` | 4773 | `87eeb2da61a737c89d49e182a9ad057bd6772ff10f54b56fba0ed1b3c3866faa` |
| `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt` | 12626 | `677eea069aac9deae39f9ec7518559700eb1d4c109026f869378dfbeca98fec5` |
| `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridCategorizationAssistService.kt` | 1688 | `de196d23e96940c45a33426af0e9bf8d20be26c958e5e7804afdad14fd27ae10` |
| `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridDashboardBriefingService.kt` | 1721 | `9d457b66b2cf6e3f6137268467668def3bb28fc15da2a989ee45a2f69195de28` |
| `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridQueryInterpretationService.kt` | 1713 | `7bcf5c4b2d7000da90ae85ed539bca43584d8ad4a85062006e792b2fd947952e` |
| `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridReceiptItemCategorizationService.kt` | 1584 | `d8f87c13eca0e1e939eb10d24d6155da771a190800b615d259e60c632085e925` |
| `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridReviewExplanationService.kt` | 1721 | `79c0d2840488811f2b64e9bfc323b7a76ba70b5c3b6595e82d9fbf67f16c3501` |
| `app/src/main/java/com/yourname/expensetracker/data/backup/BackupVerifier.kt` | 28220 | `574ee78413ab341f5217094221eb9228f94f18bfb0c6f54b30545acd04ab4af1` |
| `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt` | 114500 | `efb05f5a2ddd53d5eabcaad96cdcc566f1d6024d604364939cb6fbbc603d264e` |
| `app/src/main/java/com/yourname/expensetracker/data/database/dao/GroupSettlementDao.kt` | 801 | `10df4f2f9f8776b34e4f34aef8d40c776c763aa3d6dc706d054ad0a6f881c0ca` |
| `app/src/main/java/com/yourname/expensetracker/data/database/dao/ManualRecurringExpenseDao.kt` | 4741 | `86920ef4d61ee61865f2529471ee9cd1d7a2e7229e796374ba5eee4c597535dc` |
| `app/src/main/java/com/yourname/expensetracker/data/database/dao/ScannedReceiptDao.kt` | 13532 | `ecdf1d4bf81edf924b02fb3487942b52ccc965c9d72942bc16445fdfbc88c54a` |
| `app/src/main/java/com/yourname/expensetracker/data/database/dao/WarrantyReminderDeliveryDao.kt` | 7334 | `e47859334156928a7544295d7201e34d10023684ab89d31171692e01b39dde67` |
| `app/src/main/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorker.kt` | 7251 | `38cadb28cef425ffca84396131e9bab51940ff8dfe3c2dbd9e1e6793010c3206` |
| `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt` | 163899 | `a30fbced5ff907ad5306b529754ab588cf83d0bcbabe17028d6456f397512ce4` |
| `app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt` | 50960 | `061cf92c1e52a6457cb1e6ffee8c701fd9833a71915a42e479aa972f895e9a8f` |
| `app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepository.kt` | 2887 | `f6dd17806a51762a4442b7bbde49c17e78e27c9fc9341f0986a26dd16b0a95bf` |
| `app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImpl.kt` | 15402 | `348f00a9a6daf96500e22488eb86aa0642cf28a01753282b90bafc99e52cf2f3` |
| `app/src/main/java/com/yourname/expensetracker/data/repository/SharedExpenseDataPortAdapter.kt` | 10580 | `c500b754350ce30f6c3ca83b6e7bdde2454d2ae2c552f6cf306042f8cbb1422f` |
| `app/src/main/java/com/yourname/expensetracker/domain/ai/HybridRouter.kt` | 3135 | `80f2888ce0caa0eb3ef6bb69e3adfa62d37353c10d3c1d29c2e97281422cffda` |
| `app/src/main/java/com/yourname/expensetracker/domain/backup/DatabaseBackupRepository.kt` | 6045 | `c0a49edc0954059e1419f9705cce6cb4152a1013c011fc0bfc0f7839ff3b4de7` |
| `app/src/main/java/com/yourname/expensetracker/domain/backup/DatabaseStatsUnavailableException.kt` | 324 | `5ea763794c7c4f883a756485a2692d74dc166506e7a5b088153415518b4a47f6` |
| `app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilder.kt` | 12318 | `f3f4d84e945cb69509c69201bb5488797d95b2d039ce4cc04881e09cec6ba26b` |
| `app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt` | 40648 | `7eea7ff60a0ac4ca7ced4fae11e674aa018a3051a8e927a6ede06681d64267ad` |
| `app/src/main/java/com/yourname/expensetracker/domain/groups/GroupBalanceCalculator.kt` | 2912 | `fa0c06528f5ceed3beb25406be644944907a5fa594a9af5937514a94c2ef2668` |
| `app/src/main/java/com/yourname/expensetracker/domain/groups/GroupSettlementBalancePolicy.kt` | 2066 | `3786ffabc99ad2917df2c3cfa7558662161e673f13a70355748aed989435d076` |
| `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseManager.kt` | 22242 | `bb66d61e5a0debb2aa48e01daa7731ba30066ef3d404cc10654bf823deda155f` |
| `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpensePort.kt` | 3597 | `69857ac1381f35f5b5332982055b08772396b2b4d6e6a46d08fba00f4d62f03e` |
| `app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyDeniedException.kt` | 994 | `bbd647b32e7c0910c9314a3cfa562b45d964ba6d480e5103675bfff31a02cf92` |
| `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt` | 63146 | `868a8844cd591262b7892099f79b01c71448549d6704078073b732f0708a37e0` |
| `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptMatchLifecycleService.kt` | 12145 | `0891e516c824359ab8433b0319d88038f3a3f58fb50e65e18549b670410fe5fd` |
| `app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrService.kt` | 40407 | `4fbbcd0105bea427987fcb155dcf608d117b4e8f0eac3ec2f8f8a0cf8b96b1ea` |
| `app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt` | 31554 | `1751ea6a1c3cb245060275e696ffd5018f496704ac04361e081353fa30e440db` |
| `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | 72584 | `c595722741b9dccafd56711a1fa2fdfc812c76fb4e410a59574efa27f9738908` |
| `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt` | 20932 | `633ae470cd9c8583e2c643652003f0645ce3b622726f19bb192a9bad6d091827` |
| `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerLeaseRegistryImpl.kt` | 5176 | `668af7e5db8d808d55a0b8defadeaeb216c1e05ac16c50e0c1f1c4633c39d75f` |
| `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRunLogger.kt` | 16833 | `0fc22d75cb03d2003c9ee1e40fdb9a1b1fc9709ec15d47442bb4c77926db4331` |
| `app/src/main/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt` | 20070 | `b35dad56a65aa7ce93a176be951eec06b521f6f201999bd2e76e2af84b9429f8` |
| `app/src/main/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorker.kt` | 12327 | `74901ad4fa2d53b480c04fdfcaaf4c3dea6af816db1d72852e0c2db191349636` |
| `app/src/main/java/com/yourname/expensetracker/ui/components/dashboard/MoneyRadarWidget.kt` | 18615 | `667191bbe391822219e81caf3919b2b73b71ada954f36e32010b8dfbfcc7355d` |
| `app/src/main/java/com/yourname/expensetracker/ui/components/FinancialStressForecastCard.kt` | 15232 | `a544a2a887559e929e06a1b0db5544f905473493503f6584489c114e1ea8d154` |
| `app/src/main/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreScreen.kt` | 17481 | `0827b20e5ba180ba1dbc9d20479c8db6ea623a297243052141b4b418f657a085` |
| `app/src/main/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModel.kt` | 19645 | `46b5da5d1fce829ed01ccf773eeded32643bc7ac60cebc06671ebb8fdfa48f21` |
| `app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugScreen.kt` | 86352 | `a6ba4d04d24a8de7b510115565a14f4fad9262f8637096b57d4eccda14172f4d` |
| `app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugViewModel.kt` | 25237 | `e1d3854970d890d5dd04a59a033c0fe03555809e6362fd71078185e379967153` |
| `app/src/main/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModel.kt` | 17676 | `60c4e9e0b1ac7025e735b0929ff4c6e4cbea711ea9a3be13fca1d095012d835c` |
| `app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt` | 61047 | `ee23129fc281cd35053de27a393f6de2c0f6a0fb09d3b4f221a91ea2b982192e` |
| `app/src/main/java/com/yourname/expensetracker/util/CsvExpenseImporter.kt` | 17076 | `66dfde120a93658c00013085287489d6b57009b39077d8f46d5859154bf981ac` |
| `app/src/main/res/values/strings.xml` | 176851 | `92d631eb6d3c441617606a81e350a734ef0b4b02026627ab7725dfa09ff1f27f` |
| `app/src/test/java/com/yourname/expensetracker/architecture/WorkerEntryPointProof.kt` | 6796 | `e1c92676d6abacd5950e181b3b4c64a308b315c95961d64b0f1d46ad11607f57` |
| `app/src/test/java/com/yourname/expensetracker/architecture/WorkerEntryPointProofTest.kt` | 4614 | `018e7825717b0a0427101776671f5884dfe6aa6a3df41f7eb9d81c31e2f2a9ae` |
| `app/src/test/java/com/yourname/expensetracker/architecture/WorkerGuardArchitectureGuardTest.kt` | 5592 | `6fb1189b2b224c4c40613c969a16dc2404730a70cda87350de0185990ddaa682` |
| `app/src/test/java/com/yourname/expensetracker/architecture/WorkerSourceMask.kt` | 2999 | `62c416748b1c4a585301040b6aa86c7df71cdcdb3169061283fbf1d5250f112f` |
| `app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudProviderTransportPayloadTest.kt` | 13536 | `7648dd83f2987c77d443886199e3a71eb4da80d86c1751e022d2c5cad7d34489` |
| `app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationServiceTest.kt` | 25514 | `ddd92179c56fa76d794a3abb5d412b1d391e68ba9fe10379eef9780ddab93344` |
| `app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudQueryPreparedPayloadTest.kt` | 8828 | `6bcb9443add66d5c5f76624951e7ea2948b05078d9534b82788e45fac3ddcc55` |
| `app/src/test/java/com/yourname/expensetracker/data/ai/provider/HybridRouterIntegrationTest.kt` | 9570 | `472ab479b09db130f6dd980978b88c31880844b63f2bc7d1d5b8217a46945db2` |
| `app/src/test/java/com/yourname/expensetracker/data/backup/BackupVerifierRequiredSemanticQueryTest.kt` | 4618 | `4c221c253010b5d94342e5bb6030cf47950cd484dcf7012393b6e7c7704db5a5` |
| `app/src/test/java/com/yourname/expensetracker/data/database/dao/WarrantyReminderDeliveryDaoTest.kt` | 12203 | `fea4db43d1ae602b79d8063eb8eb67575d4251075e5c3c529c80ea26277ee3e6` |
| `app/src/test/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinatorTest.kt` | 63142 | `fd48bf4b5a64eaeaa15aed9026fa2d1b1b09436287575f5ca996777a88466cbf` |
| `app/src/test/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorkerTest.kt` | 9318 | `aaefe92879843d012f654834c18bc595181d55a23bff9f37cb97064305c4b9ad` |
| `app/src/test/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImplTest.kt` | 112936 | `8b1e487cdd17eaa41bf33b82a9087215cf61ac1ef06d4b808a48069f57e6e5f3` |
| `app/src/test/java/com/yourname/expensetracker/data/repository/ExpenseRepositoryMerchantKeyBackfillTest.kt` | 6666 | `48b88063a1ed0b169d00d3404ddf6fd4dcb5c9e005ef608d6821976cee94cedf` |
| `app/src/test/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImplTest.kt` | 27269 | `eaefe6c8fd7b566f0eb7eef93ea5f869f2d2d36c2d0044f0cad4af743496d242` |
| `app/src/test/java/com/yourname/expensetracker/data/repository/SharedExpenseSettlementReadTest.kt` | 2055 | `e970ae8df3ecbc02ed3dbd59cb2950322646784bb3c9e30bd8e7740105ce57d7` |
| `app/src/test/java/com/yourname/expensetracker/domain/ai/HybridRouterTest.kt` | 5132 | `24bd95c9c73007945e9755fd25c3cc5f182408de7b8e6c6db6c5afd276659468` |
| `app/src/test/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilderRestrictionTest.kt` | 16573 | `bced6298d3817071f1f017ec8c186723f789ed24dea0d8e0afedcfb309d3e76d` |
| `app/src/test/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngineTest.kt` | 36969 | `7d465f123f78782e76451baad6d2b211b6e750b4eef7721305c30b43ba7a710d` |
| `app/src/test/java/com/yourname/expensetracker/domain/groups/GroupSettlementBalancePolicyTest.kt` | 2275 | `a561d25956e952ca9685c243222f9c73fc4be19abf7ffbcc955d17ed9ffdeac6` |
| `app/src/test/java/com/yourname/expensetracker/domain/groups/SharedExpenseManagerSettlementTest.kt` | 3311 | `34b57c33f4a089be0068f388176b1700746959fbc01770d067596bd7703da120` |
| `app/src/test/java/com/yourname/expensetracker/domain/privacy/CloudProviderPreparedPayloadTest.kt` | 5849 | `c05b3680731c9d0f0bd781c53a46cc12f463c47c5fc285ac36f8c52f02a06140` |
| `app/src/test/java/com/yourname/expensetracker/domain/privacy/PrivacyDeniedExceptionTest.kt` | 1609 | `e974145fa9ed8024c7543eb057b1bb8d41f9cfe80241ea913fb83793dab0b119` |
| `app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementCompletionStatusTest.kt` | 1856 | `2c960ed0475ade974e587c5e0113dd81c9d0fccd95df3cf98ccbf54794211e36` |
| `app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptMatchLifecycleServiceTest.kt` | 17324 | `8b942ea217d30355685b9d80da70954f709bf5bf67867c1453f4ddfa6f61a24b` |
| `app/src/test/java/com/yourname/expensetracker/domain/receipt/OcrResultPartialTest.kt` | 1112 | `2f648954b8f66f7f78e0d2696be25511b40be0ca74ee3123d9528c24e25c9086` |
| `app/src/test/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinatorTest.kt` | 44136 | `c4d8a6788f134babf7cf492d721a0c5de69022c4e8b1c1a3068aa8f8c6c20255` |
| `app/src/test/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCasePaceWiringTest.kt` | 19056 | `e4f7ce9c2326af792da49d1c1077b02c067fb7eb0d216e21b354727af4587723` |
| `app/src/test/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCaseTest.kt` | 29461 | `c2a5fa1b5dbf9b6b101eb2df6ee2f35907f881715931e0f9e3b22a147306aa76` |
| `app/src/test/java/com/yourname/expensetracker/domain/workers/WorkerLeaseRegistryTest.kt` | 16184 | `e54bf2b971cc7ec03b3eebc47645b32034966f1cbfe1ec5be76e11536b29b1a3` |
| `app/src/test/java/com/yourname/expensetracker/domain/workers/WorkerRestoreRegressionTest.kt` | 27314 | `f5ba4227c4b0d4199601410a9d45b7cfbc75776d2565ccaa44bf2207f21f84a2` |
| `app/src/test/java/com/yourname/expensetracker/domain/workers/WorkerRunLoggerTest.kt` | 48708 | `3e0991afd01d7a31fb2523e3e351c3b018dc141009e515db1c1d99c91a822a0b` |
| `app/src/test/java/com/yourname/expensetracker/e2e/NotificationExpenseDashboardPipelineTest.kt` | 34257 | `1d6bd170f8f0fd34d113aae8e51cd7e9cbfdb3a5c0fb3327ec666b4aa1cd763b` |
| `app/src/test/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorkerTest.kt` | 38239 | `cf94f172c839f653869977d66b15c3f7a2478fc3a03a51f0e1292bc3bfcdde37` |
| `app/src/test/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorkerTest.kt` | 19352 | `bb78094c7efd3985c71b7343eccee493c3df66223f1de73d145802367d2daffe` |
| `app/src/test/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModelPrivacyDenialTest.kt` | 15403 | `9ae3fd99ecfcc8f2044136c1ec548a538643010a0301063c8efb3ed10c57284d` |
| `app/src/test/java/com/yourname/expensetracker/ui/screens/debug/DebugViewModelDatabaseStatsTest.kt` | 7837 | `d23d06f07f82258231ad5a94c13319ffe417eeb66663144178283ab113a07315` |
| `app/src/test/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModelTest.kt` | 34043 | `0dfe535da453a3e4498e791c8ade6a3329eaedaefa7bf155ea9d821704023c2c` |
| `app/src/test/java/com/yourname/expensetracker/ui/screens/receiptmatching/ReceiptMatchingViewModelTest.kt` | 10529 | `04ce37e2c5afcd81d0268d849b80da98869b1607bfd665245ea1079d95708342` |
| `app/src/test/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModelPrivacyDenialTest.kt` | 8699 | `9ba57cabd64a0594c794dff9228371864fa466cd649edac0d570673041d12276` |
| `app/src/test/java/com/yourname/expensetracker/util/CsvImportRfc4180Test.kt` | 11512 | `f9c74f75b6c4288cc7e9a5deb629ea3f206bce2e728d752e6ec5876dac404394` |
| `docs/analyses and debug master/campaign CA-2026-09-21/JOURNAL.md` | 56219 | `a3516b3f481f84fac8ffa58e3d9f0aea68a413bb4674ea6ea420e42e4d38a18c` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/A2-denial-contract.md` | 3365 | `3bf8dc1a98385ed81c22c3742a6eb74e481b1d7302d8fb551fafd20029a15b65` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/A7-group-balances-self-review.md` | 4041 | `aeae818741c12c2f85475d127edb79915cee838270e0f3c1b624627de8e030b0` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/A7-group-balances-spec.md` | 3306 | `b76ffa0b6e42e561d3d31bf7a4ae9b32ea74f94a5393141f774b5371237e76f6` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-05-review.md` | 29013 | `13b19217a6dd807df94371784a1c171f91d2d0dcffafcf093f2ccb0cfa834da5` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-09-self-review.md` | 4293 | `6d3f6e004a66aa1939ad5b15b5453ef72af369ab818125bc551afb0b243ce65a` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-09-spec.md` | 8022 | `4eca24002f9de4caf8cff6f3bba422d78b83623d93d656cb6b7d9cb66be1c5b0` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-15-self-review.md` | 7081 | `db08aaf10ec0da8d217b368287bfef037eb574b9d2374b2b61a843eff2e56ead` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-15-spec.md` | 4307 | `2af81380894bd1689d28c50bb9ed232caf118c6b2ed21a5f178050f94c2e5744` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-21-self-review.md` | 4341 | `6e7e7cf0d49888eab58e82e99dbdcf641308357a581fca62933563cae4d6c507` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-21-spec.md` | 4688 | `5fbaeb87e8dabbdb25920fc486e7d6cfa1d28f3a9018022d8166e45d39b53461` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-22-self-review.md` | 8495 | `5a204b954c492dcf89ada462d5858c7cfcbf7cdf1be0ca9c0da5c9f319a08e54` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-22-spec.md` | 5433 | `e53d2c44c560965154b611548718d5663729d2daf7ac599fad470ea92c862232` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-23-self-review.md` | 3623 | `b16072b7b9fd7392b0bea9706cd0852071f20c6178c60e25181b8b469fbafb90` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-23-spec.md` | 6653 | `731cbb28887cef53aa409728a09f3aa5a1357049b85a96bff4a3b65640c81975` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/remaining-wave2-plan.md` | 7340 | `05637fe213b06d86875ce37a0a3b8513a33c13e70f046d6d992a4179720a86ec` |
| `docs/analyses and debug master/CL-29-wave1-implementation-report.md` | 4994 | `e26b82b86bc4bd7f243a7d505cde197e42f67379f1ba521081e59f616b436d4a` |
| `docs/architecture/COVERAGE_MATRIX.md` | 32948 | `edc6edde036ff454bc78eb8d8675be1383cbe97b10cedac7f2da78bfb88a0c4d` |
| `docs/prompts/master-cross-codebase-audit-campaign-prompt-v3.md` | 23470 | `ae7685d47100bed31744f545415f10d8306886ab5c87b171c7c9b7f835788808` |
| `scripts/allowlists/ui_dao_allowlist.yml` | 398 | `fa74538a2792dc4a786243a33e3bccea1d77f423a82531f1fc47b9356cb88374` |
| `scripts/ci/capture_db_guard_evidence.py` | 190307 | `1ae59e95f10d6b97b9eb7a41798ca74823c5f7055e66ac78a09ca118a37ec9c4` |
| `scripts/ci/guard_ratchet.py` | 91792 | `b77e8cf946b7fea6df7f61307b0f441b12f5f429ff6d1083d73c98fffb06b26b` |
| `scripts/ci/run_static_guard_suite.py` | 39854 | `da3b46af40c93046d56850cf517d0a7122ced3a9aac8e91b029039c72b8f14ed` |
| `scripts/ci/test_capture_db_guard_evidence.py` | 237978 | `a76ddf913009e6143b847f6c4d13a7c2445080c5b0e4f190cd41dd176912dbdc` |
| `scripts/ci/test_guard_ratchet_occurrences.py` | 4582 | `b79bc4fffbe64b5ef525893165e6dd48d9b26f2520def93e563ecc8a36681806` |
| `scripts/ci/test_run_static_guard_suite.py` | 99780 | `5b13b4d6738aad52f50878a3ef789e30becec22aa801f5e05d1cd98e232cdd7c` |
| `scripts/ci/test_wave2_guard_wiring.py` | 1757 | `092a5b2fd523d534e76beca1319c3ab17853872bcc9a90844b91c6773424cc94` |
| `scripts/db_guard/mediation_analysis/test_models.py` | 4040 | `83c97e3e8f77b1c4fc347e7bff9ce2fe75d5382e322d0d56fcf03f2531de9db8` |
| `scripts/guardrails/cloud_payload_proof.py` | 31123 | `fffff1c31e316bef696cd5be24939ed7e80909ae5575a6806dbf97fd4983068b` |
| `scripts/guardrails/test_cloud_payload_proof.py` | 12780 | `93b5e73a2262d3a44b37a463c7f3ceb1a35958674173a3458315f8bb322f5d03` |
| `scripts/test_cloud_payload_guard_integration.py` | 4309 | `422727c0f8895292ce9aa3762b99ba3d616cabde14d20df1d0c164ac7b38a3b2` |
| `scripts/test_verify_allowlist_compliance_fail_closed.py` | 3535 | `6e1c1ae0b4f7a9ba9773492e50de4942653b25b3e3c338caaf755b42f129490c` |
| `scripts/test_verify_cancellation_allowlist_scope.py` | 4961 | `087504cf5f6bae9b4b306ead8eb8a59b55a5cf4bb326bc677c55a312a3b3f3a1` |
| `scripts/test_verify_worker_entrypoint_proof.py` | 4521 | `2c34a9d7a76ebc568faea57b4897f3f47dbbc17b46b5b71eab466c6d54025569` |
| `scripts/verify_allowlist_compliance.py` | 18069 | `c5d13efad18e82b233d8f46002b28b147e390edb6f9afb561d282b512d8946fb` |
| `scripts/verify_cancellation_boundaries.py` | 22776 | `185eaad2693ab2f889ab98b8638a67b378ffea9f5d81bcf8c95b80e58a47f1b1` |
| `scripts/verify_cloud_payload_boundaries.py` | 10878 | `49ab0d18f0203878fce46819162c7463f9b44b5820f6f07f60027f7712398426` |
| `scripts/verify_event_writers.py` | 11761 | `18fa0e0d90eef570dcb081926c0d45b1037d2f0e8fccfccf42c33d9673fcfa4a` |
| `scripts/verify_privacy_boundaries.py` | 25500 | `6f73f3bea2ce48f88832c47dbe0640095bbf2b46a73c768452ed8daa6c0309d1` |
| `scripts/verify_worker_boundaries.py` | 26027 | `f40564c1e3441677f5569f4c57f4fbd23e875278337e324d10424ab63976eb0e` |
| `workflows/active/wave2-remaining-files.md` | 11480 | `1bd01939a0e4e30fce314890678162daeb4fd96fdba06aa735cf1113a0fe0456` |
| `workflows/active/wave2-remaining-handoff.md` | 9633 | `a85fad904ccb98d4416ca59c6ea72a7bb3f14a58e0238f06cd33cc170fcdf83a` |
| `workflows/active/wave2-remaining-validation.md` | 8707 | `3fe0ec8e4e6e83a65dbbd5ce9d3596476ed0080d527259687646fa8b6fdcfc7c` |
| `workflows/active/wave2-validation-repair-20260927.md` | 11022 | `b95431469690db4523310e2383d37c89bdf21471d79e5e1126cec3a317362156` |

### 9.3 Reviewer safety / completion

Final pre-creation read-only check at 2026-09-27T13:02:31.5671375+03:00: branch/HEAD/index and all 137 pre-existing dirty-file bytes unchanged from S2; no active validation locks or RUNNING/STARTING records found in the main checkout or retained lane roots. The separately approved read-only process inspection found no running GradleWrapperMain or validation-runner process. Report path: `workflows/active/wave2-independent-deep-review-20260927-100231Z.md`.

The only authorized reviewer write is this newly named report under workflows/active. No existing report or campaign record is overwritten. Validation lock/process state is checked immediately before creation; the report must remain unsaved if a live run is detected. A final read-back and protected-file comparison must confirm that HEAD, index and the 137 pre-existing dirty file bytes are unchanged, with only the new report added. The final response records that verification outcome.

Overall technical verdict remains **FAIL** until the listed defects/acceptance gaps and required execution/guardian gates are resolved. No milestone is marked DONE/GREEN, no human waiver is expanded, and no baseline/allowlist/check is weakened.

