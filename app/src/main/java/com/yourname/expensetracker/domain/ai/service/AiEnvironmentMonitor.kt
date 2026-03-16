package com.yourname.expensetracker.domain.ai.service

import com.yourname.expensetracker.domain.ai.model.AiCapability

interface AiEnvironmentMonitor {
    fun isNetworkAvailable(): Boolean
    fun isWifiConnected(): Boolean
    fun isOnDeviceModelAvailable(capability: AiCapability): Boolean
}
