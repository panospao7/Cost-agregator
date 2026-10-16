# Release 1.0 — Phase 3 (horizontal) + Phase 4 (vertical) consolidated result (lead draft, untracked)

Candidate: C* = release-1.0-remediation @ 0a9cab1c. Static, read-only audits plus independent verifiers. No runtime proof unless
stated. Detail and file:line evidence: build/release-lead-notes/pending-ledger.md. To be copied into the tracked verification ledger
only after the final gates finish (STALE_RESULT rule).

## Coverage status
- Phase 3: HZ-01, HZ-01b1, HZ-01b2, HZ-02..HZ-08 run. All PARTIAL (budget caps); residual cells listed per slice in the ledger.
- Phase 4: V-A..V-I cover VJ-01..VJ-24. All PARTIAL; residual cells listed per slice.
- Process deviations: V-H overran its call cap (54/35, read-only); earlier three-worker overlaps already disclosed.
- Almost every confirmed defect below is PRE-EXISTING (predates campaign kit 84d819be, many predate Wave 5). The campaign's own
  fixes (R1-R15) produced no confirmed regression in these passes.

## Confirmed P1 (release blockers; owner approval needed for fix batches)
| ID | Defect | Proposed batch |
|----|--------|----------------|
| V-A(A) | Comma decimal deleted: "12,50" saved as 1250 (AddExpense amount/share, receipt scan, budget) | R16 |
| V-A(B) | No release-visible notification-listener access check/prompt/status -> capture silently empty | R17 |

## P1 if owner says so / needs runtime
- V-C2 bill reminders stop after 12 months (P2; P1 if reminders are a 1.0 headline feature).
- V-I-6 BOOT_COMPLETED dataSync FGS on Android 15 (P2, ~70% no crash; P1 if the reboot device test crashes). Small safe fix regardless.
- HZ-02 F3 crash loop for databases < v145 (P3; P1 if any shipped build had schema < 145 — owner H-2).

## Confirmed P2 (grouped into proposed batches)
- R18 Data integrity and crash class:
  - V-G-2 deleting a shared row's linked personal expense CASCADE-deletes the group expense (silent group balance change).
  - V-F-1 write-barrier exception crashes Savings/Home VMs during backup export (needs a full VM sweep).
  - V-I-6 boot FGS skip on API 35.
- R19 Feature scope (owner H-1), cheapest as hide-for-1.0:
  - Tax: VAT percent/fraction, US brackets, unsaved country/currency, missing disclaimer, false "not exposed" comment.
  - Investment: a dead-end tile.
  - Challenges: raw currency sum, outcome lost.
  - Negotiation: unlabelled estimates (P3).
  - Privacy tile label (P3).
  - "Voice search (Coming soon)".
- R20 Money correctness (shown numbers):
  - HZ01-A subscriptions raw foreign sum.
  - HZ01-B stress forecast drops foreign obligations.
  - HZ01b1-F1 savings sweep ignores upcoming bills (its test pins the defect).
  - V-E-12 analytics % change compares unequal windows.
  - V-G-1 export end date excluded / UTC bounds.
  - V-D F-2 forecast not reactive.
  - V-D F-1 / CURR-6 home-currency switch breaks amount sort and filter (documented deferral; candidate to keep deferred).
- R21 Recurring and reminders:
  - V-C1 "Mark as Paid" only advances nextDate.
  - V-C2 no occurrence window extension.
  - V-C3 reminder burst after permission grant.
  - V-C4 recurring default EUR.
  - HZ-05 F1 daily briefing REPLACE postpones indefinitely.
- R22 Bank-statement import parser:
  - V-I-1 1000x thousands.
  - V-I-2 sign dropped.
  - V-I-3 US/ambiguous dates.
  - N1 and N2 still unverified.
  - Overlaps R16 parsing; share one amount parser.
- R23 Rates and backup disclosure (owner decisions):
  - HZ01b2-F1 ECB rate refresh gated on cloud-AI consent, with a raw-code and misleading "offline" message.
  - V-F-3 default backup silently drops receipt images; a doc claims they are included.
- R24 UX and error surfacing:
  - V-A ReviewViewModel drops error messages.
  - V-A POST_NOTIFICATIONS re-prompts on every launch.
  - V-B blank OCR currency defaults to home.
  - V-B WarrantyTrackerViewModel swallows CE, shows raw message, has unguarded writes.
  - V-I-7 no onboarding (product).
- Guard/process (deferred guard hardening, owner review 2026-12-01):
  - HZ-08 F1 android.util.Log throwables invisible to the PII guard.
  - HZ-08 F2 RecurringArchitectureGuardTest contradicts LEGAL_PATHS :524.
  - HZ-03 F1 post-swap restore lock when the Activity is destroyed (P2; Room/lifecycle-risky; candidate for R18 or 1.0.1).

## P3
About 50 P3 items (latent paths, debug-only, dead code, copy, raw e.message in UI, CE swallows in VMs, export number formatting,
retention doc gaps, etc.). Proposed: record them and defer to 1.0.x unless trivially bundled with a batch above.

## Lead recommendation (for owner decision)
Minimum stable-1.0 set:
- R16 and R17 (P1).
- R18 (data loss + crash).
- R19 as HIDE (Tax, Investment, Challenges or fix; remove "Coming soon"; fix privacy label).
- R22 restricted to the amount parser shared with R16.
- From R21, V-C1 + V-C2 (if reminders ship).
- The R23 owner decisions (copy-only fixes are cheap).
- Defer R20 items marked deferrable, R24 and P3s to 1.0.1 with release notes, OR include R20 if the owner wants correct numbers
  everywhere before 1.0.

Every batch: release-planner plan -> remediation coder -> targeted tests -> reviewer-strict (money/lifecycle/Room) -> then ONE
consolidated final-gate cycle on the new C* (unit-tests, app-check, static-guards, assemble-release, evidence, device runs).
The current blocked part-3 gates on 0a9cab1c become moot once code changes; the runner dedup fix (option 2) is still recommended
before that cycle.
