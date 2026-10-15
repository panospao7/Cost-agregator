package com.yourname.expensetracker.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * RT-C3 contract: Gson maps JSON keys to field names reflectively, so under R8 every
 * class Gson (de)serializes needs a scoped -keepclassmembers rule in
 * app/proguard-rules.pro, and TypeToken subclasses must keep their generic Signature.
 *
 * Fail-closed: any new production file referencing com.google.gson must be added to
 * [KNOWN_GSON_CONSUMER_FILES] together with its keep rules (or an explicit
 * justification that it only handles types Gson already covers).
 */
class GsonR8KeepRulesContractTest {

    private val appDir: File by lazy {
        listOf(File("app"), File("."), File(System.getProperty("user.dir") ?: ".", "app"))
            .firstOrNull { File(it, "proguard-rules.pro").isFile && File(it, "src/main").isDirectory }
            ?: error("Could not locate app module. user.dir=${System.getProperty("user.dir")}")
    }

    private val rules: List<String> by lazy {
        File(appDir, "proguard-rules.pro").readLines().map { it.trim() }
    }

    @Test
    fun gsonConsumersInProductionSourceMatchKnownSet() {
        val actual = File(appDir, "src/main").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { it.readText().contains("com.google.gson") }
            .map { it.name }
            .toSortedSet()

        assertEquals(
            "New/removed Gson consumer: update KNOWN_GSON_CONSUMER_FILES and the R8 keep rules in " +
                "app/proguard-rules.pro together (RT-C3).",
            KNOWN_GSON_CONSUMER_FILES.toSortedSet(),
            actual
        )
    }

    @Test
    fun everyGsonModelClassExistsAndHasScopedKeepClassMembersRule() {
        for (binaryName in GSON_MODEL_CLASSES) {
            // Throws ClassNotFoundException when the plan/rule names a class that does not exist
            // (a typo here would silently keep nothing under R8).
            Class.forName(binaryName, false, javaClass.classLoader)

            val rule = rules.firstOrNull {
                it.startsWith("-keepclassmembers") && it.contains(" $binaryName {")
            }
            if (rule == null) {
                fail("Missing -keepclassmembers rule for Gson model $binaryName in app/proguard-rules.pro")
            } else {
                assertTrue("Rule for $binaryName must keep <fields>: $rule", rule.contains("<fields>;"))
            }
        }
    }

    @Test
    fun automatedSavingsPersistedStateKeepsNoArgConstructor() {
        val name = "com.yourname.expensetracker.data.repository.AutomatedSavingsRuleStateRepository\$PersistedState"
        val rule = rules.first { it.startsWith("-keepclassmembers") && it.contains(" $name {") }
        assertTrue("Defaults-dependent Gson read path needs <init>(): $rule", rule.contains("<init>();"))
    }

    @Test
    fun typeTokenRulesExist() {
        assertTrue(
            "Missing TypeToken base keep rule",
            rules.contains("-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken")
        )
        assertTrue(
            "Missing package-scoped TypeToken subclass keep rule",
            rules.contains(
                "-keep,allowobfuscation,allowshrinking class com.yourname.expensetracker.** " +
                    "extends com.google.gson.reflect.TypeToken"
            )
        )
    }

    @Test
    fun noBroadGsonKeepOrDontwarnRules() {
        val broad = rules.filter {
            it.startsWith("-dontwarn com.google.gson") ||
                (it.startsWith("-keep") && it.contains("com.yourname.expensetracker.** {")) ||
                (it.startsWith("-keep") && it.contains("com.yourname.expensetracker.**{"))
        }
        assertTrue("Broad Gson/app-wide R8 rules are not allowed: $broad", broad.isEmpty())
    }

    private companion object {
        val KNOWN_GSON_CONSUMER_FILES = listOf(
            "ServiceModule.kt",
            "EnhancedSplitManager.kt",
            "FileWorkerTerminalDiagnosticSink.kt",
            "VisualSplitViewModel.kt",
            "SplitTemplatesScreen.kt",
            "CustomSplitJsonCodec.kt",
            "SavingsContributionHistoryRepository.kt",
            "MainActivity.kt",
            "AutomatedSavingsRuleStateRepository.kt"
        )

        val GSON_MODEL_CLASSES = listOf(
            "com.yourname.expensetracker.data.repository.SavingsContributionEvent",
            "com.yourname.expensetracker.data.repository.SavingsContributionHistoryRepository\$PersistedState",
            "com.yourname.expensetracker.data.repository.AutomatedSavingsRuleStateRepository\$PersistedState",
            "com.yourname.expensetracker.data.repository.AutomatedSavingsRuleStateRepository\$WeeklyRewardReservation",
            "com.yourname.expensetracker.data.repository.AutomatedSavingsRuleStateRepository\$MonthlyCapUsage",
            "com.yourname.expensetracker.data.database.entity.SplitShare",
            "com.yourname.expensetracker.ui.PersistedVisualSplit",
            "com.yourname.expensetracker.data.database.entity.SplitTemplate\$SplitType"
        )
    }
}
