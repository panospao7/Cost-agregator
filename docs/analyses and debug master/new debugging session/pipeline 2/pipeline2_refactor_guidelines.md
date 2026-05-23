# Pipeline 2 refactor guidelines

Pipeline 2 is not just “expense CRUD.” It crosses:

1. **Financial correctness**
   - amount/currency/date/type validation,
   - transfer metadata,
   - ownership/shared expense math,
   - duplicate/idempotency handling.

2. **Room transaction semantics**
   - returning an error from `withTransaction` commits,
   - throwing rolls back,
   - nested coordinator calls can make side effects run too early.

3. **Side effects**
   - budgets,
   - anomaly detection,
   - recurring links,
   - merchant/category learning,
   - dashboard/cache freshness.

4. **Provenance**
   - review → expense,
   - receipt → expense,
   - group → expense,
   - import/bank/email/notification → expense.

5. **Restore/write safety**
   - no mutation during restore,
   - no direct DAO write outside approved paths.

6. **Auditability**
   - every create/update/delete/drop/duplicate/conflict must be explainable later.

Main warning:

```text
Do not fix Pipeline 2 locally per repository method.
Fix the lifecycle contracts and make callsites obey them.
```

## Pipeline 2 invariants to protect

### 1. Expense mutation invariant

```text
No expense row mutation unless:
- TransactionLifecycleCoordinator owns it, or
- the method is explicitly allowlisted,
- guarded by DatabaseWriteBarrier,
- tested/static-guarded.
```

Be very careful with:

```text
expenseDao.update(...)
expenseDao.delete(...)
expenseDao.insert(...)
expenseDao.clearSharedExpenseFlags(...)
expenseDao.updateCategory...
```

Every direct DAO mutation should feel suspicious.

---

### 2. Create/update validation invariant

```text
An update must not be able to create an invalid expense state that create would reject.
```

So create and update should share one final-state validator.

Guard:

- amount,
- merchant,
- currency,
- date,
- transfer fields,
- ownership conflict,
- location pair/range.

Do not only validate UI inputs. Validate the final `Expense`.

---

### 3. Atomicity invariant

```text
If two writes represent one user operation, they must commit or rollback together.
```

Examples:

- group system expense + group link,
- receipt-created expense + receipt link,
- source link + created expense,
- category bulk update + bulk event,
- debug restore + restore event.

Inside `database.withTransaction`:

```text
returning Error = commits
throwing exception = rolls back
```

Agents must not “return failure” from inside a Room transaction if rollback is required.

---

### 4. Post-commit side-effect invariant

```text
DB transaction commits first.
Side effects run after commit.
Outer transaction owner dispatches side effects.
```

Danger pattern:

```text
GroupTransactionCoordinator.withTransaction {
    transactionLifecycleCoordinator.updateExpense(...)
    // coordinator dispatches side effects before outer transaction commits
}
```

Safer pattern:

```text
DB-only mutation returns postCommitActions
outer coordinator commits
outer coordinator dispatches actions
```

---

### 5. Duplicate/idempotency invariant

```text
Duplicate/conflict paths must be visible and must not trigger creation side effects.
```

For duplicate creates:

- no `CREATED`,
- no budget creation side effect,
- write `CREATE_DUPLICATE_SKIPPED`,
- resolve existing ID when possible.

For strict external ID:

```text
attempt key == persisted dedupe key
```

No mismatch between audit and actual idempotency key.

---

### 6. Provenance invariant

```text
Every non-manual runtime create must carry concrete source provenance.
```

Avoid weak fallback:

```text
LEGACY_SOURCE_ONLY
```

except migrations/backfills/debug.

Examples:

- review: `pendingReviewId`,
- receipt: `scannedReceiptId`,
- group: `groupId`,
- CSV: batch + row,
- bank: sync/provider/account source,
- notification: raw notification ID.

---

### 7. Bulk invariant

```text
Bulk operations are aggregate operations, not N local updates.
```

Prefer:

```sql
UPDATE expenses SET categoryId = :new WHERE categoryId = :old
```

Then:

```text
one BULK_UPDATED event
one post-commit bulk side-effect batch
changedFields declared
```

Avoid loops calling per-expense lifecycle methods unless truly needed.

---

### 8. Delete invariant

```text
Delete behavior must be explicitly tested.
```

Especially:

- transaction events survive,
- receipt links policy,
- group links policy,
- recurring links policy,
- before snapshot is current,
- entity delete overloads do not use stale screen-loaded objects.

---

## Biggest regression risks

1. Fixing a repository path while another path still bypasses coordinator.
2. Returning errors inside `withTransaction` instead of throwing.
3. Dispatching side effects inside an outer transaction.
4. Adding a direct DAO mutation without barrier/static allowlist.
5. Fixing create validation but leaving update validation weaker.
6. Adding provenance model but not wiring every `CreateExpenseRequest` callsite.
7. Writing diagnostics/events with raw merchant/notes/receipt payloads.
8. Treating duplicate/conflict as “not important” because no row was created.

## Suggested rule for every Pipeline 2 PR

Each PR should answer:

```text
1. What DB rows can this operation mutate?
2. Is every mutation write-barrier protected?
3. Is the operation atomic?
4. What lifecycle event/diagnostic explains success/failure/skip?
5. Are side effects after commit only?
6. Is provenance preserved?
7. Are duplicate paths side-effect free?
8. Is there a static/regression test preventing reintroduction?
```

## Recommendation

Before implementing more fixes, create a short doc:

```text
docs/architecture/pipeline-2-transaction-lifecycle-invariants.md
```

Then require every Pipeline 2 PR to prove those invariants with tests or static guards.

That will prevent the Pipeline 1 loop where each fix repairs one path but reopens another boundary.