# Release 1.0 regression and fix-closure ledger (seed)

```text
Workflow / phase: release-1.0-assurance / Phase 2 input (seeded during coverage planning)
Source candidate SHA: 84d819be6e288b82649a1c125fbcaa10f026d0d9 local HEAD, pushed (app == ea446316)
Comparison baselines: campaign 37601232 / Wave 5 parent 3e8377e4
Seeded by: release-orchestrator Opus 5.5/high from a scout (Haiku 5.5/medium)
  document-claims index; lead spot-checks noted as [lead-checked]
Independent of implementation: yes. This seed is NOT verification.
Status: PENDING. Every row is CLAIMED/UNVERIFIED until a Phase 2 slice and a
  fresh verifier dispose of it. Updated 2026-10-14 after RG-01 (HEAD 279b7c92,
  app == ea446316): CL-19 and RP-03 S2 rows plus the Findings section.
```

Columns follow `artifact-templates.md`. "Claimed" restates what the campaign
documents say. Classification and Confirmed status remain UNKNOWN/CANDIDATE
until audited. Source docs: `CAMP` = `docs/analyses and debug master/campaign
CA-2026-09-21/`, `WF` = `workflows/active/`, `J<n>` = CAMP/JOURNAL.md line.

## Clusters CL-01..CL-30

