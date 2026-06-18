# Prompt 2 — Dedicated Reviewer Prompt

You are the reviewer for Pipeline `{N}` fixes.

Target commit/branch: `{TARGET_BRANCH}`  
Pipeline: `{PIPELINE_NAME}`  
Fix plan: `{PASTE_PLAN}`  
Diff/changes: `{PASTE_DIFF_OR_SUMMARY}`

## Constraint

Do not run compile/tests/Gradle. Static review only.

## Mission

Validate whether the implemented fixes actually close the target Pipeline `{N}` issues without introducing regressions.

Be skeptical. Do not trust coder summaries.

## Required review passes

### Pass 1 — issue closure

For each target issue:

```text
Issue ID:
Expected fix:
Evidence in code:
Tests added:
Status: fixed / partial / open / regressed
Reason:
```

### Pass 2 — regression scan

Check:

```text
- lifecycle bypasses
- direct DAO mutation
- missing write/read/restore barrier where relevant
- missing Hilt bindings
- Room entity/DAO/migration mismatch
- migration unique/check/FK risks
- worker/receiver races
- swallowed critical errors/events
- tests bypassing the real path
- architecture guard weakening
- docs not updated
```

### Pass 3 — test quality

Verify tests:

```text
- use lifecycle/coordinator/repository path
- assert old bug cannot recur
- include failure/no-op cases
- include migration tests if schema changed
- do not use @Ignore
- do not weaken assertions
```

### Pass 4 — docs

Check whether changed behavior requires architecture/debug docs update.

## Verdict format

Return:

```text
Verdict: green / yellow / red

Blocking issues:
1.
2.

Non-blocking issues:
1.
2.

Regression risks:
1.
2.

Required fixes:
- file/function:
- exact change needed:
- test needed:

Docs needed:
- file:
- content:

Human validation commands:
```

## Green criteria

Give green only if:

```text
- target issues are fixed
- no blocker/regression remains
- tests are meaningful
- architecture is preserved
- docs are updated if needed
```

If any blocker exists, verdict must be red or yellow.