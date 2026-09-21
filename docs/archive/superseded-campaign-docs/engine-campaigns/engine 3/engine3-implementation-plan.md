# Engine 3 Implementation Plan — Categorization / Merchant Normalization

Engine: **Categorization / Merchant Normalization**  
Current verdict: **YELLOW / red-leaning because this is a shared engine**

Core goal:

> Make merchant/category decisions deterministic, source-aware, cache-safe, privacy-safe, and pipeline-safe without regressing notification, receipt, recurring, email, analytics, dashboard, or review flows.

Do **not** start with schema changes.  
Do **not** start with broad learning-policy rewrites.  
Do **not** rewrite merchant normalization globally in one PR.

---

# Main current problems

From the Engine 3 audit, the biggest remaining issues are:

1. alias linking can still silently no-op for same normalized key + same canonical
2. mapping insert conflicts are not surfaced to callers
3. cache invalidation is better but still not fully safe/coordinated
4. `normalizedCanonicalName` lookup remains ambiguous
5. auto-learning is still source-blind
6. semantic ambiguity does not fully reach review routing
7. merchant stats can double count and raw-sum mixed currencies
8. category correction/backfill is no-op or lifecycle-bypassing
9. decision trace stores raw merchant names and uses wall clock
10. raw DAO mutation surfaces remain public and easy to misuse

---

# Affected pipelines

| Pipeline | Risk |
|---|---|
| Notification processing | High |
| Pending review queue | High |
| Receipt/OCR matching | High |
| Email ingestion | High |
| Recurring detection | High |
| Transaction lifecycle side effects | High |
| Analytics merchant/category grouping | Medium-high |
| Dashboard merchant/category widgets | Medium |
| Backup/restore/write barrier | Medium |
| Privacy/debug diagnostics | Medium-high |

---

# General non-regression rule

A change is acceptable only if:

1. merchant keys remain stable for existing merchants
2. receipt/email/notification merchant matching still works
3. valid manual corrections still learn correctly
4. ambiguous auto classifications route to review or are clearly marked
5. auto sources do not create strong permanent mappings unless policy allows
6. no raw merchant text is persisted/emitted in debug traces
7. no write bypasses restore/write barrier
8. category and merchant caches invalidate immediately and safely
9. analytics/category totals remain stable
10. no Room migration is added unless the slice explicitly requires it

---

# Recommended PR sequence

## PR0 — Baseline checkpoint

### Goal

Freeze the current working state.

### Steps

- create branch: `engine3-categorization-hardening`
- tag working state
- do not change schema
- do not touch rescue/database baseline work

### Deliverables

- current app still boots
- Engine 3 audit saved in docs
- Engine 3 implementation plan saved in docs

---

# PR1 — Alias linking contract and timestamp hardening

## Goal

Fix the silent alias no-op bug without schema changes.

## Issues closed

- `C01`
- residual `C02`
- `E3-NOW-001` alias part

## Main files

- `MerchantNormalizer.kt`
- `MerchantNormalizationRepository.kt`
- `MerchantNormalizationDao.kt`
- `MerchantAlias.kt`
- DAO/repository/normalizer tests

## Current problem

The safe check exists in `MerchantNormalizer`, but the DAO transaction still mainly checks by `rawName`.

Problem case:

```text
Existing alias:
rawName = "McDonald's"
normalizedKey = "mcdonalds"
canonicalId = 1

New alias:
rawName = "MCDONALDS"
normalizedKey = "mcdonalds"
canonicalId = 1
```

Expected:

```text
update existing alias occurrenceCount/lastUsedAt
```

Current likely behavior:

```text
insert ignored by unique normalizedKey
caller sees success
stats are not updated
```

## Implementation plan

### Step 1 — Introduce explicit alias-link result

Repository should no longer return `Unit`.

Use a result concept like:

- `Created`
- `UpdatedExistingSameCanonical`
- `ConflictDifferentCanonical`
- `CanonicalMissing`
- `Ignored`

No broad architecture change yet.

### Step 2 — Resolve by normalized key inside the DB transaction

Within the repository/DAO transaction:

1. lookup alias by normalized key
2. if found and same canonical:
   - update occurrence count
   - update `lastUsedAt`
   - preserve `createdAt`
   - return `UpdatedExistingSameCanonical`
3. if found and different canonical:
   - return conflict
4. otherwise lookup raw name if needed
5. insert new alias with `createdAt = now` and `lastUsedAt = now`

