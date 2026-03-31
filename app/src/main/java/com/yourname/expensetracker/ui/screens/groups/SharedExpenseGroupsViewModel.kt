package com.yourname.expensetracker.ui.screens.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.dao.ExpenseGroupDao
import com.yourname.expensetracker.data.database.dao.GroupExpenseDao
import com.yourname.expensetracker.data.database.dao.GroupMemberDao
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ManualExpenseRepository
import com.yourname.expensetracker.domain.groups.GroupCreationResult
import com.yourname.expensetracker.domain.groups.GroupExpenseCreationResult
import com.yourname.expensetracker.domain.groups.GroupTransactionCoordinator
import com.yourname.expensetracker.domain.logic.SplitCalculator
import com.yourname.expensetracker.domain.model.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for shared expense groups screen.
 */
data class GroupsUiState(
    val groups: List<GroupWithDetails> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedGroup: GroupWithDetails? = null,
    val creatingGroup: Boolean = false,
    val addingMember: Boolean = false,
    val addingExpense: Boolean = false
)

data class GroupWithDetails(
    val group: ExpenseGroup,
    val members: List<GroupMember>,
    val expenses: List<GroupExpenseWithDetails>,
    val totalSpent: Double,
    val memberBalances: Map<Long, Double> // memberId -> balance (positive = owed, negative = owes)
)

data class GroupExpenseWithDetails(
    val expense: GroupExpense,
    val paidByName: String,
    val splitAmounts: Map<Long, Double> // memberId -> amount they owe for this expense
)

