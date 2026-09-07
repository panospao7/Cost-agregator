# Remediation Plan Index — RP-00…RP-21

> **Basis:** `REVALIDATED_ISSUE_REGISTRY_2026-09-07.md` (adversarially verified findings; the only authoritative issue list).
> **Convention:** every plan doc gives per issue: problem → fix design (exact files + logic) → what it solves → guardrails (architecture / crossovers) → test plan. Paths marked **⟂** = class name is unique in the repo but the directory was not captured in the audit — run a quick file search before editing.
> **Modes** (per AGENTS.md): `strict` = strict review required (workers, privacy, Room, money, lifecycle, cross-layer, 5+ files). `standard` = normal review.
> **Rule for all packages:** smallest correct change; no opportunistic refactors; no destructive migration; tests updated in the same change; no milestone marked DONE without implementation + tests + review gate.

---

## 1. Package registry

| ID | Package | Issues covered | Scope class | Mode | Status |
|---|---|---|---|---|---|
| RP-00 | Decision register | 7 blocking decisions | — | — | ✅ written |
| RP-01 | Cancellation-safety completion + guard repair | U-001, U-002, U-003, U-006 (+ P2-009, P5-008 overlaps) | **Universal** (engine + guard) | strict | ✅ written |
| RP-02 | Write-barrier guard repair + writer registration | U-004 (+ P4-002 guard sub-issue) | **Universal** (guard + 4 pipelines' writers) | strict | ✅ written |
| RP-03 | Backup/restore integrity | P7-001…011 (+ P12-008 crossover) | Pipeline 7 + cross (encryption service shared with P12) | strict | ✅ written |
| RP-04 | Recurring/subscription lifecycle | P4-001, 002, 003, 004, 005, 006 | Pipeline 4 + crossover (guard, calendar utils) | strict | ✅ written |
| RP-05 | Dashboard window & income correctness | P5-001, 003, 004, 005, 012 | Pipeline 5 (isolated) | standard→strict (money-adjacent) | ✅ written |
| RP-06 | Pace & synthesis input correctness | P5-002, 006, 007, 008, P5-CURRENT-009, NEW-P6-013, NEW-P5-009/012/013, U-MONEY-01 residual, REVAL-8 | Pipeline 5 ↔ 6 crossover (SynthesisEngine shared) | strict (money) | ✅ written |
| RP-07 | Runway/trend polish | P5-010, 011, 013, 014 | Pipeline 5 (isolated) | standard | ✅ written |
| RP-08 | Budget autopilot correctness | P6-002, 003, 004 | Pipeline 6 (release-critical) | strict (money) | ✅ written |
| RP-09 | Budget partial-data & FX basis | P6-001, 005, 006, 007, 008, 009, 010, NEW-P6-010/015, P6-P1-13 | Pipeline 6 ↔ stress engine crossover | strict (money) | ✅ written |
| RP-10 | Notification capture hardening | P1-001…008, **REVAL-4** (folded into the P1-003 fix: RecoveryScheduler KDoc + missing sweeps) | Pipeline 1 (isolated; privacy-adjacent) | strict | ✅ written |
| RP-11 | Transaction lifecycle semantics | P2-001…008, 010, NEW-P2-016 | Pipeline 2 engine (consumed by all) | strict | ✅ written |
| RP-12 | Receipt save dispatch & privacy | P3-001, 004, 007, 008, 009 | Pipeline 3 ↔ warranty/price-protection crossover | strict | ✅ written |
| RP-13 | Statement import & OCR robustness | P3-002, 003, 005, 006, 010 | Pipeline 3/10 shared processor | strict | ✅ written |
| RP-14 | Retention perimeter completion | P8-001, 002, 003, 004, 007, + REVAL-3 | Pipeline 8 + cross (P2 events table) | strict (privacy) | ✅ written |
| RP-15 | Privacy hardening & denied-UX | P8-005, 006, 008, P8-P1-12, NEW-P8-006, resolver debt (D5) | Pipeline 8 (isolated) | strict (privacy) | ✅ written |
| RP-16 | Worker observability & guard contract | P9-001…009 | Pipeline 9 engine (all workers) | strict (workers) | ✅ written |
| RP-17 | Bank sync productization | REVAL-10, P10-001…012, tracked P10 partials, **U-BARRIER-02 residual** (refreshToken token-write, stub-gated) | Pipeline 10 (DEBUG-stub-gated) | strict | ✅ written (gated by RP-00/D1) |
| RP-18 | Email parser correctness | P11-001…010 (parsers only; wiring = RP-00/D2), **tracked NEW-P11-002/003 residuals** (detectProvider body-routing + fallback bypassing canParse) | Pipeline 11 (currently unwired) | standard (CODE-REAL but zero prod reach) | ✅ written |
| RP-19 | Import/export correctness | P12-001…007, 009, **tracked P12 partials**: P12-P0-01 (via D3), P12-P1-02/03/05/06/07/08 (P12-P1-06 rescoped: receiptLinks only) | Pipeline 12 (+ encryption crossover w/ RP-03) | standard | ✅ written |
| RP-20 | Dead-code & cleanup sweep | NEW-P2-006/008, P3-P1-05/07-remnant, P4-004, P4-006, P8-005, P10-004, P12-010, P9-009, REVAL-5 | Cross-pipeline deletions | standard (verify zero callers each) | ✅ written |
| RP-21 | Test-debt triage | REVAL-1/2/3/6/7/9 | Test suite | standard | ✅ written (no test runs until stakeholder approves) |

## 2. Staging order (dependency-aware)

```
Stage 1 (this drop):  RP-00 → RP-01 → RP-02 → RP-03 → RP-04
Stage 2:              RP-05 → RP-06 → RP-07 → RP-08 → RP-09
Stage 3:              RP-10 → RP-11 → RP-12 → RP-13
Stage 4:              RP-14 → RP-15 → RP-16 → RP-17 → RP-18 → RP-19
Stage 5:              RP-20 → RP-21 → final cross-check vs registry
```

Hard dependencies:
- **RP-01 before RP-11** (coordinator catch fixes reference the same helper/pattern) and before RP-06 (P5-008 shares the pattern).
- **RP-02 before RP-04** (guard detection upgrade also catches the `subscriptionDao` alias) and before RP-14 (retention worker audit writes).
- **RP-00/D1 before RP-17**; **RP-00/D2 before RP-18 scope**; **RP-00/D3 before RP-19 import scope**; **RP-00/D4 before RP-20**.
- **RP-03 owns P12-008** (same encryption service) — RP-19 must not touch `BackupEncryptionService` independently.
- **RP-12 and RP-13 both touch `BankStatementLifecycleProcessor`** — sequence RP-13 after RP-12 or split by method.

## 3. Classification summary

- **Universal (multi-pipeline infrastructure):** RP-01, RP-02, RP-11 (engine consumed by all), RP-14 (retention perimeter spans pipelines), RP-16 (guard used by all workers), RP-20 (cross-pipeline deletions).
- **Pipeline-isolated:** RP-03, RP-05, RP-07, RP-10, RP-18, RP-19.
- **Engine/feature crossovers (special handling — read both sides before editing):**
  - `SynthesisEngine` + `ForecastInputAssembler`: RP-06 ↔ RP-09 (both change forecast inputs; land RP-06 first).
  - `BankStatementLifecycleProcessor`: RP-12 ↔ RP-13 (shared with bank statement import).
  - `BackupEncryptionService`: RP-03 ↔ RP-19 (envelope change must land once).
  - `TransactionLifecycleCoordinator`: RP-01 ↔ RP-11 (catch patterns + semantics in same file).
  - Architecture guards: RP-02 ↔ RP-04 (detection upgrade covers subscription alias).
- **Decision-gated:** RP-17 (D1), RP-18 (D2), RP-19 partial (D3), RP-20 (D4).

## 4. Coverage ledger (every registry ID → disposition)

Traceability rule: **every ID in `REVALIDATED_ISSUE_REGISTRY_2026-09-07.md` maps to exactly one row above or one entry below.** Verified 2026-09-07.

**No-action items (documented, deliberately not planned):**
- `NEW-P1-011` — accepted debt (hash format pinned by MIGRATION_104_105; fixing it would silently break cross-era dedupe). Do not "fix".
- `P4-P1-05` — verified accurate as-is (occurrence key structurally collision-free); no action.
- `P7-P1-05` / `U-PRIVACY-03` — deferred-by-design; documented 5-item plan lives in `BackupVerifier.kt:20-33`. Revisit after RP-03.
- `U-SIDEEFFECT-01` — verified NOT a bug.
- `U-MONEY-02`, `U-MONEY-03`, `U-TOCTOU-01`, `U-CANCEL-01`, `U-BARRIER-01/03`, `U-PRIVACY-01/02`, `U-WORKER-01…04`, `U-TIME-01/02`, `U-SIDEEFFECT-02`, `U-EXPORT-01/02`, `U-DEAD-01` — verified FIXED (see cross-validation report §2.2); no packages.
- Refuted fresh findings (`FRESH-U-005`, `FRESH-P1-007`, `P3-P1-07`) — no action; P3-P1-07's class deletion is covered by RP-20/D4.
- `REVAL-9` (suite health) — context for RP-21; `REVAL-6/7` (tests codifying wrong behavior) — RP-21.

**Also stale but intentionally not rewritten:** the original trackers (`PIPELINE_1..12_CONSOLIDATED_ISSUES.md`, `PIPELINE_ISSUES_MASTER_TRACKER.md`, `MASTER_ISSUE_TRACKER.md`) are superseded by the cross-validation report + revalidated registry. Regenerating them is optional post-remediation cleanup; until then the remediation folder is the single source of truth for open work.

## 5. Global guardrails (apply to every package)

1. **Lifecycle legal paths:** expense mutations only via `TransactionLifecycleCoordinator`; recurring rule mutations only via `RecurringRuleLifecycleCoordinator`/`RecurringLifecycleCoordinator`; no direct DAO writes from new code. Several fixes *restore* this invariant (RP-04) — do not introduce new bypasses while fixing others.
2. **Worker rules:** preserve `WorkerExecutionGuard` usage, checkpoint cadence, retry-vs-fail classification, idempotency across WorkManager retries, CE propagation (never swallow), sanitized reason codes only.
3. **Privacy:** fail-closed everywhere; never persist/log raw notification/OCR/email text outside the storage-mode contract; reason codes are controlled constants; privacy cleanup must never be gated on the capability it enforces.
4. **Room:** none of the planned fixes require a schema change *except* none — verify per plan; if one emerges, stop and add version+migration+snapshots+tests.
5. **Money:** no float changes to rounding semantics; engine math changes need golden-test updates in the same PR.
6. **Diff discipline:** no drive-by formatting; each RP lands as its own PR-sized change; tests touched only as required by the behavior change (RP-21 handles the pre-existing test debt separately).
