# Wave 2 — adjudication of vr-20260928-075653 and fixture-input repair

Date: 2026-09-28. Overall verdict: **FAIL / Wave-2 closure remains pending**. Fixture repair: **IMPLEMENTED_UNVERIFIED**. Independent strict/guardian approval: **PENDING**.

One agent; no delegation. No validation was launched. This report corrects the supplied interpretation against persisted evidence; it does not overwrite the campaign journal or earlier reports.

## 1. Verified execution snapshot

- Run: `vr-20260928-075653-8d98ca3b`, profile `static-guards`, terminal FAIL / exit 1 / `E_COMMAND_FAILED`; completion marker present.
- Started: `2026-09-28T07:56:53.5512474Z`; finished: `2026-09-28T08:23:10.5566452Z`; elapsed 1,573 seconds.
- Command recorded by the runner: `python.exe scripts/ci/run_static_guard_suite.py --output-dir build/ci/static-guards`.
- Branch at the associated handoff: `bug-fixes`; HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`.
- Start/end fingerprint, equal and matching the previous final handoff: `0320a5326bfeb55c20d90b2b9edcc49f1de99d7295b55237fe4bb9a373e933b4`.
- Suite: 18/25 passed, 7 blocking violations, **0 infrastructure errors**.
- Pytest: **54 failed / 4,663 passed / 28 skipped**. The failures comprise three production-pipeline tests and 51 File-discovery fixture cases.

| Preserved artifact | SHA-256 |
|---|---|
| `build/validation-runs/vr-20260928-075653-8d98ca3b/result.json` | `9068b477cd3a6f5e6a8f90d865f2115f3aa4361678ad108a319ba42ef756d3cb` |
| `build/ci/static-guards/summary.json` | `94c9805b7bcaee7fff32a0f1c1d81c34299875feff5491dc13a141b416fa59b5` |
| `build/ci/static-guards/guard_tests.log` | `ebdc206aad7e4a42296b470f8652d12ce16cd0dc6c3a0da162920126f0ed8efa` |
| `build/ci/static-guards/db_access.log` | `26301941eb2dfbaa304d8db5518e8ca4c84c69c6b655c8ecba6c59af14de4c6d` |
| `build/ci/static-guards/known_good_state.log` | `e54025c6a21e1ca1a3a40cfcefc6bb6502f0648c111f1ef2c6151acef44964ae` |
| `build/guard-debug/known-good-state/active-db-gate.findings.json` | `3ab1d9d274317f9391f1736cb92a593480f172b9a9b7ad3803e3569bb7960656` |

Shared build artifacts can be overwritten by later validation. Associate these observations with this run, fingerprint and hashes, not HEAD alone.

## 2. Authorized cleanup grant — verified narrowly

The structural manifest passed 65 = 60 expected + 5 fixtures. All nine new cleanup-policy cases have explicit PASSED records in `guard_tests.log`. The production report has zero blocking diagnostics, so the two previously reported DatabaseBackupRepositoryImpl discovery blockers are absent. This verifies the scoped authorized grant at the tested snapshot, not the entire Wave-2 implementation.

No additional structural permission is added in this follow-up. The cleanup grant, caller grant, all policy pins and the nine policy tests are unchanged.

## 3. Correction: the production pipeline is trusted and reports three mutations

All three production-pipeline failure summaries at `guard_tests.log:4757–4773` explicitly say `trusted=true`, `exitCode=1`, `findingCount=3`, `blockingDiagnosticCount=0`, and `advisoryDiagnosticCount=21`. They do NOT say trusted=false or exit 2.

| Actual finding | Current source coordinate | Operation |
|---|---|---|
| `BankSyncStartupRecovery.recoverStaleRuns(Long)` | `app/src/main/java/com/yourname/expensetracker/domain/bank/BankSyncStartupRecovery.kt:64` | `operationRunDao.finalizeIfRunning` |
| Same recovery owner | Same file, line 76 | `bankStatementImportRunDao.markStaleFailed` |
| `AppStartupCoordinator.resumeSingleAssetTask` | `app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt:665` | `dao.update` on `ScannedReceiptDao` |

These are concrete `DB_UNAUTHORIZED_MUTATION` findings against the empty DB ratchet, not advisory-trust failures. Source inspection confirms the direct calls. Both source files are tracked and unchanged relative to current HEAD; the findings are newly visible after discovery becomes trusted, not newly introduced uncommitted calls. That does not waive them.

Execution paths inspected:
- Startup scheduling checks maintenance, then `recoverStaleBankRuns` calls `BankSyncStartupRecovery.recoverStaleRuns`; the recovery class checks `DatabaseWriteBarrier` before processing its two bookkeeping families.
- Restore asset recovery opens a fresh database in `resumePendingAssetTasks`, passes its receipt DAO to `resumeSingleAssetTask`, and the helper performs its update inside `restoreInternalWriteScope.run("startupAssetResume.updateImagePath")`.

No exact ownership entries for these three identities were found in the active ownership policy. The correct next step is source-backed legal-owner/barrier adjudication: repair an illegal write path if necessary, or separately authorize only genuinely correct owner identities. The previous human approval covered the cleanup structural tuple, not these three mutation grants. Do not baseline them, add permissions automatically, or change the three pipeline tests to accept findings. Apply `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-06/FG-07 and FG-23.

