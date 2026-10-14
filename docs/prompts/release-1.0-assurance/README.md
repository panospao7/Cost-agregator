# Release 1.0 Assurance Campaign

This campaign is the post-Wave-5 path to a stable Release 1.0 candidate. It is
not a promise that software can contain zero bugs. It is an evidence program
that searches for defects, verifies the fixes, proves the important user
journeys, and blocks release claims when required evidence is missing.

## Preconditions

Quick navigation: `copy-paste-starts.md` (launches and prompts),
`coverage-plan.md` (whole-app scope/model routing), `evidence-map.md` (actual
owners/tests/guard IDs/shards), `artifact-templates.md` (honest evidence ledger).

Do not start the broad campaign on the current Wave 5 WIP tree. First obtain a
single frozen Wave 5 candidate with a commit fingerprint, current handoff,
independent review, and the required validation evidence. If the tree is dirty,
the Wave 5 handoff is partial, or required evidence is historical, stop at
Phase 0.

That initial gate concerns an unreviewed mixed implementation tree. Once the
source is pinned and the release lane starts, the sole artifact owner may write
the named campaign reports: document these permitted artifact-only changes and
prove app/config/scripts/build inputs still match the source pin before and
after every audit. Unexpected source drift stops the affected slice. Do not
enforce a blanket clean-status rule that makes writing reports invalidate a
static audit; final runner evidence still requires its exact whole-tree snapshot.

Read these files before scoping:

- `AGENTS.md`
- `docs/architecture/CODEBASE_SEGMENTS.md`
- `docs/architecture/CODEBASE_INVENTORY.md`
- `docs/architecture/LEGAL_PATHS.md`
- `docs/architecture/ENGINE_INTERACTION_MAP.md`
- `docs/prompts/master-cross-codebase-audit-campaign-prompt-v3.md`
- `workflows/active/release-1.0-assurance-handoff.md`
- the latest Wave 5 handoff referenced there (currently
  `workflows/active/money-w5-savings-b8-20261012-handoff.md`)

Preparing this kit does not start the campaign or approve paid autonomous work.
Historical no-subagent amendments remain binding for their Wave 5 lane. Start
the release lane with explicit user authorization for delegated or solo mode.

## Phases and model routing

| Phase | Work | Model | Effort | Write access |
| --- | --- | --- | --- | --- |
| 0 | Wave 5 closeout and baseline dossier | Opus 5.5 | high | artifact owner only |
| 1 | File/cell inventory and evidence indexing | Haiku 5.5 | medium | none |
| 2 | Campaign Waves 1-5 closure and regression audit | Sonnet 5.5 | high | none |
| 3 | Horizontal cross-app contract audit | Opus 5.5 | xhigh | none |
| 4 | Vertical user-journey audit | Sonnet 5.5 | high | none |
| 5 | Hardening one approved finding at a time | Sonnet 5.5 | high | coder only |
| 6 | Device, optimized artifact, and stabilization evidence | Sonnet 5.5 | high | test owner only |
| 7 | Independent final acceptance | Opus 5.5 | max | none |

After every discovery batch: a fresh Opus/xhigh verifier tries to refute it.
Test authoring is Sonnet/high; execution is Haiku/low `validation-runner`.
Money/Room/privacy/workers and disputed cross-pipeline paths start at Opus/xhigh
for deep analysis rather than waiting for a cheaper model to fail.

Use Opus only where independent judgment or cross-cutting reasoning matters.
Use Haiku for bounded indexing and repetitive evidence extraction. Use Sonnet
for most diagnosis, test design, and implementation. Escalate a Sonnet task to
Opus when it crosses money, privacy, Room/restore, workers, or several
pipelines, or when the first diagnosis is disputed.

The initial lead is Opus/high for scope and gate decisions. Once the plan is
approved, routine dispatch/status bookkeeping can use the same lead profile
with `--model claude-sonnet-5-5 --effort high`; keep specialist/guardian pins
intact. Return to Opus for safety-policy ambiguity, changed scope and acceptance.
This saves cost without weakening the independent reviews.

