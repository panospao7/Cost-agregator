# STAGE 3 — WAVE 2 SPEC + CODER PROMPTS (CA-2026-09-21)

Wave 2 (human-ratified): **CL-05, CL-09, CL-15, CL-21, CL-22, CL-23, CL-27** (7 clusters,
34 findings). Split schedule per W1/W2 overlap analysis:

## SPEC SESSIONS (astra @ high, sequential; run alongside W1 coder lanes)

| # | When | Scope | Output |
|---|---|---|---|
| W2-S1 | NOW | CL-09 + CL-23 | wave2/CL-09-spec.md, CL-23-spec.md |
| W2-S2 | NOW | CL-27, then CL-05 | wave2/CL-27-spec.md, CL-05-spec.md |
| W2-S3 | NOW | CL-22 | wave2/CL-22-spec.md |
| W2-S4 | after rp-20 (CL-18) + rp-21 (CL-17) MERGE | CL-21 | wave2/CL-21-spec.md |
| W2-S5 | after rp-22 (CL-19) merges AND CL-27/CL-05 specs exist | CL-15 | wave2/CL-15-spec.md |

Output dir: `docs/analyses and debug master/campaign CA-2026-09-21/wave2/`.

## SESSION SCOPE blocks

**W2-S1:** `CL-09 — findings CA-P-03-001, CA-P-04-005, CA-P-09-001, CA-E-05-008. Then CL-23 — findings CA-P-09-002, CA-P-09-003`
**W2-S2:** `CL-27 — findings CA-E-01-001, CA-E-01-003, CA-E-01-005. Then CL-05 — findings CA-P-01-006, CA-P-04-006, CA-P-06-001, CA-E-01-002, CA-E-02-002, CA-E-02-005, CA-E-04-004`
**W2-S3:** `CL-22 — findings CA-I-04-001, CA-I-04-002, CA-I-04-003, CA-I-04-004, CA-I-04-005, CA-I-04-006, CA-I-04-007, CA-P08-005`
**W2-S4:** `CL-21 — findings CA-P08-002, CA-P08-004`
**W2-S5:** `CL-15 — findings CA-P-03-003, CA-P-05-003, CA-P-05-004, CA-P-06-005, CA-P-07-002, CA-P-07-009, CA-P-12-003, CA-E-01-004`

