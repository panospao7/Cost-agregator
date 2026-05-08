Yes — most advanced-engine issues are documented, but the tracker wording/status likely needs cleanup so they are easier to act on.

Advanced-engine issues are mainly in these buckets:

```text
1. Groups / shared expenses
2. Tax / business / mileage
3. Investment
4. Legacy Natural Language / Smart Search
5. Analytics advanced/personality/forecast
6. Warranty/subscription advanced lifecycle
7. CI guards / migration integrity
```

## Most important documented advanced issues

### Groups

Documented issues include:

```text
persistent settlement missing
GroupLifecycleCoordinator incomplete/removed
hard delete/member delete lifecycle gaps
group lifecycle/audit events missing
multi-currency group settlements deferred
single-currency policy partly enforced
linked expense ownership partially fixed
```

Status: **partial / beta**

---

### Tax / business

Documented issues include:

```text
TaxSettingsRepository exists but TaxEstimator not fully driven by it
business reports need MoneyAggregate end-to-end verification
CSV formula safety needs real tests
business/tax updates need TransactionLifecycleCoordinator path
official tax provider deferred
fiscal-year assumptions need tests
```

Status: **partial / estimate-only**

---

### Investment

Documented issues include:

```text
InvestmentTransaction table exists but ledger is not wired
BUY/SELL/DIVIDEND flows missing
realized gains/cost basis deferred
raw PortfolioSummary consumers may remain
portfolio allocation mixed-currency safety needs verification
portfolio history carry-forward mostly addressed
```

Status: **partial / beta**

---

### Legacy NL / Smart Search

Documented issues include:

```text
raw min/max amount filtering still possible
conversion failure fallback risk
category/location parsed but not reliably applied
legacy path overlaps Assistant
should be fixed or contained as beta
```

Status: **partial / beta**

---

### Advanced analytics / forecast

Documented issues include:

```text
AnalyticsInputAssembler exists but migration incomplete
shared expense state not fully preserved
stale-rate count incomplete
spending personality needs normalized input
dashboard/analytics/assistant consistency tests needed
forecast planned/recurring quality still partial
```

Status: **partial**

---

### Warranty / subscription advanced lifecycle

Documented issues include:

```text
WarrantyLifecycleEvent table exists but event writes may not be wired everywhere
subscription validateAndCreate atomicity still needs final fix
subscription baseline history path needs rollback test
candidate recurrence/calendar behavior needs test
```

Status: **partial**

---

### Guards / migrations

Documented issues include:

```text
guard scripts exist but tests may not execute them for real
migration tests check registration, not table/data integrity
raw money/time guards need allowlist + seeded failure tests
```

Status: **partial**

---

## What I would do

Create one tracker section:

```text
ADVANCED ENGINE BACKLOG
```

with this format:

```text
ID
Area
Issue
Current status
Feature exposure
Release decision
Required next action
```

Example:

```text
ADV-GROUP-01
Persistent settlements missing
Status: PARTIAL
Feature exposure: visible
Decision: Beta/disable Settle or implement settlement table
Next action: create GroupSettlementEntity + recordSettlement()
```

## Short answer

Yes, they are documented — but not cleanly enough.

They need one reconciliation pass so each advanced issue is marked as either:

```text
FIX NOW
CONTAIN AS BETA
DEFERRED DESIGN
DONE
```

That will prevent you from chasing all advanced features before the core is stable.