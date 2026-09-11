# Guardrail Program Execution Charter

**Intended location:** `docs/ci/GUARDRAIL_PROGRAM_EXECUTION_CHARTER.md`  
**Applies to:** every guardrail, migration-proof, release-proof, CI-integrity, policy, baseline, and structural-analysis PR.

## 1. Purpose

Individual PR plans define *what* to implement. This charter defines *how agents execute them safely across many PRs and handoffs*.

The program is complete only when a green result means:

- the intended scope was fully scanned;
- policy decisions are exact and source-proven;
- detector uncertainty fails closed;
- runtime migration and release behavior are genuinely exercised;
- CI preserves the result;
- evidence is tied to one exact SHA;
- two runs reproduce the same semantic result.

A green result must never mean “the guard did not run,” “the test was skipped,” “the scanner guessed,” or “a broad exception hid the issue.”

---

## 2. Authority hierarchy

When instructions conflict, use this order:

1. System/user safety instructions.
2. `AGENTS.md`.
3. Final acceptance contract and current architecture invariants.
4. Current source code, loaded configuration, and exact-SHA evidence.
5. Approved PR plan.
6. Task-specific orchestrator instruction.

A PR plan is **approved intent, not guaranteed current truth**.

Before editing, every implementer must verify the plan against the actual start SHA and explicitly classify it:

```text
MATCHED
ADAPTATION_REQUIRED
STALE_OR_SUPERSEDED
BLOCKED
```

No silent plan deviation is allowed.

---

## 3. Non-negotiable program rules

1. **Exact SHA first.** Historical results are context, never current proof.
2. **Exit 2 is never a violation.** It is an infrastructure/proof failure and blocks triage.
3. **No finding is hidden to get green.** Do not weaken policy, source scope, parser behavior, severity, baseline, or exception matching.
4. **No broad suppression.** No path/class/package wildcard, “legacy” rationale, permanent unresolved debt, or whole-file bypass.
5. **No source triage while detector trust is broken.** Repair detector/configuration/source-root evidence first.
6. **No baseline update in a feature/fix PR.** Baseline changes require their own approved, reviewed, expiring-debt process.
7. **One safety claim per PR.** Do not mix control-plane rewrites, scanner semantics, production refactors, and policy expansion unless one exact root cause requires all.
8. **No self-certification for high-risk work.** A separate reviewer must inspect proof, diffs, and adversarial tests.
9. **No command-result laundering.** No `|| true`, silent `set +e`, ignored exit code, warning-mode substitute, or skipped required job.
10. **No “probably.”** Use `PASS`, `FAIL`, `NOT RUN`, or `BLOCKED`, with evidence.

---

## 4. Agent roles and separation of duties

| Role | May do | Must not do |
|---|---|---|
| Orchestrator | Select next ready plan, allocate locks, freeze SHA, route failures, approve handoffs | Treat prior prose as evidence; run conflicting implementation work |
| Scout | Read source/docs, validate plan assumptions, prepare evidence map | Edit policy/source/baseline |
| Implementer | Make smallest approved change and targeted tests | Expand scope without plan delta |
| Gradle owner | Run sequential compile/test/emulator commands and preserve logs | Share Gradle ownership or run parallel builds |
| Evidence runner | Execute canonical commands, retain reports/hashes | Interpret missing evidence as pass |
| Independent reviewer | Review semantic diff and seed adversarial checks | Approve based only on implementer summary |
| CI/platform operator | Run workflows, retrieve artifacts, verify protection/rulesets | Alter required-check policy without recorded transition |

For high-risk work—DB authorization, source scope, migrations, release, CI, privacy, structural proof—the implementer and reviewer must be different agents.

---

## 5. Ownership locks

The orchestrator must maintain one active owner per authority surface:

```text
ACTIVE_DB_POLICY
DB_BASELINE
DB_SCANNER_AND_REPORT_PROTOCOL
STRUCTURAL_PROOF_ENGINE
SOURCE_SCOPE_MANIFEST_AND_RESOLVER
REGISTRY_SUITE_RATCHET_GRADLE_CONTROL_PLANE
TIME_POLICY
MIGRATION_POLICY_AND_PROOF
RELEASE_POLICY_AND_ARTIFACT_VERIFIER
CI_WORKFLOW_AND_REQUIRED_CHECKS
DOCUMENTATION_AND_EVIDENCE_INDEX
PRODUCTION_KOTLIN
GRADLE_EXECUTION
```

Rules:

- Two agents may read the same area.
- Only one agent may modify one locked surface.
- One Gradle owner exists globally at a time.
- Concurrent work must use separate worktrees.
- A PR touching multiple locked surfaces needs explicit orchestrator approval.
- If a lock conflict appears, stop and re-sequence work.

