# Post-merge work list (scout 2026-10-19 on HEAD 945afffc; orchestrator-condensed)

## OBSOLETE
- B1R-5: fixed by the release (BackupEncryptionService :158-160).
- B1-1/MISC-02: WriteBarrier expiry gone.
- B1-2: HANG review_by 2026-12-01.
- B4-P6, most of B4-K6, X7-X9.
- B7-6: no YAML library and no temporary entries.

## Already done this session (the scout mislisted these as open)
- money.json empty pin (user-approved).

## READY (test/docs only)
- Ignore chain: B3b-6 → B3-1b → B3b-R2 (G-IGNORE baseline 3→0, a tightening that mirrors the approved B3b-R).
- B3-9: orphan .kts files + GuardSeededViolationTest + test-shards.json guards.* entries.
- B5 leftovers: B5-4 Statistics, B5-7 navigation, B5-9 reminder workers/receivers, B5-14 RecurringOccurrence, B5-18 ReceiptScan.
- B6 slices:
  - B6-3 AdvancedAnalytics;
  - B6-8c, B6-9, B6-11 and the G-B6e rows (privacy);
  - B6-13, B6-14, B6-15 DailyBriefing, B6-15b(c)=X2 and X3 (workers);
  - B6-17, B6-20 with X1, B6-22 (touches known-hanging-tests.json HANG-003) and B6-24 (lifecycle/backup);
  - B6-18 leftovers;
  - G-B6j small adds.
- B7-K comment/KDoc subset.
- X4.

## NEEDS APPROVAL
- B4-K5: PBKDF2 iteration seam in BackupEncryptionService; production crypto. Gates B6-23.
- B1R-4: cancellation.json G-CANCEL-01 removal; baseline tightening.
- B1-4: CE rethrow in MerchantKeyBackfillWorker and WarrantyExpirationWorker; production. Also SourceScanning allowlists expiring 2026-12-31.
- B1-5: CancellationSafety guard real clock + 3 runCatching fixes; production + guard.
- B1-3: provenance allowlist entry.
- B4-P5: known_good_state reuse of db_access results; guard infra; plan approval item 2 never given.
- B7-2, B7-4: guard test changes; B7-4 deletes tests.
- B7-5: needs a YAML dependency.
- MoneyContractTest delete: needs the verifier extended.
- B9 orphan cleanups: worker_root_dispositions.yml, GR-13 yml, CallGraphBuilder, V3 paths.
- X5/X6: verify_db_access_boundaries.py string rewording, including the user-facing :650 stderr.
- 52 production follow-ups.

## Re-validate on the merged tree
- Full unit-tests + static-guards (running).
- Device set.
- pytest db_guard and seed_rows.
- Registered guards: db_access, money, guard_docs_truth, ignored_test_budget, cancellation.
