# Follow-up commit review — Cost-agregator `master-refactor`

Commits reviewed:

- `e132fced6751b7c84e64b887d4ff8de7bc165842`
- `96c7d05458d5742587be533682ed1b283ae67b6c`
- `1badc84ebc0408c1800c88e58c82bdf46a12e431`
- `20db75857ff104fbb8f0af0efe9dcbd749df38eb`

Static review only. I did not run Gradle, Room schema validation, or tests.

---

## Overall verdict

The follow-up work is meaningful and moves the project forward, especially for:

- MoneyAmount adoption.
- DB invariant enforcement.
- Backup OOM hardening attempt.
- Reminder snooze/dismiss adaptation.
- BudgetForecastingEngine historical currency normalization.
- Monthly savings obligation normalization.
- AnalyticsRepository `DataQualityReport` exposure.

But I would **not mark the hardening complete yet**.

Top blockers:

1. **`1badc84` does not actually solve the large-backup OOM problem fully.**
   - ZIP creation no longer uses `ByteArrayOutputStream`, good.
   - But `BackupEncryptionService.encrypt(File, OutputStream, password)` still calls `plaintextFile.readBytes()` and then encrypts to another `ByteArray`.
   - Restore still reads the entire `.costbackup` into memory and decrypts the entire ZIP into memory.

2. **`1badc84` writes a plaintext ZIP temp file beside the final output.**
   - If the output directory is user-visible/external, the unencrypted database + receipt files temporarily exist there.
   - If the app crashes before cleanup, the plaintext temp ZIP can remain.

3. **`96c7d05` planned-expense CHECK constraint is weaker than the KDoc claims.**
   - It does not enforce `openSourceOccurrenceKey == sourceOccurrenceKey`.
   - It allows a PLANNED row with `sourceOccurrenceKey = null` and arbitrary non-null `openSourceOccurrenceKey`.
   - This weakens the materialized-key invariant that Phase 7/10 dedup relies on.

4. **Fresh-install index names may drift from Room’s expected names.**
   - Some fresh-install callback indexes use `idx_*`, while entity-generated Room names are likely `index_*`.
   - Migration `106→107` uses `index_*` for some rebuilt indexes.
   - This can cause fresh-install vs migrated schema differences and possible Room validation mismatch.

5. **`20db758` fixes historical expense normalization, but budget limits may still be mixed-currency.**
   - Expenses are normalized to home currency.
   - `budget.amount` is still compared directly.
   - If a budget can be stored in a non-home currency, risk/probability/remaining can still be wrong.

---

# Commit-by-commit review

## 1. `e132fced` — MoneyAmount adoption

### What it does well

This commit adds many computed `MoneyAmount` properties across:

- Room entities.
- Domain models.
- ViewModels/use cases.
- Analytics/budget/dashboard/savings surfaces.

This is a good low-risk transition strategy because it does **not remove existing `Double` fields** and does **not require a DB migration**.

The pattern is generally:

- keep stored `Double` + `String currency`
- add computed `MoneyAmount`
- mark Room entity computed properties with `@get:Ignore`

That is a reasonable adaptation layer.

### Remaining concerns

#### 1. Direct `CurrencyCode(currency)` can throw

`CurrencyCode` validates length and uppercase format. Many new computed properties call the constructor directly.

That is okay only if every stored currency is guaranteed valid. But the database still has legacy/default/import paths and does not appear to have a universal currency `CHECK`.

Risk examples:

- lowercase `"eur"`
- blank currency
- legacy malformed value
- display currency accidentally `""`

Any UI/model path reading the computed property could crash.

Recommendation:

- Use fail-fast direct constructor only where data is truly trusted.
- For persisted/user/imported values, prefer safe parse with fallback or surface invalid currency as data-quality warning.

#### 2. This is adoption, not enforcement

The commit adds a useful API surface, but raw `Double` monetary fields still remain broadly used.

That is acceptable as a transition phase, but not enough to claim “money safety is enforced.”

Recommended next step:

- Add a lightweight rule/audit/test that flags new raw monetary model fields unless explicitly approved.
- Or at least add a documented “new code must expose MoneyAmount” CI check.

#### 3. Room validation still needs proof

`@get:Ignore` is plausible for computed getters, but this must be proven by:

- `:app:kaptDebugKotlin`
- Room schema export diff
- migration/schema validation test

The included review doc says compile was confirmed separately, but I did not verify that from CI output.

### Status

**Good adaptation commit. Not a full money-safety closure by itself.**

---

## 2. `96c7d05` — DB v107 CHECK constraints

### What it does well

This is a strong idea. It moves materialized invariant keys from “repository convention” toward DB-enforced correctness.

It raises DB version to `107` and adds migration `106→107`.

The following constraints are valuable:

- Budget active key invariant.
- Group member current-user key invariant.
- Planned expense open occurrence key invariant.
- Fresh-install callback updated.

This directly supports previous roadmap concerns about raw DAO inserts bypassing invariant maintenance.

### Important issue: planned expense CHECK is incomplete

The intended invariant is:

- Non-PLANNED rows must have `openSourceOccurrenceKey = null`.
- PLANNED rows with no `sourceOccurrenceKey` must have `openSourceOccurrenceKey = null`.
- PLANNED rows with a `sourceOccurrenceKey` must have `openSourceOccurrenceKey` equal to that source key.

But the actual CHECK allows any PLANNED row with a non-null `openSourceOccurrenceKey`.

So these bad states can pass:

- `status = PLANNED`, `sourceOccurrenceKey = abc`, `openSourceOccurrenceKey = xyz`
- `status = PLANNED`, `sourceOccurrenceKey = null`, `openSourceOccurrenceKey = xyz`

That matters because occurrence-based dedup depends on the key being accurate.

Recommendation:

- Add a `107→108` migration that rebuilds `planned_expenses` with the stricter invariant:
  - non-planned => open key null
  - planned + source null => open key null
  - planned + source non-null => open key equals source
- Before rebuilding, heal bad rows by setting `openSourceOccurrenceKey = sourceOccurrenceKey` for PLANNED rows and nulling it otherwise.

### Fresh-install index-name risk

The fresh-install callback appears to recreate some indexes using `idx_*` names, while the Room entity default index names are `index_*`.

Migration `106→107` recreates some as `index_*`.

This can create two different physical schemas:

- upgraded DB: `index_*`
- fresh DB after callback rebuild: `idx_*`

Room schema validation can be sensitive to index definitions/names. Even if behavior is equivalent, this is risky.

Recommendation:

- Use the same index names everywhere as Room expects from the entity annotations.
- Prefer explicit named indexes in entities if you want custom names.
- Add a fresh-install Room validation test and long-hop migration test to v107.

### `INSERT INTO ... SELECT *` risk

The migration rebuilds tables and uses `SELECT *` in some places.

That is only safe if old and new column order is exactly identical. It probably is for 106→107, but it is fragile.

Recommendation:

- In future rebuild migrations, always use explicit column lists.

### Status

**Good hardening direction, but do not close until planned-expense CHECK and fresh-install schema consistency are fixed/tested.**

---

## 3. `1badc84` — backup streaming ZIP + reminder actions

## Backup hardening

### What it improves

The backup creation path no longer builds the ZIP with `ByteArrayOutputStream`. It streams ZIP contents into a temp file first.

That improves one part of the OOM problem.

### Major remaining issue: encryption still loads whole ZIP

The new file-based encryption overload still does:

- read entire temp ZIP into memory
- call byte-array encryption
- write encrypted byte array

So the backup path can still OOM on large backups, just later in the pipeline.

The old memory pressure was:

- ZIP byte array
- encrypted byte array

The new memory pressure is still at least:

- plaintext ZIP byte array
- encrypted byte array

Recommendation:

- Implement true streaming encryption using chunked file reads and cipher streaming/update.
- Or pipe ZIP output directly into an encrypted output stream after writing the `.costbackup` header.
- Avoid any whole-archive `readBytes()` or whole-ciphertext `ByteArray`.

### Major security issue: plaintext temp ZIP location

The temp ZIP is created beside the final output file.

That temp ZIP contains:

- database.sqlite
- receipt files
- manifest/checksums

If output is user-visible/external/shared storage, plaintext financial data can temporarily exist there. If the app crashes before `finally`, it may remain.

Recommendation:

- Put plaintext temp files only in app-private cache/noBackup storage.
- Delete on next startup as crash cleanup.
- Prefer no plaintext temp file at all by streaming ZIP into encryption.

### Restore still has OOM risk

Extraction still reads:

- entire `.costbackup`
- entire decrypted ZIP

Then it streams from `zipBytes.inputStream()`.

So restore can still OOM on large backups.

Recommendation:

- Add file/stream-based decrypt.
- Extract decrypted ZIP from a private temp file or streaming decrypt pipeline.
- Enforce max entry sizes and total extracted size.

### Output atomicity

If encryption fails mid-write, `outputFile` may be left partially written.

Recommendation:

- Write to an encrypted temp output first.
- Rename/move only after successful completion.
- Clean partial output on failure.

## Reminder snooze/dismiss adaptation

### What it improves

The notification now has Snooze and Dismiss actions, with manifest receivers and icons.

This is a useful UX addition.

### Concerns

#### 1. `runBlocking` inside `BroadcastReceiver`

The receivers do DB work synchronously with `runBlocking(Dispatchers.IO)`. Broadcast receivers have strict execution-time expectations; this can lead to sluggish behavior or ANR risk.

