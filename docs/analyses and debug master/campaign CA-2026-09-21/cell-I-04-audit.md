# I-04 — Static guardrails and CI — static audit

Date: 2026-09-22
Pinned commit: `37601232b9778170c57a656a245b199ab6d7d965`
Auditor: **direct astra session**
Cell: **I-04**
Mode: AUDIT, static only. No builds, tests, guard execution, or production edits.
Status: **PARTIAL — bounded static discovery finished; the entire cell is not audited end-to-end.** Seven new discovery findings (7 P2; 0 P0/P1/P3). Independent adversarial verification remains pending. No validation PASS is claimed.

## Provenance and scope

- `git rev-parse --short HEAD` returned `37601232`.
- `git diff --stat 37601232..HEAD -- app config scripts` was empty.
- Additional worktree check `git diff --stat 37601232 -- app config scripts .github` was empty. Expected documentation and agent-configuration drift is excluded.
- Governing prompt §2 and §4 read before code. I-04 row in `COVERAGE_MATRIX.md` read first, followed by exact `LEGAL_PATHS.md` sections: Expense Mutations, Workers / Background Jobs, Privacy / Cloud AI, Diagnostics, Group Mutations.
- Read segment 39 plus relevant segment 29/30/32 entries; inventory's repo-level CI tooling entry; only matching engine-map rows for WorkerExecutionGuard, PrivacyGate/CloudPayloadPolicy, GroupTransactionCoordinator.
- Known-debt reconciliation sources: registry guard/cancellation/barrier entries; remediation README exclusions, WAVE-1-STATUS guard/GR-15 entries, RP-00 recorded decisions. U-002/U-003/U-004 and P4-002 naming-related guard issues are tracked and will not be restated.
- Acceptance criteria referenced by path and FG-ID: `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` (especially FG-02/03/05/06/07/08/09/23). It is intent, not proof of implementation.

## Coverage ledger

Full-file source/config read count: **20 / 20** (14 source files + 6 baseline JSON files). Documentation excerpts and inventory-only scans do not count as full source reads. Each entry below records the actual depth; an inventory entry is not a claim of semantic coverage.

