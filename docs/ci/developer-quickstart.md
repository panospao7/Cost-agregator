# Developer Quickstart — CI Static Guardrails

## Before pushing
Run these checks locally. Most take <10 seconds.

### Fast pre-push (required)
```bash
actionlint                                          # Validate CI workflows
./gradlew :app:assembleDebug --stacktrace           # Compile
./gradlew :app:check --stacktrace                   # All wired checks + lint + tests
```

### Static guard scripts

Canonical guard commands are **generated, never pasted**: every guard's
command identity, mode, inputs, and source scope live in
`docs/ci/GUARD_COMMANDS.generated.md` (renderer-owned from the registry
execution schema), and current per-guard evidence state lives in
`docs/ci/GUARD_STATUS.generated.md`. Run the whole canonical suite rather
than hand-typed per-guard commands:

```bash
python3 scripts/ci/run_static_guard_suite.py --output-dir build/ci/static-guards
python3 scripts/ci/verify_guard_registry.py --root .
```

Individual guards run through the registered runner (example for the DB
guard; substitute the guard id from the generated reference):

```bash
python3 scripts/ci/run_registered_guard.py --guard-id db_access --context direct --root .
```

### If you add a new guard
1. Copy `scripts/guard_template.py`
2. Implement `scan_file()` with your detection logic
3. Create allowlist in `scripts/allowlists/`
4. Add tests in `scripts/test_verify_*.py`
5. Add to CI in `.github/workflows/ci.yml` (static-guards job)
6. Document in `docs/ci/CI_GUARDRAILS_BASELINE.md` and `docs/ci/guard-framework.md`
7. Register allowlist in `scripts/verify_allowlist_compliance.py` YAML_ALLOWLISTS

### If a guard flags your code
1. Read the violation message — it includes file:line and suggested fix
2. Fix the code if it's a real issue
3. If the code is safe, add an exact entry to the guard's allowlist YAML with all seven required fields: `path`, `class`, `method`, `api`, `reason`, `owner`, `linked_issue`
4. Do NOT remove or @Ignore guard tests

### Allowlist entry format
```yaml
- path: app/src/main/java/com/yourname/expensetracker/domain/util/SystemTimeProvider.kt
  class: SystemTimeProvider
  method: now
  api: System.currentTimeMillis
  reason: "Canonical platform clock adapter — the single production implementation of TimeProvider"
  owner: "@panospao7"
  linked_issue: "MIT-003"
```

**Forbidden in exception entries:**

- `expires`, `permanent`, or any time-bound semantics — no entry is
  temporary or evergreen; every entry must be re-verified when the
  guarded code changes.
- Wildcard `path`, `class`, `method`, or `api` values (`*`, `**`,
  glob patterns) — each row must name exactly one source location.
- Extra keys not in the seven-field schema — unknown keys cause a
  parse error in the guard.

### CI pipeline
| Job | Purpose | Blocking? |
|-----|---------|-----------|
| validate-workflow | Lint CI YAML | ✅ |
| static-guards | Python guard scripts | ✅ (with --fail-on-violation) |
| unit-tests | Gradle unit tests + schema + ignored count | ✅ |
| lint-and-check | Lint + assemble + :app:check | ✅ |
| instrumented-tests | Android instrumented tests (emulator) | ❌ (flaky) |

### Need help?
- Guard framework docs: `docs/ci/guard-framework.md`
- Baseline inventory: `docs/ci/CI_GUARDRAILS_BASELINE.md`
- Local CI guide: `docs/ci/local-ci.md`