Recommendation:

- Use `goAsync()` and complete pending result after coroutine work.
- Or enqueue a WorkManager job.

#### 2. Receivers build their own DB instance

The receivers call `AppDatabase.fileBuilder(context).build()` directly.

That bypasses normal app dependency injection and may bypass restore maintenance/write gates depending on how those are implemented.

Risk areas:

- concurrent restore
- separate Room instance contention
- missing app-level coordinators
- direct write path outside domain/service layer

Recommendation:

- Use Hilt entry point or worker/coordinator layer.
- Ensure snooze/dismiss respects restore maintenance mode.

#### 3. Notification may remain visible

Action button taps do not necessarily cancel the notification. `setAutoCancel(true)` mainly handles content tap behavior, not always action-button behavior.

Recommendation:

- Explicitly cancel notification after successful snooze/dismiss.

### Status

**Partial hardening. UX improved, but backup streaming/OOM/security is not finished.**

---

## 4. `20db758` — Phase 10 review fixes

### What it fixes well

This is the strongest Phase 10 follow-up.

It addresses several earlier review issues:

1. `BudgetForecastingEngine` no longer uses raw SQL monthly aggregates for historical prediction.
2. Historical expense snapshots are normalized through `AnalyticsCurrencyNormalizer`.
3. Current-period spent-to-date and historical forecast data now use the same normalization concept.
4. `MonthlySavingsSweepUseCase` normalizes recurring/planned upcoming obligations.
5. `PeriodKind.LAST_7_DAYS` and `LAST_30_DAYS` now use calendar-day range utilities.
6. `AnalyticsRepository.getDataQualityReport()` exposes the new shared report type.

This substantially improves the previous Phase 10 status.

### Remaining issue: budget amount currency

The engine normalizes expenses to home currency, but then compares against `budget.amount`.

If `budget.currency` can differ from home currency, the forecast still mixes units:

- normalized spent-to-date in home currency
- normalized predicted spending in home currency
- raw budget limit in budget currency

Recommendation:

- Either normalize all expenses to `budget.currency`, or convert `budget.amount` to home currency before risk/remaining/probability calculations.
- Store/display the forecast currency explicitly.

### Remaining issue: DataQualityReport integration is still shallow

Adding `AnalyticsRepository.getDataQualityReport()` is useful, but it does not prove the UI/forecast/health/savings/AI surfaces consume it.

Recommendation:

- Forecast outputs should carry data-quality metadata.
- Savings sweep should degrade if obligation conversion fails.
- Analytics UI should display reliability/warnings.
- Health/AI should avoid confident claims when quality is poor.

### Remaining issue: savings sweep conversion failures are safety-sensitive

`MonthlySavingsSweepUseCase` logs failed conversions and excludes them from known-upcoming obligations.

For a savings sweep, excluding obligations can overstate available money.

Recommendation:

- If MUST planned/recurring obligations fail conversion, mark sweep as degraded or block automatic sweep.
- Surface a warning to the user.
- Consider using original-currency buckets rather than silently excluding.

### Remaining issue: period range timezone semantics

`LAST_7_DAYS` and `LAST_30_DAYS` improved from rolling milliseconds to calendar days.

But earlier concern remains if `PeriodKind.toPeriodRange(zoneId = ...)` stores `zoneId` without actually using it in boundary computation.

Recommendation:

- Ensure all `TimePeriodUtils` boundary functions accept/use the requested zone.
- Add tests for DST/timezone boundary cases.

### Performance tradeoff

Replacing SQL aggregates with raw snapshot normalization fixes correctness but fetches more rows.

For 3 months this is likely acceptable, but heavy users may still need:

- category/type-filtered snapshot DAO queries
- pagination/chunking
- normalized aggregate cache

### Status

**Phase 10 is now much closer. Historical budget normalization blocker is mostly fixed, but budget-limit currency and quality propagation remain.**

---

# Updated completion status

## Phase 9 / backup hardening

**Not fully complete.**

Earlier Phase 9/9b work fixed many restore-safety issues, but `1badc84` shows the large-backup OOM fix is incomplete and introduces a plaintext temp-file security risk.

Minimum remaining work:

1. True streaming encryption.
2. True streaming/deferred restore extraction.
3. Private temp storage only.
4. Startup cleanup for orphaned plaintext temp ZIPs.
5. Atomic output write.
6. Tests with large backup/large receipt set.

## Phase 10

**Mostly implemented, but not fully closeable.**

`20db758` fixes the main historical-normalization issue I flagged earlier. That is a significant improvement.

Remaining Phase 10 blockers:

