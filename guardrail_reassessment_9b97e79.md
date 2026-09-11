# Guardrail reassessment — `9b97e79`

## Verdict

**RED / blocked, with substantial real progress.** This is a source-level review; I did **not** execute Gradle, pytest, or an emulator.

The last 20 commits form a coherent DB-guard migration sequence: structured findings/ratchet, callable signatures, Room inventory, declaration scanning, D4 reporting, then triage tooling. The newest commit itself only patches a test-helper argument-binding issue. ([github.com](https://github.com/panospao7/Cost-agregator/compare/9b97e7979130de605d164386bbf719cf20579475~20...9b97e7979130de605d164386bbf719cf20579475))

## Most important correction to the earlier review

Your current DB guard is not merely “finding a large backlog.” At this SHA, it is structurally unable to produce a trusted enforcement result:

1. `db_access` is configured as protocol-v2. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/scripts/ci/guard_registry.py))  
2. The active ownership policy has no `signature` entries; the CLI explicitly treats *any* missing signature as an infrastructure failure before scanning. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/config/guards/db_ownership_policy.yml))  
3. The active baseline is still legacy v1 text fingerprints, while ratchet v2 requires a v2 baseline envelope and exits `2` on schema mismatch. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/config/baselines/db_access.json))  

So the likely sequence is:

```text
today: DB_POLICY_SOURCE_EVIDENCE_INVALID / exit 2
after policy migration: RATCHET_BASELINE_SCHEMA_MISMATCH / exit 2
after both: real v2 findings / exit 1, ready for triage
```

That is a correct fail-closed stop, not evidence of hundreds of newly introduced writes.

## New issues I would add

### P0-A — Candidate policy cannot yet be promoted safely

The checked-in signature candidate has only nine entries and omits `signature.kind`. But the D4 matcher requires `receiver`, `parameters`, **and `kind`** to match. It also compares only the owner’s simple class name, not the required fully qualified owner identity. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/config/guards/db_ownership_policy.signatures.candidate.yml))

**Fix:** settle one policy-v2 identity:

```text
path + ownerFqcn + callable kind + method + extension receiver + parameter types
```

Make the candidate generator emit exactly that schema. Do not copy the current candidate into the active policy.

### P0-B — Legacy overload-union validation is still authoritative

The old source-evidence path groups entries by `(path, class, method)` and explicitly validates the union of all overload mutation pairs. That is the exact unsafe behavior P0-5 was meant to remove. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/scripts/verify_db_access_boundaries.py))

**Fix:** extract policy schema/evidence from `verify_db_access_boundaries.py`, make D4 use it, and delete method-union authorization before D5 triage.

### P0-C — Barrier proof is still lexical

D4 accepts any earlier `writeBarrier.checkWritesAllowed` or `runWrite` text in the declaration before the call. It does not prove receiver type, unconditional execution, dominance, or lambda containment. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/scripts/db_guard/scanner.py))

Therefore conditional, caught, deferred, or unrelated barriers remain bypasses. P0-7 is still unstarted in the security sense.

### P0-D — Worker mediation is still documentation

The active policy still uses `barrier_required: false` plus `barrier_via: WorkerExecutionGuard`; current validation only checks metadata consistency, not call-graph reachability. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/config/guards/db_ownership_policy.yml))

P0-6 remains unproven.

### P1 — Control plane remains duplicated

`guard_registry.py` claims the suite derives from it, but `run_static_guard_suite.py` has its own hard-coded manifest. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/scripts/ci/guard_registry.py))

Worse, Gradle wires **two** overlapping raw-text lifecycle scanners into `check`, each with filename/class-substring allowlists. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/app/build.gradle.kts))

Retire both only after proving canonical DB scanner parity; retain a compatibility task name as an alias if needed.

### P1 — Room discovery is promising, but not yet production-proven

The inventory is annotation/SQL-driven and fail-closed, which is the right direction. But it only approves `app/src/main/java`; a future `src/main/kotlin` or feature module becomes a blind spot. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/scripts/db_guard/room_inventory.py))

Add an explicit production-source-root policy/meta-guard now.

### Time remains semantically RED

`TimePeriodUtils` claims no internal clock access, while repeatedly calling `Calendar.getInstance()`, uses ambient `ZoneId.systemDefault()`, mixes modern and legacy calendar algorithms, and claims broad/full-`Long` compatibility. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/app/src/main/java/com/yourname/expensetracker/domain/util/TimePeriodUtils.kt))

Your supplied evidence of eight time failures must remain a release blocker. Do T0 historical classification and an approved time-domain ADR before further implementation.

### Migration proof is not a PR gate

The workflow is triggered on PRs overall, but `Migration Proof` is conditionally skipped for PR events; it runs only on main/master pushes or manual dispatch. It uses JVM-style `--tests` filtering and has no XML result-contract verifier. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/.github/workflows/ci.yml))

## Progress estimate

| Measure | Estimate |
|---|---:|
| Useful implementation artefacts written | **40–45%** |
| Guardrail program that is proven/trustworthy end-to-end | **25–30%** |
| Work remaining to claim “properly strengthened and complete” | **70–75%** |

More specifically:

- **P0-1..P0-5:** ~55% coded, but only ~15–20% accepted; D5 cannot begin safely yet.
- **P0-6/P0-7:** ~5–10% complete; policy labels exist, proof engines do not.
- **P0-8/P0-9:** substantial migration code exists, but semantic completion is near 0 until failures and ADR are resolved.
- **P0-10:** partial workflow exists, but blocking PR proof is not implemented.

