# CL-22 — author source review and guard-debt handoff

September 27 update: the human authorized the explicit empty UI DAO allowlist, and its isolated rerun passed. The full suite remains FAIL: its persisted artifact lists 16 pass, five violations and four infrastructure errors. Recursive pytest aborted on the mediation model test's bare import before executing tests. That import is now repaired without narrowing selection, and a canonical-module assertion was added; execution remains pending. See workflows/active/wave2-validation-repair-20260927.md. The remaining content below records the initial September 26 author review; its empty-input blocker was subsequently resolved, not waived.

VERDICT: FAIL — not ready for merge or Wave-2 closure.

This is an author review of the uncommitted implementation, not an independent strict-review or guardian verdict. All eight scoped changes and regression fixtures are authored. A source-identified input blocker remains, and no new compile/test/guard execution or independent approval exists. No waiver is inferred from CL-05.

## Findings and inspected implementation

| Finding | Authored change | Regression evidence authored, NOT RUN |
|---|---|---|
| CA-I-04-001 | CI evidence capture receives the same validated PR/push/manual full base SHA for both runs; evidence checkout fetches history. Missing/zero/invalid/unresolvable bases fail rather than falling back to a guessed base. | scripts/ci/test_wave2_guard_wiring.py; existing capture invalid-pin tests remain required. |
| CA-I-04-002 | Cloud R2 and privacy G3 share scripts/guardrails/cloud_payload_proof.py. Each executable POST body must derive from an actual canonical policy call through supported serialization. Source-read and parse failures cannot produce PASS. | Shared proof fixtures, both-frontend integration tests, and positive source fixtures for eight actual provider classes. |
| CA-I-04-003 | Protocol-v1 ratcheting retains occurrence multiplicity and distinct event rule identity. Explicit count metadata is bounded/validated. Legacy entries authorize one occurrence, not unlimited occurrences; v2 is unchanged. Event matching uses all occurrences on a line. | scripts/ci/test_guard_ratchet_occurrences.py, plus existing ratchet/v2 tests selected by recursive collection. |
| CA-I-04-004 | Both canonical suite and evidence capture select pytest recursively over scripts, including nested parser, source-scope and barrier proofs. Capture retains cache suppression and required-module absence warnings. | Updated suite/capture command and discovery fixtures; mere file presence does not prove execution. |
| CA-I-04-005 | Python and Kotlin worker checks discover qualified CoroutineWorker declarations, ignore inert markers and require the actual doWork guard/result bridge. Unsupported declarations and source failures fail closed. Cleanup helpers must actually return a typed guard call; helper names are not exemptions. | Python entry-point fixtures; WorkerEntryPointProofTest; updated WorkerGuardArchitectureGuardTest. |
| CA-I-04-006 | Cancellation exemptions match the active rule, exact resolved callable signature, and bounded path. Empty, class-wide, unresolved, sibling or wrong-rule matches cannot exempt a finding. | scripts/test_verify_cancellation_allowlist_scope.py, including overload, sibling, local-function and interpolation cases. |
| CA-I-04-007 | Missing parser/input, unreadable/malformed YAML, duplicate keys and unsupported entry containers produce controlled infrastructure failure. Explicit empty lists remain valid. | scripts/test_verify_allowlist_compliance_fail_closed.py, direct and CLI fixtures with/without the violation flag. |
| CA-P08-005 | Actual provider instances are invoked with distinguishable policy outputs; captured HTTP bodies and audit metadata are checked. Policy-unit tests are retained and relabeled honestly. | CloudProviderTransportPayloadTest: nine paths (eight providers plus bank-statement text) and five cases per path, authored but not executed. |

## Source-review repairs included

- Executable string interpolation remains visible to violation discovery, while non-executable literal markers cannot establish a worker/cloud proof. Nested strings/comments are bounded and malformed input fails.
- JSON references are inspected through the consuming POST/helper return, so later mutation of a nested container is not hidden by an earlier parent construction.
- Unknown live-object escapes, typed/parenthesized aliases, receiver lambdas, mutable JSON helper parameters and helper alternate returns do not receive a compliant proof. Canonical encoder/config/serialization imports are required for the supported built-ins.
- Immutable record wrappers remain supported only through their inspected fields and returned values. Raw body fields cannot be approved by unrelated prepared metadata. A positive record fixture accompanies the shadowing repair.
- Worker fixtures distinguish early failure from early success/retry, unused/dead helpers from called helpers, constructor guard receivers from shadowed names, and optional cleanup from unrelated unguarded work.
- Provider acceptance fixtures use strict transport/policy boundaries and captured requests; a permanent synthetic HTTP failure avoids claiming response-parser coverage or performing network calls. Receipt image tests use only policy-supplied bytes, not a real user image/path.

## Issues / newly exposed debt

1. **Known input blocker, identified by source inspection:** scripts/allowlists/ui_dao_allowlist.yml contains comments and blank lines only. PyYAML returns null for that input; the hardened compliance parser intentionally rejects null. The next full guard run is therefore expected to report an infrastructure failure for this input unless the owner authorizes normalization. No run has been performed, so this is a predicted failure, not an observed runner result.
   - The smallest proposed owner-authorized repair is an explicit empty list, preserving zero exemptions. It has NOT been made: the persisted coder plan prohibits touching allowlists. Do not bypass the guard or add an exemption to hide this condition.
2. Existing cancellation entries may no longer authorize broad sibling/file matches. Protocol-v1 baselines lacking reviewed counts may expose multiple historical occurrences. Neither a new violation list nor approved counts can be invented before a real run.
3. The cloud/worker proofs are deliberately bounded source analyses, not a Kotlin compiler, full control-flow graph, or whole-program taint analysis. Ambiguous/unsupported forms can require source-proof improvements with positive and negative regressions. Mutable cross-helper JSON and alternate-return helper shapes are not silently approved. Conservative mutation horizons can reject harmless post-snapshot mutation; do not resolve that by reverting to marker/name checks.
4. Existing follow-up debt remains separate: RestoreMaintenanceMode time/cancellation, BankSyncStartupRecovery writer ownership, database-artifact synchronization, approximately 86 raw-money aggregates, and the historical full-suite failure tail. No baseline/allowlist/config growth or guard weakening was performed.

## Validation

- Command actually executed: git -c core.safecrlf=false diff --check -- app scripts .github/workflows/ci.yml. Result: exit 0 at the last source-review checkpoint; whitespace inspection only.
- Compile, Python tests, Kotlin tests, instrumented tests, static guards and CI evidence capture: NOT RUN. No new run IDs, result.json files or validation logs exist for this work.
- Required independent strict review and applicable privacy/architecture guardians: PENDING.
- The 54 named Kotlin test targets in the combined handoff were checked for source-file presence only. That is not test discovery, execution or a passing result.
- See workflows/active/wave2-remaining-validation.md for exact serialized human commands. FINAL_CI_GUARD_ACCEPTANCE_GATE.md remains authoritative by reference: FG-03, FG-06, FG-07 and FG-23.

## Files and preservation

The complete session-owned file manifest is workflows/active/wave2-remaining-files.md. No config, baseline, allowlist or event-writer exception file was changed. No production worker was rewritten merely to satisfy a weak guard. No Room entity/schema/version or migration change belongs to this batch. Campaign closure records and the five pre-existing unrelated worktree changes were left untouched.