1. `scripts/ci/guard_registry.py` — full, 1–897 (chunked; truncated spans re-read). Registry modes, 23 in-suite guard declarations plus two external declarations, command arguments, required inputs, protocols, baseline paths, test declarations and file-existence validator.
2. `.github/workflows/ci.yml` — full, 1–493. Traced trigger → static suite; separate two-run evidence capture; Kotlin/unit/Gradle check; external currency/release guards; conditional emulator/migration jobs. No inference about remote branch-protection settings.
3. `scripts/ci/verify_guard_registry.py` — full, 1–364. Checks registered files and registry-derived plan completeness, external exclusion, duplicate/order checks and AST-based command-authority checks. Does not independently discover all filesystem guards.
4. `scripts/ci/run_static_guard_suite.py` — full, 1–969 (chunked; 391–539 re-read). Registry/order reconciliation, infrastructure legs, subprocess exit classification, timeout, artifact persistence, aggregate exit. Guard commands run serially; failed legs do not stop following legs. Test selection is hard-coded globs, separate from `testManifest`.
5. `scripts/verify_cloud_payload_boundaries.py` — full, 1–276. Followed source enumeration → R1/R2 → allowlist filtering → exit; R2 uses raw whole-file policy markers, no payload data flow.
6. `scripts/verify_worker_boundaries.py` — full, 1–453. Followed worker detection, file-wide guard/return markers, DAO-name matcher, catch checker, exact allowlist matching and exits. Not a proof of barrier dominance or callback scope.
7. `scripts/verify_privacy_boundaries.py` — full, 1–571 (255–299 re-read). All G1–G14 implementations, function-window helper, semantic package filter and fail-closed read/enumeration behavior. G3 uses raw text markers; G14 ignores providers without a declared gate dependency.
8. `scripts/verify_event_writers.py` — full, 1–291. Entity construction and direct DAO rules, basename/substring exemptions, plain-text allowlist, source enumeration and ratchet output; does not prove an event and mutation share a transaction.
9. `app/src/test/java/com/yourname/expensetracker/architecture/WorkerGuardArchitectureGuardTest.kt` — full, 1–138. Worker discovery, raw guard marker and empty/stale/redundant allowlist tests.
10. `scripts/ci/shared_guard_engine.py` — full, 1–521. YAML validation, exact optional symbol matching, duplicate fingerprint implementation, file read errors, comment stripping, source discovery and JSON persistence. Caller search found only `verify_deprecation_escalations.py` importing `find_source_files`/`safe_read_file`; did not treat unused shared utilities as live detector defects.
11. `scripts/ci/run_registered_guard.py` — full, 1–478. Registry compile → validation → shell-free subprocess → exit mapping → atomic summary. CI override rejection and summary-write failure preserve failure; timeouts are delegated to callers.
12. `scripts/verify_allowlist_compliance.py` — full, 1–436. YAML format normalization, missing/malformed handling, expiry parsing, grace rules, text format and CLI result.
13. `scripts/verify_cancellation_boundaries.py` — full, 1–536. Suspend/catch scans, sibling catch handling, three rules and shared allowlist matcher, source enumeration, read-failure tracking and CLI exits. Did not restate U-002's related Kotlin guard issue.
14. `scripts/verify_money_boundaries.py` — full, 1–445. Financial path scoping, exclusions, line/multiline rules, structured comment exemptions, normalization/fallback checks and CLI. This is heuristic coverage, not arithmetic or currency data-flow proof.
15–20. `config/baselines/{cancellation,privacy,event_writers,money,migration_matrix,db_access_v2}.json` — full. Populated file-level v1 fingerprints for cancellation/privacy/events; empty money/migration lists and empty count-aware v2 DB entries. Baseline entries are existing debt, not findings.

Targeted reads (not full-file coverage):

- `scripts/ci/capture_db_guard_evidence.py` 2131–2181 (eleven explicit focused test modules), 3741–3792 (mandatory base/head arguments), plus symbol/caller searches.
- `scripts/ci/test_ci_workflow_evidence_gate.py` 61–109: positive workflow assertions omit mandatory base-ref. `scripts/ci/test_run_static_guard_suite.py` 707–734,781–815 plus test-command expectations/searches: timeout failure assertion and protocol metadata checks; test command is pinned to two glob arguments.
- `scripts/ci/guard_ratchet.py` 211–350,519–539,1331–1368,2113–2236: legacy fingerprint extraction/acceptance and v2 count comparison. Loader/error branch symbol searches only; **not a full ratchet audit**.
- `scripts/verify_db_access_boundaries.py` 3528–3557,3601–3626,3651–3817: active entry → Room inventory → typed v2 policy/evidence → structural manifest → D4 scanner → trusted/untrusted report → exit. The scanner and policy engines themselves remain uncovered end-to-end.
- `scripts/verify_time_boundaries.py` 784–912 and function/masking/exception searches: detector uses masked Kotlin text and scope stacks; read/masking errors raise instead of returning an empty result. Masker, alias-resolution limitations and exception parser not fully audited.
- `scripts/guardrails/production_source_scope.py` readable-file/topology/enumeration searches (not full read). Existing readability checks mitigate simple unreadable-input cases; did not elevate every downstream read-error suppression to a separate finding.
- `scripts/test_verify_cloud_payload_boundaries.py` 96–190 and test names; `scripts/test_verify_privacy_boundaries.py`, `scripts/test_verify_worker_boundaries.py`, `scripts/test_verify_cancellation_boundaries.py`, `scripts/test_verify_allowlist_compliance.py`: targeted test-index/marker/allowlist checks, not all fixture bodies.
- `app/src/test/java/com/yourname/expensetracker/domain/privacy/PrivacyGuardTest.kt` 1–90 and privacy-rule/call searches; `scripts/db_guard/structural_analysis/test_barrier_proof.py`, `scripts/guardrails/test_production_source_scope.py`, `scripts/test_db_guard_policy_v2.py`: named negative-case/test-function inventory, not full reads.
- `scripts/allowlists/cancellation_allowlist.yml` 23–45 and rule/path/symbol/expiry search; cloud allowlist metadata search. `config/guards/production_source_roots.yml`, `worker_root_dispositions.yml`, `time_boundary_exceptions.yml`, `db_structural_exceptions.yml`, `db_ownership_policy.yml`: relevant schema/disposition/barrier/owner metadata hits only.
- Remaining detector siblings received function/exclusion/IO-oriented scoped searches where noted in the inventory discussion; none is implicitly marked end-to-end covered.
- `docs/ci/guard-policy.md` fail-closed, exact-symbol and test contracts. Documentation and Git provenance reads added no source execution.

