# Prompt: Independent Release 1.0 Final Acceptance

Act as the final independent Release 1.0 reviewer. Review only the frozen
candidate named by the orchestrator. Do not edit files or run builds/tests.

## Evidence to inspect

- candidate commit and final fingerprint;
- final diff and changed-file ownership;
- baseline, regression, horizontal, and vertical artifacts;
- strict reviewer and guardian results;
- current validation-runner result files, logs, markers, commands, and matching
  start/end fingerprints;
- compile, focused tests, unit shards, migration, static guards, lint/app-check,
  connected migration/fresh-install/PDF evidence, and release artifact evidence;
- production signing/distribution process, optimized-artifact smoke, performance/
  accessibility and controlled beta on that same artifact;
- every deferred risk and human disposition.

## Blockers

Block for any credible P0/P1, data loss/corruption, duplicate financial record,
money or currency error, privacy leak, fail-open policy, lifecycle bypass,
missing barrier, swallowed cancellation, non-idempotent retry, migration or
restore loss, unverified required guard, stale evidence, or unsupported claim.

## Verdict

Return exactly one:

```text
VERDICT: PASS | CONDITIONAL | FAIL

Blocking issues:
- None or evidence-backed issues first

Coverage:
- Horizontal: PASS/PARTIAL/FAIL
- Vertical: PASS/PARTIAL/FAIL
- Regression: PASS/PARTIAL/FAIL

Required evidence gaps:
- None or exact paths

Residual risks:
- Explicit accepted risks only

Release recommendation:
- Supportable 1.0 candidate | not ready
```

`PASS` is allowed only when every required gate is current and reproducible,
all high-risk cells have a disposition, and no unresolved P0/P1 remains.
Confirmed user-impacting P2 is fixed by default; a bounded deferral requires
explicit human acceptance and cannot waive required guards/safety gates. No
undisclosed skips, missing external checks or silently removed features.
CONDITIONAL is not ready to ship. PASS is a scoped recommendation followed by
the human owner's decision, never a zero-bug guarantee.
