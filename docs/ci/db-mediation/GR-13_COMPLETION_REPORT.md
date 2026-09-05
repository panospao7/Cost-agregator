# GR-13 completion report (PR-GR-13, shadow-only)

Status: COMPLETE through plan Step 7. All mediation work remains
**SHADOW-ONLY**: no helper/worker proof is active in normal DB enforcement,
no policy/baseline/production-Kotlin change is included, and the normal DB
CLI result is unchanged.

```text
PR: GR-13
START SHA: 338eb3c5f937da3e596600e9285cc0e7b1856f35
END SHA: 338eb3c5f937da3e596600e9285cc0e7b1856f35 (shadow-only; analysis
  artifacts committed on top; no source/policy/baseline change)
ACTIVE POLICY SHA: 859643ec13e6c17f9b6ce8d054e708d06cbb135701a5721009a7e78216438c42
BASELINE SHA: 44421412c890b60d17db9a4157c497cf44ec836753d195eeb95783ff8ccebfec
SOURCE ROOT MANIFEST SHA: 02ec3e28c9451cecb31ca90dc8f665837b7857d979c20b3a400f898f8d1c2c95

HELPER MUTATIONS: 431
WORKER-MEDIATED MUTATIONS: 18
PROVEN HELPER: 20
PROVEN WORKER: 5
COUNTEREXAMPLES: 16 (11 unguarded path, 5 non-worker root)
UNPROVEN EXTERNAL: 64
UNPROVEN AMBIGUOUS: 5
UNPROVEN ASYNC/CALLBACK: 299
UNPROVEN RECURSIVE: 38
UNSUPPORTED: 2 (the two known uncorrelated rows recorded in
  GR-13_EVIDENCE_FREEZE.md — DataRetentionWorker.doWork|privacyAuditDao,
  WorkerRunLoggerImpl.start|backgroundJobRunDao)
INFRASTRUCTURE FAILURES: 0

EXACT CALL EDGES: 15147 (12660 exact_synchronous + 2487 exact_canonical_scope)
UNRESOLVED CALL EDGES: 38754 (all explicit closed-vocabulary uncertainty)
WORKER ROOTS DISCOVERED: 10
WORKER REGISTRY/SOURCE MISMATCHES: 3 (NotificationIntakeWorker,
  SnoozeReminderActionWorker, DismissReminderActionWorker — discovered in
  source, absent from WorkerRegistry.entries)

GR-14 REMEDIATION ROWS: 424 (one disposition per non-proven row,
  docs/ci/db-mediation/GR-13_UNPROVEN_WRITERS.yml)
SHADOW REPORT REPRODUCIBLE: yes
NORMAL DB CLI UNCHANGED: yes
BASELINE CHANGED: no
PRODUCTION KOTLIN CHANGED: no

NEXT PR: GR-14a…n
```

## Shadow report determinism evidence

Two consecutive runs of
`python scripts/ci/inspect_db_mediation_proof.py --output <path>` exit `2`
(unsupported source uncertainty — the two rows above) and are byte-identical:

```text
SHA-256: 984a19e0f03435b3c9ea6bb12d83126f9b359379f3b477f56003aeda79c73c0e
```

Exit mapping holds: 0 all proven / 1 unproven-or-counterexample only /
2 unsupported-or-infrastructure. The report carries `reportOnly: true` and
no raw source text (protocol rows PR-01..PR-08 are pinned by
`scripts/ci/test_inspect_db_mediation_proof.py`).

## Engine notes recorded for GR-14 planning

* **Expression bodies** (`= withContext(...) { ... }`) span their whole
  lambda; brace-less type declarations (`object X : Y`, brace-less primary
  constructors) become header-only owners. Both fixes were driven by
  production-tree defects and are pinned by
  `scripts/db_guard/mediation_analysis/test_parser_spans.py`.
* **Recursion tier (38 rows):** the exact-edge SCC set (21 keys, e.g.
  `AdvancedAnalyticsEngine.getPeriodRange` ↔ `getPreviousPeriodRange`) is
  real source mutual recursion. Pre-fix runs measured it over an incomplete
  graph (51,328 edges) — the complete graph (53,901 edges) connects those
  callers to 38 mutation subjects, which classify fail-closed as
  `UNPROVEN_RECURSION` per the plan (SCCs are never silently iterated).
* **Multi-site rows:** one mutation key observed at several call sites
  becomes one subject per site; the row combines them with worst-state
  semantics (`worst_subject_proof`): proven < unproven < counterexample <
  unsupported < infrastructure. A definite counterexample is never
  re-labeled as uncertainty.

## Validation sweep at completion

| Check | Result |
|---|---|
| Fixture corpus (`fixture_runner.py`) | 44/44 |
| Parser-span battery (`test_parser_spans.py`) | 8/8 |
| Engine model/manifest batteries | PASS |
| CLI protocol battery (`test_inspect_db_mediation_proof.py`) | 19/19 |
| Structural-model CLI battery (unchanged) | 12/12 |
| Normal DB CLI (`verify_db_access_boundaries.py`) | exit 0, 0 findings, 20 advisory `DB_SIGNATURE_UNRESOLVED` |
| `verify_guard_docs_truth.py` | PASS, 0 violations |
| Gradle | NOT RUN (per ground rule) |