### Step 3 — Normalize timestamps on direct repository insert

If repository has `insertAlias(alias)`, protect against sentinel timestamps before insert.

### Step 4 — Keep public normalizer behavior compatible

`MerchantNormalizer.linkAliasToCanonical()` can still return its existing public result, but it must map repository result correctly.

## Engine tests

- alias same normalized key + same canonical updates count
- alias same normalized key + same canonical updates `lastUsedAt`
- alias same normalized key + different canonical returns conflict
- new alias sets `createdAt`
- direct repository alias insert cannot persist `createdAt = 0`

## Pipeline tests

- notification merchant alias still resolves to canonical
- receipt merchant variant still matches canonical
- recurring merchant matching still works after alias update
- duplicate transaction detection still receives stable merchant key

## Risk

Medium. No schema impact.

---

# PR2 — Cache invalidation and mapping write contract

## Goal

Make merchant/category mapping writes honest and cache-safe.

## Issues closed

- `C04`
- `C05`
- `E3-NOW-007`

## Main files

- `CategorizationEngine.kt`
- `MerchantCategoryRepository.kt`
- `CategoryRepository.kt`
- `MerchantCategoryDao.kt`
- tests

## Current problems

1. `MerchantCategoryDao.insert()` returns an ID, but repository hides conflict by returning `Unit`.
2. `CategorizationEngine.learnMerchantCategory()` can log success even when insert was ignored.
3. `ensureDefaultCategories()` directly mutates merchant mappings and normalized names.
4. `invalidateAllCaches()` uses a different lock from cache reads.

## Implementation plan

### Step 1 — Return mapping write result

Repository insert should report:

- inserted
- updated
- conflict
- ignored

### Step 2 — CategorizationEngine learns honestly

`learnMerchantCategory()` should return/report actual result.

Do not log “learned” when DB ignored the insert.

### Step 3 — Safe invalidation

Make invalidation use the same synchronization model as cache load/read.

Preferred:

- make invalidation suspend and use the same mutex

If that is too disruptive:

- replace cache state with an atomic immutable cache object
- invalidation sets atomic reference to null

Do not leave two unrelated locks.

### Step 4 — Fix default category seeding path

After `ensureDefaultCategories()` inserts merchant mappings or backfills normalized canonical names, explicitly invalidate categorization/hybrid caches.

Longer term, route seeding through a central writer.

## Engine tests

- insert duplicate mapping returns conflict/ignored
- `learnMerchantCategory()` does not report success on ignored insert
- default category seeding invalidates cache
- cache invalidation during categorize does not expose partial state
- deleting mappings invalidates immediately

## Pipeline tests

- new merchant-category mapping is visible immediately to notification classification
- default merchant mappings are visible immediately after seeding
- category merge/delete invalidates categorization results

## Risk

Medium. No schema impact if done carefully.

---

# PR3 — Deterministic `normalizedCanonicalName` resolution

## Goal

Remove nondeterministic merchant-category lookup without schema migration.

## Issues closed

- `C06`

## Main files

- `MerchantCategoryDao.kt`
- `MerchantCategoryRepository.kt`
- `CategorizationEngine.kt`
- tests

## Current problem

`normalizedCanonicalName` has non-unique index. Query returns one row without deterministic ordering.

## Recommended first fix

No schema migration yet.

Make lookup deterministic:

Priority order:

1. user-defined / confirmed mapping if available
2. higher confidence
3. higher `timesUsed`
4. newest confirmed/updated if available
5. stable lexical tie-breaker by merchant pattern or ID

If some fields do not exist yet, use available ones:

- confidence
- timesUsed
- merchantPattern
- id

## Tests

- duplicate normalized canonical candidates resolve deterministically
- higher confidence wins
- higher times used wins if confidence tied
- stable tie-breaker prevents random result

## Risk

Low/medium.

## Deferred schema option

Later, after DB baseline is stable:

- cleanup duplicates
- add unique index or explicit candidate table

Do not do that in this PR.

---

# PR4 — Ambiguity propagation to review routing

## Goal

If semantic/category result is ambiguous, downstream pipelines must know.

## Issues closed

- `C12`
- `E3-NOW-003`

## Main files

- `CategorizationEngine.kt`
- `SemanticKeywordMatcher.kt`
- `HybridExpenseClassifier.kt`
- `ExpenseClassifier.kt`
- `NotificationProcessingPipeline.kt`
- `ReviewQueueRepository.kt`
- tests

