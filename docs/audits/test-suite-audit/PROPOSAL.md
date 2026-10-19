# Test suite audit — consolidated proposal (HUMAN GATE)

Date: 2026-10-16. Sources: `baseline-metrics.md`, `A1-slow.md` … `A7-python-guards.md` (read-only audits; nothing edited or run).
All figures are auditor estimates from source reading + persisted timings; runtime claims for Python must be confirmed by one measured run.

## 1. Headline numbers
| Suite | Today | Removable / mergeable (est.) | To revive / strengthen |
|---|---|---|---|
| Kotlin JVM unit | 8,089 tests, 228k LOC | **~1,750 (~22%)**, ~25k LOC | ~55 dead-but-valuable `@Ignore`d tests to revive; ~30 vacuous tests to rewrite |
| Python guard self-tests | 5,153 items | ~150 now; up to ~530 with tooling-retirement decisions | — |
| androidTest | 391 | ~8 duplicates; **~248 DAO tests portable to JVM**; 9 permanently-skipped migration tests | — |

| Wall clock (median) | Today | Realistic target after B4 (estimate) |
|---|---|---|
| static-guards | 17.6 min | ~8–11 min (guard_tests 736 s → ~300–450 s; known_good_state 152 s → ~50 s) |
| Kotlin unit summed CPU | 477 s | ~300 s (WriteBarrier −95 s, retries/KDF/loops −70 s, Robolectric −20–60 s) |
| connected-tests | 16.3 min | maybe −¼ to −⅓ if DAO tests move to JVM (unmeasured) |
| Compile | test LOC ≈ main LOC | −~25k LOC test source (~11%) |

**The important finding is not the count.** About 30 tests look like proof of key rules but test nothing: they check copies of production logic written inside the test, test-local fakes, or `if (x != null)` assertions. These are privacy fail-closed, atomicity, terminal-once diagnostics and worker concurrency. Some rules currently have **no real proof** (see B2).

## 2. Batches (in recommended order)
Tier per batch follows CLAUDE.md routing. Each slice: implementer/test-author → targeted-unit-test via validation-runner → review.

