---
name: implement-batch
description: Execute one approved implementation-plan batch with appropriate gates. Use when the user asks to implement a planned batch or slice of work.
---

# Implement Batch

Execute one approved batch only. The batch description comes from the invoking message. Codex skills have no argument passing: treat everything in the invoking message as the batch/request.

## Instructions

1. Identify the exact batch to execute.
2. Confirm an approved plan exists in the conversation or provided file path.
3. If no approved plan exists, stop and ask the user for planning first (act as the orchestrator planning phase).
4. Use the smallest safe workflow:
   - trivial low-risk edits: implement directly
   - normal work: implement + targeted tests
   - strict for workers/privacy/security/Room/lifecycle/static guards/cross-layer work
5. Do not implement unrelated batches.
6. Do not broaden scope.
7. Require targeted tests for behavior changes.
8. Require strict review (reviewer-strict agent) for risky batches.
9. When a batch touches guard scripts, baselines, allowlists, or exceptions, verify against `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` (path + FG-ID only) that nothing is weakened and fail-closed semantics (FG-03) are preserved.
10. Stop on reviewer fail, test fail, privacy ambiguity, architecture ambiguity, schema surprises, or unexpected broad diff.

## Default batch loop

```text
scout subagent if needed
→ specialist-coder implements
→ tester-runtime authors tests and/or tester-static assesses coverage
→ reviewer-strict for risky batches
→ validation-runner runs the narrowest applicable live profile
```

For risky work:

```text
scout
→ relevant guardian (architecture-guardian, privacy-security-guardian, room-migration-guardian)
→ specialist-coder
→ tester-runtime authors tests + tester-static assesses coverage
→ relevant guardian re-check if needed
→ reviewer-strict
→ validation-runner runs targeted compile/tests/guards
```

## Output format

```markdown
## Batch Execution Plan
- Batch: ...
- Mode: trivial|standard|strict

## Steps
1. ...
2. ...

## Gates
- Test gate: required|optional
- Review gate: required|optional
- Architecture/privacy/Room gate: required|optional

## Stop Condition
- Complete when: ...
- Blocked when: ...
```
