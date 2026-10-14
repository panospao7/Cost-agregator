# Release 1.0 regression and fix-closure ledger (seed)

```text
Workflow / phase: release-1.0-assurance / Phase 2 input (seeded during coverage planning)
Source candidate SHA: 84d819be6e288b82649a1c125fbcaa10f026d0d9 local HEAD, pushed (app == ea446316)
Comparison baselines: campaign 37601232 / Wave 5 parent 3e8377e4
Seeded by: release-orchestrator Opus 5.5/high from a scout (Haiku 5.5/medium)
  document-claims index; lead spot-checks noted as [lead-checked]
Independent of implementation: yes. This seed is NOT verification.
Status: PENDING. Every row is CLAIMED/UNVERIFIED until a Phase 2 slice and a
  fresh verifier dispose of it.
```

Columns follow `artifact-templates.md`. "Claimed" restates what the campaign
documents say. Classification and Confirmed status remain UNKNOWN/CANDIDATE
until audited. Source docs: `CAMP` = `docs/analyses and debug master/campaign
CA-2026-09-21/`, `WF` = `workflows/active/`, `J<n>` = CAMP/JOURNAL.md line.

## Clusters CL-01..CL-30

| Cluster | Wave | Old IDs (max sev) | Claimed closure | Review on record | Slice | Classification | Confirmed | Closure |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CL-01 | 1 | P01-001 (P0), P01-004 | merged (J75) | FAIL, no PASS [lead-checked] | RG-03 | UNKNOWN | CANDIDATE | OPEN |
| CL-02 | 4 | P01-002 | validated W4-1 | none; W4 strict review PENDING | RG-03 | UNKNOWN | CANDIDATE | OPEN |
| CL-03 | 4 | P01-003, P01-005 | validated W4-1/W4-6 | none | RG-03 | UNKNOWN | CANDIDATE | OPEN |
| CL-04 | 4 | P02-001 (P3) | deferred by design (keep unwired) | none | RG-11 | UNKNOWN | CANDIDATE | OPEN |
| CL-05 | 2 | P01-006, P04-006, P06-001, E01-002, E02-002, E02-005, E04-004 | merged 03f197d1 | FAIL then human-waived (a4807632) | RG-09 | UNKNOWN | CANDIDATE | OPEN |
| CL-06 | 4 | P01-007, P03-005, E05-001/002, I02-001, I03-001/002 (P3) | docs reconciled | none | RG-15 | UNKNOWN | CANDIDATE | OPEN |
| CL-07 | 3 | P02-002 (P1), E05-005 | validated (a25f843a) | none independent | RG-11 | UNKNOWN | CANDIDATE | OPEN |
| CL-08 | 3 | P02-003, I05-001 (P1->P2 verifier) | validated; policy pin 413->424 approved | none independent | RG-11 | UNKNOWN | CANDIDATE | OPEN |
| CL-09 | 2 | P03-001 (P1), P04-005 (P1), P09-001, E05-008 | merged 0b44cd4d | author self-review only | RG-07 | UNKNOWN | CANDIDATE | OPEN |
| CL-10 | 3 | P03-002 (P1) | human decision local-first; validated | human decision only | RG-10 | UNKNOWN | CANDIDATE | OPEN |
| CL-11 | 3 | P03-004 (P1) | validated | none | RG-12 | UNKNOWN | CANDIDATE | OPEN |
| CL-12 | 4 | P04-001, P04-002 | validated W4-2 | none | RG-13 | UNKNOWN | CANDIDATE | OPEN |
| CL-13 | 4 | P04-003, P04-004 | validated W4-2 | none | RG-13 | UNKNOWN | CANDIDATE | OPEN |
| CL-14 | 4 | P05-001, P05-002 | validated W4-3 | none | RG-14 | UNKNOWN | CANDIDATE | OPEN |
| CL-15 | 2 | P03-003, P07-002, P07-009 (P1); P05-003/004, P06-005, P12-003, E01-004 | merged 0b44cd4d | S1 indep PASS (J117) vs strict FAIL doc unreconciled; CL-15 self-review | RG-08 | UNKNOWN | CANDIDATE | OPEN |
| CL-16 | 4 | P06-002 | validated W4-4 | none | RG-14 | UNKNOWN | CANDIDATE | OPEN |
| CL-17 | 1 | P07-006 (P0), E04-001 (P0), P08-003 (P1), P04-007, P06-003, P06-004, P07-008, E01-006, E02-006, I05-002, I05-004 | merged (rp-21) | FAIL x2, no PASS [lead-checked] | RG-02 (P0/P1), RG-15 (rest) | UNKNOWN | CANDIDATE | OPEN |
| CL-18 | 1 | P07-001 (P1), P08-001 (P1) | merged (rp-20) | FAIL x2, no PASS [lead-checked] | RG-02 | UNKNOWN | CANDIDATE | OPEN |
| CL-19 | 1 | P07-003/004/005 (P0), P10-001 (P1), I01-001 | merged (rp-22) | FAIL x2, no PASS [lead-checked] | RG-01 | UNKNOWN | CANDIDATE | OPEN |
| CL-20 | 4 | P07-007 | validated W4-5 | none | RG-15 | UNKNOWN | CANDIDATE | OPEN |
| CL-21 | 2 | P08-002 (P1), P08-004 | merged 0b44cd4d | self-review; privacy guardian PENDING | RG-10 | UNKNOWN | CANDIDATE | OPEN |
| CL-22 | 2 | I04-001..007, P08-005 | merged 0b44cd4d | author FAIL->fix; reviewer-strict PENDING | RG-17 | UNKNOWN | CANDIDATE | OPEN |
| CL-23 | 2 | P09-002, P09-003 | merged 0b44cd4d | self-review; architecture-guardian NOT RUN | RG-13 | UNKNOWN | CANDIDATE | OPEN |
| CL-24 | 4 | P11-001, P11-002 (P3) | validated W4-6 | none | RG-15 | UNKNOWN | CANDIDATE | OPEN |
| CL-25 | 3 | P12-001 (P1->P2 verifier) | validated | none | RG-12 | UNKNOWN | CANDIDATE | OPEN |
| CL-26 | 4 | P12-002 | validated W4-5 | none | RG-12 | UNKNOWN | CANDIDATE | OPEN |
| CL-27 | 2 | E01-001, E01-003, E01-005 | merged 0ea0316a | direct review PASS; guardian waived | RG-09 | UNKNOWN | CANDIDATE | OPEN |
| CL-28 | 4 | E02-001, E02-003, E02-004 | validated W4-4 | none | RG-14 | UNKNOWN | CANDIDATE | OPEN |
| CL-29 | 1 | E04-002 (P0), E04-003 | merged (rp-24) | FAIL, no PASS [lead-checked] | RG-04 | UNKNOWN | CANDIDATE | OPEN |
| CL-30 | 4 | I05-003 | validated W4-5 | none | RG-15 | UNKNOWN | CANDIDATE | OPEN |

