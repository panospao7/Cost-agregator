# Necessary follow-up PR roadmap after `8b45879`

## First: what is already behind you

Your recent history shows GR-07 activation, the GR-08a…p2 DB-finding batches, and GR-09’s v2 baseline/control-plane migration have landed. The current registry points `db_access` at `db_access_v2.json`, not the old baseline. Do **not** reopen those PRs as though they were unfinished. ([github.com](https://github.com/panospao7/Cost-agregator/commits/8b45879e445e8750fcb9210bcf44b9a26e6468dd))

However, GR-09 proof predates the August 29, 2026 time refactor at `8b45879`, which changed 40 files. Historical green evidence is not proof for the current SHA. ([github.com](https://github.com/panospao7/Cost-agregator/commit/8b45879e445e8750fcb9210bcf44b9a26e6468dd))

Also avoid reusing old PR numbers from older documents: the repository contains earlier plans whose “PR-GR-01…07” names refer to a different starting SHA and older design. Use the new names below to avoid confusion. ([github.com](https://github.com/panospao7/Cost-agregator/blob/8b45879e445e8750fcb9210bcf44b9a26e6468dd/GUARDRAIL_P0_P1_IMPLEMENTATION_PLAN.md))

---

# Phase 0 — mandatory evidence before changing anything

## 1. `PR-GATE-00R` — Re-establish exact-SHA guard evidence

**Must be first. No policy, source, allowlist, or baseline changes.**

### Why it must happen
You are currently lost because several documents and test ledgers are historical. The acceptance contract explicitly rejects evidence from another commit and requires exact target/base SHA evidence, clean checkout, deterministic outputs, artifacts, and two full candidate runs. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

### What it decides
It produces the authoritative route:

- DB CLI `0`, trusted → no DB triage PR is needed.
- DB CLI `1`, trusted → create `GR-08q` batches.
- DB CLI `2` → repair scanner/control-plane infrastructure first; no policy additions.
- Time guard `1` → do `TIME-01`.
- Static suite or Gradle `2` → do control-plane/test infrastructure repair, not app-policy changes.

### Why it cannot be skipped
Otherwise you risk:
- treating an old GR-09 result as current;
- editing a baseline to hide a newly introduced finding;
- fixing Kotlin when the real problem is a timeout/interpreter/report contract;
- fixing a guard based on stale documentation.

**Exit condition:** two semantically identical DB reports and static-suite summaries for the exact SHA, plus a tracked ledger of every non-zero result.

---

# Phase 1 — resolve actual current failures

## 2. `PR-TIME-01` — Validate and stabilize the August 29 time migration

**Required if `G-TIME-01`, compilation, or affected tests fail; strongly recommended even if the scanner is green.**

### Why it must follow GATE-00R
The latest commit replaced 78 direct clock reads across 40 files with `TimeProvider`, `java.time` derivation, and `Calendar.Builder` patterns. That is large behavioral change, not merely a mechanical guard cleanup. ([github.com](https://github.com/panospao7/Cost-agregator/commit/8b45879e445e8750fcb9210bcf44b9a26e6468dd))

### What it must establish
- The strict time guard has zero unapproved direct-clock findings.
- Production compilation succeeds.
- Changed domain logic has deterministic tests using controlled time.
- Calendar/date conversions preserve intended timezone, DST, month-end, leap-year, and epoch behavior.
- Duration measurement is consciously separated from wall-clock “now” where elapsed-time correctness matters.
- Default `SystemTimeProvider()` constructor fallbacks are reviewed: they may be acceptable compatibility seams, but must not become a hidden way to avoid deterministic test injection.

The time guard is strict and only permits exact, source-verified exceptions; it currently scans a hard-coded `app/src/main/java` scope and rejects stale/broad exceptions fail-closed. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/verify_time_boundaries.py))

**Do not add new time exceptions merely to make the latest commit pass.**

---

## 3. `PR-GR-08q…n` — Triage new current DB findings

**Conditional, but mandatory if GATE-00R returns DB exit `1` with `trusted: true`.**

### Why it must follow evidence
A trusted `1` means the scanner found real current unauthorized mutations. The correct response is a small batch of exact fingerprints—not a baseline update. Your GR-08 history already demonstrates the intended batch pattern: recent commits resolved groups of exact findings after GR-07 exposed 497 real findings. ([github.com](https://github.com/panospao7/Cost-agregator/commits/8b45879e445e8750fcb9210bcf44b9a26e6468dd))

### Rules
- Maximum 25 selected fingerprints per PR.
- Each item is exactly one of: code fix, exact policy row, or analyzer repair.
- No baseline changes.
- No bulk policy expansion.
- Re-run the full trusted scan after each batch.
- If a scan becomes exit `2`, stop triage and move to diagnostic repair.

**Do not create GR-09R.** The checked-in DB v2 baseline was intentionally approved as empty; new findings must not be absorbed into it. ([github.com](https://github.com/panospao7/Cost-agregator/commit/f96f717))

---

## 4. `PR-GR-10C` — DB diagnostic precision and advisory-debt closure

**Required before claiming the DB guard is fully trustworthy, even if current DB findings are zero.**

### Why it must happen
The scanner currently permits non-DB-relevant parser diagnostics to be advisory rather than blocking; the August 28 follow-up explicitly records 20 advisory diagnostics. The rationale is understandable, but this remains detector debt that must be individually understood rather than permanently tolerated. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/docs/DB_WRITE_OWNERSHIP.md))

### Required outcome
For every advisory diagnostic, choose one:
1. eliminate it through a parser/resolver repair with regression fixtures;
2. prove it cannot conceal a DB mutation and retain it only under a narrow documented temporary policy;
3. reclassify it as blocking if it could affect DB authorization.

No “advisory forever” population. No changing DB relevance merely to retain trusted output.

**Dependency:** if DB returns exit `2`, this moves ahead of GR-08q. If DB is trusted and clean, it may follow GR-10A.

---

# Phase 2 — make the guard control plane authoritative

## 5. `PR-GR-10A` — Canonicalize registry → suite → ratchet → Gradle command ownership

**Mandatory.**

### Why it must happen
`guard_registry.py` says it is the single source of truth and that the suite derives from it. But `run_static_guard_suite.py` still owns a separate hard-coded `GUARD_MANIFEST`, including command lines, arguments, baselines, and modes. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/ci/guard_registry.py))

That duplication is dangerous: a guard can be registered but not run equivalently, use a different interpreter, timeout, policy path, or child command in one control plane.

### Target outcome
One canonical executable guard descriptor must drive:
- registry validation;
- static suite execution;
- ratchet child argv;
- Gradle DB task;
- CI documentation/output;
- timeout configuration;
- policy/baseline/input declarations.

It must use one explicit interpreter strategy, tokenized argv, and tests proving that DB CLI, ratchet, suite, and Gradle invoke semantically equivalent commands.

The acceptance gate expressly forbids an independent manual guard list in workflow/control-plane code. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

---

## 6. `PR-GR-10B` — Unify production source-scope authority across guards

**Mandatory for complete-scope claims.**

### Why it must happen
The DB guard has an explicit production-source-root manifest. The time guard still hard-codes `app/src/main/java`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/docs/DB_WRITE_OWNERSHIP.md))

That means a future `src/main/kotlin` root or production module could be guarded by DB tooling but invisible to the time guard or other architecture guards.

### Target outcome
- One declared production-source topology authority.
- Each Kotlin guard either consumes it or explicitly proves why its scope differs.
- An undeclared production root fails closed.
- Registry records source scope, exclusions, and expected sentinels.
- No guard silently scans only part of production code.

This is a guardrail correctness PR, not a Kotlin refactor PR.

---

## 7. `PR-GR-10D` — Documentation and evidence truth sync

**Mandatory; small and separate.**

### Why it must happen
`AGENTS.md` instructs future contributors to read architecture/docs before non-trivial work. Yet `DB_WRITE_OWNERSHIP.md` still says v1 is active, GR-07 is pending, and refers to old baseline/transition states even though the recent branch activated v2 and migrated the DB baseline. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/AGENTS.md))

Stale architecture documentation becomes an operational defect: it sends the next agent toward incorrect policy edits.

### Target outcome
- Mark historical transition sections as historical or remove them.
- State the current v2 authority and baseline path accurately.
- Separate DB authorization, ratchet debt, structural exceptions, and migration/schema baselines.
- Update current proof status honestly: “implemented,” “verified at SHA,” or “pending reproduction.”
- Update the recovery ledger and README claims that are stale.

---

# Phase 3 — close the biggest remaining DB semantic gap

The current v2 model gives exact callable/DAO authorization, but `barrierMode` is still metadata. Source evidence checks local direct-barrier syntax and explicitly makes **no dominance, reachability, helper, or worker-mediation claim**. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/db_guard/policy_model.py))

