# Artifact templates - no predeclared outcomes

These are templates, not instantiated acceptance artifacts. At campaign start,
the orchestrator creates the authoritative reports and one workflow handoff.
Auditors return content without writing shared ledgers. Use `PENDING` until
there is evidence; no inferred reads, independent sessions or runtime PASS.

## Common provenance (every report/slice)

```text
Workflow / phase / cell:
Source candidate SHA:
Start/end/current worktree fingerprint:
Comparison baselines (full campaign / Wave 5):
Scope and write set:
Role, requested and actual model/effort:
Session/agent identity: actual ID if available; never fabricated
Independent of discovery/implementation: yes/no + evidence
Full reads / scoped reads / search-only / not-read paths:
Commands executed: none or exact list
Evidence paths and timestamps:
Limitations / pending scope:
Status: PENDING | PARTIAL | PASS | FAIL
```

Record SHA and fingerprint separately. Current runner fingerprinting hashes
dirty/untracked bytes, not the base commit SHA: different clean commits can
share that fingerprint. SHA identity/ancestry is a separate required check.
Finish kit/profile/handoff edits before the validation freeze; these edits
also participate in a dirty-tree fingerprint. Never edit during a live queue.

## Coverage matrix

| Cell/journey | Pipeline | Segments | Engine owner/consumers | Legal path | Source/variant files | Read depth | Tests/oracle | Runtime proof | Old IDs | Owner/model/effort | Status/limits |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| <ID> | <name> | <IDs> | <names> | <section> | <paths> | NOT-READ | PENDING | PENDING | <IDs> | <role> | PENDING |

All 39 segments, 12 pipelines, engine interaction rows, infrastructure, manifest/
resources/build variants and shipped journeys must have a justified disposition.
File count and test count are not substitutes for semantic proof.

## Findings and original-fix closure

| ID/old ID | Candidate severity | Class | Entry/caller trace | Source evidence | Classification | Independent verifier | Runtime/oracle | Confirmed status | Minimal fix/owner | Closure review and run | Residual risk |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| <ID> | <P0-P3> | <lens> | <path> | <SHA/path/symbol/line> | UNKNOWN | PENDING | PENDING | CANDIDATE | PENDING | PENDING | <risk> |

Classification: NEW-REGRESSION / CHANGED / STILL-OPEN / PRE-EXISTING / TEST-DEBT /
DOC-DRIFT / UNKNOWN. Confirmation: CANDIDATE / CONFIRMED / RECALIBRATED /
REFUTED / NEEDS_RUNTIME. Closure: OPEN / FIX-AUTHORED / REVIEW-PENDING /
VALIDATION-PENDING / CLOSED-VERIFIED / HUMAN-DEFERRED.

Severity is based on demonstrated impact: P0 data loss/corruption, duplicate
financial records, privacy leak/restore bypass; P1 critical lifecycle/audit/race/
barrier failure; P2 bounded user-impact/recovery/incorrect-state defect; P3
nonblocking documentation/maintainability. Recalibrate when evidence changes.
Do not auto-defer P2 just because it is not P0/P1.

## Validation/runtime/artifact ledger

| Gate/scenario | Required? | Exact runner command/profile/filter | RunId | Exit/status | Marker/log | SHA/fingerprints | Actual execution/cache/skips | Device/artifact hash | Limitation |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| <gate> | yes | PENDING | PENDING | NOT RUN | PENDING | PENDING | PENDING | PENDING | <gap> |

Runtime-only human observations name reproducible steps, independent expected
result, tester, device/API, timestamp and artifact hash. Do not assign a Gradle
RunId to a manual test. Never rename FAIL/INFRA/TIMEOUT to PASS.

## Handoff

```text
Status: SETUP ONLY | PENDING | PARTIAL | BLOCKED | ACCEPTANCE RECOMMENDED
Current source/artifact:
Current approved phase/scope:
Mode: solo | delegated (explicit human authorization)
Decisions / historical constraints:
Changed paths:
Confirmed/refuted/open findings:
Review/guardian verdicts:
Current versus historical validation evidence:
Next action and exact prompt/profile/model/effort:
Human approvals needed:
Risks/required gates:
```

## Release gates

| Gate | Evidence | Current status | Owner |
| --- | --- | --- | --- |
| Frozen candidate and shipped feature scope | source/artifact identity | PENDING | human/orchestrator |
| Original fix closure + regression | independently verified ledger | PENDING | verifier |
| Whole-app pipeline/engine/infra coverage | read/test/runtime matrix | PENDING | planners/auditors |
| Confirmed defect remediation | reviewed tests and matched runs | PENDING | coder/reviewer |
| Money/privacy/lifecycle/Room/worker/guard gates | guardian reports | PENDING | guardians |
| Comprehensive unit/integration/static/lint evidence | durable runner records | PENDING | validation-runner |
| Fresh-install/upgrade/backup/restore/device evidence | current matrix | PENDING | tester/human |
| Optimized artifact/production-signing process | safe provenance/smoke | PENDING | human/runner |
| Controlled beta/performance/accessibility | measured observations | PENDING | human/tester |
| Independent final recommendation | evidence-backed verdict | PENDING | final reviewer |
| Human release decision | explicit approval | PENDING | human |

CONDITIONAL, missing required proof or a failed guard never means release-ready.
Keep any scoped human P2/P3 deferral explicit; no safety/guard waiver or silent
feature deletion. Do not certify zero bugs.
