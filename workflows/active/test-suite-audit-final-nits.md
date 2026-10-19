# Final docs/nit pass (scout 2026-10-19; orchestrator decisions under standing approval)

None of the actionable files below is release-changed. Older nit lists:
- B5 nits 1-18: done.
- B6 nits 1-69: done, except (15) and (42), which are skipped (X1).
- B6 nits 76-78: done.

## K — Kotlin test edits (writer K-1; one compile)
- K1: ReceiptMatchingWorkerTest :75. Comment → "a plain CancellationException is rethrown ahead of the Exception catch" (WorkerExecutionGuard.kt:405-410).
- K2: ReceiptMatchingWorkerTest :450. Change to `catch (e: CancellationException)` and add `assertEquals("job cancelled", e.message)`; the throw is at :442.
- K3: TestUtils.kt :213-216. KDoc NaN wording (Double.compare treats NaN as equal to itself). KDoc only.
- K4: RecommendationDaoTest :127. `archiveTime = FIXED_NOW + 5_000`, which makes the dismissedAt/updatedAt asserts non-vacuous. Verify the helper default first.
- K5: SavingsGoalDaoTest :27-38. KDoc wording (references the deleted androidTest; the "Reviewer finding" note is stale).
- K6 (nit 70): EmailReceiptIngestionServiceDiagnosticsPrivacyTest :49-54. Comment: >512-char messages are fully [REDACTED], and free text outside the patterns survives. Verify the rule in EventMetadataSanitizer.
- K7 (nit 71): SharedExpenseGroupScenarioTest :214. Message → "seeded myShareAmount 30.0 round-trips".
- K8 (nit 73): RecurringNoDoubleCountScenarioTest. Remove the unused `seeder` field, its init (:41/:51) and the import (:9). **No rename or fold** (that would churn FQNs and docs).
- K9 (nit 75): GoldenTestBase.kt :46. Comment 2026-04-15 → 2026-04-16.
- K10 (optional): skipped.

## P — Python test edits (writer P-1)
- P1: seed_rows docstring :12 and section :1023-1027. Say "retired promotion gate (test-local copy)".
- P2: seed_rows :1033. Reword the type comment as prose (it names unimported typing types).
- P3: seed_rows :1020. Delete the redundant `!= _PAIR_MISMATCH_MESSAGE` assert; also delete `_PAIR_MISMATCH_MESSAGE` if it is then unused.
- P4: test_generate_db_baseline_v2.py :44/:60. Remove the unused GUARD_DB_ACCESS import and `_GUARD`.
- P5: same file. Make the section-header numbering consistent.
- P6: same file :768/:779/:796/:808. Round-trip docstrings → "parse the frozen on-disk fixture".
- P7 (optional): skipped.

## D — docs (writer D-1)
- D1: docs/ci/CI_GUARDRAILS_BASELINE.md :233. Drop "`guard/`". The doc is indexed as HISTORICAL_RECORD.
- D2: docs/ci/DB_TRIAGE_PROCEDURE.md Step 1 (:42-61). Add a retirement note and wrap the deleted-builder command block in a HISTORICAL_RECORD marker, mirroring DB_ACCESS_V2_TRIAGE_SCHEMA.md :116-123. Indexed as CURRENT_OPERATIONS.
- D3: docs/ci/DB_ACCESS_V2_TRIAGE_SCHEMA.md :4/:11/:19. Put the builder mentions in the past tense: "produced by the retired build_db_finding_triage.py". Keep the literal YAML example as a historical shape. Indexed as CURRENT_ARCHITECTURE.
- D4: docs/ci/GUARDRAIL_STATE_REPORT.md :1220-1222. Remove worker_recognition.py from the note, since there is no such row.
- D5 (nit 74): RP-04-recurring-subscriptions.md :150-151. The stale test citations become "(consolidated into table tests by the test-suite audit)" with the current names.
- D8: verify the 01-INVENTORY :340 CorruptionTest row and the new-class rows. Fix only if still stale and the doc is not generated.
- D6/D7 (generated test-batch/coverage docs; no generator exists): skipped as frozen artifacts and reported.

## X — deferred (post-merge or needs approval)
- X1: count_rows_* move (DatabaseBackupRepositoryImplTest is release-changed).
- X2: WorkerExecutionGuardTest CE case (release-changed).
- X3: WORKER_BASELINE_INVENTORY rows (release-changed).
- X4: GUARD_DOCUMENT_INDEX LF endings (harmless).
- X5/X6: verify_db_access_boundaries.py strings (incl. the :650 stderr), orphan cleanup, and cancellation.json B1R-4 (production/guard/config).
- X7: release ledger rename note (release-owned).
- X8/X9: moot or cosmetic.

## Validation (one batch after K-1, P-1 and D-1)
1. targeted-unit-test over these classes, checking per-class counts:
   - ReceiptMatchingWorkerTest (26)
   - RecommendationDaoTest (15)
   - SavingsGoalDaoTest (16)
   - EmailReceiptIngestionServiceDiagnosticsPrivacyTest
   - SharedExpenseGroupScenarioTest (4)
   - RecurringNoDoubleCountScenarioTest (1)
   - one GoldenTestBase subclass (compile coverage for K9)
2. pytest-subset on seed_rows (268) and on test_generate_db_baseline_v2.py (61).
3. registered-guard guard_docs_truth.
