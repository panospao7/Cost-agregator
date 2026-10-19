# A1 — Slow-test audit (read-only; nothing run, nothing edited)

Auditor: scout (Sonnet), 2026-10-16. Persisted by orchestrator.

Basis: persisted artifacts only. Kotlin seconds are CPU-sum across parallel forks (`app/build.gradle.kts:221`, forks = cores/2), so wall-clock gain is smaller except for single long tests. Python per-test durations don't exist (guard_tests.log has no `--durations`) → Python figures are code-reading estimates (EST). Confirm with `pytest scripts --durations=60` via validation-runner.

## 1. Ranked findings
| # | Item | Measured | Root cause (evidence) | Fix | Est. saved | Risk |
|---|---|---|---|---|---|---|
| 1 | guard_tests: ~35 `_run_cli` subprocess runs of `migrate_db_policy_signatures.py` over the REAL tree | part of 736 s | `scripts/test_migrate_db_policy_signatures.py:1305-1315` `_run_cli`, called at 1326, 1382, 1383, 1389, 1404, 1474, 1640, 1712, 1748, 1953, 2445, 2559-2713, 2797, 3292, 3433-3520. Fresh process each time, no cross-process `run_cache` (`scripts/run_cache.py:33-38`). One `--verify` ≈ 5 s | Session-scope fixtures for repeated identical runs (one `--check --report`, one `--write-candidate`); keep the 2 runs in `test_report_has_deterministic_ordering` | EST 60-150 s | Low if byte-identical argv; don't merge tests checking exit codes of different argv |
| 2 | guard_tests duplicates the db_access step | EST ~100-130 s | `scripts/test_verify_db_access_v2.py:1395-1416` `_real_tree_scan` runs full DB gate on real repo ("costs minutes", 1333-1336) — the `db_access` step (137 s) already ran it | Reuse db_access findings report when effective-inputs hash matches, else run once per suite | up to ~120 s | Medium (tests assert report schema/activation stats) |
| 3 | known_good_state (152 s) re-runs db_access | 152 s | `scripts/ci/verify_known_good_state.py:465-477` row 1 re-runs `verify_db_access_boundaries.py`; row 2 (528-541) full-tree `--inventory-only`; rows 3/5 (597-599, 665-667) `migrate --check`/`--verify` again (also run by db_artifact_sync). Docstring (101-109) "~250 s warm" stale | Consume the db_access report when hashes match; keep the "21 × DB_SIGNATURE_UNRESOLVED" assertion | EST 80-110 s | Medium |
| 4 | `WriteBarrierArchitectureGuardTest` (single test `all DAO write callers are barrier-protected or exempt`) | 97.6 s | `architecture/WriteBarrierArchitectureGuardTest.kt:460-470`: for each non-exempt file × each (DAO, method) pair × up to 5 regexes (`buildCallerRegexes` 312-329) full-text `containsMatchIn`. `allKotlinFiles()` re-called at 479/578/618; `buildBarrierProtectedClasses()` reads every file twice (539, 549) | Literal pre-filter `if (!sanitizedContent.contains(".$method")) continue` (every regex requires that literal → identical results); cache file list/texts `by lazy` | ~90-95 s (also the longest single test → direct wall gain) | None |
| 5a | `P7BugFixesTest.backup_bundle_closes_stream_on_exception` | 21.0 s | `data/backup/P7BugFixesTest.kt:1384-1392` `repeat(50)` extract on garbled file (1357-1362) → PBKDF2 600k each (`BackupEncryptionService.kt:51`, `deriveKey` 164-169) | Use input that fails before KDF (`decryptStream` throws "Truncated input" at :127/:132) or reduce to ~5; confirm same `finally` in `CostbackupBundle.extract` (:406) | ~19 s | Low-med |
| 5b | `BackupEncryptionServiceTest` 7.7 s, `CostbackupBundleLimitsTest` 6.7 s, `BackupVerifierSavingsLedgerTest` 6.2 s, `BackupRestoreContractTest` 2.1 s, `BackupRestoreIntegrityE2ETest` 2.5 s | 25 s | Real 600k KDF per encrypt/decrypt | `internal` iteration-count ctor seam (default `ITERATION_COUNT`), low count in tests except the 600k pin in `BackupEncryptionServiceTest`. Main-code change; watch Hilt `@Inject constructor()` | ~15-18 s | **Medium — crypto seam; reviewer-strict** |
| 6a | Cloud AI retry: `CloudReceiptAssistServiceTest` 13.6 s (one 11.0 s), `CloudQueryInterpretationServiceTest` 8.3 s, `CloudDashboardBriefingServiceTest` 5.1 s, `CloudCategorizationAssistServiceTest` 1.9 s | 29 s | `CloudRetryPolicy.kt:38-43` 250/500/1000 ms + jitter; services `withContext(Dispatchers.IO){ delay(...) }` (e.g. `CloudReceiptAssistService.kt:169,249,341,402`); tests `runBlocking`; loop `CloudReceiptAssistServiceTest.kt:134-159` 20 combos | Test-only delay-scale hook on `CloudRetryPolicy` (or injected dispatcher). Retry counts still asserted; magnitudes pinned by `CloudRetryPolicyTest` | ~22-25 s | Low |
| 6b | `GeocodingRetryHttpSemanticsTest` (+ `OverpassNearbyServiceTest`) | 9.5 s | `NominatimGeocodingService.kt:278-312` `delay(max(..., 1100))`; tests `runBlocking`; no `Dispatchers.IO` in path | `runTest` (virtual time); optionally assert `currentTime` (stronger proof) | ~9-10 s | None |
| 7 | Robolectric cold start / mixed SDKs | EST 60-150 s of 477 s | 133 Robolectric files; `robolectric.properties` sdk=28 + HiltTestApplication; 22 files pin other SDKs (26/33/34) → extra sandbox per fork. Trivial-logic classes paying ~2 s first-case startup: `ComputeMoneyRadarUseCaseTest`, `TaxGoldenScenarioTest`, `BudgetForecastingEngineDiagnosticsTest`, `FinancialStressForecastEngineTest`, `OnDeviceReceiptAssistServiceTest`, `ReceiptScanViewModelStressTest` | Standardise on sdk=28 where no API-specific behaviour; drop Robolectric where unnecessary (`DefaultAiEnvironmentMonitorTest`, `BudgetForecastingEngineDiagnosticsTest`; `isReturnDefaultValues=true` at `app/build.gradle.kts:203`) | 20-60 s | Medium — targeted run per change |
| 8 | `BitmapConcurrencyTest` | 5.3 s | Tests `kotlinx.coroutines.Mutex` itself, imports no production class (header :16, :94 "Simulates ReceiptOcrService.bitmapMutex"); real delays | `runTest` or delete (library behaviour; deletion needs user approval) | ~5 s | none |
| 9 | `ServiceDiagnosticsTest` | 2.8 s | `Thread.sleep(1)` in real-thread race test (:174) | Leave | — | — |

