# Release 1.0 baseline dossier (Phase 0)

```text
Workflow / phase / cell: release-1.0-assurance / Phase 0 baseline freeze / n.a.
Source candidate SHA: 84d819be6e288b82649a1c125fbcaa10f026d0d9 (local HEAD,
  pushed; origin/bug-fixes = 84d819be). HEAD moved during this session; see
  "Pin change during Phase 0". app/ content == 8fdfd205 == ea44631647e69a0dabacdc179dd094b292378b7e.
  Runner/build inputs == 14a2e6ce (performance lane), NOT the Wave 5 validated inputs.
Start/end/current worktree fingerprint: NOT COMPUTED (no runner invocation in
  this phase); worktree is dirty, see "Worktree state"
Comparison baselines: full campaign 37601232b9778170c57a656a245b199ab6d7d965 /
  Wave 5 parent 3e8377e42083c92990f32f4e46fdcb481c29cc3a
Scope and write set: campaign artifacts under docs/releases/release-1.0-assurance/
  and workflows/active/release-1.0-assurance-handoff.md only
Role, requested and actual model/effort: release-orchestrator, Opus 5.5 / high
  (main session). Two scout (Haiku 5.5/medium) T3 indexing reports feed the
  coverage plan only; they certify nothing.
Session/agent identity: main session; subagent IDs not recorded as identities
Independent of discovery/implementation: yes (no implementation in this lane)
Commands executed: read-only git (rev-parse, log, diff --stat/--shortstat,
  merge-base --is-ancestor, ls-files, check-ignore, show), gh run list,
  gh repo view, file listing/hash comparison of the Wave 5 evidence packet.
  NO Gradle, NO validation-runner, NO guards.
Date: 2026-10-14
Status: PARTIAL (see verdict)
```

## Verdict

```text
BASELINE: PARTIAL
  Wave 5 closure reconciliation ............ PASS (owner-accepted; limits below)
  Read-only static audit start ............. READY on app-source pin, subject to
                                             human approval of this dossier
  Validation / runtime / final gates ....... BLOCKED (B-1, B-2, B-3, H-4)
  Shipped 1.0 scope ........................ PENDING human confirmation (H-1)
```

This is not a release-ready statement. The candidate is the starting point for
the assurance campaign, not an accepted release.

## Pin change during Phase 0 (observed 2026-10-14 ~20:00 +03:00)

While this phase ran, three local commits by the repository owner landed on
bug-fixes. None were made by this lane, and none are pushed:

