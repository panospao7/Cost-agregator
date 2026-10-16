# manual-entry-currency (increment 1) — Gate 2 decision sheet

Prepared by the orchestrator from `domain-spec.md` (OD-1..5, RF-1..6), `ux-flows.md` (UX-Q-1..7), `impact-map.md` §0, §9, §10 (C-1..C-4, S-1..S-6, RF-7). Date 2026-10-16.
Status: DECIDED 2026-10-16 — owner: "recommended" → G2-1..G2-19 = Recommended; G2-20 release routing NOT sent (no explicit "send to release").

| # | Decision | Recommended |
|---|---|---|
| G2-1 | **Global post-1.0 build order** for shared files (AddExpense VM/Sheet, TLC, CreateExpenseRequest/Result, ReceiptScan, strings, LEGAL_PATHS): Release 1.0 (incl. R16, R23 part 1) → ai-fixes → money-correctness-1.0.x (if specced in time) → MEC core (a) → loan S1..S10 (S3/S6a serialised vs TLC work) → nl-quick-add inc 1 → MEC (b) add-expense UI → MEC (c) receipt review → loan S11 built directly on the general control → nl-quick-add inc 1b → MEC inc 1b | **Approve** (changes earlier "loan first after 1.0" assumption: loan S1..S10 still early, only S11 moves later) |
| G2-2 | If money-correctness-1.0.x isn't specced when MEC (a) is due: MEC (a) goes first (adds a flagged branch beside the existing one; money-correctness rebases) | **Yes** |
| G2-3 | OD-1 per-currency cap scope: one per-currency cap table used by the validator (all TLC creates), manual repo, add-expense VM **and** review approval (`ReviewQueueRepository.kt:157`); notification parser/pipeline caps (currency unknown there) routed to their owners; `AmountUtils.isValidAmount` left alone | **A + review approval** |
| G2-4 | OD-2 BGN/RUB removed from new selection via a feature-local list (not inactive-like-HRK, which would touch home picker, budget autopilot, bill reminders) | **Feature-local list** |
| G2-5 | OD-3 stored snapshot vs analytics read-time conversion may differ near midnight for flagged rows → accept; align in money-correctness | **Accept** |
| G2-6 | OD-4 + S-4: MEC core (a) lands before loan S11; edit the approved loan plan (S11 write set uses the general control; D-8 answered = TLC refusal via flag; S6a `AUTO_RECORD_DEFERRED_FX` revisited — today TLC never refuses unflagged) at loan S0 | **Approve** |
| G2-7 | OD-5 batch receipt review stays unflagged (no block) in inc 1 | **Yes** |
| G2-8 | S-3 (C-3): core slice ships with NO production flag setter; setter + VM rounding removal land together in the add-expense slice | **Yes** |
| G2-9 | S-2: removing add-expense's silent HALF_UP rounding — home-currency "12.345" becomes an inline "too many decimals" error instead of saving 12.35 (behaviour change for all manual entries) | **Accept** |
| G2-10 | S-1: new architecture guard — only the three approved places may set the user-present rate flag (manual entry, single-receipt review save, loan declare) | **Approve** |
| G2-11 | S-5 / RF-1: every create (incl. refused retries) writes merchant + amount + currency into the audit event reason text (`TransactionLifecycleCoordinator.kt:397`) — fix to bounded codes inside MEC (a) since refused retries add rows, privacy-security-guardian to confirm | **Fix in (a)** |
| G2-12 | S-6: on refusal the bounded audit/failure events commit while no expense row is written (vs full rollback) | **Events commit, no row** |
| G2-13 | UX-Q-1 "Open exchange-rate setting" from a filled form: keep the form if the host allows, else confirm first when an amount is entered | **Yes** |
| G2-14 | UX-Q-2 receipt with no OCR currency: preselect home with a "check it" caption (as today) rather than strict unset | **Yes** |
| G2-15 | UX-Q-3 no "recently used" currency group in inc 1; UX-Q-4 keep "Refresh rates" on the stale card with honest post-refresh copy; UX-Q-5 quick-save confirm surface settled in the receipt slice; UX-Q-6 installments + unsupported selection → unset, restore on switch-off (loan UX doc follow-up) | **Yes** |
| G2-16 | UX-Q-7 + copy: setting name comes from R23 part 1; ~40 strings EN/el/es — owner reviews Greek, Spanish native review follow-up | **Yes** |
| G2-17 | RF-2 possible date-picker zone bug (`AddExpenseSheet.kt:841-855`, users west of UTC may get the previous day): read-only debugger check before the add-expense slice (b) | **Yes (check before b)** |
| G2-18 | RF-7 `ReceiptParser.kt:690` silently drops OCR totals > 50,000 (JPY/KRW/HUF/ISK receipts) → receipt owner / follow-up list; RF-6 `receipt.parsedDate` zone checked in slice (c) | **Follow-up / slice (c)** |
| G2-19 | Risk tier: all four parts T3 (money, TLC + result contracts, receipt lifecycle, cross-feature); reviewer-strict + architecture-guardian on each; privacy-security-guardian on (a); no schema, no migration tests | **Accept** |
| G2-20 | Release-track routing (R23: setting default, periodic refresh, BGN/RUB home error; TLC no-rate log; V-C4 note) — still NOT sent; owner must say "send to release" explicitly | **Owner's call** |
