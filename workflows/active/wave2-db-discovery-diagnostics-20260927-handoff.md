# Wave 2: discovery-diagnostic adjudication and visibility repair

Date: 2026-09-27. **VERDICT: FAIL for db_access / Wave-2 closure.** The narrowly scoped diagnostic-reporting repair below is **IMPLEMENTED_UNVERIFIED**. The four discovery blockers are NOT_FIXED; this report does not claim to resolve them.

## Correction to the incoming diagnosis

The proposed eight-identity repair was not supported by the preserved evidence. The test assertion helper displayed only the first eight diagnostic codes because MAX_ITEMS was eight. The same JSON explicitly reported diagnosticCount 26. Reading the original subprocess JSON established **four blocking diagnostics and twenty-two advisory diagnostics**, not eight defective policy entries.

The first eight preview entries comprised three blockers and five advisories. The fourth blocker, DatabaseBackupRepositoryImpl, was the ninth diagnostic and was omitted from that preview. The advisory signature diagnostics do not mean five new policy signatures are wrong.

The source-evidence spy reported failedGroupCount 0 and omittedGroupCount 0: the eleven original policy groups cleared. The missing failed-group detail was not a changed nesting scheme; this is now the later discovery stage, whose diagnostics live in the main report array. No policy entry should be rewritten merely to silence these codes.

## Verified human validation evidence

- Run: `vr-20260927-183237-704e43a6`; static-guards; completion marker present; FAIL / exit 2 / E_COMMAND_FAILED.
- Started 2026-09-27T18:32:37.8629660Z; finished 2026-09-27T19:07:04.8358080Z; elapsed 2,062 seconds.
- Recorded command: `python.exe scripts/ci/run_static_guard_suite.py --output-dir build/ci/static-guards`.
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`. Matching start/end fingerprint: `bcd0ca744ab157c38b57225f407c4f502b9a5d031fbe4a5be29742e704ef3ee1`.
- Suite: 18/25 PASS, five blocking violations, two infrastructure errors. Pytest: 3 failed / 4,518 passed / 28 skipped.
- All 54 cases in scripts/test_wave2_db_ownership_reconciliation.py were individually found PASSED, with no nonpassing entries.
- The three failing tests remain test_current_db_gate_activated_policy_real_config_pipeline, test_default_project_root_uses_canonical_manifest, and test_fixture_manifest_mismatch_is_fail_closed_and_production_defaults_stay_strict.
- Guard log: `build/ci/static-guards/guard_tests.log`, diagnostic summaries at lines 4564, 4571 and 4577; SHA-256 `6a79dba420cedbe93bb5565f3b4b9129bd8c0d30f12c8b1b0908789858487550`.
- Suite summary: `build/ci/static-guards/summary.json`, SHA-256 `a0aaed921cd4586d9cea9f8923875521c48594823eb02746b161e734f447505f`.
- Full subprocess report: `C:/Users/panos/AppData/Local/Temp/pytest-of-panos/pytest-666/real-tree-scan0/real-tree.json`, SHA-256 `b0a7da291004c5a15e63829d417f29631f348ae94b14eeb5157229e6690b8d7c`. Read with approved read-only access after sandbox access was denied. No new scan was executed.
- The full report is trusted=false and findingCount=0. An untrusted scan with provisional findings discarded is NOT proof that the tree contains no unauthorized mutations.

## Complete blocking inventory

| Diagnostic | Source file | Disposition |
|---|---|---|
| `DB_CALL_TARGET_AMBIGUOUS` | `app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt` | NOT_FIXED; exact failing call not recorded in the preserved report |
| `DB_DAO_SCOPE_UNRESOLVED` | `app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt` | NOT_FIXED; exact failing call not recorded in the preserved report |
| `DB_DAO_SCOPE_UNRESOLVED` | `app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt` | NOT_FIXED; exact failing call not recorded in the preserved report |
| `DB_SIGNATURE_UNRESOLVED` | `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt` | NOT_FIXED; exact failing call not recorded in the preserved report |

These are four deduplicated code/path records, not proof of exactly four failing calls. The old scanner collapsed identical path-only emissions. Once source coordinates are retained, the diagnostic count may increase without any new source defect being introduced.

DB_CALL_TARGET_AMBIGUOUS is also emitted for a known but mismatched argument tuple with no unique match; the code alone does not prove that multiple overloads exist. DB_DAO_SCOPE_UNRESOLVED concerns discovery of the actual receiver, not necessarily a missing accessor in an ownership-policy entry. Both scope records name files not edited in the preceding policy reconciliation.

### Complete advisory inventory

All twenty-two records below carry DB_SIGNATURE_UNRESOLVED with controlled_context.advisory exactly true. That existing classification was not added or broadened by this repair:

- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/OnDeviceQueryInterpretationService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/StrictAiJsonParsing.kt`
- `app/src/main/java/com/yourname/expensetracker/data/email/provider/EmailReceiptParser.kt`
- `app/src/main/java/com/yourname/expensetracker/data/location/internal/CancellableHttpCall.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerSpecScheduler.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/components/BentoCard.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/components/RetroBudgetBlockPartyCard.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/components/RetroTopCategoriesCard.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/components/RetroTotalsDashboardCard.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/components/analytics/PersonalityProfileCard.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/components/feature/FeatureComponents.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/components/feature/FormComponents.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/components/health/HealthScoreWidget.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/navigation/NavigationController.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/aisettings/AiSettingsScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/challenge/SpendingChallengesScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/map/SpendingMapScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionFilterSheet.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/theme/Theme.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/util/ModifierExtensions.kt`