Initial inventory: enumerated `scripts/ci/`, `scripts/verify_*`, `scripts/guards/`, `scripts/guardrails/`, `config/guards/`, `config/baselines/`, `.github/workflows/` and guard test filenames. Source contains 20 top-level `verify_*.py` files and 7 baseline JSON files; many auxiliary DB parser/proof components require separate bounded traversal.

## Findings

### CA-I-04-001 | Evidence Gate invokes a CLI without its required base pin | defect class 13 | severity P2

- **Evidence at pin:** `.github/workflows/ci.yml`, `evidence-gate` capture steps, 156–164; `scripts/ci/capture_db_guard_evidence.py`, `main`, 3768–3788, particularly required `--base-ref` at 3779–3783. Workflow supplies only root, expected SHA and output directory.
- **Impact path:** CI trigger → successful compile → evidence-gate run 1 → argparse rejects missing `--base-ref` with exit 2 before any evidence capture → run 2/comparison cannot produce the required proof. Automatic CI evidence is broken for every invocation reaching this step; this is a wiring failure, not evidence of application data loss.
- **Caller trace:** `.github/workflows/ci.yml:160–164` → `capture_db_guard_evidence.py:main` → `parse_args` (capture never reached).
- **Existing tests/guards:** `scripts/ci/test_ci_workflow_evidence_gate.py` checks `--expected-sha` and workflow structure; its source search has no `base-ref` check. CLI pin validation is fail-closed, but the caller has not followed its contract. No tests executed.
- **Cross-cell impact:** I-02 persistence evidence and all DB-owner/barrier consumers lose automated capture proof.
- **Old-ID cross-refs:** none found in the supplied registry/remediation sources. Separate from tracked GR-15 unresolved observations and stale DB policy fixtures.

### CA-I-04-002 | Cloud payload guards accept a comment in place of policy execution | defect class 9 | severity P2

- **Evidence at pin:** `scripts/verify_cloud_payload_boundaries.py`, `scan_file`, 160–179 and `POLICY_MARKERS_RE` 77–80; `scripts/verify_privacy_boundaries.py`, `_enclosing_function_text` 124–143 and `rule_g3_raw_request_post_in_provider` 202–229. R2 searches raw whole-file content for policy names; G3 searches raw nearby text for substrings such as `prepared` and `cloudPayloadPolicy`. Neither requires executed preparation nor a dependency between the prepared payload and posted body.
- **Static counterexample (not executed):** in a provider's `send(raw: String)`, put `// cloudPayloadPolicy prepares this request` immediately before `Request.Builder().post(raw.toRequestBody()).build()`. R1 does not match extension `toRequestBody`; R2's `has_policy` is true; G3's marker predicate is true. Both checks therefore emit no finding for this raw-body bypass. Keeping an injected policy unused or calling it only in a separate path is also enough for the whole-file rule.
- **Impact path:** provider change → suite cloud guard and privacy ratchet → neither reports this missing preparation → unsafe cloud body escapes these promised enforcement checks. This is an enforcement defect; no current provider leak or overall CI PASS is asserted.
- **Caller trace:** `.github/workflows/ci.yml:239–241` → `run_static_guard_suite.py:_derive_default_suite_plan/main` → registry `cloud_payload`/`privacy` → detector functions above.
- **Existing tests/guards:** `scripts/test_verify_cloud_payload_boundaries.py:96–140,145–186` covers compliant fixtures with actual preparation and clean markers. No comment-only/unused-policy counterexample appeared in the inspected tests. `PrivacyGuardTest.kt` source searches show other privacy contracts, not proof of posted-body provenance. No tests executed.
- **Cross-cell impact:** P-08 AI and cloud consumers P-03/P-10; privacy shared surface ownership remains P-08.
- **Old-ID cross-refs:** none. U-002 concerns a different Kotlin cancellation guard and is not re-reported here.

