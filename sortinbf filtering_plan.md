# Transaction Page Enhancements Plan

## Goal Description
The "All" transactions tab currently loads transactions in pages of 50. Filtering and searching are performed *in-memory* on the ViewModel side. This means if a user searches for an old transaction, it will not appear unless the user has scrolled down enough to load that page into memory. For users with 500-1000+ transactions over multiple years, this is extremely inefficient and limits the usability of the search feature. 

This implementation plan moves filtering, searching, and new sorting capabilities directly to the database layer via SQL queries, allowing the app to efficiently query large datasets and paginate the results accurately. 

## Proposed Changes

### Database Layer
We will implement dynamic SQL querying using Room's `@RawQuery` to handle combinations of sorting, searching, and filtering.

#### [MODIFY] ExpenseDao.kt
- Add a `@RawQuery` method to support dynamic search, filter, and sorting:
  ```kotlin
  @RawQuery
  suspend fun getExpensesDynamic(query: SupportSQLiteQuery): List<ExpenseWithCategory>
  ```
- Alternatively, add an index on `merchant` in the DB schema to speed up LIKE queries if not already present.

#### [MODIFY] ExpenseRepository.kt
- Add a new function `getExpensesPagedDynamic(...)` that constructs the `SimpleSQLiteQuery`:
  - Builds the `WHERE` clause based on:
    - `searchQuery`: `merchant LIKE '%query%' OR categoryName LIKE '%query%'` (requires a `JOIN` with categories).
    - `ownershipFilter`: Appends `isNotMine = 1`, `isShared = 1`, etc.
    - Active `TransactionFilter` properties (date range, type, categoryId).
  - Appends `ORDER BY` based on the selected sort order (e.g., `date DESC`, `date ASC`, `amount DESC`, `amount ASC`).
  - Appends `LIMIT` and `OFFSET` for pagination.
- Add `SortOrder` enum class in the domain/model layer.

### ViewModel Layer
#### [MODIFY] TransactionsViewModel.kt
- Introduce a new `StateFlow<SortOrder>` for the active sorting method.
- Refactor the `combine` flow: Instead of loading base expenses and filtering in-memory, the ViewModel will trigger the repository's new `getExpensesPagedDynamic()` method passing the current query, filters, and sort order.
- On any change to `searchQuery`, `ownershipFilter`, [filter](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsViewModel.kt#475-490), or `sortOrder`, the `_currentPage` should be reset to 0, and `_pagedExpenses` should be cleared before loading the first page using the new DB query.
- The [loadMore()](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsViewModel.kt#245-278) function will fetch the next offset using the exact same dynamic query configuration.

### UI Layer
#### [MODIFY] TransactionsScreen.kt
- Add a "Sort" icon button next to the search and filter icons in the TopAppBar.
- Create a `SortBottomSheet` or dropdown menu to allow users to select sorting options:
  - Newest First (Date Descending)
  - Oldest First (Date Ascending)
  - Amount High to Low (Amount Descending)
  - Amount Low to High (Amount Ascending)
- Hook up the selected sort order to `viewModel.setSortOrder(order)`.

## Verification Plan

### Automated Tests
- **Unit Tests for Repository**: Write unit tests to verify that [ExpenseRepository](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt#23-236) correctly constructs the `SimpleSQLiteQuery` strings and binds arguments for different combinations of queries, filters, and sorting orders. Run via:
  ```bash
  ./gradlew testDebugUnitTest --tests "com.yourname.expensetracker.data.repository.ExpenseRepositoryTest"
  ```

### Manual Verification
1. Open the app and navigate to the **Activity (Transactions)** tab.
2. Ensure you have significantly more than 50 transactions. Select the **All** tab.
3. Test **Searching**:
   - Type a merchant name for a transaction that is *not* in the first 50 latest transactions.
   - Verify it appears instantly and correctly without needing to scroll down to load more.
4. Test **Sorting**:
   - Tap the new Sort icon and select "Amount High to Low".
   - Verify the transactions are sorted by amount correctly.
   - Scroll down to trigger load more, and verify the next page continues the correct sorting.
5. Test **Filtering**:
   - Change the Ownership chips (e.g., "Not Mine") while a search query is active.
   - Verify the combination of Search + Sort + Filter works accurately.