The existing advisory contract is already explicit: `scanner.py:3830–3845` computes trust from blocking diagnostics only; `docs/ci/DB_ACCESS_V2_RATCHET_DEBT.md:21–26` records the GR-07 Option-B advisory treatment. No trust-policy negotiation or relaxation is needed to explain this run.

## 4. File-discovery module — declared scope, genuine fixture defect

The module was not undeclared. `workflows/active/wave2-db-file-discovery-20260928-handoff.md:7–10` lists it and its 55 intended cases; the subsequent cleanup-policy handoff explicitly carries that prior repair forward at line 28. The cleanup handoff listed its own incremental six-file edit scope, not a replacement cumulative batch manifest.

The persisted run executed **55 cases: 4 passed, 51 failed**. The four passing cases explicitly supply structural policy lists. The failing default path passed `structural=()` from `_scan`; `_load_policy` at `scripts/db_guard/scanner.py:1796–1809` accepts None or list/document shapes and correctly rejects tuples. The resulting `DB_POLICY_SOURCE_EVIDENCE_INVALID` made positive fixtures untrusted and added an extra blocking diagnostic to negative fixtures. This was the authored fixture error, not evidence that File discovery needs a different trust policy.

### Implemented repair

- Changed only `_scan`'s default from `structural=()` to `structural=None` at `scripts/test_wave2_db_file_discovery.py:25`.
- Preserved every existing assertion and all 55 existing cases.
- Added seven input-shape cases at lines 335–359: three supported empty-policy forms must still expose an unauthorized mutation; four malformed forms, including the original tuple mistake, must remain untrusted with withheld findings and the exact invalid-policy diagnostic.
- Expected module inventory after this change: 62 intended cases. This is an authored count, not collection or passing evidence.

The scanner, trust computation, loaders, production defaults, ownership policy, structural policy, baselines, allowlists, RawQuery pins and test-selection logic were not changed. Removing the fixture setup error can expose further substantive failures; no claim is made that all 51 now pass without execution.

## 5. Correction: freshness is SKIP; the advisory count pin is separate drift

The actual scorecard row is `test_result_freshness result=SKIP`, observed `stamp=missing`. Its terminal summary is `pass=5 fail=1 infra=0 skip=1 exit=1`. The failing row is `active_db_gate`, which observes three findings and 21 advisories versus its zero-findings / 20-advisory pin. No stamp was created and no freshness evidence is claimed.