### CA-I-04-003 | Protocol-v1 ratchets allow new violations inside already baselined files | defect class 12 | severity P2

- **Evidence at pin:** `scripts/ci/guard_ratchet.py`, `extract_fingerprints`, 274–350 (set at 294, path-only insertion at 324–334); `compare_fingerprints`, 519–533; `main` legacy branch 2151–2202. Line numbers and multiplicity are discarded; acceptance compares only sets of rule/file pairs. `scripts/verify_event_writers.py:274–279` prints `[DAO]` or `[ENTITY]` as the bracketed key, so the ratchet also discards the specific event rule after that prefix.
- **Static counterexample (not executed):** keep an existing `G-CANCEL-01` finding in baselined `ReceiptRepository.kt`, then add another detected `G-CANCEL-01` site in that file. Both collapse to the same fingerprint. `new` and `resolved` remain empty and the ratchet returns 0. For event writers, one baselined DAO site covers additional detected DAO event types in that file as well.
- **Impact path:** new unsafe site in a baselined file → detector emits additional finding → v1 set conversion erases growth → ratchet reports unchanged/PASS. Existing populated cancellation and event-writer baselines make the gap active at the pin. It does not affect the distinct count-aware DB v2 contract.
- **Caller trace:** CI static suite → registry cancellation/privacy/event_writers/money/migration_matrix v1 plans → `guard_ratchet.py:main`. Empty money/migration baselines do not currently provide this bypass until a fingerprint exists.
- **Existing tests/guards:** `scripts/ci/test_guard_ratchet.py` and `test_guard_ratchet_v2.py` exist and are selected by the suite. V1 behavior is explicit in source; no execution performed. Separate Kotlin architecture tests may catch some offending changes; this finding is specifically the no-growth ratchet's false negative, not a claim that every other gate also passes.
- **Cross-cell impact:** cancellation across pipelines, diagnostics/lifecycle event writers, privacy; I-02 DB v2 is excluded from this defect.
- **Old-ID cross-refs:** none. Accepted individual baseline debts are not new findings; the failure to detect additional sites is.

### CA-I-04-004 | CI omits dedicated guard parser, barrier-proof and source-scope test modules | defect class 14 | severity P2

