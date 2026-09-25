# TEST RECOVERY PACK — CA-2026-09-21 interlude (2026-09-23)

## PRIME DIRECTIVE (governs every phase)

**Green is not the goal — truthful is the goal.** A repaired test must verify exactly the property
it verified before the repair. If making a test pass requires: changing what it asserts, weakening
an assertion, deleting or ignoring a test, adding sleeps to mask a race, or touching production
behavior — that is OUT OF FENCE. Log it to the triage file and move on. Triage decisions get made
with the campaign findings ledger in view, not inline by a coder.

Triage file (created by the first session that needs it, on its lane):
`docs/testing/generated/TRIAGE-2026-09-test-recovery.md`
Row format: `testClass.method | observed | suspected mechanism | class: harness | stale | REAL-BUG-CANDIDATE (→ cluster?)`

Ground truth: 5 test files / 9 compile errors block ALL unit-test runs at HEAD
(KeystoreInstallationSecretHashingTest — nullable return drift; LegacyDataConsistencyCheckerTest —
FakeReceiptEventDao missing a DAO member; ReceiptLifecycleCoordinatorTest — renamed constructor
param; BackupRestoreViewModelPrivacyDenialTest — Result type change; ExportOptionsViewModelPrivacyDenialTest —
new constructor param). The compile run is the source of truth; this list is the expected set.
Known landmine: May-2026 assertion updates in the money batch may have encoded audit finding
CA-E-01-005 — re-examine when MoneyAggregateBuilder work happens (Phase C guard).

---

## PHASE A — compile repair (rp-25, sol @ high, worktree build/worktrees/rp-25)

Paste into a fresh codex session opened in `build/worktrees/rp-25`:

```
## Role
You are the test-compilation repair coder. Goal: make the app's unit-test sources COMPILE and
EXECUTE again at this commit. Success is: test sources compile and the previously-blocked tests
RUN — any pass/fail outcome is acceptable and recorded. Chasing green is explicitly NOT your job.

PRIME DIRECTIVE (absolute): a repaired test must verify exactly the property it verified before.
Changing assertions to pass, weakening, deleting, ignoring, sleep-masking, or editing production
code = out of fence. Such cases go to docs/testing/generated/TRIAGE-2026-09-test-recovery.md
(create it) with the row format given in the campaign pack.

## Step 0 — environment verify
git rev-parse --abbrev-ref HEAD  → must be rp-25-wip; tree must be clean. STOP if not.
Read the prime directive again. It overrides any temptation to "just make it pass".

## Method
1. Probe compile: scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test
   -TestFilter '*RestoreJournalDurabilityTest'  (any cheap filter — its value is forcing full
   test-source compilation). Capture the full e: error list from the run's stderr.log.
2. Repair file by file (expected set: KeystoreInstallationSecretHashingTest,
   LegacyDataConsistencyCheckerTest, ReceiptLifecycleCoordinatorTest,
   BackupRestoreViewModelPrivacyDenialTest, ExportOptionsViewModelPrivacyDenialTest):
   update test-side call sites, fakes, and constructor args to the CURRENT production APIs.
   For a fake missing a new interface member: implement it minimally and honestly (the fake
   must behave, not throw/return garbage).
   For each file, record in your report: intent (what the file verifies), repairs made,
   assertions changed (target: NONE; if any assertion body had to change, that row goes to
   triage instead and the change is not made).
3. Iterate probe → fix until test compilation is clean.
4. Execute the repaired files: targeted runs per repaired class. Record every result —
   behavioral FAILURES ARE EXPECTED AND FINE (these tests haven't run in months; failures are
   signal, likely corroborating campaign findings). Do not fix production. Do not chase green.
   Behavioral failures → triage rows (class: REAL-BUG-CANDIDATE where the test looks right).
5. Validate via validation-runner only; never Gradle directly. Lock contention = NOT RUN, wait
   and retry; never report it as FAIL.
6. Commit on rp-25-wip in small commits (one per file family). Do not touch campaign/, main
   source outside nothing at all (this lane edits ONLY app/src/test/** + the triage file).

## Hard rules
No subagents. No production edits (app/src/main/** is read-only to you). No test deletion,
@Ignore, or assertion weakening (triage instead). Determinism: never replace real waiting with
longer sleeps.

## Return contract (≤20 lines)
Per file: intent · repairs · assertions changed (none/N) · run outcome (pass/fail counts).
Triage rows added: count + REAL-BUG-CANDIDATE flags. Validation: command + result + log path.
Journal line for the human:
2026-09-23T<HH:MM>+03:00 | coder (direct session, sol high) | TEST-RECOVERY-A | rp-25-wip | <n> files compile-repaired, <n> triage rows, <m> REAL-BUG-CANDIDATE | <validation result>
```

---

## PHASE B — fresh baseline (after rp-25 merges to bug-fixes)

Run by you (no session needed), in the main checkout, serialized (nothing else running):

1. `scripts/validation-runner.ps1 -Action Start -Profile trusted-tests` — fast structural sanity.
2. Full suite with hang isolation ON (so suspect classes don't stall the run): the runner's
   unit-tests profile with its legacy-tests exclusion (the isolation the runner already provides
   — do not invent new flags). If the full profile must run in named serial shards, run them one
   at a time; a shard that stalls = note it and move on (that's Phase C data, not a blocker).
3. Collect every run's `result.json` + logs under `build/validation-runs/`.

Then hand me the result.json/log paths — I classify the failures free (harness / hang /
assertion-vs-buggy-production / genuinely stale) and size Phase C against the campaign ledger,
flagging REAL-BUG-CANDIDATE clusters. That classification replaces the stale May report.

---

## PHASE C — hang + harness lane (rp-26, sol @ high; AFTER Phase B classification)

Scope: hang suspects (driven by the legacy-tests ledger — locate it from the runner's config,
do not invent a path) and harness-family failures (mock setup, dispatchers, uncaught-before-test,
"no answer found"). Fix ROOT CAUSES in families, not per-test whack-a-mole.

Fences: test sources + test utilities only; production is read-only (suspicion → triage).
Deterministic waiting on real state replaces sleeps; fakes must behave honestly.
Isolation, never deletion: a hang that can't be fixed in-lane goes to the legacy-tests bucket
with a ledger row — @Ignore/deletion is forbidden.
OUT OF SCOPE: assertion failures against current production behavior (they heal wave-aligned —
Waves 2–4 own their surfaces' tests; Phase 4 sweeps the residue).
HARD BOUNDARY: this phase ends when the suite runs end-to-end mechanically. Phase C also carries
the landmine guard: any test row touching MoneyAggregateBuilder failure-counting gets cross-checked
against CA-E-01-005 before any assertion is touched — expect to route it to triage, not fix it.