| Cluster | Wave | Old IDs (max sev) | Claimed closure | Review on record | Slice | Classification | Confirmed | Closure |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CL-01 | 1 | P01-001 (P0), P01-004 | merged (J75) | FAIL historical; RG-03 static verification: both VERIFIED-FIXED (7d021502) | RG-03 | CHANGED (fixed) | REFUTED as open (static) | CLOSED-STATIC; named test execution PENDING |
| CL-02 | 4 | P01-002 | validated W4-1 | none; W4 strict review PENDING; RG-03 static verification VERIFIED-FIXED (a1c66173) | RG-03 | CHANGED (fixed) | REFUTED as open (static) | CLOSED-STATIC |
| CL-03 | 4 | P01-003, P01-005 | validated W4-1/W4-6 | none; RG-03 static verification both VERIFIED-FIXED (a1c66173) | RG-03 | CHANGED (fixed); residual RG03B-2 | REFUTED as open (static) | CLOSED-STATIC; RG03B-2 P3 OPEN |
| CL-04 | 4 | P02-001 (P3) | deferred by design (keep unwired) | RG-11a static: still unwired in release | RG-11 | STILL-OPEN (by design) | n/a | ACCEPTED-BY-DESIGN (dormant code ships) |
| CL-05 | 2 | P01-006, P04-006, P06-001, E01-002, E02-002, E02-005, E04-004 | merged 03f197d1 | FAIL then human-waived (a4807632); RG-09 static: all 7 consumer fixes + review regression R1 FIXED per discovery (A5 verified) | RG-09 | CHANGED (fixed) | REFUTED as open (static) | CLOSED-STATIC (P06-001 not independently re-verified); P3 residuals OPEN |
| CL-06 | 4 | P01-007, P03-005, E05-001/002, I02-001, I03-001/002 (P3) | docs reconciled | RG-15b scout: 4 FIXED, I02-001 STILL-OPEN, I03-001/002 PARTIAL (other docs stale) | RG-15 | DOC-DRIFT | n/a | PARTIAL (P3) |
| CL-07 | 3 | P02-002 (P1), E05-005 | validated (a25f843a) | none independent; RG-11 static: P02-002 VERIFIED-FIXED; E05-005 CHANGED (label-only retry) | RG-11 | CHANGED (fixed) | REFUTED as open (static); see RG11-H2 | CLOSED-STATIC; RG11-H2 P1 OPEN |
| CL-08 | 3 | P02-003, I05-001 (P1->P2 verifier) | validated; policy pin 413->424 approved | none independent; RG-11b static: both FIXED (I05-001 not independently re-verified) | RG-11 | CHANGED (fixed) | REFUTED as open (static) | CLOSED-STATIC (discovery for I05-001) |
| CL-09 | 2 | P03-001 (P1), P04-005 (P1), P09-001, E05-008 | merged 0b44cd4d | author self-review only; RG-07 static verification: P04-005, P09-001, E05-008 VERIFIED-FIXED; P03-001 PARTIAL | RG-07 | CHANGED (mostly fixed) | P03-001 residual RG07A-1 CONFIRMED P2 | P04-005/P09-001/E05-008 CLOSED-STATIC; P03-001 OPEN |
| CL-10 | 3 | P03-002 (P1) | human decision local-first; validated | human decision only; RG-10 static: FIXED (local OCR ungated; cloud image upload 4-way gated) | RG-10 | CHANGED (fixed) | VERIFIED-FIXED (V-10/11) | CLOSED-STATIC |
| CL-11 | 3 | P03-004 (P1) | validated | none; RG-12 static: PARTIAL | RG-12 | CHANGED (partial) | residual RG12-01 CONFIRMED P1 | OPEN |
| CL-12 | 4 | P04-001, P04-002 | validated W4-2 | none; RG-13 static: both FIXED (discovery) | RG-13 | CHANGED (fixed) | closure verification PENDING | FIXED-STATIC (discovery); RG13-1 P3 |
| CL-13 | 4 | P04-003, P04-004 | validated W4-2 | none; RG-13 static: both FIXED (anchor parity hand-traced; D7 distinguished) | RG-13 | CHANGED (fixed) | P04-004 VERIFIED-FIXED (V-13..16); P04-003 discovery | FIXED-STATIC (discovery) |
| CL-14 | 4 | P05-001, P05-002 | validated W4-3 | none; RG-14 static: both FIXED (discovery; UI consumers unread) | RG-14 | CHANGED (fixed) | closure verification PENDING | FIXED-STATIC (discovery) |
| CL-15 | 2 | P03-003, P07-002, P07-009 (P1); P05-003/004, P06-005, P12-003, E01-004 | merged 0b44cd4d | CL-15 self-review; RG-08 static verification: 3 P1s VERIFIED-FIXED, 5 P2s FIXED per discovery (fixes survived Wave 5); S1 records reconciled as sequential | RG-08 | CHANGED (fixed) | REFUTED as open (static) | CLOSED-STATIC; P3 residuals OPEN |
| CL-16 | 4 | P06-002 | validated W4-4 | none; RG-14 static: FIXED (re-clamp) | RG-14 | CHANGED (fixed) | closure verification PENDING | FIXED-STATIC (discovery) |
| CL-17 | 1 | P07-006 (P0), E04-001 (P0), P08-003 (P1), P04-007, P06-003, P06-004, P07-008, E01-006, E02-006, I05-002, I05-004 | merged (rp-21) | FAIL x2 historical; RG-02 verified P0s; RG-15a/c static: P04-007, P06-003, P06-004, P07-008, E01-006, E02-006, I05-002 FIXED; I05-004 deleted; R-2/B-1 addressed | RG-02, RG-15 | CHANGED (fixed) | P0s REFUTED as open; P08-003 -> RG02B-1 P2 | P0s CLOSED-STATIC; P08-003 OPEN; rest FIXED-STATIC (discovery) |
| CL-18 | 1 | P07-001 (P1), P08-001 (P1) | merged (rp-20) | FAIL x2 historical; RG-02 static verification: both VERIFIED-FIXED (R-1 fixed by 984596ed) | RG-02 | CHANGED (fixed) | REFUTED as open (static) | CLOSED-STATIC; test execution PENDING |
| CL-19 | 1 | P07-003/004/005 (P0), P10-001 (P1), I01-001 | merged (rp-22) | FAIL x2 historical; RG-01 independent STATIC verification 2026-10-14: all 5 VERIFIED-FIXED | RG-01 | CHANGED (fixed; I01-001 contract change -> RG01A-1) | REFUTED as open defects (static) | CLOSED-STATIC; runtime/test execution PENDING |
| CL-20 | 4 | P07-007 | validated W4-5 | RG-15c static: FIXED (a1c66173) | RG-15 | CHANGED (fixed) | VERIFIED-FIXED (V-13..16) | FIXED-STATIC (discovery); RG15C-2 P3 |
| CL-21 | 2 | P08-002 (P1), P08-004 | merged 0b44cd4d | self-review; privacy guardian PENDING; RG-10 static: P08-002 FIXED (all 8 providers prepare via policy), P08-004 FIXED | RG-10 | CHANGED (fixed) | P08-002 VERIFIED-FIXED (V-10/11) | CLOSED-STATIC |
| CL-22 | 2 | I04-001..007, P08-005 | merged 0b44cd4d | author FAIL->fix; RG-17 static verification: all 8 VERIFIED-FIXED | RG-17 | CHANGED (fixed) | REFUTED as open (static) | CLOSED-STATIC; FG-06 residuals RG17A2-1/RG17A2-3 OPEN |
| CL-23 | 2 | P09-002, P09-003 | merged 0b44cd4d | self-review; RG-13 static: both FIXED (discovery) | RG-13 | CHANGED (fixed) | closure verification PENDING | FIXED-STATIC (discovery) |
| CL-24 | 4 | P11-001, P11-002 (P3) | validated W4-6 | RG-15b scout: P11-001 FIXED; P11-002 PARTIAL (labeled-line path unguarded); email unwired in release | RG-15 | CHANGED | n/a | PARTIAL (P3, unreachable) |
| CL-25 | 3 | P12-001 (P1; verifier P2) | validated | none; RG-12 static: FIXED (CSV provenance; debug-only) | RG-12 | CHANGED (fixed) | REFUTED as open (static) | CLOSED-STATIC; RG12-04 P3 |
| CL-26 | 4 | P12-002 | validated W4-5 | none; RG-12 static: FIXED (format switch) | RG-12 | CHANGED (fixed) | REFUTED as open (static) | CLOSED-STATIC; RG12-03 P2 (date range) OPEN |
| CL-27 | 2 | E01-001, E01-003, E01-005 | merged 0ea0316a | direct review PASS; guardian waived; RG-09 static: E01-001/003/005 FIXED (E01-005 verified); E01-003 fix caused latent RG09A-1 (P3) | RG-09 | CHANGED (fixed) | REFUTED as open (static) | CLOSED-STATIC; RG09A-1 P3 OPEN |
| CL-28 | 4 | E02-001, E02-003, E02-004 | validated W4-4 | none; RG-14 static: E02-001/003 FIXED; E02-004 recurring/MUST-planned in trajectory (RG14-1 REFUTED by V-13..16); likely-bill trajectory gap V16-NEW-3 unverified | RG-14 | CHANGED (fixed) | REFUTED as open (static) | FIXED-STATIC; V16-NEW-1/3 candidates |
| CL-29 | 1 | E04-002 (P0), E04-003 | merged (rp-24) | FAIL historical; RG-04 static verification: E04-002 VERIFIED-FIXED on the live path; E04-003 VERIFIED-FIXED (zero-caller) | RG-04 | CHANGED (fixed) | REFUTED as open (static); variant RG04A-1 P2 | CLOSED-STATIC; WI-6 scope decision OPEN |
| CL-30 | 4 | I05-003 | validated W4-5 | RG-15c static: FIXED | RG-15 | CHANGED (fixed) | VERIFIED-FIXED (V-13..16) | FIXED-STATIC (discovery); RG15C-1 P3 |

