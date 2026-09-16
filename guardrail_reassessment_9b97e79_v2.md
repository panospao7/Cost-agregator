# Guardrail reassessment — exact SHA `9b97e7979130de605d164386bbf719cf20579475`

## Scope and confidence

This is an independent **source/document review**, not an execution result. I reviewed the guardrail-relevant architecture and recovery documents, DB ownership/baseline documents, current DB guard implementation, ratchet, Gradle wrapper, CI workflow, current policy/baseline, candidate migration output, and the recent guardrail commit series.

The latest commit is not a substantive guard implementation change: it adds silent positional-argument coercion to the Room-inventory **test helper**. It does not repair the active policy, baseline, scanner semantics, or CI evidence. ([github.com](https://github.com/panospao7/Cost-agregator/commit/9b97e7979130de605d164386bbf719cf20579475))

## Verdict

**RED / BLOCKED for trustworthy DB enforcement, with meaningful groundwork completed.**

The important distinction is:

- You have built substantial infrastructure: structured findings v2, a v2-capable ratchet, Room inventory, declaration scanning, a D4-style scanner, policy-source evidence, Gradle preflight, and many tests.
- However, the current DB guard cannot yet produce a trustworthy inventory of architectural violations because it intentionally stops at configuration/proof failures first.
- Therefore, a red result today does **not** mean “hundreds of new DB writes were introduced.” It means the gate is correctly refusing to make an authorization decision using incomplete identity/policy evidence.

The recent commit series clearly includes structured findings and ratchet migration work; the current commit is only a small test-helper patch. ([github.com](https://github.com/panospao7/Cost-agregator/compare/9b97e7979130de605d164386bbf719cf20579475~20...9b97e7979130de605d164386bbf719cf20579475))

---

## Current DB execution path

Conceptually, the current path is:

`static suite` → `guard ratchet v2` → `DB CLI` → `policy/source-evidence validation` → `D4 scanner` → `finding comparison against baseline`

The registry declares `db_access` as a protocol-v2 / fingerprint-schema-v2 ratcheted guard. However, the static suite still owns an independent, hard-coded command manifest rather than deriving it from that registry. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/scripts/ci/guard_registry.py))

### Expected current stop sequence

1. **Normal DB CLI stops before scanning**  
   The CLI rejects the entire active policy when even one entry lacks a `signature`. Visible active policy entries at both the beginning and end of the file are legacy-shaped and lack it. The CLI maps that condition to `DB_POLICY_SOURCE_EVIDENCE_INVALID`, marks the report untrusted, and returns exit `2`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/config/guards/db_ownership_policy.yml))

2. **After policy migration, the baseline becomes a separate blocker**  
   The active DB baseline is legacy text: it contains `generated` and `fingerprints`. The v2 ratchet validation expects v2-style metadata including `generated_at` and an `entries` list with structured debt metadata. Thus the old baseline cannot be treated as a valid v2 baseline. The exact controlled error name should be captured rather than assumed, but a v2 baseline validation failure is expected after the policy blocker is removed. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/config/baselines/db_access.json))

3. **Only after both are repaired can real D4 findings be triaged**  
   Do not regenerate or “fix” the baseline before that point.

---

## Confirmed critical issues

### P0-DB-1 — The active ownership policy is not usable as protocol-v2 authority

The scanner requires an exact signature with receiver, kind, and ordered parameters. The CLI refuses to scan if an active policy entry is missing a signature. The active file is still legacy-shaped; the separate candidate is not active. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/scripts/verify_db_access_boundaries.py))

**Impact:** no trustworthy D4 enforcement result exists yet.

**Required direction:** establish a dedicated v2 policy model first; do not paste the candidate into the active file.

---

### P0-DB-2 — The candidate is incomplete and cannot be promoted

The candidate has only nine entries and serializes only `parameters` and `receiver`; it omits `kind` and has no serialized `ownerFqcn`. The scanner currently checks `signature.kind`, so these entries cannot match as valid v2 policy entries. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/config/guards/db_ownership_policy.signatures.candidate.yml))

**Impact:** promotion would still fail, or worse, invite compatibility fallbacks.

**Required direction:** PR-02 must regenerate a complete candidate using the v2 model, not patch this file manually.

---

### P0-DB-3 — Owner identity is still unsafe

The D4 matcher compares policy `class` against only the final simple component of `declaration.owner_fqcn`. A nested `Handle`, or two same-named classes in different packages, can collide. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/scripts/db_guard/scanner.py))

**Required policy identity:**

`path + ownerFqcn + kind + callable name + extension receiver + ordered parameter types + DAO identity + operation`

The simple class name must become display metadata only, never authorization identity.

---

