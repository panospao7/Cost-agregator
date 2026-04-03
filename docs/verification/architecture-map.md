# Analytics Architecture Map

This document describes the analytics data flow from ingestion to presentation. It captures how data moves through DAO, Repository, Engines, ViewModels, and Screens, including key semantics and filter rules used to produce analytics metrics.


- ExpenseDao.kt - All query methods documented
- Filter rules applied at each method
- Amount semantics (effectiveAmount vs amount)
- Date boundary semantics

- ExpenseRepository.kt - All methods documented
- Transformations applied
- Filter enforcement

- All 10 engines documented
- Input/output contracts
- Key formulas

- All analytics ViewModels documented
- State mapping
- UI state transformation

- All analytics screens documented
- Data display patterns

The standard path for a metric from database to screen:

```
ExpenseDao.kt  --->  ExpenseRepository.kt  --->  Engines (10 total)
        \
         ->  ViewModels  --->  Screens
```

Canonical path (example):
- DAO query returns a raw dataset
- Repository applies domain-specific aggregations and filters
- Engines compute derived metrics (monthly totals, pace, category analytics, etc.)
- ViewModels map engine outputs to UI-friendly state
- Screens render the final metrics

## Filter Consistency Rules
- transactionType = PURCHASE for spend analytics
- isNotMine = false for user's own expenses
- effectiveAmount for user's share
- Half-open date ranges [start, end)
