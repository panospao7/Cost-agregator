# Decision sheet: quick-fix batches + deep-link interim (2026-10-22)

Answer by ID: "accept all recommendations" or override individual items.
Sources: `impact-map-qf1.md`, `impact-map-qf2.md`, `impact-map-qf3.md`, and
`../deeplink-diag-hardening/impact-map.md`.

## Already settled by facts (no decision needed)
- **Q-T translations:** new English-only strings fail `:app:lintDebug`. The baseline holds 2223 keyed
  MissingTranslation entries and any new key is a new error. So every new key ships in EN + EL + ES.
  - QF-2 and QF-3 add no strings.
  - QF-1 adds about 4 keys, in a per-slice `strings_release_truth.xml` (the app-lock convention).
  - **Open: who supplies the Greek and Spanish copy?** Options: the implementer drafts it and you review, or you
    provide it.
- **Price Protection hide needs no `MainActivity` edit.** The feature has no entry point other than the menu and
  saved-state restore, and both are filtered.
- **The QF batches touch none of your in-progress files** (checked with git in both worktrees).

## QF-1 "release truth" (batch T2)
| ID | Question | Recommended | Cost of the alternative |
|---|---|---|---|
| D-PP | Price Protection | **Hide in release** (DeferredFeatureGate, T1) | Stripping the invented card benefits and SAVE10 means deleting ~9 tests (needs your approval), and the result is still not honest (fake deals, simulated drops, hardcoded return windows remain) |
| D-PP2 | Render-branch redirect in `MainActivity` too? | **No** (menu + restore filters suffice) | One more `MainActivity` writer before app-lock S10 |
| D-NEG | Bill negotiation | **Honest labels for everyone**: a "reference prices, Greece, EUR, approximate" note, the count of skipped non-EUR subscriptions, and an honest empty state | Hiding for non-EUR users needs a data-dependent menu filter in `HomeScreen`, which conflicts with briefing S3/S4 |
| D-NEG2 | Skipped count includes which skips? | **Non-EUR only** | Mixing in invalid-amount and energy skips confuses the message |
| D-NEG3 | Fix the "compare to cheapest streaming service" fallback now? | **Defer** (business-rule change, T2) | — |
| D-FT | € fix in the follow-through text | **Text only now.** The raw-amount comparison goes to the money sweep. | Fixing the comparison touches `ExpenseDao` (your MC work) and is T3 |

## QF-2 "dead buttons" (batch T1, no new strings)
| ID | Question | Recommended |
|---|---|---|
| D-SB | Subscription empty-state "scan_bank" action | **Remove it.** Leave its strings in place so the lint baseline doesn't move. |
| D-MR | Money Radar "Adjust budget" goes to… | **Budget screen.** Also fixes MEDIUM risk, which today does nothing. |
| D-MR2 | Hide the "On Track" chip at LOW risk? | **Yes** |
| D-MR3 | Record D-MR in the briefing-grounded-signals S3 plan, which currently says "MEDIUM → Analytics"? | **Yes** (a one-line doc note at its S0; doc lock) |
| D-NL | Search result tap opens… | **Transactions filtered to that day, with the row highlighted.** A real detail screen comes later (Track C). |
| D-NS | Notification settings fail to open | **Do nothing silently** (no new string) |
| D-WE | Warranty "Add manually" fix | **Ship together with the Scan-receipt fix** |

## QF-3 "stale comments and dead code" (T1, no guard changes)
| ID | Question | Recommended |
|---|---|---|
| D-BETA | Remove the dead Beta badge path | **Remove** (string and code together, to avoid an UnusedResources failure) |
| D-VOICE | Voice search | **Delete only the unused VM method.** Keep the speech stack for possible future voice search. |
| D-OPP | Fix the false line in the finished `opportunity-scan-handoff.md` | **Yes**, unless your other sessions are editing it |
| D-MER | Manual-expense comment fix | **Fold into the ai-fixes / MEC slice** that edits that file anyway |
| D-PDF | Accountant PDF TODO | **Defer to after MC 1a-G** (QF-3c), together with the business-flags comment |

## Deep-link interim (T3, Lane C W1)
| ID | Question | Recommended |
|---|---|---|
| D-DL | Ship the interim hardening before app-lock S10? | **Yes.** Any app can currently write the briefing key and suppress a future briefing notification. |
| D-DL2 | Save the key only when it equals the last delivered briefing | **Yes** (closes the suppression hole; this is the app-lock spec rule) |
| D-DL3 | Analytics period tokens file | **Leave to app-lock S3**; canonicalise inside the validator for now |
| D-DL4 | Track "Home keeps showing an old briefing after you open it" as a separate item | **Yes** (new backlog item UF-62) |

## Sequencing (needs your OK)
- **Lane B W1, one writer:** QF-3a → QF-1 → QF-2a → QF-2b → QF-2c → QF-2d → QF-3b.
- **Lane C W1:** the deep-link interim.
- Both lanes run in fresh worktrees cut from `feature/ai-evolution` 044be2c8.
- Every `MainActivity` hunk (QF-2d and the deep-link interim) merges before app-lock S10 (W3), one writer at a time.
- **Validation:** one combined run per batch by validation-runner (compile, a targeted filter, lint when strings or
  resources change, static-guards once). No full app-check until a gate.

## Also needed before writing code
- **Commits:** implementation needs commits on lane branches. Please confirm that each slice gets committed on its
  lane branch (never merged or pushed without your request).

## Owner answers (2026-10-26)
Owner: "do your recommendations". **Every recommendation above is accepted as written**, along with the sequencing.
- D-PP hide · D-PP2 no · D-NEG honest labels · D-NEG2 non-EUR only · D-NEG3 defer · D-FT text only.
- D-SB remove (keep strings) · D-MR Budget · D-MR2 yes · D-MR3 yes · D-NL day filter + highlight · D-NS silent ·
  D-WE together.
- D-BETA remove · D-VOICE VM method only · D-OPP yes · D-MER fold into ai-fixes/MEC · D-PDF defer to QF-3c.
- D-DL yes · D-DL2 yes · D-DL3 leave to app-lock S3 · D-DL4 yes (UF-62).
- **Greek/Spanish copy:** the implementer drafts it. **The owner signs off on the el/es text before merge.**
- **Commits:** each slice is committed on its own lane branch, and lane branches may be pushed. Merges and PRs still
  need an explicit owner request.

## Plan gate (2026-10-26)
Owner: "I approve". `workflows/active/uf-quick-fixes-plan.md` is APPROVED together with orchestrator amendments
A-1..A-8. It is not startable while the owner keeps "planning only".