---

## 6. Required PR lifecycle

```text
NOT_READY
→ DISCOVERY
→ FROZEN
→ IMPLEMENTING
→ VALIDATING
→ INDEPENDENT_REVIEW
→ COMPLETE_AT_SHA
```

Alternative terminal states:

```text
BLOCKED
RED
SUPERSEDED
```

Never use unqualified `DONE`, `GREEN`, or `COMPLETE`.

### Discovery
Read relevant architecture documents, current implementation, current tests, and plan prerequisites.

### Frozen
Record:

```text
start SHA
tree SHA
base SHA and merge-base where applicable
clean-tree result
policy/baseline/manifest hashes
incoming evidence report hashes
exclusive ownership locks
```

### Implementing
Make only changes within the approved scope. If a required file is unexpected, stop and issue a `PLAN_DELTA`.

### Validating
Run targeted tests first, then direct CLI, then suite/Gradle/CI as required.

### Independent review
Review the diff, test coverage, failure routing, policy delta, artifact safety, and adversarial cases.

### Complete at SHA
A completion state applies only to the exact end SHA and exact evidence bundle.

---

## 7. Mandatory failure routing

| Observation | Meaning | Required route |
|---|---|---|
| Guard exits `0` | Current policy satisfied | Preserve evidence; continue |
| Guard exits `1`, trusted | Real violation | Narrow triage/fix PR; no baseline |
| Guard exits `2` | Detector/configuration/proof failure | Stop code triage; repair owning guard/control plane |
| Two runs differ semantically | Nondeterminism or hidden dependency | Infrastructure/control-plane repair |
| Required command not run | Evidence incomplete | `NOT RUN` or `BLOCKED` |
| Required artifact missing | Evidence incomplete | `BLOCKED` |
| Policy/root/baseline changes unexpectedly | Scope breach | Stop; review plan delta |
| New advisory diagnostic | Detector debt | Inventory; do not normalize it silently |

---

## 8. Things agents must avoid

### Never do these to obtain green

- Add a baseline entry for a new finding.
- Change a finding to advisory without proof it cannot conceal a violation.
- Narrow source roots to hide a file.
- Add a broad exception, wildcard, package exclusion, or class exclusion.
- Move a DAO write from one forbidden layer to another forbidden layer.
- Use simple class names, method names, or regex coincidence as authorization identity.
- Treat a nearby barrier string as proof of barrier dominance.
- Treat a worker/helper label as proof of mediation.
- Use a debug APK as release proof.
- Treat static schema parsing as actual migration execution.
- Trust a source test without proving the canonical CLI/CI path executes it.
- Delete tests, lower assertions, or increase timeouts without root-cause evidence.
- Commit generated build outputs or sensitive artifacts.
- Claim a status from an old SHA as current.

### Especially dangerous mixed PRs

Do not combine these without explicit approval:

```text
scanner semantics + baseline update
policy expansion + broad production refactor
source-root changes + new exclusions
migration policy choice + unrelated schema cleanup
release verifier changes + release policy relaxation
CI required-check changes + branch-protection weakening
```

---

## 9. Proof ladder for every guard change

Every guard behavior change must include, in this order:

1. **Unit/model test** — validates exact parser/model/contract behavior.
2. **Positive fixture** — illegal pattern produces finding.
3. **Negative fixture** — legal pattern passes.
4. **At least three adversarial fixtures** — common bypass patterns fail.
5. **Infrastructure fixture** — malformed/missing/ambiguous input exits `2`.
6. **Subprocess test** — real CLI exit and report contract.
7. **Real-repository integration** — actual checkout is scanned.
8. **Determinism test** — two semantic outputs match.
9. **Control-plane proof** — direct CLI, suite, ratchet, Gradle, and CI use the intended authority.

For source-scanning guards, also require:

```text
declared second-root positive test
undeclared-root fail-closed test
symlink/unreadable-root test
```

For ratchets, require:

```text
new finding → 1
resolved baseline finding → 1
malformed report/baseline → 2
stale debt → 2
```

---

## 10. Guard semantic separation

Agents must keep these concepts separate:

```text
Ownership policy       = who may perform one exact mutation
Source evidence        = whether that exact mutation exists in that callable
Barrier proof          = whether the mutation is guarded on every path
Mediation proof        = whether callers/workers preserve guard context
Baseline               = temporary ratchet debt only
Structural exception   = exact inherently required narrow behavior
Advisory diagnostic    = unresolved detector debt, never authorization
```

One layer may not substitute for another.

Examples:

- A legal ownership row does not prove a barrier.
- A barrier metadata label does not prove a worker path.
- A passing unit test does not prove the release artifact.
- A clean current scan does not validate an old historical report.
- A baseline does not authorize future source changes.

---

## 11. Evidence and artifact discipline

Every implementation handoff must include:

```text
exact start/end SHA
clean-tree status before work
files changed
commands actually run
exit codes
artifact/report paths
artifact hashes
tests not run and why
remaining risks
next recommended route
```

Rules:

- Reports must be structured, atomically written, deterministic, and safe.
- Do not place raw source, SQL, exception messages, PII, tokens, paths outside repository scope, or signing material into reports.
- Do not summarize a report manually when a machine-readable report exists.
- Preserve raw logs externally or in CI artifacts; track only safe indexes/checksums in repository docs.
- Compare semantic content, not timestamps, durations, temp directories, or ephemeral signing fingerprints.

---

## 12. Required handoff format

Every agent must end with:

```markdown
PLAN:
ROLE:
START SHA:
END SHA:
WORKTREE CLEAN BEFORE/AFTER:

SCOPE:
- Allowed:
- Actual files touched:
- Unexpected files:

IMPLEMENTATION:
- What changed:
- What intentionally did not change:

VALIDATION:
- command:
  result: PASS | FAIL | NOT RUN | BLOCKED
  exit:
  artifact/report:
  notes:

EVIDENCE:
- policy/baseline/root hashes:
- semantic report hash:
- reproducibility result:

RISKS / OPEN ITEMS:
- ...

LOCKS RELEASED:
- ...

NEXT ROUTE:
- ...
```

A reviewer must separately return:

```markdown
VERDICT: PASS | FAIL | BLOCKED

Reviewed:
- plan compliance
- semantic diff
- policy/baseline/source-scope delta
- negative/adversarial coverage
- command evidence
- artifact safety

Issues:
- exact file / symbol / evidence
```

---

## 13. Plan-delta protocol

If current code does not match the plan:

1. Stop before broad implementation.
2. Create a short `PLAN_DELTA` record.
3. State:
   - expected plan condition;
   - observed current condition;
   - impact;
   - safe options;
   - recommendation;
   - whether the plan is stale, split, or blocked.
4. Orchestrator decides whether to:
   - amend the plan;
   - split a prerequisite PR;
   - defer the work;
   - cancel/supersede the plan.

No agent may “repair around” an architectural ambiguity.

---

## 14. Long-term intended outcome

The program should converge on these durable properties:

1. **One canonical control plane**  
   Registry, suite, ratchet, Gradle, CI, and documentation describe the same guards.

2. **Complete declared source scope**  
   New production roots cannot silently evade scanning.

3. **Exact authorization**  
   DB writes are authorized by full callable + mutation identity, not names or patterns.

4. **Proven execution safety**  
   Direct barriers dominate writes; helper/worker mediation is bounded and source-proven.

5. **No metadata-only safety claims**  
   Labels such as `helper`, `workerMediated`, or “reviewed” cannot authorize behavior alone.

6. **No indefinite silent debt**  
   Debt is exact, finite, owned, expiring, and independently ratcheted.

7. **Runtime proof matters**  
   Migration and release verification exercise actual production builder/artifact paths.

8. **Fail-closed infrastructure**  
   Unknown scope, parser uncertainty, missing artifact, skipped proof, and unavailable tool are non-mergeable.

9. **Low-maintenance evolution**  
   Every new guard/rule/root/migration/release behavior updates its registry contract, tests, evidence, and documentation at the same time.

---

## 15. Steady-state rule after final acceptance

After FIN-00, ordinary feature PRs must include a short guard-impact declaration:

```text
Does this change:
- add/change a production source root?
- add/change a DB mutation?
- add/change barrier/worker path?
- add/change time access?
- add/change Room schema/migration?
- add/change release manifest/dependency/signing behavior?
- add/change policy, exception, baseline, or guard implementation?
```

If yes, the feature PR must include the relevant guard contract/test/evidence update. It must not defer guard impact into “cleanup later.”

---

## 16. Minimum orchestrator checklist

Before assigning any plan:

```text
[ ] Actual start SHA and tree SHA frozen
[ ] Required predecessor evidence exists at current SHA
[ ] Plan verified against current source
[ ] Scope and forbidden surfaces named
[ ] Ownership locks acquired
[ ] Gradle owner assigned
[ ] Expected 0/1/2 route defined
[ ] Required artifacts named
[ ] Independent reviewer assigned
[ ] Plan-delta route named
[ ] No baseline/policy/source-scope shortcut is implied
```