### P0-DB-4 — Legacy policy-source evidence still unions overloads

The existing source-evidence verifier groups policy by `(path, class, method)` and explicitly treats all overloads’ mutation pairs as one union. That is unsafe: a policy entry for one overload can inadvertently prove or cover a mutation in another overload with the same name. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/scripts/verify_db_access_boundaries.py))

**Required direction:** source evidence may aggregate entries only by the complete callable-v2 identity. It must never union different overloads.

---

### P0-DB-5 — Barrier and worker mediation are not structural proofs

The scanner is a fail-closed source/declaration analyzer, but it does not model a control-flow graph or a call graph. It cannot prove that a barrier dominates a mutation across conditionals, catches, deferred lambdas, helper calls, escaping callbacks, or worker entry paths. The current policy carries `barrier_required` / `barrier_via` metadata, but metadata is not a proof engine. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/config/guards/db_ownership_policy.yml))

**Required direction:** do not claim P0-6/P0-7 complete until a later structural-analysis track proves direct barriers and mediated worker roots.

---

## Newly elevated issues

### P1-DB-1 — Source-root contract is too narrow

Room inventory hard-codes `app/src/main/java` as the only production Kotlin root. That is correct only if the repository keeps that layout forever; a future `app/src/main/kotlin`, feature module, or production source-set addition becomes a blind spot. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/scripts/db_guard/room_inventory.py))

**Fix:** create an explicit production-source-root contract and a meta-test that fails when a production Kotlin root exists but is not declared.

---

### P1-DB-2 — Ownership count is coupled to the structural-exception manifest

The structural expected-method manifest also pins the number of ownership entries: documented as 99 ownership and 62 structural entries. A v2 migration may split one legacy multi-DAO entry into multiple exact mutation entries, changing the count for a valid reason. That creates an unnecessary coupling between structural exceptions and ownership-policy evolution. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/scripts/verify_db_access_boundaries.py))

**Fix:** retain strict structural-exception validation, but move or remove the ownership-count lock before activating v2. A count is an audit tripwire, not authorization evidence.

---

### P1-DB-3 — Registry / suite control plane has drift

`guard_registry.py` claims the static-suite manifest derives from the registry. The suite instead defines its own hard-coded `GUARD_MANIFEST`, including DB command details. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/scripts/ci/guard_registry.py))

A concrete reliability issue follows from this duplication: the suite invokes the child DB CLI as `python`, while most surrounding commands use `python3`. On a machine with no `python` executable, the DB ratchet can fail with infrastructure error before policy analysis.

**Fix:** generate the suite execution plan from the registry and invoke the current interpreter (`sys.executable`) or one explicit configured interpreter everywhere.

---

### P1-DB-4 — Documentation currently overstates enforcement

`DB_WRITE_OWNERSHIP.md` says authorization is decided by the canonical policy and structural-exceptions files, and describes exact write ownership. In practice, active v2 authorization is blocked, simple-name owner matching remains, and barrier mediation is not proven. The document should be labelled as an architectural target / v1 operational state until v2 activation is complete. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/docs/DB_WRITE_OWNERSHIP.md))

Also distinguish:

- `docs/DATABASE_BASELINE_POLICY.md` = Room schema migration support policy, currently v145.
- `config/baselines/db_access.json` = static-guard ratchet debt baseline.

They are unrelated “baselines” and should never be changed together by an agent. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/docs/DATABASE_BASELINE_POLICY.md))

---

## Corrections to the attached reassessment

### Confirmed

- Active policy/signature mismatch blocks trustworthy DB scanning.
- Candidate lacks enough exact identity to promote.
- Legacy overload-union evidence is unsafe.
- Worker mediation remains unproven.
- Room source roots need an explicit contract.
- Registry/suite duplication remains.
- Migration proof is not a PR gate. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/scripts/verify_db_access_boundaries.py))

### Corrected or qualified

1. **Gradle DB fail-open is fixed at this SHA.**  
   The current Gradle task validates required inputs, checks Python availability, and maps ratchet exit `2` to a Gradle failure. Do not spend another PR re-fixing the old missing-ratchet fail-open bug. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/app/build.gradle.kts))

2. **Do not state that two legacy raw-text scanners are definitely wired into `check` without rerunning the task graph.**  
   Legacy lifecycle-bypass code remains in Gradle, but this review did not establish its exact current dependency wiring. Treat it as an audit item for PR-09, not a confirmed current blocker. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/app/build.gradle.kts))