All three 5.5 models support `low`, `medium`, `high`, `xhigh`, and `max`
effort. The profile files pin effort explicitly; do not rely on the model
default for release work. These are recommended task assignments, not promises
that more effort always improves results. `max` is a bounded exception; never
set it globally. See [model and effort guidance](https://code.claude.com/docs/en/model-config#adjust-effort-level).

## Gate order

1. `01-baseline-freeze.md` - Wave 5 is actually closed and the candidate is frozen.
2. `coverage-plan.md` - full shipped-app inventory and risk-tiered slice queue.
3. `02-regression-audit.md` - Waves 1-5 fixes are reconciled; regressions separated from old debt.
4. `03-horizontal-audit.md` - shared contracts and architecture boundaries are covered.
5. `04-vertical-audit.md` - all shipped journeys work across persistence and UI.
6. `05-remediation-batch.md` - confirmed findings receive minimal fixes and tests.
7. `08-runtime-and-release-evidence.md` - devices, optimized artifact and stabilization.
8. `06-final-acceptance.md` - independent recommendation followed by human release decision.

Use `07-adversarial-verification.md` after each findings batch, before promotion
to the confirmed ledger. Filename numbers identify prompts, not an automatic
execution sequence. Do not implement speculative candidates.

Do not run these as an uncontrolled swarm. Keep one owner for the ledger, one
validation process, no parallel edits, and no self-approval. Auditors return
reports; the orchestrator integrates them into the authoritative artifacts.

## Required artifacts

- `release-1.0-baseline.md`
- `release-1.0-coverage-matrix.md`
- `release-1.0-regression-ledger.md`
- `release-1.0-horizontal-audit.md`
- `release-1.0-vertical-journey-matrix.md`
- `release-1.0-remediation-plan.md`
- `release-1.0-verification-ledger.md` (including retained refutations)
- `release-1.0-runtime-matrix.md`
- `release-1.0-release-gates.md`
- `release-1.0-final-acceptance.md`

Use `artifact-templates.md`; the orchestrator is the sole ledger writer.
Store campaign reports in `docs/releases/release-1.0-assurance/` once the lane
starts. Update the workflow handoff before each final fingerprint freeze.
Keep validation output and post-freeze reports in generated `build/` evidence,
after checking runner fingerprint exclusions; they are not source changes or
automatically committed. Never edit fingerprinted docs during the queue.

Every artifact records the candidate commit, worktree state, agent/model/effort,
files inspected, evidence paths, findings, and remaining uncertainty.

## Validation ownership

Only `validation-runner` executes compile, tests, lint, connected tests, and
guards, serially through `scripts/validation-runner.ps1`. In solo mode, the
main session explicitly assumes that execution-only role. All other roles may
request validation or diagnose persisted results, not execute it. Expensive
queues require human approval. Missing, stale, skipped-required, unknown,
timed-out, or historical evidence is not PASS.

The current wrapper supports `compile`, `targeted-unit-test`, `unit-test-shard`,
`unit-tests`, `migration-tests`, `lint`, `static-guards`, `registered-guard`,
`app-check`, `assemble-debug`, and `connected-tests`. Check current source and
shard manifests before use. It currently has no release APK/AAB profile: plan
an approved narrow runner addition, with independent review, before executing
release artifact checks. Never bypass the wrapper or invent a profile.
The existing `release_artifact` verifier is declared external in the registry
and lives outside the static suite; the bridge cannot run it as an ordinary
registered-guard. Reuse its CI contract or approve a durable integration first.

## Release acceptance, not just clean code

- No known unresolved P0/P1. Default: fix all confirmed user-impacting P2 too.
  A narrowly bounded P2 deferral needs human risk acceptance or an explicitly
  approved disabled/withheld feature; safety/guard requirements cannot be waived.
- All shipped cells have source/test/runtime coverage appropriate to their risk.
  Mapping a file to a cell or a green unit test is not proof of a working app.
- No failing required gates, undisclosed skips, or unresolved critical evidence.
- Fresh-install, supported upgrades, old/current backup restore, permissions,
  offline/error paths, restart, and optimized release artifact have evidence.
- Confirm production signing outside the debug-signed CI artifact; never read
  private keys or store credentials in campaign artifacts.
- Human testing/controlled beta of the same candidate is required. Record device
  and artifact identity, test duration, observations and fixes. Changing the
  candidate invalidates affected evidence and requires impact-based reruns.
- Opus acceptance is a scoped recommendation, not certification of no bugs.
  CONDITIONAL is not release-ready; the human owner makes the release decision.

## Start in Claude Code

Installed CLI at kit preparation: `2.1.296`. Profiles pin full 5.5 IDs and
supported `effort` frontmatter, documented in [subagent configuration](https://code.claude.com/docs/en/sub-agents).
No paid Claude session or actual profile invocation was run while authoring.

Auditors have no Edit/Write/Agent tools and use plan mode; coders have no shell/
Agent tools. Shell-equipped auditors are still constrained by permission policy
and read-only instructions, not an absolute process sandbox. Do not use
`--dangerously-skip-permissions` or assume prompts enforce every boundary.
Confirm loaded profiles and actual model/effort before starting approved work.

Delegated mode, only when explicitly authorized for the release lane:

```powershell
claude --agent release-orchestrator --model claude-opus-5-5 --effort high
```

Paste `00-master-orchestrator.md`, or use this starter:

```text
Read docs/prompts/release-1.0-assurance/README.md and 00-master-orchestrator.md,
then workflows/active/release-1.0-assurance-handoff.md.
Do Phase 0 and coverage planning only. I authorize named release subagents for
this new release lane, not changes to the Wave 5 lane's historical constraints.
No production edits, validation execution, commit/push, or autonomous later
phases. Return the missing prerequisites and bounded slice plan for approval.
```

Solo mode (no spawning; separate fresh audit/reviewer sessions):

```powershell
claude --agent release-orchestrator --model claude-opus-5-5 --effort high --disallowedTools Agent
claude --agent release-regression-auditor --effort high --disallowedTools Agent
claude --agent release-final-reviewer --effort max --disallowedTools Agent
```

Run those sequentially, not all at once. The read-only profiles return reports;
the artifact owner persists them. No live validation alongside editing.

Do not set `CLAUDE_CODE_EFFORT_LEVEL` globally: it overrides role-specific
effort. Likewise do not force `CLAUDE_CODE_SUBAGENT_MODEL_FORCE` or a cheap
model fallback over the profile routing. Inspect `/tasks` for actual model/
effort and record substitution; unavailable intended reviewers remain pending,
not silently downgraded. These defaults do not alter your global settings.

## Quality-first, cost-aware limits

- Opus designs coverage and owns high-risk/refutation/final gates; it does not
  reread every low-risk file or perform routine indexing.
- Haiku scopes files/evidence; its summaries cannot clear financial/security
  invariants or declare test/feature coverage adequate.
- Normal audits and coding are Sonnet/high. Escalate once on ambiguous/disputed
  evidence instead of paying for repeated blind retries.
- Start a slice with roughly 8-15 primary files plus necessary real callers;
  expand or split when proof requires it. A context budget is never a coverage
  certificate: list unread paths as pending.
- At most two independent read-only workers, one writer, one live validation.
  Resume a specialist only for the same slice; use fresh context for independent
  verification. Keep discovery details in reports and the lead's context lean.
- Two failed review/fix rounds trigger replanning, not endless loops, test
  relaxation, automatic feature removal, or shipping with an unexplained FAIL.
- Prefer one targeted check for each change and one final approved comprehensive
  frozen-snapshot queue. Do not rerun broad suites after every unrelated thought.

## Completion rule

Stop when planned coverage and required evidence are satisfied, candidates are
independently dispositioned, confirmed user-impacting defects are fixed or
explicitly accepted within policy, the same optimized candidate has runtime/
stabilization proof, and independent review recommends acceptance. Do not demand
endless zero-finding rounds to prove zero possible bugs. New confirmed defects
reopen affected scope and required final gates, not an automatic whole refactor.

