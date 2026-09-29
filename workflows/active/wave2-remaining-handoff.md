# Remaining Wave-2 — single-session implementation handoff

## Current repair handback — September 27, 2026, batch 1

Resume from `workflows/active/wave2-finalization-repair-20260927-batch1.md`. The independently verified Money Radar income-fixture cause, forecast currency reuse, cloud proof return parsing, exact cancellation exemptions, and three Python fixtures now have incremental repairs. Eleven source/test files were changed; no subagents, commits, live validation or baseline/allowlist widening were used.

Post-repair runtime/guard validation is NOT RUN under the campaign human-run policy. Wave 2 remains NOT CLOSED: the generic PDF/OCR integration repair, remaining guard debt, unfinished targeted tails and independent/guardian gates remain explicit blockers. The new handback includes exact serialized commands and preserves the prior review and all validator-side edits. The earlier update below is historical, not the current validation state.

## Prior update — September 27, 2026

Resume from workflows/active/wave2-validation-repair-20260927.md for the latest handback. Human-run production compilation and five related worker classes passed; the receipt identity test and Python collection then blocked progress. Two test/infrastructure repairs are now authored, with strict identity/rollback coverage retained and real caller-cancellation coverage added. No post-repair validation was executed by this coder.

The explicit empty UI DAO allowlist was authorized and passed its isolated human rerun. All six validator-side edits have now been reviewed and retained. The persisted full guard summary is 16 passing legs, five violations and four infrastructure errors, not an all-green result; the separate allowlist rerun does not rewrite that full-run result.

The rp-27 nine-test receipt baseline did not include the new failing identity test. The repair report records that evidence correction and the existing non-copying throwable fixture convention; no speculative production exception-unwrapping was introduced. Wave 2 remains NOT CLOSED, with independent gates and reruns pending.

The remainder of this file is the initial September 26 authoring snapshot, including its then-current NOT RUN and empty-allowlist-blocker statements. Use the current repair report above rather than those historical statements for validation status.

## State and authority

Starting and final inspected HEAD: a48076322e57f3d312f34cf6a71139efc4fcc48b, branch bug-fixes. Changes are uncommitted in the current checkout. No subagents, alternate worktrees, commits, pushes or merges were used.

Status: all five remaining cluster scopes have authored implementation and regression changes, plus A2 and A7. **Wave 2 is NOT finalized; these five clusters are NOT closed or merge-approved.** Compilation, tests, static guards and independent approvals remain outstanding. CL-22 author review records a known input blocker.

The user's one-session/no-subagent request superseded the older multi-session/lane-worktree arrangement. The campaign's human-run validation, no baseline/allowlist edits, and no implicit waiver constraints were preserved. Task-specific specifications, author notes and this handoff were created for the requested organization; authoritative STATE.md and JOURNAL.md were not changed or marked complete.

Campaign root: docs/analyses and debug master/campaign CA-2026-09-21. The ordered ledger is wave2/remaining-wave2-plan.md. Read each cluster spec and self-review there for finding IDs and bounded contracts.

## What changed

| Scope | Authored implementation | Gate state |
|---|---|---|
| CL-23 / rp-28 | Correct diagnostic persistence error attribution; monotonic worker-drain deadlines and targeted regressions. | Source-reviewed by author; new validation NOT RUN; independent gate pending. |
| CL-09 / rp-28 | Four stale-write/CAS boundaries: receipt suggestions, recurring activation, merchant-key backfill and warranty eligibility. Success counters/events follow actual affected rows. | Source-reviewed by author; SQL/Kotlin/runtime evidence pending. |
| CL-21 / rp-28 | Query provider uses the actual prepared policy payload; five production-bound hybrid services delegate through HybridRouter. | Source-reviewed by author; privacy and independent gates pending. |
| CL-15 / rp-29 | All-failed money availability, malformed CSV headers, required backup checks, partial OCR semantics, unavailable database statistics/radar/forecast states, and own-timeout-only savings omission. | Eight finding scopes authored; compile/runtime/guardian gates pending. |
| CL-22 / rp-31 | Evidence-base pinning, shared POST provenance, count-aware ratchets, recursive guard-test selection, worker/cancellation proof hardening, fail-closed allowlist input handling, and actual-provider transport tests. | Author closure verdict FAIL: known empty-input blocker and missing validation/independent gates. |
| A2 | Bounded typed denial reason codes from producer to backup UI, distinguishing capability denial from operational failure. | Authored; privacy/runtime approval pending. |
| A7 | Shared settlement-balance policy and actual manager/screen read paths, with chunked SELECT-only history and targeted tests. No settlement writer revival. | Authored; money/group/architecture review and runtime evidence pending. |

