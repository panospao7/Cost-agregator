package com.yourname.expensetracker.data.ai

import com.yourname.expensetracker.domain.ai.service.CloudProviderConnectionTester
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OkHttpCloudProviderConnectionTester @Inject constructor(
    private val privacyGate: PrivacyGate
) : CloudProviderConnectionTester {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun testGemini(apiKey: String): String? = withContext(Dispatchers.IO) {
        val gateCheck = privacyGate.check(PrivacyCapability.CLOUD_AI_GENERAL)
        if (gateCheck.blocksExecution()) {
            return@withContext "Cloud AI is blocked by privacy settings: ${gateCheck.reason()}"
        }
        val request = Request.Builder()
            .url("${AppConfig.Ai.GEMINI_BASE_URL}/v1beta/models")
            .header("x-goog-api-key", apiKey)
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> null
                    response.code in setOf(400, 401, 403) -> "Provider rejected the API key. Check the key and retry."
                    response.code == 429 -> "Provider rate limit reached. Wait a moment and retry."
                    response.code in 500..599 -> "Provider is temporarily unavailable. Please retry."
                    else -> "Provider connectivity test failed (HTTP ${response.code})."
                }
            }
        } catch (e: IOException) {
            Timber.w(e, "AI settings provider probe failed")
            "Could not reach the cloud provider. Check internet and retry."
        }
    }
}