Other top-40 classes (`DatabaseBackupRepositoryImplTest` 22.7 s, `AppStartupCoordinatorRecoveryTest` 8.3 s, `GroupTransactionCoordinatorTest` 6.6 s, `SavingsSweepAcceptanceCoordinatorTest`, `Retention*`, `NotificationIntakeEnqueueFailureRoomTest`, `ReviewQueueRepositoryNestedRollbackTest`, `RecurringRuleLifecycleCoordinatorTest`): Robolectric + in-memory Room per test; no single dominating cause. Per-class Room sharing = needs-confirm (lifecycle/money proof area).

## 2. Kotlin architecture scanners vs Python guards
- `WriteBarrierArchitectureGuardTest` vs Python `db_access` barrier proof (`scripts/db_guard/structural_analysis/barrier_proof.py`, `barrier_markers.py`, `direct_barrier_bridge.py`): overlapping, Python is stronger, strict subsumption **not proven** — do not delete on this basis.
- `RecurringArchitectureGuardTest`, `SourceScanningArchitectureGuardTest`, `MoneyBoundaryGuardTest`: cheap; overlap with `verify_*.py` unverified.
- **Time bomb:** `WriteBarrierArchitectureGuardTest.kt:143-158` has 16 `owner=UNASSIGNED ... expiry=2026-10-31` exemptions; hygiene test at :666 goes red on 2026-11-01.