## Addenda and named residuals

| Item | Claimed | Slice | Status |
| --- | --- | --- | --- |
| A1 CANCEL-01 x3 (DataRetentionWorker, TLC, AppStartupCoordinator) | conflicting claims (fixed in-lane vs "via CL-05") | RG-03 | VERIFIED-FIXED (static): fix landed via CL-05 sweep 3b7cabfe; no cancellation baseline entries |
| A2 denial reason-code contract | implemented; privacy guardian PENDING | RG-02 | VERIFIED consistent and fail-closed (static); guardian approval still PENDING |
| A3 SpendingMap gpsPrivacyBlocked | OPEN | RG-15 | FIXED (production correct; test harness repaired c8f4c383) |
| A4 ReviewViewModel denial flow | fixture repaired; runtime missing | RG-15 | FIXED (fixture); production fail-closed; RG15C-3 UI gap P3 |
| A5 phone heuristic over-redaction | via CL-05 (waived review) | RG-09 | VERIFIED-FIXED for 2-decimal amounts; other formats still redacted (fail closed, P3) |
| A6 conversionWarnings redaction | via CL-05 | RG-09 (checked early in RG-02) | VERIFIED: warnings are constants and unconsumed (static) |
| A7 settlements in balances | implemented; review PENDING ("not closed") | RG-04 | VERIFIED-FIXED on the shipped VM path (static); no visible effect because nothing writes group_settlements (see RG04B-1) |
| A8 money-batch assertion landmine | closed by owner acceptance | RG-09 | NOT A LANDMINE (test values hand-derivable; test builds aggregates directly, so it does not exercise the builder) |
| RP-03 S2 asset recovery (six gaps, J115) | no closure record | RG-01 | R1, R2, R3, R4a, R4b, R6 VERIFIED-FIXED (static, 0b44cd4d); R5 NOT-VERIFIED / NEEDS_RUNTIME |
| P07-009 orphan-check residual | verifier says P1 stands | RG-08 | VERIFIED-FIXED (transient failures fail closed; recurring_occurrences orphan check active; sourceType false-orphan refuted for current writers) |
| raw_money_aggregates 83-84 -> 25/25 PASS | no recorded fix/baseline [lead: no raw-money baseline file] | RG-17 | EXPLAINED: genuine source fix in 73c4e4c4 (guard/allowlist/scope unchanged; no baseline mechanism); semantic correctness -> E-01 |
| Cancellation ratchet 125 -> 112 | claimed approvals | RG-17 | EXPLAINED: removal-only shrink, no growth; 24 allowlist additions at 0b44cd4d approved only in agent-written records; all 77 entries expire 2027-01-01 (human decision) |
| 76-failure tail (W2) vs 0 failures (W5) | unmapped | RG-09 | NOT MAPPED item-by-item (out of RG-09 budget); W5 candidate-only app-check had 0 failures/276 skips (skips accounted in RG-17) |
| Wave 3 and Wave 4 independent strict review | PENDING per handoffs | RG-11..RG-15 | UNKNOWN |
| M-3..M-5 money track | unstarted as of 2026-10-04 | RG-06/RG-16 | UNKNOWN |
| Legacy contribution-currency attribution decision | owner decision status UNKNOWN | RG-16 | UNKNOWN |