## Source review and scope decision

- Read AGENTS.md, scope/fix-ci/review-strict guidance, the architecture router and Segment 39 ownership, the current handoff, the actual failed reports, scanner emission/resolution paths and the four affected source files. Worked alone under the explicit no-subagents instruction.
- Reviewed representative real operations, including restore preference chains, startup asset restoration and DAO update, recurring reconciliation and its current CAS-only dirty diff, and the generic backup export/restore helpers. The path-only evidence cannot safely select which operation caused each diagnostic.
- The source-evidence stage and discovery stage use distinct checks. Passing the former and 54 reconciliation regressions does not prove discovery correctness. Conversely, a later discovery diagnostic does not prove that a newly added ownership row is incorrect.
- No speculative accessor/signature edits, resolver permissiveness, production refactor, exception addition or policy regeneration was made. Precise call/declaration evidence is required before the next semantic repair.

## Diagnostic-only changes made

1. `scripts/ci/guard_test_diagnostics.py`: retain bounded code previews but expose their omission count; separately report blocking/advisory totals and bounded blocking identities. Compact callers get eight blocking rows; the existing explicit expanded_evidence=True option gets up to sixty-four. Advisory rows cannot crowd blockers out of the preview.
2. The reporter copies only controlled diagnostic codes, validated repository-relative Kotlin paths and bounded positive integer coordinates. It does not copy raw streams, arbitrary contexts, source text, signatures, exception messages or absolute source paths. Malformed or merely truthy advisory values are not treated as true.
3. `scripts/db_guard/scanner.py`: attach bounded source coordinates at unresolved DAO receiver, argument/overload and structural-signature emission sites. For an owner-wide parser failure, the coordinate identifies the affected declaration, not necessarily the malformed sibling header that caused the parser to fail.
4. No resolution condition, authorization match, trust predicate, exception, discovery selection or exit-code decision changed. All unresolved cases remain blocking. Advisory-only declaration behavior remains unchanged.
5. `scripts/test_guard_test_diagnostics.py`: thirty intended new cases cover 26-vs-8 truncation, four blockers behind advisories, exact boolean classification, row caps/omissions, unsafe path rejection, coordinate bounds and malformed rows. These are authored cases, not claimed executions.
6. New `scripts/test_db_discovery_diagnostic_details.py`: seven intended source-fixture cases assert that scope/signature/overload uncertainty remains untrusted with no guessed findings, while coordinates survive and distinct calls do not collapse into one path-only record.
7. `scripts/test_db_guard_scanner_d4.py`: the existing exact negative contract now pins the known fixture declaration line (6), while retaining its failure code, empty findings and untrusted assertion.

All 37 new intended cases and the revised coordinate assertion are NOT RUN. Existing 54-case reconciliation coverage was left byte-identical. This author read-back is not independent strict/guardian approval.

## Preservation and reviewed snapshot