Even after the three mutations are properly dispositioned, the scorecard's documented 20-advisory pin versus the observed 21 needs evidence-backed adjudication. It must not be silently changed to match a number. The referenced frozen `build/guard-debug/gr09/current-findings.json` was not present, so this review does not invent the identity of the added advisory from counts alone. The current complete 21-diagnostic inventory is preserved in the structured report listed above.

An explicitly optional missing-stamp row is not a passing test or a substitute for device/unit evidence. Missing/skipped/unknown/infrastructure guard outcomes remain subject to FG-03; this run's guard results are concrete violations, not infrastructure failures.

## 6. Changed files, snapshot and preservation

Files touched in this follow-up:
- `scripts/test_wave2_db_file_discovery.py` — fixture default and seven additional cases.
- This new handoff. No earlier report or journal was modified.

- Intake: `f059d5e41709b8939361e3b8e38ac527c2f3f894d9ad987076594b22303690ba`; 114 tracked dirty and 69 untracked paths; index empty. Only the campaign JOURNAL differed from the previous tested handoff at intake.
- Post-test-edit/pre-report capture: `2026-09-28T11:37:27.0348160+03:00`.
- Post-test-edit/pre-report fingerprint: `26f38cee85e4560dcb946f16baae2193f25173617bf5a29845910c97dbd61f6c`.
- Branch/HEAD unchanged: `bug-fixes` / `a48076322e57f3d312f34cf6a71139efc4fcc48b`.
- Test module before: `f38c596b549993a4510436a7a484bfce099f87ae402ec2f92f445512d96fce52`; after: `2029c2ffad9eced5aad1ec6adea2a7e3eb65c26fced5fd5befd7da8ba8f9da5b`.
- Exact read-back succeeded. Reversing only this edit in memory recovered the original file SHA-256. All 182 other intake dirty/untracked paths remained byte-identical; nothing was removed.
- Repeated checks found no active runner lock, STARTING/RUNNING result or validation process before writes. No Git staging, commits, history operations, branch switching or worktree manipulation occurred.

The new report itself changes the dirty-tree fingerprint. The final response records the post-report fingerprint rather than putting a self-referential hash in this file.

## 7. Human validation and remaining closure gates

Validation of the new fixture repair: **NOT RUN**. No build, pytest, guard, import/AST probe or validation-runner execution was performed by this agent. File/source inspection is not test execution.

After review and quiescence, freeze the complete tree. The human can verify through the full serialized profile:

```powershell
pwsh scripts/vrun.ps1 -Worktree . -Profile static-guards -Raw -MaxTotalMinutes 130
if ($LASTEXITCODE -ne 0) { throw "Static guards did not PASS; preserve and inspect the run artifacts." }
```

`-Raw` retains the wrapper's completed-run deduplication. The previous request recorded `AllowOverlap=true`, but source inspection shows the runner rejects a live lock independently at `validation-runner.ps1:462`; that option alone is not evidence of concurrent validation. Do not bypass serialization. Poll a RUNNING result rather than launch another run.

Keep recursive guard-test selection intact; `guard_tests` is a suite segment, not a standalone registered guard. Require actual execution of all 62 File-discovery cases, the nine verified policy cases, existing scanner/semantic coverage and the three production-pipeline tests. Preserve complete findings and diagnostics, terminal artifacts and equal worktree fingerprints.

A rerun can verify the fixture repair but should not be represented as a closure run while the three mutation findings, advisory-count drift and other standing guard violations remain unresolved. For efficiency, adjudicate those real issues before another full-suite closure attempt.

Independent strict/guardian reviews, required PDF/image/MerchantKeyBackfill device evidence, and the previously recorded functional/evidence gaps remain open. In particular this turn does not close receipt/PDF partial-result propagation, WorkerRunLogger evidence adjudication, or missing snapshot-linked CL-09 records. Commit remains a separate human decision. Author read-back is not independent approval.
