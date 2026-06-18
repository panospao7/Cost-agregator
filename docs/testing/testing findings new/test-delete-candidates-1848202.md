# Cost-agregator tests I consider low-value / removable

Target commit: `18482021294eba1d209afa2deb34aea6c107a52f`  
Review type: static GitHub review, not local execution.

## A. Delete now

### 1. `ui/screens/transactions/TransactionsScreenTest.kt`

**Recommendation:** delete.

**Why:** it reads `TransactionsScreen.kt` as a text file and asserts source-code strings. This is brittle and does not test app behavior.

Current pattern:

- reads production source with `File(...)`
- checks that specific implementation strings exist
- will fail on harmless refactors
- will pass even if the UI behavior is broken but the strings remain

Replace with one of:

- a real Compose screen test, or
- a ViewModel/state contract test, or
- a pure helper test if the signed-total/color behavior is extracted.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsScreenTest.kt

---

### 2. `domain/analytics/RecurringIntervalLogicTest.kt`

**Recommendation:** delete.

**Why:** it mostly tests local math inside the test body, not production code. It calculates averages, rounds them, and asserts ranges. This protects almost nothing unless the exact same logic is wired to the production recurring engine, which this test does not do.

Replace with tests against the actual recurring detection engine or recurring lifecycle.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/domain/analytics/RecurringIntervalLogicTest.kt

---

### 3. `scenarios/GoldenScenarioSmokeTest.kt`

**Recommendation:** delete or reduce to one tiny infrastructure smoke test.

**Why:** this is not really a golden scenario. It manually builds `MoneyAggregate` and a fake `PrivacyGate` inside the test. That means it mostly proves the test code itself works.

Problems:

- no real golden JSON files are used
- no real app pipeline is exercised
- privacy gate behavior is faked inline
- money aggregate is manually constructed, not produced by repository/engine/dashboard

Keep only one tiny test if you want to prove `ScenarioSeeder` can insert one row. Otherwise delete after real golden scenarios exist.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/scenarios/GoldenScenarioSmokeTest.kt

Related issue: `GoldenScenarioVerifier` accepts missing golden files as success, and current `test/resources` does not contain scenario golden files.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/testfixtures/golden/GoldenScenarioVerifier.kt  
https://github.com/panospao7/Cost-agregator/tree/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/resources

---

## B. Do not delete immediately, but rewrite or rename

These are not useless, but they are not good “scenario” tests. They mostly test DAO insertion or test-fixture behavior.

### 4. `scenarios/TransactionLifecycleDbContractTest.kt`

**Recommendation:** replace with a real lifecycle scenario; then delete this file or rename it to `ScenarioSeederExpenseInsertTest`.

**Why:** despite the name, it does not test the `TransactionLifecycleCoordinator`. It seeds rows with `ScenarioSeeder.seedState()` and asserts that rows exist.

It even has a duplicate test that proves `seedState` inserts duplicates blindly. That is fixture behavior, not business behavior.

Delete after you have:

- `TransactionLifecycleCoordinatorDbContractTest`
- create/update/delete/dedupe through the real coordinator
- event log assertions
- dashboard/analytics/budget assertions

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/scenarios/TransactionLifecycleDbContractTest.kt

---

### 5. `scenarios/NotificationPipelineScenarioTest.kt`

**Recommendation:** rewrite, not keep as-is.

**Why:** the parser tests are useful, but the “pipeline” part is weak. It parses Greek bank text, then inserts the resulting expense directly with `ScenarioSeeder.seedState()`. That skips the real notification/review/lifecycle path.

This should become:

```text
raw notification
→ notification processing pipeline
→ parser
→ review or auto-accept
→ TransactionLifecycleCoordinator
→ transaction event log
→ dashboard
→ budget
→ analytics
```

If `GreekBankParserTest` already covers the parsing cases, delete the parser-only parts here after the real pipeline scenario exists.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/scenarios/NotificationPipelineScenarioTest.kt

---

### 6. `scenarios/MulticurrencyPartialRateScenarioTest.kt`

**Recommendation:** split and partially delete.

**Delete this specific test inside it:**

```kotlin
dashboard total is sum of raw amounts (no conversion in seedState)
```

**Why:** this protects the wrong behavior. A dashboard should not silently sum EUR + USD + GBP raw values. If kept, it should be renamed as a fixture limitation test, not a business scenario.

Move the pure `MoneyAmount` addition tests to a proper domain test like:

```text
domain/core/money/MoneyAmountTest.kt
```

Replace the scenario with a real one:

```text
EUR expense
USD expense with valid rate
GBP expense with stale/missing rate
→ dashboard MoneyAggregate
→ partial flag
→ warnings
→ source buckets
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/scenarios/MulticurrencyPartialRateScenarioTest.kt

---

### 7. `scenarios/ReceiptLifecycleDbContractTest.kt`

**Recommendation:** rename/move, then replace with a real receipt lifecycle scenario.

**Why:** the file itself says it uses DAOs directly and defers coordinator integration. That makes it a DAO contract test, not a lifecycle scenario.

Do not delete until you have direct DAO tests for:

- `ScannedReceiptDao`
- `ReceiptEventDao`
- `ReceiptExpenseLinkDao`

Then add a real scenario:

```text
receipt OCR/email
→ receipt lifecycle coordinator
→ receipt event log
→ receipt-expense link
→ analytics no-double-count
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/scenarios/ReceiptLifecycleDbContractTest.kt

