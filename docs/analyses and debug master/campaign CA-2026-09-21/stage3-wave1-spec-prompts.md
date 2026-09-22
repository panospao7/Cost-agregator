# STAGE 3 — WAVE 1 IMPLEMENTATION SPEC PROMPTS (CA-2026-09-21)

Wave 1 (human-ratified 2026-09-22): **CL-01, CL-17, CL-18, CL-19, CL-29**.

## Session plan — run sequentially, one fresh codex session each

| Session | Model / effort | Scope | Output specs |
|---|---|---|---|
| W1-S1 | gpt-6-astra @ **xhigh** | CL-19 (restore/backup fail-closed state machine) | wave1/CL-19-spec.md |
| W1-S2 | gpt-6-astra @ high | CL-18, then CL-17 (privacy gate, then diagnostics sweep) | wave1/CL-18-spec.md, wave1/CL-17-spec.md |
| W1-S3 | gpt-6-astra @ high | CL-01 + CL-29 (capture boundary + group splits) | wave1/CL-01-spec.md, wave1/CL-29-spec.md |

Order rationale: CL-18's spec must exist before CL-17's (same files, gate semantics land first).
W1-S3 is the cheapest — running it FIRST is a valid template-validation option before spending
xhigh on CL-19.

All spec files land in `docs/analyses and debug master/campaign CA-2026-09-21/wave1/` (create it).

## SESSION SCOPE blocks (paste the matching one into §1 of the template)

**W1-S1:** `CL-19 — findings CA-P-07-003, CA-P-07-004, CA-P-07-005, CA-P-10-001, CA-I-01-001`
**W1-S2:** `CL-18 — findings CA-P-07-001, CA-P08-001. Then CL-17 — findings CA-P-04-007, CA-P-06-003, CA-P-06-004, CA-P-07-006, CA-P-07-008, CA-P08-003, CA-E-01-006, CA-E-02-006, CA-E-04-001, CA-I-05-002, CA-I-05-004`
**W1-S3:** `CL-01 — findings CA-P-01-001, CA-P-01-004. Then CL-29 — findings CA-E-04-002, CA-E-04-003`

---

## TEMPLATE (paste ONLY this block — not the whole file — after replacing the SESSION SCOPE line in §1 with your session's scope from the table above)

## 0. Pin verify (execute first, stop on failure)

Working directory: `C:\Users\panos\Desktop\cost agregator\ExpenseTracker`.

```
git rev-parse --short HEAD                                  # must print 37601232
git diff --stat 37601232..HEAD -- app config scripts        # must print nothing
```

If either fails, STOP and report the mismatch in ≤5 lines.

## 1. Role and scope

You are the Stage-3 spec-writer for campaign CA-2026-09-21, Wave 1. You translate audited defect
clusters into **coder-ready implementation specs**. You are NOT implementing: no patches, no fix
code beyond short illustrative fragments. A coder agent (sol @ high) will later execute each spec
file in its own worktree lane, with AGENTS.md governing; the spec must therefore carry EVERYTHING
the coder needs to avoid wrong-layer edits, missed tests, or privacy violations.

SESSION SCOPE: `<paste from the table above>`

Process clusters in the order given in the scope. One spec file per cluster.

## 2. Inputs (in read order, per cluster)

1. `docs/analyses and debug master/campaign CA-2026-09-21/cluster-map.md` — header, revision note,
   wave map, and the in-scope cluster blocks (fix shape, placement, files, review gate).
2. `docs/analyses and debug master/campaign CA-2026-09-21/findings-ledger.md` — the member rows.
3. The member findings' sections in their cell reports (`cell-*.md`, same directory) for evidence
   detail (lines, caller traces).
4. `docs/architecture/LEGAL_PATHS.md` — ONLY the sections for this cluster's surfaces.
5. **The code itself** — every work item MUST be grounded in the real file/function at the pin.
   This is the core of the job: the cell audits cited code at pin `37601232`; you re-open those
   files, confirm the anchors, and read enough surrounding code that the spec's change
   instructions are exact (conditions, ordering, call sites).

## 3. Spec file schema (exact structure, one file per cluster)

```
# CL-XX Implementation Spec — <name>
Provenance: <date>; pin 37601232 verified; astra <effort> Stage-3 session; source cluster-map.md.

## GATE
- Implementation of this spec is blocked until: <unverified member findings> are revalidated.
  (Skip section if all members verified — currently only P-01 findings are.)

## Context for the coder
- What this surface does for the user (2-4 sentences, plain language).
- Root cause being fixed (from the cluster map).

## Pre-implementation checks (coder MUST run, in order)
1. `git diff 37601232..HEAD -- <allowed files>` — read every hunk; anchors below are from the pin
   and may have drifted; function names + signatures are authoritative, line numbers advisory.
2. Re-read each target function at current HEAD before editing.

## Legal path constraints
- The coordinators/services this change must route through (named LEGAL_PATHS sections).
- Explicit "must NOT write directly to DAO from X" statements where relevant.

## Work items
### WI-1 — <short title> (<finding ID>, severity, verification status)
- Location: <file.kt — function signature — ≈L<line> @pin 37601232>
- Defect: 1-2 lines.
- Change: design-precise instructions — exact conditions, ordering, semantics, call sites.
  Short illustrative fragments allowed; full patches forbidden.
- Acceptance criteria: observable behavior a test can check.
(repeat per finding)

## Tests
- Per work item: test class to add/update (real name), and the enumerated boundary cases
  (the spec must LIST them — the coder must not invent coverage).

## Validation
- validation-runner profiles only (targeted-unit-test first; compile). The coder NEVER invokes
  Gradle directly (repo rule).

## Blast-radius fence
- ALLOWED: <files>
- FORBIDDEN: <files that look adjacent but are out of scope, with the cluster that owns them>

## Review gate
- <guardian/reviewer> + the specific things the reviewer must verify.

## Privacy constraints (privacy clusters only)
- Controlled reason-code constants enumerated PER SITE (repo rule: constants only, no free text).
- What may never be logged/persisted on this surface.
- EventMetadataSanitizer is FALLBACK for bounded text only — deletion beats redaction.

## Worker constraints (worker-touching clusters only)
- Preserve WorkerExecutionGuard usage; never swallow CancellationException; retry-vs-failure
  semantics; idempotency across WorkManager retries; metrics only after actual success.

## Out of scope
- Adjacent finding IDs NOT in this spec and where they are handled instead.
```

