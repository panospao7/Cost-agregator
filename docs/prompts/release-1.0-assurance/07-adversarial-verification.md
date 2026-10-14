# Prompt: Independent adversarial verification

Use `release-finding-verifier`, Opus 5.5 at xhigh. Run a fresh invocation/session
that did not discover the candidates or implement their fixes. Do not fork a
coder's conversation and call that independent verification. Work read-only.

Inputs: pinned SHA/fingerprint, candidate findings, original issue references,
claimed user paths, relevant scoped docs/source/tests, and durable evidence.

For each candidate:

1. Re-read cited file/function/line at the candidate. Check actual production
   entry point, DI, callers and consumer behavior, including guard conditions.
2. Try to disprove the scenario through constraints, atomicity, early return,
   coordinator handling, platform behavior, intended policy or counterexample.
3. Inspect tests for vacuity, wrong expectations, overly relaxed mocks, missing
   real persistence/transaction boundaries, ignored cases and inadequate oracle.
4. Compare old/new behavior when claiming regression; cross-reference existing
   debt rather than duplicating IDs. Verify supposedly fixed original issues too.
5. Calibrate P0-P3 to demonstrated user impact. Do not inflate unwired code into
   a runtime finding. Uncertainty is allowed; ask for exact runtime evidence.

Return per candidate: CONFIRMED, REFUTED (evidence), RECALIBRATED (reason), or
NEEDS_RUNTIME (exact requested test and deciding outcome). Only confirmed or
recalibrated candidates enter the confirmed registry. Keep refutations in the
same ledger. UNKNOWN historical classification must not become an assumed
regression/pre-existing verdict. Never execute validation or edit artifacts.

For each NEEDS_RUNTIME: specify profile/test, expected observations, required
fixture/real boundary, sanitized evidence and limitation. Test authorship is a
separate approved task; runtime execution belongs only to validation-runner.

State whether the result is independent static verification, independent runtime
evidence, or both. No invented session IDs, file reads, tool runs or guardian
verdicts. No finding quota; returning no confirmed findings is valid.
