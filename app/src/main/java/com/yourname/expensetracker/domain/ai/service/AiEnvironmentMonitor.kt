package com.yourname.expensetracker.domain.ai.service

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus

interface AiEnvironmentMonitor {
    fun isNetworkAvailable(): Boolean
    fun isWifiConnected(): Boolean
    suspend fun getOnDeviceModelStatus(capability: AiCapability): OnDeviceModelStatus
}
