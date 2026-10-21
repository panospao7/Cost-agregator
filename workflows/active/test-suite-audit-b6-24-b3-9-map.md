# B6-24 + B3-9 map + decisions (scout 2026-10-20 on HEAD 945afffc)

T = app/src/test/java/com/yourname/expensetracker.

## B6-24 — DO (T2; reviewer-strict because backup/restore territory)
- Move T/scenarios/BackupRestoreContractTest.kt (23) → T/data/backup/BackupRestoreContractTest.kt (package data.backup; drop now-same-package imports). Orchestrator deletes the old file after count check. No config/shard FQN references (evidence-map.md:20 uses a name glob → safe).
- Replace `BackupVerifier.allTableNames().size == 59` (:482-491) — NOT with a looser bound. Load the exported schema for APP_DATABASE_SCHEMA_VERSION (app/schemas on the test resources path, app/build.gradle.kts:196-199; same approach as DatabaseMigrationProofTest) and parse `database.entities[].tableName`. Assert: (1) every verifier table exists in the schema (exact subset; failure lists unknown names); (2) the set of schema tables NOT tracked by the verifier equals an explicit frozen set in the test (exact equality; failure lists newly untracked / newly tracked names with "classify the table in BackupVerifier.TABLE_TIERS or add it here with a reason"); (3) keep the size pin as `assertEquals(TABLE_TIERS size derived from the explicit expectation)` only if still meaningful. Verify whether FTS/virtual/shadow tables appear in the schema and handle them explicitly.
- Fix stale KDoc "58-table" in BackupVerifier.kt:10 only if trivially wrong → that is production KDoc; leave it and report instead (no production edits in this slice).
- Report to user: the list of schema tables BackupVerifier does not tier (≈13), as information (backup copies the whole DB; verifier tiers drive verification only — confirm in report).
- Validation: targeted com.yourname.expensetracker.data.backup.BackupRestoreContractTest.

## B3-9 — DO (T2 cheap; approved plan row :239)
- Delete scripts/guards/check_direct_time_calls.kts, check_lifecycle_bypasses.kts, check_raw_money_aggregates.kts and T/guards/GuardSeededViolationTest.kt (4 tests; its only consumer; advisory scripts not wired into CI; canonical guards G-TIME-01 verify_time_boundaries.py and db_access remain). Orchestrator deletes files.
- config/validation/test-shards.json: remove the `com.yourname.expensetracker.guards.*` filter at :11 (architecture-contracts) and :103 (trusted) — the package contains only GuardSeededViolationTest. Keep `guard.*` (singular). Check how the runner/tests validate test-shards.json (scripts tests may pin the filter list) and update any pin consistently.
- Docs (re-locate by text; lines shifted +1 after merge): CI_GUARDRAILS_BASELINE.md:231; ARCHITECTURE.md :102, :110, :292-293, :336, :1821; CODEBASE_SEGMENTS.md :248, :738-740, :747; CODEBASE_INVENTORY.md:23; DEPENDENCY_MAP.md:1331. Prose retirement note, no status-claim wording; historical ledgers/plans untouched.
- Validation: targeted com.yourname.expensetracker.architecture.*, registered-guard guard_docs_truth, and the static-guards suite at the final gate (test-shards config tests).
