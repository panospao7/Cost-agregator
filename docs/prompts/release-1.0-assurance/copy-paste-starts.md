# Copy-paste starts - one phase/slice per session

Run the listed CLI profile, then paste the block. Replace <PLACEHOLDERS> with
verified facts. For solo mode add --disallowedTools Agent. Read-only agents
return reports; the lead persists shared ledgers. Do not launch all phases at
once. See README for authorization, routing, gates and runtime limits.

## 0. Bootstrap and prerequisites only

Launch: `claude --agent release-orchestrator --effort high`

```text
Read docs/prompts/release-1.0-assurance/README.md, 00-master-orchestrator.md,
01-baseline-freeze.md, coverage-plan.md and evidence-map.md.
Resume workflows/active/release-1.0-assurance-handoff.md.
Do Phase 0/prerequisites and a bounded whole-app dispatch proposal only.
Target stable Release 1.0, including unchanged shipped features, not just Wave 5.
No production edits, validation, commit/push or later phases.
Mode: SOLO, no spawning. Return blockers/scope for my approval.
```

For delegated mode explicitly replace the last line with:
`Mode: DELEGATED; I authorize the kit's named agents for this new release lane,
max two read-only workers, no parallel edits/validation.`

## 1. Inventory/coverage plan

Launch: `claude --agent release-planner --effort xhigh`

```text
Use coverage-plan.md, evidence-map.md and artifact-templates.md under
docs/prompts/release-1.0-assurance/. Candidate: <SHA>; handoff: <PATH>.
Read architecture signposts and old campaign cell-prompts/cluster-map.
Return risk-tiered coverage and dependency-ordered bounded slices across all
pipelines, engine consumers, infrastructure and shipped features. Distinguish
file mapping from real read/test/runtime coverage. No edits, spawning or tests.
```

The lead can use scout Haiku/medium for explicit file/evidence enumeration;
planner confirms safety scope from actual source, not cheap summaries alone.

## 2. Fix closure/regression - one slice

Launch: `claude --agent release-regression-auditor --effort high`

```text
Use 02-regression-audit.md and evidence-map.md under
docs/prompts/release-1.0-assurance/. Candidate: <SHA>; fingerprint: <HASH>.
Bases: full campaign <BASE>; Wave 5 <BASE>. Assigned cell/old IDs: <IDS>;
primary files/callers: <PATHS>. Prove current fix closure and changed reachability
from actual source/tests. Return candidates and status corrections; never confirm
your own findings. No broader phases, edits, spawning or validation.
```

For T1 use `--model claude-opus-5-5 --effort xhigh` and record the actual model.

## 3. Shared-contract/pipeline/engine audit

Launch: `claude --agent release-horizontal-auditor --effort xhigh`

```text
Use 03-horizontal-audit.md, coverage-plan.md and evidence-map.md under
docs/prompts/release-1.0-assurance/. Candidate: <SHA>; handoff: <PATH>.
Assigned cell/slice: <ID/SCOPE>; primary files/callers: <PATHS>.
Deep-review owner contract and affected consumers using all 15 lenses. Inspect
real test oracles and guard scope; names/comments/green guards are not proof.
Return inspected/unread scope, candidates and runtime gaps. No edits, spawning
or validation; no whole-app completion claim from this slice.
```

T2 can override to Sonnet 5.5/high; T1 stays Opus/xhigh with independent refutation.

## 4. Complete user journey

Launch: `claude --agent release-vertical-auditor --effort high`

```text
Use 04-vertical-audit.md, coverage-plan.md, evidence-map.md and
08-runtime-and-release-evidence.md under docs/prompts/release-1.0-assurance/.
Candidate: <SHA>; journey: <ID>; scope/evidence: <PATHS>.
Trace entry -> lifecycle -> DB/event -> worker/side effect -> visible UI.
Check normal, unavailable, stale, retry/cancellation and restart outcomes.
Distinguish parser/DB tests from full orchestration/device proof. Return
invariants, candidates and missing evidence. No edits, spawning or validation.
```

Use Opus/xhigh for restore, conservation, lifecycle races and privacy-heavy paths.

## 5. Independent refutation after every finding batch

FRESH session: `claude --agent release-finding-verifier --effort xhigh`

```text
Use 07-adversarial-verification.md and artifact-templates.md under
docs/prompts/release-1.0-assurance/. Candidate: <SHA>; findings: <REPORT>.
You did not discover or implement these candidates. Try to refute each one
using callers, constraints, intended policy and tests. Return CONFIRMED/REFUTED/
RECALIBRATED/NEEDS_RUNTIME with evidence and exact runtime requests. Preserve
old IDs and UNKNOWN where unproven. No edits, spawning, tests or finding quota.
```

## 6. One approved fix

Launch: `claude --agent release-remediation-coder --effort high`

```text
Use 05-remediation-batch.md under docs/prompts/release-1.0-assurance/.
Verified findings: <IDS>; approved plan: <PATH>; candidate: <SHA>.
Exact write set: <PATHS>; acceptance tests/guardian gates: <CRITERIA>.
Implement only this batch and meaningful regression tests. Preserve unrelated
work; stop on unexpected schema/privacy/guard/legal-path changes. No validation,
spawning, commit/push or unrelated refactors. Return files/risks/requested runner
profiles. Independent review and runtime validation remain pending.
```

Use release-test-author for a disjoint test-only write set when useful. Complex
T1 implementation can use Opus/xhigh after approved design; routine edits do not.

## 7. Strict review then approved execution

Fresh reviewer-strict and relevant guardians first; only then validation-runner.

```text
Review only authored paths <PATHS>, verified findings <IDS>, plan <PATH> and
candidate <SHA>. Inspect production callers, tests, money/privacy/Room/worker/
legal paths. Return VERDICT PASS/FAIL with exact evidence and limits. No edits,
spawning or validation. Do not approve your own implementation.
```

After review/guardian PASS and human approval of expensive queues:

```text
Act only as validation-runner. Frozen candidate: <SHA>; fingerprint: <HASH>.
Approved serial queue: <PROFILES/FILTERS>. Use scripts/validation-runner.ps1
only. Check ownership before Start; poll RUNNING using the same RunId. No edits,
direct Gradle/guards or extra tasks. Stop on failure/drift. Report commands,
results/exit, markers/logs, actual execution/skips/cache, SHA and fingerprints.
```

## 8. Runtime and final decision

Use Sonnet/high for 08-runtime-and-release-evidence.md planning; human approves
devices, artifact, exact execution queue and stabilization. Automated execution
belongs to validation-runner; optimized-artifact smoke needs actual device proof.

Final FRESH session: `claude --agent release-final-reviewer --effort max`

```text
Use 06-final-acceptance.md and authoritative evidence under <CAMPAIGN_PATH>.
Final SHA/fingerprint: <SHA/HASH>; artifact: <HASH/VARIANT>; gates: <PATH>.
Independently assess original fix closure, shipped-cell coverage, current test/
guard/device evidence, signing/artifact smoke, beta and approved residual risks.
No edits or validation. Return PASS/CONDITIONAL/FAIL; CONDITIONAL is not ready
to ship. Do not certify zero bugs or replace missing runtime/human evidence.
```
