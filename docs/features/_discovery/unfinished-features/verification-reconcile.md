# Verification — docs and handoff reconciliation UF-60, UF-61 (2026-10-22)

Source: read-only Sonnet scout (no Bash). The orchestrator verified the git facts directly (marked [git]).

## Release and branch facts
- Acceptance records: `docs/releases/release-1.0-assurance/release-1.0-final-acceptance.md` (01fae80c, CONDITIONAL),
  `docs/releases/release-1.0.1/release-1.0.1-final-acceptance.md` (f150a1ac, CONDITIONAL),
  `docs/releases/release-1.0.1/release-1.0.2-acceptance.md` (candidate 27db3a03, CONDITIONAL: production signing,
  manual smoke on the release APK, guard review before 2026-12-31).
- [git] `bug-fixes` = 8e449c6f = 0a52b066 + 10 statement-import commits. 8e449c6f is NOT in HEAD (044be2c8). The
  merge is explicitly not approved (`build/release-lead-notes/owner-approvals-20261022.md`, "Not approved").
  `three-track-execution-plan.md:46` wrongly says the integration branch contains 8e449c6f.
- [git] Worktrees: main = feature/ai-evolution 044be2c8 (dirty); `Cost-agregator-mc` = feature/money-correctness at
  044be2c8 (clean tip, no MC commits); `Cost-agregator-release-b` = fix/statement-import 8e449c6f;
  `-release`/`-ai-merge` = 9de3f646; `-tests` = chore/test-suite-audit.
- **Start gate "Release 1.0 accepted and release-1.0-wt merged": SATISFIED** (code baseline; not a store release).

## (A) Handoff status
| File(s) | True status | Action |
|---|---|---|
| `release-1.0-assurance-handoff.md` | CLOSED (superseded by the 1.0, 1.0.1 and 1.0.2 acceptance records) | Update header, then archive |
| `money-w5-independent-remediation-20261013-handoff.md`, `money-w5-independent-review-handoff.md` | CLOSED (owner acceptance; F1-F10 remediated, RG-05) | Archive |
| All other `money-w5-*` handoffs and plans | SUPERSEDED by ea446316 and the release line; residuals are in `release-1.0.1-backlog.md` (CURR-6, FORE-04, F-H1-c) | Header "SUPERSEDED", then archive |
| `wave2-*` (35 files), `wave3-*`, `wave4-*` | SUPERSEDED (ancestors of the release line; re-verified in Release 1.0 Phase 2; deferred items in the 1.0.1 backlog) | Archive |
| `money-m0..m2d` | SUPERSEDED by Wave 5 (m2a code shipped) | Archive |
| `handoff-test-diagnosis-20260919.md` | STALE (followed by test-suite audit 5414696f) | Archive |
| `local-build-performance-20261014-handoff.md` | CLOSED (14a2e6ce) | Archive |
| `opportunity-scan-handoff.md` | Finished; superseded by planning-finalization. **Line 57 is false** (the negotiation label never landed) | Correct line 57; keep or archive |
| `planning-finalization-handoff.md`, `home-insight-gap-handoff.md` | Open, still referenced | Keep |
| `app-lock-privacy-mode-handoff.md` | Header stale ("STAGE 1 Brief running"); plan v2 APPROVED 2026-10-17 | Update header |
| Approved-plan handoffs (ai-fixes, nl-quick-add, money-correctness, MEC, loan, cloud-alias, assistant-follow-ups, briefing) and `ai-evolution-handoff.md:13` | Headers still say "PARKED until Release 1.0 accepted"; the gate is now satisfied | Update headers |

## (B) Build order
- Global order (`ai-evolution-handoff.md:108-111`): Release 1.0 → ai-fixes → money-correctness → MEC core (a) →
  loan S1..S10 → nl-quick-add inc 1 → MEC (b) → MEC (c) → loan S11 → nl-quick-add 1b → MEC 1b. Also: briefing S-fix
  before ai-fixes B3a; assistant-follow-ups after ai-fixes + MC 1a, before nl-quick-add.
- The three-track lane proposal (`build/release-lead-notes/three-track-execution-plan.md`) runs MC 1a in parallel with
  ai-fixes (it deviates from the global order). The lane split is an orchestrator default; **no explicit owner GO**
  (its decision 1 is still open).

## (C) Dirty tree (main worktree)
- Money-correctness 1a-1: recorded in `build/release-lead-notes/mc-1a1-record-20261022.md`. Validated (targeted
  64/0, shards, db_access, db_artifact_sync, static-guards; golden 18/18 after a reviewer-strict MINOR). **Uncommitted.**
- **MC 1a-2 is partly in the tree, unrecorded and unvalidated:** `ExpenseRepository.kt`, `TransactionsViewModel.kt`,
  3 test files, plus the `db_raw_query_classification.yml:52` row (owner-approved item 7).
- `money-correctness-handoff.md` is stale (still PARKED/planned).
- Also dirty: `.claude/agents/*` and `CLAUDE.md` (owner edits), `gradle.properties` (unattributed), and plan-doc
  edits from the W0 preflight.
- The MC work sits in the main worktree, not in the `Cost-agregator-mc` worktree created for it.
- **Risk: HIGH** for starting any other implementation here.

## (D) FEATURES.md corrections (UF-60)
- Header/footer: feature counts 22 vs 28 vs 43; DB version 52 vs 47 vs 68 (actual **151**,
  `AppDatabase.kt:33`); date March 31 2026; branch, commit and file counts stale; duplicate feature numbers.
  `FEATURE_DOCUMENTATION_COMPLETE.md` inherits the same errors (:148-156, :168, :188, :208, :234, :356, :440).
- Status over-claims ("Complete"):

| Feature | Lines | Actual state | Ref |
|---|---|---|---|
| Investment #12 | :485-518 | Hidden in release; no add UI | UF-31 |
| Bank API #9 | :523-542 | Demo stub | UF-30 |
| Shared Budgets #11 | :604-619 | Dead code | UF-34 |
| Tax #15 | :641-656 | Hidden; demo data; US bracket bug | UF-32 |
| Challenges #17 | :684-704 | Hidden; mixed-currency sums | UF-33 |
| Price Protection #11 | :928-955 | Simulated | UF-20 |
| Bill Negotiation #12 | :897-914 | Static seed; non-EUR skipped; "AI scripts" unverified | UF-21 |
| NL Search #6 | :959-989 | Voice not wired; filters unapplied | UF-11, UF-40 |
| Warranty #14 | :32-60 | No edit; dead empty-state actions; hardcoded return windows | UF-02, UF-03 |
| Bill Reminders #16 | :660-680 | "Mark as paid" absent; legacy path | UF-43 |

- Not spot-checked: Carbon, Lifestyle, Enhanced Split, Phases 1-3.