| # | Batch | What | Est. effect | Tier / review |
|---|---|---|---|---|
| **B1** | Calendar fixes (owner decisions) | `WriteBarrierArchitectureGuardTest` 16 exemptions expire 2026-10-31 (red 2026-11-01); `DirectEventDaoInsertGuardTest` 7 LEGACY entries red 2026-11-21; ~37 more expire 2026-12-31; `CancellationSafetyArchitectureGuardTest` uses a fixed clock so its expiries never fire (13 already past); HANG-003 review by 2026-10-31 | prevents red CI in 2 weeks | Guard/allowlist change → **needs your decision** |
| **B2** | Honesty pass: replace vacuous proofs with real ones | Real tests for: privacy settings corruption → fail-closed (`PrivacySettingsRepositoryImpl`); notification capture PRIV-441-05/06/07 via real `NotificationCaptureGate`; group transaction rollback with injected failure; production `SafeSinkOperationRunHandle` terminal-once; real concurrency for worker lease/logger mutex; Recommendation cancellation-rethrow; Windows-effective sink failure; then delete the vacuous versions. Also silent-pass scanners: 16 `if (!exists()) return` in `RecurringArchitectureGuardTest`, `PrivacyGuardTest`, `IncludeDepositsForBehaviorCleanupTest`, contracts `continue`s | adds ~15 real tests, removes ~45 vacuous | T3 → reviewer-strict (privacy, workers, lifecycle) |
| **B3** | Dead-code deletions (zero proof loss) | 7 data `@Ignore` StressTest classes; 10 `@Ignore` ViewModel stress classes (trivial halves); 3 empty `@Ignore` shells; `SecureKeyStorageTest`; formula-copy tests (`DashboardProjectionSafetyTest`, `SplitCalculationPrecisionTest`, most of `TaxCalculationTest`); test-local fake/simulator tests; `BitmapConcurrencyTest`; constant/getter/`assertNotNull`-only tests; `GuardSeededViolationTest`; `HiltGraphSmokeTest`; pipeline "integration" tests of utilities; orphan helpers (`HiltTestUtils.kt`, dead `TestUtils` helpers); 12 wall-clock perf asserts. Update `TEST_IGNORE_CLASSIFICATION.md`, lower G-IGNORE-01 baseline (a ceiling), update doc citations | ~650 tests | T1–T2, cheap review; **test deletion → needs your approval** |
| **B3b** | Revive valuable ignored tests | ~55 tests that are the ONLY coverage of: home widget reorder/visibility, planned expenses, review AI-assist, add-expense validation, transactions paging, composite geocoding routing (8) — into active, honestly named classes (time-boxed; record as DELETE if hang unfixable) | +55 live tests | T2 |
| **B4** | Speed fixes, same proof | WriteBarrier literal pre-filter + cached reads (−95 s); Cloud retry delay hook + Geocoding `runTest` (−35 s); P7 50× KDF loop (−19 s); Robolectric removal where pure JVM; Python: session-scoped fixtures in `test_migrate_db_policy_signatures.py`, isolate/batch `test_validation_runner_protocol.py` (one PowerShell per case), reuse db_access result in `known_good_state` + `test_verify_db_access_v2` when input hash matches. PBKDF2 iteration seam (main-code crypto change) separately | static-guards −6–9 min; unit CPU −150 s | Kotlin T1; guard infra + crypto seam → reviewer-strict |
| **B5** | Table-driven merges, low-risk areas | parsers (TransferDirection, ReceiptParser, Generic/GreekBank), categorization, navigation routes, util stress → tables, merchant-key family, notification filters/resolvers, importers, AI service error contract, DAO CRUD duplicates, Python seed-rows (−140) | ~600 tests | T1–T2 |
| **B6** | Table-driven merges, money/time/privacy/worker areas | TimePeriodUtils family (302 → ~230), Totals/SpendingPace/BudgetCalculator clusters, `SynthesisEngineStressTest`, raw-storage privacy matrix (~120 → 55), worker guard/logger matrices (~95 → 45, every outcome a row), coordinator matrices, backup splits; add exact `assertMoneyEquals` (current helper tolerates 1 cent) | ~450 tests | T3 → reviewer-strict |
| **B7** | Kotlin ↔ Python guard de-duplication | Retire Kotlin scanners proven weaker than Python (Engine5 wall-clock, MoneyContract, RecurringDeactivateContract, MoneyBoundaryGuardTest runner); shrink `DbGuardPolicyFixtureTest`; unify CancellationSafety allowlist with Python YAML; WriteBarrier exemptions vs `db_ownership_policy.yml` | ~70 tests, fewer hand-synced allowlists | **Guard change → needs your approval**, reviewer-strict |
| **B8** | androidTest | Delete ExchangeRate/Recommendation device duplicates; move ~248 portable DAO tests to JVM (keep small device smoke set); DaoStress timing asserts → correctness; decide 9 always-skipped migration tests | connected-tests shorter | Room-adjacent → T3 review; migration tests untouched |
| **B9** | Python unwired tooling | Retire (if you agree): policy v3 loader (15), triage tool (105), promote/v2-evidence CLIs (39), subsumption proof (6), mediation prover stack (~212) | up to ~380 tests | **needs your decision** |

## 3. Decisions needed from you
1. **Approve the batch plan and order** (B3/B5/B6/B7 delete tests → explicit approval required). Any batch you want dropped or reordered?
2. **B1 time bombs:** renew the expiring exemptions with owners/new dates, or fix the underlying code before 2026-11-01 / 2026-11-21? And `CancellationSafetyArchitectureGuardTest`: switch to the real clock (it goes red immediately on 13 entries) or remove the dates?
3. **9 always-skipped migration tests** (`DatabaseMigrationTest`, need schemas v1–v10 that don't exist): delete as dead, or keep and report as skipped?
4. **Orphaned `scripts/guards/*.kts`** (referenced by nothing): delete with `GuardSeededViolationTest`?
5. **B9 tooling:** which of v3 loader / triage tool / promotion CLIs / subsumption proof / mediation prover remain supported?
6. **B8:** OK to move DAO tests from androidTest to JVM (Robolectric), keeping a small device smoke set?
7. **Measurement run first?** One validation-runner run of `pytest scripts --durations=60` would confirm the Python runtime estimates before B4 (may need a runner profile; static-guards profile alone does not print durations).

## 4. Guardrails for execution
- One batch = one or more slices with disjoint write sets; no test deleted that is the sole proof of a money/privacy/lifecycle/backup/worker/migration rule (sole-proof lists in each A-report §4).
- Pinned citations (denylist, registry, capture manifest, ledgers, policy docs) updated in the same slice.
- Validation only via validation-runner: targeted-unit-test per slice; `static-guards` after B4/B7; `migration-tests`/`connected-tests` after B8; full `unit-tests` once at the end.
- Max 2 review + 2 debug iterations per slice; then PARTIAL/BLOCKED with evidence. No commit/push unless you ask.