## Current problem

`SemanticKeywordMatcher` can detect ambiguity.  
`CategorizationResult` has ambiguity flags.  
But `ClassificationResult` likely drops `isAmbiguous/requiresReview`.

Notification pipeline then only sees category ID and may treat it as normal.

## Implementation plan

### Step 1 — Extend classification result

Add fields conceptually equivalent to:

- `isAmbiguous`
- `requiresReview`
- `alternatives`
- `reason`

Be careful: this may touch many tests/call sites.

### Step 2 — Preserve ambiguity from categorization engine

When dictionary/semantic categorization is ambiguous:

- category suggestion can exist
- but `requiresReview = true`
- confidence should not be “auto-accept safe”

### Step 3 — Notification/review behavior

If classification requires review:

- pending review should be created or marked as needing user decision
- auto-accept should be suppressed unless another higher-authority signal overrides

### Step 4 — Do not change broad learning policy yet

This PR only propagates ambiguity. Learning authority comes in PR5.

## Engine tests

- semantic ambiguity reaches classification result
- alternatives are preserved
- ambiguous result lowers confidence/requires review

## Pipeline tests

- ambiguous notification category routes to pending review
- non-ambiguous high-confidence category still behaves as before
- receipt/email category suggestions still compile statically with new result fields

## Risk

High because `ClassificationResult` call sites may be broad.

---

# PR5 — Source-aware learning policy

## Goal

Stop auto-learning from untrusted sources while preserving manual/review learning.

## Issues closed

- `C10`
- `E3-NOW-004`

## Main files

- `TransactionSideEffectPlanner.kt`
- `TransactionSideEffectDispatcher.kt`
- `MerchantCategoryRepository.kt`
- `HybridExpenseClassifier.kt`
- possibly `ExpenseSource` model
- notification/review tests

## Current problem

Post-commit side effects learn merchant-category pattern for every created/updated expense with category ID, regardless of source.

This can turn wrong auto-classified transactions into permanent mappings.

## Implementation plan

### Step 1 — Define learning authority policy

Create a small policy object or function, not a broad rewrite.

Strong learning allowed for:

- manual user-created category
- explicit category correction
- review-approved transaction
- user-edited pending review

Strong learning not allowed for:

- notification auto-accepted classification
- OCR guess
- email parser guess
- ML-only guess
- bank import guess
- imported CSV unless explicitly confirmed

### Step 2 — Add side-effect gating

Post-commit learning action should check:

- source
- confidence/authority
- whether category was user-confirmed
- whether update actually changed merchant/category

### Step 3 — Updates should not blindly learn

For updates:

- only learn if user changed category or confirmed merchant-category relationship
- do not re-learn every update

### Step 4 — Keep provisional learning deferred

Do not add new DB fields yet unless necessary.

If you need a provisional concept, represent it in memory/docs first and defer schema.

## Engine tests

- manual correction strong-learns
- review approval strong-learns
- auto-accepted notification does not strong-learn
- ML-only result does not strong-learn
- update without category change does not learn
- update with user category correction learns

## Pipeline tests

- notification auto-create still creates expense but does not create permanent merchant mapping
- review approve creates expense and learns mapping
- user manual category choice learns mapping
- email/OCR suggestion does not strong-learn until reviewed

## Risk

High. This affects many pipelines. Do after PR1–PR4.

---

# PR6 — Merchant stats correctness

## Goal

Stop merchant stats from becoming misleading/double-counted.

## Issues closed

- `C08`
- `E3-NOW-005`
- `E3-NOW-006`

## Main files

- `TransactionSideEffectPlanner.kt`
- `MerchantNormalizationRepository.kt`
- `MerchantNormalizationDao.kt`
- `MerchantCanonical.kt`
- analytics/dashboard tests

## Current problems

1. create increments merchant stats
2. update also increments stats, causing double count
3. delete does not decrement or recompute
4. `totalSpent` is raw `Double` without currency

## Recommended no-schema fix first

Because schema changes are risky now:

1. keep occurrence count updates on create
2. do not increment stats on update unless you can apply a true delta
3. do not present `totalSpent` as financially meaningful if mixed currency
4. if possible, recompute merchant stats from expenses periodically or on demand
5. document that raw `totalSpent` is legacy/unsafe

## Better later schema fix

Later add merchant stats buckets:

- merchant ID
- currency
- amount
- transaction count

But defer until baseline DB is stable.

## Engine tests

- create increments occurrence once
- update does not double count
- delete does not make stats claim precision
- raw total spent is not used as authoritative mixed-currency value

## Pipeline tests

- analytics top merchants do not double count after edit
- dashboard merchant widget does not show mixed-currency raw total as final truth
- merchant search ranking still works after create/update

## Risk

Medium/high. No schema if doing safety-first version.

---

# PR7 — Diagnostics, privacy, cancellation, and static guards

## Goal

Make debug/diagnostic behavior safe and block future bypasses.

## Issues closed

- `C14`
- `E3-NOW-002`
- part of `E3-NOW-009`
- raw DAO guard issues

## Main files

- `CategorizationEngine.kt`
- `HybridExpenseClassifier.kt`
- `FeatureExtractor.kt`
- static guard tests
- docs

## Implementation plan

### Step 1 — Rethrow cancellation

In classifier paths that catch broad exceptions:

- rethrow coroutine cancellation
- only fallback for non-cancellation failures

### Step 2 — Sanitize decision trace

Current trace stores raw merchant. Replace with:

- hashed merchant key
- optional short redacted preview if privacy settings allow
- category ID/name
- method
- timestamp from `TimeProvider`

### Step 3 — Use deterministic time source

Replace wall-clock in categorization trace with `TimeProvider`.

### Step 4 — Protect trace buffer

Use one synchronization strategy.

Do not mix mutex + unsynchronized mutable collection.

### Step 5 — Static guard

Add static checks to prevent production code calling raw DAO mutators directly, except allowlisted repositories/tests.

Guard targets:

- `MerchantCategoryDao.insert/update/delete`
- `MerchantNormalizationDao.insertAlias`
- direct merchant-category mapping mutation paths

### Step 6 — Calendar migration note

`FeatureExtractor` still uses Calendar. Either:

- fix in this PR if small and isolated
- or document for Engine 5/time primitive slice

## Engine tests

- classifier cancellation rethrows
- decision trace does not store raw merchant
- decision trace uses `TimeProvider`
- trace buffer is safe under concurrent calls
- raw DAO mutator guard catches unapproved production use

## Pipeline tests

- notification fallback classification still works on non-cancellation ML error
- cancellation does not create fallback category
- debug trace redaction does not break categorization

## Risk

Low/medium.

---

# PR8 — Category correction/backfill lifecycle

## Goal

Make category correction/backfill honest and lifecycle-safe.

## Issues closed

- `C11`

## Risk

High. Do later.

## Current problems

- `updateExpenseCategoryBulk()` is no-op
- `mergeCategories()` directly updates historical expenses and other tables
- lifecycle events/side effects may be bypassed

## Implementation plan

### Step 1 — Define user choice

Every merchant/category correction should specify:

- future only
- backfill existing matching expenses
- backfill selected subset only

### Step 2 — Use lifecycle-aware bulk update

Do not directly update expenses through DAO for user-facing changes unless you also:

- record a bulk transaction lifecycle event
- invalidate analytics/budget/category caches
- dispatch side effects after commit

### Step 3 — Prevent event storm

For many rows, prefer one bulk event with affected count over thousands of per-expense events.

### Step 4 — Do not combine with PR5

Learning policy and backfill are related but should be separate.

## Tests

- future-only correction does not change old expenses
- backfill correction updates matching expenses
- bulk backfill writes lifecycle event
- analytics/budget invalidated after backfill
- category merge preserves transaction lifecycle audit

## Schema impact

Maybe none if using existing events.  
Potential schema impact if adding bulk event table.

---

# PR9 — Category display-name policy

## Goal

Stop lowercasing user-facing category names while retaining normalized lookup.

## Issues closed

- `E3-NOW-008`

## Risk

Medium. UI-visible behavior change.

## Current problem

`CategoryRepository.addCategory()` lowercases display name.

## Implementation plan

- store display name as trimmed original
- enforce uniqueness using normalized key/case-insensitive lookup
- update tests expecting lowercase if any
- do not change existing categories automatically unless explicitly planned

## Tests

- add category `"Dining Out"` displays `"Dining Out"`
- lookup by `"dining out"` still finds it
- duplicate `"DINING OUT"` is rejected or mapped to same category

## Schema impact

Potentially none if existing table can store display name.

---

