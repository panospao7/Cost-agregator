# PR C — Enforce No-Growth Backlogs

## 1. PR definition

**Suggested title:**  
`ci: enforce exact no-growth baselines for architecture guard debt`

**Base:** Successful final commit of PR B.

**Reference snapshot:**  
`ebb5aa93348282b31c1c669d1bf1271d584b9eb0`

**Primary issues:**

- MIT-003 — Architecture guard enforcement
- MIT-025 — Sensitive identifier hashing/privacy
- MIT-030 — Write-barrier enforcement
- MIT-031 — Event ownership and atomicity
- MIT-034 — Cancellation propagation
- MIT-036 — DAO ownership
- MIT-050 — Dashboard money correctness

**Estimated effort:** 5–8 engineering days.

## 2. Objective

Convert the remaining warning-only guard backlogs into **blocking ratchets**.

After this PR:

1. Every existing finding has a stable machine-readable fingerprint.
2. Existing findings may remain temporarily.
3. Any new finding fails CI.
4. Replacing one old finding with a new one also fails CI.
5. Baseline counts cannot be increased to make CI pass.
6. Resolved findings must be removed from the baseline.
7. Guard crashes, malformed configuration and unreadable source files fail CI.
8. CI publishes exact added, unchanged and resolved findings.
9. `|| true` and warning-only execution are removed for covered guards.
10. Baseline additions after initial bootstrap are prohibited.

At the reference commit, privacy, DB access, event-writer and money guards are neutralized with `|| true`; cancellation runs without `--fail-on-violation`. Therefore, those checks report debt but cannot prevent regressions. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/.github/workflows/ci.yml))

The reported reference backlogs were approximately:

| Guard | Reference findings |
|---|---:|
| Cancellation | 198 |
| DB access | 70 |
| Event writers | 45 |
| Privacy | 1 |
| Money | 2 |

These numbers must **not** be copied into the new baseline. Recalculate them after PR A and PR B because their refactors may change the results.

---

# 3. Non-goals

Do not include:

- Full cancellation backlog removal.
- Full DAO/event-writer architecture migration.
- Migration execution enforcement.
- Release APK/AAB verification.
- Broad guard parser replacement with a Kotlin AST.
- New warning-mode guards unrelated to these five.
- Count-only thresholds.
- New permanent exemptions.
- New `|| true` expressions.
- Baseline additions merely to unblock a feature PR.

This PR installs the ratchet. Subsequent PRs burn down the debt.

---

# 4. Enforcement model

## 4.1 Covered guards

### Ratchet mode

These will probably retain substantial baselines:

- Cancellation.
- DB access.
- Event writers.

### Zero-or-ratchet decision

These have very small reported backlogs:

- Privacy.
- Money.

Preferred result:

- Fix the one privacy and two money findings.
- Run those guards in strict zero mode.
- Do not create unnecessary baseline files.

If those findings require a separate architectural decision, place them in exact temporary baselines. They must still reject all growth.

## 4.2 Required modes

Support three explicit modes:

| Mode | Meaning | CI use |
|---|---|---|
| `strict` | No findings permitted | Clean guards |
| `ratchet` | Only exact known findings permitted | Existing debt |
| `report` | Report without enforcement | Local investigation only |

`report` mode must never be used by required CI jobs.

## 4.3 Set comparison

For each guard, compare current findings against its baseline:

- `unchanged = current ∩ baseline`
- `new = current − baseline`
- `resolved = baseline − current`

Rules:

- Any `new` finding fails CI.
- Any unresolved stale baseline entry also fails CI until pruned.
- Only `unchanged` findings are tolerated.
- A lower total count does not permit an unrelated new finding.
- A finding may not be accepted based only on its file or line number.

This prevents the “one fixed, one introduced, total unchanged” bypass.

---

# 5. Workstream C1 — Introduce a shared finding contract

## 5.1 New shared modules

Create:

- `scripts/guards/finding.py`
- `scripts/guards/fingerprint.py`
- `scripts/guards/baseline.py`
- `scripts/guards/path_normalization.py`
- `scripts/guards/symbol_resolver.py`

