# Prompt: Horizontal Cross-App Audit

Act as the independent Release 1.0 horizontal auditor. Work read-only against
the frozen candidate after the regression audit. Do not run builds or modify
files.

Read coverage-plan.md and evidence-map.md for historic cell ownership, actual
source paths, test seeds and guard scope. Work on the assigned bounded cell;
return partial if owner/consumer proof needs additional slices.

## Objective

Check whether the refactor is coherent across the whole application: shared
contracts, cross-layer boundaries, pipelines, engines, persistence, workers,
privacy, and UI state. Use the campaign model of 39 segments, 12 pipelines,
engine interaction rows, and infrastructure cells.

## Coverage requirements

Create a matrix that names every audited cell. A cell may be `AUDITED`,
`N/A-JUSTIFIED`, or `DEFERRED-HUMAN` only with evidence. Every production source
file must belong to at least one cell; orphan ownership is a finding.

## Mandatory lenses

1. legal path and single writer;
2. write/read/restore barriers;
3. atomicity and TOCTOU;
4. idempotency and duplicates;
5. cancellation and timeout safety;
6. post-commit side-effect timing;
7. money, currency, rounding, and unavailable propagation;
8. time zones and period boundaries;
9. privacy, consent, redaction, and fail-closed behavior;
10. worker guard, retry/failure, and metrics semantics;
11. data integrity, provenance, and migration preservation;
12. error and recovery handling;
13. wiring, dead code, and dangerous unreachable paths;
14. test validity and non-vacuity;
15. regressions caused by landed fixes.

## Priority engines and paths

Trace `CurrencyConverter`, `MultiCurrencyRepository`, money builders,
`TimeProvider`, lifecycle coordinators, `WorkerExecutionGuard`,
`DatabaseWriteBarrier`, privacy/cloud policy, backup/restore, groups, receipts,
recurring rules, exports, and UI unavailable states through their consumers.

## Output

Return cell coverage, evidence inspected, confirmed findings, refuted candidates,
unresolved questions, and deferred proof. Use P0-P3 severity and exact file,
symbol, and line evidence. End with `HORIZONTAL AUDIT: PASS | FAIL | PARTIAL`.
PASS requires no unresolved P0/P1 and no unreviewed high-risk cell.
This is the assigned slice's result, not a release certificate. Findings and
original-fix closure need a separate independent verifier.
