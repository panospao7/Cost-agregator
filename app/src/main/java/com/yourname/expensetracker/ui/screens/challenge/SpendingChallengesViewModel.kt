package com.yourname.expensetracker.ui.screens.challenge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    
    private val _noSpendStatus = MutableStateFlow<NoSpendStatus?>(null)
    val noSpendStatus: StateFlow<NoSpendStatus?> = _noSpendStatus.asStateFlow()

    private val _activeChallenges = MutableStateFlow<List<SpendingChallenge>>(emptyList())
    val activeChallenges: StateFlow<List<SpendingChallenge>> = _activeChallenges.asStateFlow()
    
    init {
        checkNoSpendStatus()
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
    
    fun refresh() {
        checkNoSpendStatus()
    }
}