- **Evidence at pin:** `scripts/ci/run_static_guard_suite.py`, `SUITE_INFRASTRUCTURE_LEGS`, 174–180, selects only `scripts/test_verify_*.py` and `scripts/ci/test_*.py`; `_expand_globs`, 500–536, expands only these specified directory patterns. `scripts/ci/capture_db_guard_evidence.py`, command matrix, 2143–2168, adds eleven exact test modules, not recursive guard-test discovery.
- **Concrete missing modules:** `scripts/db_guard/structural_analysis/test_barrier_proof.py`, `scripts/guardrails/test_production_source_scope.py`, `scripts/test_db_guard_policy_v2.py` match neither CI test selection. These contain counterexamples for barrier-after-write, wrong receiver/fake barrier, unreadable/symlinked production scope and malformed/wildcard policy keys. `testManifest` metadata does not drive the suite's test command.
- **Impact path:** change to proof/scoping/policy engine → CI runs selected tests but never these dedicated modules → their regression assertions have no CI effect. Some related integration cases do run; the missing unit surfaces are not equivalent to zero testing of these engines.
- **Caller trace:** workflow Static Guards → suite `guard_tests`; workflow Evidence Gate → capture focused tests (currently independently blocked by CA-I-04-001). No other Python test runner was found in the only workflow or Gradle wiring searches. No import-based execution path for these modules was found in production scripts searched.
- **Existing tests/guards:** the omitted tests exist; the suite test pins the current two glob arguments (`scripts/ci/test_run_static_guard_suite.py`, manifest expectations/search). Registry validation checks test-file existence, not collection completeness. No tests executed.
- **Cross-cell impact:** I-02 and all legal-writer/barrier consumers, plus every guard using the production source enumerator.
- **Old-ID cross-refs:** none. Distinct from tracked Kotlin `DbGuardPolicyFixtureTest` drift.

### CA-I-04-005 | Both worker architecture checks accept comment-only guard usage and miss qualified supertypes | defect class 10 | severity P2

- **Evidence at pin:** `scripts/verify_worker_boundaries.py`, `COROUTINE_WORKER_RE`/`GUARD_INVOCATION_RE`, 72–76 and `scan_file` 214–225,279–296; `app/src/test/java/com/yourname/expensetracker/architecture/WorkerGuardArchitectureGuardTest.kt`, supertype/guard regexes 59–66, `discoverCoroutineWorkerFiles` 68–75, primary assertion 88–99. Both match raw source text, not executable calls, and discover only literal `: CoroutineWorker`.
- **Static counterexamples (not executed):** a normal CoroutineWorker whose `doWork` returns `Result.success()` after unguarded work, with `// runGuarded( ... toWorkerResult(` in its file, satisfies the Python guard/return markers and Kotlin guard marker without executing the guard. Alternatively `: androidx.work.CoroutineWorker(...)` is missed by both discovery regexes; the Kotlin nonempty assertion still succeeds because existing workers remain discoverable.
- **Impact path:** worker addition/refactor → both dedicated checks accept/ignore it → restore/lease/privacy/diagnostic worker entry contract can regress without these checks reporting it. No current unguarded production worker is asserted, and DB mediation may independently reject a particular DB-writing variant.
- **Caller trace:** CI static suite → registered worker Python guard; CI unit-tests → `WorkerGuardArchitectureGuardTest` → regex checks above.
- **Existing tests/guards:** the Kotlin file's three tests cover discovered workers and allowlist hygiene; all use the same raw regex, with no fixture proving comment/qualified-supertype rejection. Python `scripts/test_verify_worker_boundaries.py` received the targeted inspection below. The empty worker allowlist does not close a discovery/evidence bypass.
- **Test follow-through:** Python test-name/guard searches found positive guard use, missing guard, direct DAO mutation, partial allowlist, missing manifest and declared-source scope cases, but no comment-only or qualified-supertype negative fixture in that file.
- **Cross-cell impact:** P-09 and all workers in other pipelines; shared WorkerExecutionGuard owner remains P-09.
- **Old-ID cross-refs:** none. U-004 is the older DAO receiver-alias/write-barrier issue, not these worker guard-entry checks.

### CA-I-04-006 | Cancellation exemptions silently authorize other rules and symbols | defect class 5 | severity P2