## Other issue dispositions

- A1: all three listed retention/transaction/startup cancellation sites were already repaired through CL-05. Current source and provenance were inspected; no duplicate edits or new live-guard PASS claim.
- A5: the CL-05 phone/decimal sanitizer repair and regression corpus were inspected; no duplicate edit or new privacy exception.
- CL-05: wave2/CL-05-review.md now has a closing addendum preserving the historical FAIL while recording the existing human waiver and historical post-fix validation. That waiver does not cover these changes.
- CL-27: existing closure and money contracts remain intact. CL-15 preserves successful-zero and zero-count success rather than redefining them as unavailable.
- A3 remains outside this bounded Wave-2 implementation (Wave-3/CL-18-adjacent candidate); it was not silently closed. A4's current fixture supplies the required settings flow, but fresh runtime evidence is still required. A6/A8 retain the already-landed CL-05/CL-27 dispositions.

## Known blocker and required next decisions

1. scripts/allowlists/ui_dao_allowlist.yml is comment-only. The hardened parser requires an explicit list and will reject its null parse result. This is source-identified, not an observed runner result. Owner authorization is needed for the proposed empty-list normalization because the coder plan prohibited allowlist edits. No exemption addition or check bypass is proposed.
2. Obtain independent human strict review and applicable architecture/privacy guardian decisions. Author reviews are not substitutes; no new waiver was assumed. In particular inspect lifecycle affected-row semantics, financial availability/settlement direction, partial OCR consumers and the new bounded guard proof.
3. Follow wave2-remaining-validation.md on a quiescent worktree, using only the serialized runner. Stop on failure or unknown status, retain durable artifacts, and re-review any fixes. New v1 multiplicity or exact-symbol guard debt must be adjudicated, not baselined away.
4. Only after implementation, relevant execution evidence and required gates are satisfied may the human update campaign closure records. Commit/push/merge still require explicit authorization.

## Validation actually performed

- Read source, surrounding callers, authored tests, selected diffs, architecture contracts and prior commit provenance.
- Repeated scoped git diff --check returned exit 0 for tracked app/scripts/CI edits. This checks whitespace only, not syntax, Room query generation or runtime behavior.
- Checked that all 54 named Kotlin test files in the combined validation plan exist. No tests were discovered or executed by a test runner.
- New compile/tests/guards: NOT RUN, as required by stage3-wave2-coder-prompts.md. No validation run ID, exit code or log can be reported for them.
- Independent strict/guardian gates: PENDING. CL-22's source-identified input issue prevents an unqualified clean author verdict.

## Risks and explicit exclusions

- All new Kotlin and Python code still needs actual compilation/execution. The static analyses are bounded and intentionally reject unproved shapes; the provider source controls and both positive/negative fixtures must run before acceptance.
- OCR regression tests cover metadata, terminal policy and the ViewModel, not physical PDF/image rendering or a full device/Room processor transaction. Merchant worker instrumented fixtures likewise need a separate device-enabled run.
- Warranty state rechecks narrow but do not make database state and OS notification posting atomic.
- The historical 76-failure tail, RestoreMaintenanceMode/BankSyncStartupRecovery/database-artifact/raw-money guard debt and device PDF/image session remain follow-up work. No green global baseline is asserted.
- Room entities, schema versions and migrations are unchanged; new settlement reads do not revive the dormant settlement writer.

## Files touched / preserved

See wave2-remaining-files.md for the complete session-owned manifest. It excludes these five pre-existing unrelated changes, which were left alone:

- .codex/agents/orchestrator.toml
- .codex/agents/explorer-lite.toml
- docs/prompts/master-cross-codebase-audit-campaign-prompt-v3.md
- docs/analyses and debug master/CL-29-wave1-implementation-report.md
- docs/architecture/COVERAGE_MATRIX.md

No config/baseline/allowlist changes, destructive operations or generated-output edits were made. The existing .pytest_cache permission warning seen during git status is not a test result.

## Proposed journal wording — human append only

Supply the actual append timestamp and human attribution; do not invent a validation run or closure. JOURNAL.md was not edited by this session.

    WAVE2-REMAINING-AUTHORED | CL-23/CL-09/CL-21/CL-15/CL-22 plus A2/A7 implementation and regression changes authored serially on bug-fixes at a4807632; no subagents or commits; A1/A5 already-landed repairs adjudicated; CL-05 waiver addendum recorded; new compile/tests/guards NOT RUN under human-run policy; independent gates pending; CL-22 blocked by comment-only UI DAO allowlist input pending owner decision; NOT CLOSED | workflows/active/wave2-remaining-handoff.md