## 3. Python guard_tests structure
- `scripts/ci/run_static_guard_suite.py:178-180`: `python -m pytest scripts -v --tb=short`, single serial process; no xdist, no pytest.ini/conftest.
- 25 legs run serially (`run_static_guard_suite.py:928`). 1070 s = guard_tests 736.6 + known_good 152.1 + db_access 137.2 + import_lifecycle 15.3 + rest < 5 s each.
- Running known_good/db_access concurrently with guard_tests would bound wall ≈ 740 s, but shared `build/` and read-only `config/guards` assertions (`test_migrate_db_policy_signatures.py:1399-1412, 3286-3306`) → concurrent-write safety not audited.
- xdist requires a new package (environment change).
- Cost is almost entirely subprocess runs of real CLIs; in-process scans already share `run_cache`. Duplicate real-tree executions per suite run: `--verify` ×3 (+3 drift runs), DB gate ×3, `--inventory-only` ×1, import_lifecycle ×2 (cheap).
- Highest leverage: disk-persisted, content-hash-keyed gate result reused across processes → removes findings 2 & 3. Guard infrastructure → reviewer-strict.

## 4. Lists
### Quick wins (no weaker proof)
1. WriteBarrier pre-filter + cached reads — ~90-95 s.
2. Geocoding/Overpass `runTest` — ~9-10 s.
3. CloudRetryPolicy test delay hook — ~22-25 s.
4. P7 leak loop on truncated input — ~19 s.
5. `test_migrate_db_policy_signatures.py` session fixtures — EST 60-150 s.
6. `BitmapConcurrencyTest` virtual time — ~5 s.

### Needs care
- 5b PBKDF2 seam (crypto, reviewer-strict).
- 2/3 cross-process reuse of db_access results (guard infra; key on effective-inputs hash + commit).
- 7 SDK/Robolectric changes (targeted runs; keep Room/restore/privacy classes out of the same slice).
- Concurrent guard legs.
- Room-per-class sharing.

## 5. Tests cited as evidence elsewhere
No `scripts/` or `config/` file references these Kotlin classes; FINAL_CI_GUARD_ACCEPTANCE_GATE.md has no hits.
| Class | Cited in |
|---|---|
| `CloudDashboardBriefingServiceTest` | `TEST_FAILURE_LEDGER.md:58,71` |
| `DatabaseBackupRepositoryImplTest` | `docs/time/time-boundary-guard.md:250`, `docs/development/TIME_SEMANTICS.md:291`, release-1.0 verification ledger |
| `P7BugFixesTest`, `AppStartupCoordinatorRecoveryTest`, `BackupRestoreContractTest` | `docs/releases/release-1.0-assurance/release-1.0-verification-ledger.md:1138-1266` |
| `SavingsSweepAcceptanceCoordinatorTest`, `BackupVerifierSavingsLedgerTest`, `ReceiptMatchingNoDoubleCountGoldenTest` | `docs/prompts/release-1.0-assurance/evidence-map.md:17-21,36`; ledger |
| `GroupTransactionCoordinatorTest` | ledger; `docs/ci/db-mediation/GR-14u36.yml:18` |
| `NotificationIntakeEnqueueFailureRoomTest`, `ReceiptDuplicateFingerprintCollisionTest`, `ReceiptScanViewModelStressTest`, `NotificationCaptureServiceDeferredPolicyTest` | ledger |
| `RetentionTargetPurgeTest` | `docs/ci/db-mediation/GR-14u51b.yml:38` ("the ONLY test constructing ...") |
| `CloudCategorizationAssistServiceTest` | `docs/ci/db-mediation/GR-14u57a.yml:126` |
| `FileWorkerTerminalDiagnosticSinkTest` | `docs/architecture/SENSITIVE_DIAGNOSTICS_POLICY.md:111` |
| `DailyBriefingWorkerTest` | `docs/workers/WORKER_BASELINE_INVENTORY.md:12` |
| `WriteBarrierArchitectureGuardTest` | `docs/ci/CI_GUARDRAILS_BASELINE.md:220`, `docs/architecture/ARCHITECTURE.md:112` |
| `RecurringArchitectureGuardTest` | `docs/architecture/LEGAL_PATHS.md:367`, `DEPENDENCY_MAP.md:484`, `ARCHITECTURE.md:73,222` |
| `SourceScanningArchitectureGuardTest` | `SENSITIVE_DIAGNOSTICS_POLICY.md:100` |

Python: `scripts/test_verify_db_access_v2.py` and `scripts/test_migrate_db_policy_signatures.py` are registered as `tests`/`testManifest` for `db_access` and `db_artifact_sync` in `scripts/ci/guard_registry.py` (413, 449, 601, 652) — markers/skips/renames must go through the registry.

## 6. Caveats
No timing measured by the auditor; items 1-3 and 7 least certain. Bodies of `migrate --generate`, `test_db_guard_scanner_d4.py`, `test_verify_db_access_boundaries.py` not read.