---

### 8. `scenarios/RecurringNoDoubleCountScenarioTest.kt`

**Recommendation:** rewrite or move to DAO tests.

**Why:** it uses DAOs directly and does not exercise `RecurringLifecycleCoordinator`. The “no double count” assertion is just summing actual expenses and proving planned occurrences are in another table. That is weaker than the real risk.

Replace with:

```text
recurring rule
→ generated occurrence
→ reminder delivery
→ actual expense matched
→ occurrence PAID/MATCHED
→ dashboard counts once
→ forecast includes future planned only
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/scenarios/RecurringNoDoubleCountScenarioTest.kt

---

### 9. `scenarios/BankSyncScenarioTest.kt`

**Recommendation:** rename to DAO contract or delete after stronger bank sync scenario exists.

**Why:** it directly inserts `BankConnection` and `Expense` rows. It does not test bank API sync, auth failure, partial sync, review queue, lifecycle coordinator, or dashboard inclusion rules.

Replace with:

```text
bank token expired
partial sync response
duplicate transaction
low-confidence transaction
review approval
→ lifecycle-created expense
→ dashboard only approved non-duplicates
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/scenarios/BankSyncScenarioTest.kt

---

### 10. `scenarios/EmailReceiptPipelineScenarioTest.kt`

**Recommendation:** rename to DAO contract or replace with real email pipeline.

**Why:** it directly inserts `EmailReceiptSource` and `ScannedReceipt`. It does not test provider parsing, ingestion service, receipt lifecycle, matching, warranty/price protection, or analytics.

Replace with:

```text
Amazon/Apple/Uber email
→ provider parser
→ email ingestion service
→ receipt lifecycle
→ receipt-expense matching
→ analytics no-double-count
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/scenarios/EmailReceiptPipelineScenarioTest.kt

---

### 11. `scenarios/SpeechInputGatewayLifecycleTest.kt`

**Recommendation:** delete the mock-only lifecycle test or replace with a real fake implementation test.

**Why:** one test uses reflection to check that `destroy()` exists on the interface. Another calls methods on a relaxed mock and verifies that the calls happened. That is mostly “verify the mock I just called”.

Keep only if you change it to use a real fake gateway or actual `AndroidSpeechInputGateway` behavior.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/scenarios/SpeechInputGatewayLifecycleTest.kt

---

## C. Keep, but move out of `scenarios/`

These are useful, but misplaced.

### 12. `scenarios/MoneyAggregateBuilderTest.kt`

**Recommendation:** keep, but move to:

```text
domain/core/money/MoneyAggregateBuilderTest.kt
```

**Why:** it is a pure domain test, not a scenario test. It has real value because latest commits touched money/aggregate/currency behavior.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/scenarios/MoneyAggregateBuilderTest.kt

---

### 13. `scenarios/PrivacyGateContractTest.kt`

**Recommendation:** keep, but move to:

```text
domain/privacy/PrivacyGateContractTest.kt
```

**Why:** this is a good domain contract test, but not a multi-pipeline scenario.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/scenarios/PrivacyGateContractTest.kt

---

## D. Older audit files that appear already deleted in latest commit

The old audit listed several dead ignored stress tests. I checked a few against commit `1848202` and they appear gone / 404, for example:

- `consistency/ConcurrencyStateRaceTest.kt`
- `domain/analytics/AdvancedAnalyticsEngineStressTest.kt`

So I would not spend time chasing those unless they still exist locally or under a different source set.

Old audit source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/docsplans/test-suite-quality-audit.md

---

## E. Practical deletion order

I would do this in order:

### Delete immediately

```text
ui/screens/transactions/TransactionsScreenTest.kt
domain/analytics/RecurringIntervalLogicTest.kt
```

### Delete or collapse into one tiny fixture smoke

```text
scenarios/GoldenScenarioSmokeTest.kt
```

### Rewrite first, then delete old shallow version

```text
scenarios/TransactionLifecycleDbContractTest.kt
scenarios/NotificationPipelineScenarioTest.kt
scenarios/MulticurrencyPartialRateScenarioTest.kt
scenarios/ReceiptLifecycleDbContractTest.kt
scenarios/RecurringNoDoubleCountScenarioTest.kt
scenarios/BankSyncScenarioTest.kt
scenarios/EmailReceiptPipelineScenarioTest.kt
scenarios/SpeechInputGatewayLifecycleTest.kt
```

### Move, do not delete

```text
scenarios/MoneyAggregateBuilderTest.kt
scenarios/PrivacyGateContractTest.kt
```

## Main rule

Delete tests that only prove:

- Kotlin/math works
- mocks received calls you just made
- production source contains certain text
- `ScenarioSeeder.seedState()` can insert rows
- DAOs insert blindly when the business contract belongs to a lifecycle coordinator

Keep tests that prove:

- real business entry point used
- real DB-visible result
- real event log
- real dashboard/analytics/budget output
- real currency partial/warning behavior
- no double-counting