# Wave-2 closure series — approved intent and bounded execution plan

Date: 2026-09-28. Status: IN PROGRESS; Wave 2 is not closed.

Authority: the user's request to execute the next series of necessary fixes, following workflows/active/wave2-closure-progress-20260928-review.md. One agent only; no subagents. Preserve every existing change and existing campaign/review record. No commits, staging or worktree operations. Validation remains human-owned and globally serialized.

## Series and gates

1. **S1 — original generic-PDF partiality, active batch.** Repair CA-P-03-003 / W2-R3 through the actual receipt repository, lifecycle persistence, interactive scan and batch-import consumers. Add source-path, real-Room persistence and UI-contract regressions. Preserve cancellation, duplicate accounting and surviving data. No Room schema change or new mutation owner is needed.
2. **S2 — RP-03 asset integrity/crash recovery, pending separate batch.** Implement the approved file identity/hash/size/durable-state/replay contract without weakening collision checks, pointer CAS, fresh-DB ownership or restart requirements. Do not infer coverage from existing passing test-class names. Complete S1 review/validation handoff before starting this larger state-machine change.
3. **S3 — exact ownership reconciliation and advisory evidence.** Limit ownership work to the four identities in the recovery handoff; no wildcard, baseline growth or unrelated exception. Treat advisory 20-versus-21 as a separate identity/evidence decision, not permission to repin a counter. Preserve FINAL_CI_GUARD_ACCEPTANCE_GATE.md FG-03, FG-06, FG-07 and FG-23.
4. **S4 — remaining execution gaps and evidence-backed repairs.** Diagnose the saved WorkerRunLogger failure before changing code or tests; obtain the six missing current-batch CL-09 results and post-fix recursive Python execution. Do not change assertions merely to obtain green results.
5. **S5 — final independent reviews, device gates and closure matrix.** A passing independent strict/guardian review, device evidence and reconciliation of all 34 findings/riders remain required. This author's inspection cannot provide independent approval. Commit remains a separate human decision.

## S1 design and acceptance

- Introduce one typed page-coverage value shared by OCR and receipt processing. Failed pages and capped processing both remain partial; images without page metadata are not falsely marked partial.
- Persist successful partial recognition as OCR_PARTIAL in the existing string status column. Keep OCR_FAILED and PARSE_FAILED precedence. Persist only controlled partial/count metadata through the existing context-aware event writer; do not enable arbitrary legacy metadata forwarding.
- Carry page coverage in the lifecycle outcome. Existing duplicates retain their existing state and do not create a second event or side-effect batch.
- Show a dedicated scan-review warning. Batch partial counts are a subset of newly saved receipts, not fabricated failures or duplicate saves; the summary must not claim all processing was complete.
- Cover partial/capped/complete inputs, parse failure, cancellation, duplicate accounting, persisted event/row consistency and actual UI state. Use real repository/coordinator/Room wiring for the propagation test; mocked prebuilt outcomes alone are insufficient.
- Author-side strict read-back follows the bounded patch. Independent review and serialized human compile/targeted validation remain gates, not inferred passes. Stop on source/schema/privacy ambiguity, failed review or actual failed validation rather than accumulate unrelated unvalidated batches.

## Intake

Branch bug-fixes; HEAD a48076322e57f3d312f34cf6a71139efc4fcc48b; index empty. Intake fingerprint 9df28a9a3f7dfd4fc2428ea7f8376773482b4f1a623d2a1349e73ec6d3db2498, 124 tracked dirty paths plus 77 untracked paths. This count includes prior reports and unrelated work, not only Wave-2 code. Quiescence is rechecked before writes. This new plan changes the subsequent fingerprint.