## Addenda and named residuals

| Item | Claimed | Slice | Status |
| --- | --- | --- | --- |
| A1 CANCEL-01 x3 (DataRetentionWorker, TLC, AppStartupCoordinator) | conflicting claims (fixed in-lane vs "via CL-05") | RG-03/RG-07/RG-01 | UNKNOWN |
| A2 denial reason-code contract | implemented; privacy guardian PENDING | RG-02 | UNKNOWN |
| A3 SpendingMap gpsPrivacyBlocked | OPEN | RG-15 | UNKNOWN |
| A4 ReviewViewModel denial flow | fixture repaired; runtime missing | RG-15 | UNKNOWN |
| A5 phone heuristic over-redaction | via CL-05 (waived review) | RG-09 | UNKNOWN |
| A6 conversionWarnings redaction | via CL-05 | RG-09 | UNKNOWN |
| A7 settlements in balances | implemented; review PENDING ("not closed") | RG-04 | UNKNOWN |
| A8 money-batch assertion landmine | closed by owner acceptance | RG-09 | UNKNOWN |
| RP-03 S2 asset recovery (six gaps, J115) | no closure record | RG-01 | UNKNOWN |
| P07-009 orphan-check residual | verifier says P1 stands | RG-08 | UNKNOWN |
| raw_money_aggregates 83-84 -> 25/25 PASS | no recorded fix/baseline [lead: no raw-money baseline file] | RG-17 | UNKNOWN |
| Cancellation ratchet 125 -> 112 | claimed approvals | RG-17 | UNKNOWN |
| 76-failure tail (W2) vs 0 failures (W5) | unmapped | RG-09 | UNKNOWN |
| Wave 3 and Wave 4 independent strict review | PENDING per handoffs | RG-11..RG-15 | UNKNOWN |
| M-3..M-5 money track | unstarted as of 2026-10-04 | RG-06/RG-16 | UNKNOWN |
| Legacy contribution-currency attribution decision | owner decision status UNKNOWN | RG-16 | UNKNOWN |

## Wave 5 items

| Item | Claimed | Review on record | Slice | Status |
| --- | --- | --- | --- | --- |
| F1, F2, F3, F6, F7, F10 | fixed in ea446316 | original indep FAIL; re-review owner-waived | RG-05 | UNKNOWN |
| F2-GOAL-DELETE / v151 marker | fixed; approved schema + grant 429 | waived | RG-05 | UNKNOWN |
| F4 dashboard budget provenance | approved intent only; ea446316 touches dashboard budget files [lead-checked] | no record | RG-06 | UNKNOWN |
| F5 email source currency | approved intent only; no implementation record | no record | RG-06 | UNKNOWN |
| F8 SQL default EUR (v150) | accepted compatibility limitation | owner | RG-05 | ACCEPTED-LIMITATION (verify reachability) |
| F9 transcript | owner retained; repo is PUBLIC (H-3) | owner | n/a (human) | HUMAN-DECIDED |
| G1 removals/grants | owner confirmed after the fact | owner | RG-05/RG-17 | HUMAN-DECIDED (verify scope unchanged) |
| M0-M2d, B1-B8 | self-reviewed batches; matrix "Closed" | partial | RG-06/RG-16 | UNKNOWN |

## Findings

None. No release-lane finding exists yet.
