# Testing Master Status

**Date:** May 6, 2026
**Commit:** `0857153214a7b0a4f261aaa1b479a954f2999744` (P0 safety baseline)

## Current State
- **Total test files (src/test):** 397 (Kotlin only — zero Java test files)
- **Instrumented test files (androidTest):** 26
- **Database version:** v117 (`APP_DATABASE_SCHEMA_VERSION = 117`)
- **Schema verifier status:** Migration-aware (reads from `AppDatabase.kt`, not hardcoded)
- **CI status:** GitHub Actions workflow created (`.github/workflows/ci.yml`)
- **Est. total test methods:** ~4,000+ (from prior audits; coverage report recorded 1,790+ in March 2026 across fewer files)

## Schema Snapshot Coverage
- **Present versions:** 33–53, 56–57, 59–60, 64–65, 67–96, 100–117
- **Intentional gaps:** 1–32 (pre-schema export), 54–55, 58, 61–63, 66, 97–99 (skip/jump migrations)
- **Latest snapshot:** 117

## Security / Repo Hygiene
- Added `.gitignore` rules for: `*.db`, `*.sqlite`, `*.costbackup`, `repomix-output.*`, `session-*.md`, `data.json`, `revodata.json`, `hardcoded_string_audit.json`
- Audit needed: committed `expense_tracker_backup_*.db` may contain real user data

## Ignored Tests
- **Files with @Ignore annotations:** 27
- **Classification status:** Pending (see [`TEST_IGNORE_CLASSIFICATION.md`](./TEST_IGNORE_CLASSIFICATION.md))

## Known Gaps (from analysis docs)
- No DB-backed lifecycle coordinator contract tests (mock-only)
- No Hilt graph smoke test
- No privacy subsystem tests
- No backup primitives tests (bundle, verifier, journal, maintenance)
- Missing ViewModel tests for ~10 screens (e.g. CashFlowCalendar, BillNegotiation, PriceProtection, NaturalLanguageSearch, CarbonFootprint, LifestyleInflation)
- ~30+ DAOs without direct DAO tests
- No Android instrumented smoke beyond `data/` package

## Next Actions
- [x] Fix Room schema verifier (migration-aware, v117)
- [x] Add `.gitignore` protections
- [x] Create `.github/workflows/ci.yml`
- [ ] Classify ignored tests (delete/rewrite/nightly)
- [ ] Create test fixture skeleton
- [ ] Build `transaction_lifecycle_db_contract` scenario test