3. **The “385 DB findings” report is historical.**  
   It was recorded on August 9, 2026, before the later protocol-v2 changes. It is useful context, but cannot be used as the current finding count. PR-00 must replace it with exact-SHA evidence. ([github.com](https://github.com/panospao7/Cost-agregator/blob/9b97e7979130de605d164386bbf719cf20579475/VALIDATION_FINDINGS_2026-08-09.md))

4. **Do not promise the exact next ratchet diagnostic name.**  
   A v2 baseline incompatibility is structurally certain from the file shapes, but another exact-policy/source-evidence issue could appear first. Capture rather than guess.

5. **The latest helper patch should be reversed in spirit.**  
   Make `policy` keyword-only in the test helper and add a negative misuse test. Silently treating a non-string third positional argument as policy hides an invalid test call.

---

## Progress estimate

| Measure | Estimate |
|---|---:|
| Guardrail implementation artifacts written | 45–50% |
| DB guard trusted end-to-end today | 25–30% |
| Work remaining for a truthful DB gate | 65–70% |
| Work remaining for the broader DB + barriers + time + migration program | 70–75% |

This is not a judgement on effort quality. The main issue is ordering: much of the work is supporting machinery, while the final authorization semantics, proof obligations, debt triage, and exact-SHA CI evidence still remain.

---

## Immediate debugging target

Do this before changing policy, baseline, production Kotlin, or CI behavior:

1. Freeze exact SHA and clean checkout state.
2. Capture environment, input hashes, policy hash, baseline hash, command lines, exit codes, logs, reports, and report trust status.
3. Run inventory-only mode first.
4. Run normal CLI and record the controlled exit-2 diagnostic.
5. Run ratchet and Gradle wrapper separately.
6. Do not update baseline.
7. Do not promote candidate policy.
8. Treat only `trusted: true`, no diagnostics, and exit `0` or `1` as a triage-ready scan.

The acceptance-gate documentation already requires deterministic outputs, stable fingerprints, atomic reports, and two-run reproducibility; PR-00 operationalizes that requirement for the DB guard specifically. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

---

## Targeted PR sequence

### DB foundation — serial

| PR | Title | Narrow outcome |
|---|---|---|
| GR-00 | Freeze reproducible DB evidence | Exact SHA ledger, logs, hashes, no policy/baseline change |
| GR-01 | Define authoritative DB policy v2 model | Shared typed schema/loader/evidence APIs; not active yet |
| GR-02 | Repair policy-v2 candidate generation | Keyword-only test seam; emit ownerFqcn, kind, receiver, ordered parameters, DAO identity |
| GR-03 | Declare production source roots | Root manifest/meta-guard; real Room inventory proof |
| GR-04 | Decouple ownership count from structural manifest | Preserve structural integrity; remove brittle ownership-count coupling |
| GR-05 | Produce complete v2 policy candidate | Resolve every legacy entry or classify unresolved items; no silent omissions |
| GR-06 | Implement exact callable source evidence | Replace overload union with full callable-v2 identity; shadow comparison only |
| GR-07 | Activate v2 policy and D4 scanner | V2 becomes authoritative; legacy path report-only temporarily |
| GR-08a…n | Triage real DB findings in batches | Maximum 25 findings per PR; code fix, exact policy, or analyzer repair |
| GR-09 | Approve v2 ratchet baseline | Only reviewed, expiring debt; no broad baseline generation |
| GR-10 | Canonicalize DB control plane | Registry-driven suite, one interpreter, retire redundant policy systems |

### Barrier / mediation — only after GR-09

| PR | Title | Narrow outcome |
|---|---|---|
| GR-11 | Structural model foundation | Parser corpus and CFG-ready representation |
| GR-12 | Prove direct barrier dominance | Reject conditional, caught, deferred, unrelated barriers |
| GR-13 | Build worker/call-graph evidence | Exact roots, bounded dispatch, ambiguity exits 2 |
| GR-14 | Refactor unprovable writers | Remove escaping callbacks and broad writer interfaces |
| GR-15 | Enforce mediation | Delete metadata-only `barrier_via` authorization |

### Parallel tracks

| PR | Title | Narrow outcome |
|---|---|---|
| TIME-00 | Freeze exact time-guard evidence | Establish present failure inventory; no expectation changes |
| TIME-01 | Approve time-domain ADR | Zone, range, DST, legacy compatibility, interval semantics |
| TIME-02 | Explicit-zone time API | Deterministic core APIs and caller migration |
| TIME-03 | Resolve legacy time seam | Remove/isolate Calendar and close all confirmed findings |
| MIG-00 | Audit actual migration execution | Verify runner selection and actual executed test count |
| MIG-01 | Make migration proof PR-blocking | Android runner class args, XML verifier, artifacts, tested SHA |
| FIN-00 | Final adversarial acceptance | Inject bypasses; run full candidate twice; record branch protection |