- **Evidence at pin:** `scripts/verify_cancellation_boundaries.py`, `scan_file`, 298–300,343–345,370–372, always passes the empty symbol for all three rules; `is_allowlisted`, 420–443, compares each entry's rule to global `RULE_ID` (`G-CANCEL-01`, line 44) rather than the active rule, and treats an empty observed symbol as a match for any declared symbol. Current `scripts/allowlists/cancellation_allowlist.yml:23–45` has explicitly named G-CANCEL-01 exemptions for worker infrastructure, including `broadCatch`.
- **Static counterexample (not executed):** add a suspend `runCatching { suspendOperation() }` in a different method of an existing G-CANCEL-01-allowlisted file. The G-CANCEL-02 loop calls the same matcher with an empty symbol, accepts the G-CANCEL-01 entry and suppresses the new violation before the ratchet sees it. A correctly scoped G-CANCEL-02 entry would conversely be ignored because the matcher checks against G-CANCEL-01.
- **Impact path:** narrow rule/symbol exemption → scanner widens it to the entire file and all cancellation rules → new swallowing sites receive no Python cancellation finding. The entry's owner/expiry metadata remains valid and therefore cannot expose the widening.
- **Caller trace:** CI static suite → registry cancellation → ratchet child → `scan_file` → `is_allowlisted`.
- **Existing tests/guards:** `scripts/test_verify_cancellation_boundaries.py` includes an allowlisted-file test and per-rule detection tests, but inspected matches show no exact rule/symbol isolation assertion. `docs/ci/guard-policy.md` requires exact violation-symbol matching. Independent Kotlin cancellation guard coverage is not claimed bypassed.
- **Cross-cell impact:** P-09 worker infrastructure and any other cancellation-allowlisted pipeline.
- **Old-ID cross-refs:** none for this matcher defect. `MIT-035` labels the existing allowlist entries, not a discovered cross-rule matching issue; U-003 concerns different Kotlin expiry handling and is excluded.

### CA-I-04-007 | Allowlist compliance reports success when it cannot parse its inputs | defect class 12 | severity P2

- **Evidence at pin:** `scripts/verify_allowlist_compliance.py`, `_load_yaml_data` 160–174, `parse_db_access_allowlist` 177–196, `check_yaml_allowlist` 216–220, `main` 383–397,425–432. YAML exceptions and missing parser return `None`, transformed to an empty entry list and zero violations; missing configured files are skipped; the CLI prints `PASS` and exits 0 when no other violation exists.
- **Static counterexample (not executed):** malformed YAML in a configured allowlist reaches the generic parse-error branch, then `[]`, then the no-entries warning; even `--fail-on-violation` cannot turn this infrastructure error into failure. A structurally wrong top-level scalar or list containing non-dictionary entries is similarly ignored. This contradicts `docs/ci/guard-policy.md` fail-closed rules and FG-03.
- **Impact path:** corrupt/unparseable exemption metadata → compliance meta-guard silently loses the entries it must check → misleading successful compliance result. Some per-domain guards separately reject malformed YAML; this finding does not assert the entire suite passes in every malformed-file case.
- **Caller trace:** Static Guards → registry `allowlist_compliance` → `main` → `check_yaml_allowlist` → loader/normalizer → exit 0.
- **Existing tests/guards:** `scripts/test_verify_allowlist_compliance.py` inspected test index covers reason/owner/expiry/wildcard/text metadata and violation exit, not the missing/malformed infrastructure contract. Registry file-existence checks mitigate some missing files but do not validate YAML contents. Pre-2026-10-01 owner/wildcard grace and explicitly warning-only permanent-expiry behavior are not reported as new defects.
- **Cross-cell impact:** all guards consuming the checked allowlist files; no app runtime mutation is alleged.
- **Old-ID cross-refs:** none found in the baseline sources.

## All-15-class disposition

Every class was considered against the inspected surface. **Considered is not equivalent to fully covered.** The following records both evidence and limits; no absence claim extends to the uncovered files.

