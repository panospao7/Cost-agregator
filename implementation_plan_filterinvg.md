# Transaction Page UI & Filtering Enhancement Plan

## Goal Description
The Transactions (Activity) page currently lacks an accessible way to apply complex filters (like Category, Date Range, Transaction Type, and Ownership). Additionally, the UI needs aesthetic improvements, specifically addressing the spacing of the top TabRow ("navbar") and the styling of the grouped date and summary headers.

This plan details how we will introduce a unified, aesthetically pleasing Filter Bottom Sheet, refine the TabRow, and modernize the date/summary headers to make the page look premium and highly functional.

## Proposed Changes

### 1. Comprehensive Filtering UI
We need to expose the filtering capabilities that our backend [TransactionFilter](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionFilter.kt#3-9) already supports.

#### [MODIFY] TransactionsScreen.kt & [NEW] TransactionFilterSheet.kt
- **Add Filter Icon:** Add a `FilterList` icon to the `TopAppBar` actions, right next to the Sort and Search icons.
- **Filter Bottom Sheet:** Create a new composable `TransactionFilterSheet` (using `ModalBottomSheet`). This sheet will aggregate all filtering options:
  - **Category:** A section with selectable chips (e.g., `FlowRow` or horizontally scrollable row) for all available categories.
  - **Transaction Type:** Chips for "Purchases", "Transfers", etc.
  - **Ownership:** Expose the [OwnershipFilter](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt#31-34) (Mine, Shared, Not Mine) as selectable chips.
  - **Date Range:** Options for specific months, years, or custom ranges (since the TabRow only covers relative times like "Month", "Quarter").
- **State Management:** When the user hits "Apply" in the bottom sheet, it will construct a [TransactionFilter](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionFilter.kt#3-9) object and call `viewModel.applyFilter(filter)` and `viewModel.setOwnershipFilter(...)`.
- **Active Filter Banner Enhancements:** The screen already has an `activeFilter` banner logic. We will ensure it looks modern (e.g., glassmorphism or a subtle primary-colored surface) and displays clearly what filters are active, with an easy "Clear All" button.

### 2. TabRow ("Navbar") Spacing Improvements
The user noted weird spacing on the TabRow. 

#### [MODIFY] TransactionsScreen.kt
- Adjust the `ScrollableTabRow`:
  - Change `edgePadding` to `0.dp` or completely transition to a fixed `TabRow` if the tabs fit on screen, or use a custom scrollable implementation with better margins.
  - Remove any unnecessary dividers that clutter the UI.
  - Ensure the tab indicator (`TabRowDefaults.SecondaryIndicator`) is styled to match the app's modern aesthetics (e.g., rounded corners, matching the primary indigo color perfectly).

### 3. Date & Summary Header Aesthetics
The current grouping header ("Friday, February 27, 2026", "3 transactions", "€15.44") looks plain.

#### [MODIFY] TransactionsScreen.kt (or a new component `DateHeaderItem.kt`)
- **Modernize the Header:** 
  - Instead of raw text on the background, wrap the header in a `Surface` with a very subtle background color (e.g., `MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)`).
  - Use a sleek font weight (`FontWeight.SemiBold`) for the date.
  - Group the "3 transactions" and the total amount strategically. For example, align the Date on the left, and the Total Amount brightly colored on the right, with the transaction count nicely muted as a subtitle.
  - Add smooth padding to separate different date groups clearly.

### 4. ViewModel Integration adjustments
Ensure that when a custom filter is applied (e.g., selecting "Year 2025" from the filter sheet), it naturally overrides or works cleanly with the currently selected Tab (which might be "Month"). 
- Usually, when a custom manual filter is applied, we might want to automatically switch the Tab to "All" so the user is searching their entire history, not just the current month's history.

## Verification Plan
### Manual Verification
1. **Filtering:** Open the app, click the new Filter icon. Select "Groceries" and "Not Mine". Apply. Verify the list updates and the Active Filter banner appears.
2. **TabRow:** Check the top tabs visually. Scroll them left and right. Verify the spacing is tight and looks intentional, not "weird".
3. **Headers:** Scroll down the list. Visually inspect the new Date headers. Verify they look distinct, premium, and clearly delineate different days or sorting groups.