## 4. Cluster-specific requirements

**CL-19 (xhigh depth)**: produce a full fail-closed state-machine design before work items:
enumerate restore/maintenance states and every transition; define behavior for corrupt journal
bytes, unknown state enum value, failed fsync/rename, failed barrier commit, unknown persisted
mode — each must land in a durable, user-visible CRITICAL/recovery state, never silently reset.
Decide WHERE enforcement lives (RestoreJournal vs RestoreMaintenanceMode vs DatabaseWriteBarrier
vs AppStartupCoordinator) with code evidence; the bank persistOutcome fix (CA-P-10-001) must be a
caller-level barrier check, not a barrier rewrite. Include the asset-rollback worker-rescheduling
fix (CA-I-01-001) as its own work item.

**CL-18**: specify FailClosed-as-blocking at every export entry point (enumerate them), and the
exact rethrow placement for CancellationException in CompositePrivacyGate before generic
conversion.

**CL-17**: default is reason-code/class-name logging; sanitizer only for bounded needed text.
Enumerate the controlled reason-code set PER SITE. CA-E-04-001 is DELETION of financial payload
logging (merchant/amount/currency/date), not sanitization. CA-I-05-004 deletes the dead use
cases. Respect the approved placement: shared policy via EventMetadataSanitizer, no ad hoc
scrubbing.

**CL-01**: give the exact deferred-capture check sequence and order (consent, master toggle,
blocked package) with the source of truth for each; specify how combinedBody threads from
NotificationTextParts into the live filter. CA-P-01-001 is VERIFIED — its evidence is trusted.

**CL-29**: specify the active-member filter point in the dialog/viewmodel flow and the
unequal-mismatch rejection semantics. For the repayment-sign fix, include a worked numeric
example (A owes B X; after repayment balances move TOWARD zero by X). Confirm at spec time
whether GroupBalanceCalculator still has zero callers at the pin (map says yes) — if so, note
that tests are the primary consumer and the fix is still required.

## 5. Hard rules

- Work directly in THIS session. Do NOT spawn subagents (no scout, planner, explorer, coder, or
  any agent type): this is a single-session spec-writing task, subagent output has no provenance
  path in this campaign, and any spawned-agent work will be discarded unread.
- Source tree READ-ONLY. You create/modify ONLY: the wave1 spec files and one JOURNAL.md line.
- Ground every anchor: re-open every cited file at the pin. Invented APIs, guessed signatures,
  or uncited anchors are campaign-grade failures.
- No new findings — one-liners to a `## Non-ledger observations` spec appendix at most.
- Never narrate unearned completion; the spec files on disk are the deliverable.

## 6. Context discipline

- ≤25 full-file reads; prefer function-scoped reads. Cell-report drill-down by finding section.
- Flush each spec file the moment its cluster settles; do not hold finished specs in context.
- At ~150K tokens: write current progress, then continue. After any compaction, re-anchor on
  this prompt's §3 schema + your specs written so far.

## 7. Return contract (final chat output, ≤20 lines)

Per cluster: spec path · work-item count · test classes named · GATE status. Plus artifact-dir
path and confirmation the journal line was written. Nothing else.

## 8. Journal (mandatory, one line per session, append to `JOURNAL.md`)

```
2026-09-22T<HH:MM>+03:00 | stage3-spec-writer (direct session, astra <effort>) | STAGE-3-SPEC | <CL-IDs> | <n> work items total | docs/analyses and debug master/campaign CA-2026-09-21/wave1/<files>
```

---

## How the specs are consumed (for panos, not pasted)

1. Revalidate the gated P0s first (six: CA-P-07-003/004/005/006, CA-E-04-001/002 — CL-19, CL-17,
   CL-29 gates). A REFUTED finding voids only its work item, not the spec.
2. One coder session per cluster spec, in its own worktree lane (suggested naming `rp-2x-clXX`;
   you assign numbers). Coder: sol @ high per the routing table; astra reviews; the cluster's
   named guardian gives the domain gate.
3. Landing order inside Wave 1: CL-18 → CL-17 (same files); CL-19, CL-01, CL-29 independent and
   parallelizable.
4. CL-17's PR is a sweep: one coherent change, but expect the reviewer to check every site
   against the enumerated reason codes.
