# Expanding Deduplication Logic to Review Zone

## Evaluation
Currently, the system successfully prevents duplicate *expenses* by checking incoming bank statement transactions against the [Expense](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugViewModel.kt#87-92) table (via [CrossSourceDeduplication.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/domain/intelligence/CrossSourceDeduplication.kt)). However, it **does not** check against the [PendingReview](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/database/dao/PendingReviewDao.kt#8-58) table. 

This creates a significant UX issue:
1. A user receives a push notification for a coffee purchase -> A [PendingReview](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/database/dao/PendingReviewDao.kt#8-58) is created.
2. The user later scans a bank statement containing that coffee purchase.
3. The system checks the [Expense](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugViewModel.kt#87-92) table, finds no duplicate (because the user hasn't approved the notification review yet), and creates a **second** [PendingReview](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/database/dao/PendingReviewDao.kt#8-58).
4. The user is now forced to manually review two identical entries in the Review screen.

Expanding deduplication to the Review Zone is **highly recommended** to keep the review queue clean and prevent duplicate work for the user. It is very much aligned with the app's goal of intelligent automation.

## Key Considerations (Pros & Cons)
*   **Pro**: Significantly reduces UI clutter on the Review Screen, especially after importing overlapping bank statement screenshots.
*   **Pro**: Prevents the user from accidentally approving two instances of the same expense (though the DB atomic insert would catch it at the end, rejecting it in the UI is highly frustrating).
*   **Con/Complexity**: Choosing the "winner". When a duplicate is detected, we must decide which data source is better. 
    *   *Push Notifications* are digital and 100% accurate regarding amounts/dates.
    *   *Bank Statements (OCR)* might have slight character errors or lower confidence scores. We should generally prioritize notifications.

## Suggested Implementation Plan

If you approve, here is how we can integrate this smoothly:

### 1. Update [PendingReviewDao.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/database/dao/PendingReviewDao.kt)
Add a fast query to fetch recent pending reviews based on date bounds, similar to how we fetch recent expenses.
```kotlin
@Query("SELECT * FROM pending_reviews WHERE status = 'PENDING' AND suggestedDate BETWEEN :startDate AND :endDate")
fun getPendingReviewsInTimeframe(startDate: Long, endDate: Long): Flow<List<PendingReview>>
```

### 2. Update [CrossSourceDeduplication.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/domain/intelligence/CrossSourceDeduplication.kt)
Enhance `isCrossSourceDuplicate` to accept a list of [PendingReview](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/database/dao/PendingReviewDao.kt#8-58)s alongside [Expense](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugViewModel.kt#87-92)s so the core matching logic (similarity algorithms, date windows) can be reused without duplicating code.

### 3. Update [ReceiptRepository.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt) (processStatement)
When parsing a bank statement:
1. Fetch recent [Expense](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugViewModel.kt#87-92)s **AND** recent [PendingReview](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/database/dao/PendingReviewDao.kt#8-58)s.
2. Run the deduplication check against both collections.
3. If the parsed statement transaction matches an existing [PendingReview](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/database/dao/PendingReviewDao.kt#8-58):
   - If the existing review is from a `notification`, **discard** the statement transaction (trust the notification).
   - If the existing review is from another `statement.import` (e.g., overlapping screenshots), **discard** the duplicate.
   - Alternatively, we could *update* the existing [PendingReview](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/database/dao/PendingReviewDao.kt#8-58) if we think the new data is better, but discarding the duplicate is the safest initial approach.

---
**Do you want to proceed with this implementation plan?**
