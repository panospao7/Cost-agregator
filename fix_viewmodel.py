import re

with open(r'c:\Users\panos\Desktop\cost agregator\ExpenseTracker\app\src\main\java\com\yourname\expensetracker\ui\screens\transactions\TransactionsViewModel.kt', 'r', encoding='utf-8') as f:
    text = f.read()

pattern1 = re.compile(r'val groupedTransactions\s*:.*?initialValue = emptyMap\(\)\s*\)', re.DOTALL)

replacement1 = '''val groupedTransactions: StateFlow<Map<String, List<ExpenseWithCategory>>> = combine(transactions, _sortOrder) { expenseList, order ->
        val sortedList = if (_selectedTab.value == TransactionTab.ALL) {
            // Already sorted and filtered via backend pagination
            expenseList
        } else {
            // Needs sorting in-memory
            when (order) {
                SortOrder.DATE_DESC -> expenseList.sortedByDescending { it.expense.date }
                SortOrder.DATE_ASC -> expenseList.sortedBy { it.expense.date }
                SortOrder.AMOUNT_DESC -> expenseList.sortedByDescending { it.expense.amount }
                SortOrder.AMOUNT_ASC -> expenseList.sortedBy { it.expense.amount }
            }
        }

        if (order == SortOrder.AMOUNT_DESC || order == SortOrder.AMOUNT_ASC) {
            mapOf("Sorted By Amount" to sortedList)
        } else {
            groupTransactionsByDate(sortedList)
        }
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )'''

text = pattern1.sub(replacement1, text)

pattern2 = re.compile(r'private fun groupTransactionsByDate.*?\.groupBy', re.DOTALL)

replacement2 = '''private fun groupTransactionsByDate(
        expenses: List<ExpenseWithCategory>
    ): Map<String, List<ExpenseWithCategory>> {
        if (expenses.isEmpty()) return emptyMap()
        
        return expenses
            .groupBy'''

text = pattern2.sub(replacement2, text)

with open(r'c:\Users\panos\Desktop\cost agregator\ExpenseTracker\app\src\main\java\com\yourname\expensetracker\ui\screens\transactions\TransactionsViewModel.kt', 'w', encoding='utf-8') as f:
    f.write(text)

print('Done')