Suggested immutable model:

```python
@dataclass(frozen=True)
class GuardFinding:
    guard: str
    rule_id: str
    kind: str
    path: str
    symbol: str
    operation: str
    line: int
    message: str
    detector_version: int
```

## 5.2 Required fields

Every finding must identify:

- Guard name.
- Precise rule ID.
- Finding category.
- Repository-relative POSIX path.
- Nearest containing class/function/property.
- Stable operation signature.
- Current line number for diagnostics.
- Human-readable remediation message.
- Detector schema version.
- Stable fingerprint.

Examples of `operation`:

- `runCatching`
- `catch:Exception`
- `expenseDao.insert`
- `database.receiptEventDao().insert`
- `ReceiptEvent.constructor`
- `ctx.totalBudgetAmount`
- `transactionId.hashCode`

## 5.3 What the fingerprint includes

Calculate the fingerprint from canonical JSON containing:

- Guard.
- Rule ID.
- Kind.
- Normalized path.
- Symbol.
- Stable operation signature.
- Detector version.

Exclude:

- Line number.
- Raw message text.
- Absolute checkout path.
- Timestamp.
- Source commit.
- Full source line.
- Whitespace formatting.

Use SHA-256 and retain a readable prefix, for example:

`gcancel-v1-0c6f9b61d40e`

## 5.4 Symbol resolution

Resolve the nearest enclosing:

1. Function.
2. Property initializer.
3. Class or object.
4. File-level scope.

For Kotlin, use brace-depth and declaration tracking as the first implementation. Do not attempt a complete parser in this PR.

A multiline finding must still map to one stable symbol.

## 5.5 Duplicate findings

If the same rule and operation occur multiple times in one symbol:

- Include a normalized statement hash.
- Add a deterministic occurrence discriminator only when necessary.
- Verify that whitespace and comment changes do not alter the identity.
- Verify that adding another identical unsafe operation creates a new finding.

## 5.6 Safe output

Do not store raw runtime values or sensitive literals in fingerprints, baselines or summaries.

Normalize string and numeric literals when deriving statement signatures. Logs may show a safe operation name but should not reproduce an entire source statement unnecessarily.

---

# 6. Workstream C2 — Define machine-readable backlog manifests

## 6.1 Directory structure

Create:

```text
config/guard-backlogs/
  policy.json
  cancellation.json
  db-access.json
  event-writers.json
  privacy.json        # only if not reduced to zero
  money.json          # only if not reduced to zero
```

## 6.2 Manifest schema

Example:

```json
{
  "schema_version": 1,
  "guard": "cancellation",
  "detector_version": 1,
  "mode": "ratchet",
  "captured_from_commit": "<PR-B-SHA>",
  "owner": "@panospao7",
  "default_issue": "MIT-034",
  "entries": [
    {
      "fingerprint": "gcancel-v1-0c6f9b61d40e",
      "rule_id": "G-CANCEL-02",
      "kind": "unsafe_run_catching",
      "path": "app/src/main/java/.../File.kt",
      "symbol": "SomeClass.someSuspendMethod",
      "operation": "runCatching"
    }
  ]
}
```

## 6.3 Manifest invariants

- Entries sorted by path, symbol, rule and fingerprint.
- Fingerprints unique.
- No line numbers used for identity.
- No wildcard paths or symbols.
- No raw source content.
- No duplicate entries.
- Mode matches central policy.
- Detector version matches the executable guard.
- Every entry corresponds to one current finding.
- Every current tolerated finding corresponds to one entry.
- Counts are derived; they are not manually stored.

## 6.4 Issue ownership

Use these defaults:

| Guard | Default issue |
|---|---|
| Cancellation | MIT-034 |
| DB access | MIT-030 / MIT-036 |
| Event writers | MIT-031 |
| Privacy | MIT-025 or a dedicated correctness issue |
| Money | MIT-050 |

Per-entry issue overrides are allowed where a more specific issue exists.

