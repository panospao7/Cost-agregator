# Prompt: Vertical User-Journey Audit

Act as the Release 1.0 vertical journey auditor. Review source, tests, and
available device evidence read-only. Do not edit files or run validation.

## Objective

Prove that important user journeys still work end to end after the refactor.
For each journey, trace entry point, domain/service path, persistence, worker or
side effect, UI state, retry/restart behavior, privacy boundary, and final
invariant. Isolated unit coverage is not enough.

## Required journeys

1. Notification or bank intake -> review -> expense -> dashboard.
2. Receipt/OCR -> draft -> link -> expense -> warranty or receipt matching.
3. Manual expense -> recurring/reminder -> budget and analytics.
4. Multi-currency expense -> totals -> budget -> forecast -> health -> savings
   -> CSV/accounting/PDF export.
5. Savings recommendation -> stale/currency checks -> acceptance -> Room ledger
   -> backup/restore -> history and balance.
6. Group expense -> split -> settlement -> reimbursement-aware budget offset.
7. Backup -> supported migration -> restore -> post-restore read and write.
8. Startup, permission denial, cancellation, retry, process death/restart, and
   empty/unavailable/partial states.
9. Every other shipped feature from coverage-plan.md: investment/tax, location,
   AI/search, subscriptions/offers, challenges/carbon, settings/accessibility,
   nudges/lifestyle and debug-versus-release surfaces. Verify current wiring and
   intended exclusions with the human; do not silently omit features.

## Required scenario dimensions

For each journey include at least one normal case and the relevant failure
case: missing currency/rate, duplicate tap or retry, stale snapshot, canceled
operation, denied permission, malformed input, partial data, migration, and
restore interruption. Confirm that failure is visible and controlled rather than
silently converted to zero, success, or an unsafe fallback.

## Output

Return report content for `release-1.0-vertical-journey-matrix.md` for the lead
to persist. The read-only auditor does not write shared artifacts. Read
evidence-map.md and classify actual test boundaries. Report:

```text
Journey:
Entry point:
Legal path:
Persistence/worker boundaries:
Expected invariant:
Evidence:
Missing runtime proof:
Findings and severity:
Verdict: PASS | FAIL | PARTIAL
```

End with `VERTICAL AUDIT: PASS | FAIL | PARTIAL`.
