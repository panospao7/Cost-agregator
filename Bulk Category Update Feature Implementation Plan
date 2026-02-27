# Bulk Category Update Feature Implementation Plan

## Overview
Currently, users can update the category of an individual expense. This feature will prompt them to optionally apply this new category to *all* past transactions from the same merchant, maintaining consistency and saving time.

## Proposed Changes

### 1. Database Layer ([com/yourname/expensetracker/data/database/dao/ExpenseDao.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt))
#### [MODIFY] ExpenseDao.kt
- Add a new suspend function: `suspend fun updateCategoryForMerchant(merchant: String, categoryId: Long)` to efficiently update multiple rows in a single query.

### 2. Repository Layer ([com/yourname/expensetracker/data/repository/ExpenseRepository.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt))
#### [MODIFY] ExpenseRepository.kt
- Add `suspend fun updateExpenseCategoryBulk(merchant: String, newCategoryId: Long)` to wrap the DAO call.
- Ensure the mutex and pattern learning side effects (like adding strictly one `UserCorrection` or multiple) are correctly managed.

### 3. UI/State Layer ([com/yourname/expensetracker/ui/screens/transactions/TransactionsViewModel.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsViewModel.kt))
#### [MODIFY] TransactionsViewModel.kt
- Modify [updateCategory](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt#75-77) to accept a boolean `applyToAll: Boolean = false`.
- If `applyToAll` is true, call the new `ExpenseRepository.updateExpenseCategoryBulk` instead of [updateExpenseCategory](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt#65-89).

### 4. UI/View Layer ([com/yourname/expensetracker/ui/screens/transactions/TransactionsScreen.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsScreen.kt))
#### [MODIFY] TransactionsScreen.kt
- **[CategoryPickerDialog](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsScreen.kt#1007-1105)**: 
  - Change `onCategorySelected` callback from [(Long) -> Unit](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/domain/analytics/InsightsEngine.kt#623-624) to [(Long, Boolean) -> Unit](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/domain/analytics/InsightsEngine.kt#623-624).
  - Add a [Row](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt#537-566) at the bottom of the dialog containing a `Checkbox` and `Text` ("Apply to all past transactions for this merchant").
  - Maintain a local `Boolean` state for the checkbox.
  - When a category is tapped, invoke the callback passing the category ID and the checkbox state.
- **Main [TransactionsScreen](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsScreen.kt#67-529) Dialog Logic**:
  - Update the invocation of [CategoryPickerDialog](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsScreen.kt#1007-1105) to handle the new `applyToAll` parameter.
  - Call `viewModel.updateCategory(it, categoryId, applyToAll)` accordingly.

## Verification Plan

### Automated Tests
- No new complex UI tests needed if tested manually.
- Can optionally add a quick unit test in `ExpenseRepositoryTest` to verify the bulk update query behaves correctly.

### Manual Verification
1. Open the Transactions screen.
2. Select a transaction with merchant "Starbucks" (ensure there are multiple).
3. Tap to edit the category.
4. Check the "Apply to all" box and select a new category (e.g., "Food & Drink").
5. Verify *all* historical "Starbucks" transactions immediately change to "Food & Drink".
6. Verify a single transaction update (without checking the box) only updates the single transaction.
