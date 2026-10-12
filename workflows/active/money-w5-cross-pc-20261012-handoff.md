# Wave 5 cross-PC WIP handoff - 2026-10-12

Status: **PARTIAL**. User explicitly authorized committing and pushing selected
work to continue on another PC. This is a WIP transfer, not Wave 5 completion,
release acceptance, a destructive DB reset, or permission to weaken any gate.
This later authorization supersedes historical no-staging/commit/push notes in
the candidate and implementation-plan packets only for this selected transfer.

## Resume from disk

- Branch: `bug-fixes`; upstream: `origin/bug-fixes`.
- Remote: `https://github.com/panospao7/Cost-agregator.git`.
- Pre-checkpoint base: `a1c66173219d7d959b0d36859a68176aaf814242`.
- On the other PC, from its existing checkout:

  ```powershell
  git switch bug-fixes
  git pull --ff-only origin bug-fixes
  ```

  If that checkout has local changes or has diverged, preserve them and reconcile
  deliberately; do not reset, clean, force-push or overwrite them.
- Read this packet, then:
  1. `money-w5-connected-repair-20261012-handoff.md` (current repair/evidence).
  2. `money-w5-remaining-implementation-plan-20261009.md` (approved batch scope).
  3. Relevant batch packet and architecture docs before changing source.
  These files are under `workflows/active/`. Resume from handoffs, not the private
  session transcript. The no-further-subagents amendment remains in force; local
  review must not be represented as independent acceptance.

## Checkpoint contents

- Money foundations, normalized analytics/forecast/dashboard boundaries,
  receipt/OCR and bank source boundaries, PDF export and savings batches 1-7
  implementation/tests accumulated in the mixed working tree.
- Scoped connected-fixture and archived migration repairs C1-C5, with exact Room
  validation, seeded financial/history preservation and uniqueness tests retained.
- Validation-runner durability/serialization fixes, registered-guard Unicode
  handling and their tests; cancellation baseline shrinks, not grows.
- Money handoffs/plans/candidate notes. Refer to the candidate packet and committed
  diff for exact files; inclusion is not an assertion that every gate has passed.
- Production DB version stays 149, supported migration baseline stays 145 and
  the active registry stays 145-to-149. Earlier migrations are reference/test
  repairs, not an expansion of production support. Unsupported DBs are not
  silently destroyed. First-release baseline planning remains separate.
- Local agent configs, private transcript, scratch files, unrelated audit/campaign
  docs, line-ending-only guard-policy noise and generated build artifacts are not
  part of this transfer. They remain local and are unnecessary to resume.

## Latest validation: FAIL, not running

- Wrapper invocation: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File
  scripts/validation-runner.ps1 -Action Start -Profile connected-tests`.
- Actual command: `gradlew.bat :app:connectedDebugAndroidTest --console=plain
  --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1
  -PvalidationForkEvery=50 --no-daemon`. Only validation-runner executed it.
- Run ID: `vr-20261012-142426-b8ed659c`; result FAIL, exit 1, `E_COMMAND_FAILED`.
- Finished 2026-10-12T15:23:10.7005671Z; elapsed 3521 seconds. No active lock remains.
- Fresh XML: **354 cases, 341 passed, 4 failed, 9 skipped**. XML totals take
  precedence over inconsistent console progress totals. No skips were added.
- Start/end fingerprint:
  `11ad8c7ef890c387e24501e90bc25987100b29840120512282b40b1179615204`.
- Result/logs/physical completion marker were inspected under
  `build/validation-runs/vr-20261012-142426-b8ed659c/`; these ignored local
  artifacts do not transfer in Git. Fresh XML was inspected under
  `app/build/outputs/androidTest-results/connected/debug/`.
- App/test compilation completed as part of this run. Latest full unit tests and
  full static-guard suite are **NOT RUN** on this final candidate. Historical
  focused PASS results in earlier packets do not establish current full-suite PASS.
- Transfer documentation was updated after the terminal run. Candidate source
  hashes were rechecked unchanged before those edits, but the committed-tree
  fingerprint is not the validated fingerprint. No new whole-tree PASS claimed.

## Remaining connected failures

All four are in `DatabaseMigrationTest`:

1. `migrate_92_to_114_chain_passes`
2. `migrate_106_to_114_preserves_materialized_keys_currency_and_receipt_history`
3. `migrate_all_versions_from_33_to_114`
4. `migrate_92_to_115_chain_passes`

Each fails Room `categories` index parity: archived `MIGRATION_112_113` creates
UNIQUE `index_categories_name_nocase`, but target 114/115 exports expect no such
index. Inspect actual source and historical schemas before selecting the next
repair. Do not blindly remove uniqueness, edit snapshots to fit a bug, skip the
tests, relax Room schema validation or reset user data just to obtain green.

The seeded 106-to-107 checkpoint now passes, but the new test's post-114 receipt/
financial-history assertions are blocked by the category failure. End-to-end
preservation is therefore not proven for that chain yet. The new 93-to-94 test,
current supported-contract fixtures and connected PDF tests pass in this run.

## Review / remaining gates

- Local strict source reviews for C1-C5: PASS for their scoped slices, per repair
  packet; these are not independent whole-candidate acceptance.
- Independent strict review and architecture/Room/privacy guardian acceptance:
  **NOT RUN** under the no-subagents amendment.
- Full connected suite: **FAIL**. Broad final unit/static-guard gates: pending.
- Exact additional cancellation-baseline removals still require the human-owner
  approval described in the implementation plan. No approval inferred from this
  WIP transfer. Follow `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-03/06/07/23.
- Savings Batch 8 (R16 durable/idempotent acceptance persistence) is **not
  implemented**. It needs its separate owner/transaction/schema/backup design
  gate; do not treat Room plus DataStore dual writes as atomic.

## Next actions

1. Pull the checkpoint, inspect status, read the packets and scope only the four
   remaining category-index failures. Preserve all unrelated work.
2. Repair one narrow archived migration/test batch without changing active
   support policy, uniqueness semantics, privacy or guards; perform strict review.
3. Check for an active validation owner before starting anything. Use only
   `scripts/validation-runner.ps1`, one serialized run at a time. Connected tests
   took about 59 minutes here; request approval for new expensive validation.
   Poll a RUNNING result, never start overlapping Gradle/tests/guards.
4. Inspect terminal result, exit code, physical marker, equal fingerprints and
   fresh XML. FAIL, stale or unknown evidence is never PASS. Persist the outcome
   in the repair handoff before proceeding to broader final gates.
5. Continue pending acceptance and Batch 8 only after their prerequisites are
   verified. Do not mark the whole wave DONE or release-ready based on this push.
