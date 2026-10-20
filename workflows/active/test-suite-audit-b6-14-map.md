# B6-14 map + decisions (scout 2026-10-20 on HEAD 945afffc; no git used by scout → writer runs `git diff 8671b844 945afffc -- <file>` first and preserves release-added tests)

Dir T/domain/workers/. Class names stay (SENSITIVE_DIAGNOSTICS_POLICY.md:111). No config references. Review: reviewer-strict + architecture-guardian; privacy-security-guardian for the sink key allow-list.
Counts: FileWorkerTerminalDiagnosticSinkTest 24, WorkerLeaseRegistryTest 28, WorkerRestoreRegressionTest 20, WorkerGuardVerifierTest 3.

## Decisions (DO, target ≈ −14; every deletion needs an equal-or-stronger survivor confirmed by reading)
Sink:
1. :243/:259/:480/:496/:512 → ONE test asserting the exact JSON key set via `JSONObject(line).keys()` (derive the set from FileWorkerTerminalDiagnosticSink's writer and check SENSITIVE_DIAGNOSTICS_POLICY.md:49), plus carry over every negative substring check (exception message, stacktrace `.kt:` frames, notification/bank/OCR text) and the :280 errorClass assertion. −4.
2. :696 ⊂ :183 → delete. :818 ⊂ :283 → delete. −2.
3. :718 + :752 → one test (slowCalls=1) keeping: no file after timeout, exactly one SINK_TIMEOUT_PREFIX log, no throw, second event written, runId 301. −1.
4. :622 keep; strengthen: backup does not contain the old workerName.
5. :183/:207 and :356/:388 tables: OPTIONAL (only if the slack is needed).
Restore:
6. :291/:307/:322 → fold into :347 as (mode ∈ PREPARING/STAGING/SWAPPING) × (all names) table; fix the "eight" title to the real count. −3.
7. :336 ⊂ WorkerLeaseRegistryTest :134 → delete. :417 ⊂ WorkerLeaseRegistryTest :263 → delete. −2.
Registry:
8. :77 duplicate of :60 → delete. :87 → fold into :96 with `assertEquals(1, activeLeaseCount())` before close. :217 ⊂ :333 → delete. −3. (:198, :149 only if exactly subsumed.)
Verifier: unchanged.

## Never touch
Registry :417 `cancelling drain propagates without releasing worker lease` (byte-exact), :263 order, :305, :226, :353, :379/:391/:404, :435, real-thread :478/:548/:604/:695; sink never-throws + exactly-once Timber log, :107-121, :356/:388 types, :421, :658, :773; Restore :107, :115, :199/:221/:246 (CE), CAS :460/:491/:516/:539; WorkerBarrierIntegrationTest.

## Not achievable
"Virtual-time timeout" for the sink: production uses `withContext(Dispatchers.IO + NonCancellable) { withTimeout(...) }` (FileWorkerTerminalDiagnosticSink.kt:93-94); needs a production seam → out of scope, reported.

Validation: targeted-unit-test the 4 classes (with B6-13 classes).
