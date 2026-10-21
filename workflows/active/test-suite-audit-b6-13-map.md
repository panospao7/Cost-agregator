# B6-13 map + decisions (scout 2026-10-20 on HEAD 945afffc; scout had no Bash → writer must run `git diff 8671b844 945afffc -- <file>` first and preserve every release-added test)

Dir T/domain/workers/. JUnit4, no Parameterized; tables = in-test loops over row lists with row-named failure messages. FQN com.yourname.expensetracker.domain.workers.*

## Counts now
WorkerExecutionGuardTest (WEG) 53 (denylisted class name: config/release_block_denylist.yml:19 → keep name, no @Ignore); WorkerRunLoggerTest ~65 (cited SENSITIVE_DIAGNOSTICS_POLICY.md:111 → keep name); P9RemainingWorkerFixesTest 12; WorkerIdempotencyTest 5 (leave alone).

## Decisions (DO; T2 worker → reviewer-strict + architecture-guardian)
1. WEG privacy/notification matrix (:826-934 runGuarded + :956-1078 WithContext, 16 tests) → tables, every combination a row; each row keeps result-class assert, reason equality, assertFalse(blockRan) and exact coVerify(exactly=1) on the expected runHandle method (row carries it) + exactly=0 on the others. R26 tests (:882 incl. coVerify(exactly=0){skipped} :894, and WithContext twin :1012) stay separate and exact; add the missing exactly=0 skipped verify to the twin (strengthening). ~−12.
2. WEG checkpoint barrier :1151/:1179/:1206 → one 3-row table; :1131 stop-requested separate. −2.
3. WEG :440 vs :458 and :868 vs matrix: delete only if the body is an exact subset of a survivor (writer confirms by reading); else keep.
4. Logger: Durable-on-success 6 tests (:409-459) → table; classifyDiagnostic 10 pure tests (:741-799) → (input, expected) table; *_persists_terminal_reason_code 5 (:626-703) → table; duplicate_success/failure_is_noop (:248/:261) → table. Keep exact: :720, :808, :832, :967, path_like_reason_code / logger_sanitizes_*, :877, :987-:1067, CAS/mutex :231-:407, real-thread :1192/:1221/:1279, CE :1326/:1351, :502, :521.
5. P9: six "handle X is idempotent" (:84-178) → delete a row only where WorkerRunLoggerTest covers the same terminal kind (:248, :261, :273, :561); otherwise keep as table rows. Spec tests :52/:63 duplicate WorkerIdempotencyTest :96 → delete only if exactly subsumed. Midnight-delay :264/:270: keep if they call production code; delete only if pure stdlib `maxOf` tautologies (report). Keep :200, :36.
6. NEVER touch: CE/TimeoutCancellation cases (WEG :516, :790, :1110, :1233; logger :1326, :1351), barrier/lease (:263-:393, :651-:790 stale_recovery_*, :1131), sanitization.
7. X2: add `runGuardedWithContext` CancellationException propagation test (no WithContext CE test exists): CE thrown by block → rethrown, runHandle.cancelled(WORKER_CANCELLED) exactly 1, retry/failure exactly 0 (mirror :516/:1233). +1.
8. Docs: evidence-map :18 / inventory counts only if a live (non-historical, non-generated) doc states current counts.
Expected ≈ −37..−42 +1. Validation: targeted-unit-test the 4 classes (per-class counts).
