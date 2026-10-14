# Release 1.0 assurance setup handoff - 2026-10-13

Status: SETUP ONLY. Prompts and profiles authored; no assurance phase, independent
review, application validation, device test or release acceptance executed here.

## Intent

After Wave 5, establish a stable 1.0 app with evidence from campaign fix closure,
whole-app pipeline/engine/infrastructure reviews, end-to-end user journeys,
runtime/device tests and a controlled optimized-artifact stabilization window.
Do not limit this to Wave 5 diff checking or claim no possible bugs.

## Resume

1. `docs/prompts/release-1.0-assurance/README.md`
2. `docs/prompts/release-1.0-assurance/00-master-orchestrator.md`
3. `docs/prompts/release-1.0-assurance/01-baseline-freeze.md`
   Also read coverage-plan.md, evidence-map.md and copy-paste-starts.md there.
4. Current Wave 5 packet: `workflows/active/money-w5-savings-b8-20261012-handoff.md`
5. Current architecture signposts and candidate source; old snapshots are not truth.

At setup: branch bug-fixes, HEAD 4137afdf, active mixed/uncommitted Wave 5 work.
This is not the accepted release pin. Preserve all unrelated changes. No commits,
staging, push, schema/source/guard edits, or subagents were performed for this kit.
Other sessions may change Wave 5 paths; re-inspect before beginning the lane.

## Models/effort

Full 5.5 IDs and `effort` are set in .claude/agents. Haiku/medium indexing;
Sonnet/high normal audits/coding/tests; Opus/xhigh risk analysis/verification/
guardians; Opus/max bounded final acceptance; Haiku/low validation execution.
Main release-orchestrator uses Opus/high. Actual profile loading/model requests
are NOT RUN; local CLI --version/--help showed 2.1.296 and effort support.

Historical no-subagent amendments remain active for the old Wave 5 lane. The
kit provides a future delegated release mode requiring explicit authorization,
and a solo mode with separate fresh independent reviewer sessions. No global
Claude settings or model override environment variables were changed.

## Release-specific planning gaps

- Final Wave 5 candidate/review/runtime gates still need reconciliation from its
  latest packet; do not borrow earlier PASS records as current proof.
- Existing release build is minified/resource-shrunk but debug-signed for CI
  verification in app/build.gradle.kts. Production signing/protected distribution
  process must be proven without exposing credentials; no signing change here.
- scripts/validation-runner.ps1 currently lacks a release APK/AAB profile.
  Reuse verified CI release-check evidence or plan an approved narrow durable
  integration before local optimized-artifact execution. Existing release_artifact
  guard is external to the static suite/registered-guard bridge. Never bypass
  the runner, invent a task/profile, or reimplement an existing verifier blindly.
- Confirm shipped features, supported upgrade/data policy, device/API matrix,
  performance bounds and stabilization duration with the human owner.

## Next action

Run Phase 0 evidence reconciliation and coverage planning only, using the master
prompt. Return prerequisites and bounded slices for approval before spawning a
campaign or executing expensive validation. All report/gate statuses are pending.

## Setup evidence and limitations

Read architecture signposts, previous campaign ownership/defect lenses, registry
execution metadata, shard manifests and representative source/test boundaries.
The evidence-map records dispatch seeds and proof limitations, not app verdicts.

Local prompt/profile review is not independent acceptance. Actual Claude profile
loading/model requests and app builds/tests/guards remain NOT RUN. An earlier
generic ASCII check rejected existing UTF-8 punctuation in CLAUDE.md and the
three old profiles; that was an inappropriate text check, not an app failure.
Existing UTF-8 contents were preserved, not broadly reformatted.

Finish kit/profile/handoff edits before any final validation queue. Current
runner fingerprints include dirty/untracked files and do not encode the base
commit SHA; record source SHA separately. No edits during live validation.
