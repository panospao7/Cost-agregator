package com.yourname.expensetracker.ui.screens.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.GroupsRepository
import com.yourname.expensetracker.data.repository.ManualExpenseRepository
import com.yourname.expensetracker.domain.logic.CustomSplitMode
import com.yourname.expensetracker.domain.logic.CustomSplitParseResult
import com.yourname.expensetracker.domain.logic.CustomSplitParser
import com.yourname.expensetracker.domain.groups.GroupCreationResult
import com.yourname.expensetracker.domain.groups.GroupExpenseCreationResult
import com.yourname.expensetracker.domain.groups.usecase.AddGroupExpenseUseCase
import com.yourname.expensetracker.domain.groups.usecase.DeleteGroupUseCase
import com.yourname.expensetracker.domain.logic.SplitCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
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
    private val groupsRepository: GroupsRepository,
    private val addGroupExpenseUseCase: AddGroupExpenseUseCase,
    private val deleteGroupUseCase: DeleteGroupUseCase,
    private val manualExpenseRepository: ManualExpenseRepository,
    private val expenseRepository: ExpenseRepository
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
                val groupsWithDetails: List<GroupWithDetails> = groupsRepository
                    .getActiveGroupsWithDetails()
                    .map { aggregate ->
                        val expensesWithDetails = aggregate.expenses.map { expense ->
                            val paidByMember = aggregate.members.find { it.id == expense.paidById }
                            GroupExpenseWithDetails(
                                expense = expense,
                                paidByName = paidByMember?.name ?: "Unknown",
                                splitAmounts = calculateSplitAmounts(expense, aggregate.members)
                            )
                        }

                        GroupWithDetails(
                            group = aggregate.group,
                            members = aggregate.members,
                            expenses = expensesWithDetails,
                            totalSpent = aggregate.expenses.sumOf { it.totalAmount },
                            memberBalances = calculateBalances(aggregate.expenses, aggregate.members)
                        )
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
     */
    fun createGroup(name: String, description: String?, currency: String) {
        viewModelScope.launch {
            try {
                when (val result = groupsRepository.createGroup(
                    name = name,
                    description = description,
                    currency = currency,
                    currentUserName = "You"
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
     * Add member to group through repository abstraction.
     */
    fun addMember(groupId: Long, name: String, email: String?) {
        viewModelScope.launch {
            try {
                val memberId = groupsRepository.addMember(
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
     * Add expense to group atomically.
     *
     * B.4 Batch 2 (Risk 1): Replaced the two-step flow (create system expense →
     * link to group) with a single atomic coordinator call via
     * [AddGroupExpenseUseCase.invokeAtomic]. This eliminates the orphan window
     * where a system expense could exist without a group link.
     *
     * If the atomic call fails, no system expense is created and no cleanup
     * is necessary.
     */
    fun addExpense(
        groupId: Long,
        description: String,
        amount: Double,
        paidById: Long,
        splitType: SplitType,
        customSplits: Map<Long, Double>? = null
    ) {
        viewModelScope.launch {
            try {
                val group = groupsRepository.getGroupById(groupId)
                val currency = group?.defaultCurrency ?: "EUR"
                val payer = groupsRepository.getMemberById(paidById)
                val groupMembers = resolveGroupMembers(groupId)

                val customSplitPayload = serializeAndValidateCustomSplits(
                    splitType = splitType,
                    customSplits = customSplits,
                    totalAmount = amount,
                    members = groupMembers
                )
                val validatedCustomSplits = when (customSplitPayload) {
                    is CustomSplitPayload.Invalid -> {
                        _uiState.value = _uiState.value.copy(error = customSplitPayload.reason)
                        return@launch
                    }

                    is CustomSplitPayload.Valid -> customSplitPayload.serialized
                }

                // Single atomic call — system expense + group link in one transaction
                when (val result = addGroupExpenseUseCase.invokeAtomic(
                    groupId = groupId,
                    description = description,
                    amount = amount,
                    paidById = paidById,
                    currency = currency,
                    splitType = splitType,
                    customSplitsJson = validatedCustomSplits,
                    transactionType = TransactionType.PURCHASE,
                    notes = "Group expense via ${payer?.name ?: "Unknown"}"
                )) {
                    is GroupExpenseCreationResult.Success -> {
                        loadGroups()
                        _uiState.value = _uiState.value.copy(addingExpense = false)
                    }
                    is GroupExpenseCreationResult.Error -> {
                        _uiState.value = _uiState.value.copy(error = result.message)
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
     * Delete (archive) a group.
     */
    fun deleteGroup(groupId: Long) {
        viewModelScope.launch {
            try {
                val success = deleteGroupUseCase(groupId)
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

    private suspend fun resolveGroupMembers(groupId: Long): List<GroupMember> {
        _uiState.value.selectedGroup
            ?.takeIf { it.group.id == groupId }
            ?.members
            ?.let { return it }

        _uiState.value.groups.firstOrNull { it.group.id == groupId }?.members?.let { return it }

        return groupsRepository.getActiveGroupsWithDetails()
            .firstOrNull { it.group.id == groupId }
            ?.members
            .orEmpty()
    }

    private fun serializeAndValidateCustomSplits(
        splitType: SplitType,
        customSplits: Map<Long, Double>?,
        totalAmount: Double,
        members: List<GroupMember>
    ): CustomSplitPayload {
        if (splitType == SplitType.EQUAL) {
            return CustomSplitPayload.Valid(null)
        }

        if (members.isEmpty()) {
            return CustomSplitPayload.Invalid("Cannot validate custom splits without group members")
        }

        if (customSplits.isNullOrEmpty()) {
            return CustomSplitPayload.Invalid("Custom split payload is missing")
        }

        if (customSplits.values.any { !it.isFinite() }) {
            return CustomSplitPayload.Invalid("Custom split values must be finite numbers")
        }

        val serialized = customSplits
            .toList()
            .sortedBy { it.first }
            .joinToString(",") { (memberId, value) ->
                "$memberId:${value.toCanonicalSplitString()}"
            }

        return when (val parseResult = CustomSplitParser.parseAndValidate(
            splitsString = serialized,
            splitType = splitType.toCustomSplitMode(),
            totalAmount = totalAmount,
            groupMemberIds = members.map { it.id }.toSet()
        )) {
            is CustomSplitParseResult.Valid -> CustomSplitPayload.Valid(serialized)
            is CustomSplitParseResult.Invalid -> CustomSplitPayload.Invalid(parseResult.reason)
        }
    }

    private fun SplitType.toCustomSplitMode(): CustomSplitMode {
        return when (this) {
            SplitType.EQUAL -> CustomSplitMode.EQUAL
            SplitType.CUSTOM_AMOUNT -> CustomSplitMode.CUSTOM_AMOUNT
            SplitType.CUSTOM_PERCENT -> CustomSplitMode.CUSTOM_PERCENT
            SplitType.UNEQUAL -> CustomSplitMode.UNEQUAL
        }
    }

    private fun Double.toCanonicalSplitString(): String {
        return BigDecimal.valueOf(this)
            .setScale(6, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    }

    private sealed class CustomSplitPayload {
        data class Valid(val serialized: String?) : CustomSplitPayload()
        data class Invalid(val reason: String) : CustomSplitPayload()
    }
}