| Commit | Subject | Paths |
| --- | --- | --- |
| 14a2e6ce | perf: add measured local validation benchmarks and runner tuning | gradle.properties, scripts/validation-runner.ps1, scripts/ci/test_validation_runner_protocol.py, config/validation/test-shards.json, docs/development/{VALIDATION_RUNNER,BUILD_GUIDE}.md, perf handoff, .sisyphus plan |
| 73a22f44 | chore: add Codex and Claude agent guidance | .codex/config.toml, CLAUDE.md, .claude/agents/* |
| 84d819be | docs: add release 1.0 assurance campaign kit | docs/prompts/release-1.0-assurance/*, original release handoff (4,124 bytes, the pre-session version) |

Verified: `git diff --quiet 8fdfd205 84d819be -- app` (app unchanged). The
41 previously dirty paths are now committed, so the worktree is clean except
this lane's artifacts. Consequences:
- The static-audit pin for app source is unchanged (ea446316 content).
- The release candidate SHA becomes 84d819be once the owner confirms the
  three commits are intended for the release line (H-4). Wave 5 runner evidence
  was produced with the pre-14a2e6ce runner and gradle.properties, so it is
  historical with respect to build inputs.
- The committed perf handoff still says "No performance files were committed"
  (DOC-DRIFT). Its record of an independent review covers the initial patch; the
  review status of the later benchmark/tuning changes is not established here.

## Candidate identity and ancestry (verified 2026-10-14)

| Item | SHA | Verified |
| --- | --- | --- |
| Release candidate (HEAD, origin/bug-fixes) | 84d819be6e288b82649a1c125fbcaa10f026d0d9 | `git rev-parse HEAD` == `origin/bug-fixes` |
| Wave 5 source commit | ea44631647e69a0dabacdc179dd094b292378b7e | ancestor; `git branch -r --contains` = origin/bug-fixes |
| Wave 5 parent/base | 3e8377e42083c92990f32f4e46fdcb481c29cc3a | ancestor of ea446316 |
| Wave 4 checkpoint | a1c66173 | ancestor |
| Wave 3 | a25f843a | ancestor |
| Wave 2 | 0b44cd4d | ancestor |
| CL-05 merge | 03f197d1 | in history |
| Original campaign pin CA-2026-09-21 | 37601232b9778170c57a656a245b199ab6d7d965 | ancestor; 95 commits to HEAD |

Comparison deltas (shortstat, read-only):

- Full campaign 37601232..HEAD: `app/src/main` 222 files (+11,753/-4,023);
  `app/src/test`+`androidTest` 321 files (+30,364/-2,634); `scripts`+`config`
  62 files (+6,622/-548).
- Wave 4 -> Wave 5 source (a1c66173..ea446316) `app/src/main`: 92 files (+6,086/-1,830).
- Wave 5 remediation (3e8377e4..ea446316) `app/src/main`: 20 files (+278/-36).

## Current source facts (from source, not docs)

- `APP_DATABASE_SCHEMA_VERSION = 151` (`data/database/AppDatabase.kt:43`);
  `app/schemas/.../150.json` and `151.json` present.
- 1,109 main `.kt`, 768 unit-test `.kt`, 31 androidTest `.kt` files.
- 10 `CoroutineWorker` subclasses: DailyBriefing, LocationBackfill, MerchantKeyBackfill,
  DataRetention, ReceiptMatching, BillReminder, DismissReminderAction,
  SnoozeReminderAction, WarrantyExpiration, NotificationIntake.
- Quick grep: 69 `@Dao` files, 70 files with a line-start `@Entity`, 40 ViewModel
  classes. Inventory doc states 70 DAO files / 72 registered entities. Grep is
  approximate; Phase 1 indexing resolves counts. AGENTS.md still says "DB v148,
  68 DAOs" (DOC-DRIFT, P3 candidate).
- Build: `compileSdk 35`, `minSdk 26`, `targetSdk 35`, `versionCode 1`,
  `versionName "1.0"`; release `isMinifyEnabled/isShrinkResources = true`,
  `signingConfig = debug` (app/build.gradle.kts:135-171). Variant source sets:
  only `ui/screens/debug/DebugDataStorage.kt` in debug and release.
- `docs/releases/RELEASE_NOTES.md` is titled "v2.0.0" (2026-04-04, "DB v68"):
  stale/contradictory with `versionName 1.0`; not used as scope evidence.
- Connected tests (31 files) are DAO, migration, fresh-install parity, one
  worker (MerchantKeyBackfill), one PDF repository and one on-device image test.
  There are no connected Compose UI journeys, real-WorkManager worker runs,
  notification-listener, or device backup/restore journey tests.

## Worktree state at session start (superseded by the pin change above)

At session end the only uncommitted paths are this lane's artifacts:
`docs/releases/release-1.0-assurance/` (new) and the modified
`workflows/active/release-1.0-assurance-handoff.md`.

app/ is clean against HEAD and has no untracked files (`git diff --quiet HEAD -- app`;
0 untracked under app/config/scripts/gradle). The dirty set is 41 paths, exactly
the 41 paths excluded from the Wave 5 commit:

| Group | Paths | Build input? | Note |
| --- | --- | --- | --- |
| Performance lane (separate, uncommitted) | gradle.properties, scripts/validation-runner.ps1, scripts/ci/test_validation_runner_protocol.py, config/validation/test-shards.json, docs/development/VALIDATION_RUNNER.md, docs/development/BUILD_GUIDE.md, workflows/active/local-build-performance-20261014-handoff.md, .sisyphus/plans/build-performance-tuning.md | YES (Gradle properties, runner itself, shard manifest) | 4 of the 41 recorded exclusion hashes changed after Wave 5 closure (runner, protocol tests, VALIDATION_RUNNER.md, perf handoff): this lane is still live. That lane's handoff records a user "no subagents" instruction for it. |
| Codex tooling | .codex/config.toml | no | compaction limit change |
| Release kit | .claude/ (16), CLAUDE.md, docs/prompts/release-1.0-assurance/ (14), workflows/active/release-1.0-assurance-handoff.md | no (but untracked files enter the runner fingerprint) | |
| This lane's artifacts | docs/releases/release-1.0-assurance/* (new) | no | permitted artifact-only writes |

The runner fingerprint hashes dirty/untracked bytes (scripts/validation-runner.ps1
uses `git ls-files --others --exclude-standard`) and does not encode the SHA.
Artifact writes therefore change the fingerprint. They are allowed for static
audits. Final validation must use one frozen whole-tree snapshot taken after the
artifact freeze.

## Wave 5 closure reconciliation

Source: `workflows/active/money-w5-independent-remediation-20261013-handoff.md`
(closure section added in 8fdfd205). The kit's README/handoff point to
`money-w5-savings-b8-20261012-handoff.md`. That handoff is superseded, which is
DOC-DRIFT in the kit.

| Gate | Evidence | Status | Note |
| --- | --- | --- | --- |
| Source committed/published | ea446316 + 8fdfd205 + 14a2e6ce + 73a22f44 + 84d819be on origin/bug-fixes | PASS | verified locally against the remote-tracking ref |
| Candidate-only validation | `build/validation-runs/wave5-20261014-candidate-only-evidence.json` (`CLOSED_OWNER_ACCEPTED`) and `build/validation-runs/candidate-only-20261014/<run-id>/` | PASS (content-equivalent) | Runs executed in isolated clone `C:/Users/panos/codex-validation/Cost-agregator-wave5-candidate-20261014-20261014-151834` = 3e8377e4 + 51 overlaid paths. Recorded committed-blob crosswalk says the 51 committed blobs equal the validated bytes. Not re-run on the commit ID itself. |
| registered-guard db_access | vr-20261014-122341-505e3cda | PASS | result.json status PASS, complete.marker present |
| compile | vr-20261014-122625-ff4f70e1 | PASS | same |
| migration-tests | vr-20261014-122727-e694b2f3 | PASS | same |
| app-check (debug+release units, lint) | vr-20261014-122950-8e76f251 | PASS | inspected: exit 0, fingerprint start == end `648e34bd...9165`, git_revision 3e8377e4 (base of overlay); 7,920 tests each, 276/277 skips (per handoff) |
| static-guards | vr-20261014-125555-ffee054e | PASS | 25/25; guard_tests 4,750 pass / 28 skip (per handoff) |
| connected-tests | vr-20261014-131128-f6ab038e | PASS | API 35 only, single AVD; XML 360 tests, 9 skips; raw output archive partial (path length) |
| Independent review of Wave 5 | money-w5-independent-review-handoff.md | FAIL (original, preserved) | |
| Independent re-review of remediation (F1-F10, F2-GOAL-DELETE, v151 marker) | none | NOT RUN, owner-waived | The owner disposition is an acceptance/waiver, not an independent PASS. Release consequence: R-1. |
| G1 (5 cancellation removals, 4 writer grants) | owner statement 2026-10-14 | PASS (human disposition) | +1 approved grant `monthAcceptanceDao.insert` (428 -> 429) |
| F9 transcript retention | owner statement 2026-10-14 | PASS (human disposition) | see H-3: repository is PUBLIC |
| Remote CI for candidate | `gh run list` | NOT RUN | No CI runs on bug-fixes since 2026-06-18 (failure); latest runs on any branch (2026-09-16) failed |

Classification for Release 1.0: these runs are current for the Wave 5 closure
gate. For Release 1.0 they are baseline evidence only, because the final gates
must re-run on the final frozen candidate. They do not cover API 26-34, an
optimized/minified artifact, or runtime journeys.

## Deferred risks carried into the release lane

- R-1: Wave 5 remediation (3e8377e4..ea446316, 20 main files + v151 schema +
  writer grant) has had no independent review; the last independent verdict on
  Wave 5 was FAIL. Phase 2 must give this delta its first independent T1 review.
  Owner closure is not re-opened by this. The release gate simply cannot rely on it.
- R-2: Original campaign closure claims (89 findings / 30 clusters, addenda A1-A8)
  are partly self-reviewed or human-waived (e.g. CL-05 "review gate waived by human",
  commit a4807632). The campaign `STATE.md` is stale (says P-02/P-03/P-07 not
  audited; cell reports exist). Closure is UNVERIFIED for the release lane until
  Phase 2.
- R-3: Device evidence is a single API 35 AVD. minSdk 26 implies API 26-34 behavior
  (notification permission, exact alarms, storage, foreground service types) is
  unproven.
- R-4: Release artifact is debug-signed and has never been verified by the
  runner. The CI release-check job (`.github/workflows/ci.yml:369-395`:
  assembleRelease + `scripts/verify_release_artifact.py`) has no current run.
- R-5: Guard PASS includes existing ratchet/legacy debt (e.g. accounting fold debt
  93/55/38 per Wave 5 handoff; 28 skipped guard tests; 276/277 skipped unit tests).
  Inventory and disposition are PENDING (slice I-04).
- R-6: v150 SQL default currency remains as a documented compatibility limitation
  (F8). The v151 upgrade consumes one month conservatively by design.

## Blockers and human prerequisites

| ID | Item | Blocks | Needed |
| --- | --- | --- | --- |
| B-1 | Performance lane changed build inputs and the validation runner. Update: it was committed locally in 14a2e6ce during this phase (not pushed). | any release validation execution until resolved | Owner confirms 14a2e6ce belongs on the release line and states the review status of its post-review benchmark/tuning changes. A runner self-check (runner-protocol-tests/smoke) at the pin precedes any release queue. No release agent edits these files. SETTLED 2026-10-15 with conditions: owner approval ("i approve them"); reviewer-strict ACCEPT-WITH-CONDITIONS (no false-PASS path introduced; defaults unchanged; removed line kapt.incremental.apt=true is inert under KSP; shard filters unchanged); runner-smoke vr-20261015-064122-be35ad7f PASS and runner-protocol-tests vr-20261015-064132-e5a8df74 PASS (200 passed) at 279b7c92, app == ea446316. Release-lane conditions: no tuning/daemon/benchmark options; cite standard profiles only; for each cited test PASS check result.json defaults + fingerprints + complete.marker AND stdout.log shows the test task executed (not UP-TO-DATE/FROM-CACHE) with >0 tests, else "not run". Open P2 (pre-existing): build cache can yield PASS without executing tests. |
| H-4 | Three Phase 0 commits (14a2e6ce, 73a22f44, 84d819be) moved HEAD during Phase 0 | candidate SHA for validation/final gates | RESOLVED: owner requested publication; all three are pushed on origin/bug-fixes. Static audits continue on the unchanged app-source pin. |
| B-2 | No release APK/AAB runner profile; release artifact verifier is external to the static suite; no current CI | optimized-artifact gate | Approve a narrow, independently reviewed runner profile (assembleRelease + `verify_release_artifact.py`) or restore CI on the candidate. Plan only; not now. |
| B-3 | Production signing process unproven (debug-signed release) | release artifact/human release gate | Owner states the production signing/distribution path (no credentials in artifacts). |
| H-1 | Shipped 1.0 feature scope and exclusions (incl. debug-only surfaces, bank sync, AI/cloud providers, email receipts, investment, challenges); RELEASE_NOTES "v2.0.0" vs versionName 1.0 | vertical journeys, runtime matrix, final acceptance | Owner confirmation. Auditors will not infer scope from menus. |
| H-2 | Supported upgrade/data policy: is 1.0 a first public install (fresh-install only), or must earlier installed schemas (migration baseline v145+) and old backup formats be supported? Device/API matrix and stabilization duration | I-02/P-07 runtime scope, Phase 6 | Owner confirmation |
| H-3 | PUBLIC repository tracks `expense_tracker_backup_2026-04-20_21-58-14.db` (7.08 MB, added ba1c1771 2026-05-01), 28 images under `receits/` (added 3ef34aee 2026-02-16) and two session transcripts. Not read by this lane. | none for app audit; potential live personal-data exposure | Owner determines whether these contain real personal data and decides removal/history policy. No release agent will read, move or rewrite them. |

## Dirty or ambiguous paths

- Performance-lane paths (B-1): committed and pushed in 14a2e6ce; release-line suitability remains a separate owner decision.
- `.codex/config.toml`: now committed in 73a22f44.
- Kit pointer drift: README/handoff name the b8 handoff as "latest Wave 5".
- Architecture doc count drift (AGENTS.md v148/68 DAOs vs v151 source).

## Next action

Human review of this dossier and of `release-1.0-coverage-matrix.md`, then
approval (or amendment) of slice S-01. S-01 is read-only static work, so it does
not depend on B-1..B-3. B-1 must be resolved before any validation queue.
