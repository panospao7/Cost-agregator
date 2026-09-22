# Phase 0 review — round 2 (astra/reviewer-strict)

> Recovered 2026-09-21 from stopped codex session rollout-2026-09-21T15-43-22
> (session was stopped before this report was written to disk; extracted from the
> session JSONL per the chat-only-results-do-not-exist rule). Verbatim below.

The handoff is unrelated to this campaign and cites no matching validation artifact; I’m treating it as the required fallback handoff and now checking the matrix section-by-section against the campaign prompt and architecture source.

---

The main stale blockers conflict with amended §3/§8. I’m now checking exact crosswalk gaps, shared ownership, and the pinned worker/schema claims before the final correction list.

---

VERDICT: FAIL

Summary:
- Changed scope: `docs/architecture/COVERAGE_MATRIX.md` only; currently untracked.
- Plan available: yes — campaign `STATE.md`; fallback handoff used: `workflows/active/handoff-test-diagnosis-20260919.md`.
- Main risks checked: exact names, legal paths, shared ownership, workers, schema wording, Phase 0 gate language.
- Architecture sources checked against pinned commit `37601232b9778170c57a656a245b199ab6d7d965`.

Issues:
- [ISSUE-1] [MAJOR] §3 requires a `Primary files` column; the matrix substitutes vague ownership notes (`COVERAGE_MATRIX.md:23-46`). Add exact pinned paths without claiming exhaustive coverage.
- [ISSUE-2] [MAJOR] Legal-path mappings need correction: remove `Notification Capture` from P-04; add exact `Email Receipt Parsing` to P-11 and `Accounting Export` to P-12 (`COVERAGE_MATRIX.md:28,35-36`).
- [ISSUE-3] [MAJOR] Shared ownership is incomplete: no owner rows for `ReceiptParser`, `MoneyAggregate/Builder`, `AnalyticsInputAssembler`, `DailyBucketEngine`, `GroupTransactionCoordinator`, `SharedExpenseManager`, `WorkerRegistry`, `TaxEstimator`, or `BillReminderWorker`; consumer lists also omit explicit WorkerExecutionGuard/TimeProvider users (`COVERAGE_MATRIX.md:52-65`).
- [ISSUE-4] [MINOR] `BackupResumeWorker` is not a missing worker; restore resumes through `RestoreMaintenanceMode.scheduleAllWorkers()` → `WorkerRegistry.scheduleAll()` in the pinned source. Replace the “coverage gap” wording (`COVERAGE_MATRIX.md:82`).
- [ISSUE-5] [MAJOR] Stale blockers conflict with amended §3/§8: lines 3, 102, 106, 129-137 must say unreconciled segments are Phase 1 byproducts, not blockers; remove prior guardian/architecture/room/re-review FAIL claims. Keep validation explicitly not run.
- [ISSUE-6] [MINOR] `Configuration, Performance & Accessibility` is assigned to I-03, although its canonical files are config/performance/UI components; use I-05 or explicitly split ownership (`COVERAGE_MATRIX.md:116`, `CODEBASE_SEGMENTS.md:651-661`).

Coverage:
- Requirements met: no, pending document-only corrections.
- Testing adequate: not applicable; builds/tests/guards/guardians were not run.
- Regression risk: medium.

READY: Yes, after documentor applies these corrections; readiness must not claim complete file coverage, passed validation, or guardian approval.