# PR10 — Long-term schema-backed improvements

Only after v145 baseline is fully stable.

Candidates:

1. unique/deterministic merchant canonical mapping
2. merchant stats currency buckets
3. provisional/user-confirmed merchant-category fields
4. sanitized persistent categorization trace
5. alias variant table
6. category history or soft-delete support

Each must be a separate migration PR with migration tests.

---

# Engine 3 specific non-regression checklist

Use this checklist after every Engine 3 PR.

## Merchant normalization

- [ ] Existing merchant canonical keys remain stable.
- [ ] New merchant creation still creates canonical with nonzero timestamps.
- [ ] Alias creation sets `createdAt` and `lastUsedAt`.
- [ ] Same normalized key + same canonical updates existing alias instead of silent no-op.
- [ ] Same normalized key + different canonical returns conflict.
- [ ] Alias occurrence count does not reset accidentally.
- [ ] `lastUsedAt` updates on alias reuse.
- [ ] Receipt merchant variants still resolve to expected canonical.
- [ ] Notification merchant variants still resolve to expected canonical.
- [ ] Recurring merchant matching still works with aliases.

## Categorization mapping

- [ ] Merchant-category insert reports inserted/conflict/ignored honestly.
- [ ] `learnMerchantCategory()` does not log success when DB ignored insert.
- [ ] Duplicate merchant patterns do not create nondeterministic mappings.
- [ ] `normalizedCanonicalName` duplicate candidates resolve deterministically.
- [ ] Case-insensitive category lookup still works.
- [ ] Default categories and default merchant mappings still seed correctly.
- [ ] Default mapping seeding invalidates categorization cache.
- [ ] Category add/merge/delete invalidates categorization cache.
- [ ] Direct mapping writes do not bypass write barrier in production paths.

## Cache safety

- [ ] Cache invalidation uses same synchronization model as cache reads.
- [ ] Concurrent categorize + invalidate does not expose partial cache state.
- [ ] Inserted mapping is visible immediately after invalidation.
- [ ] Deleted mapping is not used after invalidation.
- [ ] Category merge/delete does not leave stale category IDs in classifier cache.
- [ ] Hybrid classifier category snapshot invalidates when categories change.

## Notification pipeline

- [ ] Notification parsing still creates expenses for valid confident transactions.
- [ ] Auto-category suggestion still appears for non-ambiguous high-confidence cases.
- [ ] Ambiguous categorization does not silently auto-accept as final category.
- [ ] Ambiguous categorization routes to pending review or marks review-required.
- [ ] Auto-accepted notification does not strong-learn merchant-category mapping unless policy allows.
- [ ] Duplicate detection still uses stable merchant key.
- [ ] Existing notification pipeline tests remain semantically valid.

## Pending review pipeline

- [ ] Review-approved expense can strong-learn merchant/category mapping.
- [ ] Rejected review does not learn.
- [ ] User-edited category in review can learn if policy allows.
- [ ] User-edited merchant alias links safely.
- [ ] Ambiguity alternatives are available to review UI/state if implemented.
- [ ] Pending review still stores suggested category when safe.

## Receipt / OCR pipeline

- [ ] Receipt merchant text still normalizes.
- [ ] Receipt-to-expense matching still works after alias changes.
- [ ] OCR/category guess does not strong-learn unless reviewed/confirmed.
- [ ] Receipt item/category suggestions still work.
- [ ] Ambiguous receipt category suggestion is review-routed or marked.

## Email ingestion pipeline

- [ ] Email merchant normalization still works.
- [ ] Email category suggestion still works.
- [ ] Email-created expenses do not strong-learn unless confirmed.
- [ ] Existing email import behavior does not regress.

## Recurring detection

- [ ] Recurring matching still uses stable merchant key.
- [ ] Alias updates do not split existing recurring subscriptions.
- [ ] Fuzzy matching does not over-merge different merchants.
- [ ] Existing recurring rules still match after alias reuse updates.

## Analytics/dashboard

- [ ] Merchant grouping remains stable.
- [ ] Category grouping remains stable.
- [ ] Category rename/delete behavior remains explicit.
- [ ] Top merchant stats do not double count after expense update.
- [ ] Deleted expense does not leave merchant stats presented as exact financial truth.
- [ ] Mixed-currency merchant spend is not shown as raw authoritative total.
- [ ] Dashboard merchant/category widgets still load.

## Learning policy

