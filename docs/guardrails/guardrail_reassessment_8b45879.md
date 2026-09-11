# Guardrail reassessment — SHA `8b45879e445e8750fcb9210bcf44b9a26e6468dd`

## Bottom line

You are **much farther than the earlier reassessment**. The DB-guard track has progressed from a blocked legacy-policy system to an active v2 scanner, exact typed policy, GR-08 triage batches, and an empty v2 ratchet baseline. The recent history shows GR-07 activation, GR-08a…p2, and GR-09 landing before the later hardening and time work. ([github.com](https://github.com/panospao7/Cost-agregator/commits/8b45879e445e8750fcb9210bcf44b9a26e6468dd))

But I would **not yet call the guards properly complete at this SHA**:

1. GR-09’s recorded zero-finding evidence is from `ce1918c`, not current SHA `8b45879`; subsequent commits changed guard code and production Kotlin. It must be rerun. ([github.com](https://github.com/panospao7/Cost-agregator/commit/f96f717))  
2. The DB report intentionally permits 20 *advisory* signature diagnostics. That may be safe for DB-relevant findings, but it deviates from the original “no diagnostics” completion condition and remains analyzer debt. ([github.com](https://github.com/panospao7/Cost-agregator/commit/f96f717))  
3. The suite still has an independently hard-coded `GUARD_MANIFEST`, despite the registry claiming it is the single source of truth. GR-10’s control-plane consolidation is still needed. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/ci/guard_registry.py))  
4. `docs/DB_WRITE_OWNERSHIP.md` is materially stale: it says v1 is active and GR-07 is pending, while the live verifier says v2 is activated and legacy authorization is removed. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/docs/DB_WRITE_OWNERSHIP.md))  

## What was accomplished

- The active ownership policy is now schema v2 and contains exact callable + DAO mutation identity, rather than legacy simple-class authorization. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/config/guards/db_ownership_policy.yml))
- The verifier’s active pipeline is root validation → Room inventory → v2 load → exact source evidence → structural validation → D4 discovery. A v1 policy cannot authorize a write. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/verify_db_access_boundaries.py))
- The ratchet now uses `db_access_v2.json`; it validates malformed/expired debt fail-closed and the checked-in baseline is correctly empty (`entries: []`). ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/config/baselines/db_access_v2.json))
- GR-09 records 497 GR-08 findings as fixed, zero approved temporary debt, and zero baseline entries. Treat that as a recorded claim pending fresh reproduction at HEAD—not as current proof. ([github.com](https://github.com/panospao7/Cost-agregator/commit/f96f717))

## How close are you?

These are judgment estimates, not measured test results:

| Scope | Status |
|---|---:|
| DB-v2 implementation artifacts | **80–85%** |
| Trustworthy DB gate proven at current SHA | **55–65%** |
| Whole guardrail/acceptance program | **40–50%** |

The gap is primarily **evidence, control-plane consistency, documentation truthfulness, advisory-analyzer cleanup, and current runtime/test validation**—not lack of DB-policy machinery.

## Likely current failure areas

### 1. Time work is now the immediate suspect
`8b45879` changes 40 files to eliminate direct clock reads. The time guard is strict and blocking, with no baseline escape route. ([github.com](https://github.com/panospao7/Cost-agregator/commit/8b45879e445e8750fcb9210bcf44b9a26e6468dd))

Two review risks:

- `FinancialStressForecastEngine` now computes elapsed duration using `timeProvider.now() - startTime`. That is injected, but wall-clock time is not monotonic; duration measurement should normally use a dedicated monotonic seam. ([github.com](https://github.com/panospao7/Cost-agregator/commit/8b45879e445e8750fcb9210bcf44b9a26e6468dd.patch))
- Several injectable classes retain default `SystemTimeProvider()` construction to preserve old tests. This avoids direct-clock findings, but it leaves test paths nondeterministic and permits bypassing DI. Prefer explicit test-only constructors with `FakeTimeProvider`. ([github.com](https://github.com/panospao7/Cost-agregator/commit/8b45879e445e8750fcb9210bcf44b9a26e6468dd.patch))

`Calendar.Builder()` is a sensible guard-compliant replacement for `Calendar.getInstance()`, but the large `TimePeriodUtils` rewrite needs DST, timezone, leap-year, pre-cutover, and month-normalization tests before it is trusted. ([github.com](https://github.com/panospao7/Cost-agregator/commit/8b45879e445e8750fcb9210bcf44b9a26e6468dd.patch))

### 2. DB freshness is unproven
The GR-09 baseline evidence predates `9ee552a`, `588623d`, and `8b45879`. `9ee552a` changed DB-adjacent production files, and the latest commit changes broad Kotlin source. Do not assume the old zero-finding report still applies. ([github.com](https://github.com/panospao7/Cost-agregator/commit/9ee552a))

### 3. Do not use the historical test ledger as current evidence
`TEST_FAILURE_LEDGER.md` is pinned to old SHA `43ca2228` and reports only partial historical test batches. It is useful context, not proof of today’s failures. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/TEST_FAILURE_LEDGER.md))

## Immediate recovery sequence — do this before editing policy or baselines

```bash
mkdir -p build/guard-debug/8b45879

git status --short
git rev-parse HEAD
git rev-parse HEAD^{tree}

python3 scripts/ci/verify_guard_registry.py \
  |& tee build/guard-debug/8b45879/registry.log

python3 scripts/verify_time_boundaries.py \
  --root . \
  --allowlist config/guards/time_boundary_exceptions.yml \
  --fail-on-violation \
  |& tee build/guard-debug/8b45879/time.log

python3 scripts/verify_db_access_boundaries.py \
  --fail-on-violation \
  --findings-output build/guard-debug/8b45879/db-findings.json \
  |& tee build/guard-debug/8b45879/db-cli.log

python3 scripts/ci/run_static_guard_suite.py \
  --output-dir build/guard-debug/8b45879/static-suite \
  |& tee build/guard-debug/8b45879/static-suite.log
```

Then inspect `static-suite/summary.json` and run **only each failing command** directly. Interpret results strictly:

| Result | Meaning | Action |
|---|---|---|
| DB CLI `0`, trusted | clean current DB scan | proceed to ratchet/GR-10 closure |
| DB CLI `1`, trusted | real current DB findings | make a small GR-08q batch; no baseline |
| DB CLI `2` | analyzer/control-plane/preflight problem | stop triage; repair infrastructure |
| Time guard `1` | real remaining direct-clock use | fix source; no exception expansion by default |
| Suite `2` | runner/interpreter/timeout/config fault | repair control plane, not app policy |

Only after Python guard results are understood—and only when no other process owns Gradle—run sequentially:

```bash
./gradlew :app:compileDebugKotlin --no-daemon --stacktrace --console=plain
./gradlew :app:verifyDbAccessBoundaries --no-daemon --stacktrace --console=plain
```

## What should be next?

1. **DEBUG-00 / GR-00R:** exact-SHA evidence capture above, twice for DB semantics.  
2. **If DB is clean:** do **GR-10**:
   - derive suite execution from the registry;
   - prove static suite, ratchet, and Gradle use equivalent DB child argv;
   - update stale DB ownership documentation;
   - explicitly track and reduce the 20 advisory diagnostics.
3. **If DB has trusted findings:** do a narrowly scoped **GR-08q** manifest; never update the empty baseline to hide them.
4. **TIME hardening:** add deterministic `FakeTimeProvider` tests, separate monotonic elapsed timing from “now,” and test `TimePeriodUtils` behavior across DST/timezone/cutover boundaries.
5. Only then attempt full `:app:check` and broader acceptance-gate closure.

## Non-negotiable guardrail rule

Do **not** modify `config/baselines/db_access_v2.json`, use `--update-baseline`, or weaken advisory classification merely to make this green. The empty DB baseline is currently one of the strongest parts of the implementation: any real new DB finding should fail immediately. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/config/baselines/db_access_v2.json))

## Scope note

I reviewed the current guard code/configuration, main architecture/recovery/acceptance documents, and the recent GR-05→GR-09-plus-hardening commit chain. I did not execute the repository or independently audit every one of the 497 historical policy dispositions; those require the exact-SHA capture and targeted manifest review above.