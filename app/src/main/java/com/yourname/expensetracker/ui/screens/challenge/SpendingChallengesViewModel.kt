package com.yourname.expensetracker.ui.screens.challenge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.challenge.ActiveChallengesSnapshot
import com.yourname.expensetracker.domain.challenge.NoSpendStatus
import com.yourname.expensetracker.domain.challenge.SpendingChallenge
import com.yourname.expensetracker.domain.challenge.SpendingChallengeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SpendingChallengesViewModel @Inject constructor(
    private val challengeManager: SpendingChallengeManager
) : ViewModel() {

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
    
    init {
        checkNoSpendStatus()
        loadActiveChallenges()
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
                    hasCanonicalSource = snapshot.unavailableReason == null,
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
}