| Class | Static check and disposition |
|---|---|
| 1 Legal path | Registry and active DB CLI use exact v2 ownership/source evidence before D4 matching; event guard enforces selected constructors/DAO receivers heuristically. Followed guard entry to scanner/report wiring; actual expense/group coordinator → DAO → transaction/event implementation is owned by other cells and was not re-audited. No additional app bypass asserted. |
| 2 Barrier | DB CLI fails its pre-scan stages closed; dedicated barrier-proof tests are omitted by CI selections (004). Worker guard-entry proof is evadable (005). Full D4/barrier CFG/mediation analysis remains uncovered. |
| 3 Atomicity / TOCTOU | Suite execution-plan and registered-runner summary use temporary files plus replace; suite aggregate JSON/MD is written only after all legs and is not incrementally durable. Event constructor/DAO checks do not establish same-transaction event writes. No new atomicity defect promoted without a complete consumer trace; runtime DB atomicity remains outside the covered slice. |
| 4 Idempotency / duplicates | Examined registry uniqueness/order and v1 fingerprint deduplication: collapsing occurrences defeats growth enforcement (003). DB v2 explicitly compares occurrence counts. Did not infer runtime duplicate expense creation. |
| 5 Cancellation | Python broad-catch/runCatching/onFailure rules read fully; exact rule/symbol suppression is broken (006). Known U-002/U-003 Kotlin issues excluded. Subprocess timeouts map to infrastructure failure; descendant process cleanup was not proven. |
| 6 Side-effect timing | Guard runner persists outputs after child completion; registered summary write failure cannot convert failure to success. Event guard checks selected writes/construction, not commit ordering. No proof offered for app post-commit dispatch; that traversal remains for the owning cells. |
| 7 Money / currency | Money detector fully read: currency-unavailable sentinels, normalization, stale-rate and fallback rules, financial-path exclusions and comment exemptions. Empty money baseline inspected. No runtime arithmetic recalculation or full raw-aggregate detector review; heuristic limitations recorded without extra speculative findings. |
| 8 Time correctness | Suite duration uses `time.monotonic`; summary timestamps use UTC. Allowlist grace uses host calendar date; v2 expiry uses UTC date. Time detector entry uses masking/scope attribution (targeted read). No new time finding promoted; alias/exception parser coverage incomplete. |
| 9 Privacy | Posted-body provenance can be faked by comments (002). Read error handling, raw source snippets/log outputs and bounded adapter summaries were inspected; CI source diagnostics are not assumed to contain user financial payloads. No actual production privacy leak asserted. |
| 10 Worker hygiene | Both worker checks accept raw markers/miss qualified subtype spelling (005); current allowlist is empty. Retry, permission and metrics semantics inside runtime workers were not re-audited. |
| 11 Data integrity | Active DB CLI loads Room inventory and exact policy evidence before authorization; baseline schema split reconciled. Migration verifier and emulator migration command received inventory/wiring review only, not migration semantics. No new data-loss claim. |
| 12 Error handling | Registry/runner unknown exits and timeout fail closed; allowlist compliance does not (007); v1 ratchet erases newly detected instances (003). Shared helper weak paths were checked for callers before deciding whether to report them. |
| 13 Wiring / dead code | Mandatory base argument missing at workflow caller (001). Distinct external guards are explicitly wired to unit/release jobs. Shared utility functions are mostly unused by inspected guard callers; no hypothetical runtime severity assigned. |
| 14 Test correctness | CI test selections omit dedicated negative-case suites (004). Read fixture assertions and workflow contract tests around findings; no tests were run. Other tests remain inventory-only. |
| 15 Fix-regression | Git history for capture and workflow was inspected to establish they evolved separately; candidate 001 is grounded in their incompatible contracts at this pin. No new finding is attributed to an RP wave without an adequately scoped before/after source comparison. Known RP-01/RP-02 debts and current GR-engine diagnostic debt are not relabeled new regressions. |

## Inventory reconciliation (byproduct)

Filename enumeration is persisted in **`cell-I-04-inventory.md`**, alongside this report: **218 tracked paths**, including **71 `scripts/ci/` files**, **20 top-level `scripts/verify_*.py` files**, **30 `config/guards/` files**, **7 baseline JSON files**, plus scoped tests, allowlists, workflow and adapters. This is a scoped inventory, not a complete repository or DB proof dependency graph.

