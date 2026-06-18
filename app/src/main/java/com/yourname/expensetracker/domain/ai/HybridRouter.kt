package com.yourname.expensetracker.domain.ai

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Reusable generic hybrid router that decides at runtime whether to execute a
 * capability via cloud AI, on-device AI, or a deterministic fallback.
 *
 * ## AID-4: Shared HybridRouter
 *
 * Six hybrid services across the app currently duplicate the same routing
 * pattern — checking [AiSettingsRepository], consulting [AiCapabilityRouter],
 * and dispatching to one of three function implementations. This generic class
 * was created as part of AID-4 to serve as a shared base.
 *
 * ### Usage
 * ```kotlin
 * val router = HybridRouter(
 *     aiSettingsRepository = aiSettingsRepository,
 *     router = aiCapabilityRouter,
 *     capability = AiCapability.RECEIPT_EXTRACTION,
 *     cloudFn = { input -> cloudService.extract(input) },
 *     onDeviceFn = { input -> onDeviceService.extract(input) },
 *     fallbackFn = { input -> fallbackService.extract(input) }
 * )
 * val result = router.execute(input)
 * ```
 *
 * ### Migration note
 * Existing hybrid service implementations should be migrated to use this
 * router to eliminate code duplication. Each service should:
 * 1. Inject [HybridRouter] with its capability-specific functions.
 * 2. Delegate its public `execute` method to [HybridRouter.execute].
 * 3. Remove the duplicated routing logic.
 *
 * @param TInput The input type for all three execution paths.
 * @param TOutput The output type returned by all three execution paths.
 * @property aiSettingsRepository Repository for user AI preference settings.
 * @property router The capability router that decides which [AiRoute] to take.
 * @property capability The specific AI capability being routed.
 * @property cloudFn Suspended function for cloud-based execution.
 * @property onDeviceFn Suspended function for on-device execution.
 * @property fallbackFn Suspended function for deterministic fallback execution.
 */
class HybridRouter<TInput, TOutput>(
    private val aiSettingsRepository: AiSettingsRepository,
    private val router: AiCapabilityRouter,
    private val capability: AiCapability,
    private val cloudFn: suspend (TInput) -> TOutput,
    private val onDeviceFn: suspend (TInput) -> TOutput,
    private val fallbackFn: suspend (TInput) -> TOutput
) {
    /**
     * Execute the capability for the given [input], routing to cloud, on-device,
     * or fallback based on current settings and router decision.
     *
     * @param input The input to pass to the selected execution function.
     * @return The output from the selected execution path.
     */
    suspend fun execute(input: TInput): TOutput {
        val settings = aiSettingsRepository.settings().first()
        return when (router.decide(capability, settings).route) {
            AiRoute.CLOUD -> cloudFn(input)
            AiRoute.ON_DEVICE -> onDeviceFn(input)
            else -> fallbackFn(input)
        }
    }
}