@HiltViewModel
class SharedExpenseGroupsViewModel @Inject constructor(
    private val groupDao: ExpenseGroupDao,
    private val memberDao: GroupMemberDao,
    private val expenseDao: GroupExpenseDao,
    private val coordinator: GroupTransactionCoordinator,
    private val manualExpenseRepository: ManualExpenseRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(GroupsUiState())
    val uiState: StateFlow<GroupsUiState> = _uiState.asStateFlow()
    
    init {
        loadGroups()
    }
    
    private fun loadGroups() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            try {
                val allGroups = groupDao.getActiveFlow().first()
                
                val groupsWithDetails: List<GroupWithDetails> = coroutineScope {
                    allGroups.map { group: ExpenseGroup ->
                        async {
                            val groupId = group.id
                            val members = memberDao.getAllForGroupFlow(groupId).first()
                            val expenses = expenseDao.getExpensesForGroup(groupId).first()
                            
                            val expensesWithDetails = expenses.map { expense ->
                                val paidByMember = members.find { it.id == expense.paidById }
                                GroupExpenseWithDetails(
                                    expense = expense,
                                    paidByName = paidByMember?.name ?: "Unknown",
                                    splitAmounts = calculateSplitAmounts(expense, members)
                                )
                            }
                            
                            val totalSpent = expenses.sumOf { it.totalAmount }
                            val memberBalances = calculateBalances(expenses, members)
                            
                            GroupWithDetails(
                                group = group,
                                members = members,
                                expenses = expensesWithDetails,
                                totalSpent = totalSpent,
                                memberBalances = memberBalances
                            )
                        }
                    }.awaitAll()
                }
                
                _uiState.value = GroupsUiState(
                    groups = groupsWithDetails,
                    isLoading = false,
                    error = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load groups: ${e.message}"
                )
            }
        }
    }
    
    private fun calculateSplitAmounts(expense: GroupExpense, members: List<GroupMember>): Map<Long, Double> {
        return SplitCalculator.calculateSplitAmounts(expense, members)
    }
    
    private fun calculateBalances(
        expenses: List<GroupExpense>,
        members: List<GroupMember>
    ): Map<Long, Double> {
        return SplitCalculator.calculateBalances(expenses, members)
    }
    
    /**
     * Create a new group.
     */
    /**
     * Create a new group with the current user as the first member.
     * Uses atomic transaction via GroupTransactionCoordinator.
     */
    fun createGroup(name: String, description: String?, currency: String) {
        viewModelScope.launch {
            try {
                val currentUser = GroupMember(
                    groupId = 0, // Will be set by coordinator
                    name = "You",
                    isCurrentUser = true
                )
                
                when (val result = coordinator.createGroupWithMembers(
                    name = name,
                    description = description,
                    currency = currency,
                    members = listOf(currentUser)
                )) {
                    is GroupCreationResult.Success -> {
                        loadGroups()
                        _uiState.value = _uiState.value.copy(creatingGroup = false)
                    }
                    is GroupCreationResult.Error -> {
                        _uiState.value = _uiState.value.copy(error = result.message)
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to create group: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Add member to group using GroupTransactionCoordinator.
     */
    fun addMember(groupId: Long, name: String, email: String?) {
        viewModelScope.launch {
            try {
                val memberId = coordinator.addMemberToGroup(
                    groupId = groupId,
                    name = name,
                    email = email,
                    isCurrentUser = false
                )
                
                if (memberId != null && memberId > 0) {
                    loadGroups()
                    _uiState.value = _uiState.value.copy(addingMember = false)
                } else {
                    _uiState.value = _uiState.value.copy(
                        error = "Failed to add member: Invalid group or member"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to add member: ${e.message}"
                    
                )
            }
        }
    }
    
    /**
     * Add expense to group by creating a system expense first, then linking to group.
     * This ensures the expense appears in transaction history and maintains referential integrity.
     */
    fun addExpense(
        groupId: Long,
        description: String,
        amount: Double,
        paidById: Long,
        splitType: SplitType
    ) {
        viewModelScope.launch {
            try {
                // Step 1: Create system expense first
                val group = groupDao.getById(groupId)
                val currency = group?.defaultCurrency ?: "EUR"
                val payer = memberDao.getById(paidById)
                
                val expenseResult = manualExpenseRepository.addManualExpense(
                    merchant = description,
                    amount = amount,
                    currency = currency,
                    categoryId = null, // Will auto-categorize
                    transactionType = TransactionType.PURCHASE,
                    notes = "Group expense via ${payer?.name ?: "Unknown"}"
                )
                
                // Step 2: Link to group if expense creation succeeded
                when (expenseResult) {
                    is Result.Success -> {
                        val systemExpenseId = expenseResult.data
                        when (val linkResult = coordinator.addExpenseWithLink(
                            groupId = groupId,
                            systemExpenseId = systemExpenseId,
                            description = description,
                            amount = amount,
                            paidById = paidById,
                            splitType = splitType
                        )) {
                            is GroupExpenseCreationResult.Success -> {
                                loadGroups()
                                _uiState.value = _uiState.value.copy(addingExpense = false)
                            }
                            is GroupExpenseCreationResult.Error -> {
                                _uiState.value = _uiState.value.copy(error = linkResult.message)
                            }
                        }
                    }
                    is Result.Error -> {
                        _uiState.value = _uiState.value.copy(
                            error = "Failed to create expense: ${expenseResult.message}"
                        )
                    }
                    is Result.Duplicate -> {
                        _uiState.value = _uiState.value.copy(
                            error = "Duplicate expense detected"
                        )
                    }
                    else -> {
                        // Loading or other states - ignore or handle as needed
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to add expense: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Delete (archive) a group using GroupTransactionCoordinator.
     */
    fun deleteGroup(groupId: Long) {
        viewModelScope.launch {
            try {
                val success = coordinator.deleteGroup(groupId)
                if (success) {
                    loadGroups()
                    selectGroup(null)
                } else {
                    _uiState.value = _uiState.value.copy(
                        error = "Failed to delete group"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to delete group: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Select a group for detail view.
     */
    fun selectGroup(group: GroupWithDetails?) {
        _uiState.value = _uiState.value.copy(selectedGroup = group)
    }
    
    /**
     * Toggle create group dialog.
     */
    fun toggleCreateGroup(show: Boolean) {
        _uiState.value = _uiState.value.copy(creatingGroup = show)
    }
    
    /**
     * Toggle add member dialog.
     */
    fun toggleAddMember(show: Boolean) {
        _uiState.value = _uiState.value.copy(addingMember = show)
    }
    
    /**
     * Toggle add expense dialog.
     */
    fun toggleAddExpense(show: Boolean) {
        _uiState.value = _uiState.value.copy(addingExpense = show)
    }
    
    fun refresh() {
        loadGroups()
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}