Therefore, the following sequence is necessary for a *proper* DB write-barrier guard:

## 8. `PR-GR-11` — Structural-analysis foundation
Create the shared parser/region representation needed to reason about method bodies, branches, lambdas, catches, local functions, and guarded blocks.

**Why first:** GR-12 and GR-13 must share one conservative model. Do not build separate regexes for direct and mediated paths.

## 9. `PR-GR-12` — Prove direct write-barrier dominance
Enforce that a direct barrier actually dominates each mutation, or that the mutation is inside a canonical guarded operation.

**Why:** a barrier earlier in a file, conditional branch, `try`, callback, or unrelated lambda does not prove that the write is guarded.

## 10. `PR-GR-13` — Prove helper and worker mediation
Build bounded call-graph/worker-root evidence for `helper` and `workerMediated` policy modes.

**Why:** metadata saying “helper” or “worker mediated” must not authorize a write unless every reachable production entry path is proven guarded.

## 11. `PR-GR-14` — Refactor paths that cannot be proven
Refactor only the writers GR-13 identifies as genuinely unprovable: escaping callbacks, public helpers with unknown callers, ambiguous dispatch, or direct worker writes.

**Why:** the analyzer must fail closed rather than guess. Some source changes are expected here; they should be driven by concrete failed proof, not speculative cleanup.