---

# 7. Workstream C3 — Implement the baseline comparator

Create:

`scripts/guards/verify_backlog.py`

## 7.1 Inputs

The comparator receives:

- Current structured findings JSON.
- Baseline manifest.
- Guard policy.
- Repository root.
- Optional base Git revision.

## 7.2 Outputs

Generate:

- `current-findings.json`
- `comparison.json`
- `comparison.md`

The comparison must include:

- Baseline count.
- Current count.
- Unchanged count.
- New count.
- Resolved count.
- Infrastructure errors.
- Findings grouped by rule, file and issue.

## 7.3 Exit codes

| Code | Meaning |
|---:|---|
| 0 | Policy satisfied |
| 1 | New finding, stale baseline or prohibited baseline change |
| 2 | Detector/configuration/infrastructure failure |

Examples of exit code 2:

- Missing source directory.
- Malformed JSON/YAML.
- Duplicate fingerprints.
- Unsupported schema version.
- Detector crash.
- Unreadable production source.
- Missing required baseline.
- Detector version mismatch.

## 7.4 Stale baseline behavior

If code removes a violation but the baseline still contains it, fail with:

> Finding resolved; remove the stale baseline entry using the pruning command.

This is required because leaving resolved entries in the baseline would permit their later reintroduction.

## 7.5 Pruning command

Create:

`scripts/guards/prune_resolved_backlogs.py`

It may:

- Remove baseline entries absent from current findings.
- Sort and validate manifests.
- Print the reduction summary.

It must never:

- Add findings.
- Update fingerprints to match new findings.
- Change ratchet mode to report.
- Create new debt entries.

Support:

```bash
python3 scripts/guards/prune_resolved_backlogs.py --check
python3 scripts/guards/prune_resolved_backlogs.py --write
```

---

# 8. Workstream C4 — Prevent baseline inflation

A checked-in baseline is ineffective if developers can add new findings to it in the same PR.

## 8.1 Base-revision comparison

Create:

`scripts/guards/verify_backlog_policy_delta.py`

It must compare current manifests with the PR base revision.

Configure checkout with full or sufficient history:

```yaml
uses: actions/checkout@v4
with:
  fetch-depth: 0
```

Determine the comparison revision from:

- Pull request: `github.event.pull_request.base.sha`
- Push: `github.event.before`
- Manual dispatch: explicit input or merge-base with protected branch

If the required base revision cannot be loaded, fail as infrastructure error.

## 8.2 Allowed manifest changes

After bootstrap:

- Removing entries: allowed.
- Sorting without semantic change: allowed.
- Strengthening metadata: allowed.
- Changing owner or linked issue: reviewable.
- Adding entries: forbidden.
- Changing a fingerprint to accept current code: forbidden.
- Changing `strict` to `ratchet` or `report`: forbidden.
- Changing `ratchet` to `report`: forbidden.
- Lowering detector version: forbidden.
- Deleting a baseline while findings remain: forbidden.

## 8.3 Initial bootstrap

PR C necessarily creates the initial manifests.

Permit initialization only when:

1. The base revision has none of the expected backlog manifests.
2. The current PR creates the complete declared set.
3. Every entry matches a finding that exists at the PR B base commit.
4. Current and baseline sets are exact.
5. `policy.json` marks the initialization as sealed.
6. No persistent `allow_bootstrap` switch remains after merge.

After PR C merges, the base branch contains the manifests, so initialization cannot occur again.

## 8.4 Prevent count-swap attacks

Add a regression test:

- Base: 100 known findings.
- PR fixes one known finding.
- PR introduces one different finding.
- Current total remains 100.
- CI must fail because the new fingerprint is unknown.

## 8.5 Controlled relocation

A file rename or symbol extraction may legitimately move unresolved debt.

Preferred policy: fix the finding during the refactor.

If relocation support is necessary, allow an explicit one-to-one mapping:

```json
{
  "fingerprint": "new-id",
  "supersedes": "old-id"
}
```

Validation must prove:

- Old entry was removed.
- New and old use the same guard, rule, kind and operation.
- Mapping is one-to-one.
- Total debt does not increase.
- The finding existed before and after solely because of relocation.
- No other new finding was introduced.

Do not allow free-form fingerprint replacement.

---

# 9. Workstream C5 — Retrofit each guard

## 9.1 Cancellation guard

The current guard detects broad catches, `runCatching`, and unsafe `.onFailure`, but its top-level identity and file-oriented allowlist handling are not sufficiently precise for exact baselining. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/scripts/verify_cancellation_boundaries.py))

Required changes:

- Emit distinct rule IDs:
  - `G-CANCEL-01`
  - `G-CANCEL-02`
  - `G-CANCEL-03`
- Emit structured findings.
- Resolve the enclosing suspend function or worker method.
- Distinguish broad-catch, `runCatching` and `.onFailure` kinds.
- Keep line number informational only.
- Fail on unreadable files.
- Fail on malformed allowlists.
- Apply only the exact finding-scoped exceptions established by PR B.
- Correct issue references from MIT-035 to MIT-034 where appropriate.
- Support `--format json` and `--output`.

### Cancellation tests

- Whitespace change preserves fingerprint.
- Moving a statement within the same function preserves fingerprint.
- Moving it to another function changes the fingerprint.
- Replacing `runCatching` with cancellable handling resolves the finding.
- Adding another unsafe `runCatching` fails.
- Existing count reduction requires baseline pruning.

## 9.2 DB-access guard

Required finding categories:

- `UNALLOWLISTED_CLASS`
- `UNALLOWLISTED_CLASS_DIRECT_CHAIN`
- `FORBIDDEN_FILE_OP`
- `MISSING_WRITE_BARRIER`
- `MISSING_WRITE_BARRIER_DIRECT_CHAIN`
- `DEBUG_GUARD_MISSING`

Fingerprint using:

- Class/method.
- DAO or database operation.
- Mutation method.
- Finding category.

Do not fingerprint all mutations in a class as one item.

The existing DB configuration contains numerous transitional ownership entries and descriptive `allowed_until` values; PR B should already have separated exact structural exemptions from unresolved debt. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/config/db_access_allowlist.yml))

### DB tests

- New DAO method in a known class fails.
- New mutation in a baseline file fails.
- Existing exact mutation passes under ratchet.
- Moving a mutation to an unapproved method fails.
- Migration structural exceptions remain separate from debt.
- File-operation and DAO findings cannot share one fingerprint.

## 9.3 Event-writer guard

Emit separate kinds:

- `ENTITY_CONSTRUCTION`
- `DIRECT_DAO_INSERT`

Fingerprint using:

- Event type.
- Writer operation.
- Class/function.
- Exact path.

A baseline entry for `ReceiptEvent` construction must not suppress a direct DAO insertion or a second constructor elsewhere.

If PR B has not already migrated the legacy text allowlist, complete that migration before baselining. The reference allowlist is only filename-based and therefore unsuitable for exact ratcheting. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/scripts/event_writer_allowlist.txt))

### Event tests

- Known constructor remains tolerated.
- Added constructor in the same file fails.
- Added DAO insert in a baseline function fails.
- Converting direct insert to typed writer resolves the finding.
- Resolved entry cannot remain in the baseline.

## 9.4 Privacy guard

The privacy detector currently exits nonzero for all findings and silently returns no findings when a source file cannot be opened. That must be corrected before ratchet enforcement. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/scripts/verify_privacy_boundaries.py))

Required changes:

- Add structured output support.
- Preserve individual rule IDs such as G1–G14.
- Treat source-read failures as exit code 2.
- Add detector version.
- Keep strict mode available.
- Do not add a broad privacy allowlist.

Decision:

- Prefer fixing the remaining finding and running strict.
- If baselined, use one exact fingerprint and a short target-removal date in policy metadata.

## 9.5 Money guard

The money detector contains both allowlistable and explicitly non-allowlistable rules. Its existing read-error behavior warns and continues; change that to infrastructure failure. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/scripts/verify_money_boundaries.py))