## Wave 5 items

| Item | Claimed | Review on record | Slice | Status |
| --- | --- | --- | --- | --- |
| F1, F2, F3, F6, F7, F10 | fixed in ea446316 | original indep FAIL; re-review owner-waived; RG-05 first independent static review | RG-05 | F1, F2, F10 VERIFIED-FIXED; F3, F6, F7 FIXED per discovery, not re-verified; residuals RG05A-1 (P2), RG05A-2 (P3) |
| F2-GOAL-DELETE / v151 marker | fixed; approved schema + grant 429 | waived; RG-05 static review | RG-05 | VERIFIED-FIXED (migration SQL == 151.json, chain complete, backup tier, exact grant); P3 RG05B-1/RG05B-2 |
| F4 dashboard budget provenance | approved intent only; ea446316 touches dashboard budget files [lead-checked] | RG-06 static verification | RG-06 | IMPLEMENTED in ea446316; wrong-currency path closed but over-closed -> RG06A-1 (P2 NEW-REGRESSION) |
| F5 email source currency | approved intent only; no implementation record | RG-06 static verification | RG-06 | VERIFIED-FIXED in ea446316 (unknown currency -> review); email ingestion unwired in release |
| F8 SQL default EUR (v150) | accepted compatibility limitation | owner | RG-05 | ACCEPTED-LIMITATION; VERIFIED no writer relies on the SQL default |
| F9 transcript | owner retained; repo is PUBLIC (H-3) | owner | n/a (human) | HUMAN-DECIDED |
| G1 removals/grants | owner confirmed after the fact | owner | RG-05/RG-17 | HUMAN-DECIDED; RG-05b verified scope unchanged and the five removals reflect real source fixes |
| M0-M2d, B1-B8 | self-reviewed batches; matrix "Closed" | partial | RG-06/RG-16 | UNKNOWN |

## Findings

Independently verified (static) in RG-01. Details and evidence:
`release-1.0-verification-ledger.md`. None is fixed; remediation is not approved.

