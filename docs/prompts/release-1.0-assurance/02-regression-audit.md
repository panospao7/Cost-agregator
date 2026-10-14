# Prompt: Campaign Fix Closure and Regression Audit

Act as an independent, read-only regression auditor. Review the frozen Release
1.0 candidate against its Wave 5 parent. Do not edit files, run validation, or
approve your own findings.

## Objective

Find defects introduced, reintroduced, or made reachable by Wave 5 changes. This
is not a generic style review and not a repetition of every historical audit.

## Method

1. Enumerate campaign-to-candidate and Wave-4-to-candidate deltas separately.
   Reconcile every original cluster/finding and fix from Waves 1-5 against the
   final source, including previously waived/pending independent reviews.
2. Map every batch and claimed fix to its real caller, consumer, persistence
   boundary, UI state, retry path, and test.
3. Re-read the source at the candidate commit. Do not trust handoff method lists.
4. Check all 15 campaign lenses, with extra weight on fix-regression:
   legal path, barrier, atomicity/TOCTOU, idempotency, cancellation, side effects,
   money/currency, time, privacy, workers, data integrity, error handling,
   wiring, test correctness, and regression.
5. Cross-reference the original campaign registry, remediation plans, and known
   debt. Mark old findings `FIXED`, `STILL OPEN`, `CHANGED`, `REFUTED`, or
   `NEWLY REACHABLE`. Refutation/closure must be independently verified.

Do not call an issue a regression without old/new source proof. If historical
runtime behavior matters, request a separate approved baseline run through the
runner; never create a destructive checkout. UNKNOWN remains valid.

## Priority paths

- money source -> normalization -> analytics/dashboard/forecast/health/savings/export;
- savings acceptance -> Room ledger -> migration -> backup/restore;
- receipt and bank source boundaries -> lifecycle creation;
- fresh install and supported migration chain;
- PDF arithmetic versus Android rendering;
- worker retries, cancellation, restore barriers, and diagnostics.

## Finding format

For every credible issue provide:

```text
ID: R1-<number>
Severity: P0 | P1 | P2 | P3
Classification: NEW-REGRESSION | CHANGED | STILL-OPEN | TEST-DEBT | DOC-DRIFT | UNKNOWN
Title:
Evidence: file, symbol, and candidate line
Impact path: entry -> boundary -> user-visible effect
Old finding IDs: none or IDs
Existing proof: tests/guards/validation records
Smallest safe next step:
Confidence: high | medium | low
```

End with `REGRESSION AUDIT: PASS | FAIL | PARTIAL` for the assigned slice.
Attach candidates, retained refutations and pending proof to the verification
queue. Discovery PASS does not independently close original issue IDs or
constitute release acceptance.
