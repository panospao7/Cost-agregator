Goal
Continue the hybrid AI rollout for the ExpenseTracker Android app by wiring a real on-device Gemini Nano provider for categorization assist (OnDeviceCategorizationAssistService). This follows the phased approach defined in Ai architecture plan.md.
Instructions
- Follow Ai architecture plan.md
- Preserve the trust boundary: AI is advisory only, deterministic app logic remains authoritative
- Keep approval/save flows deterministic and user-controlled
- Use both cloud and on-device, routing by capability, privacy, connectivity, and runtime support
Discoveries
Previous work completed
- Phases 1-3 AI implementation: advisory assistant, query layer, capture assist - all committed
- Hybrid PRs 1-5: routing, cloud review explanation, receipt assist, categorization assist, dedupe judge - all live-verified and committed
- Nano PR 1: runtime routing foundation with richer on-device status modeling (AVAILABLE, DOWNLOADING, UNAVAILABLE, UNSUPPORTED_DEVICE, NOT_APPLICABLE) - committed
- Nano PR 1.5 (toolchain upgrade): Completed to enable ML Kit GenAI Prompt API:
  - Kotlin 2.0.21 → 2.2.21
  - AGP 8.7.2 → 8.10.1
  - Gradle 8.9 → 8.11.1
  - KSP 2.0.21-1.0.27 → 2.2.21-2.0.5
  - Added com.google.mlkit:genai-prompt:1.0.0-beta1
  - Upgraded Room 2.6.1 → 2.7.2, Hilt 2.51.1 → 2.57
  - Fixed Kotlin 2.2 test regressions (toLowerCase → lowercase)
- Compile and router unit tests pass with the upgraded toolchain
Current work in progress
- Attempting to wire OnDeviceCategorizationAssistService using ML Kit GenAI Prompt API
- Inspecting ML Kit API to understand how to:
  1. Check on-device model status (downloaded vs downloading)
  2. Execute text prompt and receive text response
- Used web fetch to get ML Kit reference documentation
- Used Python to inspect downloaded AAR artifacts in Gradle cache to find class names:
  - Key classes found: com.google.mlkit.genai.prompt.Generation, GenerativeModel, GenerateContentRequest, GenerateContentResponse, DownloadStatus, FeatureStatus, GenAiException
  - GenerativeModel is an interface, Generation.getClient() appears to be the factory
- Subagent was tasked to inspect signatures but couldn't find javap in PATH
Blocker
- Need to determine exact Kotlin API call shapes from ML Kit GenAI Prompt library to implement the provider
- May need Android Studio/emulator to test runtime behavior
Accomplished
- Toolchain upgraded successfully (Kotlin 2.2.21, AGP 8.10.1, Gradle 8.11.1, KSP 2.2.21-2.0.5)
- ML Kit genai-prompt dependency added and resolving
- Compile and router tests passing
- Architecture research complete: understand how HybridCategorizationAssistService routes, how to wire in OnDeviceCategorizationAssistService
What remains
1. Determine exact ML Kit GenAI Prompt API usage (Kotlin call shapes)
2. Implement OnDeviceCategorizationAssistService:
   - Use ML Kit Generation.getClient() to get model instance
   - Handle model status checking via ML Kit APIs
   - Implement assist() method that calls generateContent() with categorization prompt
   - Parse response to extract suggested category
3. Wire into AiModule as Hilt provider
4. Ensure DefaultAiEnvironmentMonitor.getOnDeviceModelStatus() can query ML Kit model status (or create separate ML Kit status checker)
5. Add tests for new provider
6. Verify routing works end-to-end
Relevant files / directories
Core AI architecture (already in place)
- app/src/main/java/com/yourname/expensetracker/domain/ai/model/AiModels.kt - OnDeviceModelStatus enum
- app/src/main/java/com/yourname/expensetracker/domain/ai/policy/DefaultAiCapabilityRouter.kt - routing logic
- app/src/main/java/com/yourname/expensetracker/domain/ai/service/AiEnvironmentMonitor.kt - interface
- app/src/main/java/com/yourname/expensetracker/data/ai/provider/DefaultAiEnvironmentMonitor.kt - implementation
- app/src/main/java/com/yourname/expensetracker/di/AiModule.kt - DI wiring
Provider files to reference
- app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridCategorizationAssistService.kt - routing entry point
- app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistService.kt - cloud implementation
- app/src/main/java/com/yourname/expensetracker/data/ai/provider/NoOpCategorizationAssistService.kt - no-op fallback
Test files
- app/src/test/java/com/yourname/expensetracker/domain/ai/policy/DefaultAiCapabilityRouterTest.kt
Build files (upgraded)
- gradle/libs.versions.toml - Kotlin 2.2.21, AGP 8.10.1
- gradle/wrapper/gradle-wrapper.properties - Gradle 8.11.1
- build.gradle.kts - KSP 2.2.21-2.0.5, Hilt 2.57
- app/build.gradle.kts - ML Kit genai-prompt:1.0.0-beta1, Room 2.7.2
Planning
- Ai architecture plan.md