Required changes:

- Emit structured findings for each money rule.
- Preserve non-allowlistable semantics.
- Keep source-comment exemptions separate from backlog manifests.
- Treat unreadable files as exit code 2.
- Use operation signatures such as `ctx.totalBudgetAmount`.
- Ensure two occurrences in one function remain distinct findings.
- Prefer fixing the two reported dashboard findings and enabling strict mode.

A backlog manifest is not a source exemption. It merely tolerates an exact pre-existing finding while forbidding new findings.

---

# 10. Workstream C6 — Capture the initial baselines

## 10.1 Preconditions

Before capture:

- PR A is green.
- PR B is green.
- Unsafe broad exemptions are gone.
- All five detectors execute successfully.
- Detector tests pass.
- The same source tree produces deterministic results twice.

## 10.2 Review process

For every finding:

1. Confirm it is real.
2. Confirm it is not a duplicate detector result.
3. Confirm its rule and category are correct.
4. Confirm its symbol is correct.
5. Confirm its fingerprint is stable.
6. Assign the proper issue.
7. Decide whether it should be fixed immediately.
8. Baseline only unresolved real debt.

False positives must be handled by:

- Improving the detector, or
- An exact structural exception under PR B policy.

Do not place false positives in the backlog.

## 10.3 Small-backlog decision gate

For privacy and money:

- Allocate a bounded effort—approximately half a day each—to remove the findings.
- If corrected, configure strict mode.
- If not corrected, baseline them exactly and record a near-term removal issue.

## 10.4 Determinism gate

Run each detector at least twice from clean checkouts.

Require:

- Identical fingerprints.
- Identical ordering.
- Identical counts.
- No absolute paths.
- No timestamps in compared data.

---

# 11. Workstream C7 — Integrate with the PR A suite runner

Update the static-guard manifest introduced by PR A.

Example conceptual policy:

```json
{
  "privacy": {"mode": "strict"},
  "db_access": {"mode": "ratchet"},
  "event_writers": {"mode": "ratchet"},
  "money": {"mode": "strict"},
  "cancellation": {"mode": "ratchet"}
}
```

## CI execution order

For each covered guard:

1. Run detector over the entire source scope.
2. Write structured findings JSON.
3. Validate detector execution.
4. Validate baseline schema.
5. Compare findings with baseline.
6. Validate baseline delta against the PR base.
7. Write human-readable report.
8. Continue running later guards even after a failure.
9. Fail the suite at the end if any ratchet fails.

## Workflow changes

Remove:

- `|| true`
- Warning-only cancellation execution
- Hardcoded backlog counts in step names

Replace step names such as:

> warning mode — 70 pre-existing violations

with:

> DB access boundaries — exact backlog ratchet

Counts must come from generated output, not workflow YAML.

## Artifact paths

Always upload:

```text
build/ci/static-guards/backlogs/**
```

Include:

- Detector findings.
- Baseline comparison.
- Baseline policy delta.
- Markdown summary.
- Suite-level JSON.

Use `if: always()` and `if-no-files-found: error`.

---

# 12. Workstream C8 — Reporting and documentation

## 12.1 Generated summary

Append a table to `$GITHUB_STEP_SUMMARY`:

| Guard | Mode | Baseline | Current | New | Resolved | Result |
|---|---:|---:|---:|---:|---:|---|
| Cancellation | Ratchet | N | N | 0 | 0 | Pass |
| DB access | Ratchet | N | N | 0 | 0 | Pass |
| Event writers | Ratchet | N | N | 0 | 0 | Pass |
| Privacy | Strict/ratchet | N | N | 0 | 0 | Pass |
| Money | Strict/ratchet | N | N | 0 | 0 | Pass |

## 12.2 Documentation source of truth

Treat `config/guard-backlogs/` as the source of truth.

Update:

- `CI_GUARDRAILS_BASELINE.md`
- `GUARD_VIOLATION_AUDIT.md`
- `guard-framework.md`
- `developer-quickstart.md`
- `local-ci.md`
- `MASTER_ISSUE_TRACKER.md`

