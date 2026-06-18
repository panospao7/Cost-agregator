Yes — not many areas are **totally** untested, but several are **effectively untested or only shallowly covered**.

## Clearly under-tested / probably untested

1. **Investment Tracking**
   - Segment exists with `InvestmentTracker.kt`, investment entities/DAO/UI, but I did not see meaningful tests in the batch plan.

2. **Bill negotiation**
   - `SmartBillNegotiationEngine.kt` and `BillNegotiationScreen` are in architecture, but tests mainly cover warranty, price protection, subscription — not negotiation itself.

3. **DI / app wiring**
   - Many modules exist: `AiModule`, `DatabaseModule`, `ServiceModule`, `NetworkModule`, `ParserModule`, etc.
   - I saw no real Hilt graph smoke test proving the app dependency graph starts.

4. **Startup / background runtime**
   - Some workers/receivers have tests, but `AppStartupDelegate`, `AppStartupCoordinator`, startup restore safety, service restart orchestration, and worker rescheduling are not strongly covered.

5. **Privacy / configuration / performance helpers**
   - `domain/privacy`, `data/privacy`, `domain/config`, `domain/performance/ImageCache.kt` appear weakly covered or not covered.

6. **Some UI screens**
   - Many ViewModels have tests, but full Compose screen behavior is sparse.
   - Especially weak: bank connections, investment portfolio, negotiation, reminders, map, receipt scan/review/transactions after deleting ignored stress tests.

## Areas with tests, but weak coverage

1. **Transaction lifecycle**
   - There is `TransactionLifecycleCoordinatorTest`, but it is mostly mock-based.
   - Missing: seeded DB lifecycle test for create/update/delete/dedupe/event log/side effects.

2. **Receipt lifecycle**
   - There is `ReceiptLifecycleCoordinatorTest`, but also mock-heavy.
   - Missing: realistic DB + asset/hash + duplicate + receipt-expense link scenario.

3. **Recurring lifecycle**
   - There is `RecurringLifecycleCoordinatorTest`, but no strong full pipeline:
   - recurring rule → planned occurrence → real expense match → no double-count dashboard.

4. **Bank integration**
   - `BankApiIntegrationTest` and `BankConnectionDaoTest` exist, but not a real ingestion pipeline to expense lifecycle/dashboard.

5. **Database migration/schema**
   - Many DAO/migration tests exist, but audit says migration tests may silently skip and schema verification is stale.

6. **Concurrency/stress**
   - Many stress tests are `@Ignore`, so they are effectively untested in CI.

## Biggest strategic gap

The main missing layer is not “more unit tests”; it is **seeded multi-pipeline scenario tests**:

- notification → review → transaction lifecycle → dashboard
- receipt → OCR/parser → matching → analytics
- recurring → planned → actual → dashboard
- multi-currency → budget → forecast
- restore/startup mode → workers paused/resumed safely

Sources: segment map, scout report, batch plan, quality audit.