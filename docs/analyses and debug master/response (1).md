Yes — if you have an older app database and don’t want to lose it, you need a **migration-first policy**.

## What to do

### 1. Never use destructive migration in production

Avoid:

```kotlin
fallbackToDestructiveMigration()
```

That wipes/recreates the DB when Room cannot migrate.

For this app, keep production builder like:

```kotlin
Room.databaseBuilder(...)
    .addMigrations(*ALL_MIGRATIONS)
```

### 2. Keep every old migration path

Room can migrate like:

```text
v70 → v71 → ... → v92
```

So don’t delete old migrations just because the app is now on v92.

If users may have v40, v60, v80, etc., keep migrations from those versions.

### 3. Test your real old DB before shipping

Best practice:

1. Take a copy of your old `.db`.
2. Put it in migration tests.
3. Open it with the new app version.
4. Verify:
   - app does not crash
   - expenses remain
   - receipts remain
   - groups remain
   - budgets remain
   - Room schema validation passes
   - `PRAGMA foreign_key_check` passes

### 4. Add an in-app backup/export before risky releases

For a finance app, add:

- export database backup
- export JSON/CSV backup
- restore/import path

Before a major schema upgrade, prompt:

> “We recommend creating a backup before updating your database.”

### 5. For very old schemas, use a legacy importer

If supporting every old Room migration becomes too messy, do this:

```text
old DB → legacy read-only importer → new clean schema
```

That means you open the old database manually, read old tables, and insert normalized data into the new schema.

This is safer than endless fragile migrations for ancient versions.

### 6. Add fresh-vs-migrated parity tests

Make sure:

```text
fresh v92 database
```

and

```text
old v70 database migrated to v92
```

end up with the same schema behavior.

## My recommendation for your app

Use this policy:

1. **Keep all existing migrations.**
2. **Never destructive-migrate user DBs.**
3. **Add migration tests using real old DB snapshots.**
4. **Add backup/export before major schema changes.**
5. **For huge redesigns, create a legacy importer instead of risky direct migration.**

The safest rule is:

> Schema can evolve, but user financial history must be treated as permanent data.