| ID | Severity | Class | Title | Verifier | Runtime | Confirmed | Closure |
| --- | --- | --- | --- | --- | --- | --- | --- |
| RG01A-1 | P2 (P1-class impact when it fires) | NEW-REGRESSION (61a509c0) | Worker-schedule confirmation failure after restore/export/abort latches permanent CRITICAL lock; only exit is clearing app data | V-01a CONFIRMED | NEEDS_RUNTIME (frequency) | CONFIRMED | OPEN; needs owner policy decision |
| RG01-N1 | P2 | PRE-EXISTING | Repeat restore of a foreign-device bundle silently loses receipt images; later image backups then fail | V-01c CONFIRMED (narrowed) | optional deciding test defined | CONFIRMED | OPEN |
| RG01A-2 | P3 | TEST-DEBT | Startup recovery tests mostly bypass production initialize()/resetCancellable() | V-01a CONFIRMED | n/a | CONFIRMED | OPEN |
| RG01B-1 | P3 | CHANGED residual | Published restored_<id> orphaned on SQLite/startup failure paths | V-01b RECALIBRATED | optional test defined | RECALIBRATED | OPEN |
| RG02B-1 | P2 | STILL-OPEN (CA-P08-003 residual) | Dedupe/review cloud providers persist raw parse-exception text (Android org.json echoes model output) into ai_artifacts.errorMessage, shown in Review UI; opt-in features | V-02b RECALIBRATED P1->P2 | NEEDS_RUNTIME (device) | CONFIRMED | OPEN |
| RG02A-1 | P3 (P2 if probe positive) | PRE-EXISTING | Blocklist sanitizer persists SQL/schema text in restore/export diagnostics, contrary to AGENTS.md wording | V-02a RECALIBRATED P2->P3 | NEEDS_RUNTIME probe | RECALIBRATED | OPEN; owner policy decision |
| RG02A-2 | P3 | TEST-DEBT | toDiagnosticsJson privacy tests cover a tests-only API | V-02a CONFIRMED | n/a | CONFIRMED | OPEN |
| RG02B-2 | P3 | PRE-EXISTING | Capture-toggle worker mapping not enforced after restart (receipt_matching, warranty, bill reminders) | V-02b RECALIBRATED P2->P3 | optional | RECALIBRATED | OPEN; product decision |
| RG02B-3 | P3 | PRE-EXISTING (likely) | Daily briefing generated despite proactive briefings off | V-02b CONFIRMED | optional | CONFIRMED | OPEN; product decision |
| RG02B-4 | P3 | PRE-EXISTING | Dedupe hybrid/on-device providers swallow cancellation | V-02b RECALIBRATED P2->P3 | unit test defined | RECALIBRATED | OPEN |
| RG02B-5 | P3 | PRE-EXISTING | LocationBackfillWorker logs throwables to release logcat | V-02b CONFIRMED | n/a | CONFIRMED | OPEN |
| RG04A-1 | P2 | PRE-EXISTING (CA-E-04-002 variant) | UNEQUAL group split silently becomes equal (wrong stored share, debts erased) when an active member's joinedAt is after the expense date; needs a backwards wall clock | V-04a RECALIBRATED P1->P2 | NEEDS_RUNTIME (Room test defined) | CONFIRMED | OPEN |
| RG04B-1 | P2 | PRE-EXISTING | "Settle" (one-tap settle) is UI-only: shows "settled up" while debts remain, nothing recorded, ticks vanish; the app cannot record settlements at all | V-04b CONFIRMED | optional UI test | CONFIRMED | OPEN; product decision |
| RG04A-2 | P3 | DOC-DRIFT/latent | LEGAL_PATHS names dormant SharedExpenseManager.addExpense (pre-fix validation shape) as legal path | V-04a CONFIRMED | n/a | CONFIRMED | OPEN |
| RG04A-3 | P3 | PRE-EXISTING | Raw coordinator error text (IDs, dedupeKey, exception.message) shown in groups UI | V-04a RECALIBRATED | n/a | RECALIBRATED | OPEN |
| RG04A-4 | — | — | Double-tap duplicate group expense | V-04a REFUTED (TLC duplicate check + unique dedupeKey) | n/a | REFUTED | n/a |
| RG03A-1 | P3 (P2 if drop rate non-trivial) | CHANGED (7d021502) | Deferred-capture 300 ms auth reads drop cold-start notifications permanently; timeouts mislabeled PRIVACY_DENIED/BLOCKED_PACKAGE | V-03a CONFIRMED | NEEDS_RUNTIME | CONFIRMED | OPEN |
| RG03-H1 | P3 (P2 if owner rules contract breach) | PRE-EXISTING | Deferred branch persists raw text of any non-blocked app (STORE_RAW default) before filtering, until worker purge | V-03a RECALIBRATED | n/a | RECALIBRATED | OPEN; owner policy decision |
| RG03-H2 | P3 (P2 if realistic trigger) | PRE-EXISTING | Uncaught exception from captureForRetry handoff can crash the app process (no handler) | V-03a CONFIRMED (static mechanism) | NEEDS_RUNTIME | CONFIRMED | OPEN |
| RG03B-1 | P3 | PRE-EXISTING | Live/recovery work-name mismatch -> duplicate chain; can overwrite PROCESSED with FAILED_FINAL at attempts==max | V-03b CONFIRMED | optional | CONFIRMED | OPEN |
| RG03B-2 | P3 (borderline P2) | PRE-EXISTING | Row stuck PROCESSING after claim ends its chain as success; recovered only after 10-min stale + next start/reconnect | V-03b RECALIBRATED P2->P3 | optional Room+TestDriver test | RECALIBRATED | OPEN |
| RG05A-1 | P2 | NEW-REGRESSION symptom (ea446316) | Accepted (or migration-seeded) sweep month keeps offering a dead Accept card for the rest of the 5-day window; affects users who never swept in their upgrade month | V-05 CONFIRMED | optional Room test defined | CONFIRMED | OPEN |
| RG05A-2 | P3 | mixed | 1-ULP-short goal gets a ~0.00 sweep that consumes the month | V-05 CONFIRMED | optional | CONFIRMED | OPEN |
| RG05B-1 | P3 | TEST-DEBT | Restore test does not assert marker month bounds | V-05 CONFIRMED | n/a | CONFIRMED | OPEN |
| RG05B-2 | P3 | DOC-DRIFT | Stale 58-table / Tier-1 count comments | V-05 RECALIBRATED | n/a | RECALIBRATED | OPEN |
| RG17B-1 | P2 | TEST-DEBT (T1 coverage gap) | Notification enqueue-failure transition covered only by a class-@Ignore'd test | V-17b CONFIRMED | n/a | CONFIRMED | OPEN |
| RG17A2-1 | P3 (FG-06 blocker) | PRE-EXISTING | cloud_payload allowlist matcher exempts any *CloudPayloadPolicy.kt (suffix, empty rule, symbol unenforced); orphan entry | V-17a RECALIBRATED P2->P3 | crafted fixture defined | RECALIBRATED | OPEN; fix or explicit waiver |
| RG17A2-3 | P3 (FG-06) | CHANGED | 53 inert cancellation allowlist entries (orphans) | V-17a CONFIRMED | n/a | CONFIRMED | OPEN |
| RG17B-2/3/4 | P3 | TEST-DEBT/EVIDENCE | Currency reload test ignored; no real-Keystore round trip; 28 guard skips never run on ubuntu for this tree | V-17b RECALIBRATED | requested | RECALIBRATED | OPEN |
| RG17B-5 | — | — | Ignored VAT test (test helper wrong; production VAT correct) | V-17b REFUTED (defect) | n/a | REFUTED | P3 test hygiene |
| RG06A-1 | P2 (release-blocker candidate) | NEW-REGRESSION (ea446316) | F4 gate requires a budget currency provenance no writer sets: everyone with an overall budget loses dashboard runway, Monte Carlo, Block Party, pace | V-06 CONFIRMED | Room chain test defined | CONFIRMED | OPEN; owner policy |
| RG06A-4 | P2 | PRE-EXISTING | Budget-suggestion create stores a home-currency amount labeled EUR (entity default) | V-06 CONFIRMED | n/a | CONFIRMED | OPEN |
| RG06A-3 | P3 | PRE-EXISTING | Money Radar budget risk skips partial/provenance checks | V-06 RECALIBRATED | n/a | RECALIBRATED | OPEN (fix after RG06A-1) |
| RG06A-2, RG06B-1, RG06B-2 | P3 | DOC-DRIFT / latent / display | stale comments; bare "$" -> USD (unwired); changePercent partial vs complete | accepted | n/a | ACCEPTED | OPEN |
| RG07A-1 | P2 | PRE-EXISTING (CA-P-03-001 residual) | Planner Suggested-branch full-row write can undo a concurrent auto-link (link row left; repeated AUTO_MATCH_LINK_FAILED); race window on fresh receipts | V-07a CONFIRMED | real-Room test defined | CONFIRMED | OPEN |
| RG07A-2, RG07A-3, RG07B-1, RG07B-2 | P3 | PRE-EXISTING/UNKNOWN | stale Reject overrides auto-match; merchantKey NULL write-back; recurring events without row check; warranty end-date edit | V-07a/V-07b | n/a | CONFIRMED/RECALIBRATED | OPEN |
| RG07B-3 | P3 if confirmed | UNKNOWN | warranty delivery insert for a deleted warranty may abort worker batch (FK) | V-07b NEEDS_RUNTIME | DAO test defined | NEEDS_RUNTIME | OPEN |
| RG08B-1..4, V08-NC1 | P3 | mixed | partial-PDF page count; lifestyle runCatching cancellation; stress forecast partial flag; sweep timeout reason; TIER_2 verify gap | discovery only | n/a | CANDIDATE | OPEN |
| RG11-H2 | P1 (provisional) | UNKNOWN | Swallowed failure inside nested Room withTransaction silently rolls back review approval / receipt link / create-and-link while success is reported (Room 2.7.2 real nested SQLite transactions) | V-10/11 CONFIRMED (static) | androidTest defined | CONFIRMED (static) | OPEN; runtime trigger check |
| RG11A-1..3, RG11B-1..2, RG09B-1..2, RG10-1 | P3 | mixed | warranty no-op reported retryable; sink fail-open/test gaps; strict key caller branch; bill reminder reason names; tax summary throw; OCR capability docs | discovery | n/a | CANDIDATE | OPEN |
| RG09A-1, RG09A-2, RG09A-3 | P3 | latent regression / pre-existing / latent | locale month-key mismatches (monthlyTrend unread; Home average badges); fromNormalizedRows getters | V-09 RECALIBRATED | Locale tests defined | RECALIBRATED | OPEN |
| RG12-01 | P1 | PRE-EXISTING (CA-P-03-004 residual) | Receipt text fingerprint strips all digits; different purchases on the same template treated as duplicates when any semantic field is unparsed; new image deleted; purchase cannot be captured by scan (or review prefilled with old receipt data) | V-12 CONFIRMED (static) | coordinator real-Room test defined | CONFIRMED | OPEN |
| RG12-03 | P2 | PRE-EXISTING | Export Save/Share deliver the previous date range file after range change | V-12 RECALIBRATED P3->P2 | n/a | RECALIBRATED | OPEN |
| RG12-02, RG12-04, RG13-1..3 | P3 | mixed | export spinner stuck; JSON v2 source; reminder retry cap; notification slot cancel; updateRule pre-tx read | discovery/V-12 | n/a | CONFIRMED/CANDIDATE | OPEN |
| RG16A-2 | P2 | PRE-EXISTING | SynthesisEngine STRICT savings-goal reserves subtracted in goal currency without conversion/policy -> dashboard AVAILABLE / Block Party misstated after home-currency change or for legacy goals | V-13..16 CONFIRMED (narrowed) | test defined | CONFIRMED | OPEN |
| RG16A-1, RG16A-3, RG15C-1..3, V16-NEW-1..3 | P3 (V16-NEW-1 P2/P3 unverified) | mixed | goal-delete history asymmetry; manual history not backed up; split editor restore fallback; redacted backup diagnostic tables; review denial toast; forecast pattern/likely-bill trajectory gaps | V-13..16 / discovery | n/a | RECALIBRATED/CANDIDATE | OPEN |

Per the release policy, confirmed user-impacting P2s are fixed by default unless the
human explicitly accepts the risk.

## Dispositions recorded 2026-10-18 (release orchestrator, after Phase 7 attempt 1)

- RG11-H2 (P1 provisional): CLOSED — FIXED. Code fixed in R1 (DefaultExpenseCategoryAssignmentService, ReceiptLinkService and callers now throw; JVM proof NestedTransactionSemanticsTest, ReviewQueueRepositoryNestedRollbackTest, ReceiptLinkServiceNestedFailureTest). Device mechanism confirmed on API 35 SQLite by NestedTransactionSemanticsInstrumentedTest 3/0 (vr-20261018-001835-6fa1a3a7); full connected suite 376/0/9 skipped (pre-v145 migration chains, unsupported by design) vr-20261018-001952-74f0d8b5.
- RG17B-1 (P2 test debt): CLOSED BY R13 — NotificationIntakeEnqueueFailureRoomTest (real Room, throwing operation) covers EnqueueFailed, retryable/final branches, PROCESSING no-op, terminalAt and payload purge; passed R13 targeted and batch gates. Residual P3 test debt: NotificationCaptureService enqueue-failure branch (deduper.remove) has no direct test -> 1.0.1.
