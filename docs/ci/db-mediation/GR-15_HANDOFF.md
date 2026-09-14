# GR-15 CAMPAIGN HANDOFF — read this fully before touching anything

You are starting the GR-15 stage on branch `gr-14f-wip` (worktree:
`build/worktrees/gr-14f`).  GR-14 is CLOSED at its endpoint — the
owner-acceptance tier — and GR-15 is hereby unblocked.  The first GR-15
batch is defined, specified, and owner-sanctioned; do not re-plan it.

## 0. READ FIRST (in this order)

1. `GR-14_OWNER_ACCEPTANCE_RECORD.md` — the endpoint decision, the 20
   accepted rows, the two MANDATORY caveats on the Handle pair.
2. `GR-15_GATE_AMENDMENT.md` — the amended gate semantics you will
   implement (batch 1).
3. `GR-14_FINAL20_CORRECTION_ADDENDUM.md` — why the promotion option is
   dead and what the mask finding means.
4. `GR-14_FINAL20_DECISION_MEMO.md` — per-family mechanisms and
   remediation options (read WITH the addendum banner at top).
5. `GR-14_HANDOFF.md` — sections 2-4 (how the engine thinks, patterns,
   verification loop) and section 6 (gotchas) remain authoritative for
   any engine work.

## 1. CURRENT STATE (GR-14 endpoint)

Board `03acbb044721e9dc` (post GR-14u57b, 379 entries), reproduced
2026-09-14 by independent single-basis full-pipeline runs:

| bucket | count |
|---|---|
| proven_helper | 344 |
| proven_worker_mediated | 14 |
| proven_restore_internal | 1 |
| owner_accepted (per the record) | 20 |
| unproven | 0 |
| counterexample | 0 |

Closing commits: FINAL20 memo `99e86311`; correction addendum (with the
FINAL20 banner) `5a41f116`; acceptance record + gate amendment + this
handoff committed together.

## 2. BATCH 1 — implement the amended gate (specified, sanctioned)

Implement `GR-15_GATE_AMENDMENT.md` exactly: acceptance-registry input
to `scripts/ci/inspect_db_mediation_proof.py`, the `owner_accepted`
bucket with the mandatory caveat line, exit semantics, fail-closed
registry matching, tests + fixtures per the established admission
pattern, verification loop per `GR-14_HANDOFF.md` section 4.

Non-goals (do not drift): no new carrier admissions, no policy edits,
no source changes.  Strict mode applies (CI guard + static
architecture area).

## 3. DEBUG TOOLING (use it; obey its limits)

`build/guard-debug/harness.py` replaces the write-a-probe-per-hypothesis
loop: warm graph queries ~1.4s, post-patch rebuild ~11-16s, full census
`prove` / `prove --patched` ~210s (scan_db_access ~190s dominates).
Commands: stats, callable, edges-into/from [--exact|--uncertain],
deciders-into, regions, text, closure, patch/patches/reset, prove
[--patched] [--row SUBSTR]; `-f batch.txt` for batches.

Rules learned the hard way:
- Emulations MUST be single-text-basis: use `prove --patched` (writes
  patches to disk on a clean worktree, byte-verified restore).  The
  older in-memory-corpus-patch + disk-scan pattern leaves observations
  and the graph on different offsets — unsound.
- The harness is DEBUG ONLY.  Final acceptance is always one cold
  `scripts/ci/inspect_db_mediation_proof.py` run.
- The cache invalidates on corpus or engine-script change; `--fresh`
  forces a rebuild.  One cache per worktree.

## 4. STANDING PROHIBITIONS AND TRIPWIRES

- **Never land the 8 type annotations** on
  DatabaseBackupRepositoryImpl/WorkerExecutionGuard/
  RestoreDiagnosticsSink (the u58 patch set).  They are the
  proof-destroying change: verified to flip both Handle-pair rows to
  counterexamples.  If a future engine capability makes the rows'
  edges honest, that is the acceptance record's re-opening clause, not
  a source patch.
- The acceptance registry is the ONLY acceptance mechanism and it is
  fail-closed: no registry entry, no acceptance.
- The counterexample HARD STOP is unchanged by the amendment.  Any
  counterexample anywhere halts the stage.

## 5. FUTURE ENGINE CAPABILITY BATCH (parked, not scheduled)

The typing capabilities (when-initializers, argument-bearing chains,
property-copy receivers) remain worth building EVENTUALLY — they remove
noise corpus-wide and the harness makes iteration ~30x cheaper.  Scope
them with the explicit expectation that the Handle pair re-opens as
counterexamples (acceptance record caveat 2) and needs an architecture-
level owner decision at that time.  Family E's preservation-rule
refinement is parked with it (addendum Correction 2).

## 6. DEFINITION OF DONE (GR-15)

Per the GR-15 plan plus the amended gate: batch 1 landed and green
(fixture + unit + one cold full-pipeline run showing the amended board
with the caveat line); GATE-00R obligations carried over from
GR-14_HANDOFF section 8 still apply; docs truth-synced (no DONE/GREEN
without the gates — AGENTS.md rule).