## First debugging session: do this before coding

1. Freeze a clean checkout at `9b97e79`; record SHA and `git status`.
2. Run only DB foundation tests.
3. Run inventory-only mode; fix every diagnostic before policy enforcement.
4. Run normal DB CLI and capture the expected exit-2 signature-policy failure.
5. Do **not** regenerate a baseline.
6. After exact-policy activation, expect the separate v2-baseline schema failure.
7. Only after both are fixed, produce the first trustworthy v2 finding inventory.

```bash
mkdir -p build/guard-debug

python -m pytest \
  scripts/ci/test_guard_findings.py \
  scripts/ci/test_guard_ratchet.py \
  scripts/ci/test_guard_ratchet_v2.py \
  scripts/test_kotlin_callable_parser.py \
  scripts/test_migrate_db_policy_signatures.py \
  scripts/test_db_guard_room_inventory.py \
  scripts/test_db_guard_sql_classifier.py \
  scripts/test_db_guard_declaration_scanner.py \
  scripts/test_db_guard_scanner_d4.py \
  scripts/test_verify_db_access_v2.py \
  scripts/test_verify_db_access_boundaries.py \
  -v --tb=short

python scripts/verify_db_access_boundaries.py \
  --inventory-only \
  --findings-output build/guard-debug/db-inventory.json \
  --dump-room-mutators build/guard-debug/room-mutators.json
```

## Small targeted PR sequence

### DB foundation — serial; do these first

1. **PR-00: Freeze reproducible DB evidence**  
   Logs, SHA ledger, no policy/baseline changes.

2. **PR-01: Define authoritative DB policy v2 model**  
   Extract shared policy loader/schema/evidence from legacy CLI. Require `ownerFqcn`, `kind`, receiver, ordered parameter types.

3. **PR-02: Repair signature candidate tooling and test seam**  
   Make `policy` keyword-only in `_inventory`; remove silent positional coercion; add a negative misuse test. Make migration output include `kind` and FQCN.

4. **PR-03: Activate exact callable evidence**  
   Migrate *all* active ownership entries to v2; replace overload-union grouping; add same-name/different-signature integration fixture.

5. **PR-04: Source-root contract + real Room inventory proof**  
   Declare all production roots; run D2/D3 over the real app; fix every diagnostic, not by baselining it.

6. **PR-05: D4 enforcement parity**  
   Compare legacy and v2 findings; classify differences. V2 becomes authoritative, legacy becomes report-only.

7. **PR-06a…n: D5 triage batches**  
   At most 25 findings/PR. Legal writer → exact policy; real violation → code fix; parser uncertainty → exit 2 and analyzer fix.

8. **PR-07: Reviewed v2 baseline activation**  
   Generate candidate only from historically proven, expiring temporary debt. Ideally empty. Prove CLI, ratchet, static suite, and Gradle agree.

9. **PR-08: Canonicalize control plane**  
   Generate static suite from registry; remove/alias both legacy lifecycle scanners; use `Path.startsWith`, not case-insensitive string prefixes.

### DB barrier and mediation — only after PR-07

10. **PR-09: PSI structural model only**  
    Add `guard-analysis` module, parser fixture corpus, exact receiver resolution. `settings.gradle.kts` currently contains only `:app`, so this module has not started. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/settings.gradle.kts))

11. **PR-10: Structural barrier dominance**  
    Implement `direct_check` and `run_write`; reject conditional/caught/deferred/local-function barriers; remove lexical authorization.

12. **PR-11: Exact call graph and worker roots**  
    Build direct/virtual/interface dispatch index. Report ambiguity as exit 2; do not authorize yet.

13. **PR-12: Refactor workers/helpers for provability**  
    Tighten visibility/capability ownership; eliminate escaping callbacks and broad interfaces.

14. **PR-13: Enforce mediation and delete `barrier_via`**  
    Add runtime contract tests plus adversarial call-site, helper-escape, false-request, and second-implementation tests.

### Time — parallel after DB PR-03

15. **PR-14: T0 historical classification only**  
    Classify all eight failures with parent/head results; no expectation changes.

16. **PR-15: Approved time ADR**  
    Decide supported financial range, explicit zone contract, invalid-import behavior, legacy compatibility policy, and half-open intervals.

17. **PR-16: Explicit-zone modern time API**  
    Introduce deterministic calculators and migrate callers; no ambient timezone in core APIs.

18. **PR-17: Isolate/remove legacy calendar seam and resolve all eight failures**  
    Separate legacy adapter if genuinely required; otherwise remove it. Add zone/DST/range matrix.

### Migration proof — can run in parallel

19. **PR-18: Harden instrumented migration suite**  
    Canonical versions, no `assumeTrue`, required named scenarios, schema parity, representative data survival.

20. **PR-19: XML verifier + PR-required Migration Proof**  
    Exact instrumentation runner class argument, no zero-test pass, artifacts/SHA verification, PR trigger, branch-protection documentation.

21. **PR-20: Independent adversarial/final exact-SHA acceptance**  
    Inject bypasses, run full suite twice, emulator migration proof, release artifact verification, branch-protection evidence.

## Bottom line

You are **not lost**: the work has a clear center of gravity.

Your immediate next milestone is:

```text
authoritative exact DB policy
→ production inventory with zero diagnostics
→ D5 triage
→ v2 baseline / green truthful DB gate
```

Only then start P0-7, then P0-6. Time and migration are valid parallel tracks, but neither should be declared complete from static checks or plans alone.