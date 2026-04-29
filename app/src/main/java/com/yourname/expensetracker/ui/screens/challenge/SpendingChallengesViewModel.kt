package com.yourname.expensetracker.ui.screens.challenge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.challenge.ChallengeType
import com.yourname.expensetracker.domain.challenge.ActiveChallengesSnapshot
import com.yourname.expensetracker.domain.challenge.NoSpendStatus
import com.yourname.expensetracker.domain.challenge.SpendingChallenge
import com.yourname.expensetracker.domain.challenge.SpendingChallengeManager
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SpendingChallengesViewModel @Inject constructor(
 private val challengeManager: SpendingChallengeManager,
 private val categoryRepository: CategoryRepository,
 private val currencySettingsRepository: CurrencySettingsRepository
) : ViewModel() {

    data class CreateChallengeUiState(
        val categories: List<Category> = emptyList(),
        val isCreating: Boolean = false,
        val errorMessage: String? = null
    )

    sealed interface CreateChallengeEvent {
        data object Created : CreateChallengeEvent
    }

    data class ChallengesAvailability(
        val hasCanonicalSource: Boolean,
        val unavailableReason: String? = null
    )
    
    private val _noSpendStatus = MutableStateFlow<NoSpendStatus?>(null)
    val noSpendStatus: StateFlow<NoSpendStatus?> = _noSpendStatus.asStateFlow()

    private val _activeChallenges = MutableStateFlow<List<SpendingChallenge>>(emptyList())
    val activeChallenges: StateFlow<List<SpendingChallenge>> = _activeChallenges.asStateFlow()

    private val _challengesAvailability = MutableStateFlow(ChallengesAvailability(hasCanonicalSource = true))
    val challengesAvailability: StateFlow<ChallengesAvailability> = _challengesAvailability.asStateFlow()

    private val _createChallengeUiState = MutableStateFlow(CreateChallengeUiState())
    val createChallengeUiState: StateFlow<CreateChallengeUiState> = _createChallengeUiState.asStateFlow()

 private val _createChallengeEvents = MutableSharedFlow<CreateChallengeEvent>(extraBufferCapacity = 1)
 val createChallengeEvents: SharedFlow<CreateChallengeEvent> = _createChallengeEvents.asSharedFlow()

 private val _homeCurrency = MutableStateFlow("EUR")
 val homeCurrency: StateFlow<String> = _homeCurrency.asStateFlow()
    
 init {
 loadCategories()
 checkNoSpendStatus()
 loadActiveChallenges()
 collectHomeCurrency()
 }

    private fun loadCategories() {
        viewModelScope.launch {
            val categories = runCatching { categoryRepository.getAll() }
                .getOrDefault(emptyList())
                .sortedBy { it.name }
            _createChallengeUiState.value = _createChallengeUiState.value.copy(categories = categories)
        }
    }
    
    private fun checkNoSpendStatus() {
        viewModelScope.launch {
            try {
                val status = challengeManager.checkNoSpendStreak()
                _noSpendStatus.value = status
            } catch (e: Exception) {
                _noSpendStatus.value = null
            }
        }
    }

    private fun loadActiveChallenges() {
        viewModelScope.launch {
            try {
                val snapshot: ActiveChallengesSnapshot = challengeManager.getActiveChallengesSnapshot()
                _activeChallenges.value = snapshot.challenges
                _challengesAvailability.value = ChallengesAvailability(
                    hasCanonicalSource = true,
                    unavailableReason = snapshot.unavailableReason
                )
            } catch (e: Exception) {
                _activeChallenges.value = emptyList()
                _challengesAvailability.value = ChallengesAvailability(
                    hasCanonicalSource = false,
                    unavailableReason = e.message ?: "Active challenges are unavailable."
                )
            }
        }
    }
    
    fun refresh() {
        checkNoSpendStatus()
        loadActiveChallenges()
    }

    fun clearCreateChallengeError() {
        _createChallengeUiState.value = _createChallengeUiState.value.copy(errorMessage = null)
    }

    fun createChallenge(
        name: String,
        type: ChallengeType,
        durationDays: Int,
        targetAmount: Double?,
        categoryId: Long?
    ) {
        viewModelScope.launch {
            _createChallengeUiState.value = _createChallengeUiState.value.copy(
                isCreating = true,
                errorMessage = null
            )

            runCatching {
                challengeManager.createChallenge(
                    name = name,
                    type = type,
                    durationDays = durationDays,
                    targetAmount = targetAmount,
                    categoryId = categoryId
                )
            }.onSuccess {
                refresh()
                _createChallengeEvents.tryEmit(CreateChallengeEvent.Created)
            }.onFailure { error ->
                _createChallengeUiState.value = _createChallengeUiState.value.copy(
                    errorMessage = error.message ?: "Unable to create challenge."
                )
            }

 _createChallengeUiState.value = _createChallengeUiState.value.copy(isCreating = false)
 }
 }

 private fun collectHomeCurrency() {
 viewModelScope.launch {
 currencySettingsRepository.homeCurrency().collect { hc ->
 _homeCurrency.value = hc
 }
 }
 }
}
