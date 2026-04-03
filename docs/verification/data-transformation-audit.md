# Data Transformation Audit

## Transformation Inventory

### T1: SQL Aggregation → Kotlin Objects
- Source: ExpenseDao SQL queries
- Target: Kotlin data classes
- Transformation: SUM(effectiveAmount) → Double
- Filters: PURCHASE, isNotMine=0, date range
- Verified: ✅

### T2: Repository Pass-Through
- Source: DAO output
- Target: Engine input
- Transformation: None (pass-through)
- Verified: ✅

### T3: Engine Calculations
- Source: Repository data
- Target: Engine output models
- Transformations: Various formulas
- Verified: ✅

### T4: UI State Preparation (ViewModel perspective)
- Source: Engine outputs
- Target: UI state objects
- Transformation: Mapping to presentation fields
- Verified: ⬜ Pending

## Amount Semantics Audit
| Component | Amount Field | Rationale |
|-----------|-------------|-----------|
| InsightsEngine | effectiveAmount | User's actual spend |
| AdvancedAnalyticsEngine | effectiveAmount | User's actual spend |
| CashFlowCalculator | effectiveAmount | User's actual spend |
| DashboardEngine | amount | Historical totals |

## Filter Consistency Audit
| Component | PURCHASE Filter | isNotMine Filter | Half-Open Dates |
|-----------|----------------|------------------|-----------------|
| ExpenseDao.getPurchasesBetween | ✅ | ✅ | ✅ |
| ExpenseDao.getAllPurchases | ✅ | ⬜ | ✅ |
| Repository.filterApplied | ✅ | ✅ | ⬜ |