- Registry declares **25 guards**: **23** in the static suite and **2** external (`currency_guardrails_ps`, `release_artifact`). Suite adds two infrastructure legs, registry validation and guard tests. All 20 top-level `verify_*.py` scripts have registry declarations; `release_artifact` runs in the separate release job. Additional registered CI verifiers and the DB policy migration tripwire account for the rest.
- `config/baselines/db_access_v2.json` is active for DB enforcement. `db_access.json` remains a tracked legacy artifact, not an undiscovered active second DB baseline. The other five active ratchets use v1 fingerprints.
- `config/guards/` contains active policy, structural, source-root, time and worker disposition inputs together with multiple `db_ownership_policy.legacy.*` snapshots, a signatures candidate/accounting pair and promotion metadata. Enumeration does not make the legacy snapshots active policy. Only referenced schema/ownership snippets were semantically inspected.
- Guard tests extend beyond `scripts/test_verify_*.py` and `scripts/ci/test_*.py`. The source-scope, DB structural/mediation and `scripts/test_db_guard_*` families are real ownership surfaces; CI's narrower selection is finding 004. Some DB parser tests are added by the evidence capture's explicit list, so they were not all labeled absent.
- `scripts/guards/*.kts`, `scripts/guardrails/dao-access-check.kts` and `tools/lifecycle-bypass-guard.groovy` are tracked adapters/legacy surfaces. Their names alone do not establish independent live enforcement; their internal behavior remains unreviewed here.
- No remote branch-protection/CODEOWNERS approval assurance or full FG-23 self-protection claim can be made from this local static pass. No repository validation was used for this reconciliation.

## Remaining coverage

**This cell is incomplete.** Stopped at the user's 20-full-file source/config budget; no context compaction occurred. The report preserves all findings obtained before that limit and targeted follow-through.

Priority continuation (read the report ledger before opening code):

1. `scripts/verify_db_access_boundaries.py` in full and its actual `scripts/db_guard/scanner.py`, Room inventory, typed policy/evidence, Kotlin callable parser, structural CFG/barrier proof, mediation and worker recognition dependencies; GR-15 acceptance wiring and its guards. The large acceptance/promotion/capture implementation was not fully read.
2. `scripts/ci/guard_execution_plan.py`, `guard_findings.py`, `guard_ratchet.py` v2 loader/execution/report paths, complete `capture_db_guard_evidence.py`, `verify_known_good_state.py`, source-root/docs-truth/deprecation/policy-evidence verifiers. Only explicitly listed ranges/searches are covered.
3. Remaining verifier files end-to-end: UI→DAO, receipt link, import lifecycle, source provenance, PII logging, DI release, raw money aggregates, time boundaries, migration matrix, ignored-test budget, lint baseline and release artifact. Their registry presence is reconciled, not their correctness.
4. Full production-scope enumerator, active guard configurations/structural expected-method manifest, suppression/expiry semantics, legacy snapshot dispositions and baseline integrity versus a trusted base. A complete FG-06/07/23 comparison has not been performed.
5. Remaining guard tests, especially source-scanning helpers, current Kotlin cancellation/write-barrier/recurring/event/privacy guards and Python negative fixtures. Runtime workers, coordinators, DAOs, lifecycle events and post-commit side effects require owning-cell evidence; this report does not certify those legal paths end-to-end.

No builds, tests, guards, fixture executions or Python module imports were performed. Verification used Git identity/diffs/history, file enumeration, text searches and static reads only. Findings are ready for a different auditor's Phase 2 verification; they are not marked independently confirmed.

## Closing provenance

Agents invoked: none (user requested direct cell auditor).
Pinned commit: `37601232b9778170c57a656a245b199ab6d7d965`.
Timestamp: 2026-09-22T08:29:08Z (closing inventory/provenance observation; journal records append time).