(P-08 IDs keep the report's no-second-dash form `CA-P08-nnn`.)

## SPEC-WRITER TEMPLATE — two deltas from Wave 1's template, rest identical

Use the Wave-1 template from `stage3-wave1-spec-prompts.md` §§1–3, 5–8 verbatim (same inputs,
schema, hard rules incl. no-subagents, context discipline, return contract, journal), with:

**§2 EXTRA INPUT (all sessions, BINDING):** the STAGE-2 ADDENDUM (bottom section of
`cluster-map.md`) plus `docs/testing/generated/TRIAGE-2026-09-test-recovery.md`. Every addendum
item targeted at your clusters — A1→CL-23 (DataRetentionWorker) / CL-08 (TransactionLifecycleCoordinator)
/ CL-15 (AppStartupCoordinator, verify against merged CL-19 first); A6 + A8→CL-05/CL-27 —
becomes an explicit spec work item, or is fenced to a named owner with a one-line reason.
Candidate findings A3/A4/A5 touching your surfaces get a one-line placement note. Never leave an
addendum item unaddressed.

**§0 REPLACED BY:**
```
## 0. Repo state verify (execute first, stop on failure)

Working directory: C:\Users\panos\Desktop\cost agregator\ExpenseTracker

git status --porcelain -- app config scripts        # must be empty (campaign docs may be dirty)
git merge-base --is-ancestor 37601232 HEAD && echo ANCESTOR-OK

The audit pin 37601232 is the ANCHOR pin for finding evidence; HEAD is now a moving target
(checkpoint ad412aec + merged Wave-1 lanes). Record `git rev-parse --short HEAD` in each
spec's provenance. Where a target file was touched by Wave 1 (CL-17/18/19 files:
DatabaseBackupRepositoryImpl, RestoreJournal, CompositePrivacyGate, cloud services, the 16
CL-17 logging files), cite anchors at CURRENT HEAD and say so; all other anchors stay
"@pin 37601232". Specs' pre-implementation drift check becomes:
`git diff 37601232..HEAD -- <allowed files>`.
```

**§4 REPLACED BY (Wave-2 cluster requirements):**
```
CL-09: per-site predicate design — ScannedReceiptDao claim-status CAS for suggestion writes;
RecurringRule activation reads the rule INSIDE the transaction and aborts on zero-row update;
MerchantKeyBackfill gains a still-NULL/unchanged predicate; WarrantyReminder delivery claim
revalidates parent ACTIVE. Idempotency across WorkManager retries per site. One sweep PR,
per-site tests; this cluster crosses 4 segments — enumerate each segment's targeted tests.

CL-23: WorkerRunLogger.toOutcome derives fallback errorClass from NotDurableFailure's own
exception (no separate error argument). Drain deadline moves to monotonic elapsed time —
grep for an existing monotonic clock abstraction (e.g. SystemClock.elapsedRealtime wrapper)
and use it; if none exists the spec designs the minimal injectable. Wall time remains for
timestamps only.

CL-27: validDate must come from the provider's actual publication field — verify the real
response shape at the pin and name the field path; if truly absent, spec a typed
fallback-with-quality-flag, never fabrication. Month keys: locale-independent ASCII
formatting. MoneyAggregateBuilder preserves failed buckets AND counts them in
totalTransactionCount. Review gate room-migration-guardian per cluster map.

CL-05: placement is PRE-APPROVED — CurrencyConverter/MultiCurrencyRepository as the single
money contract, unknown currency becomes a typed unavailable outcome. The spec MUST split
into two work-item groups per the approval condition: (A) core-contract PR, (B) consumer
sweep PR. Money blast-radius isolation absolute; InsightsEngine.kt may carry CL-17 drift.

CL-22: per-guard fix list; FG-03/06/07 constraints are absolute (fail-closed, no baseline
growth, no allowlist broadening). The spec MUST include a pre-merge step: run the full
static-guards suite via validation-runner and REPORT newly-failing violations as findings
for the human — the coder does not fix or baseline them in-lane without approval.

CL-21 (post-W1): route CloudQueryInterpretationService through CloudPayloadPolicy.prepare*;
decide HybridRouter wiring per service with evidence (wire where the contract applies,
document the rest). Cloud service files carry CL-17's landed reason-code changes — anchor
at current HEAD. Doc half of P08-004 lands only after callers are real.

CL-15 (post-W1 + post CL-27/05 specs): typed unavailable/error outcome per site (radar
unavailable state; dashboard widget-level degradation; stress-forecast unavailable result;
CSV malformed-header = error; BackupVerifier required-query failure = verification error;
getDatabaseStats blocked read = error not zeros; OCR failedPages partial status). The
MoneyAggregateBuilder UNAVAILABLE item (E-01-004) must align with CL-27's landed/ specced
quality-metadata design. Backup files anchor at current HEAD (CL-17/19 drift).
```

## CODER SESSIONS — wrapper UNCHANGED

Use the Wave-1 coder wrapper (`stage3-wave1-coder-prompts.md` **rev 3**) verbatim; fill the two
slots. Lanes (rp-25 consumed by test-recovery; W2 starts at rp-26), with merge order:

| Merge order | Worktree / branch | Cluster |
|---|---|---|
| W2 money 1st | build/worktrees/rp-26 · `rp-26-wip` | CL-27 money core |
| W2 money 2nd | build/worktrees/rp-27 · `rp-27-wip` | CL-05 currency contract (A then B) |
| after rp-22 | build/worktrees/rp-28 · `rp-28-wip` | CL-23 worker diagnostics (+A1 DataRetentionWorker) |
| after rp-27 | build/worktrees/rp-29 · `rp-29-wip` | CL-15 failure semantics (+A1 AppStartupCoordinator if open) |
| after rp-21 merge | build/worktrees/rp-30 · `rp-30-wip` | CL-21 cloud routing |
| after rp-30 | build/worktrees/rp-31 · `rp-31-wip` | CL-22 guard hardening |
| anytime | build/worktrees/rp-32 · `rp-32-wip` | CL-09 CAS sweep (+A1 TransactionLifecycleCoordinator via CL-08 coordination) |

## GATES — Wave 2 findings are ALL UNVERIFIED

Every W2 spec must carry a `## GATE` blocking implementation until the finding set is
adversarially revalidated (same flow as Wave 1: independent pass, verdicts + journal line,
then GATE-lift stamps). W2 holds P1s (P-03-001, P-04-005, P08-002, P-07-002/009, P-12-001
is W1... correct set: CL-09's P-03-001/P-04-005, CL-15's P-03-003/P-07-002/P-07-009,
CL-21's P08-002) — verification before coder launch, not after.
