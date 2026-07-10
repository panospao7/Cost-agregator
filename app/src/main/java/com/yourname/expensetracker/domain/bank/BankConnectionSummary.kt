package com.yourname.expensetracker.domain.bank

data class BankConnectionSummary(
    val id: Long,
    val bankId: String,
    val bankName: String,
    val countryCode: String,
    val isConnected: Boolean,
    val isActive: Boolean,
    val lastSync: Long?,
    val lastSyncStatus: String?,  // safe status code, not raw text
    val syncFrequency: String     // display-only sync frequency name, not sensitive
)
