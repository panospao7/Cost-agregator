# Master State Summary — Exact Application State (2026-09-04)

The answer to "what is the actual state of the application and its bugs?" as of `d1fa9c68` (`atomicity-pr21-enforcement-final`). Static validation only; ~240 pipeline issues + the 22-issue universal program re-verified in source by 9 agents with orchestrator spot-checks.

---

## 1. Headline numbers

| Metric | Count |
|---|---|
| Pipeline issues validated | ~240 (P1:23, P2:21, P3:19, P4:21, P5:26, P6:31, P7:14, P8:18, P9:26, P10:10, P11:13, P12:18) |
| **Verified fixed** | **~226 (≈94%)** |
| Verified open | 7 |
| Verified partial | 12 |
| Regressed (confirmed) | 1 real (latent) + 1 minor behavior delta |
| Not verifiable statically | 1 (P12-P0-01) |
| New issues found this session | 1 systemic privacy cluster (11+ sites) + 2 process patterns + 10 MAJOR + ~35 MINOR (doc 16) |
| Tracker/doc drift rows (both directions) | ~103 |

Per-pipeline breakdown (fixed / open / partial / new):

- **P1** 22/0/1 · 4 new — **P2** 21/0/0 · 3 new
- **P3** 19/0/0 · 3 new — **P4** 19/0/1 · 3 new
- **P5** 24/1/0 · 3 new — **P6** 29/0/1 · 4 new
- **P7** 14/1/1 · 3 new — **P8** 18/0/2 · 3 new
- **P9** 26/0/0 · 4 new — **P10** 10/1/4 · 4 new (incl. 1 regression)
- **P11** 13/0/0 · 3 new — **P12** 11/4/2 · 2 new (1 not verifiable statically)

## 2. Universal program state (truth, not the stale tracker)

| U-PR | Fresh verdict | Honest tail |
|---|---|---|
| U-PR1 cancellation | Mostly landed; sampled services rethrow correctly | 98 allowlist entries (38 UI) + 8 fresh production swallow sites (incl. `TransactionLifecycleCoordinator.kt:321,343,515,652,691`) |
| U-PR2 TOCTOU | Landed (read-inside-transaction, 10/10 methods) | 2 real residuals in `ReceiptLinkService` (NEW-P3-2026-001); planned `atomicReadModifyWrite` helper never existed (doc drift) |
| U-PR3 money | MONEY-01/02 in place | MONEY-03 open + new silent raw-mixing fallbacks (5 SynthesisEngine sites, 1 recurring report) |
| U-PR4 barriers | Landed (try/finally exit verified; static guard exists — "no guard" claim stale) | — |
| U-PR5 privacy | **Substantially landed contrary to all docs** — `rawBankStatementStorageMode` exists (`PrivacySettings.kt:15-18`), redaction targets exist | `requireAllowed()` has exactly 1 production caller (`ReceiptLifecycleCoordinator.kt:296`) — the authoritative-cloud-gate wiring is the real remaining work |
| U-PR6 worker guard | Fully verified (barrier-before-log, 15-min recovery, NO_WORK, REPLACE, timeout-retry `7076d730`) | — |
| U-PR7 time | Landed + T1–T4C merged and clean | ~49 non-behavioral `System.currentTimeMillis()` + SynthesisEngine Calendar debt; closes when `gr-00-local` (`8b45879e`) lands |
| U-PR8 side effects | Verified: no double dispatch; trigger type parameterized | New adjacent leak (SYS-2026-01) |
| U-EXPORT-01/02, U-DEAD-01 | **All three fixed in code** (tracker rows stale) | — |

**New universal categories discovered (U-PR9+ candidates):** N1 e.message→persisted diagnostics (11+ sites, the session's biggest find); N2 TOCTOU outside the coordinator (4 sites); N3 fix-in-dead-path (2 confirmed); N5 silent conversion-failure raw-mixing (5 sites).

## 3. The genuinely open work at HEAD (the real backlog)

**Still-open pipeline issues (7):** NEW-P5-012 (false fix claim, inline `sevenDaysMs` remains, `AnalyticsCurrencyNormalizer.kt:175`); P7-P1-05; P10-P1-02 (OAuth/PKCE); P12-P1-03/04/06/07.
**Partial (12):** P1-P1-07, P4-P1-05 (deferred), P6-P1-13, P7-P1-02, P8-P1-09, NEW-P8-007 (`sanitizeRawOcrNullable` has zero production callers), P10-P1-03/07/09, NEW-P10-002 (re-auth signal never reaches UI), P12-P1-02/05.
**New MAJOR work (doc 16):** SYS-2026-01 e.message cluster; AppleReceiptParser double-escape + canParse breadth (auto-created wrong expenses, false dedup = data-loss vector); NotificationFilter "pos" substring defeating deposit-deny; ReceiptLinkService TOCTOU; currency-blind recurring report; bank `refresh()` regression + dead sync gating; guard expiry fail-open.
**Deferred tails:** U-PR1 allowlist burn-down (98 entries); U-MONEY-03; PII-guard data-flow scope.

## 4. Priority order recommendation

1. **SYS-2026-01 (e.message → persisted diagnostics)** — violates the repo's own P0 privacy law in 11+ places including restore journal and raw-notification JSON; fix pattern already exists in-repo (`92a6ebf7`'s typed constants). Extend the PII guard to data-flow so it can't silently regrow.
2. **AppleReceiptParser pair (NEW-P11-2026-001/002)** — auto-creates wrong expenses and falsely dedups real ones; contained parser fix with clear tests.
3. **NotificationFilter "pos" substring (NEW-P1-2026-001)** — a class of notifications the user explicitly denied is being captured raw.
4. **ReceiptLinkService TOCTOU (NEW-P3-2026-001)** — silent lost updates; promote TOCTOU-outside-coordinator to a universal rule afterwards.
5. **Guard expiry enforcement (NEW-REG-2026-001)** — trivial test fix; expires silently on 2026-10-01; coordinate with the guardrail session since it touches the cancellation guard test.
6. **Bank connections pair (NEW-P10-2026-001/002)** — real but low-traffic area (stub integration).
7. **Money residuals** — fold RecurringLifecycleCoordinator sums + SynthesisEngine mixing into the U-MONEY-03 slice planned alongside the guardrails.
8. **Tracker reconciliation** — the old consolidated docs and `PIPELINE_ISSUES_MASTER_TRACKER` are wrong in both directions (~103 rows). Either update them or mark them superseded by this folder (recommended; they were intentionally left untouched).

## 5. Sequencing vs the guardrail program (GATE-00R)

This folder is **documentation only** — safe to land anytime, including before GATE-00R. Source fixes from §4 follow the sequencing agreed earlier: land GATE-00R first (no source changes during it), then start the low-overlap slices (Apple parser, notification filter are parser/filter files outside the DB-ownership policy set; the SYS-2026-01 sites touch `TransactionSideEffectPlanner`/writers, which should be coordinated with GR-08q triage batches that touch the same lifecycle files).

## 6. Validation status of this validation

- Static source verification with file:line evidence: **done** for every verdict in this folder.
- Compile/test execution: **NOT RUN** (deliberate — guardrail session owns Gradle). Before closing anything as fixed for milestone purposes, run the targeted tests suggested in each pipeline doc §6.
- Headline claims additionally spot-checked by the orchestrator: `AppleReceiptParser.kt:37-41,55` double-escape ✓, `TransactionSideEffectFailureEventWriter.kt:59` persisted `reason` ✓, `BankConnectionsViewModel.kt:75-87` infinite collect ✓.
