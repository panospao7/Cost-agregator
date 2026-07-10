---
description: Execute an external review/debug doc plus implementation plan one strict batch at a time.
agent: orchestrator
---

Use the `strict-imported-plan` skill.

User arguments:

```text
$ARGUMENTS
```

Treat the arguments as paths, pasted context, or instructions for an external review/debug document and implementation plan.

## Required behavior

1. Identify the review/debug source and the implementation plan.
2. If file paths are provided, read those files first.
3. Do not re-plan from scratch.
4. Treat the external plan as approved intent, not guaranteed truth.
5. First delegate to `@scout` to verify the plan against current source.
6. If the plan is stale, conflicting, or unsafe, stop and ask `@planner-advanced` for a delta plan.
7. If the plan matches, execute only the requested batch.
8. If no batch is specified, start with Batch 1.
9. Use strict gates for workers, privacy, security, permissions, diagnostics, Room/migrations, lifecycle paths, architecture guards, backup/export/cloud AI, or cross-layer changes.
10. Do not implement all batches at once.
11. Do not mark docs/status/MIT/PR work complete until code, tests, guardian review, and strict review pass.

## Default strict batch loop

```text
@scout verifies current source
→ relevant guardian checks architecture/privacy/Room risk
→ @coder or @specialist-coder implements minimal diff
→ @tester-runtime adds/runs targeted tests with approval
→ @reviewer-strict reviews current diff
→ stop or continue based on verdict
```

## Hard stops

Stop and report if:

- the review doc or implementation plan cannot be found
- the plan does not match current code
- files/classes named in the plan are missing
- reviewer returns FAIL
- tests fail and root cause is not obvious
- schema migration appears unexpectedly
- privacy/security behavior is ambiguous
- architecture legal path is unclear
- implementation exceeds requested batch scope
- destructive git/file commands would be needed

## Initial response format

```markdown
## Imported Plan Workflow
- Review/debug doc: <path or pasted/unknown>
- Implementation plan: <path or pasted/unknown>
- Requested batch: <batch or Batch 1>
- Mode: strict imported-plan
- Cost posture: quality-gated

## Steps
1. @scout: verify external docs against current source.
2. <guardian>: validate risky boundaries if applicable.
3. @coder/@specialist-coder: implement one batch only.
4. @tester-runtime: add/run targeted tests.
5. @reviewer-strict: review final diff.

## Gates
- Architecture gate: required if workers/lifecycle/static guards are touched
- Privacy/security gate: required if privacy, permissions, diagnostics, AI/export/backup are touched
- Room gate: required if entities/DAOs/migrations/schema are touched
- Test gate: required
- Strict review gate: required

## Stop Condition
- Complete when the requested batch passes implementation, targeted validation, guardian checks, and strict review.
- Fail/block when any hard stop condition occurs.
```