- [ ] Manual user correction strong-learns.
- [ ] Review approval strong-learns.
- [ ] Explicit category correction strong-learns.
- [ ] Auto notification does not strong-learn.
- [ ] ML-only guess does not strong-learn.
- [ ] OCR/email parser guess does not strong-learn.
- [ ] Update without category change does not learn.
- [ ] Update with explicit user category change can learn.
- [ ] Learning result is visible/logged honestly.

## Semantic ambiguity

- [ ] Semantic matcher alternatives are preserved.
- [ ] `requiresReview` survives into classifier result.
- [ ] Classifier confidence reflects ambiguity.
- [ ] Ambiguous result does not look like normal rule certainty.
- [ ] Notification/review pipeline respects ambiguity.
- [ ] Non-ambiguous semantic match still works as before.

## Privacy/debug diagnostics

- [ ] Decision trace does not store raw merchant names.
- [ ] Decision trace uses `TimeProvider`, not wall clock.
- [ ] Debug trace buffer is concurrency-safe.
- [ ] Diagnostic output is privacy-governed or sanitized.
- [ ] Cloud/debug/export paths do not receive raw merchant trace strings.
- [ ] Raw DAO mutator static guard exists or is planned.

## Coroutine/time correctness

- [ ] `HybridExpenseClassifier` rethrows cancellation.
- [ ] ML classifier failure still falls back only for non-cancellation exceptions.
- [ ] Time features use deterministic event timestamp where possible.
- [ ] Remaining Calendar usage is documented or migrated.
- [ ] Tests use fixed time provider when relevant.

## Backup/restore/write barrier

- [ ] Merchant canonical writes respect `DatabaseWriteBarrier`.
- [ ] Merchant alias writes respect `DatabaseWriteBarrier`.
- [ ] Merchant-category mapping writes respect `DatabaseWriteBarrier`.
- [ ] Default category/mapping seeding respects `DatabaseWriteBarrier`.
- [ ] No production path directly mutates merchant/category DAOs during restore.
- [ ] Static guard catches raw DAO writes outside allowlist.

## Tests/static review

- [ ] Engine unit tests added for each fixed bug.
- [ ] Pipeline tests added for notification/review/receipt/recurring where affected.
- [ ] DAO tests cover alias conflict and same-canonical update.
- [ ] No `@Ignore`.
- [ ] No weak assertions only checking non-null.
- [ ] Tests assert timestamps, counts, conflicts, cache invalidation, and review flags.
- [ ] Static grep confirms no new raw DAO bypass.
- [ ] Static grep confirms no raw merchant trace persistence.

## Build/schema discipline

- [ ] No Room migration in PR1–PR9 unless explicitly approved.
- [ ] No destructive migration.
- [ ] No DB baseline changes.
- [ ] No broad Hilt rewiring in alias/cache PRs.
- [ ] No global `CurrencyConverter`, `MoneyAmount`, or `TimePeriodUtils` change.
- [ ] No category backfill without lifecycle event plan.
- [ ] No schema uniqueness change until duplicates and migration strategy are known.

---

# Suggested validation commands

Do not run during individual static slices if following the orchestrator rule.

After all Engine 3 PRs are finalized:

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:check --stacktrace
```

If schema/migration is added later:

```bash
./gradlew :app:connectedDebugAndroidTest --stacktrace
```

If Hilt/DI is changed:

```bash
./gradlew :app:assembleDebug --stacktrace
```

---

# Definition of done for Engine 3

Engine 3 can be considered clean when:

- alias linking cannot silently no-op
- alias conflicts are explicit
- alias/canonical timestamps are nonzero in production paths
- merchant/category mapping writes return honest results
- all mapping/category mutations invalidate caches safely
- normalized canonical lookup is deterministic or uniquely enforced
- auto-learning is source-authority aware
- ambiguity reaches review routing
- merchant stats do not double count or raw-sum mixed currencies as truth
- category correction/backfill is lifecycle-safe or explicitly deferred
- decision trace is sanitized, deterministic, and privacy-safe
- classifier cancellation is not swallowed
- raw DAO mutators are guarded
- notification, review, receipt, recurring, email, analytics, and dashboard regression tests protect affected flows

Best first slice:

> **PR1 — Alias linking contract and timestamp hardening**

Best second slice:

> **PR2 — Cache invalidation and mapping write contract**

Do not start with schema, source-aware learning, or category backfill. Those have wider blast radius.