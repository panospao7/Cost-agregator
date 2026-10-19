# B7/B8/B9 READY/WAIT classification (scout 2026-10-19; orchestrator decisions)

All named files exist. Release overlap: none in B8/B9 write sets; B7 touches release-changed guard files/config.

## Classification
- B7-1 READY (MoneyContractTest, RecurringDeactivateContractTest, PrivacyStorageContractTest T3 cases; CI_GUARDRAILS_BASELINE.md:215-232) — LifecycleBarrierContractTest stays until after merge. Needs the parity table (each removed rule → Python fixture).
- B7-2 WAIT (B1R; db_ownership_policy.yml release-changed; SourceScanning scans release-changed prod).
- B7-3 READY (port fixtures to scripts/test_verify_money_boundaries.py, then delete guard/MoneyBoundaryGuardTest.kt); rerun registered-guard money after merge.
- B7-4 WAIT (DbGuardPolicyFixtureTest.kt + db_ownership_policy.yml release-changed).
- B7-5 BLOCKED (B1R-4; cancellation.json release-changed; YAML-lib precondition unverified).
- B7-6 WAIT (U-004 exemptions resolved by release; ARCHITECTURE.md release-changed).
- B7-K WAIT (DirectEventDaoInsertGuardTest release-changed; MIT-043 ratchet pins release-changed coordinator). Possible early subset (M1, N10 comment in ScannerFailClosedSelfTest, SourceTextSanitizer KDoc) — not split now.
- B8-1, B8-4 (JVM part), B8-5, B8-6a, B8-6b, B8-7 READY (JVM DAO moves; revalidate after merge because AppDatabase/GatedOpenHelper/DatabaseSchemaPolicy changed). B8-2, B8-3, B8-4 device part need a device/emulator (connected tests) — check availability before starting.
- B9-1..B9-7 READY (Python tooling retirement). B9 caveat: release-changed scripts/verify_db_access_boundaries.py names scripts/ci/promote_db_policy_v2.py in remediation strings (:11, :421, :632, :650, :3553, :3682) → post-merge follow-up to reword (do not edit before merge). B9-7: config/guards/worker_root_dispositions.yml would become orphaned → NOT in plan; decision: leave the file untouched and report to the user (no config change without approval).

## Approval status
B7–B9 are part of the plan the user approved ("I approve them all") plus standing approval; scope stays exactly as planned. Unplanned config/guard decisions (e.g. worker_root_dispositions.yml) are not taken — reported instead.

## Order (READY-NOW)
1. G-B9 (Python): B9-1, B9-5, B9-6, B9-7 → pytest-subset scripts/db_guard (+ scripts/test_db_guard_policy_v2.py for B9-1); B9-3 → pytest-subset scripts/test_migrate_db_policy_seed_rows.py; B9-2, B9-4 → registered-guard guard_docs_truth; registered-guard db_access after B9-7; one static-guards at the end of the group. Review: reviewer-strict on the whole diff.
2. G-B7A: B7-3 then B7-1 → targeted-unit-test contracts.* + guard.*, pytest-subset scripts/test_verify_money_boundaries.py, registered-guard money. reviewer-strict.
3. G-B8J: B8-1, B8-5, B8-6a, B8-6b, B8-7, B8-4 JVM part → targeted-unit-test data.database.dao.* (+ MerchantKeyBackfillWorkerTest); reviewer-strict + room-migration-guardian (B8-4/B8-5 T3).
4. G-B8D (device only if available).
After merge: revalidate B7-3 money leg, B7-1 contracts.*, B9-7 db_access leg, B8-J; then WAIT slices.