- Branch `bug-fixes`; HEAD `a48076322e57f3d312f34cf6a71139efc4fcc48b`; staged index empty.
- Intake: 2026-09-27T22:37:43.8786783+03:00; 169 dirty paths (109 tracked changes, 60 untracked); fingerprint `5713106313d5bea1cf80cbd44777e25b289d0eed992da42c48747defc559e6bf`.
- Reviewed post-edit/pre-handoff snapshot: 2026-09-27T23:05:08.1358512+03:00; 172 dirty paths; fingerprint `788e0b7a4b6708cd3cf3cf3e34436bafe38905ac147d20dfb430a096454aa230`.
- Hash comparison against intake found only the five source/test paths below changed or added, with no removals. All other existing work, including validator/campaign edits, was preserved. This newly created handoff is the sixth touched path and changes the final full-tree fingerprint.

| Source/test file | Reviewed SHA-256 |
|---|---|
| `scripts/ci/guard_test_diagnostics.py` | `d3838e0ddd0d91a762d8a60b0578458b0c47b6d0482c471223c7d09061408a2d` |
| `scripts/db_guard/scanner.py` | `760e5b052bf60e4961ef67910feb6d698a82508dae8f240097bb321749efe0bc` |
| `scripts/test_db_discovery_diagnostic_details.py` | `f7785af69b483b42b5257881f287bcb2d154c89956cbb3ee0965fab9e512b58c` |
| `scripts/test_db_guard_scanner_d4.py` | `562a094b337a338489bfb9e20bffb85cbd9129a062327422578a44d384ba1ae3` |
| `scripts/test_guard_test_diagnostics.py` | `5b5895aeefb9f8055d7aee2cc91772b222c1e4ebe7c402cf911d1c9af3adddf8` |

- Active policy remains SHA-256 `9d91711177bea287c44ad5f7f28d4857805f6ac0f3f66ee022a890c50e4917e2`; prior reconciliation test module remains `2343c9045340d2128b15734f81fd9a2b17ccc24c36affbfa11715fb34d1fa2a3`.
- Quiescence checks found no active validation lock, STARTING/RUNNING record or validation process before writes. Initial restricted process access was retried with approval; an inaccessible check was not treated as passing evidence.
- No production Kotlin, configuration, policy, baseline, allowlist, RawQuery pin, generated artifact, existing handoff, campaign record or git history was modified. No commits, staging, pushes, resets, stashes or branch/worktree changes. No subagents.

## Human validation / evidence collection - NOT RUN

After the required review and quiescence check, freeze the whole tree (including this report) and run one serialized full profile from the repository root:

```powershell
pwsh scripts/vrun.ps1 -Worktree . -Profile static-guards -MaxTotalMinutes 130
if ($LASTEXITCODE -ne 0) { throw "Static guards did not PASS; preserve and inspect the run artifacts." }
```

This is a diagnostic-evidence iteration, NOT a claimed fix for the four blockers: the three real-tree pipeline tests may still exit 2. Do not interpret a count change caused by coordinate-aware deduplication as a new ownership-policy regression without comparing source sites.

Require actual execution of the 37 authored cases, the prior 54 reconciliation cases and the existing suite without discovery narrowing or new skips. Capture result.json, completion marker, run ID and equal start/end fingerprints. If RUNNING persists, poll the same run ID instead of starting another run.

From the updated failure summaries preserve blockingDiagnosticCount, advisoryDiagnosticCount, omittedDiagnosticCodeCount, omittedBlockingDiagnosticCount and every reported path/line. The expanded real-tree assertion must not be mistaken for a complete inventory if omission counts are nonzero. Retain the full subprocess findings JSON before temporary-directory cleanup. Then reproduce the concrete resolver/source mismatch and make a separately reviewed minimal semantic repair; do not add exemptions or relabel blockers advisory.

Governing references: `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-03, FG-06, FG-07 and FG-23. Missing/unknown/skipped infrastructure evidence is not PASS. Protected-base/head review of this guard-metadata delta remains required.

## Still open beyond this diagnostic repair

- Four blocking discovery records and the three failing production pipeline tests: NOT_FIXED.
- Existing time_boundaries, cancellation, event_writers and raw_money_aggregates violations, plus known_good_state infrastructure failure: not repaired by this batch.
- Independent strict/guardian reviews, device/instrumented gates and protected-check acceptance remain pending.
- Prior review findings are not erased by a static-guards run: W2-R3 / CA-P-03-003 receipt/PDF partial-result propagation, WorkerRunLogger evidence adjudication and the six missing CL-09 targeted-filter records remain open as documented in the preceding handoff and independent review.
- No commit/closure decision was taken. The ownership reconciliation is verified at its source-evidence stage, not a certificate that all Wave-2 findings are fixed.
