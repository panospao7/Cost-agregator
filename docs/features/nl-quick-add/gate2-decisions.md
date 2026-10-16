# nl-quick-add (increment 1) — Gate 2 decision sheet

Prepared by the orchestrator from `domain-spec.md` §13 (OD-1..5), `ux-flows.md` (UX-Q-1..6), `impact-map.md` §8. Date 2026-10-16.
Status: DECIDED 2026-10-16 — owner: "recommended" → every G2-n = Recommended.

| # | Decision | Recommended |
|---|---|---|
| G2-1 | OD-1 pre-existing clipboard bug ("12,50" copied → amount 1250): fix as its own small slice (clipboard channel otherwise unchanged) | **Yes** |
| G2-2 | OD-2 + UX-Q-5: bounded localized errors also for the VM `Result.Error` branch (:415-425); localize the 4 hard-coded validation messages; scroll to the save-error card + assertive live region | **Yes** |
| G2-3 | OD-3: bare leading number + word ("2 καφέδες", "15 taxi") → flagged as quantity, amount empty | **Flag** |
| G2-4 | OD-4: weekday name equal to today's weekday → flagged, date stays today | **Flag** |
| G2-5 | OD-5: ISO codes outside the app's catalog ("thb") block the amount only when adjacent/attached to the number | **Yes** |
| G2-6 | New guards (stop-and-ask) G-1 no `rememberSaveable`/`SavedStateHandle` in add-expense; G-2 quick-add package import boundary; G-3 no interpolated `Timber(e, …)` in AddExpenseViewModel; G-4 category lookup never calls the side-effecting `categorize`/`normalize`/`getCategoryForMerchant`. In a NEW `QuickAddPrivacyArchitectureGuardTest.kt`; G-1/G-3 scoped to add-expense | **Approve** |
| G2-7 | Validation-config edit (stop-and-ask): add `domain.quickadd.*` to the `domain-n-z` shard in `config/validation/test-shards.json` (otherwise sharded runs silently skip the parser tests) | **Approve** |
| G2-8 | Guard edit (stop-and-ask, removal only): remove `AddExpenseViewModel.kt` from CancellationSafety KNOWN_VIOLATIONS once its catches rethrow cancellation; never add entries | **Approve** |
| G2-9 | Architecture doc corrections: LEGAL_PATHS §Categorization (stale "no persistent side effects" — `MerchantNormalizer.normalize(autoCreate=false)` writes alias rows) + read-only lookup entry + FORBIDDEN line; ENGINE_INTERACTION_MAP notes; prefill-aids note in §Expense Mutations | **Approve** |
| G2-10 | Package ownership: new `domain/quickadd` registered under Segment 9 (Core Expense) | **Segment 9** |
| G2-11 | UX-Q-1 category source enum (exact name vs history) for the category chip; UX-Q-2 `clearCategory()` (Remove on chip counts as user edit, never re-filled) | **Yes** |
| G2-12 | UX-Q-3 no extra caption when Fill overwrites a clipboard amount; single "Fields you edited were kept." line | **Yes** |
| G2-13 | UX-Q-4 fill summary stays as the description of the last Fill after later edits (only markers clear) | **Yes** |
| G2-14 | Empty-category caption ("assigned automatically when you save") shown in every add flow, not only after quick-add (small behaviour change for plain add-expense) | **Yes** |
| G2-15 | UX-Q-6 autocorrect off on the line (Compose can't disable IME personalised learning — not a privacy guarantee); Greek + Spanish copy need native review (owner can review Greek) | **Yes; owner reviews Greek at plan/implementation** |
| G2-16 | Sequencing: after ai-fixes (B2d); never in parallel with loan-installments S11 (same AddExpenseViewModel/Sheet) — quick-add lands before loan S11, which rebases | **Approve** |
| G2-17 | Out of scope → follow-ups: hard-coded "(€)" amount label / "Euro" content description (→ manual-entry-currency); spec §15 five pre-existing defects; runner has no shard-coverage check (new packages silently skipped — general validation gap) | **Follow-up list** |
| G2-18 | Risk tier: T2 (upper end) overall; guard slice T3 by rule. reviewer-strict on parser money rules + fill contract; privacy-security-guardian on lookup, diagnostics, guards; architecture-guardian on guards + docs; no migration tests. Accept residual risks R2 (category false match, mitigated by visible removable chip), R3 (concurrency), R4 (locale parsing, mitigated by 132-case fixtures) | **Accept** |
