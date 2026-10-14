ExpenseTracker - Development Build Guide

Prerequisites
- Android Studio compatible with the project's AGP 8.10.1, with Android SDK Platform 35 installed
- JDK 17 for running Gradle and the Kotlin plugin (the app's bytecode target remains 11)
- Gradle 8.11.1 via the wrapper included in the repo; current AGP is 8.10.1
- Node/Yarn (if any web tooling is used in later modules)
- Git for source control

Setup
- Clone the repository: git clone <repo-url> Cost-agregator && cd Cost-agregator
- Open in Android Studio and let Gradle sync complete
- Ensure Android SDK licenses are accepted
- Check environment: JAVA_HOME points to JDK 17; build uses the Gradle wrapper

Build
- Prefer incremental builds; use clean only for diagnosed stale-output problems, not before every build.
- Agents must use `scripts/validation-runner.ps1` only; see [Validation Runner](VALIDATION_RUNNER.md) for `Start`/`Plan` commands. The direct Gradle examples below are for manual human use only.
- Assemble: ./gradlew assembleDebug
- Assemble Release: ./gradlew assembleRelease
- To run tests: ./gradlew testDebugUnitTest

Running on device/emulator
- Create or start an Android Emulator (or connect a real device via USB)
- Run the app from Android Studio or via command: adb install -r app/build/outputs/apk/debug/app-debug.apk
- Ensure that USB debugging is enabled on the device
- Optional: use Android Studio's Run/Debug configurations to launch on a selected device

Configuration
- API keys and endpoints must be securely stored; BuildConfig should not contain secrets.
- Use SecureKeyStorage (production) for API keys; avoid putting secrets in repo
- If you enable experimental features, update BuildConfig flags as documented in BUILD_GUIDE or FEATURES.md

Project structure overview
- app/ or module containing UI (Compose), domain logic, data layer
- data/ - database, repositories, network providers
- domain/ - use cases, engines, models
- ui/ - screens, viewmodels, components
- di/ - dependency injection and binding modules
- data/location/ - geocoding integrations (NEW Mar 2026)
- data/ai/ - AI-related providers
- data/security/ - key storage and related security utilities (NEW Apr 2026)

Common issues and solutions
- Gradle sync issues: Inspect the error, check connectivity, and confirm JDK 17, wrapper, and SDK settings before considering cache invalidation.
- Missing dependencies: Check repository/network errors, refresh Gradle in the IDE, and retry incrementally; clean is not a dependency-resolution fix. Agents use the validation runner only.
- Build failures due to Kotlin version: Check project Gradle wrapper and Kotlin plugin versions in build.gradle files.
- Emulator slow or API level mismatch: Adjust AVD settings or use a physical device.
- Network calls failing in emulator: Ensure proper network configuration or use a real device for testing behind corporate proxies.

Tips for contributors
- Use feature branches per task; follow commit messages with a concise why and what.
- Run unit tests locally; consider integration tests for critical flows.
- Update or add tests when introducing new domain logic.

Local performance experiments
- The working checkout is `C:\Users\panos\Desktop\Cost-agregator`, outside OneDrive.
- See [Validation Runner: per-run settings and examples](VALIDATION_RUNNER.md#per-run-gradle-settings) for opt-in workers, test forks, warm Gradle daemon, and Kotlin compiler strategy experiments. Defaults remain serial; all agents use the runner and only one invocation may execute at a time.
- Each test JVM has a default `2g` maximum heap in addition to Gradle/compiler memory. Eight forks is a limit, not the default; full-suite evidence is still needed before relying on parallelism. No speedup has been measured here.
- Compare repeated medians for matched warm no-op, representative edit/recompile, and identical test workloads; never compare serial-cold against fast-warm runs.
- Measure Defender/file-I/O impact first; do not change system settings, disable protection, or add exclusions. Guard configuration-cache refactoring is deferred, not a reason to skip guards.

End-to-end quick start
- Start Android Studio > Open project
- Build > Run on device or emulator
- Navigate to the features via the app; check the 77 screens and 32 routes for coverage

---