## 12. `PR-GR-15` — Enforce proven mediation; retire metadata-only authorization
Make the proof model active, migrate policy semantics where needed, and reject metadata-only helper/worker approval.

**Why last:** enabling this before GR-14 would simply create a broad red state. Enabling it after GR-14 makes “green” mean the stronger thing the acceptance gate requires: barrier/worker protection is proven, not asserted.

These five must be serial: GR-11 → 12 → 13 → 14 → 15.

---

# Phase 4 — guard quality, migrations, release, and CI acceptance

## 13. `PR-GUARD-QA-00` — Guard contract and test-coverage audit

**Mandatory before final acceptance.**

The registry currently records no dedicated tests for several guards, including source provenance, privacy, event writers, money, and lint-baseline policy. That may mean tests exist elsewhere, but it must be demonstrated and registered. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/ci/guard_registry.py))

This PR inventories every guard’s:
- positive/negative/adversarial fixtures;
- infrastructure exit-2 coverage;
- real-repository integration test;
- subprocess/exit-code test;
- deterministic-output proof;
- owner, source scope, policy, baseline, and documentation anchor.

It should create follow-up `PR-GUARD-QA-01…n` only for verified gaps. Do not put all parser rewrites into one PR.

---

## 14. `PR-MIG-00` — Audit real migration execution evidence

**Mandatory discovery PR.**

The final acceptance contract requires blocking migration proof, not only static registry checks. The repository’s remaining-plan calls for `MigrationTestHelper`, matrix execution, fresh-vs-migrated parity, unsupported-version behavior, and representative fixture data. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

## 15. `PR-MIG-01` — Make migration proof blocking

**Mandatory implementation PR after the audit.**

It must make actual migration execution a required CI result, with artifact/JUnit proof and no skipped critical migration tests. The registry currently labels `migration_matrix` as a ratchet, which is not by itself proof that every supported migration path executes successfully. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/ci/guard_registry.py))

---

## 16. `PR-REL-00` — Actual release-artifact verification

**Mandatory before declaring the guardrail program complete.**

Static/source guards do not prove the release artifact is safe. The acceptance gate requires release build inspection and bundle-generated APK smoke verification; the repository already has a historical PR-E plan for this work. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

This should verify evaluated release properties, release DI bindings, minification/shrinking, absence of debug/test implementations, artifact contents, and an install/smoke path.

---

## 17. `PR-CI-11` — Workflow, artifact, and required-check integrity

**Mandatory before final sign-off.**

The acceptance contract requires stable aggregating checks, no silent skips, no `continue-on-error` escape route, required artifact uploads, action/workflow validation, and definitive conclusions for all required jobs. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

This is distinct from GR-10A:
- GR-10A fixes local command ownership.
- CI-11 makes GitHub Actions execute and preserve that truth correctly.

---

## 18. `PR-FIN-00` — Final adversarial acceptance

**Mandatory and last.**

Run the whole acceptance sequence twice on the same exact target SHA:
- registry/suite/ratchets;
- guard tests and adversarial seeded violations;
- Gradle compile, unit tests, lint, and check;
- migration proof;
- release artifact verification;
- artifacts/report validation;
- branch-protection verification.

The acceptance gate says missing evidence, unknown results, warnings silently ignored, detector failure, skipped proof, or policy weakening is not green. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

If GitHub branch protection is not actually configured to require the resulting stable checks, add a final operational/configuration PR or documented administrative change: that is a conditional `FIN-01`, but it cannot be skipped if protections are absent.

---

# Recommended execution order

```text
GATE-00R
  ├─ TIME-01, if time/compile/tests fail
  ├─ GR-10C first, if DB exits 2
  └─ GR-08q…n, if DB exits 1 but is trusted
        ↓
fresh GATE-00R evidence
        ↓
GR-10A → GR-10B → GR-10D
        ↓
GR-11 → GR-12 → GR-13 → GR-14 → GR-15
        ↓
GUARD-QA-00 → targeted QA repair PRs
        ↓
MIG-00 → MIG-01
        ↓
REL-00 + CI-11
        ↓
FIN-00 (+ FIN-01 only if branch protection is missing)
```

# Explicitly not next

- **Not GR-09R:** do not regenerate or populate the empty DB baseline.
- **Not a blanket GR-08 restart:** only create GR-08q after a fresh trusted current finding report.
- **Not a broad “fix every guard” PR:** one detector family or proof obligation at a time.
- **Not a barrier-policy rewrite now:** barrier enforcement must wait for GR-11 through GR-13 evidence.
- **Not trusting old documents blindly:** several are valuable historical requirements but stale as descriptions of the current branch.

The immediate next item should be `PR-GATE-00R`; once its output exists, I can turn the selected next branch—TIME repair, DB triage, or control-plane repair—into the first full, implementation-ready PR plan.