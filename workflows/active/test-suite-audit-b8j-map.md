# G-B8J map + decisions (scout S1 2026-10-19; S2 pending; orchestrator decisions under the approved plan + standing approval)

AT = app/src/androidTest/java/com/yourname/expensetracker, T = app/src/test/java/com/yourname/expensetracker. AT files are unmodified vs HEAD (git is the backup).

## Canonical JVM DAO pattern (exemplar T/data/database/dao/ExpenseDaoDateRangeTest.kt)
- `@OptIn(RestrictedExpenseDaoMutation::class)` (:28, needed for insert/insertAtomic/insertAll/update), `@RunWith(RobolectricTestRunner::class)`, `@Config(sdk = [28])` (:29-30).
- setUp :41-45: `ApplicationProvider.getApplicationContext()` + `AppDatabaseTestFactory.create(context)` (T/testfixtures/database/AppDatabaseTestFactory.kt:14-18 → `AppDatabase.inMemoryBuilder(context).build()`: ALL_MIGRATIONS, WAL, allowMainThreadQueries; no SQLCipher/GatedOpenHelper/barrier). tearDown: `if (::db.isInitialized) db.close()`.
- `runTest` (no dispatcher injection, no Dispatchers.setMain). Fixed UTC instants (testfixtures `dateMs(...)`); no wall clock.
- robolectric.properties: sdk=28, HiltTestApplication. No JVM TZ override in Gradle; no JVM test currently exercises SQLite `'localtime'`.

## B8-1 (delete 3 AT duplicates)
- ExchangeRateDaoTest AT 4 → T twin (10) covers all; **port first:** AT `upsertRate_updatesExistingRateForSamePair` (:63) asserts `getRateCount() == 1` after upsert → add that assertion to T `insertOrUpdate replaces existing rate…` (:87-95).
- RecommendationDaoTest AT 4 → T twin (15) covers all (:100, :50, :114, :430-465). No port.
- MerchantKeyBackfillWorkerTest AT 3 → T (6): happy path :89, idempotent :106; AT `doWork_emptyDatabase_returnsSuccess` is subsumed by T :106 (same empty stub). No port.
- ARCHITECTURE.md:134 is a historical changelog naming classes only → no change.

## B8-4 JVM part (move 3 AT → T, same package `data.database.dao`; no T name collisions)
- DedupeKeyUniquenessRegressionTest (3; fixed dates; real unique index + IGNORE → -1): move; add @OptIn.
- ComplexQueryTest (18): move; pin `System.currentTimeMillis()` (:125, :206, :255, :288) and the `Calendar.getInstance()` month test (:137-159) to fixed instants preserving relative semantics. KDoc claims "index usage verification" without any EXPLAIN → correct the KDoc (no new claim).
- ExpenseDaoTest (40): move; `makeExpense` default `System.currentTimeMillis()` (:45-55) and relative-time tests (:107-141) → fixed base instant, same relative offsets. `getAllFlowEmitsUpdates` is a single-shot read → rename to describe what it checks (assertions unchanged). Overlap with T ExpenseDaoDateRangeTest: AT `getExpensesBetweenReturnsCorrectRange` (:155-166) is a loose size-only check, superseded by ExpenseDaoDateRangeTest :66-78 → keep moved (no deletion in this slice) unless exact duplicate.
  - **Risk:** `getWeeklyTotalsForPeriod_*` (:168-212) use SQL `'localtime'` (ExpenseDao.kt:2347) vs Java `ZoneId.systemDefault()`. Decision: move them; consistent while JVM default TZ == process TZ (no override exists). Must not call TimeZone.setDefault. If they fail or are TZ-fragile in validation → debug iteration: return those 2 to the device smoke set (B8-4 device part, G-B8D).
- The half-open boundary test the plan asks for already exists (B3-2: ExpenseDaoDateRangeTest :66-103) → no new test needed. `DaoDeviceSmokeTest` (new AT file) belongs to the device part → G-B8D (no androidTest compile profile exists without a device).

## References
None of the 6 classes in release_block_denylist.yml, known-hanging-tests.json, Gradle, CI, scripts or guards.

## B8-5..B8-7 (scout S2): 16 pure moves, no T collisions, 159 @Test
Counts (AT, must equal JVM after move):
- B8-5: Budget 18, SavingsGoal 16, RecurringExpense 7, Category 6, MerchantCategory 4.
- B8-6a: GroupMember 16, ExpenseGroup 4, UserCorrection 16, PendingReview 8.
- B8-6b: MerchantNormalization 15, MerchantLocation 14, ScannedReceipt 7, Warranty 4.
- B8-7: AiArtifact 14, AiChatMessage 5, AiChatSession 5.
No SQL date features. PRAGMA index_list and sqlite_master via openHelper (GroupMember, Category) are supported. FK cascades (ExpenseGroup, AiChatMessage) are on in Room. SQLiteConstraintException catches are expected to behave the same. No config/Gradle/CI/guard references; FreshInstall*ParityTest are independent.
- @OptIn(RestrictedExpenseDaoMutation::class) needed: ScannedReceiptDaoTest (:125, :146) and GroupMemberDaoTest (:366).
- Wall clock: fixture defaults only, plus AiArtifactDaoTest :49-50/:128/:158/:183 and MerchantLocationDaoTest :211/:252 (relative). Pin to a fixed base instant with the same offsets.
- **Risk (medium):** concurrency tests CategoryDaoTest :120-148 (24 × Dispatchers.IO, withTimeout 30s) and SavingsGoalDaoTest :195/:275. If they hang or are flaky under Robolectric, open a debug iteration. Never weaken them; adding to known-hanging-tests.json is a config change that needs user approval. Fallback: keep that test on device (G-B8D).

## Writers (sequential)
- W1: B8-1 (port count assert, delete 3 AT files) + DedupeKeyUniquenessRegressionTest + ComplexQueryTest.
- W2: ExpenseDaoTest.
- W3: Budget, SavingsGoal, MerchantCategory, ExpenseGroup.
- W4: RecurringExpense, Category, UserCorrection, PendingReview.
- W5: GroupMember (OptIn), ScannedReceipt (OptIn), MerchantNormalization, MerchantLocation.
- W6: Warranty, AiArtifact, AiChatMessage, AiChatSession.
## Validation
targeted-unit-test over `com.yourname.expensetracker.data.database.dao.*` + `com.yourname.expensetracker.data.location.MerchantKeyBackfillWorkerTest` (per-class JUnit counts: moved classes must equal AT counts). Review: reviewer-strict + room-migration-guardian (B8-4/B8-5 T3).
