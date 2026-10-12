# M-0 money inventory handoff

Date: 2026-10-03. Branch: `bug-fixes`. Source checkpoint: `a1c66173219d7d959b0d36859a68176aaf814242`.

## Resume here

Read `workflows/active/money-m0-inventory-20261003.md` before proposing a money patch. It is the source-reconciled inventory and slice/contract plan for the money track in `wave4-money-batch-plan-20261001.md`.

M-0 discovery is recorded; independent money/architecture review remains pending. The M-0 batch itself made no production changes and ran no tests, builds, scanners or guards. Subsequent local contract review and M-1a implementation are recorded in `workflows/active/money-m1a-20261003-handoff.md`. M-1b is now authored, with validation deferred by the user; resume from `workflows/active/money-m1b-20261003-handoff.md`. Preserve the user's no-subagent and user-run-validation workflow.

## Finding reconciliation

- 83 persisted raw-money findings / 28 production files, all mapped to source, owning segments, consumers and proposed slices.
- 69 financial sums: 47 with an inspected normalization path, 13 same-receipt/source-currency sums, and 9 absent/bypassable currency preconditions (including five on the deprecated raw personality method).
- 10 aggregate scalar contracts; 4 candidate scanner-scope matches. Candidates remain open, not suppressed.
- Rule counts: RAW01 5, RAW02 55, RAW03 5, RAW04 4, RAW06 14; RAW05/RAW07 zero.
- Evidence is the historical static run `vr-20261003-164616-50beeeac`: suite FAIL / exit 1, 24 of 25 guards passed, raw-money sole failure. This is not a fresh current-document fingerprint run.

## Priority risks

1. Money radar privately fetches original expense rows, then compares their raw sum with budget/recurring inputs. Its caller-normalization comment cannot normalize those fetched rows.
2. Cash-flow UI sums original metadata rows while its domain calculator already has normalized balance aggregates. Expose/consume those aggregates; do not do FX in Compose.
3. Savings contribution history omits event currency. Legacy attribution requires an explicit owner decision, not today's home/goal currency.
4. Synthesis still permits a blank-currency legacy sum; the deprecated personality self-fetch path is also not normalized, although the inspected production ViewModel uses its canonical normalized overload.

## Proposed next batch

M-1a and M-1b are now authored, pending independent review and user-run validation. The remaining money slices have not started. Local review corrected `CurrencyCode.parse` support assumptions and M060's tax-comparison description; no guard contract was changed.

**M-1a: receipt/source-currency producer boundary.** Scope `ReceiptParser`, only the required existing `domain/core/money` boundary, direct parser/money tests and minimal DTO consumers if changed. First pin optional total/subtotal, currency resolution, confidence/tax tolerance and rounding contracts. No broad Double/BigDecimal replacement or provider/lifecycle refactor.

After the shared contract is pinned, bring the isolated M-4a radar/cash-flow fixes forward where feasible. Do not treat the numbered phases as a reason to defer confirmed unsafe consumer arithmetic. M-2a's normalized-slice adapter must retain exclusions/provenance; `fromHomeCurrencyAmounts` alone currently drops that context.

## Gates and stop conditions

- Strict independent money/architecture review before implementation validation; current inventory is not reviewer approval.
- User runs compile/focused filters and required registered guards serially through `scripts/validation-runner.ps1`; no direct Gradle or overlapping processes.
- Reject guessed home currency, hidden all-failed zero totals, double conversion, loss of quality/affected counts, baseline/allowlist expansion, scanner-shaped sum disguises, and migrations without explicit reviewed data policy.
- Scanner-scope candidates require narrowly reviewed guard semantics/tests under `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-03, FG-06, FG-07 and FG-23.
- Device rendering and deferred Wave 4 review/device items remain separate outstanding gates. No release closure claim.

## Worktree boundary

M-0 documentation paths: this handoff, `money-m0-inventory-20261003.md`, and the active plan's M-0 status/link. No commit was requested for this batch.

Unrelated work remains untouched: `.codex/agents/orchestrator.toml`, `.codex/config.toml`, `.codex/agents/explorer-lite.toml`, campaign prompt, `### VERDICT.txt`, CL-29 Wave 1 report, architecture coverage matrix, and line-ending-only `config/guards/db_ownership_policy.yml` dirtiness. Do not blanket stage, reset, normalize or delete those files.