1. Convert budget limits or normalize forecast data to budget currency.
2. Strengthen planned-expense DB CHECK.
3. Finish `DataQualityReport` propagation beyond repository method.
4. Treat savings conversion failures as degraded/unsafe, not just logged.
5. Add tests.

---

# Priority fixes

## P0 — must fix before release

1. Replace backup encryption `readBytes()` with true streaming encryption.
2. Move plaintext temp ZIP out of user-visible output directory, or eliminate plaintext temp ZIP.
3. Add streaming restore/decrypt path.
4. Fix planned-expense CHECK constraint with a new DB migration.
5. Normalize `budget.amount` currency in `BudgetForecastingEngine`.

## P1 — should fix before marking complete

1. Align fresh-install callback index names with Room expected index names.
2. Add Room migration tests:
   - fresh install to v107
   - 106→107
   - long-hop old version→107
3. Replace `BroadcastReceiver.runBlocking` with `goAsync()` or WorkManager.
4. Make reminder actions go through app coordinator/write gate.
5. Explicitly cancel notification after snooze/dismiss.

## P2 — quality / maintenance

1. Add compile-time or CI audit for new raw monetary fields.
2. Use safe `CurrencyCode.parseOr` for persisted/untrusted currency strings.
3. Add data-quality UI indicators.
4. Add backup crash cleanup for orphaned temp files.
5. Add performance tests for normalized historical forecast.

---

# Recommended tests

## Backup

- Create backup with large DB + many receipt images.
- Assert no OOM.
- Assert no plaintext temp remains after success.
- Simulate failure during encryption and assert temp cleanup.
- Simulate crash and startup cleanup.
- Restore large backup without loading full archive into memory.

## DB v107

- Invalid budget materialized keys fail insert.
- Invalid group current-user key fails insert.
- Planned row with mismatched `sourceOccurrenceKey` / `openSourceOccurrenceKey` fails insert.
- Fresh-install schema validates against Room.
- 106→107 migration validates against Room.
- Long-hop migration validates.

## Phase 10

- Mixed-currency expenses + non-home-currency budget.
- Missing FX rate in budget forecast.
- Savings sweep with failed MUST obligation conversion.
- LAST_7/LAST_30 calendar-day ranges across DST/timezone boundaries.
- DataQualityReport shown/propagated to UI/forecast outputs.

---

# Final verdict

These commits are good progress, especially `20db758`.

But I would set status to:

> **Phase 10: near-complete at implementation level, still incomplete at correctness/proof level.**  
> **Backup/restore hardening: still incomplete for large files and plaintext-temp security.**  
> **DB invariant hardening: good direction, but planned-expense CHECK must be corrected.**

Do not call the whole refactor/release line complete until the P0 items are fixed and Gradle/Room tests pass.

---

# Sources reviewed

- `e132fced` commit: https://github.com/panospao7/Cost-agregator/commit/e132fced6751b7c84e64b887d4ff8de7bc165842
- `96c7d05` commit: https://github.com/panospao7/Cost-agregator/commit/96c7d05458d5742587be533682ed1b283ae67b6c
- `1badc84` commit: https://github.com/panospao7/Cost-agregator/commit/1badc84ebc0408c1800c88e58c82bdf46a12e431
- `20db758` commit: https://github.com/panospao7/Cost-agregator/commit/20db75857ff104fbb8f0af0efe9dcbd749df38eb
- `CostbackupBundle.kt` at `20db758`: https://raw.githubusercontent.com/panospao7/Cost-agregator/20db75857ff104fbb8f0af0efe9dcbd749df38eb/app/src/main/java/com/yourname/expensetracker/data/backup/CostbackupBundle.kt
- `BackupEncryptionService.kt` at `20db758`: https://raw.githubusercontent.com/panospao7/Cost-agregator/20db75857ff104fbb8f0af0efe9dcbd749df38eb/app/src/main/java/com/yourname/expensetracker/data/privacy/BackupEncryptionService.kt
- `AppDatabase.kt` at `20db758`: https://raw.githubusercontent.com/panospao7/Cost-agregator/20db75857ff104fbb8f0af0efe9dcbd749df38eb/app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt
- `BudgetForecastingEngine.kt` at `20db758`: https://raw.githubusercontent.com/panospao7/Cost-agregator/20db75857ff104fbb8f0af0efe9dcbd749df38eb/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt
- `MoneyAmount.kt` at `20db758`: https://raw.githubusercontent.com/panospao7/Cost-agregator/20db75857ff104fbb8f0af0efe9dcbd749df38eb/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAmount.kt
- `CurrencyCode.kt` at `20db758`: https://raw.githubusercontent.com/panospao7/Cost-agregator/20db75857ff104fbb8f0af0efe9dcbd749df38eb/app/src/main/java/com/yourname/expensetracker/domain/core/money/CurrencyCode.kt