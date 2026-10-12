# M-1b receipt-item categorization and tax-allocation handoff

Date: 2026-10-03. Branch: `bug-fixes`. Base checkpoint: `a1c66173219d7d959b0d36859a68176aaf814242`.

## Status and resume point

The user authorized M-1b only and explicitly deferred validation. The on-device producer, additive typed result contract, and 21 new regression tests are authored. Builds, tests, scanners, and guards are NOT RUN. No subagents, guard/baseline/policy edits, staging, commit, or push were used. Local author review is not independent strict money/receipt approval. M-2 through M-5 remain unstarted.

Read this handoff, `money-m1a-20261003-handoff.md`, and the source diff before further implementation. Preserve the unrelated dirty files. Keep one stable worktree throughout the eventual serial validation queue and write evidence documentation only after it finishes.

## Scope and local review

- Segment 5 owns receipt-item categorization; Segment 16's existing money builder owns same-source-currency aggregation. No new money system or FX conversion is introduced.
- `ReceiptItemCategorizationInputBuilder` already passes `ScannedReceipt.currency` and `parsedTaxAmount`, including when ephemeral items are used. Its production implementation is unchanged; new tests pin that contract, including unresolved currencies without a home-currency fallback.
- Historical findings M001/M002 are the all-item denominator and category subtotal in `OnDeviceReceiptItemCategorizationService`. Both now use M-1a's `MoneyAggregateBuilder.fromSourceCurrencyAmounts`. This is source migration, not fresh guard closure or a new finding count.
- AI capability checks, hybrid/cloud routing, category matching/confidence/rationale, privacy sanitization, receipt lifecycle, persistence methods, and artifact serialization are unchanged. Empty item lists now have finite zero confidence and require review instead of publishing NaN confidence.

## Files touched

Production:

- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/OnDeviceReceiptItemCategorizationService.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/model/ReceiptItemCategorizationModels.kt`

Tests:

- `app/src/test/java/com/yourname/expensetracker/data/ai/provider/OnDeviceReceiptItemCategorizationServiceTest.kt` — 18 new tests, 1 existing test retained; NOT RUN.
- `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/ReceiptItemCategorizationInputBuilderTest.kt` — 3 new tests, 3 existing tests retained; NOT RUN.

Documentation: this handoff, the M-0 inventory/handoff, M-1a resume status, and M-1 status in `wave4-money-batch-plan-20261001.md`.

## Implemented contract

`ReceiptItemCategorizationResult.taxAllocation` is an additive typed property. The on-device producer always supplies one of these states:

- `NotRequested`: tax is absent. No tax arithmetic or artificial successful zero allocation is performed. This does not independently validate unrelated item/currency fields.
- `Available`: source-currency all-item/category `MoneyAggregate` values, typed requested tax, typed category amounts, and typed unallocated tax. Aggregate counts and identity-rate provenance are retained.
- `Unavailable`: a controlled `MoneyAggregateResult.Unavailable` reason and available failure metadata. Missing/malformed/unsupported source currency, non-finite values, overflow, negative tax, or positive tax without a positive denominator cannot become a successful tax map. The result requires review.

For valid positive tax, existing weights are preserved: `totalTax * (categoryTotal / allItemTotal)`. All items stay in the denominator. Unclassified items and nonpositive category IDs receive no published category share; their share is not redistributed to known categories. Finite signed item adjustments retain the prior proportional behavior when the net denominator is positive. Negative values are valid only when the caller has classified them as explicit discount/refund adjustments; arbitrary negative product prices are not implicitly accepted.

Tax shares are allocated in source-currency minor units using HALF_EVEN for the requested tax and a largest-remainder distribution for the shares, with item order as the deterministic tie-break. Item shares, category shares, and `unallocatedTax` are all required to conserve the rounded request exactly. Fractional JPY values and sub-cent contributions therefore cannot disappear into an implicit floating residual. Signed unallocated tax remains possible for explicit discount/refund adjustments.

Zero tax with valid currency/items, including valid empty items, is an available zero with no category-tax map. Positive tax with empty, zero, or negative net items is unavailable rather than pretending allocation completed.

The existing `taxDistribution: Map<Long, Double>` remains a compatibility projection of available typed local shares, empty otherwise. Legacy/cloud/fallback result constructors default `taxAllocation` to null; they are not silently declared migrated. Existing serialization continues writing the compatibility map, not the new in-memory allocation object.

## Authored regression coverage

Service cases cover normalized receipt currency and aggregate counts, stable proportions, sub-cent amounts, fractional JPY, unclassified/nonpositive category IDs, fully unallocated tax, absent/zero tax, empty confidence, invalid currencies, invalid/negative tax, non-finite items, global/category/share overflow, signed adjustments, and floating residuals with compatibility-map equality.

Input-builder cases cover persisted and ephemeral currency/tax pass-through plus missing/unsupported currency without guessing a home currency. Existing privacy/redaction tests are retained.

Local hygiene check: scoped `git diff --check` passed, and the new handoff has no trailing-whitespace lines. Source enumeration confirms 19 service tests (1 existing + 18 new) and 6 input-builder tests (3 existing + 3 new). These are authoring/whitespace checks only, not compilation or behavioral validation.

## Known adjacent boundaries and risks

- `ReceiptItemCategorizationRepository.saveCategorizationResult` persists the typed per-item shares and validates that the compatibility category map, item shares, category totals, and unallocated tax describe the same conserved allocation. Legacy callers that provide only a category map are rejected rather than silently duplicating tax across items.
- Cloud/fallback producers, legacy maps, and artifact explanation/serialization consumers do not yet use the new typed allocation state. The local result exposes unavailable allocation and sets review, but this patch does not migrate all downstream tax storage/UI contracts.
- The on-device provider's existing broad exception catch and logging are unchanged. No new catch or raw financial/text diagnostic was added. Cancellation/privacy remediation of that catch requires its own scoped review.
- M-1a's scalar DTO/helper declarations remain open. Neither M-1 slice resolves the complete inherited money backlog or supersedes independent review/device gates.
- MoneyAmount still uses the approved existing Double representation. Global precision or refund-tax policy changes are outside this slice.

## Deferred user-run validation queue

Do not execute this queue until the user requests validation. From the repository root, use PowerShell 5.1 and one runner process at a time. Review M-1a/M-1b before validation. Stop on a real failure; poll an existing RUNNING/STARTING record instead of launching a duplicate. Wrapper errors are not PASS: inspect durable result, exit code, completion marker, and start/end fingerprint.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile compile
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*MoneyAggregateBuilder*'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*OnDeviceReceiptItemCategorizationServiceTest*'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*ReceiptItemCategorizationInputBuilderTest*'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*HybridReceiptItemCategorizationServiceTest*'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*CloudReceiptItemCategorizationServiceTest*'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*CategorizeReceiptItemsUseCaseTest*'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*ReceiptSideEffectPlannerEphemeralItemsTest*'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*ReceiptStructuredDataPolicyTest*'
```

Also retain M-1a's parser/receipt-consumer queue for the combined final tree. Registered raw-money and full static checks remain later observational gates: other open findings are expected, no suppression or baseline/allowlist edits are authorized, and a nonzero suite exit must still be reported as FAIL. No validated fingerprint or runtime PASS evidence exists for M-1b yet.