Do not manually maintain counts in multiple documents.

The audit should either:

- Be generated from baseline/current JSON, or
- State that counts are available in CI artifacts and link conceptually to the generation command.

## 12.3 Local commands

Document:

```bash
# Run all backlog ratchets
python3 scripts/ci/run_static_guard_suite.py --group backlogs

# Check baseline policy
python3 scripts/guards/verify_backlog_policy_delta.py \
  --base-ref origin/main

# Check for stale entries
python3 scripts/guards/prune_resolved_backlogs.py --check

# Remove resolved entries
python3 scripts/guards/prune_resolved_backlogs.py --write
```

---

# 13. Required regression tests

Create shared tests under:

`scripts/test_guard_backlog_*.py`

## Core comparison tests

1. Exact current set passes.
2. One new finding fails.
3. One removed and one added finding fails.
4. Lower total with one new finding fails.
5. Resolved finding with stale baseline fails.
6. Resolved finding plus baseline removal passes.
7. Duplicate baseline fingerprint fails.
8. Duplicate current fingerprint fails.
9. Missing baseline fails in ratchet mode.
10. Findings fail in strict mode.
11. Report mode cannot be selected by CI policy.
12. Malformed manifest exits 2.
13. Unreadable source exits 2.
14. Detector crash exits 2.
15. Detector-version mismatch exits 2.

## Fingerprint tests

16. Whitespace changes preserve identity.
17. Comment changes preserve identity.
18. Line movement preserves identity.
19. Absolute checkout path does not affect identity.
20. Symbol change changes identity.
21. Operation change changes identity.
22. Second identical violation is independently detected.
23. String literal contents are not stored.
24. Fingerprint output is deterministic.

## Anti-tampering tests

25. Baseline entry addition after bootstrap fails.
26. Ratchet-to-report change fails.
27. Strict-to-ratchet change fails without policy approval.
28. Fingerprint replacement fails.
29. Baseline deletion with live findings fails.
30. One-to-many relocation fails.
31. Unproven relocation fails.
32. Initial bootstrap fails if findings do not exist at the base commit.

## Integration tests

33. All five guards emit valid structured findings.
34. All five guards are present in policy.
35. No covered workflow command contains `|| true`.
36. No covered CI mode is `warning` or `report`.
37. Suite executes later guards after an earlier ratchet failure.
38. Artifacts are produced on pass and failure.

---

# 14. Suggested burn-down workflow after PR C

For a normal debt-removal PR:

1. Fix one or more violations.
2. Run the relevant detector.
3. Run baseline pruning.
4. Review the removed entries.
5. Commit code and baseline reductions together.
6. CI verifies no unrelated finding was introduced.
7. Update issue progress from generated results.

A developer must never manually replace an old fingerprint with a new one.

Example:

```bash
python3 scripts/verify_cancellation_boundaries.py \
  --format json \
  --output build/ci/cancellation.json

python3 scripts/guards/prune_resolved_backlogs.py \
  --guard cancellation \
  --write
```

---

# 15. Recommended commit sequence

## Commit C1

`ci(guards): add structured finding and fingerprint contract`

Contains:

- Shared finding model.
- Path/symbol normalization.
- Deterministic fingerprints.
- Shared unit tests.

## Commit C2

`ci(guards): add exact backlog manifests and comparator`

Contains:

- Manifest schema.
- Comparator.
- Pruning tool.
- Exit-code contract.
- Comparator tests.

## Commit C3

`ci(guards): retrofit cancellation db and event guards for ratchets`

Contains:

- Structured outputs.
- Rule/category corrections.
- Fatal error behavior.
- Guard-specific tests.

## Commit C4

`ci(guards): retrofit privacy and money guards for strict ratchets`

Contains:

- Structured outputs.
- Read-error handling.
- Small-backlog fixes or exact manifests.
- Guard-specific tests.

## Commit C5

`ci: block guard backlog growth and baseline inflation`

Contains:

- Base-revision policy comparison.
- Initial bootstrap sealing.
- Workflow integration.
- Removal of warning execution and `|| true`.
- Artifact publication.

## Commit C6

`docs(ci): document exact guard debt ratchets`

Created only after successful GitHub Actions verification.

---

# 16. Risks and mitigations

## Fingerprint instability

**Risk:** Routine formatting causes false new/resolved pairs.

**Mitigation:**

- Exclude line numbers and whitespace.
- Use stable symbols and operation signatures.
- Test formatting and line movement.
- Version the fingerprint algorithm.

## Baseline hides false positives

**Risk:** Detector defects become accepted debt.

**Mitigation:**

- Human review every initial finding.
- Fix detector false positives before capture.
- Baseline only unresolved real violations.

## Baseline inflation

**Risk:** Feature PR adds its violation to the manifest.

**Mitigation:**

- Compare against PR base.
- Reject added entries.
- Protect manifests through CODEOWNERS.
- Require exact initial-bootstrap conditions.

## Detector improvements find historical debt

**Risk:** A new detector rule discovers pre-existing issues and blocks its own introduction.

**Mitigation:**

A future new-rule bootstrap may be accepted only if CI runs the new detector against the base revision and proves every proposed entry already existed before the PR. This process must not accept findings introduced by the current change.

## File rename creates churn

**Risk:** Existing findings appear new after a rename.

**Mitigation:**

- Prefer fixing debt during the move.
- Otherwise use verified one-to-one `supersedes` mappings.
- Never accept unrestricted fingerprint replacement.

---

# 17. PR acceptance checklist

## Shared framework

- [ ] Structured finding model implemented.
- [ ] Stable fingerprints exclude line numbers.
- [ ] Paths are repository-relative and normalized.
- [ ] Symbols and operation signatures are present.
- [ ] Detector/configuration errors exit 2.
- [ ] Determinism tests pass.

## Baselines

- [ ] Baselines captured from the final PR B commit.
- [ ] Every entry maps to one real finding.
- [ ] False positives excluded.
- [ ] Duplicate entries rejected.
- [ ] Stale entries rejected.
- [ ] Baseline additions after bootstrap rejected.
- [ ] Counts are derived, not manually maintained.

## Guard enforcement

- [ ] Cancellation runs in ratchet mode.
- [ ] DB access runs in ratchet mode.
- [ ] Event writers run in ratchet mode.
- [ ] Privacy runs strict or exact ratchet.
- [ ] Money runs strict or exact ratchet.
- [ ] No covered guard uses warning/report mode in CI.
- [ ] No covered guard uses `|| true`.

## Anti-bypass

- [ ] Fix-one/add-one test fails.
- [ ] Lower-count-with-new-finding test fails.
- [ ] Baseline inflation test fails.
- [ ] Mode weakening test fails.
- [ ] Stale baseline test fails.
- [ ] Detector crash test fails as infrastructure error.

## CI evidence

- [ ] Complete static suite passes.
- [ ] All five guards execute.
- [ ] All comparison reports are uploaded.
- [ ] Guard pytest passes.
- [ ] Unit tests pass.
- [ ] Lint and `:app:check` pass.
- [ ] Two consecutive CI runs are green.
- [ ] Exact SHA and Actions run ID are documented.

---

# 18. Definition of done

PR C is complete only when:

- The five backlog guards can no longer grow silently.
- Every tolerated violation has one exact stable fingerprint.
- Any new violation fails CI even when total debt decreases.
- Resolved violations force baseline reduction.
- Developers cannot add baseline entries to unblock their PR.
- No guard execution is neutralized by warning mode or `|| true`.
- Guard crashes and unreadable source/configuration fail closed.
- Generated artifacts show unchanged, new and resolved debt.
- Privacy and money are strict-zero where feasible.
- Documentation reflects an actual successful CI run.

The required invariant is:

> **The set of tolerated architecture violations can only remain identical or shrink; it can never expand, mutate or be silently replaced.**