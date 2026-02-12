# ExpenseTracker Full Source Code Extraction

This file contains the complete source code from the `src` directory.

## Table of Contents
1. [main\AndroidManifest.xml](#mainandroidmanifestxml)
2. [main\java\com\yourname\expensetracker\ExpenseTrackerApp.kt](#mainjavacomyournameexpensetrackerexpensetrackerappkt)
3. [main\java\com\yourname\expensetracker\data\database\AppDatabase.kt](#mainjavacomyournameexpensetrackerdatadatabaseappdatabasekt)
4. [main\java\com\yourname\expensetracker\data\database\converter\Converters.kt](#mainjavacomyournameexpensetrackerdatadatabaseconverterconverterskt)
5. [main\java\com\yourname\expensetracker\data\database\dao\BlockedPackageDao.kt](#mainjavacomyournameexpensetrackerdatadatabasedaoblockedpackagedaokt)
6. [main\java\com\yourname\expensetracker\data\database\dao\BudgetDao.kt](#mainjavacomyournameexpensetrackerdatadatabasedaobudgetdaokt)
7. [main\java\com\yourname\expensetracker\data\database\dao\CategoryDao.kt](#mainjavacomyournameexpensetrackerdatadatabasedaocategorydaokt)
8. [main\java\com\yourname\expensetracker\data\database\dao\ExpenseDao.kt](#mainjavacomyournameexpensetrackerdatadatabasedaoexpensedaokt)
9. [main\java\com\yourname\expensetracker\data\database\dao\MerchantCategoryDao.kt](#mainjavacomyournameexpensetrackerdatadatabasedaomerchantcategorydaokt)
10. [main\java\com\yourname\expensetracker\data\database\dao\MerchantNormalizationDao.kt](#mainjavacomyournameexpensetrackerdatadatabasedaomerchantnormalizationdaokt)
11. [main\java\com\yourname\expensetracker\data\database\dao\PendingReviewDao.kt](#mainjavacomyournameexpensetrackerdatadatabasedaopendingreviewdaokt)
12. [main\java\com\yourname\expensetracker\data\database\dao\PlannedExpenseDao.kt](#mainjavacomyournameexpensetrackerdatadatabasedaoplannedexpensedaokt)
13. [main\java\com\yourname\expensetracker\data\database\dao\RawNotificationDao.kt](#mainjavacomyournameexpensetrackerdatadatabasedaorawnotificationdaokt)
14. [main\java\com\yourname\expensetracker\data\database\dao\RecurringExpenseDao.kt](#mainjavacomyournameexpensetrackerdatadatabasedaorecurringexpensedaokt)
15. [main\java\com\yourname\expensetracker\data\database\dao\SavingsGoalDao.kt](#mainjavacomyournameexpensetrackerdatadatabasedaosavingsgoaldaokt)
16. [main\java\com\yourname\expensetracker\data\database\dao\ScannedReceiptDao.kt](#mainjavacomyournameexpensetrackerdatadatabasedaoscannedreceiptdaokt)
17. [main\java\com\yourname\expensetracker\data\database\dao\SourceStatsDao.kt](#mainjavacomyournameexpensetrackerdatadatabasedaosourcestatsdaokt)
18. [main\java\com\yourname\expensetracker\data\database\dao\UserCorrectionDao.kt](#mainjavacomyournameexpensetrackerdatadatabasedaousercorrectiondaokt)
19. [main\java\com\yourname\expensetracker\data\database\entity\BlockedPackage.kt](#mainjavacomyournameexpensetrackerdatadatabaseentityblockedpackagekt)
20. [main\java\com\yourname\expensetracker\data\database\entity\Budget.kt](#mainjavacomyournameexpensetrackerdatadatabaseentitybudgetkt)
21. [main\java\com\yourname\expensetracker\data\database\entity\Category.kt](#mainjavacomyournameexpensetrackerdatadatabaseentitycategorykt)
22. [main\java\com\yourname\expensetracker\data\database\entity\Expense.kt](#mainjavacomyournameexpensetrackerdatadatabaseentityexpensekt)
23. [main\java\com\yourname\expensetracker\data\database\entity\ManualRecurringExpense.kt](#mainjavacomyournameexpensetrackerdatadatabaseentitymanualrecurringexpensekt)
24. [main\java\com\yourname\expensetracker\data\database\entity\MerchantAlias.kt](#mainjavacomyournameexpensetrackerdatadatabaseentitymerchantaliaskt)
25. [main\java\com\yourname\expensetracker\data\database\entity\MerchantCanonical.kt](#mainjavacomyournameexpensetrackerdatadatabaseentitymerchantcanonicalkt)
26. [main\java\com\yourname\expensetracker\data\database\entity\MerchantCategory.kt](#mainjavacomyournameexpensetrackerdatadatabaseentitymerchantcategorykt)
27. [main\java\com\yourname\expensetracker\data\database\entity\PendingReview.kt](#mainjavacomyournameexpensetrackerdatadatabaseentitypendingreviewkt)
28. [main\java\com\yourname\expensetracker\data\database\entity\PlannedExpense.kt](#mainjavacomyournameexpensetrackerdatadatabaseentityplannedexpensekt)
29. [main\java\com\yourname\expensetracker\data\database\entity\RawNotification.kt](#mainjavacomyournameexpensetrackerdatadatabaseentityrawnotificationkt)
30. [main\java\com\yourname\expensetracker\data\database\entity\SavingsGoal.kt](#mainjavacomyournameexpensetrackerdatadatabaseentitysavingsgoalkt)
31. [main\java\com\yourname\expensetracker\data\database\entity\ScannedReceipt.kt](#mainjavacomyournameexpensetrackerdatadatabaseentityscannedreceiptkt)
32. [main\java\com\yourname\expensetracker\data\database\entity\SourceStats.kt](#mainjavacomyournameexpensetrackerdatadatabaseentitysourcestatskt)
33. [main\java\com\yourname\expensetracker\data\database\entity\UserCorrection.kt](#mainjavacomyournameexpensetrackerdatadatabaseentityusercorrectionkt)
34. [main\java\com\yourname\expensetracker\data\database\model\DashboardWidgetConfig.kt](#mainjavacomyournameexpensetrackerdatadatabasemodeldashboardwidgetconfigkt)
35. [main\java\com\yourname\expensetracker\data\database\model\ExpenseWithCategory.kt](#mainjavacomyournameexpensetrackerdatadatabasemodelexpensewithcategorykt)
36. [main\java\com\yourname\expensetracker\data\database\model\PendingReviewWithReceipt.kt](#mainjavacomyournameexpensetrackerdatadatabasemodelpendingreviewwithreceiptkt)
37. [main\java\com\yourname\expensetracker\data\provider\MerchantCategoryProvider.kt](#mainjavacomyournameexpensetrackerdataprovidermerchantcategoryproviderkt)
38. [main\java\com\yourname\expensetracker\data\repository\BudgetRepository.kt](#mainjavacomyournameexpensetrackerdatarepositorybudgetrepositorykt)
39. [main\java\com\yourname\expensetracker\data\repository\CategoryRepository.kt](#mainjavacomyournameexpensetrackerdatarepositorycategoryrepositorykt)
40. [main\java\com\yourname\expensetracker\data\repository\DashboardRepository.kt](#mainjavacomyournameexpensetrackerdatarepositorydashboardrepositorykt)
41. [main\java\com\yourname\expensetracker\data\repository\FinancialWeatherRepository.kt](#mainjavacomyournameexpensetrackerdatarepositoryfinancialweatherrepositorykt)
42. [main\java\com\yourname\expensetracker\data\repository\MerchantCategoryRepository.kt](#mainjavacomyournameexpensetrackerdatarepositorymerchantcategoryrepositorykt)
43. [main\java\com\yourname\expensetracker\data\repository\NotificationRepository.kt](#mainjavacomyournameexpensetrackerdatarepositorynotificationrepositorykt)
44. [main\java\com\yourname\expensetracker\data\repository\PlannedExpenseRepository.kt](#mainjavacomyournameexpensetrackerdatarepositoryplannedexpenserepositorykt)
45. [main\java\com\yourname\expensetracker\data\repository\ReceiptRepository.kt](#mainjavacomyournameexpensetrackerdatarepositoryreceiptrepositorykt)
46. [main\java\com\yourname\expensetracker\di\AppModule.kt](#mainjavacomyournameexpensetrackerdiappmodulekt)
47. [main\java\com\yourname\expensetracker\domain\analytics\AnalyticsModels.kt](#mainjavacomyournameexpensetrackerdomainanalyticsanalyticsmodelskt)
48. [main\java\com\yourname\expensetracker\domain\analytics\InsightsEngine.kt](#mainjavacomyournameexpensetrackerdomainanalyticsinsightsenginekt)
49. [main\java\com\yourname\expensetracker\domain\budget\BudgetModels.kt](#mainjavacomyournameexpensetrackerdomainbudgetbudgetmodelskt)
50. [main\java\com\yourname\expensetracker\domain\budget\BudgetMonitor.kt](#mainjavacomyournameexpensetrackerdomainbudgetbudgetmonitorkt)
51. [main\java\com\yourname\expensetracker\domain\categorization\CategorizationEngine.kt](#mainjavacomyournameexpensetrackerdomaincategorizationcategorizationenginekt)
52. [main\java\com\yourname\expensetracker\domain\debug\NotificationSeeder.kt](#mainjavacomyournameexpensetrackerdomaindebugnotificationseederkt)
53. [main\java\com\yourname\expensetracker\domain\intelligence\ConfidenceRouter.kt](#mainjavacomyournameexpensetrackerdomainintelligenceconfidencerouterkt)
54. [main\java\com\yourname\expensetracker\domain\intelligence\TransactionClassifier.kt](#mainjavacomyournameexpensetrackerdomainintelligencetransactionclassifierkt)
55. [main\java\com\yourname\expensetracker\domain\intelligence\ml\ExpenseCategoryClassifier.kt](#mainjavacomyournameexpensetrackerdomainintelligencemlexpensecategoryclassifierkt)
56. [main\java\com\yourname\expensetracker\domain\intelligence\ml\ExpenseClassifier.kt](#mainjavacomyournameexpensetrackerdomainintelligencemlexpenseclassifierkt)
57. [main\java\com\yourname\expensetracker\domain\intelligence\ml\FeatureExtractor.kt](#mainjavacomyournameexpensetrackerdomainintelligencemlfeatureextractorkt)
58. [main\java\com\yourname\expensetracker\domain\intelligence\ml\HybridExpenseClassifier.kt](#mainjavacomyournameexpensetrackerdomainintelligencemlhybridexpenseclassifierkt)
59. [main\java\com\yourname\expensetracker\domain\intelligence\ml\MerchantNormalizer.kt](#mainjavacomyournameexpensetrackerdomainintelligencemlmerchantnormalizerkt)
60. [main\java\com\yourname\expensetracker\domain\logic\NarrativeGenerator.kt](#mainjavacomyournameexpensetrackerdomainlogicnarrativegeneratorkt)
61. [main\java\com\yourname\expensetracker\domain\logic\RecurringExpenseEngine.kt](#mainjavacomyournameexpensetrackerdomainlogicrecurringexpenseenginekt)
62. [main\java\com\yourname\expensetracker\domain\logic\SynthesisEngine.kt](#mainjavacomyournameexpensetrackerdomainlogicsynthesisenginekt)
63. [main\java\com\yourname\expensetracker\domain\model\FinancialForecast.kt](#mainjavacomyournameexpensetrackerdomainmodelfinancialforecastkt)
64. [main\java\com\yourname\expensetracker\domain\model\PlannedExpense.kt](#mainjavacomyournameexpensetrackerdomainmodelplannedexpensekt)
65. [main\java\com\yourname\expensetracker\domain\model\RecurringPattern.kt](#mainjavacomyournameexpensetrackerdomainmodelrecurringpatternkt)
66. [main\java\com\yourname\expensetracker\domain\model\Result.kt](#mainjavacomyournameexpensetrackerdomainmodelresultkt)
67. [main\java\com\yourname\expensetracker\domain\model\SavingsGoal.kt](#mainjavacomyournameexpensetrackerdomainmodelsavingsgoalkt)
68. [main\java\com\yourname\expensetracker\domain\model\UpcomingItem.kt](#mainjavacomyournameexpensetrackerdomainmodelupcomingitemkt)
69. [main\java\com\yourname\expensetracker\domain\parser\AppParserRegistry.kt](#mainjavacomyournameexpensetrackerdomainparserappparserregistrykt)
70. [main\java\com\yourname\expensetracker\domain\parser\GenericTransactionParser.kt](#mainjavacomyournameexpensetrackerdomainparsergenerictransactionparserkt)
71. [main\java\com\yourname\expensetracker\domain\parser\parsers\GoogleWalletParser.kt](#mainjavacomyournameexpensetrackerdomainparserparsersgooglewalletparserkt)
72. [main\java\com\yourname\expensetracker\domain\parser\parsers\GreekBankParser.kt](#mainjavacomyournameexpensetrackerdomainparserparsersgreekbankparserkt)
73. [main\java\com\yourname\expensetracker\domain\parser\parsers\RevolutParser.kt](#mainjavacomyournameexpensetrackerdomainparserparsersrevolutparserkt)
74. [main\java\com\yourname\expensetracker\domain\parser\parsers\SmsParser.kt](#mainjavacomyournameexpensetrackerdomainparserparserssmsparserkt)
75. [main\java\com\yourname\expensetracker\domain\receipt\BankStatementParser.kt](#mainjavacomyournameexpensetrackerdomainreceiptbankstatementparserkt)
76. [main\java\com\yourname\expensetracker\domain\receipt\ReceiptOcrService.kt](#mainjavacomyournameexpensetrackerdomainreceiptreceiptocrservicekt)
77. [main\java\com\yourname\expensetracker\domain\receipt\ReceiptParser.kt](#mainjavacomyournameexpensetrackerdomainreceiptreceiptparserkt)
78. [main\java\com\yourname\expensetracker\domain\util\BKTree.kt](#mainjavacomyournameexpensetrackerdomainutilbktreekt)
79. [main\java\com\yourname\expensetracker\domain\util\CalendarUtils.kt](#mainjavacomyournameexpensetrackerdomainutilcalendarutilskt)
80. [main\java\com\yourname\expensetracker\domain\util\CurrencyNormalizer.kt](#mainjavacomyournameexpensetrackerdomainutilcurrencynormalizerkt)
81. [main\java\com\yourname\expensetracker\domain\util\MerchantCleaner.kt](#mainjavacomyournameexpensetrackerdomainutilmerchantcleanerkt)
82. [main\java\com\yourname\expensetracker\domain\util\StatisticsUtils.kt](#mainjavacomyournameexpensetrackerdomainutilstatisticsutilskt)
83. [main\java\com\yourname\expensetracker\domain\util\StringDistanceUtils.kt](#mainjavacomyournameexpensetrackerdomainutilstringdistanceutilskt)
84. [main\java\com\yourname\expensetracker\receiver\BootReceiver.kt](#mainjavacomyournameexpensetrackerreceiverbootreceiverkt)
85. [main\java\com\yourname\expensetracker\service\NotificationCaptureService.kt](#mainjavacomyournameexpensetrackerservicenotificationcaptureservicekt)
86. [main\java\com\yourname\expensetracker\ui\MainActivity.kt](#mainjavacomyournameexpensetrackeruimainactivitykt)
87. [main\java\com\yourname\expensetracker\ui\MainViewModel.kt](#mainjavacomyournameexpensetrackeruimainviewmodelkt)
88. [main\java\com\yourname\expensetracker\ui\components\BentoCard.kt](#mainjavacomyournameexpensetrackeruicomponentsbentocardkt)
89. [main\java\com\yourname\expensetracker\ui\components\FinancialWeatherCard.kt](#mainjavacomyournameexpensetrackeruicomponentsfinancialweathercardkt)
90. [main\java\com\yourname\expensetracker\ui\components\ForecastTimeline.kt](#mainjavacomyournameexpensetrackeruicomponentsforecasttimelinekt)
91. [main\java\com\yourname\expensetracker\ui\components\PulseDot.kt](#mainjavacomyournameexpensetrackeruicomponentspulsedotkt)
92. [main\java\com\yourname\expensetracker\ui\components\SpendingPaceGauge.kt](#mainjavacomyournameexpensetrackeruicomponentsspendingpacegaugekt)
93. [main\java\com\yourname\expensetracker\ui\components\SpendingTrendChart.kt](#mainjavacomyournameexpensetrackeruicomponentsspendingtrendchartkt)
94. [main\java\com\yourname\expensetracker\ui\screens\addexpense\AddExpenseSheet.kt](#mainjavacomyournameexpensetrackeruiscreensaddexpenseaddexpensesheetkt)
95. [main\java\com\yourname\expensetracker\ui\screens\addexpense\AddExpenseViewModel.kt](#mainjavacomyournameexpensetrackeruiscreensaddexpenseaddexpenseviewmodelkt)
96. [main\java\com\yourname\expensetracker\ui\screens\analytics\AnalyticsScreen.kt](#mainjavacomyournameexpensetrackeruiscreensanalyticsanalyticsscreenkt)
97. [main\java\com\yourname\expensetracker\ui\screens\analytics\AnalyticsViewModel.kt](#mainjavacomyournameexpensetrackeruiscreensanalyticsanalyticsviewmodelkt)
98. [main\java\com\yourname\expensetracker\ui\screens\budget\BudgetScreen.kt](#mainjavacomyournameexpensetrackeruiscreensbudgetbudgetscreenkt)
99. [main\java\com\yourname\expensetracker\ui\screens\budget\BudgetViewModel.kt](#mainjavacomyournameexpensetrackeruiscreensbudgetbudgetviewmodelkt)
100. [main\java\com\yourname\expensetracker\ui\screens\categories\CategoryScreen.kt](#mainjavacomyournameexpensetrackeruiscreenscategoriescategoryscreenkt)
101. [main\java\com\yourname\expensetracker\ui\screens\categories\CategoryViewModel.kt](#mainjavacomyournameexpensetrackeruiscreenscategoriescategoryviewmodelkt)
102. [main\java\com\yourname\expensetracker\ui\screens\debug\DebugScreen.kt](#mainjavacomyournameexpensetrackeruiscreensdebugdebugscreenkt)
103. [main\java\com\yourname\expensetracker\ui\screens\debug\DebugViewModel.kt](#mainjavacomyournameexpensetrackeruiscreensdebugdebugviewmodelkt)
104. [main\java\com\yourname\expensetracker\ui\screens\home\HomeScreen.kt](#mainjavacomyournameexpensetrackeruiscreenshomehomescreenkt)
105. [main\java\com\yourname\expensetracker\ui\screens\home\HomeViewModel.kt](#mainjavacomyournameexpensetrackeruiscreenshomehomeviewmodelkt)
106. [main\java\com\yourname\expensetracker\ui\screens\receiptscan\ReceiptScanScreen.kt](#mainjavacomyournameexpensetrackeruiscreensreceiptscanreceiptscanscreenkt)
107. [main\java\com\yourname\expensetracker\ui\screens\receiptscan\ReceiptScanViewModel.kt](#mainjavacomyournameexpensetrackeruiscreensreceiptscanreceiptscanviewmodelkt)
108. [main\java\com\yourname\expensetracker\ui\screens\recurring\RecurringExpensesScreen.kt](#mainjavacomyournameexpensetrackeruiscreensrecurringrecurringexpensesscreenkt)
109. [main\java\com\yourname\expensetracker\ui\screens\review\ReviewScreen.kt](#mainjavacomyournameexpensetrackeruiscreensreviewreviewscreenkt)
110. [main\java\com\yourname\expensetracker\ui\screens\review\ReviewViewModel.kt](#mainjavacomyournameexpensetrackeruiscreensreviewreviewviewmodelkt)
111. [main\java\com\yourname\expensetracker\ui\screens\transactions\TransactionsScreen.kt](#mainjavacomyournameexpensetrackeruiscreenstransactionstransactionsscreenkt)
112. [main\java\com\yourname\expensetracker\ui\screens\transactions\TransactionsViewModel.kt](#mainjavacomyournameexpensetrackeruiscreenstransactionstransactionsviewmodelkt)
113. [main\java\com\yourname\expensetracker\ui\theme\Theme.kt](#mainjavacomyournameexpensetrackeruithemethemekt)
114. [main\java\com\yourname\expensetracker\ui\util\HapticFeedback.kt](#mainjavacomyournameexpensetrackeruiutilhapticfeedbackkt)
115. [main\res\drawable\ic_launcher_background.xml](#mainresdrawableic_launcher_backgroundxml)
116. [main\res\drawable\ic_launcher_foreground.xml](#mainresdrawableic_launcher_foregroundxml)
117. [main\res\mipmap-anydpi-v26\ic_launcher.xml](#mainresmipmap-anydpi-v26ic_launcherxml)
118. [main\res\mipmap-anydpi-v26\ic_launcher_round.xml](#mainresmipmap-anydpi-v26ic_launcher_roundxml)
119. [main\res\mipmap\ic_launcher.xml](#mainresmipmapic_launcherxml)
120. [main\res\mipmap\ic_launcher_round.xml](#mainresmipmapic_launcher_roundxml)
121. [main\res\values\strings.xml](#mainresvaluesstringsxml)
122. [main\res\values\themes.xml](#mainresvaluesthemesxml)
123. [main\res\xml\file_paths.xml](#mainresxmlfile_pathsxml)

---

## main\AndroidManifest.xml <a name="mainandroidmanifestxml"></a>
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <!-- Keep service running -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <!-- NEW: Camera permission for receipt scanning -->
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera" android:required="false" />
    <application
        android:name=".ExpenseTrackerApp"
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.ExpenseTracker">
        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:theme="@style/Theme.ExpenseTracker">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <!-- THE CRITICAL SERVICE -->
        <service
            android:name=".service.NotificationCaptureService"
            android:exported="true"
            android:foregroundServiceType="dataSync"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
        </service>
        <receiver
            android:name=".receiver.BootReceiver"
            android:enabled="true"
            android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>
        <!-- NEW: FileProvider for camera photos -->
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
    </application>
</manifest>

```

---

## main\java\com\yourname\expensetracker\ExpenseTrackerApp.kt <a name="mainjavacomyournameexpensetrackerexpensetrackerappkt"></a>
```kotlin
package com.yourname.expensetracker
import android.app.Application
import android.os.StrictMode
import com.yourname.expensetracker.BuildConfig
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
@HiltAndroidApp
class ExpenseTrackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\data\database\AppDatabase.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseappdatabasekt"></a>
```kotlin
package com.yourname.expensetracker.data.database
import com.yourname.expensetracker.data.database.entity.*
import com.yourname.expensetracker.data.database.dao.*
import androidx.room.*
@Database(
    entities = [
        RawNotification::class, 
        BlockedPackage::class, 
        Expense::class,
        Category::class,
        MerchantCategory::class,
        PendingReview::class,
        UserCorrection::class,
        SourceStats::class,
        Budget::class,
        ScannedReceipt::class,
        ManualRecurringExpense::class,
        PlannedExpense::class,
        SavingsGoal::class,
        MerchantCanonical::class,
        MerchantAlias::class
    ],
    version = 17,
    exportSchema = false
)
@TypeConverters(com.yourname.expensetracker.data.database.converter.Converters::class)
abstract class AppDatabase : RoomDatabase() {
    // ... (DAOs)
    abstract fun rawNotificationDao(): RawNotificationDao
    abstract fun blockedPackageDao(): BlockedPackageDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun categoryDao(): CategoryDao
    abstract fun merchantCategoryDao(): MerchantCategoryDao
    abstract fun pendingReviewDao(): PendingReviewDao
    abstract fun userCorrectionDao(): UserCorrectionDao
    abstract fun sourceStatsDao(): SourceStatsDao
    abstract fun budgetDao(): BudgetDao
    abstract fun scannedReceiptDao(): ScannedReceiptDao
    abstract fun recurringExpenseDao(): RecurringExpenseDao
    abstract fun plannedExpenseDao(): PlannedExpenseDao
    abstract fun savingsGoalDao(): SavingsGoalDao
    abstract fun merchantNormalizationDao(): MerchantNormalizationDao
    companion object {
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE expenses ADD COLUMN paymentMethod TEXT NOT NULL DEFAULT 'UNKNOWN'"
                )
                database.execSQL(
                    "ALTER TABLE expenses ADD COLUMN isManualEntry INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE expenses ADD COLUMN notes TEXT DEFAULT NULL"
                )
            }
        }
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS budgets (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        categoryId INTEGER,
                        amount REAL NOT NULL,
                        period TEXT NOT NULL,
                        startDate INTEGER NOT NULL,
                        isActive INTEGER NOT NULL DEFAULT 1,
                        notifyAtWarning REAL NOT NULL DEFAULT 0.75,
                        notifyAtCritical REAL NOT NULL DEFAULT 0.9,
                        rollover INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        lastWarningNotifiedAt INTEGER,
                        lastCriticalNotifiedAt INTEGER,
                        lastExceededNotifiedAt INTEGER,
                        FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_categoryId ON budgets (categoryId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_isActive ON budgets (isActive)")
            }
        }
        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS scanned_receipts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        imagePath TEXT NOT NULL,
                        rawOcrText TEXT NOT NULL,
                        parsedTotal REAL,
                        parsedMerchant TEXT,
                        parsedDate INTEGER,
                        parsedItems TEXT,
                        parsedTaxAmount REAL,
                        currency TEXT NOT NULL DEFAULT 'EUR',
                        confidence REAL NOT NULL,
                        expenseId INTEGER,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(expenseId) REFERENCES expenses(id) ON DELETE SET NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_scanned_receipts_expenseId ON scanned_receipts (expenseId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_scanned_receipts_createdAt ON scanned_receipts (createdAt)")
            }
        }
        val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // To change NOT NULL constraint in SQLite, we must recreate the table
                database.beginTransaction()
                try {
                    database.execSQL("ALTER TABLE pending_reviews RENAME TO pending_reviews_old")
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS pending_reviews (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            rawNotificationId INTEGER,
                            scannedReceiptId INTEGER,
                            suggestedAmount REAL NOT NULL,
                            suggestedCurrency TEXT NOT NULL,
                            suggestedMerchant TEXT NOT NULL,
                            suggestedType TEXT NOT NULL,
                            suggestedCategoryId INTEGER,
                            confidence REAL NOT NULL,
                            packageName TEXT NOT NULL,
                            notificationTitle TEXT,
                            notificationText TEXT,
                            createdAt INTEGER NOT NULL,
                            status TEXT NOT NULL DEFAULT 'PENDING',
                            FOREIGN KEY(rawNotificationId) REFERENCES raw_notifications(id) ON DELETE SET NULL,
                            FOREIGN KEY(scannedReceiptId) REFERENCES scanned_receipts(id) ON DELETE SET NULL
                        )
                    """.trimIndent())
                    database.execSQL("""
                        INSERT INTO pending_reviews (
                            id, rawNotificationId, suggestedAmount, suggestedCurrency, 
                            suggestedMerchant, suggestedType, suggestedCategoryId, 
                            confidence, packageName, notificationTitle, notificationText, 
                            createdAt, status
                        )
                        SELECT 
                            id, rawNotificationId, suggestedAmount, suggestedCurrency, 
                            suggestedMerchant, suggestedType, suggestedCategoryId, 
                            confidence, packageName, notificationTitle, notificationText, 
                            createdAt, status
                        FROM pending_reviews_old
                    """.trimIndent())
                    database.execSQL("DROP TABLE pending_reviews_old")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_rawNotificationId ON pending_reviews (rawNotificationId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_scannedReceiptId ON pending_reviews (scannedReceiptId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_status ON pending_reviews (status)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_status_createdAt ON pending_reviews (status, createdAt)")
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        }
        val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE pending_reviews ADD COLUMN suggestedDate INTEGER DEFAULT NULL")
            }
        }
        val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS manual_recurring_expenses (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        merchant TEXT NOT NULL,
                        amount REAL NOT NULL,
                        currency TEXT NOT NULL DEFAULT 'EUR',
                        frequency TEXT NOT NULL,
                        nextDate INTEGER NOT NULL,
                        note TEXT,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }
        val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS planned_expenses (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        description TEXT NOT NULL,
                        amount REAL NOT NULL,
                        date INTEGER NOT NULL,
                        categoryId INTEGER,
                        isRecurring INTEGER NOT NULL DEFAULT 0,
                        priority TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_planned_expenses_date ON planned_expenses (date)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_planned_expenses_categoryId ON planned_expenses (categoryId)")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS savings_goals (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        targetAmount REAL NOT NULL,
                        currentAmount REAL NOT NULL DEFAULT 0.0,
                        targetDate INTEGER,
                        protectionLevel TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }
        val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS source_stats (
                        packageName TEXT PRIMARY KEY NOT NULL,
                        totalNotifications INTEGER NOT NULL DEFAULT 0,
                        acceptedAsExpense INTEGER NOT NULL DEFAULT 0,
                        rejectedByUser INTEGER NOT NULL DEFAULT 0,
                        autoRejected INTEGER NOT NULL DEFAULT 0,
                        pendingReview INTEGER NOT NULL DEFAULT 0,
                        lastSeen INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }
        val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // RawNotifications index
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_raw_notifications_isRelevant` ON `raw_notifications` (`isRelevant`)")
                // Expense indices optimization
                database.execSQL("DROP INDEX IF EXISTS `index_expenses_date`")
                database.execSQL("DROP INDEX IF EXISTS `index_expenses_categoryId`")
                database.execSQL("DROP INDEX IF EXISTS `index_expenses_transactionType`")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_transactionType_merchant` ON `expenses` (`transactionType`, `merchant`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_transactionType_categoryId_date` ON `expenses` (`transactionType`, `categoryId`, `date`)")
            }
        }
        val MIGRATION_15_16 = object : androidx.room.migration.Migration(15, 16) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1. UserCorrections: Recreate table to add Foreign Keys and Indices, and new columns
                database.beginTransaction()
                try {
                    // Create new table with updated schema (FKs + Indices support)
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS user_corrections_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            packageName TEXT NOT NULL,
                            originalMerchant TEXT NOT NULL,
                            correctedMerchant TEXT,
                            originalAmount REAL NOT NULL,
                            correctedAmount REAL,
                            originalCategoryId INTEGER,
                            correctedCategoryId INTEGER,
                            wasRejected INTEGER NOT NULL DEFAULT 0,
                            wasApproved INTEGER NOT NULL DEFAULT 0,
                            notificationTitle TEXT,
                            notificationText TEXT,
                            createdAt INTEGER NOT NULL,
                            FOREIGN KEY(originalCategoryId) REFERENCES categories(id) ON DELETE SET NULL,
                            FOREIGN KEY(correctedCategoryId) REFERENCES categories(id) ON DELETE SET NULL
                        )
                    """.trimIndent())
                    // Copy data from old table
                    // We assume originalCategoryId and correctedCategoryId are new columns, so we don't migrate them.
                    database.execSQL("""
                        INSERT INTO user_corrections_new (
                            id, packageName, originalMerchant, correctedMerchant, 
                            originalAmount, correctedAmount, wasRejected, wasApproved, 
                            notificationTitle, notificationText, createdAt
                        )
                        SELECT 
                            id, packageName, originalMerchant, correctedMerchant, 
                            originalAmount, correctedAmount, wasRejected, wasApproved, 
                            notificationTitle, notificationText, createdAt
                        FROM user_corrections
                    """.trimIndent())
                    // Swap tables
                    database.execSQL("DROP TABLE user_corrections")
                    database.execSQL("ALTER TABLE user_corrections_new RENAME TO user_corrections")
                    // Create indices for UserCorrection
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_user_corrections_originalCategoryId ON user_corrections (originalCategoryId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_user_corrections_correctedCategoryId ON user_corrections (correctedCategoryId)")
                    // 2. Expenses: Update Indices to match Expense.kt
                    // Drop obsolete indices
                    database.execSQL("DROP INDEX IF EXISTS index_expenses_transactionType_merchant")
                    database.execSQL("DROP INDEX IF EXISTS index_expenses_date_transactionType") // Fixes schema mismatch crash
                    // Create new/updated indices
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_rawNotificationId ON expenses (rawNotificationId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_transactionType_date ON expenses (transactionType, date)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_categoryId_date ON expenses (categoryId, date)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_amount_merchant_date ON expenses (amount, merchant, date)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_merchant_date ON expenses (merchant, date)")
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        }
        val MIGRATION_16_17 = object : androidx.room.migration.Migration(16, 17) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Create canonical merchants table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS merchant_canonicals (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        normalizedName TEXT NOT NULL,
                        searchKey TEXT NOT NULL,
                        categoryId INTEGER,
                        totalOccurrences INTEGER NOT NULL DEFAULT 0,
                        totalSpent REAL NOT NULL DEFAULT 0.0,
                        isVerified INTEGER NOT NULL DEFAULT 0,
                        logoUrl TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL
                    )
                """)
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_merchant_canonicals_normalizedName ON merchant_canonicals (normalizedName)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_merchant_canonicals_searchKey ON merchant_canonicals (searchKey)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_merchant_canonicals_categoryId ON merchant_canonicals (categoryId)")
                // Create merchant aliases table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS merchant_aliases (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        rawName TEXT NOT NULL,
                        normalizedKey TEXT NOT NULL,
                        canonicalId INTEGER NOT NULL,
                        occurrenceCount INTEGER NOT NULL DEFAULT 1,
                        isUserDefined INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        lastUsedAt INTEGER NOT NULL,
                        FOREIGN KEY(canonicalId) REFERENCES merchant_canonicals(id) ON DELETE CASCADE
                    )
                """)
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_merchant_aliases_rawName ON merchant_aliases (rawName)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_merchant_aliases_normalizedKey ON merchant_aliases (normalizedKey)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_merchant_aliases_canonicalId ON merchant_aliases (canonicalId)")
                // Migrate existing merchants
                val now = System.currentTimeMillis()
                database.execSQL("""
                    INSERT INTO merchant_canonicals (normalizedName, searchKey, categoryId, totalOccurrences, totalSpent, isVerified, createdAt, updatedAt)
                    SELECT 
                        merchant as normalizedName,
                        LOWER(REPLACE(REPLACE(REPLACE(merchant, '.', ''), '''', ''), ' ', '')) as searchKey,
                        categoryId,
                        COUNT(*) as totalOccurrences,
                        SUM(amount) as totalSpent,
                        0 as isVerified,
                        $now as createdAt,
                        $now as updatedAt
                    FROM expenses
                    WHERE merchant IS NOT NULL AND transactionType IN ('PURCHASE', 'WITHDRAWAL', 'TRANSFER')
                    GROUP BY merchant
                """)
                database.execSQL("""
                    INSERT INTO merchant_aliases (rawName, normalizedKey, canonicalId, occurrenceCount, isUserDefined, createdAt, lastUsedAt)
                    SELECT 
                        mc.normalizedName as rawName,
                        mc.searchKey as normalizedKey,
                        mc.id as canonicalId,
                        mc.totalOccurrences as occurrenceCount,
                        0 as isUserDefined,
                        mc.createdAt,
                        mc.updatedAt as lastUsedAt
                    FROM merchant_canonicals mc
                """)
            }
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\data\database\converter\Converters.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseconverterconverterskt"></a>
```kotlin
package com.yourname.expensetracker.data.database.converter
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import androidx.room.TypeConverter
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.PaymentMethod
class Converters {
    @TypeConverter
    fun fromTransactionType(value: TransactionType): String {
        return value.name
    }
    @TypeConverter
    fun toTransactionType(value: String): TransactionType {
        return try {
            TransactionType.valueOf(value)
        } catch (e: IllegalArgumentException) {
            TransactionType.UNKNOWN
        }
    }
    @TypeConverter
    fun fromPaymentMethod(value: PaymentMethod): String {
        return value.name
    }
    @TypeConverter
    fun toPaymentMethod(value: String): PaymentMethod {
        return try {
            PaymentMethod.valueOf(value)
        } catch (e: IllegalArgumentException) {
            PaymentMethod.UNKNOWN
        }
    }
    @TypeConverter
    fun fromBudgetPeriod(value: com.yourname.expensetracker.data.database.entity.BudgetPeriod): String {
        return value.name
    }
    @TypeConverter
    fun toBudgetPeriod(value: String): com.yourname.expensetracker.data.database.entity.BudgetPeriod {
        return try {
            com.yourname.expensetracker.data.database.entity.BudgetPeriod.valueOf(value)
        } catch (e: IllegalArgumentException) {
            com.yourname.expensetracker.data.database.entity.BudgetPeriod.MONTHLY
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\data\database\dao\BlockedPackageDao.kt <a name="mainjavacomyournameexpensetrackerdatadatabasedaoblockedpackagedaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao
import androidx.room.*
import com.yourname.expensetracker.data.database.entity.BlockedPackage
import kotlinx.coroutines.flow.Flow
@Dao
interface BlockedPackageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun block(blockedPackage: BlockedPackage)
    @Delete
    suspend fun unblock(blockedPackage: BlockedPackage)
    @Query("DELETE FROM blocked_packages WHERE packageName = :packageName")
    suspend fun unblock(packageName: String)
    @Query("SELECT * FROM blocked_packages")
    fun getAllFlow(): Flow<List<BlockedPackage>>
    @Query("SELECT packageName FROM blocked_packages")
    fun getAllPackageNamesFlow(): Flow<List<String>>
    @Query("SELECT EXISTS(SELECT 1 FROM blocked_packages WHERE packageName = :packageName)")
    suspend fun isBlocked(packageName: String): Boolean
    @Query("DELETE FROM blocked_packages")
    suspend fun deleteAll()
}

```

---

## main\java\com\yourname\expensetracker\data\database\dao\BudgetDao.kt <a name="mainjavacomyournameexpensetrackerdatadatabasedaobudgetdaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao
import androidx.room.*
import com.yourname.expensetracker.data.database.entity.Budget
import kotlinx.coroutines.flow.Flow
@Dao
interface BudgetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(budget: Budget): Long
    @Update
    suspend fun update(budget: Budget)
    @Delete
    suspend fun delete(budget: Budget)
    @Query("SELECT * FROM budgets WHERE id = :id")
    suspend fun getById(id: Long): Budget?
    @Query("SELECT * FROM budgets")
    suspend fun getAll(): List<Budget>
    @Query("SELECT * FROM budgets")
    fun getAllFlow(): Flow<List<Budget>>
    @Query("SELECT * FROM budgets WHERE isActive = 1")
    suspend fun getActiveBudgets(): List<Budget>
    @Query("SELECT * FROM budgets WHERE isActive = 1")
    fun getActiveBudgetsFlow(): Flow<List<Budget>>
    @Query("SELECT * FROM budgets WHERE categoryId IS NULL AND isActive = 1 LIMIT 1")
    suspend fun getOverallBudget(): Budget?
    @Query("SELECT * FROM budgets WHERE categoryId = :categoryId AND isActive = 1 LIMIT 1")
    suspend fun getByCategory(categoryId: Long): Budget?
    @Query("UPDATE budgets SET lastWarningNotifiedAt = :timestamp WHERE id = :id")
    suspend fun updateWarningNotification(id: Long, timestamp: Long)
    @Query("UPDATE budgets SET lastCriticalNotifiedAt = :timestamp WHERE id = :id")
    suspend fun updateCriticalNotification(id: Long, timestamp: Long)
    @Query("UPDATE budgets SET lastExceededNotifiedAt = :timestamp WHERE id = :id")
    suspend fun updateExceededNotification(id: Long, timestamp: Long)
    @Query("UPDATE budgets SET isActive = :isActive WHERE id = :id")
    suspend fun setActive(id: Long, isActive: Boolean)
    @Query("DELETE FROM budgets")
    suspend fun deleteAll()
}

```

---

## main\java\com\yourname\expensetracker\data\database\dao\CategoryDao.kt <a name="mainjavacomyournameexpensetrackerdatadatabasedaocategorydaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.yourname.expensetracker.data.database.entity.Category
import kotlinx.coroutines.flow.Flow
@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY isDefault DESC, name ASC")
    fun getAllFlow(): Flow<List<Category>>
    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): Category?
    @Query("SELECT * FROM categories WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Category>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: Category): Long
    @Update
    suspend fun update(category: Category)
    @Delete
    suspend fun delete(category: Category)
    @Query("SELECT COUNT(*) FROM categories")
    suspend fun getCount(): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<Category>)
    @Query("SELECT * FROM categories ORDER BY isDefault DESC, name ASC")
    suspend fun getAll(): List<Category>
}

```

---

## main\java\com\yourname\expensetracker\data\database\dao\ExpenseDao.kt <a name="mainjavacomyournameexpensetrackerdatadatabasedaoexpensedaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import androidx.room.*
import kotlinx.coroutines.flow.Flow
@Dao
interface ExpenseDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(expense: Expense): Long
    @Query("SELECT * FROM expenses ORDER BY date DESC")
    fun getAllFlow(): Flow<List<Expense>>
    @Transaction
    @Query("SELECT * FROM expenses ORDER BY date DESC LIMIT :limit")
    fun getAllWithCategoryFlow(limit: Int = 200): Flow<List<ExpenseWithCategory>>
    @Transaction
    @Query("SELECT * FROM expenses ORDER BY date DESC LIMIT :limit OFFSET :offset")
    suspend fun getExpensesWithCategoryPaged(limit: Int, offset: Int): List<ExpenseWithCategory>
    @Transaction
    @Query("SELECT * FROM expenses WHERE date >= :startMs AND date <= :endMs ORDER BY date DESC")
    fun getExpensesWithCategoryInPeriodFlow(startMs: Long, endMs: Long): Flow<List<ExpenseWithCategory>>
    @Query("SELECT * FROM expenses ORDER BY date DESC")
    suspend fun getAll(): List<Expense>
    @Query("SELECT SUM(amount) FROM expenses WHERE transactionType = 'PURCHASE'")
    fun getTotalSpentFlow(): Flow<Double?>
    @Query("DELETE FROM expenses")
    suspend fun deleteAll()
    @Delete
    suspend fun delete(expense: Expense)
    @Query("UPDATE expenses SET categoryId = :categoryId WHERE id = :expenseId")
    suspend fun updateCategory(expenseId: Long, categoryId: Long)
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM expenses 
            WHERE (ABS(amount - :amount) < 0.01 OR ABS(amount - :amount) / amount < 0.001)
            AND merchant = :merchant 
            AND ABS(date - :date) <= :windowMs
        )
    """)
    suspend fun isDuplicate(amount: Double, merchant: String, date: Long, windowMs: Long = 300000): Boolean
    @Query("""
        SELECT COALESCE(SUM(amount), 0.0) FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND categoryId = :categoryId 
        AND date >= :startMs AND date < :endMs
    """)
    suspend fun getCategorySpentInPeriod(categoryId: Long, startMs: Long, endMs: Long): Double
    @Query("""
        SELECT COALESCE(SUM(amount), 0.0) FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND categoryId = :categoryId 
        AND date >= :startMs AND date < :endMs
    """)
    fun getCategorySpentInPeriodFlow(categoryId: Long, startMs: Long, endMs: Long): Flow<Double>
    // === Merchant Search for Manual Entry ===
    @Query("""
        SELECT merchant, categoryId, AVG(amount) as avgAmount, COUNT(*) as txCount
        FROM expenses
        WHERE UPPER(merchant) LIKE '%' || UPPER(:query) || '%'
        GROUP BY UPPER(merchant)
        ORDER BY txCount DESC
        LIMIT 10
    """)
    suspend fun searchMerchants(query: String): List<MerchantSuggestion>
    @Query("""
        SELECT DISTINCT merchant
        FROM expenses
        ORDER BY date DESC
        LIMIT 100
    """)
    suspend fun getRecentMerchantNames(): List<String>
    // === Analytics Queries ===
    @Query("SELECT * FROM expenses WHERE date >= :startDate AND date <= :endDate ORDER BY date DESC")
    suspend fun getExpensesBetween(startDate: Long, endDate: Long): List<Expense>
    @Query("SELECT * FROM expenses WHERE date >= :startDate AND date <= :endDate ORDER BY date DESC")
    fun getExpensesBetweenFlow(startDate: Long, endDate: Long): Flow<List<Expense>>
    @Query("""
        SELECT SUM(amount) FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startDate AND date <= :endDate
    """)
    suspend fun getTotalSpentBetween(startDate: Long, endDate: Long): Double?
    @Query("""
        SELECT merchant, SUM(amount) as total, COUNT(*) as cnt 
        FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startDate AND date <= :endDate
        GROUP BY UPPER(merchant)
        ORDER BY total DESC
    """)
    suspend fun getMerchantTotalsBetween(startDate: Long, endDate: Long): List<MerchantTotal>
    @Query("""
        SELECT categoryId, SUM(amount) as total, COUNT(*) as txCount
        FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startDate AND date <= :endDate
        AND categoryId IS NOT NULL
        GROUP BY categoryId
        ORDER BY total DESC
    """)
    suspend fun getCategoryTotalsBetween(startDate: Long, endDate: Long): List<CategoryTotal>
    @Query("SELECT COUNT(*) FROM expenses WHERE transactionType = 'PURCHASE'")
    suspend fun getPurchaseCount(): Int
    @Query("SELECT MIN(date) FROM expenses")
    suspend fun getOldestExpenseDate(): Long?
    // === Tier 1 & 2 Analytics Queries ===
    // Monthly total for a specific month range
    @Query("""
        SELECT COALESCE(SUM(amount), 0.0) FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
    """)
    suspend fun getTotalForPeriod(startMs: Long, endMs: Long): Double
    // Count for a period
    @Query("""
        SELECT COUNT(*) FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
    """)
    suspend fun getCountForPeriod(startMs: Long, endMs: Long): Int
    // Category totals for a period
    @Query("""
        SELECT categoryId, SUM(amount) as total, COUNT(*) as txCount
        FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
        AND categoryId IS NOT NULL
        GROUP BY categoryId
        ORDER BY total DESC
    """)
    suspend fun getCategoryTotalsForPeriod(startMs: Long, endMs: Long): List<CategoryTotal>
    // Merchant averages (merchants with 2+ transactions)
    @Query("""
        SELECT merchant, AVG(amount) as avgAmount, 
               MIN(amount) as minAmount, MAX(amount) as maxAmount,
               COUNT(*) as txCount, SUM(amount) as totalAmount
        FROM expenses 
        WHERE transactionType = 'PURCHASE'
        GROUP BY merchant
        HAVING txCount >= 2
        ORDER BY totalAmount DESC
    """)
    suspend fun getMerchantStats(): List<MerchantStats>
    // All merchant stats (including single-transaction merchants)
    @Query("""
        SELECT merchant, AVG(amount) as avgAmount, 
               MIN(amount) as minAmount, MAX(amount) as maxAmount,
               COUNT(*) as txCount, SUM(amount) as totalAmount
        FROM expenses 
        WHERE transactionType = 'PURCHASE'
        GROUP BY merchant
        ORDER BY totalAmount DESC
    """)
    suspend fun getAllMerchantStats(): List<MerchantStats>
    // Top merchants by total spending for a period
    @Query("""
        SELECT merchant, AVG(amount) as avgAmount, 
               MIN(amount) as minAmount, MAX(amount) as maxAmount,
               COUNT(*) as txCount, SUM(amount) as totalAmount
        FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
        GROUP BY merchant
        ORDER BY totalAmount DESC
        LIMIT :limit
    """)
    suspend fun getTopMerchantsForPeriod(startMs: Long, endMs: Long, limit: Int = 10): List<MerchantStats>
    // Largest single transaction in a period
    @Query("""
        SELECT * FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
        ORDER BY amount DESC
        LIMIT 1
    """)
    suspend fun getLargestExpenseForPeriod(startMs: Long, endMs: Long): Expense?
    // Largest single transaction for a specific merchant in a period
    @Query("""
        SELECT * FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
        AND merchant = :merchant
        ORDER BY amount DESC
        LIMIT 1
    """)
    suspend fun getLargestExpenseForMerchant(merchant: String, startMs: Long, endMs: Long): Expense?
    // Daily spending totals for a period (for pace calculation)
    @Query("""
        SELECT (date / 86400000) as dayEpoch, SUM(amount) as total, COUNT(*) as txCount
        FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
        GROUP BY dayEpoch
        ORDER BY dayEpoch ASC
    """)
    suspend fun getDailyTotalsForPeriod(startMs: Long, endMs: Long): List<DailyTotal>
    // Recurring candidates: merchants that appear in multiple distinct months
    @Query("""
        SELECT merchant, 
               AVG(amount) as avgAmount, 
               MIN(amount) as minAmount, 
               MAX(amount) as maxAmount,
               COUNT(*) as txCount, 
               SUM(amount) as totalAmount
        FROM expenses 
        WHERE transactionType = 'PURCHASE'
        GROUP BY merchant
        HAVING txCount >= 2 
        AND (maxAmount - minAmount) < (avgAmount * 0.15)
        ORDER BY txCount DESC
    """)
    suspend fun getRecurringCandidates(): List<MerchantStats>
    // Day-of-week spending pattern
    @Query("""
        SELECT 
            CAST((((date + :timeZoneOffset) / 1000 + 259200) % 604800) / 86400 AS INTEGER) as dayOfWeek,
            SUM(amount) as total,
            COUNT(*) as txCount,
            AVG(amount) as avgAmount
        FROM expenses
        WHERE transactionType = 'PURCHASE'
        AND date >= :startMs AND date < :endMs
        GROUP BY dayOfWeek
        ORDER BY dayOfWeek ASC
    """)
    suspend fun getDayOfWeekPattern(startMs: Long, endMs: Long, timeZoneOffset: Int): List<DayOfWeekTotal>
}
data class MerchantSuggestion(
    val merchant: String,
    val categoryId: Long?,
    val avgAmount: Double,
    val txCount: Int
)
data class MerchantTotal(
    val merchant: String,
    val total: Double,
    val cnt: Int
)
data class CategoryTotal(
    val categoryId: Long,
    val total: Double,
    val txCount: Int = 0 
)
data class MerchantStats(
    val merchant: String,
    val avgAmount: Double,
    val minAmount: Double,
    val maxAmount: Double,
    val txCount: Int,
    val totalAmount: Double
)
data class DailyTotal(
    val dayEpoch: Long,
    val total: Double,
    val txCount: Int
)
data class DayOfWeekTotal(
    val dayOfWeek: Int,
    val total: Double,
    val txCount: Int,
    val avgAmount: Double
)

```

---

## main\java\com\yourname\expensetracker\data\database\dao\MerchantCategoryDao.kt <a name="mainjavacomyournameexpensetrackerdatadatabasedaomerchantcategorydaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.MerchantCategory
@Dao
interface MerchantCategoryDao {
    @Query("SELECT * FROM merchant_categories WHERE merchantPattern = :merchantPattern")
    suspend fun getCategoryForMerchant(merchantPattern: String): MerchantCategory?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(merchantCategory: MerchantCategory)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(merchantCategories: List<MerchantCategory>)
    @Query("SELECT * FROM merchant_categories")
    suspend fun getAll(): List<MerchantCategory>
    @Query("DELETE FROM merchant_categories")
    suspend fun deleteAll()
}

```

---

## main\java\com\yourname\expensetracker\data\database\dao\MerchantNormalizationDao.kt <a name="mainjavacomyournameexpensetrackerdatadatabasedaomerchantnormalizationdaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao
import androidx.room.*
import com.yourname.expensetracker.data.database.entity.MerchantAlias
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
/**
 * DAO for merchant normalization tables.
 */
@Dao
interface MerchantNormalizationDao {
    // ==================== Canonical Merchants ====================
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCanonical(merchant: MerchantCanonical): Long
    @Update
    suspend fun updateCanonical(merchant: MerchantCanonical)
    @Query("SELECT * FROM merchant_canonicals WHERE id = :id")
    suspend fun getCanonicalById(id: Long): MerchantCanonical?
    @Query("SELECT * FROM merchant_canonicals WHERE searchKey = :searchKey LIMIT 1")
    suspend fun getCanonicalBySearchKey(searchKey: String): MerchantCanonical?
    @Query("SELECT * FROM merchant_canonicals WHERE normalizedName = :name LIMIT 1")
    suspend fun getCanonicalByName(name: String): MerchantCanonical?
    @Query("SELECT * FROM merchant_canonicals ORDER BY totalOccurrences DESC")
    suspend fun getAllCanonicals(): List<MerchantCanonical>
    @Query("SELECT * FROM merchant_canonicals ORDER BY totalOccurrences DESC LIMIT :limit")
    suspend fun getTopMerchants(limit: Int): List<MerchantCanonical>
    @Query("UPDATE merchant_canonicals SET categoryId = :categoryId WHERE id = :id")
    suspend fun updateCanonicalCategory(id: Long, categoryId: Long?)
    @Query("UPDATE merchant_canonicals SET totalOccurrences = totalOccurrences + 1, totalSpent = totalSpent + :amount, updatedAt = :timestamp WHERE id = :id")
    suspend fun incrementMerchantStats(id: Long, amount: Double, timestamp: Long = System.currentTimeMillis())
    // ==================== Merchant Aliases ====================
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlias(alias: MerchantAlias): Long
    @Update
    suspend fun updateAlias(alias: MerchantAlias)
    @Query("SELECT * FROM merchant_aliases WHERE id = :id")
    suspend fun getAliasById(id: Long): MerchantAlias?
    @Query("SELECT * FROM merchant_aliases WHERE rawName = :rawName LIMIT 1")
    suspend fun getAliasByRawName(rawName: String): MerchantAlias?
    @Query("SELECT * FROM merchant_aliases WHERE normalizedKey = :normalizedKey LIMIT 1")
    suspend fun getAliasByNormalizedKey(normalizedKey: String): MerchantAlias?
    @Query("SELECT * FROM merchant_aliases WHERE canonicalId = :canonicalId")
    suspend fun getAliasesForCanonical(canonicalId: Long): List<MerchantAlias>
    @Query("""
        SELECT * FROM merchant_aliases 
        WHERE normalizedKey LIKE '%' || :query || '%'
        ORDER BY occurrenceCount DESC
        LIMIT :limit
    """)
    suspend fun searchAliases(query: String, limit: Int = 20): List<MerchantAlias>
    @Query("DELETE FROM merchant_aliases WHERE lastUsedAt < :olderThan")
    suspend fun deleteUnusedAliasesOlderThan(olderThan: Long): Int
    // ==================== Combined Operations ====================
    @Transaction
    suspend fun linkAliasToCanonical(rawName: String, canonicalId: Long, isUserDefined: Boolean = false) {
        val normalizedKey = rawName.lowercase().trim()
            .replace(Regex("[^a-z0-9α-ωά-ώ]"), "")
        val existing = getAliasByRawName(rawName)
        if (existing != null) {
            updateAlias(existing.copy(
                canonicalId = canonicalId,
                isUserDefined = isUserDefined || existing.isUserDefined,
                occurrenceCount = existing.occurrenceCount + 1,
                lastUsedAt = System.currentTimeMillis()
            ))
        } else {
            insertAlias(MerchantAlias(
                rawName = rawName,
                normalizedKey = normalizedKey,
                canonicalId = canonicalId,
                isUserDefined = isUserDefined
            ))
        }
    }
    @Query("SELECT COUNT(*) FROM merchant_canonicals")
    suspend fun getCanonicalCount(): Int
}

```

---

## main\java\com\yourname\expensetracker\data\database\dao\PendingReviewDao.kt <a name="mainjavacomyournameexpensetrackerdatadatabasedaopendingreviewdaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao
import androidx.room.*
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import kotlinx.coroutines.flow.Flow
@Dao
interface PendingReviewDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(review: PendingReview): Long
    @Update
    suspend fun update(review: PendingReview)
    @Delete
    suspend fun delete(review: PendingReview)
    @Transaction
    @Query("SELECT * FROM pending_reviews WHERE status = 'PENDING' ORDER BY createdAt DESC LIMIT :limit")
    fun getPendingFlow(limit: Int = 100): Flow<List<PendingReviewWithReceipt>>
    @Transaction
    @Query("SELECT * FROM pending_reviews WHERE status = 'PENDING' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getPending(limit: Int = 500): List<PendingReviewWithReceipt>
    @Query("SELECT COUNT(*) FROM pending_reviews WHERE status = 'PENDING'")
    fun getPendingCountFlow(): Flow<Int>
    @Query("SELECT * FROM pending_reviews WHERE id = :id")
    suspend fun getById(id: Long): PendingReview?
    @Query("SELECT * FROM pending_reviews WHERE rawNotificationId = :rawId")
    suspend fun getByRawId(rawId: Long): PendingReview?
    @Query("DELETE FROM pending_reviews WHERE rawNotificationId = :rawId")
    suspend fun deleteByRawId(rawId: Long)
    @Query("UPDATE pending_reviews SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)
    @Query("UPDATE pending_reviews SET status = :status WHERE id = :id AND status = 'PENDING'")
    suspend fun updateStatusIfPending(id: Long, status: String): Int
    @Query("SELECT * FROM pending_reviews ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<PendingReview>>
    @Query("DELETE FROM pending_reviews WHERE status != 'PENDING'")
    suspend fun clearResolved()
    @Query("DELETE FROM pending_reviews")
    suspend fun deleteAll()
}

```

---

## main\java\com\yourname\expensetracker\data\database\dao\PlannedExpenseDao.kt <a name="mainjavacomyournameexpensetrackerdatadatabasedaoplannedexpensedaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao
import androidx.room.*
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import kotlinx.coroutines.flow.Flow
@Dao
interface PlannedExpenseDao {
    @Query("SELECT * FROM planned_expenses ORDER BY date ASC")
    fun getAllPlannedExpenses(): Flow<List<PlannedExpense>>
    @Query("SELECT * FROM planned_expenses WHERE date BETWEEN :startMs AND :endMs")
    fun getPlannedExpensesForPeriod(startMs: Long, endMs: Long): Flow<List<PlannedExpense>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlannedExpense(expense: PlannedExpense): Long
    @Delete
    suspend fun deletePlannedExpense(expense: PlannedExpense)
    @Query("DELETE FROM planned_expenses WHERE id = :id")
    suspend fun deletePlannedExpenseById(id: Long)
}

```

---

## main\java\com\yourname\expensetracker\data\database\dao\RawNotificationDao.kt <a name="mainjavacomyournameexpensetrackerdatadatabasedaorawnotificationdaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao
import androidx.room.*
import com.yourname.expensetracker.data.database.entity.RawNotification
import kotlinx.coroutines.flow.Flow
@Dao
interface RawNotificationDao {
    @Insert
    suspend fun insert(notification: RawNotification): Long
    @Query("SELECT * FROM raw_notifications WHERE id = :id")
    suspend fun getById(id: Long): RawNotification?
    @Query("SELECT * FROM raw_notifications ORDER BY capturedAt DESC")
    fun getAllFlow(): Flow<List<RawNotification>>
    @Query("SELECT * FROM raw_notifications ORDER BY capturedAt DESC LIMIT :limit")
    fun getRecentFlow(limit: Int): Flow<List<RawNotification>>
    @Query("SELECT * FROM raw_notifications WHERE packageName = :packageName ORDER BY capturedAt DESC")
    fun getByPackageFlow(packageName: String): Flow<List<RawNotification>>
    @Query("SELECT DISTINCT packageName FROM raw_notifications ORDER BY packageName")
    fun getAllPackagesFlow(): Flow<List<String>>
    @Query("SELECT COUNT(*) FROM raw_notifications")
    fun getCountFlow(): Flow<Int>
    @Query("DELETE FROM raw_notifications")
    suspend fun deleteAll()
    @Delete
    suspend fun delete(notification: RawNotification)
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM raw_notifications 
            WHERE packageName = :packageName 
            AND timestamp = :timestamp 
            AND (title = :title OR (:title IS NULL AND title IS NULL))
            AND (text = :text OR (:text IS NULL AND text IS NULL))
        )
    """)
    suspend fun exists(packageName: String, timestamp: Long, title: String?, text: String?): Boolean
    @Query("UPDATE raw_notifications SET isRelevant = :isRelevant WHERE id = :id")
    suspend fun markRelevance(id: Long, isRelevant: Boolean)
}

```

---

## main\java\com\yourname\expensetracker\data\database\dao\RecurringExpenseDao.kt <a name="mainjavacomyournameexpensetrackerdatadatabasedaorecurringexpensedaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao
import androidx.room.*
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import kotlinx.coroutines.flow.Flow
@Dao
interface RecurringExpenseDao {
    @Query("SELECT * FROM manual_recurring_expenses ORDER BY nextDate ASC")
    fun getAllFlow(): Flow<List<ManualRecurringExpense>>
    @Query("SELECT * FROM manual_recurring_expenses")
    suspend fun getAll(): List<ManualRecurringExpense>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(expense: ManualRecurringExpense): Long
    @Update
    suspend fun update(expense: ManualRecurringExpense)
    @Delete
    suspend fun delete(expense: ManualRecurringExpense)
    @Query("SELECT * FROM manual_recurring_expenses WHERE merchant = :merchant LIMIT 1")
    suspend fun getByMerchant(merchant: String): ManualRecurringExpense?
    @Query("DELETE FROM manual_recurring_expenses WHERE id = :id")
    suspend fun deleteById(id: Long)
}

```

---

## main\java\com\yourname\expensetracker\data\database\dao\SavingsGoalDao.kt <a name="mainjavacomyournameexpensetrackerdatadatabasedaosavingsgoaldaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao
import androidx.room.*
import com.yourname.expensetracker.data.database.entity.SavingsGoal
import kotlinx.coroutines.flow.Flow
@Dao
interface SavingsGoalDao {
    @Query("SELECT * FROM savings_goals")
    fun getAllGoals(): Flow<List<SavingsGoal>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGoal(goal: SavingsGoal): Long
    @Delete
    suspend fun deleteGoal(goal: SavingsGoal)
}

```

---

## main\java\com\yourname\expensetracker\data\database\dao\ScannedReceiptDao.kt <a name="mainjavacomyournameexpensetrackerdatadatabasedaoscannedreceiptdaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao
import androidx.room.*
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import kotlinx.coroutines.flow.Flow
@Dao
interface ScannedReceiptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(receipt: ScannedReceipt): Long
    @Update
    suspend fun update(receipt: ScannedReceipt)
    @Delete
    suspend fun delete(receipt: ScannedReceipt)
    @Query("SELECT * FROM scanned_receipts ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<ScannedReceipt>>
    @Query("SELECT * FROM scanned_receipts ORDER BY createdAt DESC")
    suspend fun getAll(): List<ScannedReceipt>
    @Query("SELECT * FROM scanned_receipts WHERE id = :id")
    suspend fun getById(id: Long): ScannedReceipt?
    @Query("SELECT * FROM scanned_receipts WHERE expenseId = :expenseId")
    suspend fun getByExpenseId(expenseId: Long): ScannedReceipt?
    @Query("SELECT COUNT(*) FROM scanned_receipts")
    suspend fun getCount(): Int
    @Query("DELETE FROM scanned_receipts")
    suspend fun deleteAll()
    @Query("UPDATE scanned_receipts SET expenseId = :expenseId WHERE id = :receiptId")
    suspend fun linkToExpense(receiptId: Long, expenseId: Long)
}

```

---

## main\java\com\yourname\expensetracker\data\database\dao\SourceStatsDao.kt <a name="mainjavacomyournameexpensetrackerdatadatabasedaosourcestatsdaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao
import androidx.room.*
import com.yourname.expensetracker.data.database.entity.SourceStats
import kotlinx.coroutines.flow.Flow
@Dao
interface SourceStatsDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNotExists(stats: SourceStats)
    @Query("SELECT * FROM source_stats WHERE packageName = :packageName")
    suspend fun getByPackage(packageName: String): SourceStats?
    @Query("SELECT * FROM source_stats ORDER BY totalNotifications DESC")
    fun getAllFlow(): Flow<List<SourceStats>>
    @Query("SELECT * FROM source_stats ORDER BY totalNotifications DESC")
    suspend fun getAll(): List<SourceStats>
    @Query("""
        UPDATE source_stats 
        SET totalNotifications = totalNotifications + 1, 
            lastSeen = :now 
        WHERE packageName = :packageName
    """)
    suspend fun incrementTotal(packageName: String, now: Long = System.currentTimeMillis())
    @Query("""
        UPDATE source_stats 
        SET acceptedAsExpense = acceptedAsExpense + 1 
        WHERE packageName = :packageName
    """)
    suspend fun incrementAccepted(packageName: String)
    @Query("""
        UPDATE source_stats 
        SET rejectedByUser = rejectedByUser + 1 
        WHERE packageName = :packageName
    """)
    suspend fun incrementRejected(packageName: String)
    @Query("""
        UPDATE source_stats 
        SET autoRejected = autoRejected + 1 
        WHERE packageName = :packageName
    """)
    suspend fun incrementAutoRejected(packageName: String)
    @Query("""
        UPDATE source_stats 
        SET pendingReview = pendingReview + 1 
        WHERE packageName = :packageName
    """)
    suspend fun incrementPending(packageName: String)
    @Query("""
        UPDATE source_stats 
        SET pendingReview = MAX(0, pendingReview - 1) 
        WHERE packageName = :packageName
    """)
    suspend fun decrementPending(packageName: String)
    @Query("UPDATE source_stats SET pendingReview = 0")
    suspend fun resetAllPendingCounts()
    @Query("DELETE FROM source_stats")
    suspend fun deleteAll()
}

```

---

## main\java\com\yourname\expensetracker\data\database\dao\UserCorrectionDao.kt <a name="mainjavacomyournameexpensetrackerdatadatabasedaousercorrectiondaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao
import androidx.room.*
import com.yourname.expensetracker.data.database.entity.UserCorrection
import kotlinx.coroutines.flow.Flow
@Dao
interface UserCorrectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(correction: UserCorrection): Long
    @Query("SELECT * FROM user_corrections ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<UserCorrection>>
    @Query("SELECT * FROM user_corrections ORDER BY createdAt DESC")
    suspend fun getAll(): List<UserCorrection>
    @Query("SELECT COUNT(*) FROM user_corrections")
    suspend fun getCount(): Int
    // Get all corrections for a specific package (to learn its patterns)
    @Query("SELECT * FROM user_corrections WHERE packageName = :packageName")
    suspend fun getByPackage(packageName: String): List<UserCorrection>
    // Get rejection rate for a package
    @Query("""
        SELECT COUNT(*) FROM user_corrections 
        WHERE packageName = :packageName AND wasRejected = 1
    """)
    suspend fun getRejectionCount(packageName: String): Int
    @Query("""
        SELECT COUNT(*) FROM user_corrections 
        WHERE packageName = :packageName
    """)
    suspend fun getTotalCorrections(packageName: String): Int
    // Find merchant name corrections (user always renames X to Y)
    @Query("""
        SELECT correctedMerchant 
        FROM user_corrections 
        WHERE originalMerchant = :originalMerchant 
        AND correctedMerchant IS NOT NULL 
        AND correctedMerchant != originalMerchant
        GROUP BY correctedMerchant 
        ORDER BY COUNT(*) DESC 
        LIMIT 1
    """)
    suspend fun getMostCommonMerchantCorrection(originalMerchant: String): String?
    @Query("""
        SELECT COUNT(*) FROM user_corrections 
        WHERE originalMerchant = :merchant
    """)
    suspend fun getMerchantTotalCorrections(merchant: String): Int
    @Query("""
        SELECT COUNT(*) FROM user_corrections 
        WHERE originalMerchant = :merchant AND wasRejected = 1
    """)
    suspend fun getMerchantRejectionCount(merchant: String): Int
    @Query("""
        SELECT correctedCategoryId 
        FROM user_corrections 
        WHERE originalMerchant = :merchant 
        AND correctedCategoryId IS NOT NULL
        GROUP BY correctedCategoryId 
        ORDER BY COUNT(*) DESC 
        LIMIT 1
    """)
    suspend fun getMostCommonCategoryForMerchant(merchant: String): Long?
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM user_corrections 
            WHERE packageName = :packageName 
            AND originalMerchant = :merchant 
            AND wasApproved = 1
        )
    """)
    suspend fun hasPreviousApprovals(merchant: String, packageName: String): Boolean
    @Query("DELETE FROM user_corrections")
    suspend fun deleteAll()
}

```

---

## main\java\com\yourname\expensetracker\data\database\entity\BlockedPackage.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseentityblockedpackagekt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity
import androidx.room.Entity
import androidx.room.PrimaryKey
@Entity(tableName = "blocked_packages")
data class BlockedPackage(
    @PrimaryKey
    val packageName: String,
    val blockedAt: Long = System.currentTimeMillis()
)

```

---

## main\java\com\yourname\expensetracker\data\database\entity\Budget.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseentitybudgetkt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
enum class BudgetPeriod {
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY
}
@Entity(
    tableName = "budgets",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["categoryId"]),
        Index(value = ["isActive"])
    ]
)
data class Budget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long?,              // null = overall budget
    val amount: Double,
    val period: BudgetPeriod,
    val startDate: Long,                // anchor date for period calculation
    val isActive: Boolean = true,
    val notifyAtWarning: Float = 0.75f, // first alert threshold (75%)
    val notifyAtCritical: Float = 0.90f,// second alert threshold (90%)
    val rollover: Boolean = false,      // carry unspent to next period
    val createdAt: Long = System.currentTimeMillis(),
    val lastWarningNotifiedAt: Long? = null,
    val lastCriticalNotifiedAt: Long? = null,
    val lastExceededNotifiedAt: Long? = null
)

```

---

## main\java\com\yourname\expensetracker\data\database\entity\Category.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseentitycategorykt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity
import androidx.room.Entity
import androidx.room.PrimaryKey
@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val icon: String, // Emoji or simple string
    val color: String, // Hex color code
    val isDefault: Boolean = false // If true, cannot be deleted (easily)
)

```

---

## main\java\com\yourname\expensetracker\data\database\entity\Expense.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseentityexpensekt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
@Entity(
    tableName = "expenses",
    foreignKeys = [
        ForeignKey(
            entity = RawNotification::class,
            parentColumns = ["id"],
            childColumns = ["rawNotificationId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["rawNotificationId"]),
        Index(value = ["transactionType", "date"]), // Replaces (date, transactionType) for better filtering
        Index(value = ["transactionType", "categoryId", "date"]), // Covers (categoryId, date) if filtered by type
        Index(value = ["categoryId", "date"]),      // For category breakdown and FK constraint
        Index(value = ["amount", "merchant", "date"]), // High specificity for duplicate check
        Index(value = ["merchant", "date"]) // Necessary for merchant-specific time searches
    ]
)
data class Expense(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val amount: Double,
    val currency: String = "EUR", // ISO 4217 Code
    val merchant: String, // Extracted merchant name
    val transactionType: TransactionType, // PURCHASE, WITHDRAWAL, etc.
    val date: Long, // Transaction date (best guess)
    val rawNotificationId: Long? = null, // Link to source
    val categoryId: Long? = null, // Link to category
    val createdAt: Long = System.currentTimeMillis(),
    // New fields
    val paymentMethod: PaymentMethod = PaymentMethod.UNKNOWN,
    val isManualEntry: Boolean = false,
    val notes: String? = null
)
enum class TransactionType {
    PURCHASE,
    WITHDRAWAL,
    TRANSFER,
    DEPOSIT,
    UNKNOWN
}
enum class PaymentMethod {
    CARD,
    CASH,
    BANK_TRANSFER,
    UNKNOWN
}

```

---

## main\java\com\yourname\expensetracker\data\database\entity\ManualRecurringExpense.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseentitymanualrecurringexpensekt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
@Entity(tableName = "manual_recurring_expenses")
data class ManualRecurringExpense(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val merchant: String,
    val amount: Double,
    val currency: String = "EUR",
    val frequency: RecurrenceFrequency,
    val nextDate: Long,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

```

---

## main\java\com\yourname\expensetracker\data\database\entity\MerchantAlias.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseentitymerchantaliaskt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
@Entity(
    tableName = "merchant_aliases",
    foreignKeys = [
        ForeignKey(
            entity = MerchantCanonical::class,
            parentColumns = ["id"],
            childColumns = ["canonicalId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["rawName"], unique = true),
        Index(value = ["normalizedKey"]),
        Index(value = ["canonicalId"])
    ]
)
data class MerchantAlias(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val rawName: String,        // e.g., "MCDONALD'S #1234"
    val normalizedKey: String,   // e.g., "mcdonalds1234"
    val canonicalId: Long,
    val occurrenceCount: Int = 1,
    val isUserDefined: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis()
)

```

---

## main\java\com\yourname\expensetracker\data\database\entity\MerchantCanonical.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseentitymerchantcanonicalkt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
@Entity(
    tableName = "merchant_canonicals",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["normalizedName"], unique = true),
        Index(value = ["searchKey"]),
        Index(value = ["categoryId"])
    ]
)
data class MerchantCanonical(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val normalizedName: String, // e.g., "McDonald's"
    val searchKey: String,      // e.g., "mcdonalds" (stripped)
    val categoryId: Long? = null,
    val totalOccurrences: Int = 0,
    val totalSpent: Double = 0.0,
    val isVerified: Boolean = false,
    val logoUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

```

---

## main\java\com\yourname\expensetracker\data\database\entity\MerchantCategory.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseentitymerchantcategorykt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
@Entity(
    tableName = "merchant_categories",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["categoryId"])]
)
data class MerchantCategory(
    @PrimaryKey
    val merchantPattern: String, // The normalized merchant name (e.g., "SKLAVENITIS")
    val categoryId: Long,
    val confidence: Float = 1.0f,
    val timesUsed: Int = 1
)

```

---

## main\java\com\yourname\expensetracker\data\database\entity\PendingReview.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseentitypendingreviewkt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
@Entity(
    tableName = "pending_reviews",
    foreignKeys = [
        ForeignKey(
            entity = RawNotification::class,
            parentColumns = ["id"],
            childColumns = ["rawNotificationId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = ScannedReceipt::class,
            parentColumns = ["id"],
            childColumns = ["scannedReceiptId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["rawNotificationId"]),
        Index(value = ["scannedReceiptId"]),
        Index(value = ["status"]),
        Index(value = ["status", "createdAt"])
    ]
)
data class PendingReview(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rawNotificationId: Long?,
    val scannedReceiptId: Long? = null,
    val suggestedAmount: Double,
    val suggestedCurrency: String,
    val suggestedMerchant: String,
    val suggestedType: String,          // TransactionType name
    val suggestedCategoryId: Long?,
    val suggestedDate: Long? = null,    // Added in v11 to preserve parsed date
    val confidence: Float,
    val packageName: String,
    val notificationTitle: String?,
    val notificationText: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "PENDING"      // PENDING, APPROVED, REJECTED, MODIFIED
)

```

---

## main\java\com\yourname\expensetracker\data\database\entity\PlannedExpense.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseentityplannedexpensekt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
@Entity(
    tableName = "planned_expenses",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["date"]),
        Index(value = ["categoryId"])
    ]
)
data class PlannedExpense(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val description: String,
    val amount: Double,
    val date: Long, // Planned date
    val categoryId: Long? = null,
    val isRecurring: Boolean = false,
    val priority: PlannedExpensePriority = PlannedExpensePriority.LIKELY,
    val createdAt: Long = System.currentTimeMillis()
)
enum class PlannedExpensePriority {
    MUST,
    LIKELY,
    OPTIONAL
}

```

---

## main\java\com\yourname\expensetracker\data\database\entity\RawNotification.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseentityrawnotificationkt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
@Entity(
    tableName = "raw_notifications",
    indices = [
        Index(value = ["packageName", "timestamp"]),
        Index(value = ["capturedAt"]), // New: for sorting in Debug screen
        Index(value = ["isRelevant"]) // Optimized for filtering
    ]
)
data class RawNotification(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    // Source app
    val packageName: String,
    val appName: String?,
    // Notification content
    val title: String?,
    val text: String?,
    val bigText: String? = null,          // Expanded notification
    val subText: String? = null,
    // Raw extras as JSON string for debugging
    val extrasJson: String? = null,
    // Metadata
    val timestamp: Long,           // When notification was posted
    val capturedAt: Long,          // When we captured it
    // Processing status
    val isProcessed: Boolean = false,
    val isRelevant: Boolean? = null,  // null = unknown, true = expense, false = ignore
    val parseResult: String? = null    // JSON of parsed data or error message
)

```

---

## main\java\com\yourname\expensetracker\data\database\entity\SavingsGoal.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseentitysavingsgoalkt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity
import androidx.room.Entity
import androidx.room.PrimaryKey
@Entity(tableName = "savings_goals")
data class SavingsGoal(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val targetAmount: Double,
    val currentAmount: Double = 0.0,
    val targetDate: Long? = null,
    val protectionLevel: GoalProtectionLevel = GoalProtectionLevel.WARNING,
    val createdAt: Long = System.currentTimeMillis()
)
enum class GoalProtectionLevel {
    STRICT,  // Fully reserved from discretionary
    WARNING, // Noted but not strictly subtracted
    TRACKING // Just for reference
}

```

---

## main\java\com\yourname\expensetracker\data\database\entity\ScannedReceipt.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseentityscannedreceiptkt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
@Entity(
    tableName = "scanned_receipts",
    foreignKeys = [
        ForeignKey(
            entity = Expense::class,
            parentColumns = ["id"],
            childColumns = ["expenseId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["expenseId"]),
        Index(value = ["createdAt"])
    ]
)
data class ScannedReceipt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imagePath: String,
    val rawOcrText: String,
    val parsedTotal: Double?,
    val parsedMerchant: String?,
    val parsedDate: Long?,
    val parsedItems: String?,        // JSON array of line items
    val parsedTaxAmount: Double?,
    val currency: String = "EUR",
    val confidence: Float,
    val expenseId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

```

---

## main\java\com\yourname\expensetracker\data\database\entity\SourceStats.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseentitysourcestatskt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity
import androidx.room.Entity
import androidx.room.PrimaryKey
@Entity(tableName = "source_stats")
data class SourceStats(
    @PrimaryKey val packageName: String,
    val totalNotifications: Long = 0,
    val acceptedAsExpense: Long = 0,
    val rejectedByUser: Long = 0,
    val autoRejected: Long = 0,
    val pendingReview: Long = 0,
    val lastSeen: Long = System.currentTimeMillis()
) {
    val trustScore: Float
        get() = if (totalNotifications > 0)
            acceptedAsExpense.toFloat() / totalNotifications
        else 0f
    val isLikelySpam: Boolean
        get() = totalNotifications > 10 && trustScore < 0.05f
}

```

---

## main\java\com\yourname\expensetracker\data\database\entity\UserCorrection.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseentityusercorrectionkt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity
import androidx.room.*
@Entity(
    tableName = "user_corrections",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["originalCategoryId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["correctedCategoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("originalCategoryId"),
        Index("correctedCategoryId")
    ]
)
data class UserCorrection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val originalMerchant: String,
    val correctedMerchant: String?,
    val originalAmount: Double,
    val correctedAmount: Double?,
    val originalCategoryId: Long?,
    val correctedCategoryId: Long?,
    val wasRejected: Boolean = false,    // User said "this isn't a transaction"
    val wasApproved: Boolean = false,    // User confirmed it was correct
    val notificationTitle: String?,
    val notificationText: String?,
    val createdAt: Long = System.currentTimeMillis()
)

```

---

## main\java\com\yourname\expensetracker\data\database\model\DashboardWidgetConfig.kt <a name="mainjavacomyournameexpensetrackerdatadatabasemodeldashboardwidgetconfigkt"></a>
```kotlin
package com.yourname.expensetracker.data.database.model
data class DashboardWidgetConfig(
    val id: String,
    val order: Int,
    val isVisible: Boolean = true
)

```

---

## main\java\com\yourname\expensetracker\data\database\model\ExpenseWithCategory.kt <a name="mainjavacomyournameexpensetrackerdatadatabasemodelexpensewithcategorykt"></a>
```kotlin
package com.yourname.expensetracker.data.database.model
import androidx.room.Embedded
import androidx.room.Relation
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import java.text.SimpleDateFormat
import java.util.*
/**
 * Optimized Room model for displaying transactions. 
 * Formatted strings are computed once when the object is instantiated from the DB,
 * preventing expensive re-calculation during LazyColumn scrolling.
 */
data class ExpenseWithCategory(
    @Embedded
    val expense: Expense,
    @Relation(
        parentColumn = "categoryId",
        entityColumn = "id"
    )
    val category: Category?
) {
    // Pre-computed formatting for UI efficiency
    val formattedDate: String by lazy {
        FORMATTER.get()?.format(Date(expense.date)) ?: ""
    }
    val formattedAmount: String by lazy {
        String.format(java.util.Locale.US, "%.2f %s", expense.amount, expense.currency)
    }
    val categoryColor: Long by lazy {
        try {
            category?.color?.let { android.graphics.Color.parseColor(it).toLong() } ?: android.graphics.Color.GRAY.toLong()
        } catch (e: Exception) {
            android.graphics.Color.GRAY.toLong()
        }
    }
    companion object {
        private val FORMATTER = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat {
                return SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
            }
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\data\database\model\PendingReviewWithReceipt.kt <a name="mainjavacomyournameexpensetrackerdatadatabasemodelpendingreviewwithreceiptkt"></a>
```kotlin
package com.yourname.expensetracker.data.database.model
import androidx.room.Embedded
import androidx.room.Relation
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
data class PendingReviewWithReceipt(
    @Embedded val review: PendingReview,
    @Relation(
        parentColumn = "scannedReceiptId",
        entityColumn = "id"
    )
    val receipt: ScannedReceipt?
)

```

---

## main\java\com\yourname\expensetracker\data\provider\MerchantCategoryProvider.kt <a name="mainjavacomyournameexpensetrackerdataprovidermerchantcategoryproviderkt"></a>
```kotlin
package com.yourname.expensetracker.data.provider
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.MerchantCategory
object MerchantCategoryProvider {
    // Default Categories (ID mapping presumed or we look them up)
    // Actually we will map by Name to be safe, then Repository will resolve IDs.
    val categoryBlueprints = listOf(
        Category(name = "Groceries", icon = "🛒", color = "#4CAF50", isDefault = true),
        Category(name = "Transport", icon = "🚗", color = "#2196F3", isDefault = true),
        Category(name = "Food", icon = "🍽️", color = "#FF9800", isDefault = true), // Restaurants/Cafe
        Category(name = "Entertainment", icon = "🎬", color = "#9C27B0", isDefault = true),
        Category(name = "Shopping", icon = "🛍️", color = "#E91E63", isDefault = true),
        Category(name = "Health", icon = "💊", color = "#00BCD4", isDefault = true),
        Category(name = "Utilities", icon = "🏠", color = "#607D8B", isDefault = true),
        Category(name = "Subscriptions", icon = "📱", color = "#673AB7", isDefault = true),
        Category(name = "Travel", icon = "✈️", color = "#009688", isDefault = true), // Changed color slightly
        Category(name = "Electronics", icon = "💻", color = "#795548", isDefault = true),
        Category(name = "Education", icon = "📚", color = "#3F51B5", isDefault = true),
        Category(name = "Fitness", icon = "💪", color = "#8BC34A", isDefault = true),
        Category(name = "Beauty", icon = "💄", color = "#FF4081", isDefault = true),
        Category(name = "Pets", icon = "🐾", color = "#A1887F", isDefault = true),
        Category(name = "Home", icon = "🛋️", color = "#FF5722", isDefault = true), // New from list
        Category(name = "Kids", icon = "🧸", color = "#FFEB3B", isDefault = true), // New from list
        Category(name = "Gifts", icon = "🎁", color = "#F44336", isDefault = true), // New from list
        Category(name = "Banking", icon = "🏦", color = "#37474F", isDefault = true), // Fees etc
        Category(name = "Legal & Gov", icon = "⚖️", color = "#9E9E9E", isDefault = true)
    )
    // Map of Merchant Name (or keyword) -> Category Name
    val merchantToCategoryMap = mapOf(
        // ═══════════════════════════════════════════════════════════════
        // 🛒 GROCERIES - Supermarkets, Bakeries, Butchers
        // ═══════════════════════════════════════════════════════════════
        // AB Vassilopoulos (all variations)
        "AB Βασιλόπουλος" to "Groceries", "AB Vasilopoulos" to "Groceries", 
        "AB BASILOPOULOS" to "Groceries", "AB SHOP" to "Groceries", 
        "A.B." to "Groceries", "ALFA BETA" to "Groceries",
        "AB FOOD MARKET" to "Groceries", "DELHAIZE" to "Groceries",
        "ΑΛΦΑ ΒΗΤΑ" to "Groceries", "TROFO" to "Groceries",
        // Sklavenitis
        "Σκλαβενίτης" to "Groceries", "Sklavenitis" to "Groceries", 
        "SKLAVENITIS" to "Groceries", "ELLINIKES YPERAGORES" to "Groceries",
        "ΣΚΛΑΒΕΝΙΤΗΣ" to "Groceries", "I & S SKLAVENITIS" to "Groceries",
        // Lidl
        "Lidl" to "Groceries", "LIDL HELLAS" to "Groceries", 
        "LIDL ELLAS" to "Groceries", "LIDL STIFTUNG" to "Groceries",
        // My Market
        "My Market" to "Groceries", "MY MARKET" to "Groceries", 
        "MYMARKET" to "Groceries", "METRO AEBE" to "Groceries",
        "METRO MY MARKET" to "Groceries",
        // Masoutis
        "Μασούτης" to "Groceries", "Masoutis" to "Groceries", 
        "MASOUTIS" to "Groceries", "MASOYTHS" to "Groceries",
        "DIAMANTIS MASOUTIS" to "Groceries",
        // Other Greek Supermarkets
        "Γαλαξίας" to "Groceries", "Galaxias" to "Groceries", 
        "GALAXIAS" to "Groceries", "PENTE SA" to "Groceries",
        "Κρητικός" to "Groceries", "Kritikos" to "Groceries", 
        "KRITIKOS" to "Groceries", "ANEDIK KRITIKOS" to "Groceries",
        "Bazaar" to "Groceries", "BAZAAR" to "Groceries", 
        "BAZAAR SM" to "Groceries",
        "Market In" to "Groceries", "MARKET IN" to "Groceries", 
        "MARKETIN" to "Groceries", "VEROUKAS" to "Groceries",
        "The Mart" to "Groceries", "THE MART" to "Groceries", 
        "THEMART" to "Groceries", "MAKRO" to "Groceries",
        "METRO CASH" to "Groceries",
        // European Chains
        "Aldi" to "Groceries", "ALDI SUD" to "Groceries", 
        "ALDI NORD" to "Groceries",
        "Kaufland" to "Groceries", "KAUFLAND" to "Groceries",
        "Carrefour" to "Groceries", "CARREFOUR" to "Groceries",
        "CARREFOUR EXPRESS" to "Groceries", "CARREFOUR CITY" to "Groceries",
        "Penny Market" to "Groceries", "PENNY" to "Groceries",
        "Tesco" to "Groceries", "TESCO" to "Groceries",
        "Sainsbury" to "Groceries", "SAINSBURYS" to "Groceries",
        "Waitrose" to "Groceries", "WAITROSE" to "Groceries",
        "Marks Spencer Food" to "Groceries", "M&S FOOD" to "Groceries",
        "Migros" to "Groceries", "MIGROS" to "Groceries",
        "Coop" to "Groceries", "COOP" to "Groceries",
        "Spar" to "Groceries", "SPAR" to "Groceries",
        "Rewe" to "Groceries", "REWE" to "Groceries",
        "Edeka" to "Groceries", "EDEKA" to "Groceries",
        // Regional Greek Supermarkets
        "PLUS Super Discount" to "Groceries", "PLUS SUPERMARKET" to "Groceries",
        "Χαλκιαδάκης" to "Groceries", "Chalkiadakis" to "Groceries", 
        "HALKIADAKIS" to "Groceries", "XALKIADAKIS" to "Groceries",
        "OK! Anytime" to "Groceries", "OK MARKET" to "Groceries", 
        "OK ANYTIME MARKETS" to "Groceries",
        "Σάββας" to "Groceries", "Savvas" to "Groceries", 
        "SAVVAS CASH" to "Groceries",
        "3Α" to "Groceries", "3A" to "Groceries", "ΤΡΙΑ ΑΛΦΑ" to "Groceries",
        "Discount Markt" to "Groceries", "DISCOUNT MARKT" to "Groceries",
        "Arvanitidis" to "Groceries", "ΑΡΒΑΝΙΤΙΔΗΣ" to "Groceries",
        "Atlantic" to "Groceries", "ATLANTIC" to "Groceries",
        "Synka" to "Groceries", "SYNKA" to "Groceries", "ΣΥΝΚΑ" to "Groceries",
        "Xynos" to "Groceries", "ΞΥΝΟΣ" to "Groceries",
        "Ena Cash" to "Groceries", "ENA CASH CARRY" to "Groceries",
        "Smile Markets" to "Groceries", "SMILE MARKETS" to "Groceries",
        "Karamolegos" to "Groceries", "ΚΑΡΑΜΟΛΕΓΚΟΣ" to "Groceries",
        // Bio/Organic Stores
        "Bio Agora" to "Groceries", "BIO AGORA" to "Groceries",
        "Ελαία" to "Groceries", "Elaia" to "Groceries",
        "Avocado" to "Groceries", "AVOCADO STORES" to "Groceries",
        "Green Family" to "Groceries", "GREEN FAMILY" to "Groceries",
        "Organic" to "Groceries", "ORGANIC SHOP" to "Groceries",
        "Bio" to "Groceries", "BIOLOGIKA" to "Groceries",
        "Herbs" to "Groceries", "HERBS STORE" to "Groceries",
        // Convenience & Local
        "Mini Market" to "Groceries", "Minimarket" to "Groceries", 
        "Μινι Μαρκετ" to "Groceries",
        "Kiosk" to "Groceries", "Periptero" to "Groceries", 
        "Περίπτερο" to "Groceries", "PERIPTERO" to "Groceries",
        "Psilika" to "Groceries", "Ψιλικα" to "Groceries",
        "Pantopoleio" to "Groceries", "Παντοπωλείο" to "Groceries",
        "Grocery" to "Groceries", "GROCERY STORE" to "Groceries",
        "Bakaliko" to "Groceries", "Μπακάλικο" to "Groceries",
        "Express Market" to "Groceries", "EXPRESS" to "Groceries",
        // Bakeries
        "Bakery" to "Groceries", "Baker" to "Groceries", 
        "Φούρνος" to "Groceries", "Fournos" to "Groceries", 
        "Artopoiio" to "Groceries", "Αρτοποιείο" to "Groceries",
        "ARTOS" to "Groceries", "Bread" to "Groceries",
        "Veneti" to "Groceries", "ΒΕΝΕΤΗ" to "Groceries", "VENETIS" to "Groceries",
        "Terkenlis" to "Groceries", "ΤΕΡΚΕΝΛΗΣ" to "Groceries",
        "Asimakopoulou" to "Groceries", "ΑΣΗΜΑΚΟΠΟΥΛΟΥ" to "Groceries",
        "Konstantinidis" to "Groceries", "ΚΩΝΣΤΑΝΤΙΝΙΔΗΣ" to "Groceries",
        "Chatzis" to "Groceries", "ΧΑΤΖΗΣ" to "Groceries",
        "Blé" to "Groceries", "BLE" to "Groceries",
        "Pain Quotidien" to "Groceries", "PAIN QUOTIDIEN" to "Groceries",
        // Butcher/Meat
        "Butcher" to "Groceries", "Kreopoleio" to "Groceries", 
        "Κρεοπωλείο" to "Groceries", "KREAS" to "Groceries",
        "Meat" to "Groceries", "MEAT SHOP" to "Groceries",
        "Chiros" to "Groceries", "Pork Shop" to "Groceries",
        "Salami" to "Groceries", "Allantika" to "Groceries",
        "ΑΛΛΑΝΤΙΚΑ" to "Groceries",
        // Fish
        "Fish Shop" to "Groceries", "Ixthiopolio" to "Groceries", 
        "Ιχθυοπωλείο" to "Groceries", "ΨΑΡΑΓΟΡΑ" to "Groceries",
        "Fish Market" to "Groceries", "Psaradiko" to "Groceries",
        "Seafood" to "Groceries",
        // Produce
        "Greengrocer" to "Groceries", "Manaviko" to "Groceries", 
        "Μανάβικο" to "Groceries", "ΛΑΪΚΗ" to "Groceries",
        "Laiki Agora" to "Groceries", "Farmers Market" to "Groceries",
        "ΑΓΟΡΑ" to "Groceries", "Varvakios" to "Groceries",
        "ΒΑΡΒΑΚΕΙΟΣ" to "Groceries",
        // Specialty
        "Cheese Shop" to "Groceries", "Tyrokomeio" to "Groceries",
        "Delicatessen" to "Groceries", "Deli" to "Groceries",
        "Kafekopeio" to "Groceries", "ΚΑΦΕΚΟΠΤΕΙΟ" to "Groceries",
        "Wine Shop" to "Groceries", "Kava" to "Groceries", "ΚΑΒΑ" to "Groceries",
        "Cellar" to "Groceries",
        // ═══════════════════════════════════════════════════════════════
        // 🚗 TRANSPORT - FUEL & MOBILITY
        // ═══════════════════════════════════════════════════════════════
        // Fuel Stations
        "Shell" to "Transport", "SHELL HELLAS" to "Transport", 
        "SEHL" to "Transport", "CORAL AE" to "Transport",
        "BP" to "Transport", "BP HELLAS" to "Transport", 
        "BRITISH PETROLEUM" to "Transport",
        "EKO" to "Transport", "EKO ABEE" to "Transport", 
        "EKO KALYPSO" to "Transport", "HELLENIC PETROLEUM" to "Transport",
        "ELPE" to "Transport", "ΕΛΠΕ" to "Utilities", // Note: ELPE can be heating oil/utility too, but keeping as fuel here or user preference
        "Aegean" to "Transport", "Aegean Oil" to "Transport", 
        "AEGEAN OIL" to "Transport",
        "Avin" to "Transport", "AVIN OIL" to "Transport", 
        "MOTOR OIL" to "Transport",
        "Ελίν" to "Transport", "Elin" to "Transport", 
        "ELIN OIL" to "Transport", "ELINOIL" to "Transport",
        "Revoil" to "Transport", "REVOIL" to "Transport", 
        "Jet Oil" to "Transport", "JETOIL" to "Transport",
        "Cyclon" to "Transport", "CYCLON" to "Transport", 
        "Coral Gas" to "Transport", "CORAL GAS" to "Transport",
        "Eteka" to "Transport", "ETEKA" to "Transport",
        "Mamidoil" to "Transport", "MAMIDOIL" to "Transport",
        "Silk Oil" to "Transport", "SILK OIL" to "Transport",
        "Naoumidis" to "Transport", "ΝΑΟΥΜΙΔΗΣ" to "Transport",
        // International Fuel
        "Total" to "Transport", "TOTAL ENERGIES" to "Transport",
        "Esso" to "Transport", "ESSO" to "Transport",
        "Texaco" to "Transport", "TEXACO" to "Transport",
        "Q8" to "Transport", "KUWAIT PETROLEUM" to "Transport",
        "Cepsa" to "Transport", "CEPSA" to "Transport",
        "Repsol" to "Transport", "REPSOL" to "Transport",
        "OMV" to "Transport", "OMV" to "Transport",
        "MOL" to "Transport", "MOL" to "Transport",
        // Generic Fuel
        "Gas Station" to "Transport", "Fuel Station" to "Transport", 
        "Πρατήριο" to "Transport", "Benzinadiko" to "Transport",
        "Βενζινάδικο" to "Transport", "PRATIRIO" to "Transport",
        "Petrol" to "Transport", "PETROL" to "Transport",
        "Diesel" to "Transport", "DIESEL" to "Transport",
        "LPG" to "Transport", "AUTOGAS" to "Transport",
        "Charging Station" to "Transport", "EV CHARGE" to "Transport",
        // Ride Hailing
        "Uber" to "Transport", "UBER TRIP" to "Transport", 
        "UBER BV" to "Transport", "UBER PAYMENTS" to "Transport",
        "Beat" to "Transport", "BEAT APP" to "Transport", 
        "BEAT RIDE" to "Transport",
        "FREE NOW" to "Transport", "NOOW" to "Transport", 
        "FREENOW" to "Transport", "MYTAXI" to "Transport",
        "Bolt" to "Transport", "BOLT EU" to "Transport",
        "BOLT OPERATIONS" to "Transport",
        "Lyft" to "Transport", "LYFT" to "Transport",
        "Didi" to "Transport", "DIDI" to "Transport",
        // Taxis
        "Taxi" to "Transport", "Ταξί" to "Transport", 
        "Cab" to "Transport", "ΤΑΞΙ" to "Transport",
        "Taxiplon" to "Transport", "TAXIPLON" to "Transport",
        "Radio Taxi" to "Transport", "RADIO TAXI" to "Transport",
        "TAXI ATHINON" to "Transport", "ΡΑΔΙΟΤΑΞΙ" to "Transport",
        // Public Transport Athens
        "OASA" to "Transport", "ΟΑΣΑ" to "Transport", 
        "ATH.ENA TICKET" to "Transport", "ATHENA CARD" to "Transport",
        "STASY" to "Transport", "ΣΤΑΣΥ" to "Transport", 
        "URBAN RAIL" to "Transport",
        "Metro Athens" to "Transport", "ΜΕΤΡΟ" to "Transport",
        "ATTIKO METRO" to "Transport",
        "Tram" to "Transport", "Τραμ" to "Transport", "ΤΡΑΜ" to "Transport",
        "ISAP" to "Transport", "ΗΣΑΠ" to "Transport", 
        "Ηλεκτρικός" to "Transport", "HLEKTRIKOS" to "Transport",
        "Proastiakos" to "Transport", "ΠΡΟΑΣΤΙΑΚΟΣ" to "Transport",
        // Public Transport Thessaloniki
        "OASTH" to "Transport", "ΟΑΣΘ" to "Transport",
        "THESSALONIKI METRO" to "Transport",
        // Buses
        "KTEL" to "Transport", "ΚΤΕΛ" to "Transport", 
        "KTEL ATTIKIS" to "Transport", "KTEL MACEDONIA" to "Transport",
        "KTEL PELOPONNISOU" to "Transport", "KTEL KRITIS" to "Transport",
        "KTEL THESSALONIKH" to "Transport", "KTEL LARISAS" to "Transport",
        "KTEL PATRON" to "Transport", "KTEL VOLOU" to "Transport",
        "KTEL EVIA" to "Transport", "KTEL IRAKLEIOU" to "Transport",
        "KTEL CHANION" to "Transport", "KTEL RODOU" to "Transport",
        "Flixbus" to "Transport", "FLIXBUS" to "Transport",
        // Trains
        "Hellenic Train" to "Transport", "TRAINOSE" to "Transport", 
        "ΤΡΕΝΟΣΕ" to "Transport", "OSE" to "Transport", "ΟΣΕ" to "Transport",
        "HELLENICTRAIN" to "Transport", "ΕΛΛΗΝΙΚΟΣ" to "Transport",
        "Eurostar" to "Transport", "EUROSTAR" to "Transport",
        "Thalys" to "Transport", "TGV" to "Transport",
        "Deutsche Bahn" to "Transport", "DB" to "Transport",
        "OBB" to "Transport", "SNCF" to "Transport",
        "Trenitalia" to "Transport", "ITALO" to "Transport",
        // Parking
        "Parking" to "Transport", "Parkin" to "Transport", 
        "Parkingmycity" to "Transport", "Cityzen" to "Transport", 
        "Polis Park" to "Transport",
        "Athens Parking" to "Transport", "APCOA" to "Transport",
        "Q-Park" to "Transport", "INTERPARKING" to "Transport",
        "SABA" to "Transport", "ΣΤΑΘΜΕΥΣΗ" to "Transport",
        "Valet" to "Transport", "VALET PARKING" to "Transport",
        // Tolls
        "E-pass" to "Transport", "EPASS" to "Transport", 
        "Attiki Odos" to "Transport", "ATTIKI ODOS" to "Transport",
        "ATTIKES DIADROMES" to "Transport",
        "Nea Odos" to "Transport", "NEA ODOS" to "Transport",
        "Olympia Odos" to "Transport", "OLYMPIA ODOS" to "Transport",
        "Egnatia Odos" to "Transport", "EGNATIA ODOS" to "Transport",
        "Moreas" to "Transport", "MOREAS" to "Transport", 
        "Kentriki Odos" to "Transport", "KENTRIKI ODOS" to "Transport",
        "Gefyra" to "Transport", "GEFYRA" to "Transport", 
        "Rio Antirio" to "Transport", "RIO ANTIRIO" to "Transport",
        "DIODIA" to "Transport", "Διόδια" to "Transport",
        "Aktor" to "Transport", "AKTOR CONCESSIONS" to "Transport",
        "Autohellas" to "Transport", "AUTOKINITODROMO" to "Transport",
        // Micromobility
        "Lime" to "Transport", "LIME SCOOTER" to "Transport",
        "Tier" to "Transport", "TIER MOBILITY" to "Transport",
        "Bird" to "Transport", "BIRD SCOOTER" to "Transport",
        "Voi" to "Transport", "VOI" to "Transport",
        "Dott" to "Transport", "DOTT" to "Transport",
        "Spin" to "Transport", "SPIN" to "Transport",
        "Bike" to "Transport", "BIKE RENTAL" to "Transport",
        "E-scooter" to "Transport", "ESCOOTER" to "Transport",
        // ═══════════════════════════════════════════════════════════════
        // ✈️ TRAVEL - Airlines, Hotels, Ferries
        // ═══════════════════════════════════════════════════════════════
        // Greek Airlines
        "Aegean Airlines" to "Travel", "AEGEAN AIR" to "Travel", 
        "AEGEAN AIRLINES" to "Travel", "A3" to "Travel",
        "Olympic Air" to "Travel", "OLYMPIC AIR" to "Travel", 
        "OLYMPIC AIRLINES" to "Travel",
        "Sky Express" to "Travel", "SKY EXPRESS" to "Travel",
        "SKYEXPRESS" to "Travel",
        // European Low Cost
        "Ryanair" to "Travel", "RYANAIR" to "Travel", 
        "RYANAIR DAC" to "Travel", "FR" to "Travel",
        "EasyJet" to "Travel", "EASYJET" to "Travel", 
        "U2" to "Travel",
        "Wizz Air" to "Travel", "WIZZAIR" to "Travel", 
        "W6" to "Travel",
        "Volotea" to "Travel", "VOLOTEA" to "Travel",
        "Vueling" to "Travel", "VUELING" to "Travel",
        "Transavia" to "Travel", "TRANSAVIA" to "Travel",
        "Norwegian" to "Travel", "NORWEGIAN AIR" to "Travel",
        "Eurowings" to "Travel", "EUROWINGS" to "Travel",
        "Lauda" to "Travel", "LAUDA EUROPE" to "Travel",
        "Buzz" to "Travel", "BUZZ POLAND" to "Travel",
        "Malta Air" to "Travel", "MALTA AIR" to "Travel",
        // Major Airlines
        "Lufthansa" to "Travel", "LUFTHANSA" to "Travel", "LH" to "Travel",
        "Swiss Air" to "Travel", "SWISS" to "Travel", "LX" to "Travel",
        "Austrian" to "Travel", "AUSTRIAN AIRLINES" to "Travel",
        "British Airways" to "Travel", "BRITISH AIRWAYS" to "Travel", "BA" to "Travel",
        "Air France" to "Travel", "AIRFRANCE" to "Travel", "AF" to "Travel",
        "KLM" to "Travel", "KLM ROYAL" to "Travel",
        "Iberia" to "Travel", "IBERIA" to "Travel",
        "TAP" to "Travel", "TAP PORTUGAL" to "Travel",
        "Alitalia" to "Travel", "ITA AIRWAYS" to "Travel",
        "SAS" to "Travel", "SCANDINAVIAN" to "Travel",
        "Finnair" to "Travel", "FINNAIR" to "Travel",
        "LOT" to "Travel", "LOT POLISH" to "Travel",
        "Czech Airlines" to "Travel", "CSA" to "Travel",
        "Croatia Airlines" to "Travel", "CROATIA AIR" to "Travel",
        "Turkish Airlines" to "Travel", "TURKISH" to "Travel", "TK" to "Travel",
        "Emirates" to "Travel", "EMIRATES" to "Travel", "EK" to "Travel",
        "Qatar Airways" to "Travel", "QATAR" to "Travel", "QR" to "Travel",
        "Etihad" to "Travel", "ETIHAD" to "Travel",
        "Singapore Airlines" to "Travel", "SINGAPORE AIR" to "Travel",
        "Cathay Pacific" to "Travel", "CATHAY" to "Travel",
        "United" to "Travel", "UNITED AIRLINES" to "Travel",
        "Delta" to "Travel", "DELTA AIRLINES" to "Travel",
        "American Airlines" to "Travel", "AMERICAN AIR" to "Travel",
        // Ferries - Greece
        "Blue Star" to "Travel", "BLUE STAR FERRIES" to "Travel",
        "BLUESTAR" to "Travel", "ATTICA GROUP" to "Travel",
        "ANEK" to "Travel", "ANEK LINES" to "Travel",
        "Minoan" to "Travel", "MINOAN LINES" to "Travel",
        "Hellenic Seaways" to "Travel", "HSW" to "Travel",
        "HELLENIC SEAWAYS" to "Travel",
        "Seajets" to "Travel", "SEAJETS" to "Travel", "SEA JETS" to "Travel",
        "Golden Star" to "Travel", "GOLDEN STAR FERRIES" to "Travel",
        "Fast Ferries" to "Travel", "FAST FERRIES" to "Travel",
        "Superfast" to "Travel", "SUPERFAST FERRIES" to "Travel",
        "Aegean Speed Lines" to "Travel", "AEGEAN SPEED" to "Travel",
        "Zante Ferries" to "Travel", "ZANTE FERRIES" to "Travel",
        "Levante Ferries" to "Travel", "LEVANTE" to "Travel",
        "Saronic Ferries" to "Travel", "SARONIC" to "Travel",
        "Anes Ferries" to "Travel", "ANES" to "Travel",
        "NEL Lines" to "Travel", "NEL" to "Travel",
        "Sky Island Ferries" to "Travel", "SKYISLAND" to "Travel",
        "Dodekanisos Seaways" to "Travel", "DODEKANISOS" to "Travel",
        "Small Cyclades" to "Travel", "EXPRESS SKOPELITIS" to "Travel",
        "Triton" to "Travel", "TRITON FERRIES" to "Travel",
        "Ferry" to "Travel", "FERRY TICKET" to "Travel",
        "ΠΛΟΙΟ" to "Travel", "ΑΚΤΟΠΛΟΙΚΑ" to "Travel",
        // International Ferries
        "Grimaldi" to "Travel", "GRIMALDI LINES" to "Travel",
        "Grandi Navi" to "Travel", "GNV" to "Travel",
        "Moby" to "Travel", "MOBY LINES" to "Travel",
        "Tirrenia" to "Travel", "TIRRENIA" to "Travel",
        "Jadrolinija" to "Travel", "JADROLINIJA" to "Travel",
        "Corsica Ferries" to "Travel", "CORSICA" to "Travel",
        "Brittany Ferries" to "Travel", "BRITTANY" to "Travel",
        "P&O Ferries" to "Travel", "P&O" to "Travel",
        "DFDS" to "Travel", "DFDS SEAWAYS" to "Travel",
        "Stena Line" to "Travel", "STENA" to "Travel",
        "Viking Line" to "Travel", "VIKING LINE" to "Travel",
        "Tallink" to "Travel", "TALLINK SILJA" to "Travel",
        // Car Rental
        "Hertz" to "Travel", "HERTZ" to "Travel", "HERTZ HELLAS" to "Travel",
        "Avis" to "Travel", "AVIS" to "Travel", "AVIS RENT" to "Travel",
        "Europcar" to "Travel", "EUROPCAR" to "Travel",
        "Enterprise" to "Travel", "ENTERPRISE" to "Travel",
        "Budget" to "Travel", "BUDGET" to "Travel",
        "Sixt" to "Travel", "SIXT" to "Travel", "SIXT RENT" to "Travel",
        "National" to "Travel", "NATIONAL CAR" to "Travel",
        "Alamo" to "Travel", "ALAMO" to "Travel",
        "Thrifty" to "Travel", "THRIFTY" to "Travel",
        "Dollar" to "Travel", "DOLLAR RENT" to "Travel",
        "Green Motion" to "Travel", "GREEN MOTION" to "Travel",
        "Goldcar" to "Travel", "GOLDCAR" to "Travel",
        "Firefly" to "Travel", "FIREFLY CAR" to "Travel",
        "Maggiore" to "Travel", "MAGGIORE RENT" to "Travel",
        "Autohellas" to "Travel", "AUTOHELLAS" to "Travel",
        "Avance" to "Travel", "AVANCE RENT" to "Travel",
        "Car Rental" to "Travel", "RENT A CAR" to "Travel",
        "ΕΝΟΙΚΙΑΣΗ" to "Travel",
        // Hotels & Accommodation
        "Booking.com" to "Travel", "BOOKING" to "Travel", 
        "BOOKING.COM" to "Travel", "BOOKINGCOM" to "Travel",
        "Airbnb" to "Travel", "AIRBNB" to "Travel", 
        "AIR BNB" to "Travel",
        "Hotels.com" to "Travel", "HOTELS.COM" to "Travel",
        "Expedia" to "Travel", "EXPEDIA" to "Travel",
        "Trivago" to "Travel", "TRIVAGO" to "Travel",
        "Agoda" to "Travel", "AGODA" to "Travel",
        "Trip.com" to "Travel", "TRIP.COM" to "Travel", 
        "CTRIP" to "Travel",
        "Hostelworld" to "Travel", "HOSTELWORLD" to "Travel",
        "Vrbo" to "Travel", "VRBO" to "Travel", 
        "HOMEAWAY" to "Travel",
        "TripAdvisor" to "Travel", "TRIPADVISOR" to "Travel",
        "Kayak" to "Travel", "KAYAK" to "Travel",
        "Skyscanner" to "Travel", "SKYSCANNER" to "Travel",
        "Google Flights" to "Travel", "GOOGLE FLIGHTS" to "Travel",
        "Momondo" to "Travel", "MOMONDO" to "Travel",
        "Kiwi" to "Travel", "KIWI.COM" to "Travel",
        "Lastminute" to "Travel", "LASTMINUTE" to "Travel",
        "Opodo" to "Travel", "OPODO" to "Travel",
        "eDreams" to "Travel", "EDREAMS" to "Travel",
        "Gotogate" to "Travel", "GOTOGATE" to "Travel",
        "Hotel" to "Travel", "HOTEL" to "Travel", 
        "ΞΕΝΟΔΟΧΕΙΟ" to "Travel", "Xenodocheio" to "Travel",
        "Hostel" to "Travel", "HOSTEL" to "Travel",
        "Resort" to "Travel", "RESORT" to "Travel",
        "Pension" to "Travel", "PENSION" to "Travel",
        "Motel" to "Travel", "MOTEL" to "Travel",
        // Greek Hotel Chains
        "Grecotel" to "Travel", "GRECOTEL" to "Travel",
        "Mitsis" to "Travel", "MITSIS HOTELS" to "Travel",
        "Aldemar" to "Travel", "ALDEMAR" to "Travel",
        "Porto Carras" to "Travel", "PORTO CARRAS" to "Travel",
        "Sani Resort" to "Travel", "SANI" to "Travel",
        "Ikos" to "Travel", "IKOS RESORTS" to "Travel",
        "Costa Navarino" to "Travel", "COSTA NAVARINO" to "Travel",
        "Divani" to "Travel", "DIVANI HOTELS" to "Travel",
        "Electra" to "Travel", "ELECTRA HOTELS" to "Travel",
        "Makedonia Palace" to "Travel", "MAKEDONIA PALACE" to "Travel",
        "Grande Bretagne" to "Travel", "GRANDE BRETAGNE" to "Travel",
        "King George" to "Travel", "KING GEORGE" to "Travel",
        "St George Lycabettus" to "Travel", "ST GEORGE" to "Travel",
        "Hilton Athens" to "Travel", "HILTON" to "Travel",
        "Marriott" to "Travel", "MARRIOTT" to "Travel",
        "Sofitel" to "Travel", "SOFITEL" to "Travel",
        "Intercontinental" to "Travel", "INTERCONTINENTAL" to "Travel",
        "Four Seasons" to "Travel", "FOUR SEASONS" to "Travel",
        "Radisson" to "Travel", "RADISSON BLU" to "Travel",
        "Wyndham" to "Travel", "WYNDHAM" to "Travel",
        "Novotel" to "Travel", "NOVOTEL" to "Travel",
        "Ibis" to "Travel", "IBIS" to "Travel",
        "Accor" to "Travel", "ACCOR" to "Travel",
        "Best Western" to "Travel", "BEST WESTERN" to "Travel",
        "Holiday Inn" to "Travel", "HOLIDAY INN" to "Travel",
        "Crowne Plaza" to "Travel", "CROWNE PLAZA" to "Travel",
        // Tour Operators
        "TUI" to "Travel", "TUI HELLAS" to "Travel",
        "Thomas Cook" to "Travel", "THOMAS COOK" to "Travel",
        "Mouzenidis" to "Travel", "MOUZENIDIS TRAVEL" to "Travel",
        "Zorpidis" to "Travel", "ZORPIDIS TRAVEL" to "Travel",
        "Amphitrion" to "Travel", "AMPHITRION" to "Travel",
        "Travelplanet24" to "Travel", "TRAVELPLANET" to "Travel",
        "Pamediakopes" to "Travel", "PAME DIAKOPES" to "Travel",
        "Discover Greece" to "Travel", "DISCOVER" to "Travel",
        "Aegean Holidays" to "Travel", "AEGEAN HOLIDAYS" to "Travel",
        "Travel Agency" to "Travel", "TRAVEL AGENCY" to "Travel",
        "ΤΑΞΙΔΙΩΤΙΚΟ" to "Travel", "Tour" to "Travel",
        // Activities & Experiences
        "GetYourGuide" to "Travel", "GETYOURGUIDE" to "Travel",
        "Viator" to "Travel", "VIATOR" to "Travel",
        "Klook" to "Travel", "KLOOK" to "Travel",
        "Musement" to "Travel", "MUSEMENT" to "Travel",
        "Tiqets" to "Travel", "TIQETS" to "Travel",
        "Civitatis" to "Travel", "CIVITATIS" to "Travel",
        "Headout" to "Travel", "HEADOUT" to "Travel",
        // ═══════════════════════════════════════════════════════════════
        // 🍽️ FOOD & RESTAURANTS
        // ═══════════════════════════════════════════════════════════════
        // Coffee Chains - Greek
        "Gregorys" to "Food", "GREGORYS" to "Food", 
        "GRIGORIS" to "Food", "Γρηγόρης" to "Food", 
        "MΙΚΡΟΓΕΥΜΑΤΑ" to "Food", "ΓΡΗΓΟΡΗΣ" to "Food",
        "Everest" to "Food", "EVEREST" to "Food",
        "Mikel" to "Food", "MIKEL" to "Food", 
        "MIKEL COFFEE" to "Food",
        "Coffee Island" to "Food", "COFFEE ISLAND" to "Food", 
        "KAFEKOPTEIO" to "Food",
        "Coffee Lab" to "Food", "COFFEE LAB" to "Food",
        "Flocafe" to "Food", "FLOCAFE" to "Food",
        "Coffee Berry" to "Food", "COFFEE BERRY" to "Food",
        "Bruno" to "Food", "BRUNO COFFEE" to "Food", 
        "Cultivos" to "Food", "CULTIVOS" to "Food",
        "Taf" to "Food", "TAF COFFEE" to "Food",
        "Holy Spirit" to "Food", "HOLY SPIRIT" to "Food",
        "Brew Lab" to "Food", "BREW LAB" to "Food",
        "Mokka" to "Food", "MOKKA COFFEE" to "Food",
        "The Underdog" to "Food", "UNDERDOG" to "Food",
        "Little Tree" to "Food", "LITTLE TREE" to "Food",
        "Seven Grams" to "Food", "SEVEN GRAMS" to "Food",
        "Mind the Cup" to "Food", "MIND THE CUP" to "Food",
        // Coffee Chains - International
        "Starbucks" to "Food", "STARBUCKS" to "Food", 
        "STARBUCKS COFFEE" to "Food",
        "Costa Coffee" to "Food", "COSTA COFFEE" to "Food",
        "McCafe" to "Food", "MCCAFE" to "Food",
        "Caffè Nero" to "Food", "CAFFE NERO" to "Food",
        "Pret" to "Food", "PRET A MANGER" to "Food",
        "Dunkin" to "Food", "DUNKIN DONUTS" to "Food",
        "Tim Hortons" to "Food", "TIM HORTONS" to "Food",
        "Gloria Jeans" to "Food", "GLORIA JEANS" to "Food",
        "Lavazza" to "Food", "LAVAZZA" to "Food",
        "Illy" to "Food", "ILLY CAFFE" to "Food",
        "Segafredo" to "Food", "SEGAFREDO" to "Food",
        // Fast Food - Global
        "McDonalds" to "Food", "MCDONALDS" to "Food", 
        "MCD" to "Food", "MC DONALDS" to "Food",
        "Burger King" to "Food", "BURGER KING" to "Food", "BK" to "Food",
        "KFC" to "Food", "KENTUCKY FRIED CHICKEN" to "Food",
        "Subway" to "Food", "SUBWAY" to "Food",
        "Pizza Hut" to "Food", "PIZZA HUT" to "Food",
        "Dominos" to "Food", "DOMINOS" to "Food", 
        "DOMINO'S" to "Food", "DOMINOS PIZZA" to "Food",
        "Papa Johns" to "Food", "PAPA JOHNS" to "Food",
        "Wendys" to "Food", "WENDYS" to "Food",
        "Taco Bell" to "Food", "TACO BELL" to "Food",
        "Chick-fil-A" to "Food", "CHICK FIL A" to "Food",
        "Five Guys" to "Food", "FIVE GUYS" to "Food",
        "Shake Shack" to "Food", "SHAKE SHACK" to "Food",
        "Popeyes" to "Food", "POPEYES" to "Food",
        "Chipotle" to "Food", "CHIPOTLE" to "Food",
        // Fast Food - Greek
        "Goodys" to "Food", "Goody's" to "Food", 
        "GOODYS" to "Food", "GOODY'S BURGER HOUSE" to "Food",
        "Pizza Fan" to "Food", "PIZZA FAN" to "Food",
        "Roma Pizza" to "Food", "ROMA PIZZA" to "Food",
        "L'Artigiano" to "Food", "LARTIGIANO" to "Food",
        "Palmie Bistro" to "Food", "PALMIE" to "Food",
        "Bufala Gelato" to "Food", "BUFALA" to "Food",
        // Casual Dining
        "TGI Fridays" to "Food", "TGI FRIDAYS" to "Food", 
        "FRIDAYS" to "Food",
        "Hard Rock" to "Food", "HARD ROCK CAFE" to "Food",
        "Wagamama" to "Food", "WAGAMAMA" to "Food", 
        "Noodle Bar" to "Food", "NOODLE BAR" to "Food",
        "Applebees" to "Food", "APPLEBEES" to "Food",
        "Chillis" to "Food", "CHILIS" to "Food",
        "Olive Garden" to "Food", "OLIVE GARDEN" to "Food",
        "PF Changs" to "Food", "PF CHANGS" to "Food",
        "Vapiano" to "Food", "VAPIANO" to "Food",
        "Bills" to "Food", "BILLS" to "Food",
        "The Breakfast Club" to "Food", "BREAKFAST CLUB" to "Food",
        // Greek Food Categories
        "Souvlaki" to "Food", "Σουβλάκι" to "Food", 
        "ΣΟΥΒΛΑΚΙ" to "Food", "SOUVLATZIDIKO" to "Food",
        "Psistaria" to "Food", "Ψησταριά" to "Food", 
        "ΨΗΣΤΑΡΙΑ" to "Food",
        "Grill" to "Food", "GRILL" to "Food", "ΣΧΑΡΑΣ" to "Food",
        "Gyros" to "Food", "ΓΥΡΟΣ" to "Food",
        "Kebab" to "Food", "KEBAB" to "Food",
        "Taverna" to "Food", "Ταβέρνα" to "Food", 
        "ΤΑΒΕΡΝΑ" to "Food",
        "Mezedopolio" to "Food", "Μεζεδοπωλείο" to "Food",
        "Ouzeri" to "Food", "Ουζερί" to "Food",
        "Tsipouradiko" to "Food", "Τσιπουράδικο" to "Food",
        "Psarotaverna" to "Food", "Ψαροταβέρνα" to "Food",
        "Estiatorio" to "Food", "Εστιατόριο" to "Food",
        "Restaurant" to "Food", "RESTAURANT" to "Food",
        // Cafes & Bars
        "Cafe" to "Food", "Καφέ" to "Food", "ΚΑΦΕ" to "Food",
        "Kafeneio" to "Food", "Καφενείο" to "Food",
        "Bar" to "Food", "BAR" to "Food", "ΜΠΑΡ" to "Food",
        "Club" to "Food", "CLUB" to "Food", "ΚΛΑΜΠ" to "Food",
        "Pub" to "Food", "PUB" to "Food",
        "Lounge" to "Food", "LOUNGE" to "Food",
        "Bistro" to "Food", "BISTRO" to "Food",
        "Brasserie" to "Food", "BRASSERIE" to "Food",
        "Cocktail" to "Food", "COCKTAIL BAR" to "Food",
        "Wine Bar" to "Food", "WINE BAR" to "Food",
        // Food Delivery Apps
        "efood" to "Food", "E-FOOD" to "Food", 
        "EFOOD" to "Food", "ONLINE DELIVERY" to "Food",
        "E FOOD SA" to "Food", "EFOOD GR" to "Food",
        "Wolt" to "Food", "WOLT" to "Food", 
        "WOLT GREECE" to "Food", "WOLT ENTERPRISES" to "Food",
        "Box" to "Food", "BOX DELIVERY" to "Food", 
        "BOX NOW" to "Food",
        "Uber Eats" to "Food", "UBER EATS" to "Food", 
        "UBEREATS" to "Food",
        "Glovo" to "Food", "GLOVO" to "Food", 
        "GLOVOAPP" to "Food",
        "Just Eat" to "Food", "JUST EAT" to "Food",
        "Deliveroo" to "Food", "DELIVEROO" to "Food",
        "Doordash" to "Food", "DOORDASH" to "Food",
        "Getir" to "Food", "GETIR" to "Food",
        "Gorillas" to "Food", "GORILLAS" to "Food",
        "Flink" to "Food", "FLINK" to "Food",
        "Delivery" to "Food", "DELIVERY" to "Food",
        "Take away" to "Food", "TAKEAWAY" to "Food",
        // Ice Cream & Desserts
        "Haagen Dazs" to "Food", "HAAGEN DAZS" to "Food",
        "Ben Jerry" to "Food", "BEN JERRYS" to "Food",
        "Baskin Robbins" to "Food", "BASKIN ROBBINS" to "Food",
        "Gelato" to "Food", "GELATO" to "Food",
        "Pagoto" to "Food", "Παγωτό" to "Food",
        "Dodoni" to "Food", "ΔΩΔΩΝΗ" to "Food",
        "Kayak" to "Food", "KAYAK ICECREAM" to "Food",
        "Cremeria" to "Food", "CREMERIA" to "Food",
        "Patisserie" to "Food", "PATISSERIE" to "Food",
        "Zacharoplasteio" to "Food", "Ζαχαροπλαστείο" to "Food",
        "Sweets" to "Food", "ΓΛΥΚΑ" to "Food",
        "Crepe" to "Food", "CREPE" to "Food",
        "Waffle" to "Food", "WAFFLE" to "Food",
        "Churros" to "Food", "CHURROS" to "Food",
        "Donuts" to "Food", "DONUT" to "Food",
        // ═══════════════════════════════════════════════════════════════
        // 🛍️ SHOPPING
        // ═══════════════════════════════════════════════════════════════
        // Inditex Group (Zara parent)
        "Zara" to "Shopping", "ZARA" to "Shopping", 
        "ZARA HELLAS" to "Shopping", "ITX HELLAS" to "Shopping",
        "Pull&Bear" to "Shopping", "PULL AND BEAR" to "Shopping", 
        "PULL&BEAR" to "Shopping",
        "Bershka" to "Shopping", "BERSHKA" to "Shopping", 
        "Stradivarius" to "Shopping", "STRADIVARIUS" to "Shopping",
        "Massimo Dutti" to "Shopping", "MASSIMO DUTTI" to "Shopping",
        "Oysho" to "Shopping", "OYSHO" to "Shopping",
        "Zara Home" to "Shopping", "ZARA HOME" to "Shopping",
        "Uterque" to "Shopping", "UTERQUE" to "Shopping",
        // H&M Group
        "H&M" to "Shopping", "H & M" to "Shopping", 
        "H AND M" to "Shopping", "HENNES" to "Shopping",
        "COS" to "Shopping", "COS STORES" to "Shopping",
        "& Other Stories" to "Shopping", "OTHER STORIES" to "Shopping",
        "Arket" to "Shopping", "ARKET" to "Shopping",
        "Weekday" to "Shopping", "WEEKDAY" to "Shopping",
        "Monki" to "Shopping", "MONKI" to "Shopping",
        // Calzedonia Group
        "Intimissimi" to "Shopping", "INTIMISSIMI" to "Shopping",
        "Calzedonia" to "Shopping", "CALZEDONIA" to "Shopping",
        "Tezenis" to "Shopping", "TEZENIS" to "Shopping",
        "Falconeri" to "Shopping", "FALCONERI" to "Shopping",
        // Fashion - International
        "Mango" to "Shopping", "MANGO" to "Shopping", "MNG" to "Shopping",
        "Uniqlo" to "Shopping", "UNIQLO" to "Shopping",
        "Gap" to "Shopping", "GAP" to "Shopping",
        "Old Navy" to "Shopping", "OLD NAVY" to "Shopping",
        "Banana Republic" to "Shopping", "BANANA REPUBLIC" to "Shopping",
        "Primark" to "Shopping", "PRIMARK" to "Shopping",
        "C&A" to "Shopping", "C AND A" to "Shopping",
        "New Yorker" to "Shopping", "NEW YORKER" to "Shopping",
        "Reserved" to "Shopping", "RESERVED" to "Shopping",
        "Sinsay" to "Shopping", "SINSAY" to "Shopping",
        "House" to "Shopping", "HOUSE BRAND" to "Shopping",
        "Cropp" to "Shopping", "CROPP" to "Shopping",
        "Mohito" to "Shopping", "MOHITO" to "Shopping",
        "Forever 21" to "Shopping", "FOREVER21" to "Shopping",
        "Topshop" to "Shopping", "TOPSHOP" to "Shopping",
        "River Island" to "Shopping", "RIVER ISLAND" to "Shopping",
        "Asos" to "Shopping", "ASOS" to "Shopping",
        "Boohoo" to "Shopping", "BOOHOO" to "Shopping",
        "Shein" to "Shopping", "SHEIN" to "Shopping",
        "Temu" to "Shopping", "TEMU" to "Shopping",
        "Wish" to "Shopping", "WISH COM" to "Shopping",
        // Premium Fashion
        "Tommy Hilfiger" to "Shopping", "TOMMY HILFIGER" to "Shopping",
        "Calvin Klein" to "Shopping", "CALVIN KLEIN" to "Shopping",
        "Ralph Lauren" to "Shopping", "POLO RALPH" to "Shopping",
        "Lacoste" to "Shopping", "LACOSTE" to "Shopping",
        "Hugo Boss" to "Shopping", "HUGO BOSS" to "Shopping",
        "Gant" to "Shopping", "GANT" to "Shopping",
        "Armani" to "Shopping", "ARMANI EXCHANGE" to "Shopping",
        "Michael Kors" to "Shopping", "MICHAEL KORS" to "Shopping",
        "Coach" to "Shopping", "COACH" to "Shopping",
        "Kate Spade" to "Shopping", "KATE SPADE" to "Shopping",
        "Guess" to "Shopping", "GUESS" to "Shopping",
        "Diesel" to "Shopping", "DIESEL" to "Shopping",
        "Replay" to "Shopping", "REPLAY" to "Shopping",
        "Levis" to "Shopping", "LEVIS" to "Shopping", "LEVI STRAUSS" to "Shopping",
        "Wrangler" to "Shopping", "WRANGLER" to "Shopping",
        "Lee" to "Shopping", "LEE JEANS" to "Shopping",
        // Sports Brands
        "Nike" to "Shopping", "NIKE" to "Shopping", 
        "NIKE RETAIL" to "Shopping", "NIKE STORE" to "Shopping",
        "Adidas" to "Shopping", "ADIDAS" to "Shopping",
        "Puma" to "Shopping", "PUMA" to "Shopping", 
        "Reebok" to "Shopping", "REEBOK" to "Shopping",
        "Under Armour" to "Shopping", "UNDER ARMOUR" to "Shopping",
        "New Balance" to "Shopping", "NEW BALANCE" to "Shopping",
        "Asics" to "Shopping", "ASICS" to "Shopping",
        "Converse" to "Shopping", "CONVERSE" to "Shopping",
        "Vans" to "Shopping", "VANS" to "Shopping",
        "Fila" to "Shopping", "FILA" to "Shopping",
        "Champion" to "Shopping", "CHAMPION" to "Shopping",
        "Skechers" to "Shopping", "SKECHERS" to "Shopping",
        "Timberland" to "Shopping", "TIMBERLAND" to "Shopping",
        "Columbia" to "Shopping", "COLUMBIA SPORTSWEAR" to "Shopping",
        "North Face" to "Shopping", "THE NORTH FACE" to "Shopping",
        "Patagonia" to "Shopping", "PATAGONIA" to "Shopping",
        "Helly Hansen" to "Shopping", "HELLY HANSEN" to "Shopping",
        "Jack Wolfskin" to "Shopping", "JACK WOLFSKIN" to "Shopping",
        "Salomon" to "Shopping", "SALOMON" to "Shopping",
        "Arc'teryx" to "Shopping", "ARCTERYX" to "Shopping",
        // Sports Retailers
        "Intersport" to "Shopping", "INTERSPORT" to "Shopping",
        "Cosmos Sport" to "Shopping", "COSMOS SPORT" to "Shopping",
        "Zakcret" to "Shopping", "ZAKCRET" to "Shopping", 
        "Sports Factory" to "Shopping", "SPORTS FACTORY" to "Shopping",
        "Foot Locker" to "Shopping", "FOOTLOCKER" to "Shopping",
        "JD Sports" to "Shopping", "JD SPORTS" to "Shopping",
        "Snipes" to "Shopping", "SNIPES" to "Shopping",
        "Sportsdirect" to "Shopping", "SPORTSDIRECT" to "Shopping",
        "Decathlon" to "Shopping", "DECATHLON" to "Shopping",
        "Athletes Foot" to "Shopping", "ATHLETES FOOT" to "Shopping",
        "Stadium" to "Shopping", "STADIUM" to "Shopping",
        "XXL Sport" to "Shopping", "XXL SPORT" to "Shopping",
        // Department Stores
        "Attica" to "Shopping", "ATTICA" to "Shopping", 
        "ATTICA DEPT" to "Shopping", "ATTICA GOLDEN HALL" to "Shopping",
        "Notos" to "Shopping", "NOTOS GALLERIES" to "Shopping",
        "Fokas" to "Shopping", "FOKAS" to "Shopping",
        "Galeries Lafayette" to "Shopping", "GALERIES LAFAYETTE" to "Shopping",
        "Harrods" to "Shopping", "HARRODS" to "Shopping",
        "Selfridges" to "Shopping", "SELFRIDGES" to "Shopping",
        "Harvey Nichols" to "Shopping", "HARVEY NICHOLS" to "Shopping",
        "El Corte Ingles" to "Shopping", "EL CORTE INGLES" to "Shopping",
        "Printemps" to "Shopping", "PRINTEMPS" to "Shopping",
        "KaDeWe" to "Shopping", "KADEWE" to "Shopping",
        "Breuninger" to "Shopping", "BREUNINGER" to "Shopping",
        "Nordstrom" to "Shopping", "NORDSTROM" to "Shopping",
        "Bloomingdales" to "Shopping", "BLOOMINGDALES" to "Shopping",
        "Macys" to "Shopping", "MACYS" to "Shopping",
        // Outlets
        "Factory Outlet" to "Shopping", "FACTORY OUTLET" to "Shopping",
        "McArthurGlen" to "Shopping", "DESIGNER OUTLET" to "Shopping",
        "MCARTHURGLEN ATHENS" to "Shopping",
        "Outlet" to "Shopping", "OUTLET STORE" to "Shopping",
        "Smart Park" to "Shopping", "SMART PARK" to "Shopping",
        "The Mall Athens" to "Shopping", "THE MALL" to "Shopping",
        "Golden Hall" to "Shopping", "GOLDEN HALL" to "Shopping",
        "Athens Metro Mall" to "Shopping", "METRO MALL" to "Shopping",
        "Mediterranean Cosmos" to "Shopping", "MED COSMOS" to "Shopping",
        // Shoes
        "Kalogirou" to "Shopping", "KALOGIROU" to "Shopping",
        "Tsakiris Mallas" to "Shopping", "TSAKIRIS MALLAS" to "Shopping",
        "Mourtzi" to "Shopping", "MOURTZI" to "Shopping",
        "Migato" to "Shopping", "MIGATO" to "Shopping",
        "Seven" to "Shopping", "SEVEN SHOES" to "Shopping",
        "Topshoes" to "Shopping", "TOPSHOES" to "Shopping",
        "Shoe Cult" to "Shopping", "SHOE CULT" to "Shopping",
        "Sante" to "Shopping", "SANTE" to "Shopping",
        "Bozikis" to "Shopping", "BOZIKIS" to "Shopping",
        "Clarks" to "Shopping", "CLARKS" to "Shopping",
        "Ecco" to "Shopping", "ECCO" to "Shopping",
        "Geox" to "Shopping", "GEOX" to "Shopping",
        "Camper" to "Shopping", "CAMPER" to "Shopping",
        "Birkenstock" to "Shopping", "BIRKENSTOCK" to "Shopping",
        "Crocs" to "Shopping", "CROCS" to "Shopping",
        "Dr Martens" to "Shopping", "DR MARTENS" to "Shopping",
        "UGG" to "Shopping", "UGG" to "Shopping",
        "Stuart Weitzman" to "Shopping", "STUART WEITZMAN" to "Shopping",
        "Jimmy Choo" to "Shopping", "JIMMY CHOO" to "Shopping",
        // Jewelry & Accessories
        "Pandora" to "Shopping", "PANDORA" to "Shopping",
        "Swarovski" to "Shopping", "SWAROVSKI" to "Shopping",
        "Tous" to "Shopping", "TOUS" to "Shopping",
        "Folli Follie" to "Shopping", "FOLLI FOLLIE" to "Shopping",
        "Links of London" to "Shopping", "LINKS OF LONDON" to "Shopping",
        "Thomas Sabo" to "Shopping", "THOMAS SABO" to "Shopping",
        "Trollbeads" to "Shopping", "TROLLBEADS" to "Shopping",
        "Alex and Ani" to "Shopping", "ALEX AND ANI" to "Shopping",
        "Nomination" to "Shopping", "NOMINATION" to "Shopping",
        "Accessorize" to "Shopping", "ACCESSORIZE" to "Shopping",
        "Bijou Brigitte" to "Shopping", "BIJOU BRIGITTE" to "Shopping",
        "Oriflame" to "Shopping", "ORIFLAME" to "Shopping",
        "Rolex" to "Shopping", "ROLEX" to "Shopping",
        "Omega" to "Shopping", "OMEGA WATCHES" to "Shopping",
        "Tag Heuer" to "Shopping", "TAG HEUER" to "Shopping",
        "Longines" to "Shopping", "LONGINES" to "Shopping",
        "Tissot" to "Shopping", "TISSOT" to "Shopping",
        "Casio" to "Shopping", "CASIO" to "Shopping",
        "Swatch" to "Shopping", "SWATCH" to "Shopping",
        "Fossil" to "Shopping", "FOSSIL" to "Shopping",
        "Daniel Wellington" to "Shopping", "DANIEL WELLINGTON" to "Shopping",
        "Jewelry" to "Shopping", "JEWELRY" to "Shopping",
        "Κοσμήματα" to "Shopping", "KOSMIMATA" to "Shopping",
        "Watch" to "Shopping", "WATCH STORE" to "Shopping",
        // ═══════════════════════════════════════════════════════════════
        // 💻 ELECTRONICS & TECH
        // ═══════════════════════════════════════════════════════════════
        // Greek E-commerce & Retail
        "Skroutz" to "Electronics", "SKROUTZ" to "Electronics", 
        "SKROUTZ.GR" to "Electronics", "PAYMENTS SKROUTZ" to "Electronics",
        "SKROUTZ MARKETPLACE" to "Electronics",
        "Public" to "Electronics", "PUBLIC" to "Electronics", 
        "PUBLIC RETAIL" to "Electronics", "PUBLIC.GR" to "Electronics",
        "Plaisio" to "Electronics", "PLAISIO" to "Electronics", 
        "PLAISIO COMPUTERS" to "Electronics",
        "Kotsovolos" to "Electronics", "KOTSOVOLOS" to "Electronics", 
        "DIXONS" to "Electronics", "SOUTH EAST EUROPE" to "Electronics",
        "Media Markt" to "Electronics", "MEDIA MARKT" to "Electronics",
        "Germanos" to "Electronics", "GERMANOS" to "Electronics", 
        "COSMOTE E-VALUE" to "Electronics",
        "E-shop" to "Electronics", "E-SHOP" to "Electronics", 
        "E-SHOP.GR" to "Electronics",
        "You.gr" to "Electronics", "YOU.GR" to "Electronics",
        "BestPrice" to "Electronics", "BESTPRICE" to "Electronics",
        "Electronet" to "Electronics", "ELECTRONET" to "Electronics",
        "Mediamarkt" to "Electronics", "MEDIAMARKT" to "Electronics",
        "Kaizer" to "Electronics", "KAIZER" to "Electronics",
        "Info Quest" to "Electronics", "INFOQUEST" to "Electronics",
        "Multirama" to "Electronics", "MULTIRAMA" to "Electronics",
        // Global E-commerce
        "Amazon" to "Electronics", "AMAZON" to "Electronics", 
        "AMZN" to "Electronics", "AMAZON.DE" to "Electronics",
        "AMAZON.CO.UK" to "Electronics", "AMAZON.ES" to "Electronics",
        "AMAZON.FR" to "Electronics", "AMAZON.IT" to "Electronics",
        "AMAZON.COM" to "Electronics", "AWS" to "Electronics",
        "AMAZON PRIME" to "Subscriptions", "PRIME VIDEO" to "Subscriptions",
        "Ebay" to "Electronics", "EBAY" to "Electronics", 
        "PAYPAL EBAY" to "Electronics",
        "AliExpress" to "Electronics", "ALIEXPRESS" to "Electronics",
        "ALIBABA" to "Electronics",
        "Banggood" to "Electronics", "BANGGOOD" to "Electronics",
        "Gearbest" to "Electronics", "GEARBEST" to "Electronics",
        "DHgate" to "Electronics", "DHGATE" to "Electronics",
        "JD.com" to "Electronics", "JD COM" to "Electronics",
        "Newegg" to "Electronics", "NEWEGG" to "Electronics",
        "CDW" to "Electronics",
        // Apple
        "Apple" to "Electronics", "APPLE STORE" to "Electronics", 
        "APPLE.COM" to "Electronics", "APPLE INC" to "Electronics",
        "Apple Store" to "Electronics", "APPLE RETAIL" to "Electronics",
        "iTunes" to "Subscriptions", "ITUNES" to "Subscriptions",
        "App Store" to "Subscriptions", "APPLE.COM/BILL" to "Subscriptions",
        // Other Brands
        "Samsung" to "Electronics", "SAMSUNG" to "Electronics",
        "SAMSUNG ELECTRONICS" to "Electronics",
        "Xiaomi" to "Electronics", "MI STORE" to "Electronics", 
        "XIAOMI" to "Electronics",
        "Huawei" to "Electronics", "HUAWEI" to "Electronics",
        "OnePlus" to "Electronics", "ONEPLUS" to "Electronics",
        "Oppo" to "Electronics", "OPPO" to "Electronics",
        "Vivo" to "Electronics", "VIVO" to "Electronics",
        "Realme" to "Electronics", "REALME" to "Electronics",
        "Google Store" to "Electronics", "GOOGLE STORE" to "Electronics",
        "Google" to "Subscriptions", "GOOGLE" to "Subscriptions",
        "Microsoft" to "Electronics", "MICROSOFT STORE" to "Electronics",
        "Sony" to "Electronics", "SONY" to "Electronics", 
        "SONY CENTER" to "Electronics",
        "LG" to "Electronics", "LG ELECTRONICS" to "Electronics",
        "Philips" to "Electronics", "PHILIPS" to "Electronics",
        "Panasonic" to "Electronics", "PANASONIC" to "Electronics",
        "Bose" to "Electronics", "BOSE" to "Electronics",
        "Bang Olufsen" to "Electronics", "BANG OLUFSEN" to "Electronics",
        "Harman Kardon" to "Electronics", "HARMAN" to "Electronics",
        "JBL" to "Electronics", "JBL" to "Electronics",
        "Beats" to "Electronics", "BEATS" to "Electronics",
        "Sennheiser" to "Electronics", "SENNHEISER" to "Electronics",
        "DJI" to "Electronics", "DJI STORE" to "Electronics",
        "GoPro" to "Electronics", "GOPRO" to "Electronics",
        "Canon" to "Electronics", "CANON" to "Electronics",
        "Nikon" to "Electronics", "NIKON" to "Electronics",
        "Sony Alpha" to "Electronics", "SONY ALPHA" to "Electronics",
        "Fujifilm" to "Electronics", "FUJIFILM" to "Electronics",
        "Olympus" to "Electronics", "OLYMPUS" to "Electronics",
        "Nintendo" to "Electronics", "NINTENDO" to "Electronics",
        "PlayStation" to "Electronics", "PLAYSTATION STORE" to "Electronics",
        "Xbox" to "Electronics", "XBOX STORE" to "Electronics",
        // Telecom Shops
        "Cosmote" to "Electronics", "COSMOTE" to "Electronics",
        "COSMOTE SHOP" to "Electronics",
        "Vodafone" to "Electronics", "VODAFONE SHOP" to "Electronics",
        "Wind" to "Electronics", "WIND SHOP" to "Electronics",
        "Nova" to "Electronics", "NOVA SHOP" to "Electronics",
        "Phone" to "Electronics", "PHONE STORE" to "Electronics",
        "Mobile" to "Electronics", "MOBILE SHOP" to "Electronics",
        "Service Mobile" to "Electronics", "REPAIR SHOP" to "Electronics",
        "iRepair" to "Electronics", "IREPAIR" to "Electronics",
        // ═══════════════════════════════════════════════════════════════
        // 📺 SUBSCRIPTIONS - Streaming, Cloud, Software, AI
        // ═══════════════════════════════════════════════════════════════
        // Video Streaming
        "Netflix" to "Subscriptions", "NETFLIX" to "Subscriptions", 
        "NETFLIX.COM" to "Subscriptions",
        "Disney+" to "Subscriptions", "DISNEY PLUS" to "Subscriptions", 
        "DISNEY+" to "Subscriptions", "DISNEYPLUS" to "Subscriptions",
        "HBO" to "Subscriptions", "HBO MAX" to "Subscriptions", 
        "WARNER BROS" to "Subscriptions",
        "Hulu" to "Subscriptions", "HULU" to "Subscriptions",
        "Amazon Prime" to "Subscriptions", "PRIME VIDEO" to "Subscriptions", 
        "AMAZONPRIME" to "Subscriptions",
        "Apple TV" to "Subscriptions", "APPLE TV+" to "Subscriptions",
        "Paramount+" to "Subscriptions", "PARAMOUNT" to "Subscriptions",
        "Peacock" to "Subscriptions", "PEACOCK TV" to "Subscriptions",
        "Discovery+" to "Subscriptions", "DISCOVERY PLUS" to "Subscriptions",
        "Rakuten TV" to "Subscriptions", "RAKUTEN" to "Subscriptions",
        "Mubi" to "Subscriptions", "MUBI" to "Subscriptions",
        // Greek TV & Streaming
        "Cosmote TV" to "Subscriptions", "COSMOTE TV" to "Subscriptions",
        "Nova" to "Subscriptions", "NOVA" to "Subscriptions", 
        "Eon" to "Subscriptions", "EON TV" to "Subscriptions",
        "Vodafone TV" to "Subscriptions", "VODAFONE TV" to "Subscriptions",
        "Ertflix" to "Subscriptions", "ERTFLIX" to "Subscriptions",
        "Ant1+" to "Subscriptions", "ANT1 PLUS" to "Subscriptions",
        "Cinobo" to "Subscriptions", "CINOBO" to "Subscriptions",
        // Music Streaming
        "Spotify" to "Subscriptions", "SPOTIFY" to "Subscriptions", 
        "SPOTIFY LUXEMBOURG" to "Subscriptions",
        "Apple Music" to "Subscriptions", "APPLE.COM/BILL" to "Subscriptions",
        "Youtube Music" to "Subscriptions", "YOUTUBE PREMIUM" to "Subscriptions",
        "Deezer" to "Subscriptions", "DEEZER" to "Subscriptions",
        "Tidal" to "Subscriptions", "TIDAL" to "Subscriptions",
        "Soundcloud" to "Subscriptions", "SOUNDCLOUD" to "Subscriptions",
        "Qobuz" to "Subscriptions", "QOBUZ" to "Subscriptions",
        // Cloud & Storage
        "Google One" to "Subscriptions", "GOOGLE ONE" to "Subscriptions", 
        "GOOGLE STORAGE" to "Subscriptions", "GOOGLE CLOUD" to "Subscriptions",
        "iCloud" to "Subscriptions", "APPLE ICLOUD" to "Subscriptions",
        "Dropbox" to "Subscriptions", "DROPBOX" to "Subscriptions",
        "OneDrive" to "Subscriptions", "MICROSOFT STORAGE" to "Subscriptions",
        "Box.com" to "Subscriptions", "BOX" to "Subscriptions",
        "Mega.nz" to "Subscriptions", "MEGA" to "Subscriptions",
        "Nextcloud" to "Subscriptions", "NEXTCLOUD" to "Subscriptions",
        // Productivity & Software
        "Microsoft 365" to "Subscriptions", "OFFICE 365" to "Subscriptions", 
        "MSFT" to "Subscriptions",
        "Adobe" to "Subscriptions", "ADOBE" to "Subscriptions", 
        "CREATIVE CLOUD" to "Subscriptions",
        "Canva" to "Subscriptions", "CANVA" to "Subscriptions",
        "Evernote" to "Subscriptions", "EVERNOTE" to "Subscriptions",
        "Notion" to "Subscriptions", "NOTION" to "Subscriptions",
        "Slack" to "Subscriptions", "SLACK" to "Subscriptions",
        "Zoom" to "Subscriptions", "ZOOM.US" to "Subscriptions",
        "Grammarly" to "Subscriptions", "GRAMMARLY" to "Subscriptions",
        "LinkedIn" to "Subscriptions", "LINKEDIN PREMIUM" to "Subscriptions",
        // VPN & Security
        "NordVPN" to "Subscriptions", "NORDVPN" to "Subscriptions",
        "ExpressVPN" to "Subscriptions", "EXPRESSVPN" to "Subscriptions",
        "Surfshark" to "Subscriptions", "SURFSHARK" to "Subscriptions",
        "CyberGhost" to "Subscriptions", "CYBERGHOST" to "Subscriptions",
        "Bitdefender" to "Subscriptions", "BITDEFENDER" to "Subscriptions",
        "Norton" to "Subscriptions", "NORTON" to "Subscriptions",
        "Avast" to "Subscriptions", "AVAST" to "Subscriptions",
        "Malwarebytes" to "Subscriptions", "MALWAREBYTES" to "Subscriptions",
        "1Password" to "Subscriptions", "1PASSWORD" to "Subscriptions",
        "LastPass" to "Subscriptions", "LASTPASS" to "Subscriptions",
        "Dashlane" to "Subscriptions", "DASHLANE" to "Subscriptions",
        // Gaming
        "Steam" to "Subscriptions", "STEAMGAMES" to "Subscriptions", 
        "VALVE" to "Subscriptions", "STEAM PURCHASE" to "Subscriptions",
        "Epic Games" to "Subscriptions", "EPIC GAMES" to "Subscriptions",
        "PlayStation" to "Subscriptions", "PLAYSTATION" to "Subscriptions", 
        "PSN" to "Subscriptions", "PS PLUS" to "Subscriptions", 
        "SONY NETWORK" to "Subscriptions",
        "Xbox" to "Subscriptions", "XBOX" to "Subscriptions", 
        "MICROSOFT XBOX" to "Subscriptions", "GAME PASS" to "Subscriptions",
        "Nintendo" to "Subscriptions", "NINTENDO ONLINE" to "Subscriptions",
        "EA Play" to "Subscriptions", "EA" to "Subscriptions",
        "Ubisoft+" to "Subscriptions", "UBISOFT" to "Subscriptions",
        "Blizzard" to "Subscriptions", "BATTLE.NET" to "Subscriptions",
        "Roblox" to "Subscriptions", "ROBLOX" to "Subscriptions",
        // Streaming & Social
        "Twitch" to "Subscriptions", "TWITCH" to "Subscriptions",
        "Discord" to "Subscriptions", "DISCORD" to "Subscriptions", 
        "NITRO" to "Subscriptions",
        "Patreon" to "Subscriptions", "PATREON" to "Subscriptions",
        "Substack" to "Subscriptions", "SUBSTACK" to "Subscriptions",
        "OnlyFans" to "Subscriptions", "ONLYFANS" to "Subscriptions",
        // AI & Dev Tools
        "ChatGPT" to "Subscriptions", "OPENAI" to "Subscriptions",
        "Claude" to "Subscriptions", "ANTHROPIC" to "Subscriptions",
        "Midjourney" to "Subscriptions", "MIDJOURNEY" to "Subscriptions",
        "GitHub" to "Subscriptions", "GITHUB" to "Subscriptions", 
        "COPILLOT" to "Subscriptions",
        "DigitalOcean" to "Subscriptions", "DIGITALOCEAN" to "Subscriptions",
        "Cloudflare" to "Subscriptions", "CLOUDFLARE" to "Subscriptions",
        "Heroku" to "Subscriptions", "HEROKU" to "Subscriptions",
        "Vercel" to "Subscriptions", "VERCEL" to "Subscriptions",
        // Education & Others
        "Duolingo" to "Subscriptions", "DUOLINGO" to "Subscriptions",
        "Udemy" to "Subscriptions", "UDEMY" to "Subscriptions",
        "Coursera" to "Subscriptions", "COURSERA" to "Subscriptions",
        "Masterclass" to "Subscriptions", "MASTERCLASS" to "Subscriptions",
        "Babbel" to "Subscriptions", "BABBEL" to "Subscriptions",
        "Fitness App" to "Subscriptions", "GYMSHARK" to "Subscriptions",
        "Strava" to "Subscriptions", "STRAVA" to "Subscriptions",
        "Tinder" to "Subscriptions", "TINDER" to "Subscriptions",
        "Bumble" to "Subscriptions", "BUMBLE" to "Subscriptions",
        // ═══════════════════════════════════════════════════════════════
        // 💡 UTILITIES - Bills, Services, Telecom
        // ═══════════════════════════════════════════════════════════════
        // Electricity
        "DEI" to "Utilities", "ΔΕΗ" to "Utilities", 
        "DIMOSIA EPICHEIRISI" to "Utilities", "DEH" to "Utilities",
        "Heron" to "Utilities", "IRON" to "Utilities", 
        "ΗΡΩΝ" to "Utilities", "HERON ENERGY" to "Utilities",
        "Protergia" to "Utilities", "PROTERGIA" to "Utilities", 
        "MYTILINEOS" to "Utilities",
        "Elpedison" to "Utilities", "ELPEDISON" to "Utilities",
        "Volton" to "Utilities", "VOLTON" to "Utilities",
        "NRG" to "Utilities", "NRG TRADING" to "Utilities",
        "Zenith" to "Utilities", "ZENITH" to "Utilities",
        "Watt+Volt" to "Utilities", "WATT AND VOLT" to "Utilities", 
        "WATT&VOLT" to "Utilities",
        "Fysiko Aerio" to "Utilities", "ΦΥΣΙΚΟ ΑΕΡΙΟ" to "Utilities",
        "Solar" to "Utilities", "SOLAR ENERGY" to "Utilities",
        // Water
        "EYDAP" to "Utilities", "ΕΥΔΑΠ" to "Utilities", 
        "NERO" to "Utilities", "WATER BILL" to "Utilities",
        "EYATH" to "Utilities", "ΕΥΑΘ" to "Utilities",
        // Gas & Heating
        "Fysiko Aerio" to "Utilities", "AERIO" to "Utilities", 
        "EPA" to "Utilities", "GAS BILL" to "Utilities",
        "Heating Oil" to "Utilities", "PETRELAIO" to "Utilities",
        // Telecom - Fixed & Mobile
        "Cosmote" to "Utilities", "COSMOTE" to "Utilities", 
        "OTE" to "Utilities", "ΟΤΕ" to "Utilities",
        "Vodafone" to "Utilities", "VODAFONE" to "Utilities", 
        "VODAFONE PANAFON" to "Utilities",
        "Wind" to "Utilities", "WIND" to "Utilities",
        "Nova" to "Utilities", "NOVA" to "Utilities", 
        "NOVA TELECOMB" to "Utilities",
        "Inalan" to "Utilities", "INALAN" to "Utilities",
        "Cyta" to "Utilities", "CYTA" to "Utilities",
        // Others
        "Koinoxrista" to "Utilities", "Κοινόχρηστα" to "Utilities", 
        "Polytechneio" to "Utilities", "SHARED EXPENSES" to "Utilities",
        "Cleaning Service" to "Utilities", "KATHARIOTHTA" to "Utilities",
        "Waste" to "Utilities", "DIMOS" to "Utilities",
        // ═══════════════════════════════════════════════════════════════
        // 🏥 HEALTH & FITNESS
        // ═══════════════════════════════════════════════════════════════
        // Pharmacies
        "Pharmacy" to "Health", "PHARMACY" to "Health", 
        "Φαρμακείο" to "Health", "Farmakeio" to "Health", 
        "DRUGSTORE" to "Health", "PHARME" to "Health",
        // Medical Services
        "Doctor" to "Health", "DOCTOR" to "Health", 
        "Γιατρός" to "Health", "Iatros" to "Health",
        "Dentist" to "Health", "Οδοντίατρος" to "Health", 
        "Odontiatros" to "Health",
        "Hospital" to "Health", "Nosokomeio" to "Health", 
        "Νοσοκομείο" to "Health",
        "Clinic" to "Health", "Κλινική" to "Health",
        "Diagnostic" to "Health", "ΔΙΑΓΝΩΣΤΙΚΟ" to "Health",
        // Centers & Platforms
        "Iatropolis" to "Health", "IATROPOLIS" to "Health",
        "Bioiatriki" to "Health", "BIOIATRIKI" to "Health",
        "Affidea" to "Health", "AFFIDEA" to "Health",
        "Euromedica" to "Health", "EUROMEDICA" to "Health",
        "Doctoranytime" to "Health", "DOCTORANYTIME" to "Health",
        // Specialists
        "Eye Clinic" to "Health", "Optical" to "Health", 
        "Οπτικά" to "Health", "Optika" to "Health",
        "Psychologist" to "Health", "Ψυχολόγος" to "Health",
        "Physiotherapy" to "Health", "Φυσικοθεραπεία" to "Health",
        // Fitness
        "Gym" to "Fitness", "GYM" to "Fitness", 
        "Gymnastirio" to "Fitness", "Γυμναστήριο" to "Fitness",
        "Yava" to "Fitness", "YAVA" to "Fitness",
        "Planet Fitness" to "Fitness", "Alterlife" to "Fitness", 
        "Holmes Place" to "Fitness",
        "Yoga" to "Fitness", "Pilates" to "Fitness", 
        "Crossfit" to "Fitness",
        "Sports Club" to "Fitness", "ΑΘΛΗΤΙΚΟΣ" to "Fitness",
        "Swimming" to "Fitness", "Κολυμβητήριο" to "Fitness",
        // ═══════════════════════════════════════════════════════════════
        // 🎬 ENTERTAINMENT
        // ═══════════════════════════════════════════════════════════════
        "Village Cinemas" to "Entertainment", "VILLAGE" to "Entertainment",
        "Odeon" to "Entertainment", "Ster Cinemas" to "Entertainment",
        "SNFCC" to "Entertainment", "STAVROS NIARCHOS" to "Entertainment",
        "Technopolis" to "Entertainment", "Ticketmaster" to "Entertainment",
        "Viva.gr" to "Entertainment", "VIVA" to "Entertainment",
        "Eventbrite" to "Entertainment", "Allou" to "Entertainment",
        "Kidom" to "Entertainment", "Escape Room" to "Entertainment",
        "Bowling" to "Entertainment", "Billiards" to "Entertainment",
        "Arcade" to "Entertainment", "Museum" to "Entertainment",
        "Μουσείο" to "Entertainment", "Theater" to "Entertainment",
        "Θέατρο" to "Entertainment", "Concert" to "Entertainment",
        // ═══════════════════════════════════════════════════════════════
        // 🏠 HOME & SERVICES
        // ═══════════════════════════════════════════════════════════════
        "IKEA" to "Shopping", // IKEA is Shopping but also Home
        "Leroy Merlin" to "Shopping", "S.G.B. AE" to "Shopping",
        "Praktiker" to "Shopping", "JYSK" to "Home",
        "BricoMarche" to "Home", "Maisons du Monde" to "Home",
        "Media Strom" to "Home", "Coco-mat" to "Home",
        "Plumber" to "Home", "Υδραυλικός" to "Home",
        "Electrician" to "Home", "Ηλεκτρολόγος" to "Home",
        "Cleaner" to "Home", "Καθαρίστρια" to "Home",
        "Pest Control" to "Home", "Locksmith" to "Home",
        "Moving" to "Home", "Furniture" to "Home",
        // ═══════════════════════════════════════════════════════════════
        // 💄 BEAUTY & PERSONAL CARE
        // ═══════════════════════════════════════════════════════════════
        "Sephora" to "Shopping", "SEPHORA" to "Shopping",
        "Hondos Center" to "Shopping", "HONDOS" to "Shopping",
        "Gallerie de Beaute" to "Shopping", "MAC" to "Shopping",
        "Hair Salon" to "Beauty", "Barber" to "Beauty",
        "Nail Salon" to "Beauty", "Spa" to "Beauty",
        "Waxing" to "Beauty", "The Body Shop" to "Beauty",
        "L'Occitane" to "Beauty", "Kiehl's" to "Beauty",
        "Rituals" to "Beauty", "Lush" to "Beauty",
        "Yves Rocher" to "Beauty", "Apivita" to "Beauty",
        "Korres" to "Beauty",
        // ═══════════════════════════════════════════════════════════════
        // ⚖️ LEGAL & GOVERNMENT
        // ═══════════════════════════════════════════════════════════════
        "EFKA" to "Legal & Gov", "ΕΦΚΑ" to "Legal & Gov",
        "AADE" to "Legal & Gov", "ΑΑΔΕ" to "Legal & Gov",
        "KEA" to "Legal & Gov", "Notary" to "Legal & Gov",
        "Lawyer" to "Legal & Gov", "Δικηγόρος" to "Legal & Gov",
        "Accountant" to "Legal & Gov", "Λογιστής" to "Legal & Gov",
        "Translation" to "Legal & Gov", "Certificate" to "Legal & Gov",
        "Driving License" to "Legal & Gov", "Paravolo" to "Legal & Gov",
        "ΠΑΡΑΒΟΛΟ" to "Legal & Gov", "TAXISNET" to "Legal & Gov",
        // ═══════════════════════════════════════════════════════════════
        // 🐾 PETS
        // ═══════════════════════════════════════════════════════════════
        "Pet City" to "Pets", "PET CITY" to "Pets",
        "Pet Shop" to "Pets", "Pet" to "Pets",
        "Vet" to "Pets", "Ktiniatros" to "Pets",
        "Animal" to "Pets", "Zooplus" to "Pets",
        "Grooming" to "Pets",
        // ═══════════════════════════════════════════════════════════════
        // 🎓 EDUCATION & BOOKS
        // ═══════════════════════════════════════════════════════════════
        "Udemy" to "Education", "Coursera" to "Education",
        "Book" to "Education", "Bookstore" to "Education",
        "Vivlio" to "Education", "Βιβλιοπωλείο" to "Education",
        "Ianos" to "Education", "Politeia" to "Education",
        "Evripidis" to "Education", "Public" to "Electronics", // Note: Public is also books, but mostly Electronics in categorization
        "School" to "Education", "University" to "Education",
        "Tuition" to "Education", "Didaktra" to "Education",
        // ═══════════════════════════════════════════════════════════════
        // 🏦 BANKING & FEES
        // ═══════════════════════════════════════════════════════════════
        "Revolut" to "Banking", "REVOLUT" to "Banking",
        "PayPal" to "Banking", "PAYPAL" to "Banking",
        "Curve" to "Banking", "Wise" to "Banking",
        "Alpha Bank" to "Banking", "Eurobank" to "Banking",
        "Piraeus" to "Banking", "Ethniki" to "Banking",
        "Commission" to "Banking", "Fee" to "Banking",
        "Interest" to "Banking",
        // ═══════════════════════════════════════════════════════════════
        // 🧸 KIDS & BABY
        // ═══════════════════════════════════════════════════════════════
        "Jumbo" to "Shopping", "Moustakas" to "Shopping",
        "DPAM" to "Kids", "Orchestra" to "Kids",
        "Lego Store" to "Kids", "LEGO" to "Kids",
        "Disney Store" to "Kids", "Hamleys" to "Kids",
        "Smyths" to "Kids", "Mothercare" to "Kids",
        "Baby" to "Kids", "Toys" to "Shopping"
    )
    // Additional mapping for normalized uppercase keys to capture variations
    fun getExpandedMap(): Map<String, String> {
        return merchantToCategoryMap.mapKeys { it.key.uppercase() }
    }
}

```

---

## main\java\com\yourname\expensetracker\data\repository\BudgetRepository.kt <a name="mainjavacomyournameexpensetrackerdatarepositorybudgetrepositorykt"></a>
```kotlin
package com.yourname.expensetracker.data.repository
import com.yourname.expensetracker.data.database.dao.BudgetDao
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.budget.BudgetSuggestion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class BudgetRepository @Inject constructor(
    private val budgetDao: BudgetDao,
    private val categoryDao: CategoryDao,
    private val expenseDao: ExpenseDao,
    private val budgetMonitor: BudgetMonitor
) {
    val allBudgets: Flow<List<Budget>> = budgetDao.getAllFlow()
    val activeBudgets: Flow<List<Budget>> = budgetDao.getActiveBudgetsFlow()
    fun getBudgetStatuses(): Flow<List<BudgetStatus>> {
        // We fetch the last 13 months to cover yearly budgets + rollover
        val thirteenMonthsAgo = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.MONTH, -13)
        }.timeInMillis
        return combine(
            budgetDao.getActiveBudgetsFlow(),
            categoryDao.getAllFlow(),
            expenseDao.getExpensesBetweenFlow(thirteenMonthsAgo, System.currentTimeMillis() + 86400000) // +1 day for safety
        ) { budgets, categories, allExpenses ->
            val purchases = allExpenses.filter { it.transactionType == com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE }
            val categoryMap = categories.associateBy { it.id }
            budgets.map { budget ->
                val window = budgetMonitor.calculatePeriodWindow(budget.period, budget.startDate)
                fun getSpentInRange(start: Long, end: Long): Double {
                    return purchases
                        .filter { 
                            (budget.categoryId == null || it.categoryId == budget.categoryId) && 
                            it.date >= start && it.date < end 
                        }
                        .sumOf { it.amount }
                }
                val spent = getSpentInRange(window.first, window.second)
                var limit = budget.amount
                // LOG-002: Implement Compounding Rollover - BUG-2 FIX
                if (budget.rollover) {
                    // we compute this by iterating forward from the budget's first period
                    val budgetFirstStart = budget.startDate
                    var movingWindow = budgetMonitor.calculatePeriodWindow(budget.period, budgetFirstStart)
                    var effectiveLimit = budget.amount
                    // Iterate forward until we reach the previous period of the current window
                    while (movingWindow.second <= window.first) {
                        val spentInPeriod = getSpentInRange(movingWindow.first, movingWindow.second)
                        val surplus = (effectiveLimit - spentInPeriod).coerceAtLeast(0.0)
                        effectiveLimit = budget.amount + surplus
                        // Move to next period
                        val nextStart = movingWindow.second
                        movingWindow = budgetMonitor.calculatePeriodWindow(budget.period, nextStart)
                    }
                    limit = effectiveLimit
                }
                val percent = if (limit > 0) (spent / limit).toFloat() else 0f
                val remaining = (limit - spent).coerceAtLeast(0.0)
                val health = when {
                    percent >= 1.0f -> BudgetHealthStatus.EXCEEDED
                    percent >= budget.notifyAtCritical -> BudgetHealthStatus.CRITICAL
                    percent >= budget.notifyAtWarning -> BudgetHealthStatus.WARNING
                    else -> BudgetHealthStatus.ON_TRACK
                }
                BudgetStatus(
                    budget = budget.copy(amount = limit), // Show effective limit
                    category = categoryMap[budget.categoryId],
                    spentAmount = spent,
                    remainingAmount = remaining,
                    percentUsed = percent,
                    healthStatus = health,
                    periodStart = window.first,
                    periodEnd = window.second
                )
            }
        }
    }
    suspend fun addBudget(budget: Budget): com.yourname.expensetracker.domain.model.Result<Long> {
        return try {
            val id = budgetDao.insert(budget)
            budgetMonitor.checkBudgets()
            com.yourname.expensetracker.domain.model.Result.Success(id)
        } catch (e: Exception) {
            android.util.Log.e("BudgetRepository", "Failed to add budget", e)
            com.yourname.expensetracker.domain.model.Result.Error(e, "Failed to add budget")
        }
    }
    suspend fun updateBudget(budget: Budget): com.yourname.expensetracker.domain.model.Result<Unit> {
        return try {
            // Reset notifications when budget is edited so user gets fresh alerts (BUG-7 Fix)
            val resetBudget = budget.copy(
                lastWarningNotifiedAt = null,
                lastCriticalNotifiedAt = null,
                lastExceededNotifiedAt = null
            )
            budgetDao.update(resetBudget)
            budgetMonitor.checkBudgets()
            com.yourname.expensetracker.domain.model.Result.Success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("BudgetRepository", "Failed to update budget ${budget.id}", e)
            com.yourname.expensetracker.domain.model.Result.Error(e, "Failed to update budget")
        }
    }
    suspend fun deleteBudget(budget: Budget): com.yourname.expensetracker.domain.model.Result<Unit> {
        return try {
            budgetDao.delete(budget)
            com.yourname.expensetracker.domain.model.Result.Success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("BudgetRepository", "Failed to delete budget ${budget.id}", e)
            com.yourname.expensetracker.domain.model.Result.Error(e, "Failed to delete budget")
        }
    }
    suspend fun toggleBudget(id: Long, isActive: Boolean): com.yourname.expensetracker.domain.model.Result<Unit> {
        return try {
            budgetDao.setActive(id, isActive)
            budgetMonitor.checkBudgets()
            com.yourname.expensetracker.domain.model.Result.Success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("BudgetRepository", "Failed to toggle budget $id", e)
            com.yourname.expensetracker.domain.model.Result.Error(e, "Failed to toggle budget")
        }
    }
    suspend fun deleteAll(): com.yourname.expensetracker.domain.model.Result<Unit> {
        return try {
            budgetDao.deleteAll()
            com.yourname.expensetracker.domain.model.Result.Success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("BudgetRepository", "Failed to delete all budgets", e)
            com.yourname.expensetracker.domain.model.Result.Error(e, "Failed to delete all budgets")
        }
    }
    suspend fun getSuggestions(): List<BudgetSuggestion> {
        val categories = categoryDao.getAllFlow().first()
        val suggestions = mutableListOf<BudgetSuggestion>()
        // Suggest budgets for top-spending categories that don't have one
        val activeBudgets = budgetDao.getActiveBudgets()
        val categoriesWithBudget = activeBudgets.mapNotNull { it.categoryId }.toSet()
        val now = System.currentTimeMillis()
        val oldestDate = expenseDao.getOldestExpenseDate() ?: now
        // Use up to 3 months of history, but at least 1 month if available
        // If data is less than 15 days, results might be unreliable, but we'll try to extrapolate conservatively
        val threeMonthsAgo = now - (90L * 24 * 60 * 60 * 1000)
        val effectiveStart = maxOf(oldestDate, threeMonthsAgo)
        val daysDiff = ((now - effectiveStart) / (24 * 60 * 60 * 1000)).coerceAtLeast(1)
        // If we have very little data (e.g. < 7 days), skip suggestions to avoid noise (LOG-010)
        if (daysDiff < 7) return emptyList()
        val monthsDivisor = daysDiff / 30.0
        for (category in categories) {
            if (categoriesWithBudget.contains(category.id)) continue
            val spent = expenseDao.getCategorySpentInPeriod(category.id, effectiveStart, now)
            // Calculate monthly average
            val monthlyAvg = if (monthsDivisor > 0) spent / monthsDivisor else 0.0
            // Only suggest if significant spend (> €20/month)
            if (monthlyAvg > 20.0) { 
                suggestions.add(
                    BudgetSuggestion(
                        categoryId = category.id,
                        categoryName = category.name,
                        categoryIcon = category.icon,
                        // increase buffer to 20% (LOG-016)
                        suggestedAmount = (monthlyAvg * 1.2).coerceAtLeast(20.0), 
                        basedOnMonths = Math.round(monthsDivisor).toInt().coerceAtLeast(1),
                        reason = "Based on your €${"%.0f".format(monthlyAvg)} monthly average spend."
                    )
                )
            }
        }
        return suggestions.sortedByDescending { it.suggestedAmount }.take(3)
    }
}

```

---

## main\java\com\yourname\expensetracker\data\repository\CategoryRepository.kt <a name="mainjavacomyournameexpensetrackerdatarepositorycategoryrepositorykt"></a>
```kotlin
package com.yourname.expensetracker.data.repository
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class CategoryRepository @Inject constructor(
    private val categoryDao: CategoryDao,
    private val merchantCategoryDao: MerchantCategoryDao,
    private val categorizationEngine: CategorizationEngine
) {
    val allCategories: Flow<List<Category>> = categoryDao.getAllFlow()
    suspend fun ensureDefaultCategories() = withContext(Dispatchers.IO) {
        if (categoryDao.getCount() == 0) {
            // Seed Categories
            val defaults = com.yourname.expensetracker.data.provider.MerchantCategoryProvider.categoryBlueprints
            categoryDao.insertAll(defaults)
            // Seed Merchant Dictionary
            // We need to resolve Category IDs first to map names to IDs
            val categories = categoryDao.getAllFlow().first() // Use flow first emission or simple get
            // Map: "Groceries" -> 1, "Transport" -> 2
            val categoryIdMap = categories.associate { it.name to it.id }
            val merchantMap = com.yourname.expensetracker.data.provider.MerchantCategoryProvider.getExpandedMap()
            val merchantEntities = merchantMap.mapNotNull { (merchant, categoryName) ->
               val catId = categoryIdMap[categoryName]
               if (catId != null) {
                   MerchantCategory(merchantPattern = merchant, categoryId = catId)
               } else {
                   null
               }
            }
            if (merchantEntities.isNotEmpty()) {
                // We need a bulk insert for speed
                merchantCategoryDao.insertAll(merchantEntities)
            }
        }
    }
    suspend fun addCategory(name: String, icon: String, color: String) = withContext(Dispatchers.IO) {
        val category = Category(name = name, icon = icon, color = color)
        categoryDao.insert(category)
    }
    suspend fun learnMerchantCategory(merchantName: String, categoryId: Long) = withContext(Dispatchers.IO) {
        val normalized = categorizationEngine.normalize(merchantName)
        val mapping = MerchantCategory(merchantPattern = normalized, categoryId = categoryId)
        merchantCategoryDao.insert(mapping)
    }
}

```

---

## main\java\com\yourname\expensetracker\data\repository\DashboardRepository.kt <a name="mainjavacomyournameexpensetrackerdatarepositorydashboardrepositorykt"></a>
```kotlin
package com.yourname.expensetracker.data.repository
import android.content.Context
import android.content.SharedPreferences
import com.yourname.expensetracker.data.database.model.DashboardWidgetConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
@Singleton
class DashboardRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)
    private val _configFlow = MutableStateFlow(getDashboardConfig())
    val configFlow: StateFlow<List<DashboardWidgetConfig>> = _configFlow.asStateFlow()
    fun getDashboardConfig(): List<DashboardWidgetConfig> {
        val json = prefs.getString("layout_config", null) ?: return getDefaultConfig()
        return try {
            val array = JSONArray(json)
            val list = mutableListOf<DashboardWidgetConfig>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    DashboardWidgetConfig(
                        id = obj.getString("id"),
                        order = obj.getInt("order"),
                        isVisible = obj.optBoolean("isVisible", true)
                    )
                )
            }
            list.sortedBy { it.order }
        } catch (e: Exception) {
            getDefaultConfig()
        }
    }
    fun saveDashboardConfig(config: List<DashboardWidgetConfig>) {
        val array = JSONArray()
        config.forEach {
            val obj = JSONObject()
            obj.put("id", it.id)
            obj.put("order", it.order)
            obj.put("isVisible", it.isVisible)
            array.put(obj)
        }
        prefs.edit().putString("layout_config", array.toString()).apply()
        _configFlow.value = config
    }
    private fun getDefaultConfig(): List<DashboardWidgetConfig> {
        return listOf(
            DashboardWidgetConfig("financial_weather", 0),
            DashboardWidgetConfig("safe_to_spend", 1),
            DashboardWidgetConfig("spending_pace", 2),
            DashboardWidgetConfig("review_alert", 3),
            DashboardWidgetConfig("spending_trend", 4),
            DashboardWidgetConfig("insight", 5),
            DashboardWidgetConfig("period_summary", 6),
            DashboardWidgetConfig("budget_health", 7),
            DashboardWidgetConfig("top_categories", 8),
            DashboardWidgetConfig("recent_transactions", 9)
        )
    }
}

```

---

## main\java\com\yourname\expensetracker\data\repository\FinancialWeatherRepository.kt <a name="mainjavacomyournameexpensetrackerdatarepositoryfinancialweatherrepositorykt"></a>
```kotlin
package com.yourname.expensetracker.data.repository
import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.logic.NarrativeGenerator
import com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
import com.yourname.expensetracker.data.database.dao.SavingsGoalDao
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.PlannedExpensePriority as EntityPlannedPriority
import com.yourname.expensetracker.data.database.entity.GoalProtectionLevel as EntityGoalProtection
import com.yourname.expensetracker.domain.model.*
import com.yourname.expensetracker.domain.model.PlannedExpensePriority as DomainPlannedPriority
import com.yourname.expensetracker.domain.model.GoalProtectionLevel as DomainGoalProtection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Calendar
enum class WeatherState {
    CLEAR_SKIES,      // 🌤️ Comfortable buffer
    PARTLY_CLOUDY,    // ⛅ Moderate, watch spending
    CLOUDY,           // ☁️ Tight, limited discretionary
    RAINY,            // 🌧️ Multiple bills, over pace
    STORMY,           // ⛈️ Budget danger, immediate action
    UNKNOWN
}
data class FinancialWeather(
    val state: WeatherState,
    val headline: String,
    val summary: String,
    val icon: String, // Emoji
    val riskLevel: Int, // 0-100
    val totalCommitted: Double,
    val totalLikely: Double,
    val predictedDiscretionary: Double,
    val discretionaryBudget: Double,
    val pastSpendingPoints: List<Double> = emptyList(),
    val projectedSpendingPoints: List<Double> = emptyList(),
    val upcomingItems: List<UpcomingItem> = emptyList(),
    val totalRecurringCount: Int = 0,
    val details: List<NarrativeSection> = emptyList()
)
@Singleton
class FinancialWeatherRepository @Inject constructor(
    private val notificationRepository: NotificationRepository,
    private val insightsEngine: InsightsEngine,
    private val budgetRepository: BudgetRepository,
    private val recurringExpenseEngine: RecurringExpenseEngine,
    private val recurringExpenseDao: com.yourname.expensetracker.data.database.dao.RecurringExpenseDao,
    private val plannedExpenseDao: PlannedExpenseDao,
    private val savingsGoalDao: SavingsGoalDao,
    private val synthesisEngine: SynthesisEngine,
    private val narrativeGenerator: NarrativeGenerator
) {
    private val calendar = Calendar.getInstance()
    private fun com.yourname.expensetracker.data.database.entity.PlannedExpense.toDomain(): PlannedExpense {
        return PlannedExpense(
            id = this.id,
            description = this.description,
            amount = this.amount,
            date = this.date,
            categoryId = this.categoryId,
            isRecurring = this.isRecurring,
            priority = when(this.priority) {
                EntityPlannedPriority.MUST -> DomainPlannedPriority.MUST
                EntityPlannedPriority.LIKELY -> DomainPlannedPriority.LIKELY
                EntityPlannedPriority.OPTIONAL -> DomainPlannedPriority.OPTIONAL
            }
        )
    }
    fun getFinancialWeather(): Flow<FinancialWeather> = combine(
        notificationRepository.getAllExpenses(),
        budgetRepository.getBudgetStatuses(),
        getAllRecurringPatterns(),
        plannedExpenseDao.getAllPlannedExpenses(),
        savingsGoalDao.getAllGoals()
    ) { expenses, budgetStatuses, recurringPatterns, plannedEntities, goalEntities ->
        val plannedExpenses = plannedEntities.map { it.toDomain() }
        val savingsGoals = goalEntities.map { entity ->
            SavingsGoal(
                id = entity.id,
                name = entity.name,
                targetAmount = entity.targetAmount,
                currentAmount = entity.currentAmount,
                targetDate = entity.targetDate,
                protectionLevel = when(entity.protectionLevel) {
                    EntityGoalProtection.STRICT -> DomainGoalProtection.STRICT
                    EntityGoalProtection.WARNING -> DomainGoalProtection.WARNING
                    EntityGoalProtection.TRACKING -> DomainGoalProtection.TRACKING
                }
            )
        }
        // 1. Calculate Past Daily Cumulative Spend
        val now = System.currentTimeMillis()
        val (monthStart, currentDay) = synchronized(calendar) {
            calendar.timeInMillis = now
            val currentDayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            calendar.set(Calendar.DAY_OF_MONTH, 1)
            val start = calendar.timeInMillis
             // Keep instance for simple read or reuse
            start to currentDayOfMonth
        }
        val purchases = expenses.filter { 
            it.transactionType == TransactionType.PURCHASE 
        }
        // 1. Calculate Past Daily Cumulative Spend - Optimized single pass
        val calInstance = Calendar.getInstance()
        val amountByDay = DoubleArray(currentDay + 1)
        for (expense in purchases) {
            if (expense.date >= monthStart) {
                calInstance.timeInMillis = expense.date
                val day = calInstance.get(Calendar.DAY_OF_MONTH)
                if (day <= currentDay) {
                    amountByDay[day] += expense.amount
                }
            }
        }
        var runningTotal = 0.0
        val pastSumDaily = (1..currentDay).map { day ->
            runningTotal += amountByDay[day]
            runningTotal
        }
        // 2. Get Engines data - Reusing already fetched expenses to avoid redundant DB queries
        val pace = insightsEngine.getSpendingPaceSuspend(expenses)
        // 3. Synthesize Forecast
        val forecast = synthesisEngine.synthesize(
            pastSumDaily = pastSumDaily,
            recurringPatterns = recurringPatterns,
            plannedExpenses = plannedExpenses,
            savingsGoals = savingsGoals,
            budgetStatuses = budgetStatuses,
            spendingPace = pace
        )
        // 4. Generate Narrative
        val narrative = narrativeGenerator.generate(forecast, budgetStatuses)
        // 5. Map to UI Model
        FinancialWeather(
            state = narrative.state,
            headline = narrative.headline,
            summary = narrative.summary,
            icon = narrative.icon,
            riskLevel = when (forecast.components.riskLevel) {
                RiskLevel.LOW -> 10
                RiskLevel.MEDIUM -> 40
                RiskLevel.HIGH -> 70
                RiskLevel.CRITICAL -> 100
            },
            totalCommitted = forecast.components.totalCommitted,
            totalLikely = forecast.components.totalLikely,
            predictedDiscretionary = forecast.components.predictedDiscretionary,
            discretionaryBudget = forecast.components.discretionaryBudget,
            pastSpendingPoints = forecast.components.pastSpendingPoints,
            projectedSpendingPoints = forecast.components.projectedSpendingPoints,
            upcomingItems = buildUpcomingItems(
                forecast.components.recurringExpenses,
                forecast.components.plannedExpenses
            ),
            totalRecurringCount = recurringPatterns.size,
            details = narrative.details
        )
    }.catch { e ->
        android.util.Log.e("FinancialWeatherRepo", "Error generating weather", e)
        emit(FinancialWeather(
            state = WeatherState.UNKNOWN,
            headline = "Weather Unavailable",
            summary = "We couldn't calculate your financial outlook right now.",
            icon = "❓",
            riskLevel = 0,
            totalCommitted = 0.0,
            totalLikely = 0.0,
            predictedDiscretionary = 0.0,
            discretionaryBudget = 0.0
        ))
    }
    private fun buildUpcomingItems(
        recurring: List<RecurringPattern>,
        planned: List<PlannedExpense>
    ): List<UpcomingItem> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startOfToday = cal.timeInMillis
        val horizon = startOfToday + (31 * 86_400_000L) // Show next 31 days in the list
        val items = mutableListOf<com.yourname.expensetracker.domain.model.UpcomingItem>()
        recurring.filter { it.nextExpectedDate in startOfToday..horizon }
            .forEach { items.add(UpcomingItem.Recurring(it)) }
        planned.filter { it.date in startOfToday..horizon }
            .forEach { items.add(UpcomingItem.Planned(it)) }
        return items.sortedBy { it.date }
    }
    fun getAllPlannedExpenses(): Flow<List<PlannedExpense>> = plannedExpenseDao.getAllPlannedExpenses()
        .map { entities -> entities.map { it.toDomain() } }
    fun getAllRecurringPatterns(): Flow<List<RecurringPattern>> = recurringExpenseDao.getAllFlow()
        .map { recurringExpenseEngine.getPatterns() }
}

```

---

## main\java\com\yourname\expensetracker\data\repository\MerchantCategoryRepository.kt <a name="mainjavacomyournameexpensetrackerdatarepositorymerchantcategoryrepositorykt"></a>
```kotlin
package com.yourname.expensetracker.data.repository
import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class MerchantCategoryRepository @Inject constructor(
    private val dao: MerchantCategoryDao,
    private val categorizationEngine: CategorizationEngine
) {
    /**
     * Learns a merchant -> category mapping.
     * Normalizes the merchant name before saving.
     */
    suspend fun learnPattern(merchantName: String, categoryId: Long) {
        val pattern = categorizationEngine.normalize(merchantName)
        if (pattern.isNotEmpty()) {
            dao.insert(
                MerchantCategory(
                    merchantPattern = pattern,
                    categoryId = categoryId,
                    confidence = 1.0f
                )
            )
        }
    }
    suspend fun getCategoryForMerchant(merchantName: String): MerchantCategory? {
        val pattern = categorizationEngine.normalize(merchantName)
        return dao.getCategoryForMerchant(pattern)
    }
}

```

---

## main\java\com\yourname\expensetracker\data\repository\NotificationRepository.kt <a name="mainjavacomyournameexpensetrackerdatarepositorynotificationrepositorykt"></a>
```kotlin
package com.yourname.expensetracker.data.repository
import androidx.room.*
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.*
import com.yourname.expensetracker.data.database.entity.*
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.intelligence.ConfidenceRouter
import com.yourname.expensetracker.domain.intelligence.RoutingDecision
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer as NewMerchantNormalizer
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MatchType
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class NotificationRepository @Inject constructor(
    private val database: AppDatabase,
    private val dao: RawNotificationDao,
    private val blockedPackageDao: BlockedPackageDao,
    private val expenseDao: ExpenseDao,
    private val merchantCategoryRepository: MerchantCategoryRepository,
    private val pendingReviewDao: PendingReviewDao,
    private val userCorrectionDao: UserCorrectionDao,
    private val sourceStatsDao: SourceStatsDao,
    private val scannedReceiptDao: ScannedReceiptDao,
    private val parserRegistry: AppParserRegistry,
    private val categorizationEngine: CategorizationEngine,
    private val confidenceRouter: ConfidenceRouter,
    private val merchantNormalizer: NewMerchantNormalizer,
    private val hybridClassifier: HybridExpenseClassifier,
    private val classifier: TransactionClassifier,
    private val budgetMonitor: BudgetMonitor 
) {
    private val repositoryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    // Shared expenses flow to prevent redundant DB queries (shared by multiple ViewModels)
    private val sharedExpenses = expenseDao.getAllFlow()
        .shareIn(
            scope = repositoryScope,
            started = SharingStarted.WhileSubscribed(30000),
            replay = 1
        )
    // === Notification access ===
    fun getAllNotifications(): Flow<List<RawNotification>> = dao.getAllFlow()
    fun getRecentNotifications(limit: Int = 100): Flow<List<RawNotification>> =
        dao.getRecentFlow(limit)
    fun getNotificationsByPackage(packageName: String): Flow<List<RawNotification>> =
        dao.getByPackageFlow(packageName)
    fun getAllPackages(): Flow<List<String>> = dao.getAllPackagesFlow()
    fun getCount(): Flow<Int> = dao.getCountFlow()
    suspend fun save(notification: RawNotification): Long = dao.insert(notification)
    suspend fun exists(packageName: String, timestamp: Long, title: String?, text: String?): Boolean =
        dao.exists(packageName, timestamp, title, text)
    // === Review Queue ===
    fun getPendingReviews(limit: Int = 100): Flow<List<PendingReviewWithReceipt>> = pendingReviewDao.getPendingFlow(limit)
    fun getPendingReviewCount(): Flow<Int> = pendingReviewDao.getPendingCountFlow()
    // === Source Stats ===
    fun getSourceStats(): Flow<List<SourceStats>> = sourceStatsDao.getAllFlow()
    // === Classifier Stats ===
    fun getClassifierStatsFlow(): Flow<ClassifierStats> = classifier.stats
    suspend fun getClassifierStats() = classifier.getStats()
    // === Manual Expense Entry ===
    /**
     * Search merchants from existing expenses for autocomplete
     */
    suspend fun searchMerchants(query: String): List<MerchantSuggestion> {
        if (query.isBlank()) return emptyList()
        return expenseDao.searchMerchants(query)
    }
    /**
     * Get recent distinct merchant names for suggestions
     */
    suspend fun getRecentMerchantNames(): List<String> {
        return expenseDao.getRecentMerchantNames()
    }
    /**
     * Add a manually entered expense
     */
    suspend fun addManualExpense(
        merchant: String,
        amount: Double,
        currency: String = "EUR",
        categoryId: Long?,
        transactionType: TransactionType = TransactionType.PURCHASE,
        paymentMethod: PaymentMethod = PaymentMethod.CASH,
        date: Long = System.currentTimeMillis(),
        notes: String? = null
    ): Long {
        // Fix 4.12: Large amount validation
        if (amount > 1000000.0) {
            android.util.Log.w("NotificationRepo", "Manual expense amount too large: $amount")
            return -1L
        }
        // 1. Normalize merchant name
        val lookupResult = merchantNormalizer.normalize(merchant, autoCreate = true)
        val normalizedMerchant = lookupResult.canonical.normalizedName
        // 2. Auto-categorize if no category provided
        val finalCategoryId = categoryId ?: hybridClassifier.classify(
            merchantName = normalizedMerchant,
            amount = amount
        ).categoryId.takeIf { it > 0 }
                // 3. Dedup check with tighter window for manual entries (1 minute)
                // For manual entries, we trust the user but want to avoid accidental double-taps.
                val isDuplicate = expenseDao.isDuplicate(
                    amount = amount,
                    merchant = normalizedMerchant,
                    date = date,
                    windowMs = 60000 // 1 minute window for manual double-entry prevention
                )
        if (isDuplicate) return -1L
        // 4. Create expense
        val expense = Expense(
            amount = amount,
            currency = currency,
            merchant = normalizedMerchant,
            transactionType = transactionType,
            date = date,
            rawNotificationId = null,
            categoryId = finalCategoryId,
            paymentMethod = paymentMethod,
            isManualEntry = true,
            notes = notes
        )
        val id = expenseDao.insert(expense)
        // 5. Check budgets
        budgetMonitor.checkBudgets()
        // 6. Learn the merchant→category mapping for future auto-categorization
        if (finalCategoryId != null && id > 0) {
            merchantCategoryRepository.learnPattern(normalizedMerchant, finalCategoryId)
        }
        return id
    }
    /**
     * Get category ID for a merchant (for auto-fill in manual entry)
     */
    suspend fun getCategoryForMerchant(merchant: String): Long? {
        return categorizationEngine.categorize(merchant)
    }
    // === Core Processing Pipeline ===
    suspend fun processAndSave(notification: RawNotification) {
        // 1. Initial existence check (fast, non-transactional)
        if (dao.exists(notification.packageName, notification.timestamp, notification.title, notification.text)) return
        // 2. Heavy CPU/IO Work - MOVE OUTSIDE TRANSACTION
        // Initialize classifier if needed
        classifier.initialize()
        // Try to parse
        val parsed = parserRegistry.parse(
            title = notification.title,
            text = notification.text,
            bigText = notification.bigText,
            subText = notification.subText,
            packageName = notification.packageName
        )
        if (parsed == null) {
            database.withTransaction {
                // Secondary check inside transaction to prevent race conditions
                if (dao.exists(notification.packageName, notification.timestamp, notification.title, notification.text)) return@withTransaction
                val rawId = try { dao.insert(notification) } catch (e: Exception) { return@withTransaction }
                sourceStatsDao.incrementTotal(notification.packageName)
                sourceStatsDao.incrementAutoRejected(notification.packageName)
                dao.markRelevance(rawId, false)
            }
            return
        }
        // Build full notification text for ML classifier
        val fullNotificationText = listOfNotNull(
            notification.title,
            notification.text,
            notification.bigText
        ).joinToString(" ")
        // Route through confidence system (includes source stats + ML)
        var routingResult = confidenceRouter.route(
            parsed = parsed,
            packageName = notification.packageName,
            notificationText = fullNotificationText
        )
        // Fix 4.12: Large amount validation -> Force Needs Review
        if (parsed.amount > 1000000.0 && routingResult.decision == RoutingDecision.AUTO_ACCEPT) {
            android.util.Log.w("NotificationRepo", "Auto-accept suppressed due to large amount: ${parsed.amount}")
            routingResult = routingResult.copy(decision = RoutingDecision.NEEDS_REVIEW)
        }
        // Apply merchant normalization & user corrections
        val lookupResult = merchantNormalizer.normalize(parsed.merchant)
        val correctedMerchant = lookupResult.canonical.normalizedName
        // 3. Database Transaction - ONLY MINIMAL DB WRITES
        database.withTransaction {
            // Secondary check inside transaction
            if (dao.exists(notification.packageName, notification.timestamp, notification.title, notification.text)) return@withTransaction
            // Save raw notification
            val rawId = try {
                dao.insert(notification)
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                return@withTransaction
            }
            // Update stats
            confidenceRouter.ensureSourceStats(notification.packageName)
            sourceStatsDao.incrementTotal(notification.packageName)
            when (routingResult.decision) {
                RoutingDecision.AUTO_ACCEPT -> {
                    // Check for duplicates
                    val isDuplicate = expenseDao.isDuplicate(
                        amount = parsed.amount,
                        merchant = correctedMerchant,
                        date = notification.timestamp,
                        windowMs = 60000 
                    )
                    if (isDuplicate) {
                        dao.markRelevance(rawId, false)
                        return@withTransaction
                    }
                    val classification = hybridClassifier.classify(
                        merchantName = correctedMerchant,
                        amount = parsed.amount,
                        notificationTitle = notification.title,
                        notificationText = notification.text,
                        packageName = notification.packageName
                    )
                    val categoryId = classification.categoryId.takeIf { it > 0 }
                    val expense = Expense(
                        amount = parsed.amount,
                        currency = parsed.currency,
                        merchant = correctedMerchant,
                        transactionType = parsed.type,
                        date = notification.timestamp,
                        rawNotificationId = rawId,
                        categoryId = categoryId,
                        paymentMethod = PaymentMethod.CARD,
                        isManualEntry = false
                    )
                    try {
                        expenseDao.insert(expense)
                        dao.markRelevance(rawId, true)
                        sourceStatsDao.incrementAccepted(notification.packageName)
                        // Check budgets (Note: potentially heavy, but standard for accept flow)
                        budgetMonitor.checkBudgets()
                        // Train classifier: auto-accepted = positive example
                        classifier.train(fullNotificationText, isTransaction = true)
                    } catch (e: android.database.sqlite.SQLiteConstraintException) {
                        dao.markRelevance(rawId, false)
                    }
                }
                RoutingDecision.NEEDS_REVIEW -> {
                    val classification = hybridClassifier.classify(
                        merchantName = correctedMerchant,
                        amount = parsed.amount,
                        notificationTitle = notification.title,
                        notificationText = notification.text,
                        packageName = notification.packageName
                    )
                    val suggestedCategoryId = classification.categoryId.takeIf { it > 0 }
                    val review = PendingReview(
                        rawNotificationId = rawId,
                        suggestedAmount = parsed.amount,
                        suggestedCurrency = parsed.currency,
                        suggestedMerchant = correctedMerchant,
                        suggestedType = parsed.type.name,
                        suggestedCategoryId = suggestedCategoryId,
                        confidence = routingResult.adjustedConfidence,
                        packageName = notification.packageName,
                        notificationTitle = notification.title,
                        notificationText = notification.text ?: notification.bigText,
                        suggestedDate = parsed.date
                    )
                    pendingReviewDao.insert(review)
                    sourceStatsDao.incrementPending(notification.packageName)
                }
                RoutingDecision.AUTO_REJECT -> {
                    dao.markRelevance(rawId, false)
                    sourceStatsDao.incrementAutoRejected(notification.packageName)
                }
            }
        }
    }
    // === Review Actions ===
    /**
     * User approves a pending review (possibly with modifications)
     */
    /**
     * User approves a pending review (possibly with modifications)
     */
    @Transaction
    suspend fun approveReview(
        reviewId: Long,
        finalAmount: Double? = null,
        finalMerchant: String? = null,
        finalCategoryId: Long? = null
    ) {
        val review = pendingReviewDao.getById(reviewId) ?: return
        // Critical Fix: Atomically check and update status to prevent double-processing
        val rowsUpdated = pendingReviewDao.updateStatusIfPending(reviewId, "PROCESSING")
        if (rowsUpdated == 0) return  // Already processed or not PENDING
        // If we fail later, we should ideally revert this, but for now we secure the lock.
        // We will update to APPROVED at the end.
        val amount: Double = finalAmount ?: review.suggestedAmount
        val merchant: String = finalMerchant ?: review.suggestedMerchant
        val categoryId: Long? = finalCategoryId ?: review.suggestedCategoryId
        // Fix 4.12: Large amount validation
        if (amount > 1000000.0) {
            android.util.Log.w("NotificationRepo", "Approval suppressed due to large amount: $amount")
            return
        }
        val type: com.yourname.expensetracker.data.database.entity.TransactionType = try {
            com.yourname.expensetracker.data.database.entity.TransactionType.valueOf(review.suggestedType)
        } catch (e: Exception) {
            android.util.Log.w("NotificationRepo", "Unknown transaction type: ${review.suggestedType}, falling back to PURCHASE")
            com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE
        }
        val notification = review.rawNotificationId?.let { dao.getById(it) }
        val transactionDate: Long = review.suggestedDate ?: notification?.timestamp ?: review.createdAt
        // Check for duplicates
        // Fix 1.1: Use consistent 60s window for manual/review approvals
        val isDuplicate = expenseDao.isDuplicate(
            amount = amount,
            merchant = merchant,
            date = transactionDate,
            windowMs = 60000
        )
        var operationSuccessful = true
        if (!isDuplicate) {
            // Create the expense
            val expense = com.yourname.expensetracker.data.database.entity.Expense(
                0L,
                amount,
                review.suggestedCurrency,
                merchant,
                type,
                transactionDate,
                review.rawNotificationId,
                categoryId,
                System.currentTimeMillis(),
                com.yourname.expensetracker.data.database.entity.PaymentMethod.CARD,
                review.scannedReceiptId != null,
                if (review.scannedReceiptId != null) "Scanned from receipt" else null
            )
            try {
                val expenseId = expenseDao.insert(expense)
                if (expenseId > 0) {
                    review.rawNotificationId?.let { dao.markRelevance(it, true) }
                    sourceStatsDao.incrementAccepted(review.packageName)
                    sourceStatsDao.decrementPending(review.packageName)
                    // Link to scanned receipt if this was a scan
                    review.scannedReceiptId?.let { receiptId ->
                        scannedReceiptDao.linkToExpense(receiptId, expenseId)
                    }
                    // Check budgets
                    budgetMonitor.checkBudgets()
                } else {
                    // Insertion failed (likely IGNORE strategy due to same ID, which shouldn't happen here as ID is 0L)
                    operationSuccessful = false
                }
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                // Unexpected constraint error, fail the operation
                operationSuccessful = false
            }
        } else {
             // It's a duplicate, we treat this as "processed" to clear the review
             sourceStatsDao.decrementPending(review.packageName)
        }
        if (operationSuccessful) {
            // Fix 1.19 status update: We update it to APPROVED even if it was a duplicate
            // so it leaves the queue. If it truly failed insertion (operationSuccessful = false),
            // we should probably still mark it as something else or just skip it for now.
            pendingReviewDao.updateStatus(reviewId, "APPROVED")
            // Record user correction for learning
            val correction = UserCorrection(
                packageName = review.packageName,
                originalMerchant = review.suggestedMerchant,
                correctedMerchant = if (finalMerchant != null && finalMerchant != review.suggestedMerchant)
                    finalMerchant else null,
                originalAmount = review.suggestedAmount,
                correctedAmount = if (finalAmount != null && finalAmount != review.suggestedAmount)
                    finalAmount else null,
                originalCategoryId = review.suggestedCategoryId,
                correctedCategoryId = if (finalCategoryId != null && finalCategoryId != review.suggestedCategoryId)
                    finalCategoryId else null,
                wasRejected = false,
                wasApproved = true,
                notificationTitle = review.notificationTitle,
                notificationText = review.notificationText
            )
            userCorrectionDao.insert(correction)
            // Retrain classifier
            try {
                classifier.retrainFromCorrections()
            } catch (e: Exception) {
                android.util.Log.e("NotificationRepo", "Failed to retrain classifier", e)
            }
            // Learn mapping
            if (categoryId != null) {
                try {
                    hybridClassifier.learnFromCorrection(
                        merchantName = merchant,
                        correctCategoryId = categoryId,
                        amount = amount,
                        packageName = review.packageName
                    )
                } catch (e: Exception) {
                    android.util.Log.e("NotificationRepo", "Failed to learn categorization", e)
                }
                merchantCategoryRepository.learnPattern(merchant, categoryId)
            }
        }
    }
    /**
     * User rejects a pending review
     */
    @Transaction
    suspend fun rejectReview(reviewId: Long) {
        val review = pendingReviewDao.getById(reviewId) ?: return
        // Atomic update check
        val rowsUpdated = pendingReviewDao.updateStatusIfPending(reviewId, "REJECTED")
        if (rowsUpdated == 0) return
        review.rawNotificationId?.let { id -> dao.markRelevance(id, false) }
        sourceStatsDao.incrementRejected(review.packageName)
        sourceStatsDao.decrementPending(review.packageName)
        // Record rejection for learning
        val correction = UserCorrection(
            packageName = review.packageName,
            originalMerchant = review.suggestedMerchant,
            correctedMerchant = null,
            originalAmount = review.suggestedAmount,
            correctedAmount = null,
            originalCategoryId = review.suggestedCategoryId,
            correctedCategoryId = null,
            wasRejected = true,
            wasApproved = false,
            notificationTitle = review.notificationTitle,
            notificationText = review.notificationText
        )
        userCorrectionDao.insert(correction)
        // Train classifier: user rejected = negative
        // LOG-003 Fix: Use retrainFrom corrections
        try {
            classifier.retrainFromCorrections()
        } catch (e: Exception) {
            android.util.Log.e("NotificationRepo", "Failed to retrain classifier", e)
        }
    }
    /**
     * Approves all currently pending reviews
     */
    @Transaction
    suspend fun approveAllReview() {
        val pending = pendingReviewDao.getPending()
        pending.forEach { item ->
            approveReview(item.review.id)
        }
    }
    // === Classifier Management ===
    suspend fun retrainClassifier() {
        classifier.retrainFromCorrections()
    }
    // === Existing methods (updated) ===
    @Transaction
    suspend fun markAsRelevant(id: Long, isRelevant: Boolean) {
        val notification = dao.getById(id) ?: return
        dao.markRelevance(id, isRelevant)
        // Train classifier directly from this manual action
        // Also record a correction for future retraining (LOG-003)
        // We record correction AND retrain immediately to ensure consistency
        val correction = UserCorrection(
            packageName = notification.packageName,
            originalMerchant = "Manual",
            correctedMerchant = null,
            originalAmount = 0.0,
            correctedAmount = null,
            originalCategoryId = null,
            correctedCategoryId = null,
            wasRejected = !isRelevant,
            wasApproved = isRelevant,
            notificationTitle = notification.title,
            notificationText = notification.text ?: notification.bigText
        )
        userCorrectionDao.insert(correction)
        try {
            classifier.retrainFromCorrections()
        } catch (e: Exception) {
            android.util.Log.e("NotificationRepo", "Failed to retrain classifier", e)
        }
    }
    suspend fun deleteAll() {
        dao.deleteAll()
        expenseDao.deleteAll()
        pendingReviewDao.deleteAll()
        userCorrectionDao.deleteAll()
        // merchantCategoryDao.deleteAll() // Removed as part of refactoring
        sourceStatsDao.resetAllPendingCounts()
    }
    suspend fun resetSourceStats() {
        sourceStatsDao.deleteAll()
    }
    suspend fun deleteAllExpenses() = expenseDao.deleteAll()
    suspend fun deleteExpense(expense: Expense) = expenseDao.delete(expense)
    suspend fun updateExpenseCategory(expense: Expense, newCategoryId: Long) {
        expenseDao.updateCategory(expense.id, newCategoryId)
        merchantCategoryRepository.learnPattern(expense.merchant, newCategoryId)
        // Also record as a correction for learning
        val correction = UserCorrection(
            packageName = "manual_edit",
            originalMerchant = expense.merchant,
            correctedMerchant = null,
            originalAmount = expense.amount,
            correctedAmount = null,
            originalCategoryId = expense.categoryId,
            correctedCategoryId = newCategoryId,
            wasRejected = false,
            wasApproved = true,
            notificationTitle = null,
            notificationText = null
        )
        userCorrectionDao.insert(correction)
    }
    suspend fun delete(notification: RawNotification) {
        // Check if there's a pending review attached to this notification
        val pendingReview = pendingReviewDao.getByRawId(notification.id)
        if (pendingReview != null && pendingReview.status == "PENDING") {
            sourceStatsDao.decrementPending(notification.packageName)
        }
        pendingReviewDao.deleteByRawId(notification.id) // Fix 1.20: Clean up orphaned reviews
        dao.delete(notification)
    }
    suspend fun blockPackage(packageName: String) =
        blockedPackageDao.block(BlockedPackage(packageName))
    suspend fun unblockPackage(packageName: String) =
        blockedPackageDao.unblock(packageName)
    suspend fun isPackageBlocked(packageName: String): Boolean =
        blockedPackageDao.isBlocked(packageName)
    fun getBlockedPackages(): Flow<List<BlockedPackage>> =
        blockedPackageDao.getAllFlow()
    fun getTotalSpent(): Flow<Double?> = expenseDao.getTotalSpentFlow()
    fun getAllExpenses(): Flow<List<Expense>> = sharedExpenses
    fun getExpensesWithCategory(limit: Int = 200): Flow<List<ExpenseWithCategory>> =
        expenseDao.getAllWithCategoryFlow(limit)
    fun getExpensesWithCategoryInPeriod(startMs: Long, endMs: Long): Flow<List<ExpenseWithCategory>> =
        expenseDao.getExpensesWithCategoryInPeriodFlow(startMs, endMs)
    suspend fun getExpensesPaged(limit: Int, offset: Int): List<ExpenseWithCategory> =
        expenseDao.getExpensesWithCategoryPaged(limit, offset)
    suspend fun processAndSaveAll(notifications: List<RawNotification>) {
        if (notifications.isEmpty()) return
        // Initialize once for the batch
        classifier.initialize()
        // Process in parallel chunks
        notifications.chunked(20).forEach { chunk ->
            coroutineScope {
                chunk.map { notification -> 
                    async { processAndSave(notification) } 
                }.awaitAll()
            }
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\data\repository\PlannedExpenseRepository.kt <a name="mainjavacomyournameexpensetrackerdatarepositoryplannedexpenserepositorykt"></a>
```kotlin
package com.yourname.expensetracker.data.repository
import com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class PlannedExpenseRepository @Inject constructor(
    private val plannedExpenseDao: PlannedExpenseDao
) {
    fun getAllPlannedExpenses(): Flow<List<PlannedExpense>> {
        return plannedExpenseDao.getAllPlannedExpenses()
    }
    fun getPlannedExpensesForPeriod(startMs: Long, endMs: Long): Flow<List<PlannedExpense>> {
        return plannedExpenseDao.getPlannedExpensesForPeriod(startMs, endMs)
    }
    suspend fun addPlannedExpense(expense: PlannedExpense): Long {
        return plannedExpenseDao.insertPlannedExpense(expense)
    }
    suspend fun deletePlannedExpense(expense: PlannedExpense) {
        plannedExpenseDao.deletePlannedExpense(expense)
    }
    suspend fun deletePlannedExpenseById(id: Long) {
        plannedExpenseDao.deletePlannedExpenseById(id)
    }
}

```

---

## main\java\com\yourname\expensetracker\data\repository\ReceiptRepository.kt <a name="mainjavacomyournameexpensetrackerdatarepositoryreceiptrepositorykt"></a>
```kotlin
package com.yourname.expensetracker.data.repository
import android.net.Uri
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer as NewMerchantNormalizer
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.receipt.BankStatementParser
import com.yourname.expensetracker.domain.receipt.OcrResult
import com.yourname.expensetracker.domain.receipt.ReceiptOcrService
import com.yourname.expensetracker.domain.receipt.ReceiptParser
// import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class ReceiptRepository @Inject constructor(
    private val scannedReceiptDao: ScannedReceiptDao,
    private val expenseDao: ExpenseDao,
    private val merchantCategoryRepository: MerchantCategoryRepository, // <-- Replaces DAO
    private val pendingReviewDao: PendingReviewDao,
    private val ocrService: ReceiptOcrService,
    private val receiptParser: ReceiptParser,
    private val statementParser: BankStatementParser,
    private val categorizationEngine: CategorizationEngine,
    private val merchantNormalizer: NewMerchantNormalizer,
    private val hybridClassifier: HybridExpenseClassifier,
    private val budgetMonitor: BudgetMonitor
) {
    val allReceipts: Flow<List<ScannedReceipt>> = scannedReceiptDao.getAllFlow()
    /**
     * Process an image URI: run OCR, parse receipt, save to DB
     *
     * @param imageUri URI of the image to process
     * @param autoCreateReview Whether to automatically create a PendingReview entry (true for batch, false for manual)
     */
    suspend fun processReceipt(
        imageUri: Uri,
        autoCreateReview: Boolean = false
    ): Pair<ScannedReceipt, ReceiptParser.ParsedReceipt> {
        try {
            // 1. Run OCR
            val ocrResult: OcrResult = ocrService.processImage(imageUri)
            // 2. Parse the OCR text
            val parsed = receiptParser.parse(ocrResult.fullText)
            // 3. Normalize merchant if found
            val lookupResult = parsed.merchantName?.let {
                merchantNormalizer.normalize(it, autoCreate = true)
            }
            val normalizedMerchant = lookupResult?.canonical?.normalizedName
            // 4. Save scanned receipt record
            val receipt = ScannedReceipt(
                imagePath = ocrResult.savedImagePath,
                rawOcrText = ocrResult.fullText,
                parsedTotal = parsed.total,
                parsedMerchant = normalizedMerchant ?: parsed.merchantName,
                parsedDate = parsed.date,
                parsedItems = if (parsed.lineItems.isNotEmpty())
                    receiptParser.lineItemsToJson(parsed.lineItems) else null,
                parsedTaxAmount = parsed.tax,
                currency = parsed.currency,
                confidence = parsed.confidence
            )
            val receiptId = scannedReceiptDao.insert(receipt)
            // 5. Optionally create a PendingReview (True for Batch, False for FAB Manual Scan)
            if (autoCreateReview) {
                val review = PendingReview(
                    rawNotificationId = null,
                    scannedReceiptId = receiptId,
                    suggestedAmount = parsed.total ?: 0.0,
                    suggestedCurrency = parsed.currency,
                    suggestedMerchant = normalizedMerchant ?: parsed.merchantName ?: "Unknown Merchant",
                    suggestedType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE.name,
                    suggestedDate = parsed.date, // Preserving the date found by parser
                    confidence = parsed.confidence,
                    packageName = "receipt.scan",
                    notificationTitle = "Scanned Receipt",
                    notificationText = ocrResult.fullText.take(200), // Preview snippet
                    suggestedCategoryId = normalizedMerchant?.let { 
                         hybridClassifier.classify(it, parsed.total ?: 0.0).categoryId.takeIf { id -> id > 0 }
                    }
                )
                pendingReviewDao.insert(review)
            }
            return Pair(receipt.copy(id = receiptId), parsed)
        } catch (e: Exception) {
            // Log error and save a "failed" record so we don't lose the image/Scan attempt
            android.util.Log.e("ReceiptRepository", "Failed to process receipt", e)
            // Fallback: Try to save the image using manual record logic
            return saveManualReceiptRecord(imageUri).let { (receipt, parsed) ->
                val failedReceipt = receipt.copy(
                    rawOcrText = "Scan Failed: ${e.message}", 
                    confidence = 0f
                )
                scannedReceiptDao.update(failedReceipt) // Update the record created by saveManualReceiptRecord
                Pair(failedReceipt, parsed)
            }
        }
    }
    suspend fun saveManualReceiptRecord(imageUri: android.net.Uri): Pair<ScannedReceipt, ReceiptParser.ParsedReceipt> {
        // 1. Try to at least copy the image for display if possible, or use original
        // For simplicity, we'll try to get ocrService to at least give us a path if it can load the bitmap
        val path = try {
            // We'll reuse the OCR service's image saving logic if possible
            // But if it fails, we fall back to the original URI string (not ideal but better than nothing)
            ocrService.processImage(imageUri).savedImagePath
        } catch (e: Exception) {
            imageUri.toString()
        }
        val receipt = ScannedReceipt(
            imagePath = path,
            rawOcrText = "[OCR Failed or Skipped]",
            parsedTotal = null,
            parsedMerchant = null,
            parsedDate = System.currentTimeMillis(),
            parsedItems = null,
            parsedTaxAmount = null,
            currency = "EUR",
            confidence = 0f
        )
        val receiptId = scannedReceiptDao.insert(receipt)
        return Pair(
            receipt.copy(id = receiptId),
            ReceiptParser.ParsedReceipt(
                merchantName = null,
                total = null,
                subtotal = null,
                tax = null,
                date = System.currentTimeMillis(),
                currency = "EUR",
                lineItems = emptyList(),
                confidence = 0f
            )
        )
    }
    /**
     * Create an expense from a scanned receipt (after user review/edit)
     */
    suspend fun createExpenseFromReceipt(
        receiptId: Long,
        merchant: String,
        amount: Double,
        currency: String = "EUR",
        categoryId: Long?,
        date: Long = System.currentTimeMillis(),
        paymentMethod: PaymentMethod = PaymentMethod.CARD,
        notes: String? = null
    ): Long {
        // 1. Normalize merchant
        val lookupResult = merchantNormalizer.normalize(merchant, autoCreate = true)
        val normalizedMerchant = lookupResult.canonical.normalizedName
        // 2. Auto-categorize if no category provided
        val finalCategoryId = categoryId ?: hybridClassifier.classify(
            merchantName = normalizedMerchant,
            amount = amount
        ).categoryId.takeIf { it > 0 }
        // 3. Check for duplicates
        val isDuplicate = expenseDao.isDuplicate(
            amount = amount,
            merchant = normalizedMerchant,
            date = date,
            windowMs = 60000 // 1 minute window for manual/scan entries
        )
        if (isDuplicate) return -1L
        // 4. Create expense
        val expense = Expense(
            amount = amount,
            currency = currency,
            merchant = normalizedMerchant,
            transactionType = TransactionType.PURCHASE,
            date = date,
            rawNotificationId = null,
            categoryId = finalCategoryId,
            paymentMethod = paymentMethod,
            isManualEntry = true, // Scanned receipts are treated as manual entries
            notes = notes ?: "Scanned from receipt"
        )
        val expenseId = expenseDao.insert(expense)
        // 5. Link receipt to expense
        if (expenseId > 0) {
            scannedReceiptDao.linkToExpense(receiptId, expenseId)
            // 6. Check budgets
            budgetMonitor.checkBudgets()
            // 7. Learn merchant → category mapping
            if (finalCategoryId != null) {
                try {
                    hybridClassifier.learnFromCorrection(
                        merchantName = normalizedMerchant,
                        correctCategoryId = finalCategoryId,
                        amount = amount
                    )
                } catch (e: Exception) {
                    android.util.Log.e("ReceiptRepo", "Failed to learn categorization", e)
                }
                merchantCategoryRepository.learnPattern(normalizedMerchant, finalCategoryId)
            }
        }
        return expenseId
    }
    fun createTempPhotoUri(): Uri {
        return ocrService.createTempImageUri()
    }
    suspend fun getReceiptById(id: Long): ScannedReceipt? {
        return scannedReceiptDao.getById(id)
    }
    suspend fun deleteReceipt(receipt: ScannedReceipt) {
        ocrService.deleteImage(receipt.imagePath)
        scannedReceiptDao.delete(receipt)
    }
    suspend fun getReceiptCount(): Int {
        return scannedReceiptDao.getCount()
    }
    data class BatchResult(
        val successCount: Int,
        val failureCount: Int,
        val errors: List<String>
    )
    /**
     * Process multiple receipts in a loop
     */
    suspend fun processBatch(uris: List<Uri>, onProgress: (Int, Int) -> Unit): BatchResult {
        var successes = 0
        var failures = 0
        val errors = mutableListOf<String>()
        uris.forEachIndexed { index, uri ->
            try {
                // Batch always creates reviews
                processReceipt(uri, autoCreateReview = true)
                successes++
                onProgress(index + 1, uris.size)
            } catch (e: Exception) {
                failures++
                errors.add("Failed to process $uri: ${e.message}")
                onProgress(index + 1, uris.size)
            }
        }
        return BatchResult(successes, failures, errors)
    }
    /**
     * Process an image URI as a bank statement: extracting multiple transactions
     */
    suspend fun processStatement(imageUri: Uri): BatchResult {
        // 1. Run OCR
        val ocrResult: OcrResult = ocrService.processImage(imageUri)
        // 2. Parse as multiple transactions using spatial data
        val parsedTransactions = statementParser.parse(ocrResult.blocks)
        if (parsedTransactions.isEmpty()) {
            return BatchResult(0, 1, listOf("No transactions found in screenshot"))
        }
        // 3. Save common scanned receipt record
        val receiptRecord = ScannedReceipt(
            imagePath = ocrResult.savedImagePath,
            rawOcrText = ocrResult.fullText,
            parsedTotal = null, // Varies per transaction
            parsedMerchant = "Bank Statement",
            parsedDate = System.currentTimeMillis(),
            parsedItems = null,
            parsedTaxAmount = null,
            currency = parsedTransactions.firstOrNull()?.currency ?: "EUR",
            confidence = 0.8f
        )
        val receiptId = scannedReceiptDao.insert(receiptRecord)
        // 4. Create a PendingReview for EACH transaction found
        var successCount = 0
        val errors = mutableListOf<String>()
        parsedTransactions.forEach { tx ->
            try {
                // Normalize merchant
                val lookupResult = merchantNormalizer.normalize(tx.merchant, autoCreate = true)
                val normalizedMerchant = lookupResult.canonical.normalizedName
                val classification = hybridClassifier.classify(
                    merchantName = normalizedMerchant,
                    amount = tx.amount
                )
                val review = PendingReview(
                    rawNotificationId = null,
                    scannedReceiptId = receiptId,
                    suggestedAmount = tx.amount,
                    suggestedCurrency = tx.currency,
                    suggestedMerchant = normalizedMerchant,
                    suggestedType = tx.type.name,
                    suggestedCategoryId = classification.categoryId.takeIf { id -> id > 0 },
                    suggestedDate = System.currentTimeMillis(),
                    confidence = tx.confidence,
                    packageName = "statement.import",
                    notificationTitle = "Bank Screenshot",
                    notificationText = "Imported from screenshot: ${tx.merchant}"
                )
                pendingReviewDao.insert(review)
                successCount++
            } catch (e: Exception) {
                errors.add("Failed to save transaction ${tx.merchant}: ${e.message}")
            }
        }
        return BatchResult(successCount, parsedTransactions.size - successCount, errors)
    }
    suspend fun clearAllScannedReceipts() {
        val receipts = scannedReceiptDao.getAll()
        receipts.forEach { ocrService.deleteImage(it.imagePath) }
        scannedReceiptDao.deleteAll()
    }
    /**
     * Concatenates all raw OCR text from the database for debugging/parsing refinement
     */
    suspend fun exportParserDebugData(): String {
        val receipts = scannedReceiptDao.getAll()
        val sb = StringBuilder()
        sb.append("=== EXPORTED PARSER DEBUG DATA (${receipts.size} RECEIPTS) ===\n\n")
        receipts.forEachIndexed { index, receipt ->
            sb.append("--- RECEIPT #${index + 1} (ID: ${receipt.id}) ---\n")
            sb.append("MERCHANT: ${receipt.parsedMerchant ?: "Unknown"}\n")
            sb.append("TOTAL: ${receipt.parsedTotal ?: "Not Found"}\n")
            sb.append("DATE: ${receipt.parsedDate ?: "Not Found"}\n")
            sb.append("RAW OCR TEXT:\n")
            sb.append(receipt.rawOcrText)
            sb.append("\n\n")
        }
        return sb.toString()
    }
}

```

---

## main\java\com\yourname\expensetracker\di\AppModule.kt <a name="mainjavacomyournameexpensetrackerdiappmodulekt"></a>
```kotlin
package com.yourname.expensetracker.di
import android.content.Context
import androidx.room.Room
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.*
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import com.yourname.expensetracker.domain.parser.GenericTransactionParser
import com.yourname.expensetracker.domain.parser.parsers.GoogleWalletParser
import com.yourname.expensetracker.domain.parser.parsers.GreekBankParser
import com.yourname.expensetracker.domain.parser.parsers.RevolutParser
import com.yourname.expensetracker.domain.parser.parsers.SmsParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "expense_tracker_db"
        ).addMigrations(
            AppDatabase.MIGRATION_6_7, 
            AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9,
            AppDatabase.MIGRATION_9_10,
            AppDatabase.MIGRATION_10_11,
            AppDatabase.MIGRATION_11_12,
            AppDatabase.MIGRATION_12_13,
            AppDatabase.MIGRATION_13_14,
            AppDatabase.MIGRATION_14_15,
            AppDatabase.MIGRATION_15_16,
            AppDatabase.MIGRATION_16_17
        )
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    super.onOpen(db)
                    android.util.Log.d("AppDatabase", "Database opened successfully. Version: ${db.version}")
                }
            })
            .fallbackToDestructiveMigration()
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    }
    @Provides
    @Singleton
    fun providePlannedExpenseDao(database: AppDatabase): PlannedExpenseDao {
        return database.plannedExpenseDao()
    }
    @Provides
    @Singleton
    fun provideSavingsGoalDao(database: AppDatabase): SavingsGoalDao {
        return database.savingsGoalDao()
    }
    @Provides
    @Singleton
    fun provideRawNotificationDao(database: AppDatabase): RawNotificationDao {
        return database.rawNotificationDao()
    }
    @Provides
    @Singleton
    fun provideBlockedPackageDao(database: AppDatabase): BlockedPackageDao {
        return database.blockedPackageDao()
    }
    @Provides
    @Singleton
    fun provideExpenseDao(database: AppDatabase): ExpenseDao {
        return database.expenseDao()
    }
    @Provides
    @Singleton
    fun provideBudgetDao(database: AppDatabase): BudgetDao {
        return database.budgetDao()
    }
    @Provides
    @Singleton
    fun provideScannedReceiptDao(database: AppDatabase): ScannedReceiptDao {
        return database.scannedReceiptDao()
    }
    @Provides
    @Singleton
    fun provideCategoryDao(database: AppDatabase): CategoryDao = database.categoryDao()
    @Provides
    @Singleton
    fun provideMerchantCategoryDao(database: AppDatabase): MerchantCategoryDao = database.merchantCategoryDao()
    @Provides
    @Singleton
    fun providePendingReviewDao(database: AppDatabase): PendingReviewDao = database.pendingReviewDao()
    @Provides
    @Singleton
    fun provideUserCorrectionDao(database: AppDatabase): UserCorrectionDao = database.userCorrectionDao()
    @Provides
    @Singleton
    fun provideSourceStatsDao(database: AppDatabase): SourceStatsDao = database.sourceStatsDao()
    @Provides
    @Singleton
    fun provideRecurringExpenseDao(database: AppDatabase): RecurringExpenseDao = database.recurringExpenseDao()
    @Provides
    @Singleton
    fun provideMerchantNormalizationDao(database: AppDatabase): MerchantNormalizationDao = database.merchantNormalizationDao()
}

```

---

## main\java\com\yourname\expensetracker\domain\analytics\AnalyticsModels.kt <a name="mainjavacomyournameexpensetrackerdomainanalyticsanalyticsmodelskt"></a>
```kotlin
package com.yourname.expensetracker.domain.analytics
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
// === New Insights Models ===
data class MonthPeriod(
    val year: Int,
    val month: Int, // 0-indexed (Calendar.JANUARY = 0)
    val startMs: Long,
    val endMs: Long
) {
    companion object {
        val MONTH_NAMES = arrayOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
        )
    }
    val label: String
        get() = "${MONTH_NAMES[month]} $year"
    val shortLabel: String
        get() = MONTH_NAMES[month]
}
data class CategoryInsight(
    val category: Category,
    val currentTotal: Double,
    val currentCount: Int,
    val previousTotal: Double?,
    val previousCount: Int?,
    val averageOverMonths: Double?,
    val monthsOfData: Int,
    val percentageOfTotal: Float,
    val changeFromPrevious: Float?, // percentage change
    val changeFromAverage: Float? // percentage deviation from average
)
data class MerchantInsight(
    val merchant: String,
    val avgAmount: Double,
    val minAmount: Double,
    val maxAmount: Double,
    val totalSpent: Double,
    val transactionCount: Int,
    val isLikelyRecurring: Boolean,
    val stdDeviation: Double? // null if < 3 transactions
)
data class SpendingPace(
    val currentMonthSpent: Double,
    val daysElapsed: Int,
    val daysInMonth: Int,
    val projectedTotal: Double,
    val previousMonthTotal: Double?,
    val averageMonthlyTotal: Double?,
    val pacePercentage: Float, // how far through the month's typical spend
    val paceStatus: PaceStatus
)
enum class PaceStatus {
    UNDER_PACE,    // spending less than typical
    ON_PACE,       // within 10% of typical
    OVER_PACE,     // spending more than typical
    NO_BASELINE    // not enough data
}
data class AnomalyTransaction(
    val expense: Expense,
    val merchantAvg: Double,
    val deviationMultiple: Float, // how many times the average
    val category: Category?
)
data class RecurringExpense(
    val merchant: String,
    val avgAmount: Double,
    val frequency: Int, // transactions total
    val amountVariation: Double, // max - min
    val isStable: Boolean // low variation
)
data class DayOfWeekInsight(
    val dayName: String,
    val dayIndex: Int, // 0=Mon, 6=Sun
    val totalSpent: Double,
    val transactionCount: Int,
    val avgPerTransaction: Double
)
data class MonthlyComparison(
    val currentMonth: MonthPeriod,
    val previousMonth: MonthPeriod?,
    val currentTotal: Double,
    val previousTotal: Double?,
    val changeAmount: Double?,
    val changePercentage: Float?,
    val currentCount: Int,
    val previousCount: Int?
)
data class InsightsSnapshot(
    val generatedAt: Long = System.currentTimeMillis(),
    val currentMonth: MonthPeriod,
    val monthlyComparison: MonthlyComparison,
    val categoryInsights: List<CategoryInsight>,
    val topMerchants: List<MerchantInsight>,
    val spendingPace: SpendingPace,
    val anomalies: List<AnomalyTransaction>,
    val recurringExpenses: List<RecurringExpense>,
    val dayOfWeekPattern: List<DayOfWeekInsight>,
    val largestTransaction: Expense?,
    val averageTransactionSize: Double,
    val medianTransactionSize: Double,
    val totalMonthsOfData: Int
)
// === Legacy / Existing Models ===
data class SpendingPeriod(
    val label: String,
    val startDate: Long,
    val endDate: Long,
    val total: Double,
    val previousTotal: Double?,         // For comparison
    val byCategory: List<CategoryBreakdown>,
    val byMerchant: List<MerchantBreakdown>,
    val dailyTotals: Map<String, Double>,   // "2024-01-15" → 45.60
    val transactionCount: Int
) {
    val changePercent: Float?
        get() = if (previousTotal != null && previousTotal > 0)
            ((total - previousTotal) / previousTotal * 100).toFloat()
        else null
}
data class CategoryBreakdown(
    val category: Category,
    val total: Double,
    val count: Int,
    val percentage: Float           // 0-100
)
data class MerchantBreakdown(
    val name: String,
    val totalSpent: Double,
    val transactionCount: Int,
    val averageTransaction: Double,
    val categoryId: Long?
)
data class SpendingInsight(
    val type: InsightType,
    val icon: String,
    val title: String,
    val description: String,
    val severity: Float             // 0-1, how important/urgent
)
enum class InsightType {
    SPENDING_INCREASE,
    SPENDING_DECREASE,
    UNUSUAL_TRANSACTION,
    RECURRING_DETECTED,
    CATEGORY_TREND,
    BUDGET_WARNING,
    MERCHANT_FREQUENCY,
    DAILY_AVERAGE,
    TOP_MERCHANT,
    STREAK
}
data class RecurringCandidate(
    val merchant: String,
    val amount: Double,
    val intervalDays: Int,
    val occurrences: Int,
    val nextExpectedDate: Long?,
    val confidence: Float = 0.0f
)
enum class TimePeriod {
    TODAY, WEEK, MONTH, YEAR, ALL
}

```

---

## main\java\com\yourname\expensetracker\domain\analytics\InsightsEngine.kt <a name="mainjavacomyournameexpensetrackerdomainanalyticsinsightsenginekt"></a>
```kotlin
package com.yourname.expensetracker.domain.analytics
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
// === Engine ===
@Singleton
class InsightsEngine @Inject constructor(
    private val expenseDao: ExpenseDao
) {
    companion object {
        private val DAY_NAMES = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }
    suspend fun generateInsights(
        categories: List<Category>,
        allExpenses: List<Expense>
    ): InsightsSnapshot = coroutineScope {
        val now = System.currentTimeMillis()
        val currentMonth = getMonthPeriod(now)
        val previousMonth = getPreviousMonthPeriod(currentMonth)
        val categoryMap = categories.associateBy { it.id }
        // Start all independent queries in parallel
        val monthlyComparisonDeferred = async { buildMonthlyComparison(currentMonth, previousMonth) }
        val categoryInsightsDeferred = async { buildCategoryInsights(currentMonth, previousMonth, categoryMap, allExpenses) }
        val topMerchantsDeferred = async { buildMerchantInsights(allExpenses) }
        val spendingPaceDeferred = async { buildSpendingPace(currentMonth, previousMonth, allExpenses) }
        val anomaliesDeferred = async { findAnomalies(currentMonth, categoryMap) }
        val recurringExpensesDeferred = async { findRecurringExpenses() }
        val threeMonthsAgo = getMonthPeriod(now, -2)
        val dayOfWeekPatternDeferred = async { buildDayOfWeekPattern(threeMonthsAgo.startMs, currentMonth.endMs) }
        val largestTransactionDeferred = async { 
            expenseDao.getLargestExpenseForPeriod(currentMonth.startMs, currentMonth.endMs) 
        }
        // Await all results
        val monthlyComparison = monthlyComparisonDeferred.await()
        val categoryInsights = categoryInsightsDeferred.await()
        val topMerchants = topMerchantsDeferred.await()
        val spendingPace = spendingPaceDeferred.await()
        val anomalies = anomaliesDeferred.await()
        val recurringExpenses = recurringExpensesDeferred.await()
        val dayOfWeekPattern = dayOfWeekPatternDeferred.await()
        val largestTransaction = largestTransactionDeferred.await()
        // Transaction size stats
        val currentMonthPurchases = allExpenses.filter {
            it.transactionType == TransactionType.PURCHASE
                    && it.date >= currentMonth.startMs
                    && it.date < currentMonth.endMs
        }
        val avgTxSize = if (currentMonthPurchases.isNotEmpty())
            currentMonthPurchases.map { it.amount }.average() else 0.0
        val medianTxSize = calculateMedian(currentMonthPurchases.map { it.amount })
        // How many months of data we have
        val totalMonthsOfData = countDistinctMonths(allExpenses)
        InsightsSnapshot(
            currentMonth = currentMonth,
            monthlyComparison = monthlyComparison,
            categoryInsights = categoryInsights,
            topMerchants = topMerchants,
            spendingPace = spendingPace,
            anomalies = anomalies,
            recurringExpenses = recurringExpenses,
            dayOfWeekPattern = dayOfWeekPattern,
            largestTransaction = largestTransaction,
            averageTransactionSize = avgTxSize,
            medianTransactionSize = medianTxSize,
            totalMonthsOfData = totalMonthsOfData
        )
    }
    // === Legacy Compatibility ===
    fun getLegacyInsights(snapshot: InsightsSnapshot): List<SpendingInsight> {
        val insights = mutableListOf<SpendingInsight>()
        // 1. Monthly Comparison (Spending Increase/Decrease)
        val comparison = snapshot.monthlyComparison
        if (comparison.changePercentage != null) {
            if (comparison.changePercentage > 20) {
                insights.add(
                    SpendingInsight(
                        InsightType.SPENDING_INCREASE, "📈",
                        "Spending up ${comparison.changePercentage.toInt()}%",
                        "€${fmt(comparison.currentTotal)} this month vs €${fmt(comparison.previousTotal ?: 0.0)} last month",
                        (comparison.changePercentage / 100).coerceAtMost(1.0f).toFloat()
                    )
                )
            } else if (comparison.changePercentage < -15) {
                insights.add(
                    SpendingInsight(
                        InsightType.SPENDING_DECREASE, "📉",
                        "Good job! Spending down ${(-comparison.changePercentage).toInt()}%",
                        "You saved €${fmt((comparison.previousTotal ?: 0.0) - comparison.currentTotal)} compared to last month",
                        0.3f
                    )
                )
            }
        }
        // 2. Spending Pace
        val pace = snapshot.spendingPace
        if (pace.paceStatus == PaceStatus.OVER_PACE) {
             insights.add(
                SpendingInsight(
                    InsightType.BUDGET_WARNING, "⚠️",
                    "Spending Pace Warning",
                    "You're at ${pace.daysElapsed} days but spent ${pace.pacePercentage.toInt()}% of typical month",
                    0.8f
                )
            )
        }
        // 3. Category Trends
        snapshot.categoryInsights.take(3).forEach { catInsight ->
            if (catInsight.changeFromPrevious != null) {
                 if (catInsight.changeFromPrevious > 40 && catInsight.currentTotal > 50) {
                    insights.add(
                        SpendingInsight(
                            InsightType.CATEGORY_TREND, catInsight.category.icon,
                            "${catInsight.category.name} up ${catInsight.changeFromPrevious.toInt()}%",
                            "€${fmt(catInsight.currentTotal)} vs €${fmt(catInsight.previousTotal ?: 0.0)}",
                            0.7f
                        )
                    )
                 }
            }
        }
        // 4. Recurring
        snapshot.recurringExpenses.take(3).forEach { recurring ->
             insights.add(
                SpendingInsight(
                    InsightType.RECURRING_DETECTED, "🔄",
                    "Recurring: ${recurring.merchant}",
                    "€${fmt(recurring.avgAmount)} ~every ${30.0/recurring.frequency} days", // approx
                    0.5f
                )
            )
        }
        // 5. Largest Transaction
        val largest = snapshot.largestTransaction
        if (largest != null && largest.amount > 50) {
             insights.add(
                SpendingInsight(
                    InsightType.UNUSUAL_TRANSACTION, "⚡",
                    "Largest: ${largest.merchant}",
                    "€${fmt(largest.amount)} on ${formatDate(largest.date)}",
                    0.25f
                )
            )
        }
        return insights.sortedByDescending { it.severity }
    }
    // === Month Period Helpers ===
    fun getMonthPeriod(timeMs: Long, monthOffset: Int = 0): MonthPeriod {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timeMs
        cal.add(Calendar.MONTH, monthOffset)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startMs = cal.timeInMillis
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH)
        cal.add(Calendar.MONTH, 1)
        val endMs = cal.timeInMillis
        return MonthPeriod(year, month, startMs, endMs)
    }
    private fun getPreviousMonthPeriod(current: MonthPeriod): MonthPeriod {
        val cal = Calendar.getInstance()
        cal.timeInMillis = current.startMs
        cal.add(Calendar.MONTH, -1)
        return getMonthPeriod(cal.timeInMillis)
    }
    // === Monthly Comparison ===
    private suspend fun buildMonthlyComparison(
        current: MonthPeriod,
        previous: MonthPeriod
    ): MonthlyComparison = coroutineScope {
        val currentTotalDeferred = async { expenseDao.getTotalForPeriod(current.startMs, current.endMs) }
        val currentCountDeferred = async { expenseDao.getCountForPeriod(current.startMs, current.endMs) }
        val previousTotalDeferred = async { expenseDao.getTotalForPeriod(previous.startMs, previous.endMs) }
        val previousCountDeferred = async { expenseDao.getCountForPeriod(previous.startMs, previous.endMs) }
        val currentTotal = currentTotalDeferred.await()
        val currentCount = currentCountDeferred.await()
        val previousTotal = previousTotalDeferred.await()
        val previousCount = previousCountDeferred.await()
        val hasPrevious = previousCount > 0
        val changeAmount = if (hasPrevious) currentTotal - previousTotal else null
        val changePercentage = if (hasPrevious && previousTotal > 0)
            ((currentTotal - previousTotal) / previousTotal * 100).toFloat() else null
        MonthlyComparison(
            currentMonth = current,
            previousMonth = if (hasPrevious) previous else null,
            currentTotal = currentTotal,
            previousTotal = if (hasPrevious) previousTotal else null,
            changeAmount = changeAmount,
            changePercentage = changePercentage,
            currentCount = currentCount,
            previousCount = if (hasPrevious) previousCount else null
        )
    }
    // === Category Insights ===
    private suspend fun buildCategoryInsights(
        current: MonthPeriod,
        previous: MonthPeriod,
        categoryMap: Map<Long, Category>,
        allExpenses: List<Expense>
    ): List<CategoryInsight> = coroutineScope {
        val currentTotalsDeferred = async { expenseDao.getCategoryTotalsForPeriod(current.startMs, current.endMs) }
        val previousTotalsDeferred = async { expenseDao.getCategoryTotalsForPeriod(previous.startMs, previous.endMs) }
        val currentTotals = currentTotalsDeferred.await()
        val previousTotals = previousTotalsDeferred.await()
        val previousMap = previousTotals.associateBy { it.categoryId }
        val currentGrandTotal = currentTotals.sumOf { it.total }
        // Calculate multi-month averages per category
        val monthlyAverages = calculateCategoryMonthlyAverages(allExpenses, current)
        currentTotals.mapNotNull { ct ->
            val category = categoryMap[ct.categoryId] ?: return@mapNotNull null
            val prev = previousMap[ct.categoryId]
            val avgData = monthlyAverages[ct.categoryId]
            val changeFromPrev = if (prev != null && prev.total > 0)
                ((ct.total - prev.total) / prev.total * 100).toFloat() else null
            val changeFromAvg = if (avgData != null && avgData.first > 0)
                ((ct.total - avgData.first) / avgData.first * 100).toFloat() else null
            CategoryInsight(
                category = category,
                currentTotal = ct.total,
                currentCount = ct.txCount,
                previousTotal = prev?.total,
                previousCount = prev?.txCount,
                averageOverMonths = avgData?.first,
                monthsOfData = avgData?.second ?: 0,
                percentageOfTotal = if (currentGrandTotal > 0)
                    (ct.total / currentGrandTotal * 100).toFloat() else 0f,
                changeFromPrevious = changeFromPrev,
                changeFromAverage = changeFromAvg
            )
        }.sortedByDescending { it.currentTotal }
    }
    private fun calculateCategoryMonthlyAverages(
        allExpenses: List<Expense>,
        currentMonth: MonthPeriod
    ): Map<Long, Pair<Double, Int>> {
        // Group purchases by category, then by month, compute average
        val purchases = allExpenses.filter {
            it.transactionType == TransactionType.PURCHASE
                    && it.categoryId != null
                    && it.date < currentMonth.startMs // exclude current month
        }
        // Group by categoryId -> month key -> sum
        val categoryMonthTotals = mutableMapOf<Long, MutableMap<String, Double>>()
        val cal = Calendar.getInstance()
        for (expense in purchases) {
            val catId = expense.categoryId ?: continue
            cal.timeInMillis = expense.date
            val monthKey = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
            categoryMonthTotals
                .getOrPut(catId) { mutableMapOf() }
                .merge(monthKey, expense.amount) { a, b -> a + b }
        }
        // For each category, compute average monthly spend
        return categoryMonthTotals.mapValues { (_, monthMap) ->
            val months = monthMap.size
            val avg = if (months > 0) monthMap.values.sum() / months else 0.0
            Pair(avg, months)
        }
    }
    // === Merchant Insights ===
    private suspend fun buildMerchantInsights(
        allExpenses: List<Expense>
    ): List<MerchantInsight> {
        val stats = expenseDao.getAllMerchantStats()
        // For std deviation, compute from raw data grouped by merchant
        val purchasesByMerchant = allExpenses
            .filter { it.transactionType == TransactionType.PURCHASE }
            .groupBy { it.merchant }
        return stats.map { ms ->
            val amounts = purchasesByMerchant[ms.merchant]?.map { it.amount } ?: emptyList()
            val stdDev = if (amounts.size >= 3) calculateStdDev(amounts) else null
            val isRecurring = ms.txCount >= 2 &&
                    (ms.maxAmount - ms.minAmount) < (ms.avgAmount * 0.15)
            MerchantInsight(
                merchant = ms.merchant,
                avgAmount = ms.avgAmount,
                minAmount = ms.minAmount,
                maxAmount = ms.maxAmount,
                totalSpent = ms.totalAmount,
                transactionCount = ms.txCount,
                isLikelyRecurring = isRecurring,
                stdDeviation = stdDev
            )
        }.sortedByDescending { it.totalSpent }
    }
    // === Spending Pace ===
    private suspend fun buildSpendingPace(
        currentMonth: MonthPeriod,
        previousMonth: MonthPeriod,
        allExpenses: List<Expense>
    ): SpendingPace = coroutineScope {
        val now = System.currentTimeMillis()
        val currentSpentDeferred = async { expenseDao.getTotalForPeriod(currentMonth.startMs, currentMonth.endMs) }
        val previousTotalDeferred = async { expenseDao.getTotalForPeriod(previousMonth.startMs, previousMonth.endMs) }
        val previousCountDeferred = async { expenseDao.getCountForPeriod(previousMonth.startMs, previousMonth.endMs) }
        val currentSpent = currentSpentDeferred.await()
        val previousTotal = previousTotalDeferred.await()
        val previousCount = previousCountDeferred.await()
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        // Calculate average monthly total (excluding current month)
        val avgMonthly = calculateAverageMonthlySpend(allExpenses, currentMonth)
        // Project: if we've spent X in D days, we'll spend X * (totalDays/D) by month end
        val projectedTotal = if (dayOfMonth > 0)
            currentSpent * daysInMonth.toDouble() / dayOfMonth else currentSpent
        // Pace percentage: how much of the baseline have we consumed
        val baseline = avgMonthly ?: if (previousCount > 0) previousTotal else null
        val pacePercentage = if (baseline != null && baseline > 0) {
            val expectedAtThisPoint = baseline * dayOfMonth.coerceAtLeast(1) / daysInMonth
            (currentSpent / expectedAtThisPoint * 100).toFloat()
        } else 0f
        val paceStatus = when {
            baseline == null || baseline == 0.0 -> PaceStatus.NO_BASELINE
            pacePercentage < 90f -> PaceStatus.UNDER_PACE
            pacePercentage > 110f -> PaceStatus.OVER_PACE
            else -> PaceStatus.ON_PACE
        }
        SpendingPace(
            currentMonthSpent = currentSpent,
            daysElapsed = dayOfMonth,
            daysInMonth = daysInMonth,
            projectedTotal = projectedTotal,
            previousMonthTotal = if (previousCount > 0) previousTotal else null,
            averageMonthlyTotal = avgMonthly,
            pacePercentage = pacePercentage,
            paceStatus = paceStatus
        )
    }
    private fun calculateAverageMonthlySpend(
        allExpenses: List<Expense>,
        currentMonth: MonthPeriod
    ): Double? {
        val purchases = allExpenses.filter {
            it.transactionType == TransactionType.PURCHASE
                    && it.date < currentMonth.startMs
        }
        if (purchases.isEmpty()) return null
        val cal = Calendar.getInstance()
        val monthTotals = mutableMapOf<String, Double>()
        for (p in purchases) {
            cal.timeInMillis = p.date
            val key = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
            monthTotals.merge(key, p.amount) { a, b -> a + b }
        }
        return if (monthTotals.isNotEmpty()) monthTotals.values.average() else null
    }
    // === Anomaly Detection ===
    private suspend fun findAnomalies(
        currentMonth: MonthPeriod,
        categoryMap: Map<Long, Category>
    ): List<AnomalyTransaction> = coroutineScope {
        val merchantStats = expenseDao.getMerchantStats() // only merchants with 2+ tx
        val statsMap = merchantStats.associateBy { it.merchant }
        // Check top merchants this month for outliers
        val topMerchants = expenseDao.getTopMerchantsForPeriod(
            currentMonth.startMs, currentMonth.endMs, 100
        )
        val deferredAnomalies: List<kotlinx.coroutines.Deferred<AnomalyTransaction?>> = topMerchants.mapNotNull { merchantStat ->
            val historicalStats = statsMap[merchantStat.merchant] ?: return@mapNotNull null
            if (historicalStats.txCount < 3) return@mapNotNull null
            // If the max amount this month is > 3x the historical average
            if (merchantStat.maxAmount > historicalStats.avgAmount * 3.0) {
                async {
                    expenseDao.getLargestExpenseForMerchant(
                        merchantStat.merchant, currentMonth.startMs, currentMonth.endMs
                    )?.let { expense ->
                        AnomalyTransaction(
                            expense = expense,
                            merchantAvg = historicalStats.avgAmount,
                            deviationMultiple = (expense.amount / historicalStats.avgAmount).toFloat(),
                            category = expense.categoryId?.let { categoryMap[it] }
                        )
                    }
                }
            } else null
        }
        deferredAnomalies.awaitAll().filterNotNull()
            .sortedByDescending { it.deviationMultiple }
            .take(5)
    }
    // === Recurring Expenses ===
    private suspend fun findRecurringExpenses(): List<RecurringExpense> {
        val candidates = expenseDao.getRecurringCandidates()
        return candidates.map { ms ->
            RecurringExpense(
                merchant = ms.merchant,
                avgAmount = ms.avgAmount,
                frequency = ms.txCount,
                amountVariation = ms.maxAmount - ms.minAmount,
                isStable = (ms.maxAmount - ms.minAmount) < (ms.avgAmount * 0.05)
            )
        }
    }
    // === Day of Week Pattern ===
    private suspend fun buildDayOfWeekPattern(
        startMs: Long,
        endMs: Long
    ): List<DayOfWeekInsight> {
        val timeZoneOffset = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis())
        val data = expenseDao.getDayOfWeekPattern(startMs, endMs, timeZoneOffset)
        // Fill in missing days with zeros
        val dayMap = data.associateBy { it.dayOfWeek }
        return (0..6).map { dayIndex ->
            val d = dayMap[dayIndex]
            DayOfWeekInsight(
                dayName = DAY_NAMES[dayIndex],
                dayIndex = dayIndex,
                totalSpent = d?.total ?: 0.0,
                transactionCount = d?.txCount ?: 0,
                avgPerTransaction = d?.avgAmount ?: 0.0
            )
        }
    }
    // === Utility Functions ===
    fun buildDailyTotals(expenses: List<Expense>, days: Int): Map<String, Double> {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val result = LinkedHashMap<String, Double>()
        val dateKeyFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        // Initialize all days with 0
        for (i in days - 1 downTo 0) {
            cal.timeInMillis = now
            cal.add(Calendar.DAY_OF_YEAR, -i)
            val key = dateKeyFormat.format(cal.time)
            result[key] = 0.0
        }
        // Fill in actual values - Optimized: reuse Date object
        val purchases = expenses.filter { it.transactionType == TransactionType.PURCHASE }
        val dateObj = java.util.Date()
        for (expense in purchases) {
            dateObj.time = expense.date
            val key = dateKeyFormat.format(dateObj)
            if (result.containsKey(key)) {
                result[key] = (result[key] ?: 0.0) + expense.amount
            }
        }
        return result
    }
    // Make detectRecurring available for ViewModel compatibility if needed
    // But it's better to use the snapshot.
    // We already have findRecurringExpenses internally.
    // Legacy helper for detections from list - RE-ADDED FOR UI COMPATIBILITY
    fun detectRecurring(expenses: List<Expense>): List<RecurringCandidate> {
         val dayMs = 86_400_000L
         val results = mutableListOf<RecurringCandidate>()
         val byMerchant = expenses
            .filter { it.transactionType == TransactionType.PURCHASE }
            .groupBy { it.merchant.uppercase() }
         for ((merchant, exps) in byMerchant) {
             if (exps.size < 2) continue
             val sorted = exps.sortedBy { it.date }
             // Check if amounts are similar (within 15%)
             val amounts = sorted.map { it.amount }
             val avgAmount = amounts.average()
             val allSimilar = amounts.all { it > 0 && Math.abs(it - avgAmount) / avgAmount < 0.15 }
             if (allSimilar && sorted.size >= 2) {
                 val intervals = sorted.zipWithNext().map { (a, b) ->
                     ((b.date - a.date) / dayMs).toInt()
                 }
                 val avgInterval = intervals.average().toInt()
                 val isRecurring = avgInterval in 5..9 ||
                         avgInterval in 12..16 ||
                         avgInterval in 25..35 ||
                         avgInterval in 350..380
                 if (isRecurring) {
                     val lastDate = sorted.last().date
                     val nextExpected = lastDate + avgInterval * dayMs
                     results.add(
                         RecurringCandidate(
                             merchant = exps.first().merchant,
                             amount = avgAmount,
                             intervalDays = avgInterval,
                             occurrences = sorted.size,
                             nextExpectedDate = nextExpected,
                             confidence = if (allSimilar && sorted.size > 3) 0.92f else 0.75f
                         )
                     )
                 }
             }
         }
         return results.sortedByDescending { it.occurrences }
    }
    private fun calculateMedian(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }
    private fun calculateStdDev(values: List<Double>): Double {
        return com.yourname.expensetracker.domain.util.StatisticsUtils.calculateStdDev(values)
    }
    private fun countDistinctMonths(expenses: List<Expense>): Int {
        if (expenses.isEmpty()) return 0
        val cal = Calendar.getInstance()
        return expenses.map { expense ->
            cal.timeInMillis = expense.date
            "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
        }.distinct().size
    }
    // === Exposed Suspend Functions for Repository Usage ===
    suspend fun getSpendingPaceSuspend(expenses: List<Expense>? = null): SpendingPace {
        val now = System.currentTimeMillis()
        val currentMonth = getMonthPeriod(now)
        val previousMonth = getPreviousMonthPeriod(currentMonth)
        // Use provided expenses or fetch from DB if null
        val recentExpenses = expenses ?: run {
            val sixMonthsAgo = getMonthPeriod(now, -6).startMs
            expenseDao.getExpensesBetween(sixMonthsAgo, now)
        }
        return buildSpendingPace(currentMonth, previousMonth, recentExpenses)
    }
    private fun fmt(amount: Double): String = String.format(java.util.Locale.US, "%.2f", amount)
    private fun formatDate(dateMs: Long): String {
         val format = java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault())
         return format.format(java.util.Date(dateMs))
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\budget\BudgetModels.kt <a name="mainjavacomyournameexpensetrackerdomainbudgetbudgetmodelskt"></a>
```kotlin
package com.yourname.expensetracker.domain.budget
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.Category
data class BudgetStatus(
    val budget: Budget,
    val category: Category?,
    val spentAmount: Double,
    val remainingAmount: Double,
    val percentUsed: Float,
    val healthStatus: BudgetHealthStatus,
    val periodStart: Long,
    val periodEnd: Long
)
enum class BudgetHealthStatus {
    ON_TRACK,   // Spent < warning threshold
    WARNING,    // Spent >= warning threshold
    CRITICAL,   // Spent >= critical threshold
    EXCEEDED    // Spent >= 100%
}
data class BudgetSuggestion(
    val categoryId: Long?,
    val categoryName: String,
    val categoryIcon: String,
    val suggestedAmount: Double,
    val basedOnMonths: Int,
    val reason: String
)
enum class BudgetAlertLevel {
    WARNING,
    CRITICAL,
    EXCEEDED
}

```

---

## main\java\com\yourname\expensetracker\domain\budget\BudgetMonitor.kt <a name="mainjavacomyournameexpensetrackerdomainbudgetbudgetmonitorkt"></a>
```kotlin
package com.yourname.expensetracker.domain.budget
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.yourname.expensetracker.data.database.dao.BudgetDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class BudgetMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val budgetDao: BudgetDao,
    private val expenseDao: ExpenseDao,
    private val categoryDao: CategoryDao
) {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    fun checkBudgets() {
        serviceScope.launch {
            try {
                val activeBudgets = budgetDao.getActiveBudgets()
                if (activeBudgets.isEmpty()) return@launch
                // 1. Pre-fetch categories for notifications (Avoid N+1 in sendNotification)
                val categoryIds = activeBudgets.mapNotNull { it.categoryId }.distinct()
                val categoryMap = if (categoryIds.isNotEmpty()) {
                    categoryDao.getByIds(categoryIds).associateBy { it.id }
                } else emptyMap()
                // 2. Group budgets by their period window to batch spending queries
                val now = System.currentTimeMillis()
                val budgetsByWindow = activeBudgets.groupBy { 
                    calculatePeriodWindow(it.period, it.startDate)
                }
                for ((window, budgets) in budgetsByWindow) {
                    val startMs = window.first
                    val endMs = window.second
                    // Bulk query category totals for this window
                    val categoryTotals = expenseDao.getCategoryTotalsForPeriod(startMs, endMs)
                        .associateBy { it.categoryId }
                    // If any budget is overall (no category), query total spent for the period
                    val totalSpent = if (budgets.any { it.categoryId == null }) {
                        expenseDao.getTotalForPeriod(startMs, endMs)
                    } else null
                    for (budget in budgets) {
                        val spent = if (budget.categoryId != null) {
                            categoryTotals[budget.categoryId]?.total ?: 0.0
                        } else {
                            totalSpent ?: 0.0
                        }
                        val categoryName = budget.categoryId?.let { categoryMap[it]?.name } ?: "Overall"
                        processBudgetWithSpent(budget, spent, now, startMs, categoryName)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("BudgetMonitor", "Error in checkBudgets: ${e.message}", e)
            }
        }
    }
    private suspend fun processBudgetWithSpent(
        budget: Budget, 
        spent: Double, 
        now: Long, 
        periodStart: Long,
        categoryName: String
    ) {
        if (spent <= 0 || budget.amount <= 0) return
        val percent = (spent / budget.amount).toFloat()
        when {
            percent >= 1.0f -> {
                if (shouldNotify(budget.lastExceededNotifiedAt, now, periodStart)) {
                    sendNotificationDirect(budget, spent, "Budget Exceeded!", categoryName)
                    budgetDao.updateExceededNotification(budget.id, now)
                }
            }
            percent >= budget.notifyAtCritical -> {
                if (shouldNotify(budget.lastCriticalNotifiedAt, now, periodStart)) {
                    sendNotificationDirect(budget, spent, "Critical Budget Warning", categoryName)
                    budgetDao.updateCriticalNotification(budget.id, now)
                }
            }
            percent >= budget.notifyAtWarning -> {
                if (shouldNotify(budget.lastWarningNotifiedAt, now, periodStart)) {
                    sendNotificationDirect(budget, spent, "Budget Warning", categoryName)
                    budgetDao.updateWarningNotification(budget.id, now)
                }
            }
        }
    }
    private fun shouldNotify(lastNotified: Long?, now: Long, periodStart: Long): Boolean {
        if (lastNotified == null) return true
        // Reset cooldown if we entered a new period (BUG-7 Fix)
        if (lastNotified < periodStart) return true
        // Cooldown: only notify once every 12 hours for the same budget level
        val cooldown = 12 * 60 * 60 * 1000L
        return now - lastNotified > cooldown
    }
    private fun sendNotificationDirect(budget: Budget, spent: Double, title: String, categoryName: String) {
        val percent = (spent / budget.amount * 100).toInt()
        val content = "You've spent €${String.format(java.util.Locale.US, "%.2f", spent)} ($percent%) of your $categoryName budget."
        val builder = NotificationCompat.Builder(context, "budget_alerts")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        notificationManager.notify(budget.id.toInt(), builder.build())
    }
    fun calculatePeriodWindow(period: BudgetPeriod, anchorDate: Long): Pair<Long, Long> {
        return calculatePeriodWindowForTime(period, anchorDate, System.currentTimeMillis())
    }
    fun getPreviousPeriodWindow(period: BudgetPeriod, anchorDate: Long): Pair<Long, Long> {
        val currentWindow = calculatePeriodWindow(period, anchorDate)
        // To get previous, we can just subtract a small amount from the start of current and recalculate
        // This is safer than date math which might miss (e.g. variable month lengths)
        // If current start is Nov 1. Nov 1 - 1ms = Oct 31.
        // Calculate window for Oct 31. It will be Oct 1 - Nov 1.
        return calculatePeriodWindowForTime(period, anchorDate, currentWindow.first - 1000)
    }
    private fun calculatePeriodWindowForTime(period: BudgetPeriod, anchorDate: Long, evaluationTime: Long): Pair<Long, Long> {
        val anchorCal = Calendar.getInstance()
        anchorCal.timeInMillis = anchorDate
        val cal = Calendar.getInstance()
        cal.timeInMillis = evaluationTime
        // Reset time components to start of day
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return when (period) {
            BudgetPeriod.DAILY -> {
                val start = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 1)
                Pair(start, cal.timeInMillis)
            }
            BudgetPeriod.WEEKLY -> {
                // Find the most recent occurrence of the anchor weekday
                val anchorDayOfWeek = anchorCal.get(Calendar.DAY_OF_WEEK)
                while (cal.get(Calendar.DAY_OF_WEEK) != anchorDayOfWeek) {
                    cal.add(Calendar.DAY_OF_YEAR, -1)
                }
                val start = cal.timeInMillis
                cal.add(Calendar.WEEK_OF_YEAR, 1)
                Pair(start, cal.timeInMillis)
            }
            BudgetPeriod.MONTHLY -> {
                val anchorDay = anchorCal.get(Calendar.DAY_OF_MONTH)
                // Set to start of current month first
                cal.set(Calendar.DAY_OF_MONTH, 1)
                val currentMonthMax = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                cal.set(Calendar.DAY_OF_MONTH, anchorDay.coerceAtMost(currentMonthMax))
                if (evaluationTime < cal.timeInMillis) {
                    // If evaluation time is before the start of this month's cycle, the cycle started last month
                    cal.add(Calendar.MONTH, -1)
                    val prevMonthMax = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    cal.set(Calendar.DAY_OF_MONTH, anchorDay.coerceAtMost(prevMonthMax))
                }
                val start = cal.timeInMillis
                // To find the end, go to the start of the next cycle
                cal.add(Calendar.MONTH, 1)
                val nextMonthMax = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                cal.set(Calendar.DAY_OF_MONTH, anchorDay.coerceAtMost(nextMonthMax))
                val end = cal.timeInMillis
                Pair(start, end)
            }
            BudgetPeriod.YEARLY -> {
                val anchorMonth = anchorCal.get(Calendar.MONTH)
                val anchorDay = anchorCal.get(Calendar.DAY_OF_MONTH)
                val currentMonth = cal.get(Calendar.MONTH)
                val currentDay = cal.get(Calendar.DAY_OF_MONTH)
                // Check if we passed the anniversary this year
                var passed = false
                if (currentMonth > anchorMonth) passed = true
                else if (currentMonth == anchorMonth && currentDay >= anchorDay) passed = true
                if (!passed) {
                    cal.add(Calendar.YEAR, -1)
                }
                cal.set(Calendar.MONTH, anchorMonth)
                cal.set(Calendar.DAY_OF_MONTH, anchorDay.coerceAtMost(cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
                val start = cal.timeInMillis
                cal.add(Calendar.YEAR, 1)
                val end = cal.timeInMillis
                Pair(start, end)
            }
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\categorization\CategorizationEngine.kt <a name="mainjavacomyournameexpensetrackerdomaincategorizationcategorizationenginekt"></a>
```kotlin
package com.yourname.expensetracker.domain.categorization
import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer as NewMerchantNormalizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
@Singleton
class CategorizationEngine @Inject constructor(
    private val merchantCategoryDao: MerchantCategoryDao,
    private val merchantNormalizer: NewMerchantNormalizer
) {
    private val cacheMutex = Mutex()
    private var cachedMappings: List<MerchantCategory>? = null
    private var cachedMappingsMap: Map<String, MerchantCategory>? = null
    private var lastCacheTime = 0L
    private val CACHE_EXPIRY_MS = 300_000 // 5 minutes
    // Regex moved to MerchantNormalizer
    suspend fun categorize(merchant: String): Long? {
        val lookupResult = merchantNormalizer.normalize(merchant, autoCreate = false)
        val normalized = lookupResult.canonical.normalizedName.lowercase()
        // Ensure cache is loaded
        val (sortedMappings, mappingsMap) = getCache()
        // 1. Exact match (from cache)
        mappingsMap[normalized]?.let { return it.categoryId }
        // 2. Substring match
        val paddedNormalized = " $normalized "
        for (mapping in sortedMappings) {
            if (mapping.merchantPattern.length >= 5) {
                if (paddedNormalized.contains(mapping.merchantPattern)) {
                    return mapping.categoryId
                }
            }
        }
        // 3. Word-level match
        val words = normalized.split(" ")
            .filter { it.length >= 2 }
            .filter { it !in listOf("the", "and", "for", "inc", "ltd", "com") }
        if (words.isNotEmpty()) {
            for (word in words) {
                val match = mappingsMap[word]
                if (match != null) return match.categoryId
            }
        }
        return null
    }
    suspend fun normalize(merchant: String): String {
        return merchantNormalizer.normalize(merchant, autoCreate = false).canonical.normalizedName
    }
    private suspend fun getCache(): Pair<List<MerchantCategory>, Map<String, MerchantCategory>> {
        cacheMutex.withLock {
            val now = System.currentTimeMillis()
            if (cachedMappings == null || cachedMappingsMap == null || now - lastCacheTime > CACHE_EXPIRY_MS) {
                val all = merchantCategoryDao.getAll()
                cachedMappings = all.map { it.copy(merchantPattern = " ${it.merchantPattern} ") }
                    .sortedByDescending { it.merchantPattern.length }
                cachedMappingsMap = all.associateBy { it.merchantPattern }
                lastCacheTime = now
            }
            return Pair(cachedMappings!!, cachedMappingsMap!!)
        }
    }
    suspend fun invalidateCache() {
        cacheMutex.withLock {
            cachedMappings = null
            cachedMappingsMap = null
            lastCacheTime = 0
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\debug\NotificationSeeder.kt <a name="mainjavacomyournameexpensetrackerdomaindebugnotificationseederkt"></a>
```kotlin
package com.yourname.expensetracker.domain.debug
import com.yourname.expensetracker.data.database.entity.RawNotification
import javax.inject.Inject
import kotlin.random.Random
class NotificationSeeder @Inject constructor() {
    val categories = mapOf(
        "Groceries" to listOf("AB Vassilopoulos", "Sklavenitis", "Lidl", "Masoutis", "My Market"),
        "Transport" to listOf("Uber", "Beat", "OASA", "Shell", "EKO", "Aegean Airlines"),
        "Bills" to listOf("DEI", "EYDAP", "Vodafone", "Cosmote", "Wind"),
        "Entertainment" to listOf("Netflix", "Spotify", "Village Cinemas", "Steam", "PlayStation"),
        "Shopping" to listOf("Amazon", "Skroutz", "Zara", "H&M", "Public", "Plaisio"),
        "Dining" to listOf("Goody's", "Wolt", "E-Food", "Starbucks", "Gregorys")
    )
    private val spamTemplates = listOf(
        "You won 1000 euros! Claim now at link.com",
        "Your OTP code is 123456. Do not share it.",
        "Limited time offer! 50% off on all items.",
        "Missed call from +306912345678",
        "Your package is out for delivery.",
        "Verify your account by clicking here."
    )
    private val unknownSources = listOf(
        "Unknown Sender", "+306900000000", "InfoSMS", "Alert", "Notice"
    )
    fun generate(count: Int): List<RawNotification> {
        val notifications = mutableListOf<RawNotification>()
        val now = System.currentTimeMillis()
        val twoMonthsMs = 60L * 24 * 60 * 60 * 1000
        for (i in 0 until count) {
            val type = Random.nextInt(100)
            val notification = when {
                type < 5 -> generateSpam(now, twoMonthsMs) // 5% Spam
                type < 10 -> generateUnknown(now, twoMonthsMs) // 5% Unknown
                type < 15 -> generateRecurring(i, now) // 5% Recurring candidates
                else -> generateTransaction(now, twoMonthsMs) // 85% Normal Transactions
            }
            notifications.add(notification)
        }
        return notifications
    }
    private fun generateTransaction(now: Long, rangeMs: Long): RawNotification {
        val categoryEntry = categories.entries.random()
        val merchant = categoryEntry.value.random()
        val amount = Random.nextDouble(5.0, 150.0)
        val date = now - Random.nextLong(rangeMs)
        // Randomize source slightly to test normalization
        val sources = listOf("Revolut", "Piraeus", "Eurobank", "Alpha Bank")
        val source = sources.random()
        val text = when (source) {
            "Revolut" -> "Spent €${"%.2f".format(amount)} at $merchant."
            "Piraeus" -> "Agora €${"%.2f".format(amount)} me karta ... sto $merchant"
            else -> "Purchase of €${"%.2f".format(amount)} at $merchant completed."
        }
        return RawNotification(
            packageName = "com.simulation.$source".lowercase(),
            appName = source,
            title = "Transaction Alert",
            text = text,
            timestamp = date,
            capturedAt = System.currentTimeMillis()
        )
    }
    private val recurringTemplates = listOf(
        Pair("Netflix", 13.99),
        Pair("Spotify", 7.99),
        Pair("Cosmote", 35.00),
        Pair("DEI", 45.50),
        Pair("iCloud", 2.99),
        Pair("YouTube Premium", 11.99)
    )
    private fun generateRecurring(index: Int, now: Long): RawNotification {
        val (merchant, amount) = recurringTemplates.random()
        // Random date within last 60 days
        val date = now - Random.nextLong(60L * 24 * 60 * 60 * 1000)
        return RawNotification(
            packageName = "com.simulation.revolut",
            appName = "Revolut",
            title = "Recurring Payment",
            text = "Spent €$amount at $merchant.",
            timestamp = date,
            capturedAt = System.currentTimeMillis()
        )
    }
    private fun generateSpam(now: Long, rangeMs: Long): RawNotification {
        val text = spamTemplates.random()
        val date = now - Random.nextLong(rangeMs)
        return RawNotification(
            packageName = "com.android.mms",
            appName = "Messages",
            title = unknownSources.random(),
            text = text,
            timestamp = date,
            capturedAt = System.currentTimeMillis()
        )
    }
    private fun generateUnknown(now: Long, rangeMs: Long): RawNotification {
        val amount = Random.nextDouble(10.0, 50.0)
        val date = now - Random.nextLong(rangeMs)
        val text = "Payment of €${"%.2f".format(amount)} to Unknown Merchant."
        return RawNotification(
            packageName = "com.unknown.app",
            appName = "Unknown App",
            title = "Payment Notification",
            text = text,
            timestamp = date,
            capturedAt = System.currentTimeMillis()
        )
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\intelligence\ConfidenceRouter.kt <a name="mainjavacomyournameexpensetrackerdomainintelligenceconfidencerouterkt"></a>
```kotlin
package com.yourname.expensetracker.domain.intelligence
import com.yourname.expensetracker.data.database.dao.SourceStatsDao
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
enum class RoutingDecision {
    AUTO_ACCEPT,    // High confidence → create expense immediately
    NEEDS_REVIEW,   // Medium confidence → add to review queue
    AUTO_REJECT     // Low confidence → silently drop
}
data class RoutingResult(
    val decision: RoutingDecision,
    val adjustedConfidence: Float,
    val reason: String
)
@Singleton
class ConfidenceRouter @Inject constructor(
    private val sourceStatsDao: SourceStatsDao,
    private val userCorrectionDao: UserCorrectionDao,
    private val classifier: TransactionClassifier
) {
    companion object {
        const val AUTO_ACCEPT_THRESHOLD = 0.85f
        const val REVIEW_THRESHOLD = 0.50f
        const val CACHE_TTL = 60_000L // 1 minute
        // ML Thresholds
        private const val ML_CONFIDENT_THRESHOLD = 0.8f
        private const val ML_LIKELY_THRESHOLD = 0.3f
        // ML Sample sizes for weight calculation
        private const val ML_SAMPLES_MIN = 20
        private const val ML_SAMPLES_LOW = 50
        private const val ML_SAMPLES_MED = 100
        private const val ML_SAMPLES_HIGH = 200
        // Trust Modifiers
        private const val TRUST_MOD_SPAM = 0.1f
        private const val TRUST_MOD_HIGH = 1.1f
        private const val TRUST_MOD_NEUTRAL = 1.0f
        private const val TRUST_MOD_LOW = 0.9f
        private const val TRUST_MOD_BAD = 0.5f
        // Trust Score Thresholds
        private const val TRUST_SCORE_HIGH = 0.8f
        private const val TRUST_SCORE_NEUTRAL = 0.4f
        private const val TRUST_SCORE_LOW = 0.15f
        // Source Stats Requirement
        private const val MIN_NOTIFICATIONS_FOR_TRUST = 10
        // Rejection Thresholds
        private const val MERCHANT_REJECTION_THRESHOLD = 0.5f
        private const val PACKAGE_REJECTION_THRESHOLD = 0.7f
        private const val PREVIOUS_APPROVAL_BOOST = 1.2f
        private const val AUTO_REJECT_PENALTY_PACKAGE = 0.3f
        private const val UNKNOWN_MERCHANT_PENALTY = 0.5f
    }
    // Caches with timestamp: Value -> Timestamp
    private val sourceStatsCache = ConcurrentHashMap<String, Pair<SourceStats?, Long>>()
    private val merchantRejectionCache = ConcurrentHashMap<String, Pair<Float, Long>>()
    private val packageRejectionCache = ConcurrentHashMap<String, Pair<Float, Long>>()
    private val approvalCache = ConcurrentHashMap<String, Pair<Boolean, Long>>()
    private val MAX_CACHE_SIZE = 1000
    private fun checkCacheSize() {
        if (sourceStatsCache.size > MAX_CACHE_SIZE) sourceStatsCache.clear()
        if (merchantRejectionCache.size > MAX_CACHE_SIZE) merchantRejectionCache.clear()
        if (packageRejectionCache.size > MAX_CACHE_SIZE) packageRejectionCache.clear()
        if (approvalCache.size > MAX_CACHE_SIZE) approvalCache.clear()
    }
    suspend fun route(
        parsed: ParsedTransaction,
        packageName: String,
        notificationText: String? = null
    ): RoutingResult {
        checkCacheSize()
        var adjustedConfidence = parsed.confidence
        val reasons = mutableListOf<String>()
        // 1. ML classifier prediction (if ready and needed)
        // Skip ML if parser is extremely confident (e.g. exact template match) to save resources
        if (notificationText != null && parsed.confidence < 1.0f) {
            val mlPrediction = classifier.predict(notificationText)
            val classifierStats = classifier.getStats()
            if (classifierStats.isReady) {
                // Blend parser confidence with ML prediction
                // Weight: 60% parser, 40% ML (ML gets more weight as it trains more)
                val mlWeight = calculateMlWeight(classifierStats)
                val parserWeight = 1.0f - mlWeight
                adjustedConfidence = parsed.confidence * parserWeight + mlPrediction * mlWeight
                if (mlPrediction < ML_LIKELY_THRESHOLD) {
                    reasons.add("ML: ${(mlPrediction * 100).toInt()}% likely transaction")
                } else if (mlPrediction > ML_CONFIDENT_THRESHOLD) {
                    reasons.add("ML: ${(mlPrediction * 100).toInt()}% confident")
                }
            }
        }
        // 2-5. Adjust based on source trust, merchant history, package history, and previous approvals
        coroutineScope {
            val sourceStatsDeferred = async { getCachedSourceStats(packageName) }
            val merchantRejectionRateDeferred = async { getCachedMerchantRejectionRate(parsed.merchant) }
            val packageRejectionRateDeferred = async { getCachedPackageRejectionRate(packageName) }
            val previouslyApprovedDeferred = async { getCachedHasPreviousApprovals(parsed.merchant, packageName) }
            // 2. Adjust based on source trust score
            val sourceStats = sourceStatsDeferred.await()
            if (sourceStats != null && sourceStats.totalNotifications > MIN_NOTIFICATIONS_FOR_TRUST) {
                val trustModifier = calculateTrustModifier(sourceStats)
                adjustedConfidence *= trustModifier
                if (trustModifier < TRUST_MOD_LOW) {
                    reasons.add("Source trust: ${(sourceStats.trustScore * 100).toInt()}%")
                }
            }
            // 3. Adjust based on user correction history for this merchant
            val merchantRejectionRate = merchantRejectionRateDeferred.await()
            if (merchantRejectionRate > MERCHANT_REJECTION_THRESHOLD) {
                adjustedConfidence *= 0.5f // Keep simple multiplier or extract? Let's fix this one too.
                reasons.add("Merchant often rejected")
            }
            // 4. Package rejection rate
            val packageRejectionRate = packageRejectionRateDeferred.await()
            if (packageRejectionRate > PACKAGE_REJECTION_THRESHOLD) {
                adjustedConfidence *= AUTO_REJECT_PENALTY_PACKAGE
                reasons.add("Package mostly rejected")
            }
            // 5. Boost if user has previously approved similar transactions
            val previouslyApproved = previouslyApprovedDeferred.await()
            if (previouslyApproved) {
                adjustedConfidence = (adjustedConfidence * PREVIOUS_APPROVAL_BOOST).coerceAtMost(1.0f)
                reasons.add("Previously approved merchant")
            }
        }
        // 6. Penalty for Unknown merchant
        if (parsed.merchant.equals("Unknown", ignoreCase = true)) {
            adjustedConfidence *= UNKNOWN_MERCHANT_PENALTY
            reasons.add("Unknown merchant")
        }
        // Clamp
        adjustedConfidence = adjustedConfidence.coerceIn(0f, 1f)
        // Route
        val decision = when {
            adjustedConfidence >= AUTO_ACCEPT_THRESHOLD -> RoutingDecision.AUTO_ACCEPT
            adjustedConfidence >= REVIEW_THRESHOLD -> RoutingDecision.NEEDS_REVIEW
            else -> RoutingDecision.AUTO_REJECT
        }
        val reason = if (reasons.isEmpty()) {
            "Base confidence: ${(parsed.confidence * 100).toInt()}%"
        } else {
            reasons.joinToString("; ")
        }
        return RoutingResult(decision, adjustedConfidence, reason)
    }
    /**
     * ML weight increases with more training data
     */
    private fun calculateMlWeight(stats: ClassifierStats): Float {
        val totalSamples = stats.totalPositive + stats.totalNegative
        return when {
            totalSamples < ML_SAMPLES_MIN -> 0f       // Not ready
            totalSamples < ML_SAMPLES_LOW -> 0.2f     // Low confidence in ML
            totalSamples < ML_SAMPLES_MED -> 0.35f   // Growing confidence
            totalSamples < ML_SAMPLES_HIGH -> 0.5f    // Moderate
            else -> 0.6f                   // Max capped at 60% (LOG-007)
        }
    }
    private fun calculateTrustModifier(stats: SourceStats): Float {
        return when {
            stats.isLikelySpam -> TRUST_MOD_SPAM // LOG-014: Heavy penalty for spam
            stats.trustScore > TRUST_SCORE_HIGH -> TRUST_MOD_HIGH
            stats.trustScore > TRUST_SCORE_NEUTRAL -> TRUST_MOD_NEUTRAL // 40-80% is neutral
            stats.trustScore > TRUST_SCORE_LOW -> TRUST_MOD_LOW // 15-40% is slight penalty
            else -> TRUST_MOD_BAD // < 15% is heavy penalty
        }
    }
    // === Cached Data Access ===
    private suspend fun getCachedSourceStats(packageName: String): SourceStats? {
        val now = System.currentTimeMillis()
        val cached = sourceStatsCache[packageName]
        if (cached != null && now - cached.second < CACHE_TTL) {
            return cached.first
        }
        val stats = sourceStatsDao.getByPackage(packageName)
        sourceStatsCache[packageName] = Pair(stats, now)
        return stats
    }
    private suspend fun getCachedMerchantRejectionRate(merchant: String): Float {
        val now = System.currentTimeMillis()
        val key = merchant.lowercase()
        val cached = merchantRejectionCache[key]
        if (cached != null && now - cached.second < CACHE_TTL) {
            return cached.first
        }
        val total = userCorrectionDao.getMerchantTotalCorrections(merchant)
        val result = if (total < 3) 0f else {
            val rejections = userCorrectionDao.getMerchantRejectionCount(merchant)
            rejections.toFloat() / total
        }
        merchantRejectionCache[key] = Pair(result, now)
        return result
    }
    private suspend fun getCachedPackageRejectionRate(packageName: String): Float {
        val now = System.currentTimeMillis()
        val cached = packageRejectionCache[packageName]
        if (cached != null && now - cached.second < CACHE_TTL) {
            return cached.first
        }
        val total = userCorrectionDao.getTotalCorrections(packageName)
        val result = if (total < 5) 0f else {
            val rejections = userCorrectionDao.getRejectionCount(packageName)
            rejections.toFloat() / total
        }
        packageRejectionCache[packageName] = Pair(result, now)
        return result
    }
    private suspend fun getCachedHasPreviousApprovals(merchant: String, packageName: String): Boolean {
        val now = System.currentTimeMillis()
        val key = "${merchant.lowercase()}|$packageName"
        val cached = approvalCache[key]
        if (cached != null && now - cached.second < CACHE_TTL) {
            return cached.first
        }
        val result = userCorrectionDao.hasPreviousApprovals(merchant, packageName)
        approvalCache[key] = Pair(result, now)
        return result
    }
    suspend fun ensureSourceStats(packageName: String) {
        // Optimistic check using cache first to avoid DB read
        val cached = sourceStatsCache[packageName]?.first
        if (cached != null) return
        val existing = sourceStatsDao.getByPackage(packageName)
        if (existing == null) {
            sourceStatsDao.insertIfNotExists(SourceStats(packageName = packageName))
        }
        // Update cache
        sourceStatsCache[packageName] = Pair(existing ?: SourceStats(packageName = packageName), System.currentTimeMillis())
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\intelligence\TransactionClassifier.kt <a name="mainjavacomyournameexpensetrackerdomainintelligencetransactionclassifierkt"></a>
```kotlin
// domain/intelligence/TransactionClassifier.kt
package com.yourname.expensetracker.domain.intelligence
import android.content.Context
import android.util.Log
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
/**
 * Lightweight on-device text classifier using Naive Bayes.
 * No TensorFlow needed. Learns from user corrections.
 */
@Singleton
open class TransactionClassifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userCorrectionDao: UserCorrectionDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var saveJob: Job? = null
    private var retrainJob: Job? = null
    fun cleanup() {
        saveJob?.cancel()
        retrainJob?.cancel()
    }
    companion object {
        private const val TAG = "TxClassifier"
        private const val MODEL_FILE = "naive_bayes_model.json"
        private const val MODEL_VERSION = 1
        private const val MIN_TRAINING_SAMPLES = 20
        private const val LAPLACE_SMOOTHING = 1.0
        private val regexNonAlphanumeric = Regex("[^a-zα-ωά-ώ0-9€$£ ]")
        private val regexWhitespace = Regex("\\s+")
        private val regexDecimalAmount = Regex("""\d+[.,]\d{2}""")
        private val regexCurrencySymbol = Regex("""[€$£]""")
        private val regexCurrencyCode = Regex("""(?i)(EUR|USD|GBP)""")
        private val regexPaymentKeyword = Regex("""(?i)(paid|payment|purchase|charged|debit)""")
        private val regexGreekPaymentKeyword = Regex("""(?i)(πληρωμ|αγορ|χρέωσ|συναλλαγ)""")
        private val regexPromoKeyword = Regex("""(?i)(offer|discount|promo|sale|free|δωρεάν|προσφορά|έκπτωση)""")
        private val regexOtpKeyword = Regex("""(?i)(otp|code|verify|κωδικός)""")
        private val regexBalanceKeyword = Regex("""(?i)(balance|υπόλοιπο)""")
    }
    private val mutex = Mutex()
    private val positiveWordCounts = mutableMapOf<String, Int>()
    private val negativeWordCounts = mutableMapOf<String, Int>()
    private var totalPositive = 0
    private var totalNegative = 0
    private val vocabulary = mutableSetOf<String>()
    private var vocabularySize = 0
    private val positiveBigramCounts = mutableMapOf<String, Int>()
    private val negativeBigramCounts = mutableMapOf<String, Int>()
    private val _stats = MutableStateFlow(
        ClassifierStats(0, 0, 0, false)
    )
    val stats: StateFlow<ClassifierStats> = _stats.asStateFlow()
    @Volatile
    private var isLoaded = false
    private var lastTrainingCount = 0
    suspend fun initialize() {
        if (isLoaded) return
        mutex.withLock {
            if (isLoaded) return
            if (loadFromDisk()) {
                isLoaded = true
                _stats.value = getStatsInternal()
                Log.d(TAG, "Loaded model from disk: +$totalPositive/-$totalNegative samples")
            }
            val correctionCount = userCorrectionDao.getCount()
            if (correctionCount > lastTrainingCount && correctionCount >= MIN_TRAINING_SAMPLES) {
                retrainFromCorrectionsInternal()
            }
            isLoaded = true
        }
    }
    open suspend fun predict(text: String): Float {
        if (!isLoaded) initialize()
        if (totalPositive + totalNegative < MIN_TRAINING_SAMPLES) {
            return 0.5f 
        }
        val features = extractFeatures(text)
        return mutex.withLock {
            calculateProbability(features)
        }
    }
    suspend fun train(text: String, isTransaction: Boolean) {
        mutex.withLock {
            val features = extractFeatures(text)
            addTrainingSample(features, isTransaction)
            scheduleSave()
        }
    }
    fun retrainFromCorrections() {
        retrainJob?.cancel()
        retrainJob = scope.launch {
            delay(2000) // Debounce for 2 seconds
            mutex.withLock {
                retrainFromCorrectionsInternal()
            }
        }
    }
    private suspend fun retrainFromCorrectionsInternal() {
        val corrections = userCorrectionDao.getAll()
        if (corrections.size < MIN_TRAINING_SAMPLES) {
            Log.d(TAG, "Not enough corrections to train: ${corrections.size}/$MIN_TRAINING_SAMPLES")
            return
        }
        positiveWordCounts.clear()
        negativeWordCounts.clear()
        positiveBigramCounts.clear()
        negativeBigramCounts.clear()
        totalPositive = 0
        totalNegative = 0
        // LOG-012 Fix: Balance dataset
        val positiveCorrections = corrections.filter { it.wasApproved }
        val negativeCorrections = corrections.filter { it.wasRejected }
        // Cap negatives to 3x positives to prevent skew
        val maxNegatives = (positiveCorrections.size * 3).coerceAtLeast(MIN_TRAINING_SAMPLES)
        val selectedNegatives = negativeCorrections.shuffled().take(maxNegatives)
        val trainingSet = positiveCorrections + selectedNegatives
        for (correction in trainingSet) {
            val text = buildTrainingText(correction)
            if (text.isNotBlank()) {
                val features = extractFeatures(text)
                if (correction.wasRejected) {
                    addTrainingSample(features, isTransaction = false)
                } else if (correction.wasApproved) {
                    addTrainingSample(features, isTransaction = true)
                }
            }
        }
        vocabularySize = vocabulary.size
        lastTrainingCount = corrections.size
        scheduleSave()
        Log.d(TAG, "Retrained from ${corrections.size} corrections: +$totalPositive/-$totalNegative")
    }
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(2000)
            saveToDisk()
        }
    }
    // Internal helper to get stats without locking (caller must hold mutex)
    private fun getStatsInternal(): ClassifierStats {
        return ClassifierStats(
            totalPositive = totalPositive,
            totalNegative = totalNegative,
            vocabularySize = vocabularySize,
            isReady = totalPositive + totalNegative >= MIN_TRAINING_SAMPLES
        )
    }
    // Public suspend version that acquires lock
    open suspend fun getStats(): ClassifierStats {
        return mutex.withLock {
            getStatsInternal()
        }
    }
    private fun addTrainingSample(features: FeatureSet, isTransaction: Boolean) {
        if (isTransaction) {
            totalPositive++
            features.words.forEach {
                positiveWordCounts[it] = (positiveWordCounts[it] ?: 0) + 1
                vocabulary.add(it)
            }
            features.bigrams.forEach {
                positiveBigramCounts[it] = (positiveBigramCounts[it] ?: 0) + 1
            }
        } else {
            totalNegative++
            features.words.forEach {
                negativeWordCounts[it] = (negativeWordCounts[it] ?: 0) + 1
                vocabulary.add(it)
            }
            features.bigrams.forEach {
                negativeBigramCounts[it] = (negativeBigramCounts[it] ?: 0) + 1
            }
        }
        vocabularySize = vocabulary.size
        _stats.value = getStatsInternal()
    }
    private fun calculateProbability(features: FeatureSet): Float {
        val total = totalPositive + totalNegative
        if (total == 0) return 0.5f
        // Guard against ln(0) which returns -Infinity
        // If a class has 0 samples, we treat its prior probability as extremely low (-20.0 in log space ~= 2e-9)
        var logProbPos = if (totalPositive > 0) ln(totalPositive.toDouble() / total) else -20.0
        var logProbNeg = if (totalNegative > 0) ln(totalNegative.toDouble() / total) else -20.0
        val vocabSize = vocabularySize.coerceAtLeast(1)
        for (word in features.words) {
            val posCount = (positiveWordCounts[word] ?: 0).toDouble() + LAPLACE_SMOOTHING
            val negCount = (negativeWordCounts[word] ?: 0).toDouble() + LAPLACE_SMOOTHING
            val posDenom = totalPositive.toDouble() + vocabSize * LAPLACE_SMOOTHING
            val negDenom = totalNegative.toDouble() + vocabSize * LAPLACE_SMOOTHING
            logProbPos += ln(posCount / posDenom)
            logProbNeg += ln(negCount / negDenom)
        }
        val bigramWeight = 0.5
        val bigramVocabSize = (positiveBigramCounts.keys + negativeBigramCounts.keys).toSet().size.coerceAtLeast(1)
        for (bigram in features.bigrams) {
            val posCount = (positiveBigramCounts[bigram] ?: 0).toDouble() + LAPLACE_SMOOTHING
            val negCount = (negativeBigramCounts[bigram] ?: 0).toDouble() + LAPLACE_SMOOTHING
            val posDenom = totalPositive.toDouble() + bigramVocabSize * LAPLACE_SMOOTHING
            val negDenom = totalNegative.toDouble() + bigramVocabSize * LAPLACE_SMOOTHING
            logProbPos += bigramWeight * ln(posCount / posDenom)
            logProbNeg += bigramWeight * ln(negCount / negDenom)
        }
        val logOdds = logProbPos - logProbNeg
        val clampedLogOdds = logOdds.coerceIn(-20.0, 20.0)
        return (1.0 / (1.0 + Math.exp(-clampedLogOdds))).toFloat()
    }
    private fun extractFeatures(text: String): FeatureSet {
        val normalized = text.lowercase()
            .replace(regexNonAlphanumeric, " ")
            .replace(regexWhitespace, " ")
            .trim()
        val words = normalized.split(" ")
            .filter { it.length >= 2 }
            .toMutableList()
        if (regexDecimalAmount.containsMatchIn(text)) {
            words.add("__HAS_DECIMAL_AMOUNT__")
        }
        if (regexCurrencySymbol.containsMatchIn(text)) {
            words.add("__HAS_CURRENCY_SYMBOL__")
        }
        if (regexCurrencyCode.containsMatchIn(text)) {
            words.add("__HAS_CURRENCY_CODE__")
        }
        if (regexPaymentKeyword.containsMatchIn(text)) {
            words.add("__HAS_PAYMENT_KEYWORD__")
        }
        if (regexGreekPaymentKeyword.containsMatchIn(text)) {
            words.add("__HAS_GREEK_PAYMENT_KEYWORD__")
        }
        if (regexPromoKeyword.containsMatchIn(text)) {
            words.add("__HAS_PROMO_KEYWORD__")
        }
        if (regexOtpKeyword.containsMatchIn(text)) {
            words.add("__HAS_OTP_KEYWORD__")
        }
        if (regexBalanceKeyword.containsMatchIn(text)) {
            words.add("__HAS_BALANCE_KEYWORD__")
        }
        val actualWords = normalized.split(" ").filter { it.length >= 2 }
        val bigrams = if (actualWords.size >= 2) {
            actualWords.zipWithNext().map { (a, b) -> "${a}_$b" }
        } else {
            emptyList()
        }
        return FeatureSet(words, bigrams)
    }
    private fun buildTrainingText(
        correction: com.yourname.expensetracker.data.database.entity.UserCorrection
    ): String {
        return listOfNotNull(
            correction.notificationTitle,
            correction.notificationText,
            correction.originalMerchant
        ).joinToString(" ")
    }
    private suspend fun saveToDisk() {
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("version", MODEL_VERSION)
                    mutex.withLock {
                        put("totalPositive", totalPositive)
                        put("totalNegative", totalNegative)
                        put("vocabularySize", vocabularySize)
                        put("lastTrainingCount", lastTrainingCount)
                        put("positiveWords", JSONObject().apply {
                            positiveWordCounts.forEach { (k, v) -> put(k, v) }
                        })
                        put("negativeWords", JSONObject().apply {
                            negativeWordCounts.forEach { (k, v) -> put(k, v) }
                        })
                        put("positiveBigrams", JSONObject().apply {
                            positiveBigramCounts.forEach { (k, v) -> put(k, v) }
                        })
                        put("negativeBigrams", JSONObject().apply {
                            negativeBigramCounts.forEach { (k, v) -> put(k, v) }
                        })
                    }
                }
                File(context.filesDir, MODEL_FILE).writeText(json.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save model", e)
            }
        }
    }
    private fun loadFromDisk(): Boolean {
        return try {
            val file = File(context.filesDir, MODEL_FILE)
            if (!file.exists()) return false
            val json = JSONObject(file.readText())
            val version = json.optInt("version", 0)
            if (version != MODEL_VERSION) {
                Log.w(TAG, "Model version mismatch. Current: $MODEL_VERSION, Found: $version.")
                return false
            }
            totalPositive = json.getInt("totalPositive")
            totalNegative = json.getInt("totalNegative")
            vocabularySize = json.optInt("vocabularySize", 0)
            lastTrainingCount = json.optInt("lastTrainingCount", 0)
            val posWords = json.getJSONObject("positiveWords")
            positiveWordCounts.clear()
            posWords.keys().forEach { key ->
                val count = posWords.getInt(key)
                positiveWordCounts[key] = count
                vocabulary.add(key)
            }
            val negWords = json.getJSONObject("negativeWords")
            negativeWordCounts.clear()
            negWords.keys().forEach { key ->
                val count = negWords.getInt(key)
                negativeWordCounts[key] = count
                vocabulary.add(key)
            }
            json.optJSONObject("positiveBigrams")?.let { posBi ->
                positiveBigramCounts.clear()
                posBi.keys().forEach { key ->
                    positiveBigramCounts[key] = posBi.getInt(key)
                }
            }
            json.optJSONObject("negativeBigrams")?.let { negBi ->
                negativeBigramCounts.clear()
                negBi.keys().forEach { key ->
                    negativeBigramCounts[key] = negBi.getInt(key)
                }
            }
            vocabularySize = vocabulary.size
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            false
        }
    }
}
data class FeatureSet(
    val words: List<String>,
    val bigrams: List<String>
)
data class ClassifierStats(
    val totalPositive: Int,
    val totalNegative: Int,
    val vocabularySize: Int,
    val isReady: Boolean
)

```

---

## main\java\com\yourname\expensetracker\domain\intelligence\ml\ExpenseCategoryClassifier.kt <a name="mainjavacomyournameexpensetrackerdomainintelligencemlexpensecategoryclassifierkt"></a>
```kotlin
package com.yourname.expensetracker.domain.intelligence.ml
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
/**
 * Naive Bayes Classifier for Expense Categorization.
 * Uses multinomial Naive Bayes with Laplace smoothing.
 */
@Singleton
class ExpenseCategoryClassifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ExpenseCategoryNB"
        private const val MODEL_FILE = "expense_category_model.json"
        private const val SMOOTHING = 1.0
        private const val MIN_SAMPLES = 20
    }
    private var categoryCounts = mutableMapOf<Long, Int>()
    private var totalSamples = 0
    private var wordCounts = mutableMapOf<Long, MutableMap<String, Int>>()
    private var wordTotals = mutableMapOf<Long, Int>()
    private var vocabulary = mutableSetOf<String>()
    private var isLoaded = false
    suspend fun classify(features: ExpenseFeatures): List<CategoryScore> {
        if (!isLoaded) loadModel()
        if (totalSamples < MIN_SAMPLES || categoryCounts.isEmpty()) {
            return emptyList()
        }
        val scores = mutableMapOf<Long, Double>()
        categoryCounts.keys.forEach { categoryId ->
            scores[categoryId] = calculateLogProbability(features, categoryId)
        }
        // Softmax normalization
        val maxLog = scores.values.maxOrNull() ?: 0.0
        val expScores = scores.mapValues { Math.exp(it.value - maxLog).coerceAtLeast(1e-10) }
        val sumExp = expScores.values.sum()
        return expScores
            .map { (categoryId, expVal) ->
                CategoryScore(
                    categoryId = categoryId,
                    categoryName = "Category_$categoryId", // Resolved by HybridClassifier
                    score = (expVal / sumExp).toFloat()
                )
            }
            .filter { it.score > 0.01f }
            .sortedByDescending { it.score }
    }
    suspend fun train(features: ExpenseFeatures, categoryId: Long) {
        if (!isLoaded) loadModel()
        categoryCounts[categoryId] = (categoryCounts[categoryId] ?: 0) + 1
        totalSamples++
        val catWordCounts = wordCounts.getOrPut(categoryId) { mutableMapOf() }
        features.merchantTokens.forEach { token ->
            catWordCounts[token] = (catWordCounts[token] ?: 0) + 1
            vocabulary.add(token)
        }
        wordTotals[categoryId] = (wordTotals[categoryId] ?: 0) + features.merchantTokens.size
        saveModel()
    }
    private fun calculateLogProbability(features: ExpenseFeatures, categoryId: Long): Double {
        var logProb = Math.log(
            (categoryCounts[categoryId] ?: 1).toDouble() / 
            totalSamples.coerceAtLeast(1)
        )
        val catWordCounts = wordCounts[categoryId] ?: mutableMapOf()
        val catWordTotal = wordTotals[categoryId] ?: 0
        val vocabSize = vocabulary.size.coerceAtLeast(1)
        features.merchantTokens.forEach { token ->
            val wordCount = catWordCounts[token]?.toDouble() ?: 0.0
            val wordProb = (wordCount + SMOOTHING) / (catWordTotal + SMOOTHING * vocabSize)
            logProb += Math.log(wordProb.coerceAtLeast(1e-10))
        }
        return logProb
    }
    fun isReady(): Boolean = totalSamples >= MIN_SAMPLES
    fun getStats(): CategoryClassifierStats {
        return CategoryClassifierStats(
            totalSamples = totalSamples,
            categoryCount = categoryCounts.size,
            vocabularySize = vocabulary.size,
            isReady = isReady()
        )
    }
    private suspend fun saveModel() = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("totalSamples", totalSamples)
                put("vocabulary", JSONObject(vocabulary.associateWith { 1 }))
                val countsJson = JSONObject()
                categoryCounts.forEach { (id, count) -> countsJson.put(id.toString(), count) }
                put("categoryCounts", countsJson)
                val wordCountsJson = JSONObject()
                wordCounts.forEach { (catId, words) ->
                    val wordsJson = JSONObject()
                    words.forEach { (word, count) -> wordsJson.put(word, count) }
                    wordCountsJson.put(catId.toString(), wordsJson)
                }
                put("wordCounts", wordCountsJson)
            }
            File(context.filesDir, MODEL_FILE).writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save model", e)
        }
    }
    private suspend fun loadModel() = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, MODEL_FILE)
            if (!file.exists()) {
                isLoaded = true
                return@withContext
            }
            val json = JSONObject(file.readText())
            totalSamples = json.getInt("totalSamples")
            categoryCounts.clear()
            val catCounts = json.getJSONObject("categoryCounts")
            catCounts.keys().forEach { key -> categoryCounts[key.toLong()] = catCounts.getInt(key) }
            vocabulary.clear()
            val vocab = json.getJSONObject("vocabulary")
            vocab.keys().forEach { vocabulary.add(it) }
            wordCounts.clear()
            val wc = json.getJSONObject("wordCounts")
            wc.keys().forEach { catId ->
                val wordsJson = wc.getJSONObject(catId)
                val words = mutableMapOf<String, Int>()
                wordsJson.keys().forEach { word -> words[word] = wordsJson.getInt(word) }
                wordCounts[catId.toLong()] = words
            }
            wordTotals.clear()
            wordCounts.forEach { (catId, words) -> wordTotals[catId] = words.values.sum() }
            isLoaded = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            isLoaded = true
        }
    }
}
data class CategoryClassifierStats(
    val totalSamples: Int,
    val categoryCount: Int,
    val vocabularySize: Int,
    val isReady: Boolean
)

```

---

## main\java\com\yourname\expensetracker\domain\intelligence\ml\ExpenseClassifier.kt <a name="mainjavacomyournameexpensetrackerdomainintelligencemlexpenseclassifierkt"></a>
```kotlin
package com.yourname.expensetracker.domain.intelligence.ml
/**
 * Match type for categorization results.
 */
enum class MatchType {
    RULE_MATCH,        // Keyword based
    HISTORY_MATCH,     // Previous user choice
    ML_PREDICTION,     // Naive Bayes prediction
    FALLBACK,          // Default category
    EXACT_MATCH,       // Exact string match (for normalization)
    ALIAS_MATCH,       // Known alias (for normalization)
    FUZZY_MATCH,       // Fuzzy/string similarity (for normalization)
    PARTIAL_MATCH,     // Substring match
    USER_DEFINED,      // User explicitly linked
    NEW_MERCHANT       // No match found
}
/**
 * Score for a specific category prediction.
 */
data class CategoryScore(
    val categoryId: Long,
    val categoryName: String,
    val score: Float
)
/**
 * Result of the classification process.
 */
data class ClassificationResult(
    val categoryId: Long,
    val categoryName: String,
    val confidence: Float,
    val alternatives: List<CategoryScore> = emptyList(),
    val matchType: MatchType
)
/**
 * Features extracted for classification.
 */
data class ExpenseFeatures(
    val merchantName: String,
    val merchantTokens: List<String>,
    val notificationTitle: String?,
    val notificationText: String?,
    val allText: String,
    val amount: Double,
    val amountBucket: AmountBucket,
    val dayOfWeek: Int, // 0 = Monday, 6 = Sunday
    val hourOfDay: Int,
    val isWeekend: Boolean,
    val sourcePackage: String
)
/**
 * Amount buckets for qualitative amount features.
 */
enum class AmountBucket {
    TINY,   // < 5
    SMALL,  // 5 - 20
    MEDIUM, // 20 - 50
    LARGE,  // 50 - 200
    HUGE;   // > 200
    companion object {
        fun fromAmount(amount: Double): AmountBucket {
            return when {
                amount < 5.0 -> TINY
                amount < 20.0 -> SMALL
                amount < 50.0 -> MEDIUM
                amount < 200.0 -> LARGE
                else -> HUGE
            }
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\intelligence\ml\FeatureExtractor.kt <a name="mainjavacomyournameexpensetrackerdomainintelligencemlfeatureextractorkt"></a>
```kotlin
package com.yourname.expensetracker.domain.intelligence.ml
import com.yourname.expensetracker.data.database.entity.Expense
import java.util.Calendar
/**
 * Extracts features from expenses and notifications for ML classification.
 */
class FeatureExtractor {
    companion object {
        private val STOP_WORDS = setOf(
            "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
            "of", "with", "by", "from", "as", "is", "was", "are", "were", "been",
            // Greek stop words
            "και", "το", "η", "τα", "του", "την", "των", "με", "σε", "για"
        )
        private val WORD_PATTERN = Regex("[a-zA-Zα-ωά-ώΑ-ΩΆ-Ώ]+")
    }
    /**
     * Extract features from an expense.
     */
    fun extractFromExpense(
        expense: Expense,
        notificationTitle: String? = null,
        notificationText: String? = null,
        packageName: String = ""
    ): ExpenseFeatures {
        val calendar = Calendar.getInstance().apply { timeInMillis = expense.date }
        val allText = listOfNotNull(
            expense.merchant,
            notificationTitle,
            notificationText
        ).joinToString(" ")
        val tokens = tokenize(allText)
        return ExpenseFeatures(
            merchantName = expense.merchant,
            merchantTokens = tokens,
            notificationTitle = notificationTitle,
            notificationText = notificationText,
            allText = allText,
            amount = expense.amount,
            amountBucket = AmountBucket.fromAmount(expense.amount),
            dayOfWeek = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7, // 0 = Monday
            hourOfDay = calendar.get(Calendar.HOUR_OF_DAY),
            isWeekend = calendar.get(Calendar.DAY_OF_WEEK) in listOf(Calendar.SATURDAY, Calendar.SUNDAY),
            sourcePackage = packageName
        )
    }
    /**
     * Extract features from notification text (before expense is created).
     */
    fun extractFromNotification(
        title: String?,
        text: String?,
        packageName: String,
        amount: Double,
        merchant: String
    ): ExpenseFeatures {
        val calendar = Calendar.getInstance()
        val allText = listOfNotNull(title, text, merchant).joinToString(" ")
        val tokens = tokenize(allText)
        return ExpenseFeatures(
            merchantName = merchant,
            merchantTokens = tokens,
            notificationTitle = title,
            notificationText = text,
            allText = allText,
            amount = amount,
            amountBucket = AmountBucket.fromAmount(amount),
            dayOfWeek = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7,
            hourOfDay = calendar.get(Calendar.HOUR_OF_DAY),
            isWeekend = calendar.get(Calendar.DAY_OF_WEEK) in listOf(Calendar.SATURDAY, Calendar.SUNDAY),
            sourcePackage = packageName
        )
    }
    /**
     * Tokenize text into words.
     */
    fun tokenize(text: String): List<String> {
        return WORD_PATTERN.findAll(text.lowercase())
            .map { it.value }
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .toList()
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\intelligence\ml\HybridExpenseClassifier.kt <a name="mainjavacomyournameexpensetrackerdomainintelligencemlhybridexpenseclassifierkt"></a>
```kotlin
package com.yourname.expensetracker.domain.intelligence.ml
import android.content.Context
import android.util.Log
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.entity.Category
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
/**
 * Hybrid Expense Classifier for CATEGORIZATION.
 * Strategy priority: Rule-based -> History (TBD) -> ML prediction.
 */
@Singleton
class HybridExpenseClassifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val categoryDao: CategoryDao,
    private val nbClassifier: ExpenseCategoryClassifier
) {
    companion object {
        private const val TAG = "HybridClassifier"
        const val RULE_CONFIDENCE = 0.95f
        const val ML_THRESHOLD = 0.60f
        private val CATEGORY_KEYWORDS: Map<String, String> = mapOf(
            "mcdonalds" to "Food", "starbucks" to "Food", "pizza" to "Food",
            "restaurant" to "Food", "cafe" to "Food", "coffee" to "Food",
            "supermarket" to "Groceries", "lidl" to "Groceries", "sklavenitis" to "Groceries",
            "uber" to "Transport", "taxi" to "Transport", "bolt" to "Transport",
            "fuel" to "Transport", "gas" to "Transport", "shell" to "Transport", "bp" to "Transport",
            "amazon" to "Shopping", "netflix" to "Entertainment", "spotify" to "Entertainment"
        )
    }
    private val featureExtractor = FeatureExtractor()
    private var categories: List<Category> = emptyList()
    private var categoryMap: Map<String, Category> = emptyMap()
    suspend fun initialize() {
        categories = categoryDao.getAll()
        categoryMap = categories.associateBy { it.name.lowercase() }
    }
    suspend fun classify(
        merchantName: String,
        amount: Double,
        notificationTitle: String? = null,
        notificationText: String? = null,
        packageName: String = ""
    ): ClassificationResult = withContext(Dispatchers.Default) {
        if (categories.isEmpty()) initialize()
        val features = featureExtractor.extractFromNotification(
            title = notificationTitle,
            text = notificationText,
            packageName = packageName,
            amount = amount,
            merchant = merchantName
        )
        // 1. Rules
        val ruleResult = classifyWithRules(features)
        if (ruleResult != null && ruleResult.confidence >= RULE_CONFIDENCE) {
            return@withContext ruleResult
        }
        // 2. ML Prediction
        if (nbClassifier.isReady()) {
            val mlResults = nbClassifier.classify(features)
            if (mlResults.isNotEmpty()) {
                val best = mlResults.first()
                if (best.score >= ML_THRESHOLD) {
                    val category = categories.find { it.id == best.categoryId }
                    return@withContext ClassificationResult(
                        categoryId = best.categoryId,
                        categoryName = category?.name ?: "Unknown",
                        confidence = best.score,
                        alternatives = mlResults.take(3).map { res ->
                            res.copy(categoryName = categories.find { it.id == res.categoryId }?.name ?: "Unknown")
                        },
                        matchType = MatchType.ML_PREDICTION
                    )
                }
            }
        }
        // 3. Fallback
        val defaultCategory = categories.firstOrNull()
        ClassificationResult(
            categoryId = defaultCategory?.id ?: -1,
            categoryName = defaultCategory?.name ?: "Uncategorized",
            confidence = 0.0f,
            matchType = MatchType.FALLBACK
        )
    }
    private fun classifyWithRules(features: ExpenseFeatures): ClassificationResult? {
        val tokens = features.merchantTokens.map { it.lowercase() }
        for (token in tokens) {
            val catName = CATEGORY_KEYWORDS[token]
            if (catName != null) {
                val category = categoryMap[catName.lowercase()]
                if (category != null) {
                    return ClassificationResult(
                        categoryId = category.id,
                        categoryName = category.name,
                        confidence = 0.98f,
                        matchType = MatchType.RULE_MATCH
                    )
                }
            }
        }
        return null
    }
    suspend fun learnFromCorrection(
        merchantName: String,
        correctCategoryId: Long,
        amount: Double = 0.0,
        packageName: String = ""
    ) {
        val features = featureExtractor.extractFromNotification(
            title = null,
            text = null,
            packageName = packageName,
            amount = amount,
            merchant = merchantName
        )
        nbClassifier.train(features, correctCategoryId)
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\intelligence\ml\MerchantNormalizer.kt <a name="mainjavacomyournameexpensetrackerdomainintelligencemlmerchantnormalizerkt"></a>
```kotlin
package com.yourname.expensetracker.domain.intelligence.ml
import android.content.Context
import android.util.Log
import com.yourname.expensetracker.data.database.dao.MerchantNormalizationDao
import com.yourname.expensetracker.data.database.entity.MerchantAlias
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.domain.util.StringBKTree
import com.yourname.expensetracker.domain.util.StringDistanceUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
/**
 * Result of a merchant lookup operation
 */
data class MerchantLookupResult(
    val canonical: MerchantCanonical,
    val alias: MerchantAlias?,
    val confidence: Float,
    val matchType: MatchType
)
/**
 * Advanced Merchant Name Normalization System.
 */
@Singleton
class MerchantNormalizer @Inject constructor(
    private val dao: MerchantNormalizationDao,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "MerchantNormalizer"
        private val LOCATION_PATTERN = Regex(
            """\s*#[\dA-Za-z]+|""" +
            """\s*-\s*\d+\s*$|""" +
            """\s*Store\s*#?\s*\d+|""" +
            """\s*Branch\s*#?\s*\d+|""" +
            """\s*Unit\s*#?\s*\d+|""" +
            """\s*\([\d\s]+\)|""" +
            """\s*(AT|at|@)\s+.*$"""
        )
        private val CORPORATE_SUFFIXES = listOf(
            "INC", "INC.", "LLC", "LTD", "LTD.", "CORP", "CORP.", "CORPORATION",
            "CO", "CO.", "COMPANY", "GMBH", "S.A.", "S.A.S", "B.V.", "A.G."
        )
        private val COMMON_IGNORE_WORDS = listOf("THE", "A", "AN", "OF", "AND", "OR", "&")
    }
    private var bkTree: StringBKTree? = null
    private val treeMutex = Mutex()
    private var lastTreeRebuild = 0L
    private val TREE_REBUILD_INTERVAL = 300_000L // 5 minutes
    suspend fun normalize(
        rawName: String,
        autoCreate: Boolean = true,
        categoryId: Long? = null
    ): MerchantLookupResult = withContext(Dispatchers.Default) {
        if (rawName.isBlank()) {
            return@withContext createPlaceholder("Unknown", "unknown", categoryId)
        }
        val cleaned = cleanMerchantName(rawName)
        val normalizedKey = createSearchKey(cleaned)
        // 1. Alias match
        dao.getAliasByNormalizedKey(normalizedKey)?.let { alias ->
            val canonical = dao.getCanonicalById(alias.canonicalId)
            if (canonical != null) {
                return@withContext MerchantLookupResult(
                    canonical = canonical,
                    alias = alias,
                    confidence = if (alias.isUserDefined) 1.0f else 0.95f,
                    matchType = if (alias.isUserDefined) MatchType.USER_DEFINED else MatchType.ALIAS_MATCH
                )
            }
        }
        // 2. Exact canonical match
        dao.getCanonicalBySearchKey(normalizedKey)?.let { canonical ->
            return@withContext MerchantLookupResult(
                canonical = canonical,
                alias = null,
                confidence = 1.0f,
                matchType = MatchType.EXACT_MATCH
            )
        }
        // 3. Fuzzy matching
        val fuzzyResult = fuzzyMatch(cleaned, normalizedKey)
        if (fuzzyResult != null && fuzzyResult.confidence >= 0.80f) {
            dao.linkAliasToCanonical(rawName, fuzzyResult.canonical.id, isUserDefined = false)
            return@withContext fuzzyResult
        }
        // 4. Create new
        if (autoCreate) {
            val newCanonical = createNewMerchant(cleaned, normalizedKey, categoryId)
            dao.linkAliasToCanonical(rawName, newCanonical.id, isUserDefined = false)
            invalidateTreeCache()
            return@withContext MerchantLookupResult(
                canonical = newCanonical,
                alias = null,
                confidence = 1.0f,
                matchType = MatchType.NEW_MERCHANT
            )
        } else {
            return@withContext createPlaceholder(cleaned, normalizedKey, categoryId)
        }
    }
    fun cleanMerchantName(rawName: String): String {
        var cleaned = rawName.trim()
        cleaned = LOCATION_PATTERN.replace(cleaned, "")
        val upper = cleaned.uppercase()
        for (suffix in CORPORATE_SUFFIXES) {
            if (upper.endsWith(" $suffix") || upper.endsWith(",$suffix")) {
                cleaned = cleaned.dropLast(suffix.length + 2).trim()
            }
        }
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()
        cleaned = cleaned.trim { !it.isLetterOrDigit() }
        return cleaned.ifEmpty { rawName.trim() }
    }
    private fun createSearchKey(name: String): String {
        return name.lowercase()
            .replace(Regex("[^a-z0-9α-ωά-ώ]"), "")
            .trim()
    }
    private suspend fun fuzzyMatch(cleaned: String, normalizedKey: String): MerchantLookupResult? {
        val tree = getOrBuildTree()
        val maxDist = if (normalizedKey.length < 6) 1 else 2
        val matches = tree.search(normalizedKey, maxDist)
        if (matches.isEmpty()) return null
        val best = matches.first()
        val canonical = dao.getCanonicalBySearchKey(best.first) ?: return null
        val similarity = StringDistanceUtils.jaroWinklerSimilarity(normalizedKey, best.first)
        return MerchantLookupResult(
            canonical = canonical,
            alias = null,
            confidence = similarity.toFloat(),
            matchType = if (best.second == 0) MatchType.EXACT_MATCH else MatchType.FUZZY_MATCH
        )
    }
    private suspend fun createNewMerchant(cleaned: String, key: String, catId: Long?): MerchantCanonical {
        val canonical = MerchantCanonical(
            normalizedName = formatDisplayName(cleaned),
            searchKey = key,
            categoryId = catId,
            totalOccurrences = 1,
            isVerified = false
        )
        var id = dao.insertCanonical(canonical)
        if (id == -1L) {
            // Fix: Handle race condition where another thread created this merchant concurrently
            id = dao.getCanonicalBySearchKey(key)?.id ?: -1L
            if (id == -1L) {
                // This shouldn't happen unless something is deleting records simultaneously
                Log.e(TAG, "FATAL: Could not retrieve ID for existing canonical $key")
                throw IllegalStateException("Failed to create or retrieve merchant: $key")
            }
        }
        return canonical.copy(id = id)
    }
    private fun createPlaceholder(cleaned: String, key: String, catId: Long?): MerchantLookupResult {
        return MerchantLookupResult(
            canonical = MerchantCanonical(normalizedName = cleaned, searchKey = key, categoryId = catId),
            alias = null, confidence = 0.0f, matchType = MatchType.NEW_MERCHANT
        )
    }
    private fun formatDisplayName(name: String): String {
        return name.split(" ").joinToString(" ") { word ->
            if (word.uppercase() in COMMON_IGNORE_WORDS) word.lowercase()
            else word.lowercase().replaceFirstChar { it.uppercase() }
        }
    }
    private suspend fun getOrBuildTree(): StringBKTree {
        return treeMutex.withLock {
            val now = System.currentTimeMillis()
            if (bkTree == null || now - lastTreeRebuild > TREE_REBUILD_INTERVAL) {
                val tree = StringBKTree.create()
                dao.getTopMerchants(1000).forEach { tree.insert(it.searchKey) }
                bkTree = tree
                lastTreeRebuild = now
            }
            bkTree!!
        }
    }
    private suspend fun invalidateTreeCache() = treeMutex.withLock { bkTree = null }
}

```

---

## main\java\com\yourname\expensetracker\domain\logic\NarrativeGenerator.kt <a name="mainjavacomyournameexpensetrackerdomainlogicnarrativegeneratorkt"></a>
```kotlin
package com.yourname.expensetracker.domain.logic
import com.yourname.expensetracker.data.repository.WeatherState
import com.yourname.expensetracker.domain.model.FinancialForecast
import com.yourname.expensetracker.domain.model.RiskLevel
import com.yourname.expensetracker.domain.model.WeatherNarrative
import com.yourname.expensetracker.domain.model.NarrativeSection
import com.yourname.expensetracker.domain.model.PlannedExpensePriority
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class NarrativeGenerator @Inject constructor() {
    fun generate(
        forecast: FinancialForecast, 
        budgetStatuses: List<com.yourname.expensetracker.domain.budget.BudgetStatus>
    ): WeatherNarrative {
        val components = forecast.components
        val risk = components.riskLevel
        val discretionary = components.discretionaryBudget
        val basic = when {
            risk == RiskLevel.LOW && discretionary > 100.0 -> WeatherNarrative(
                state = WeatherState.CLEAR_SKIES,
                icon = "☀️",
                headline = "Clear Skies",
                summary = "You have a comfortable buffer of €${String.format(java.util.Locale.US, "%.2f", discretionary)} for the rest of the month."
            )
            risk == RiskLevel.LOW || risk == RiskLevel.MEDIUM -> WeatherNarrative(
                state = WeatherState.PARTLY_CLOUDY,
                icon = "⛅",
                headline = "Partly Cloudy",
                summary = "Everything is on track, though discretionary buffer is moderate (€${String.format(java.util.Locale.US, "%.2f", discretionary)})."
            )
            risk == RiskLevel.HIGH && discretionary > 0 -> WeatherNarrative(
                state = WeatherState.CLOUDY,
                icon = "☁️",
                headline = "Cloudy Forecast",
                summary = "Spending is tight. You only have €${String.format(java.util.Locale.US, "%.2f", discretionary)} remaining for unpredicted expenses."
            )
            risk == RiskLevel.HIGH -> WeatherNarrative(
                state = WeatherState.RAINY,
                icon = "🌧️",
                headline = "Rainy Conditions",
                summary = "Over pace on budgets and high committed costs. Caution is highly advised."
            )
            risk == RiskLevel.CRITICAL -> WeatherNarrative(
                state = WeatherState.STORMY,
                icon = "⛈️",
                headline = "Stormy Weather",
                summary = "⚠️ Immediate action required. Budgets exceeded and no discretionary buffer remains."
            )
            else -> WeatherNarrative(
                state = WeatherState.UNKNOWN,
                icon = "❓",
                headline = "Mixed Signals",
                summary = "Not enough data to provide a clear outlook yet."
            )
        }
        return basic.copy(details = buildDetails(forecast, budgetStatuses))
    }
    private fun buildDetails(
        forecast: FinancialForecast,
        budgetStatuses: List<com.yourname.expensetracker.domain.budget.BudgetStatus>
    ): List<NarrativeSection> {
        val sections = mutableListOf<NarrativeSection>()
        val components = forecast.components
        // 1. Budget Health Section (Momentum Engine)
        val criticalBudgets = budgetStatuses.filter { 
            it.healthStatus == com.yourname.expensetracker.domain.budget.BudgetHealthStatus.EXCEEDED ||
            it.healthStatus == com.yourname.expensetracker.domain.budget.BudgetHealthStatus.CRITICAL 
        }
        if (criticalBudgets.isNotEmpty()) {
            sections.add(
                NarrativeSection(
                    title = "Budget Alerts",
                    icon = "🚨",
                    items = criticalBudgets.map { 
                        val name = it.category?.name ?: "Total Budget"
                        "$name is ${it.healthStatus.name}: €${String.format(java.util.Locale.US, "%.0f", it.spentAmount)} spent"
                    }
                )
            )
        } else if (budgetStatuses.isNotEmpty()) {
            sections.add(
                NarrativeSection(
                    title = "Budget Health",
                    icon = "✅",
                    items = listOf("All active budgets are currently on track")
                )
            )
        }
        // 2. Goal Protection (Constraint)
        if (components.goalReserves > 0) {
            sections.add(
                NarrativeSection(
                    title = "Goal Reserves",
                    icon = "⛨",
                    items = listOf("€${String.format(java.util.Locale.US, "%.0f", components.goalReserves)} locked for high-priority savings")
                )
            )
        }
        // 3. Planned Intentions (Intention Engine)
        val importantPlans = components.plannedExpenses.filter { 
            it.priority == PlannedExpensePriority.MUST || 
            it.priority == PlannedExpensePriority.LIKELY 
        }
        if (importantPlans.isNotEmpty()) {
            sections.add(
                NarrativeSection(
                    title = "Committed Plans",
                    icon = "🎯",
                    items = importantPlans.map { 
                        val priorityLabel = if (it.priority == PlannedExpensePriority.MUST) "Must" else "Likely"
                        "${it.description}: €${String.format(java.util.Locale.US, "%.0f", it.amount)} ($priorityLabel)"
                    }
                )
            )
        }
        // 4. Predicted Habits (Behavioral)
        if (components.predictedDiscretionary > 0) {
            sections.add(
                NarrativeSection(
                    title = "Predicted Activity",
                    icon = "📈",
                    items = listOf(
                        "Habit-based forecast: €${String.format(java.util.Locale.US, "%.0f", components.predictedDiscretionary)} likely spending based on your typical month."
                    )
                )
            )
        }
        return sections
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\logic\RecurringExpenseEngine.kt <a name="mainjavacomyournameexpensetrackerdomainlogicrecurringexpenseenginekt"></a>
```kotlin
package com.yourname.expensetracker.domain.logic
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.RecurringExpenseDao
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
@Singleton
class RecurringExpenseEngine @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val recurringExpenseDao: RecurringExpenseDao
) {
    /**
     * Analyze all expenses to find recurring patterns and merge with manual overrides.
     * Returns a list of patterns sorted by confidence (Manual = 1.0).
     */
    suspend fun getPatterns(): List<RecurringPattern> {
        // 1. Fetch Manual Overrides
        val manualExpenses = recurringExpenseDao.getAll()
        val manualMap = manualExpenses.associateBy { it.merchant.lowercase() }
        // 2. Fetch all expenses for detection
        val allExpenses = expenseDao.getAll()
        // Group by normalized merchant name
        val grouped = allExpenses.groupBy { it.merchant }
        val detectedPatterns = mutableListOf<RecurringPattern>()
        for ((merchant, expenses) in grouped) {
            // If we already have a manual rule for this merchant, skip detection
            if (manualMap.containsKey(merchant.lowercase())) continue
            // Requirement: At least 3 occurrences to form a pattern
            if (expenses.size < 3) continue 
            // Sort by date ascending to calculate intervals
            val sorted = expenses.sortedBy { it.date }
            // 1. Amount Stability Check
            val amounts = sorted.map { it.amount }
            val avgAmount = amounts.average()
            val stdDevAmount = calculateStdDev(amounts)
            // Coefficient of variation: stdDev / mean
            val amountVariance = if (avgAmount > 0) stdDevAmount / avgAmount else 0.0
            // If amount varies by more than 35%, likely not a fixed subscription/bill
            if (amountVariance > 0.35) continue 
            // 2. Interval Analysis
            val dates = sorted.map { it.date }
            val intervals = calculateIntervals(dates)
            val (frequency, confidence, varianceDays) = determineFrequency(intervals)
            // Thresholds: Must be a known frequency and have > 50% confidence (LOG-013 Relaxed further to catch varying bills)
            if (frequency != RecurrenceFrequency.IRREGULAR && confidence > 0.50) {
                // Predict next date
                // Predict next date (LOG-021 Fix: Use Calendar for proper Month/Year addition)
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = dates.last()
                when (frequency) {
                    RecurrenceFrequency.MONTHLY -> cal.add(java.util.Calendar.MONTH, 1)
                    RecurrenceFrequency.QUARTERLY -> cal.add(java.util.Calendar.MONTH, 3)
                    RecurrenceFrequency.SEMI_ANNUALLY -> cal.add(java.util.Calendar.MONTH, 6)
                    RecurrenceFrequency.ANNUALLY -> cal.add(java.util.Calendar.YEAR, 1)
                    else -> cal.add(java.util.Calendar.DAY_OF_YEAR, frequency.days)
                }
                val nextDate = cal.timeInMillis
                detectedPatterns.add(
                    RecurringPattern(
                        merchantName = merchant,
                        averageAmount = avgAmount,
                        currency = sorted.first().currency,
                        frequency = frequency,
                        periodVarianceDays = varianceDays,
                        amountVariancePercent = amountVariance,
                        nextExpectedDate = nextDate,
                        confidence = confidence.toFloat(),
                        previousDates = dates.takeLast(5),
                        categoryId = sorted.first().categoryId
                    )
                )
            }
        }
        // 3. Convert Manual Entries to RecurringPattern
        val manualPatterns = manualExpenses.map { manual ->
            RecurringPattern(
                merchantName = manual.merchant,
                averageAmount = manual.amount,
                currency = manual.currency,
                frequency = manual.frequency,
                periodVarianceDays = 0,
                amountVariancePercent = 0.0,
                nextExpectedDate = manual.nextDate,
                confidence = 1.0f, // Manual is 100% confident
                previousDates = emptyList(), // No history needed for display
                categoryId = null, // Manual entries don't have categoryId yet
                id = manual.id // Use DB ID
            )
        }
        return (manualPatterns + detectedPatterns).sortedByDescending { it.confidence }
    }
    private fun calculateIntervals(dates: List<Long>): List<Long> {
        if (dates.size < 2) return emptyList()
        val intervals = mutableListOf<Long>()
        for (i in 0 until dates.size - 1) {
            val diff = dates[i+1] - dates[i]
            intervals.add(diff)
        }
        return intervals
    }
    private fun calculateStdDev(values: List<Double>): Double {
        return com.yourname.expensetracker.domain.util.StatisticsUtils.calculateStdDev(values)
    }
    private fun determineFrequency(intervalsMs: List<Long>): Triple<RecurrenceFrequency, Double, Int> {
        if (intervalsMs.isEmpty()) return Triple(RecurrenceFrequency.IRREGULAR, 0.0, 0)
        // Convert ms to days (round to nearest integer day)
        val intervalsDays = intervalsMs.map { (it / 86_400_000.0).roundToInt() }
        // Find Mode (most common interval)
        val frequencyMap = intervalsDays.groupingBy { it }.eachCount()
        val modeEntry = frequencyMap.maxByOrNull { it.value } 
            ?: return Triple(RecurrenceFrequency.IRREGULAR, 0.0, 0)
        val mode = modeEntry.key
        // Map mode to known frequencies with tolerance
        val frequency = when (mode) {
             in 5..9 -> RecurrenceFrequency.WEEKLY
             in 11..17 -> RecurrenceFrequency.BIWEEKLY
             in 25..35 -> RecurrenceFrequency.MONTHLY // Covers shifts due to weekends/month length
             in 80..100 -> RecurrenceFrequency.QUARTERLY
             in 170..190 -> RecurrenceFrequency.SEMI_ANNUALLY
             in 350..380 -> RecurrenceFrequency.ANNUALLY
             else -> RecurrenceFrequency.IRREGULAR
        }
        if (frequency == RecurrenceFrequency.IRREGULAR) {
            return Triple(RecurrenceFrequency.IRREGULAR, 0.0, 0)
        }
        // Calculate Confidence
        // Score based on how many intervals are "close" to the mode (within ±20% or ±1 day)
        val tolerance = (mode * 0.2).coerceAtLeast(1.0)
        val matchingIntervals = intervalsDays.count { abs(it - mode) <= tolerance }
        val consistencyScore = matchingIntervals.toDouble() / intervalsDays.size
        // Calculate Average Deviation (days)
        val deviations = intervalsDays.map { abs(it - mode) }
        val avgDeviation = deviations.average()
        return Triple(frequency, consistencyScore, avgDeviation.roundToInt())
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\logic\SynthesisEngine.kt <a name="mainjavacomyournameexpensetrackerdomainlogicsynthesisenginekt"></a>
```kotlin
package com.yourname.expensetracker.domain.logic
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.model.*
import java.time.Instant
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class SynthesisEngine @Inject constructor() {
    fun synthesize(
        pastSumDaily: List<Double>,
        recurringPatterns: List<RecurringPattern>,
        plannedExpenses: List<PlannedExpense>,
        savingsGoals: List<SavingsGoal>,
        budgetStatuses: List<BudgetStatus>,
        spendingPace: SpendingPace
    ): FinancialForecast {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
        val daysRemaining = (daysInMonth - dayOfMonth).coerceAtLeast(1)
        val endOfMonthCal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, daysInMonth)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        val endOfMonth = endOfMonthCal.timeInMillis
        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        // 1. Calculate Committed (Highly likely/Automated/Must happen)
        val committedUpcomingBills = recurringPatterns.filter { 
            it.confidence >= 0.90f && it.nextExpectedDate >= startOfToday && it.nextExpectedDate <= endOfMonth 
        }.sumOf { it.averageAmount }
        val committedPlanned = plannedExpenses.filter {
            it.priority == PlannedExpensePriority.MUST && it.date >= startOfToday && it.date <= endOfMonth
        }.sumOf { it.amount }
        val totalCommitted = committedUpcomingBills + committedPlanned
        // 2. Calculate Likely (Probable behavior)
        val likelyUpcomingBills = recurringPatterns.filter { 
            it.confidence in 0.70f..0.89f && it.nextExpectedDate >= startOfToday && it.nextExpectedDate <= endOfMonth
        }.sumOf { it.averageAmount }
        val likelyPlanned = plannedExpenses.filter {
            it.priority == PlannedExpensePriority.LIKELY && it.date >= startOfToday && it.date <= endOfMonth
        }.sumOf { it.amount }
        val monthlyRecurringTotal = recurringPatterns.sumOf { pattern ->
            when (pattern.frequency) {
                RecurrenceFrequency.WEEKLY -> pattern.averageAmount * (30.0 / 7.0)
                RecurrenceFrequency.BIWEEKLY -> pattern.averageAmount * (30.0 / 14.0)
                RecurrenceFrequency.MONTHLY -> pattern.averageAmount
                RecurrenceFrequency.QUARTERLY -> pattern.averageAmount / 3.0
                RecurrenceFrequency.SEMI_ANNUALLY -> pattern.averageAmount / 6.0
                RecurrenceFrequency.ANNUALLY -> pattern.averageAmount / 12.0
                else -> 0.0
            }
        }
        val typicalDailyDiscretionary = spendingPace.averageMonthlyTotal?.let { (it - monthlyRecurringTotal).coerceAtLeast(0.0) / daysInMonth } 
            ?: (spendingPace.previousMonthTotal?.let { (it - monthlyRecurringTotal).coerceAtLeast(0.0) / daysInMonth })
            ?: 0.0
        val predictedDiscretionary = typicalDailyDiscretionary * daysRemaining
        val totalLikely = likelyUpcomingBills + likelyPlanned
        // 3. Goal Reserves
        // Strict goals are subtracted from "Available"
        // 3. Goal Reserves (Pro-rated for strict goals - LOG-019)
        val goalReserves = savingsGoals
            .filter { it.protectionLevel == GoalProtectionLevel.STRICT }
            .sumOf { goal ->
                 val remaining = (goal.targetAmount - goal.currentAmount).coerceAtLeast(0.0)
                 if (remaining <= 0) 0.0
                 else {
                     val targetDate = goal.targetDate
                     if (targetDate == null || targetDate <= now) remaining // Due now or past due
                     else {
                         val msRemaining = targetDate - now
                         val monthsRemaining = (msRemaining / (30.0 * 24 * 60 * 60 * 1000)).coerceAtLeast(1.0)
                         remaining / monthsRemaining
                     }
                 }
            }
        // 4. Calculate Projected Timeline Points
        val lastKnownTotal = pastSumDaily.lastOrNull() ?: 0.0
        val dailyProjectionRate = (totalLikely + predictedDiscretionary) / daysRemaining
        val projectedPoints = (1..daysRemaining).map { dayIndex ->
            lastKnownTotal + (dailyProjectionRate * dayIndex)
        }
        // 5. Calculate Discretionary (Available)
        val overallBudget = budgetStatuses.find { it.budget.categoryId == null }?.budget?.amount ?: 0.0
        val categoryBudgetsSum = budgetStatuses.filter { it.budget.categoryId != null }.sumOf { it.budget.amount }
        val budgetLimit = if (overallBudget > 0) overallBudget else categoryBudgetsSum
        val spentSoFar = spendingPace.currentMonthSpent
        // Revised Formula: Limit - (Spent + Future Committed + Future Likely + Goal Reserves)
        // If budgetLimit is 0, we use a fallback or express "Unknown" state
        val projectedObligations = committedUpcomingBills + committedPlanned + likelyUpcomingBills + likelyPlanned
        val discretionaryBudget = if (budgetLimit > 0) {
            (budgetLimit - (spentSoFar + projectedObligations + goalReserves)).coerceAtLeast(0.0)
        } else {
            // If no budget is set, the discretionary "pool" isn't 0 (which looks like "No money left"),
            // it's effectively unlimited/unknown vs a goal. 
            // We'll return 0.0 for now but the RiskLevel will signal NO_BUDGET
            0.0
        }
        // 6. Determine Risk Level
        val riskLevel = determineRiskLevel(
            spendingPace, 
            budgetStatuses, 
            discretionaryBudget, 
            budgetLimit
        )
        return FinancialForecast(
            horizon = ForecastHorizon.REST_OF_MONTH,
            generatedAt = Instant.now(),
            confidence = 0.85, 
            components = ForecastComponents(
                recurringExpenses = recurringPatterns,
                plannedExpenses = plannedExpenses,
                goalReserves = goalReserves,
                projectedCategorySpending = emptyMap(),
                pastSpendingPoints = pastSumDaily,
                projectedSpendingPoints = projectedPoints,
                totalCommitted = totalCommitted,
                totalLikely = totalLikely,
                predictedDiscretionary = predictedDiscretionary,
                discretionaryBudget = discretionaryBudget,
                riskLevel = riskLevel
            ),
            actionableInsights = buildInsights(riskLevel, budgetStatuses, spendingPace, plannedExpenses, savingsGoals)
        )
    }
    private fun determineRiskLevel(
        pace: SpendingPace,
        budgets: List<BudgetStatus>,
        discretionary: Double,
        limit: Double
    ): RiskLevel {
        val criticalBudgets = budgets.count { it.healthStatus == BudgetHealthStatus.CRITICAL || it.healthStatus == BudgetHealthStatus.EXCEEDED }
        val overPace = pace.paceStatus == PaceStatus.OVER_PACE
        // Ratio of discretionary to total budget
        val bufferRatio = if (limit > 0) discretionary / limit else 0.0
        return when {
            // Priority 1: Critical Budget Issues or Severe Overspending with no buffer
            criticalBudgets > 0 -> RiskLevel.CRITICAL
            overPace && bufferRatio < 0.05 -> RiskLevel.CRITICAL
            // Priority 2: High Risk (Overspending or Low Buffer)
            overPace -> RiskLevel.HIGH // If overPace but buffer > 0.05
            bufferRatio < 0.1 -> RiskLevel.HIGH
            // Priority 3: Medium Risk
            bufferRatio < 0.2 -> RiskLevel.MEDIUM
            // Priority 4: Low Risk
            else -> RiskLevel.LOW
        }
    }
    private fun buildInsights(
        risk: RiskLevel,
        budgets: List<BudgetStatus>,
        pace: SpendingPace,
        planned: List<PlannedExpense>,
        goals: List<SavingsGoal>
    ): List<String> {
        val insights = mutableListOf<String>()
        if (pace.paceStatus == PaceStatus.OVER_PACE) insights.add("Spending pace is higher than usual.")
        val exceeded = budgets.count { it.healthStatus == BudgetHealthStatus.EXCEEDED }
        if (exceeded > 0) insights.add("$exceeded budgets exceeded.")
        val strictGoalCount = goals.count { it.protectionLevel == GoalProtectionLevel.STRICT }
        if (strictGoalCount > 0) insights.add("$strictGoalCount strict savings goals active.")
        val mustPlannedCount = planned.count { it.priority == PlannedExpensePriority.MUST }
        if (mustPlannedCount > 0) insights.add("$mustPlannedCount must-pay planned expenses this month.")
        return insights
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\model\FinancialForecast.kt <a name="mainjavacomyournameexpensetrackerdomainmodelfinancialforecastkt"></a>
```kotlin
package com.yourname.expensetracker.domain.model
import java.time.Instant
data class FinancialForecast(
    val horizon: ForecastHorizon,
    val generatedAt: Instant,
    val confidence: Double, // 0.0 - 1.0
    val components: ForecastComponents,
    val actionableInsights: List<String>
)
enum class ForecastHorizon(val days: Int, val displayName: String) {
    NEXT_7_DAYS(7, "Next 7 Days"),
    NEXT_30_DAYS(30, "Next 30 Days"),
    REST_OF_MONTH(0, "Rest of Month") // 0 means calculate based on calendar
}
data class ForecastComponents(
    val recurringExpenses: List<RecurringPattern>,
    val plannedExpenses: List<PlannedExpense> = emptyList(), // Manual intentions
    val goalReserves: Double = 0.0, // Money locked in goals
    val projectedCategorySpending: Map<Long, Double>, // Category ID to amount
    // Timeline Data
    val pastSpendingPoints: List<Double>, // Cumulative daily spend up to today
    val projectedSpendingPoints: List<Double>, // Projected cumulative daily spend for rest of month
    // Synthesis Metrics
    val totalCommitted: Double,        // High confidence (bills, manual)
    val totalLikely: Double,           // Medium confidence (patterns, manual)
    val predictedDiscretionary: Double, // Habit-based predicted spending
    val discretionaryBudget: Double,   // "Safe-to-Spend"
    val riskLevel: RiskLevel
)
enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
data class WeatherNarrative(
    val state: com.yourname.expensetracker.data.repository.WeatherState,
    val icon: String,
    val headline: String,
    val summary: String,
    val details: List<NarrativeSection> = emptyList()
)
data class NarrativeSection(
    val title: String,
    val icon: String,
    val items: List<String>
)

```

---

## main\java\com\yourname\expensetracker\domain\model\PlannedExpense.kt <a name="mainjavacomyournameexpensetrackerdomainmodelplannedexpensekt"></a>
```kotlin
package com.yourname.expensetracker.domain.model
data class PlannedExpense(
    val id: Long,
    val description: String,
    val amount: Double,
    val date: Long,
    val categoryId: Long?,
    val isRecurring: Boolean,
    val priority: PlannedExpensePriority
)
enum class PlannedExpensePriority {
    MUST,
    LIKELY,
    OPTIONAL
}

```

---

## main\java\com\yourname\expensetracker\domain\model\RecurringPattern.kt <a name="mainjavacomyournameexpensetrackerdomainmodelrecurringpatternkt"></a>
```kotlin
package com.yourname.expensetracker.domain.model
import java.time.LocalDate
data class RecurringPattern(
    val merchantName: String,
    val averageAmount: Double,
    val currency: String,
    val frequency: RecurrenceFrequency,
    val periodVarianceDays: Int, // e.g. ±2 days
    val amountVariancePercent: Double, // e.g. 0.05 (5%)
    val nextExpectedDate: Long, // Epoch millis
    val confidence: Float, // 0.0 - 1.0
    val previousDates: List<Long>, // For debugging/UI visualization
    val categoryId: Long? = null,
    val id: Long? = null // ID of the underlying RecurringExpense rule, if any
)
enum class RecurrenceFrequency(val days: Int) {
    WEEKLY(7),
    BIWEEKLY(14),
    MONTHLY(30),
    QUARTERLY(90),
    SEMI_ANNUALLY(180),
    ANNUALLY(365),
    IRREGULAR(0);
    val intervalInMs: Long
        get() = days * 86_400_000L
}

```

---

## main\java\com\yourname\expensetracker\domain\model\Result.kt <a name="mainjavacomyournameexpensetrackerdomainmodelresultkt"></a>
```kotlin
package com.yourname.expensetracker.domain.model
/**
 * A generic class that holds a value with its loading status.
 * @param <T>
 */
sealed class Result<out T> {
    data class Success<out T>(val data: T) : Result<T>()
    data class Error(val exception: Throwable? = null, val message: String? = null) : Result<Nothing>()
    object Loading : Result<Nothing>()
    override fun toString(): String {
        return when (this) {
            is Success<*> -> "Success[data=$data]"
            is Error -> "Error[exception=$exception, message=$message]"
            Loading -> "Loading"
        }
    }
}
val Result<*>.succeeded
    get() = this is Result.Success && data != null

```

---

## main\java\com\yourname\expensetracker\domain\model\SavingsGoal.kt <a name="mainjavacomyournameexpensetrackerdomainmodelsavingsgoalkt"></a>
```kotlin
package com.yourname.expensetracker.domain.model
data class SavingsGoal(
    val id: Long,
    val name: String,
    val targetAmount: Double,
    val currentAmount: Double,
    val targetDate: Long?,
    val protectionLevel: GoalProtectionLevel
)
enum class GoalProtectionLevel {
    STRICT,
    WARNING,
    TRACKING
}

```

---

## main\java\com\yourname\expensetracker\domain\model\UpcomingItem.kt <a name="mainjavacomyournameexpensetrackerdomainmodelupcomingitemkt"></a>
```kotlin
package com.yourname.expensetracker.domain.model
sealed class UpcomingItem {
    abstract val id: String
    abstract val description: String
    abstract val amount: Double
    abstract val date: Long
    abstract val categoryId: Long?
    data class Recurring(
        val pattern: RecurringPattern
    ) : UpcomingItem() {
        override val id: String = "recurring_${pattern.merchantName}"
        override val description: String = pattern.merchantName
        override val amount: Double = pattern.averageAmount
        override val date: Long = pattern.nextExpectedDate
        override val categoryId: Long? = pattern.categoryId
    }
    data class Planned(
        val expense: PlannedExpense
    ) : UpcomingItem() {
        override val id: String = "planned_${expense.id}"
        override val description: String = expense.description
        override val amount: Double = expense.amount
        override val date: Long = expense.date
        override val categoryId: Long? = expense.categoryId
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\parser\AppParserRegistry.kt <a name="mainjavacomyournameexpensetrackerdomainparserappparserregistrykt"></a>
```kotlin
package com.yourname.expensetracker.domain.parser
import com.yourname.expensetracker.domain.parser.parsers.GoogleWalletParser
import com.yourname.expensetracker.domain.parser.parsers.GreekBankParser
import com.yourname.expensetracker.domain.parser.parsers.RevolutParser
import com.yourname.expensetracker.domain.parser.parsers.SmsParser
import javax.inject.Inject
import javax.inject.Singleton
import com.yourname.expensetracker.data.database.entity.TransactionType
/**
 * Result from an app-specific parser. Higher confidence = more certain it's a real transaction.
 */
data class ParsedTransaction(
    val amount: Double,
    val currency: String,
    val merchant: String,
    val type: TransactionType,
    val confidence: Float, // 0.0 to 1.0
    val date: Long? = null
)
/**
 * Interface for app-specific notification parsers.
 */
interface AppNotificationParser {
    /** Package names this parser handles */
    val supportedPackages: Set<String>
    /**
     * Try to parse. Return null if notification is NOT a transaction.
     * Should be strict — only return a result when confident.
     */
    fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction?
}
/**
 * Registry that routes notifications to the right parser.
 */
@Singleton
class AppParserRegistry @Inject constructor(
    private val greekBankParser: GreekBankParser,
    private val revolutParser: RevolutParser,
    private val smsParser: SmsParser,
    private val googleWalletParser: GoogleWalletParser,
    private val genericParser: GenericTransactionParser
) {
    private val parsers = mutableListOf<AppNotificationParser>()
    init {
        // Order matters: Specific parsers first
        parsers.add(greekBankParser)
        parsers.add(revolutParser)
        parsers.add(smsParser)
        parsers.add(googleWalletParser)
    }
    fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        // 1. Try app-specific parser first
        val specificParser = parsers.find { packageName in it.supportedPackages }
        if (specificParser != null) {
            return specificParser.parse(title, text, bigText, subText, packageName)
        }
        // 2. Fallback to generic parser with HIGH threshold
        return genericParser.parse(title, text, bigText, subText, packageName)
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\parser\GenericTransactionParser.kt <a name="mainjavacomyournameexpensetrackerdomainparsergenerictransactionparserkt"></a>
```kotlin
package com.yourname.expensetracker.domain.parser
import com.yourname.expensetracker.data.database.entity.TransactionType
import java.util.regex.Pattern
/**
 * Fallback parser for unknown apps. VERY strict — requires both
 * a strong transaction signal AND a plausible amount pattern.
 * Returns results with lower confidence.
 */
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import javax.inject.Inject
/**
 * Fallback parser for unknown apps. VERY strict — requires both
 * a strong transaction signal AND a plausible amount pattern.
 * Returns results with lower confidence.
 */
class GenericTransactionParser @Inject constructor(
    private val currencyNormalizer: CurrencyNormalizer,
    private val merchantCleaner: MerchantCleaner
) {
    // Strong signals that this is a REAL transaction notification
    private val strongTransactionSignals by lazy {
        listOf(
            // English patterns that strongly indicate actual transactions
            Pattern.compile("""(?:you\s+)?paid\s+[€$£]?\s*\d""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""payment\s+(?:of\s+)?[€$£]\s*\d""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""charged?\s+[€$£]?\s*\d""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""(?:debit|deducted)\s+[€$£]?\s*\d""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""transaction\s+(?:of\s+)?[€$£]\s*\d""", Pattern.CASE_INSENSITIVE),
            // Greek patterns - using UNICODE_CASE and \p{L} for Greek letters
            Pattern.compile("""(?:πληρω|χρεω|αγορ[αά])[\p{L}]*\s+\d+[.,]\d{2}\s*(?:€|EUR)""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
            Pattern.compile("""(?:€|EUR)\s*\d+[.,]\d{2}\s*(?:στ[οη]|at)\s""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
            // Greeklish patterns
            Pattern.compile("""(?:pliromi|xreosi|hreosi|agora)\w*\s+\d+[.,]\d{2}\s*(?:€|EUR)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""(?:€|EUR)\s*\d+[.,]\d{2}\s*(?:sto|se|stin?)\s""", Pattern.CASE_INSENSITIVE),
        )
    }
    // NEGATIVE signals — if present, this is NOT a transaction
    // Using Regex to enforce word boundaries for English words to avoid "Coffee" matching "offer"
    private val negativeSignalsPattern by lazy {
        Pattern.compile(
            """\b(offer|discount|save\s+up\s+to|earn|free|up\s+to|starting\s+from|balance|otp|verification|code|unsubscribe|opt\s+out|sale|%\s+off|promo|your\s+order|tracking|shipped|delivered|reminder|rate\s+us|review|survey)\b|""" +
            """(προσφορά|έκπτωση|εξοικονομ|κέρδισε|δωρεάν|έως|από|υπόλοιπο|κωδικός|υπενθύμιση)""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    }
    private val amountPattern by lazy {
        Pattern.compile(
            """([€$£])\s*(\d+(?:[.,]\d{2})?)|(\d+(?:[.,]\d{2})?)\s*([€$£]|EUR|USD|GBP)""",
            Pattern.CASE_INSENSITIVE
        )
    }
    private val MERCHANT_PREFIXES = listOf(" at ", " to ", " σε ", " στον ", " στην ", " στο ", " για ", " sto ", " ston ", " stin ", " se ")
    fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        val fullText = listOfNotNull(title, text, bigText).joinToString(" ")
        val lowerFull = fullText.lowercase()
        // 1. Check negative signals first
        if (negativeSignalsPattern.matcher(lowerFull).find()) return null
        // 2. Require at least one STRONG transaction signal
        val hasStrongSignal = strongTransactionSignals.any { it.matcher(lowerFull).find() }
        if (!hasStrongSignal) return null
        // 3. Extract amount
        val amountResult = extractAmount(fullText) ?: return null
        // 4. Sanity check amount
        if (amountResult.first < 0.10 || amountResult.first > 25000) return null
        // 5. Extract merchant
        val merchant = extractMerchant(fullText, title)
        return ParsedTransaction(
            amount = amountResult.first,
            currency = amountResult.second,
            merchant = merchant,
            type = TransactionType.PURCHASE,
            confidence = 0.60f // Lower confidence for generic parser
        )
    }
    private fun extractAmount(text: String): Pair<Double, String>? {
        val matcher = amountPattern.matcher(text)
        if (matcher.find()) {
            val currency = matcher.group(1) ?: matcher.group(4) ?: "€"
            val amountStr = (matcher.group(2) ?: matcher.group(3))?.replace(",", ".") ?: return null
            val amount = amountStr.toDoubleOrNull() ?: return null
            return Pair(amount, currencyNormalizer.normalize(currency))
        }
        return null
    }
    private fun extractMerchant(text: String, title: String?): String {
        val normalized = text.replace('\u00A0', ' ')
        for (prefix in MERCHANT_PREFIXES) {
            val index = normalized.indexOf(prefix, ignoreCase = true)
            if (index != -1) {
                val after = normalized.substring(index + prefix.length).trim()
                return merchantCleaner.clean(after)
            }
        }
        // Fallback to title if it's not a generic keyword
        if (!title.isNullOrBlank() && !isGenericTitle(title.lowercase())) {
            return merchantCleaner.clean(title)
        }
        return "Unknown"
    }
    private fun isGenericTitle(title: String): Boolean {
        val genericWords = listOf("payment", "purchase", "transaction", "alert", "notification",
            "πληρωμή", "αγορά", "συναλλαγή", "ειδοποίηση")
        return genericWords.any { title.contains(it) }
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\parser\parsers\GoogleWalletParser.kt <a name="mainjavacomyournameexpensetrackerdomainparserparsersgooglewalletparserkt"></a>
```kotlin
package com.yourname.expensetracker.domain.parser.parsers
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.AppNotificationParser
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import java.util.regex.Pattern
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import javax.inject.Inject
class GoogleWalletParser @Inject constructor(
    private val currencyNormalizer: CurrencyNormalizer,
    private val merchantCleaner: MerchantCleaner
) : AppNotificationParser {
    override val supportedPackages = setOf(
        "com.google.android.apps.walletnfcrel",
        "com.google.android.apps.nbu.paisa.user"
    )
    private val amountPattern by lazy {
        Pattern.compile(
            """([€$£])\s*(\d+[.,]\d{2})|(\d+[.,]\d{2})\s*([€$£]|EUR|USD|GBP)""",
            Pattern.CASE_INSENSITIVE
        )
    }
    private val atPattern by lazy {
        Pattern.compile("""(?:at|to)\s+([A-Za-zΑ-Ωα-ω0-9\s&'.,-]+)""", Pattern.CASE_INSENSITIVE)
    }
    // Things that are NOT transactions
    private val REJECT_PATTERNS = listOf(
        "add a card", "set up", "tap to pay", "loyalty", "offer",
        "reward", "cashback available", "nearby", "suggest"
    )
    override fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        val fullText = listOfNotNull(title, text, bigText).joinToString(" ")
        val lowerFull = fullText.lowercase()
        if (REJECT_PATTERNS.any { lowerFull.contains(it) }) return null
        // Extract amount from anywhere in the notification
        val amount = extractAmount(fullText) ?: return null
        // Extract merchant: usually the title IS the merchant, or text contains "at MERCHANT"
        val merchant = extractMerchant(title, text, bigText)
        return ParsedTransaction(
            amount = amount.first,
            currency = amount.second,
            merchant = merchant,
            type = TransactionType.PURCHASE,
            confidence = 0.90f
        )
    }
    private fun extractAmount(text: String): Pair<Double, String>? {
        val matcher = amountPattern.matcher(text)
        if (matcher.find()) {
            val prefixCurrency = matcher.group(1) ?: matcher.group(4)
            val amountStr = (matcher.group(2) ?: matcher.group(3))?.replace(",", ".") ?: return null
            val amount = amountStr.toDoubleOrNull() ?: return null
            // Filter unrealistic amounts
            if (amount < 0.01 || amount > 50000) return null
            return Pair(amount, currencyNormalizer.normalize(prefixCurrency))
        }
        return null
    }
    private fun extractMerchant(title: String?, text: String?, bigText: String?): String {
        // Check for "at MERCHANT" pattern in text
        val combinedText = listOfNotNull(text, bigText).joinToString(" ")
        val atMatcher = atPattern.matcher(combinedText)
        if (atMatcher.find()) {
            return merchantCleaner.clean(atMatcher.group(1))
        }
        // Title might be the merchant if it doesn't contain amount/payment keywords
        if (!title.isNullOrBlank()) {
            val lowerTitle = title.lowercase()
            val isAmount = amountPattern.matcher(title).find()
            val isKeyword = listOf("payment", "purchase", "paid", "transaction", "google wallet", "wallet").any { lowerTitle.contains(it) }
            if (!isAmount && !isKeyword) {
                return merchantCleaner.clean(title)
            }
        }
        return "Unknown"
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\parser\parsers\GreekBankParser.kt <a name="mainjavacomyournameexpensetrackerdomainparserparsersgreekbankparserkt"></a>
```kotlin
package com.yourname.expensetracker.domain.parser.parsers
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.AppNotificationParser
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import java.util.regex.Pattern
import javax.inject.Inject
/**
 * Parser for Greek banking apps (NBG, Alpha, Eurobank, Piraeus).
 * These typically send very structured SMS-like notifications.
 */
class GreekBankParser @Inject constructor(
    private val currencyNormalizer: CurrencyNormalizer,
    private val merchantCleaner: MerchantCleaner
) : AppNotificationParser {
    override val supportedPackages = setOf(
        "gr.nbg.mobilebanking",
        "gr.alpha.mobile",
        "com.eurobank.mobile",
        "com.winbank.mobile"
    )
    private val PURCHASE_PATTERNS = listOf(
        // "Αγορά 12,50 EUR στο MERCHANT" or "Πληρωμή €6.30 σε..."
        Pattern.compile(
            """(?:αγορ[άα]|χρ[έε]ωσ|συναλλαγ[ήη]|πληρ[ώω]σ?(?:ατε|μ[ήη])?|payment|purchase)\s+(?:[€$£]|EUR|USD|GBP)?\s*(\d+[.,]\d{2})\s*(?:EUR|€|USD|GBP)?\s*(?:στ[οη]ν?|σε|at|-)?\s*(.+?)(?:\s*(?:με|with)\s*κ[άα]ρτ|$)""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        ),
        // "€12.50 at MERCHANT" or "12,50€ MERCHANT"
        Pattern.compile(
            """([€$£])\s*(\d+[.,]\d{2})\s*(?:at|στ[οη]ν?|σε|-)\s+(.+?)(?:\s*(?:με|with)|$)""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        ),
        // "MERCHANT 12,50 EUR"
        Pattern.compile(
            """(?:χρ[έε]ωσ[ηη]?\s*κ[άα]ρτ[αά]ς?\s*\*?\d*:?\s*)(\d+[.,]\d{2})\s*(EUR|€)?\s*[-–]\s*(.+)""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    )
    // Patterns to REJECT
    private val REJECT_PATTERNS = listOf(
        "υπόλοιπο", "balance", "otp", "κωδικός", "code",
        "ενεργοποί", "activate", "εγκρίθηκε η αίτηση",
        "προσφορά", "offer", "έκπτωση", "discount",
        "ενημέρωση", "update", "reminder"
    )
    override fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        val fullText = listOfNotNull(title, text, bigText).joinToString(" ")
        val lowerFull = fullText.lowercase()
        // Quick reject
        if (REJECT_PATTERNS.any { lowerFull.contains(it) }) return null
        for (pattern in PURCHASE_PATTERNS) {
            val matcher = pattern.matcher(fullText)
            if (matcher.find()) {
                // Groups vary by pattern but we try to extract amount and merchant
                return tryExtract(matcher, fullText)
            }
        }
        return null
    }
    private fun tryExtract(matcher: java.util.regex.Matcher, fullText: String): ParsedTransaction? {
        // Try to find the amount (could be in group 1 or 2)
        var amountStr: String? = null
        var currency = "EUR"
        var merchant = "Unknown"
        for (i in 1..matcher.groupCount()) {
            val group = matcher.group(i) ?: continue
            if (group.matches(Regex("""\d+[.,]\d{2}"""))) {
                amountStr = group
            } else if (group.matches(Regex("""[€$£]|EUR|USD|GBP""", RegexOption.IGNORE_CASE))) {
                currency = currencyNormalizer.normalize(group)
            } else if (group.length > 2) {
                merchant = merchantCleaner.clean(group)
            }
        }
        val amount = amountStr?.replace(",", ".")?.toDoubleOrNull() ?: return null
        if (amount < 0.01 || amount > 50000) return null
        return ParsedTransaction(
            amount = amount,
            currency = currency,
            merchant = merchant,
            type = TransactionType.PURCHASE,
            confidence = 0.92f
        )
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\parser\parsers\RevolutParser.kt <a name="mainjavacomyournameexpensetrackerdomainparserparsersrevolutparserkt"></a>
```kotlin
package com.yourname.expensetracker.domain.parser.parsers
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.AppNotificationParser
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import java.util.regex.Pattern
import javax.inject.Inject
class RevolutParser @Inject constructor(
    private val currencyNormalizer: CurrencyNormalizer,
    private val merchantCleaner: MerchantCleaner
) : AppNotificationParser {
    override val supportedPackages = setOf("com.revolut.revolut")
    // Revolut notifications typically look like:
    // Title: "Paid €12.50" or "💳 €12.50 at SKLAVENITIS"
    // Text: "💳 €12.50 at SKLAVENITIS" or "You paid €5.00 to John"
    // Also: "Received €100.00 from John"
    // Also: "ATM withdrawal: €50.00"
    // Ignore: "Your exchange rate...", "Weekly report", "Special offer"
    private val PAID_PATTERN = Pattern.compile(
        """(?:paid|sent|💳)\s*([€$£]|EUR|USD|GBP)?\s*(\d+[.,]\d{2})\s*(?:at|to)\s+(.+)""",
        Pattern.CASE_INSENSITIVE
    )
    private val RECEIVED_PATTERN = Pattern.compile(
        """received\s*([€$£]|EUR|USD|GBP)?\s*(\d+[.,]\d{2})\s*from\s+(.+)""",
        Pattern.CASE_INSENSITIVE
    )
    private val ATM_PATTERN = Pattern.compile(
        """(?:atm|withdrawal)[:\s]*([€$£]|EUR|USD|GBP)?\s*(\d+[.,]\d{2})""",
        Pattern.CASE_INSENSITIVE
    )
    // Patterns to REJECT (not transactions)
    private val REJECT_PATTERNS = listOf(
        "exchange rate", "weekly report", "special offer", "cashback",
        "refer a friend", "upgrade", "verify", "security", "pin",
        "top-up reminder", "price alert", "savings vault"
    )
    override fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        // Check each field individually to avoid "doubling" content which confuses greedy regex
        val candidates = listOfNotNull(title, text, bigText)
        for (content in candidates) {
            val lower = content.lowercase()
            if (REJECT_PATTERNS.any { lower.contains(it) }) return null
            // Try paid/purchase pattern
            val paidMatcher = PAID_PATTERN.matcher(content)
            val receivedMatcher = RECEIVED_PATTERN.matcher(content)
            val atmMatcher = ATM_PATTERN.matcher(content)
            if (paidMatcher.find()) {
                val amount = paidMatcher.group(2)?.replace(",", ".")?.toDoubleOrNull() ?: return null
                val currency = currencyNormalizer.normalize(paidMatcher.group(1))
                val merchant = merchantCleaner.clean(paidMatcher.group(3))
                return ParsedTransaction(amount, currency, merchant, TransactionType.PURCHASE, 0.95f)
            } else if (receivedMatcher.find()) {
                val amount = receivedMatcher.group(2)?.replace(",", ".")?.toDoubleOrNull() ?: return null
                val currency = currencyNormalizer.normalize(receivedMatcher.group(1))
                val merchant = merchantCleaner.clean(receivedMatcher.group(3))
                return ParsedTransaction(amount, currency, merchant, TransactionType.DEPOSIT, 0.90f)
            } else if (atmMatcher.find()) {
                val amount = atmMatcher.group(2)?.replace(",", ".")?.toDoubleOrNull() ?: continue
                val currency = currencyNormalizer.normalize(atmMatcher.group(1))
                return ParsedTransaction(amount, currency, "ATM", TransactionType.WITHDRAWAL, 0.95f)
            }
        }
        return null
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\parser\parsers\SmsParser.kt <a name="mainjavacomyournameexpensetrackerdomainparserparserssmsparserkt"></a>
```kotlin
package com.yourname.expensetracker.domain.parser.parsers
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.AppNotificationParser
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import java.util.regex.Pattern
/**
 * Handles SMS from banking apps (Google Messages, Samsung Messages, etc.)
 * These are forwarded notifications from SMS — needs very careful filtering
 * because messaging apps send ALL messages.
 */
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import javax.inject.Inject
class SmsParser @Inject constructor(
    private val currencyNormalizer: CurrencyNormalizer,
    private val merchantCleaner: MerchantCleaner
) : AppNotificationParser {
    override val supportedPackages = setOf(
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.mms"
    )
    // Known bank SMS sender IDs
    private val BANK_SENDERS = setOf(
        "nbg", "alpha", "eurobank", "piraeus", "winbank",
        "revolut", "paypal", "visa", "mastercard",
        "ethniki", "εθνική", "αλφα", "πειραιώς"
    )
    private val amountPattern by lazy {
        Pattern.compile(
            """(\d+[.,]\d{2})\s*(EUR|€|USD|\$|GBP|£)|(EUR|€|USD|\$|GBP|£)\s*(\d+[.,]\d{2})""",
            Pattern.CASE_INSENSITIVE
        )
    }
    private val TRANSACTION_KEYWORDS = listOf(
        "αγορ", "πληρωμ", "χρέωσ", "συναλλαγ",
        "purchase", "payment", "charged", "debit",
        "agora", "pliromi", "plirwmi", "hreosi", "xreosi", "synallagi"
    )
    override fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        // For SMS, the title is usually the sender
        val sender = title?.lowercase() ?: return null
        val body = listOfNotNull(text, bigText).joinToString(" ")
        val lowerBody = body.lowercase()
        // Only process if sender looks like a bank
        val isBankSms = BANK_SENDERS.any { sender.contains(it) }
        if (!isBankSms) return null
        // Must contain transaction keywords
        val hasKeyword = TRANSACTION_KEYWORDS.any { lowerBody.contains(it) }
        if (!hasKeyword) return null
        // Extract amount
        val matcher = amountPattern.matcher(body)
        if (!matcher.find()) return null
        val amountStr = (matcher.group(1) ?: matcher.group(4))?.replace(",", ".") ?: return null
        val amount = amountStr.toDoubleOrNull() ?: return null
        val currency = currencyNormalizer.normalize(matcher.group(2) ?: matcher.group(3))
        if (amount < 0.10 || amount > 50000) return null
        // Try to extract merchant from the SMS body
        val merchant = extractMerchantFromSms(body)
        return ParsedTransaction(
            amount = amount,
            currency = currency,
            merchant = merchant,
            type = TransactionType.PURCHASE,
            confidence = 0.85f
        )
    }
    private val merchantPatterns by lazy {
        listOf(
            Pattern.compile("""(?:στ[οη]ν?|at|sto|stin?|ston?|se|sta)\s+([A-Za-zΑ-Ωα-ω0-9\s&'.,-]{2,30})""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""-\s+([A-Za-zΑ-Ωα-ω0-9\s&'.,-]{2,30})""", Pattern.CASE_INSENSITIVE)
        )
    }
    private fun extractMerchantFromSms(body: String): String {
        for (p in merchantPatterns) {
            val m = p.matcher(body)
            if (m.find()) {
                val raw = m.group(1) ?: continue
                return merchantCleaner.clean(raw)
            }
        }
        return "Unknown"
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\receipt\BankStatementParser.kt <a name="mainjavacomyournameexpensetrackerdomainreceiptbankstatementparserkt"></a>
```kotlin
package com.yourname.expensetracker.domain.receipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import java.util.regex.Pattern
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class BankStatementParser @Inject constructor(
    private val currencyNormalizer: CurrencyNormalizer,
    private val merchantCleaner: MerchantCleaner
) {
    /**
     * Parse a list of text blocks (with spatial data) into multiple transactions.
     * Groups text into horizontal rows and then extracts data from each row.
     */
    fun parse(blocks: List<TextBlock>): List<ParsedTransaction> {
        if (blocks.isEmpty()) return emptyList()
        // 1. Group blocks into rows based on vertical proximity
        val rows = groupBlocksIntoRows(blocks)
        // 2. Process each row to extract transactions
        return rows.mapNotNull { rowText ->
            extractTransactionFromRow(rowText)
        }
    }
    private fun groupBlocksIntoRows(blocks: List<TextBlock>): List<String> {
        // Sort by top coordinate to process top-to-bottom
        val sortedBlocks = blocks.sortedBy { it.top }
        val rows = mutableListOf<MutableList<TextBlock>>()
        for (block in sortedBlocks) {
            val lastRow = rows.lastOrNull()
            // If block overlaps vertically with the current row, add it to that row
            if (lastRow != null && isSameRow(lastRow.last(), block)) {
                lastRow.add(block)
            } else {
                // Otherwise, it belongs to a new row below
                rows.add(mutableListOf(block))
            }
        }
        // Within each row, sort blocks by left-to-right and join into a single string
        return rows.map { rowBlocks ->
            rowBlocks.sortedBy { it.left }.joinToString(" ") { it.text }
        }
    }
    /**
     * Heuristic to determine if two text blocks belong to the same horizontal row.
     */
    private fun isSameRow(lastBlock: TextBlock, currentBlock: TextBlock): Boolean {
        val lastHeight = lastBlock.bottom - lastBlock.top
        val currentHeight = currentBlock.bottom - currentBlock.top
        val avgHeight = (lastHeight + currentHeight) / 2
        // Use center point comparison with a threshold based on font size (height)
        val lastCenter = (lastBlock.top + lastBlock.bottom) / 2
        val currentCenter = (currentBlock.top + currentBlock.bottom) / 2
        // 60% of average height is a safe overlap threshold for rows
        return kotlin.math.abs(lastCenter - currentCenter) < (avgHeight * 0.6)
    }
    private fun extractTransactionFromRow(rowText: String): ParsedTransaction? {
        // 1. Clean noise
        val cleanRow = rowText.replace('\u00A0', ' ').trim()
        // 2. Look for amount patterns
        // Matches -12.50, 1.250,50, etc. optionally followed by currency
        val amountPattern = Pattern.compile(
            """(-?\b\d+[\s.,]?\d{0,3}[\s.,]\d{2})\s*([€$£]|EUR|USD|GBP)?""",
            Pattern.CASE_INSENSITIVE
        )
        val amountMatcher = amountPattern.matcher(cleanRow)
        if (!amountMatcher.find()) return null
        val amountStr = amountMatcher.group(1)?.replace(" ", "")?.replace(",", ".") ?: return null
        val absAmount = kotlin.math.abs(amountStr.toDoubleOrNull() ?: return null)
        val currency = currencyNormalizer.normalize(amountMatcher.group(2) ?: "EUR")
        // 3. Extract logic for merchant
        // Usually merchant is the text that is NOT the amount and NOT a date/time
        var merchant = cleanRow.replace(amountMatcher.group(0)!!, "")
            .replace(Regex("""\d{1,2}[/.-]\d{1,2}([/.-]\d{2,4})?"""), "") // Date
            .replace(Regex("""\d{2}:\d{2}(:\d{2})?"""), "") // Time
            .replace(Regex("""\s{2,}"""), " ") // Double spaces
            .trim()
        // Basic validation: must have some letters to be a merchant
        if (merchant.isBlank() || !merchant.any { it.isLetter() }) {
            merchant = "Unknown Merchant"
        }
        // Sanity checks: amount shouldn't be zero, merchant shouldn't be too long
        if (absAmount < 0.01) return null
        return ParsedTransaction(
            amount = absAmount,
            currency = currency,
            merchant = merchantCleaner.clean(merchant),
            type = if (amountStr.contains("-")) TransactionType.PURCHASE else TransactionType.DEPOSIT,
            confidence = 0.70f // Base confidence for statement parsing
        )
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\receipt\ReceiptOcrService.kt <a name="mainjavacomyournameexpensetrackerdomainreceiptreceiptocrservicekt"></a>
```kotlin
package com.yourname.expensetracker.domain.receipt
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
data class OcrResult(
    val fullText: String,
    val blocks: List<TextBlock>,
    val savedImagePath: String
)
data class TextBlock(
    val text: String,
    val confidence: Float?,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
)
@Singleton
class ReceiptOcrService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    /**
     * Process an image URI and return OCR results.
     * Also saves a compressed copy of the image for future reference.
     */
    suspend fun processImage(imageUri: Uri): OcrResult {
        // 1. Load and prepare the image (throws if fail)
        val bitmap = loadAndCorrectBitmap(imageUri) ?: throw IllegalStateException("Failed to load and correct image: $imageUri")
        try {
            // 2. Save compressed copy
            val savedPath = saveReceiptImage(bitmap)
            // 3. Run ML Kit OCR
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val visionText = recognizeText(inputImage)
            // 4. Extract blocks
            val blocks = visionText.textBlocks.map { block ->
                TextBlock(
                    text = block.text,
                    confidence = block.lines.firstOrNull()?.confidence,
                    left = block.boundingBox?.left ?: 0,
                    top = block.boundingBox?.top ?: 0,
                    right = block.boundingBox?.right ?: 0,
                    bottom = block.boundingBox?.bottom ?: 0
                )
            }
            return OcrResult(
                fullText = visionText.text,
                blocks = blocks,
                savedImagePath = savedPath
            )
        } finally {
            // CRITICAL: Prevent memory leaks during batch processing
            bitmap.recycle()
        }
    }
    private suspend fun recognizeText(
        image: InputImage
    ): com.google.mlkit.vision.text.Text {
        return kotlinx.coroutines.withTimeout(15000) { // Fix 4.17: 15s timeout
            suspendCancellableCoroutine { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { text ->
                        continuation.resume(text)
                    }
                    .addOnFailureListener { e ->
                        continuation.resumeWithException(e)
                    }
            }
        }
    }
    /**
     * Load bitmap from URI with EXIF rotation correction.
     * Copies to a temp file first to ensure reliable multi-read access.
     */
    private fun loadAndCorrectBitmap(uri: Uri): Bitmap? {
        val tempFile = File(context.cacheDir, "temp_ocr_${System.nanoTime()}.jpg")
        var decodedBitmap: Bitmap? = null
        try {
            // Copy URI to temp file
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Could not open input stream for $uri")
            inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (!tempFile.exists() || tempFile.length() == 0L) {
                throw IllegalStateException("Temp file creation failed or empty for $uri")
            }
            // 1. Get dimensions
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(tempFile.absolutePath, options)
            // Calculate sample size - Optimized: 1024 is plenty for OCR and saves memory/time
            val maxDimension = 1024
            var sampleSize = 1
            if (options.outWidth > 0 && options.outHeight > 0) {
                while (options.outWidth / sampleSize > maxDimension ||
                    options.outHeight / sampleSize > maxDimension
                ) {
                    sampleSize *= 2
                }
            }
            // 2. Decode actual bitmap
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath, decodeOptions)
                ?: throw IllegalStateException("Bitmap decode failed for $uri (Sample: $sampleSize)")
            decodedBitmap = bitmap
            // 3. Apply EXIF rotation
            val exif = ExifInterface(tempFile.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = Matrix()
            var needsRotate = true
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                else -> needsRotate = false
            }
            if (needsRotate) {
                try {
                    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    if (rotated != bitmap) {
                        bitmap.recycle() // Clean up original if rotated
                    }
                    return rotated
                } catch (e: Exception) {
                    bitmap.recycle() // CRITICAL: Recycle original if rotation fails (OOM similar)
                    throw e
                }
            } else {
                return bitmap
            }
        } catch (e: Exception) {
            android.util.Log.e("ReceiptOcrService", "Error loading bitmap from $uri", e)
            if (decodedBitmap?.isRecycled == false) {
                decodedBitmap?.recycle()
            }
            throw IllegalStateException("Failed to load image: ${e.message}", e)
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }
    /**
     * Save a compressed copy of the receipt image
     */
    private fun saveReceiptImage(bitmap: Bitmap): String {
        val receiptsDir = File(context.filesDir, "receipts")
        if (!receiptsDir.exists()) receiptsDir.mkdirs()
        val fileName = "receipt_${System.currentTimeMillis()}.jpg"
        val file = File(receiptsDir, fileName)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        return file.absolutePath
    }
    /**
     * Create a temporary URI for the camera to write to
     */
    fun createTempImageUri(): Uri {
        val cacheDir = File(context.cacheDir, "receipt_images")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val file = File(cacheDir, "camera_${System.currentTimeMillis()}.jpg")
        return androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }
    /**
     * Delete a saved receipt image
     */
    fun deleteImage(path: String) {
        try {
            File(path).delete()
        } catch (_: Exception) {
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\receipt\ReceiptParser.kt <a name="mainjavacomyournameexpensetrackerdomainreceiptreceiptparserkt"></a>
```kotlin
package com.yourname.expensetracker.domain.receipt
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern
import java.text.SimpleDateFormat
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class ReceiptParser @Inject constructor() {
    data class ParsedReceipt(
        val merchantName: String?,
        val total: Double?,
        val subtotal: Double?,
        val tax: Double?,
        val date: Long?,
        val currency: String,
        val lineItems: List<LineItem>,
        val confidence: Float
    )
    data class LineItem(
        val description: String,
        val quantity: Double?,
        val unitPrice: Double?,
        val totalPrice: Double
    )
    // Total amount patterns (Greek + English receipts)
    private val totalPatterns = listOf(
        // Greek patterns with fuzzy space and comma handling
        Pattern.compile(
            """(?:ΣΥΝΟΛΟ|ΤΕΛΙΚΟ|ΠΛΗΡΩΤΕΟ|ΠΟΣΟ|ΑΞΙΑ|ΓΕΝΙΚΟ\s*ΣΥΝΟΛΟ|ΛΟΓΑΡΙΑΣΜΟ[ΣΖ]|TOTAL|AMOUNT)\s*[:\s]*€?\s*(\d+[\s.,]\s*\d{2})""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        ),
        // Amount with currency symbol at end
        Pattern.compile(
            """(?:TOTAL|ΣΥΝΟΛΟ|ΠΟΣΟ)\s*[:\s]*(\d+[\s.,]\s*\d{2})\s*(?:€|EUR)""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        ),
        // Standalone large amount at the very bottom (common for Lidl/Sklavenitis)
        Pattern.compile(
            """(?:€|EUR)\s*(\d+[\s.,]\s*\d{2})\s*$""",
            Pattern.MULTILINE
        )
    )
    // Tax patterns
    private val taxPatterns = listOf(
        Pattern.compile(
            """(?:Φ\.?Π\.?Α\.?|VAT|TAX|TVA)\s*[\d%]*\s*[:\s]*€?\s*(\d+[\s.,]\s*\d{2})""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    )
    // Date patterns
    private val datePatterns = listOf(
        Pattern.compile("""(\d{2})[/\-.](\d{2})[/\-.](\d{4}|\d{2})"""),  // DD/MM/YYYY or DD/MM/YY
        Pattern.compile("""(\d{4})[/\-.](\d{2})[/\-.](\d{2})""")   // YYYY/MM/DD
    )
    // Line item pattern: "description  price" with at least 2 spaces or tab
    private val lineItemPatterns = listOf(
        // "Item description    12.50" (fuzzy spaces in amount)
        Pattern.compile(
            """^(.{3,40}?)\s{2,}(\d+[\s.,]\s*\d{2})\s*€?\s*$""",
            Pattern.MULTILINE
        ),
        // "Quantity x Description   Sum"
        Pattern.compile(
            """^(\d+)\s*x\s*(.{3,40}?)\s{2,}(\d+[\s.,]\s*\d{2})\s*€?\s*$""",
            Pattern.MULTILINE
        )
    )
    // Subtotal patterns (to distinguish from total)
    private val subtotalPatterns = listOf(
        Pattern.compile(
            """(?:SUBTOTAL|SUB\s*TOTAL|ΥΠΟΣΥΝΟΛΟ|ΥΠΟ\s*ΣΥΝΟΛΟ|ΜΕΡΙΚΟ|ΚΑΘΑΡΗ\s*ΑΞΙΑ)\s*[:\s]*€?\s*(\d+[\s.,]\s*\d{2})""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    )
    // Discount patterns
    private val discountPatterns = listOf(
        Pattern.compile(
            """(?:DISCOUNT|ΕΚΠΤΩΣΗ|SAVINGS?|ΜΕΙΟΝ|ΕΚΠΤ)\s*[:\s]*-?\s*€?\s*(\d+[\s.,]\s*\d{2})""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    )
    fun parse(rawText: String): ParsedReceipt {
        // 1. Pre-process text to fix OCR spacing issues and Greek characters
        val cleanedText = normalizeGreekOcr(rawText)
        val lines = cleanedText.lines().filter { it.isNotBlank() }
        // 2. Extract merchant
        val merchant = extractMerchant(lines)
        // 3. Extract date
        val date = extractDate(cleanedText)
        // 4. Extract total
        val total = extractTotal(lines)
        // 5. Extract subtotal (using original text as fallback or new logic if needed)
        val subtotal = extractSubtotal(cleanedText)
        // 6. Extract tax
        val tax = extractTax(cleanedText)
        // 7. Extract line items
        val lineItems = extractLineItems(cleanedText)
        // 8. Cross-validate
        val finalTotal = total ?: lineItems.sumOf { it.totalPrice }.takeIf { it > 0 }
        // 9. Calculate subtotal
        val finalSubtotal = subtotal
            ?: if (finalTotal != null && tax != null) finalTotal - tax else null
        // 10. Confidence
        val confidence = calculateConfidence(merchant, finalTotal, date, lineItems, tax)
        return ParsedReceipt(
            merchantName = merchant,
            total = finalTotal,
            subtotal = finalSubtotal,
            tax = tax,
            date = date,
            currency = detectCurrency(cleanedText),
            lineItems = lineItems,
            confidence = confidence
        )
    }
    /**
     * Normalizes Greek OCR errors and cleans up number formatting.
     */
    private fun normalizeGreekOcr(text: String): String {
        return text.uppercase()
            // --- 1. CRITICAL: Fix Numbers broken by spaces (e.g., "55, 00" -> "55,00") ---
            .replace(Regex("(\\d+)[.,]\\s+(\\d{2})"), "$1.$2") 
            .replace(Regex("(\\d+)\\s+[.,](\\d{2})"), "$1.$2")
            // --- 2. Fix Total Keywords ---
            .replace(Regex(".*[ΠN]O[SZ]O.*AMOUNT.*"), "TOTAL_KEY")
            .replace(Regex(".*[ΠN]O[SZ]O.*"), "TOTAL_KEY")
            .replace(Regex(".*[ΣE2ZXY]YN.*[AΛV][O0Ω].*"), "TOTAL_KEY") // ΣΥΝΟΛΟ variants
            .replace("NAHPQTEO", "TOTAL_KEY")
            .replace("AMOUNT", "TOTAL_KEY")
            .replace("TOTAL", "TOTAL_KEY")
            // --- 3. Fix Dates ---
            .replace(Regex("(\\d{2})-[D0O]-(\\d{2})"), "$1-04-$2") // Fix "16-D4-2017"
            .replace("HM/NIA", "ΗΜΕΡΟΜΗΝΙΑ")
            // --- 4. Currency & Noise Cleaning ---
            .replace("EVP9", "") 
            .replace("EVP", "")
            .replace("EUR", "")
            .replace("€", "")
    }
    // --- MERCHANT EXTRACTION ---
    private fun extractMerchant(lines: List<String>): String? {
        // Skip common non-merchant headers
        val invalidHeaders = listOf(
            "APODEIXI", "AIOAEIEH", "ANOD", "NOMIMH", "ENARXI", "START", 
            "EAPA", "ADDRESS", "THL", "TEL", "AFM", "AOM"
        )
        // Find anchors: Address, Tax ID, Phone
        val headerMarkers = listOf("ΑΦΜ", "AOM", "ΤΗΛ", "THA", "STR.", "ΟΔΟΣ", "TK", "Τ.Κ", "VAT", "TEL")
        for ((index, line) in lines.withIndex()) {
            if (index > 8) break // Merchant is usually in top 8 lines
            // Check if this line is an anchor
            if (headerMarkers.any { line.contains(it) }) {
                // If we found an anchor, the merchant is likely ABOVE it.
                // Scan upwards for the first valid line.
                for (j in index - 1 downTo 0) {
                    val candidate = lines[j]
                    if (isValidMerchantLine(candidate, invalidHeaders)) {
                        return cleanMerchantName(candidate)
                    }
                }
            }
        }
        // Fallback: Just return the first valid line if no anchors found
        for (line in lines.take(5)) {
            if (isValidMerchantLine(line, invalidHeaders)) {
                return cleanMerchantName(line)
            }
        }
        return null
    }
    private fun isValidMerchantLine(line: String, invalidHeaders: List<String>): Boolean {
        if (line.length < 3) return false
        if (line.all { !it.isLetter() }) return false // Must have letters
        if (invalidHeaders.any { line.contains(it) }) return false
        return true
    }
    private fun cleanMerchantName(raw: String): String {
        return raw.replace(Regex("[^a-zA-Zα-ωΑ-Ω0-9\\s&.-]"), "").trim()
    }
    private fun extractTotal(lines: List<String>): Double? {
        // Regex: Matches 12.50, 12,50, 1.250,00
        // Strictly avoids numbers followed by % (VAT rates)
        val amountRegex = Regex("""(\d{1,3}(?:[.,]\d{3})*[.,]\d{2})(?!\s?%)""")
        // --- STRATEGY 1: Explicit "TOTAL" Keyword (Highest Confidence) ---
        // Scan backwards (bottom-up) for the word "TOTAL_KEY"
        val totalLineIndex = lines.indexOfLast { it.contains("TOTAL_KEY") }
        if (totalLineIndex != -1) {
            // Check the exact line
            val amountInLine = extractAmountFromLine(lines[totalLineIndex], amountRegex)
            if (amountInLine != null) return amountInLine
            // Check the NEXT line (common in POS receipts: Label then Value)
            if (totalLineIndex + 1 < lines.size) {
                val amountNext = extractAmountFromLine(lines[totalLineIndex + 1], amountRegex)
                if (amountNext != null) return amountNext
            }
        }
        // --- STRATEGY 2: Fallback (Smart Max) ---
        // If no keyword found, find the LARGEST plausible number.
        var maxAmount = 0.0
        // Only scan the bottom 70% of the receipt (Price is rarely at the top)
        val searchStart = (lines.size * 0.3).toInt() 
        for (i in searchStart until lines.size) {
            val line = lines[i]
            // FILTER: Ignore lines that definitely aren't the total
            if (line.contains("%")) continue // Ignore VAT lines (13,00%)
            if (line.contains("METPHTA") || line.contains("CASH")) continue // Ignore Cash Given (Receipt #18)
            if (line.contains("RESTA") || line.contains("ΡΕΣΤΑ")) continue // Ignore Change
            if (line.contains("KARTA") || line.contains("CARD")) continue // Ignore "Card" references unless parsed carefully
            // Extract numbers from this line
            val matches = amountRegex.findAll(line)
            for (match in matches) {
                val rawVal = match.groupValues[1]
                val amount = parseAmount(rawVal)
                // SANITY CHECKS:
                // 1. Amount must be < 5000 (Avoids phone numbers/Tax IDs misread as price)
                // 2. Amount must not look like a Year (e.g., 2024, 2025)
                // 3. Amount must not look like Time (e.g., 14.24 in Receipt #6)
                if (isValidAmount(amount, line)) {
                    if (amount > maxAmount) {
                        maxAmount = amount
                    }
                }
            }
        }
        return if (maxAmount > 0.0) maxAmount else null
    }
    private fun isValidAmount(amount: Double, line: String): Boolean {
        if (amount > 5000) return false
        if (amount == 0.0) return false
        // Year check: 2020-2030 usually represents date, not price
        if (amount >= 2020 && amount <= 2035 && amount % 1 == 0.0) return false
        // Time check: If line contains "ORA" or matches HH:MM pattern logic
        if (line.contains("QPA") || line.contains("ORA")) return false
        return true
    }
    private fun parseAmount(rawAmount: String): Double {
        // Standardize: "1.250,50" -> "1250.50"
        // Standardize: "12,50" -> "12.50"
        val clean = rawAmount.replace(".", "").replace(",", ".")
        return clean.toDoubleOrNull() ?: 0.0
    }
    private fun extractAmountFromLine(line: String, regex: Regex): Double? {
        // If line has multiple numbers, we generally want the LAST one (Net... VAT... Total)
        val matches = regex.findAll(line)
        return matches.lastOrNull()?.groupValues?.get(1)?.let { parseAmount(it) }
    }
    private fun extractSubtotal(text: String): Double? {
        for (pattern in subtotalPatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                return matcher.group(1)?.replace(",", ".")?.toDoubleOrNull()
            }
        }
        return null
    }
    private fun extractTax(text: String): Double? {
        for (pattern in taxPatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                return matcher.group(1)?.replace(",", ".")?.toDoubleOrNull()
            }
        }
        return null
    }
    // --- DATE EXTRACTION ---
    private fun extractDate(text: String): Long? {
        // Regex handles: dd/MM/yyyy, dd-MM-yyyy, dd.MM.yyyy
        val datePatterns = listOf(
            Regex("""(\d{1,2})\s?[/.-]\s?(\d{1,2})\s?[/.-]\s?(20\d{2})"""),
            Regex("""(\d{1,2})\s?[/.-]\s?(\d{1,2})\s?[/.-]\s?(\d{2})""")
        )
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US)
        sdf.isLenient = false
        for (pattern in datePatterns) {
            pattern.find(text)?.let { match ->
                val (d, m, y) = match.destructured
                val year = if (y.length == 2) "20$y" else y
                // SANITY CHECK: Year must be reasonable (e.g., 2020-2030)
                // Fixes Receipt #8 where OCR read 2058
                val yearInt = year.toIntOrNull() ?: 0
                if (yearInt in 2020..2030) { 
                    try {
                        return sdf.parse("$d/$m/$year")?.time
                    } catch (e: Exception) { }
                }
            }
        }
        return null
    }
    private fun extractLineItems(text: String): List<LineItem> {
        val items = mutableListOf<LineItem>()
        // Skip lines that look like totals/subtotals
        val skipLinePattern = Regex(
            """(?i)(TOTAL|ΣΥΝΟΛΟ|VAT|ΦΠΑ|CHANGE|ΡΕΣΤΑ|CASH|CARD|VISA|MASTER|SUBTOTAL|ΥΠΟΣΥΝΟΛΟ|ΜΕΤΡΗΤΑ|ΚΑΡΤΑ|ΠΛΗΡΩΜΗ|PAYMENT|DISCOUNT|ΕΚΠΤΩΣΗ)"""
        )
        // Pattern 1: "description   amount"
        val matcher1 = lineItemPatterns[0].matcher(text)
        while (matcher1.find()) {
            val desc = matcher1.group(1)?.trim() ?: continue
            val price = matcher1.group(2)?.replace(",", ".")?.toDoubleOrNull() ?: continue
            if (skipLinePattern.containsMatchIn(desc)) continue
            if (price <= 0 || price > 10000) continue
            items.add(
                LineItem(
                    description = desc,
                    quantity = null,
                    unitPrice = null,
                    totalPrice = price
                )
            )
        }
        // Pattern 2: "qty x description   amount"
        val matcher2 = lineItemPatterns[1].matcher(text)
        while (matcher2.find()) {
            val qty = matcher2.group(1)?.toDoubleOrNull() ?: continue
            val desc = matcher2.group(2)?.trim() ?: continue
            val price = matcher2.group(3)?.replace(",", ".")?.toDoubleOrNull() ?: continue
            if (skipLinePattern.containsMatchIn(desc)) continue
            if (price <= 0 || price > 10000) continue
            items.add(
                LineItem(
                    description = desc,
                    quantity = qty,
                    unitPrice = if (qty > 0) price / qty else null,
                    totalPrice = price
                )
            )
        }
        return items
    }
    private fun detectCurrency(text: String): String {
        return when {
            text.contains("€") || text.contains("EUR", ignoreCase = true) ||
                    text.contains("ΕΥΡΩ", ignoreCase = true) -> "EUR"
            text.contains("$") || text.contains("USD", ignoreCase = true) -> "USD"
            text.contains("£") || text.contains("GBP", ignoreCase = true) -> "GBP"
            else -> "EUR"
        }
    }
    private fun calculateConfidence(
        merchant: String?,
        total: Double?,
        date: Long?,
        items: List<LineItem>,
        tax: Double?
    ): Float {
        var score = 0f
        if (merchant != null) score += 0.15f
        if (total != null) score += 0.40f  // Most important
        if (date != null) score += 0.15f
        if (items.isNotEmpty()) score += 0.15f
        if (tax != null) score += 0.05f
        // Bonus: items sum matches total (cross-validation)
        if (total != null && items.isNotEmpty()) {
            val itemsSum = items.sumOf { it.totalPrice }
            val diff = kotlin.math.abs(total - itemsSum)
            if (diff < total * 0.05) { // Within 5%
                score += 0.10f
            }
        }
        return score.coerceIn(0f, 1f)
    }
    // Utility: serialize line items to JSON
    fun lineItemsToJson(items: List<LineItem>): String {
        val jsonArray = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("description", item.description)
                put("totalPrice", item.totalPrice)
                item.quantity?.let { put("quantity", it) }
                item.unitPrice?.let { put("unitPrice", it) }
            }
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }
    // Utility: deserialize line items from JSON
    fun lineItemsFromJson(json: String?): List<LineItem> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val jsonArray = JSONArray(json)
            (0 until jsonArray.length()).map { i ->
                val obj = jsonArray.getJSONObject(i)
                LineItem(
                    description = obj.getString("description"),
                    totalPrice = obj.getDouble("totalPrice"),
                    quantity = if (obj.has("quantity")) obj.getDouble("quantity") else null,
                    unitPrice = if (obj.has("unitPrice")) obj.getDouble("unitPrice") else null
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\util\BKTree.kt <a name="mainjavacomyournameexpensetrackerdomainutilbktreekt"></a>
```kotlin
package com.yourname.expensetracker.domain.util
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
/**
 * BK-Tree (Burkhard-Keller Tree) for efficient fuzzy string searching.
 * 
 * Allows finding all strings within a certain edit distance in O(log n)
 * instead of O(n) for linear search.
 */
class StringBKTree private constructor(
    private val distanceFunction: (String, String) -> Int
) {
    private data class Node(
        val item: String,
        val children: MutableMap<Int, Node> = mutableMapOf()
    )
    private var root: Node? = null
    private var _size = 0
    private val mutex = Mutex()
    val size: Int get() = _size
    val isEmpty: Boolean get() = root == null
    companion object {
        /**
         * Create a BK-Tree using Levenshtein distance.
         */
        fun create(): StringBKTree {
            return StringBKTree { s1, s2 -> 
                StringDistanceUtils.levenshteinDistance(s1, s2) 
            }
        }
    }
    /**
     * Insert an item into the tree.
     */
    suspend fun insert(item: String) = mutex.withLock {
        val normalized = item.lowercase().trim()
        if (root == null) {
            root = Node(normalized)
            _size = 1
            return@withLock
        }
        var current = root!!
        while (true) {
            val dist = distanceFunction(current.item, normalized)
            if (dist == 0) return@withLock // Duplicate
            val child = current.children[dist]
            if (child == null) {
                current.children[dist] = Node(normalized)
                _size++
                return@withLock
            }
            current = child
        }
    }
    /**
     * Insert multiple items.
     */
    suspend fun insertAll(items: Collection<String>) {
        items.forEach { insert(it) }
    }
    /**
     * Find all items within a maximum distance from the query.
     */
    suspend fun search(query: String, maxDistance: Int): List<Pair<String, Int>> = mutex.withLock {
        val results = mutableListOf<Pair<String, Int>>()
        val normalized = query.lowercase().trim()
        searchRecursive(root, normalized, maxDistance, results)
        results.sortedBy { it.second }
    }
    private fun searchRecursive(
        node: Node?,
        query: String,
        maxDistance: Int,
        results: MutableList<Pair<String, Int>>
    ) {
        if (node == null) return
        val dist = distanceFunction(node.item, query)
        if (dist <= maxDistance) {
            results.add(node.item to dist)
        }
        val minDist = maxOf(0, dist - maxDistance)
        val maxDist = dist + maxDistance
        for ((edgeDist, child) in node.children) {
            if (edgeDist in minDist..maxDist) {
                searchRecursive(child, query, maxDistance, results)
            }
        }
    }
    /**
     * Find the single best match within maxDistance.
     */
    suspend fun findBestMatch(query: String, maxDistance: Int): Pair<String, Int>? {
        return search(query, maxDistance).minByOrNull { it.second }
    }
    /**
     * Clear all items.
     */
    suspend fun clear() = mutex.withLock {
        root = null
        _size = 0
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\util\CalendarUtils.kt <a name="mainjavacomyournameexpensetrackerdomainutilcalendarutilskt"></a>
```kotlin
package com.yourname.expensetracker.domain.util
import java.util.Calendar
object CalendarUtils {
    fun getStartOfDay(timestamp: Long = System.currentTimeMillis()): Long {
        return Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    fun getStartOfMonth(timestamp: Long = System.currentTimeMillis()): Long {
        return Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    fun getDaysRemainingInMonth(timestamp: Long = System.currentTimeMillis()): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        return daysInMonth - dayOfMonth
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\util\CurrencyNormalizer.kt <a name="mainjavacomyournameexpensetrackerdomainutilcurrencynormalizerkt"></a>
```kotlin
package com.yourname.expensetracker.domain.util
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
/**
 * centralized utility for normalizing currency strings.
 * Converts symbols (€, $, £) to ISO 4217 codes (EUR, USD, GBP).
 * Defaults to "EUR" if unknown.
 */
@Singleton
class CurrencyNormalizer @Inject constructor() {
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return "EUR"
        val cleaned = raw.trim().uppercase(Locale.getDefault())
        return when (cleaned) {
            "€", "EUR", "EURO" -> "EUR"
            "$", "USD", "DOLLAR" -> "USD"
            "£", "GBP", "POUND" -> "GBP"
            "CHF", "FRANC" -> "CHF"
            "¥", "JPY", "YEN" -> "JPY"
            else -> {
                // If it looks like a valid 3-letter code, keep it, otherwise default
                if (cleaned.length == 3 && cleaned.all { it.isLetter() }) {
                    cleaned
                } else {
                    "EUR"
                }
            }
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\util\MerchantCleaner.kt <a name="mainjavacomyournameexpensetrackerdomainutilmerchantcleanerkt"></a>
```kotlin
package com.yourname.expensetracker.domain.util
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
/**
 * Centralized utility for cleaning and normalizing merchant names.
 * Consolidates logic from various parsers to ensure consistency.
 */
@Singleton
class MerchantCleaner @Inject constructor() {
    private val timeRegex = Regex("""\s\d{1,2}:\d{2}(?::\d{2})?.*$""")
    private val dateRegex = Regex("""\s\d{1,2}[/.-]\d{1,2}(?:[/.-]\d{2,4})?.*$""")
    private val cardInfoRegex = Regex("""\s*(?:(?:Mastercard|Visa|Amex|card|•|·|-)+\s*)+\*?\.?\d+.*$""", RegexOption.IGNORE_CASE)
    private val stopWords = listOf(
        "confirmed", "successful", "completed", "declined", "pending",
        "ολοκληρώθηκε", "επιτυχής", "with card", "με κάρτα", "στις", "at", "on", "to"
    )
    fun clean(raw: String?): String {
        if (raw.isNullOrBlank()) return "Unknown"
        var candidate = raw.trim()
            .replace('\u00A0', ' ') // Non-breaking space
            .replace(timeRegex, "")
            .replace(dateRegex, "")
            .replace(cardInfoRegex, "")
        // Remove stop words from the end
        for (stop in stopWords) {
            val idx = candidate.indexOf(" $stop", ignoreCase = true)
            if (idx != -1) candidate = candidate.substring(0, idx)
            // Check if it's the very start (e.g. "at Starbucks")
            if (candidate.startsWith("$stop ", ignoreCase = true)) {
                candidate = candidate.substring(stop.length + 1)
            }
        }
        return candidate
            .replace(Regex("""\s{2,}"""), " ") // Standardize whitespace
            .replace(Regex("""[.!;]$"""), "") // Remove trailing punctuation
            .trim()
            .take(40)
            .let { if (it.isEmpty()) "Unknown" else it }
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\util\StatisticsUtils.kt <a name="mainjavacomyournameexpensetrackerdomainutilstatisticsutilskt"></a>
```kotlin
package com.yourname.expensetracker.domain.util
import kotlin.math.sqrt
object StatisticsUtils {
    /**
     * Calculates the Sample Standard Deviation from a list of values.
     * Uses Bessel's correction (N-1).
     * Returns 0.0 if there are fewer than 2 values.
     */
    fun calculateStdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val sumSq = values.sumOf { (it - mean) * (it - mean) }
        return sqrt(sumSq / (values.size - 1))
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\util\StringDistanceUtils.kt <a name="mainjavacomyournameexpensetrackerdomainutilstringdistanceutilskt"></a>
```kotlin
package com.yourname.expensetracker.domain.util
/**
 * Utility functions for calculating string distances and similarities.
 */
object StringDistanceUtils {
    /**
     * Calculate Levenshtein distance between two strings.
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length
        val n = s2.length
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)
        for (i in 1..s1.length) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                curr[j] = minOf(
                    minOf(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                )
            }
            val temp = prev
            prev = curr
            curr = temp
        }
        return prev[n]
    }
    /**
     * Calculate Levenshtein similarity (0.0 to 1.0).
     */
    fun levenshteinSimilarity(s1: String, s2: String): Double {
        val dist = levenshteinDistance(s1, s2)
        val maxLen = maxOf(s1.length, s2.length)
        if (maxLen == 0) return 1.0
        return 1.0 - dist.toDouble() / maxLen
    }
    /**
     * Calculate Jaro similarity between two strings.
     */
    fun jaroSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        val matchWindow = maxOf(0, maxOf(s1.length, s2.length) / 2 - 1)
        val s1Matches = BooleanArray(s1.length)
        val s2Matches = BooleanArray(s2.length)
        var matches = 0
        for (i in s1.indices) {
            val start = maxOf(0, i - matchWindow)
            val end = minOf(i + matchWindow + 1, s2.length)
            for (j in start until end) {
                if (!s2Matches[j] && s1[i] == s2[j]) {
                    s1Matches[i] = true
                    s2Matches[j] = true
                    matches++
                    break
                }
            }
        }
        if (matches == 0) return 0.0
        var transpositions = 0.0
        var k = 0
        for (i in s1.indices) {
            if (s1Matches[i]) {
                while (!s2Matches[k]) k++
                if (s1[i] != s2[k]) transpositions++
                k++
            }
        }
        return (matches.toDouble() / s1.length + 
                matches.toDouble() / s2.length + 
                (matches - transpositions / 2.0) / matches) / 3.0
    }
    /**
     * Calculate Jaro-Winkler similarity.
     */
    fun jaroWinklerSimilarity(s1: String, s2: String, prefixWeight: Double = 0.1): Double {
        val jaro = jaroSimilarity(s1, s2)
        if (jaro < 0.7) return jaro
        var prefix = 0
        for (i in 0 until minOf(4, minOf(s1.length, s2.length))) {
            if (s1[i] == s2[i]) prefix++
            else break
        }
        return jaro + prefix * prefixWeight * (1.0 - jaro)
    }
    /**
     * Combined similarity measure.
     */
    fun combinedSimilarity(s1: String, s2: String): Double {
        val jaroWinkler = jaroWinklerSimilarity(s1, s2)
        val levenshtein = levenshteinSimilarity(s1, s2)
        return 0.7 * jaroWinkler + 0.3 * levenshtein
    }
}

```

---

## main\java\com\yourname\expensetracker\receiver\BootReceiver.kt <a name="mainjavacomyournameexpensetrackerreceiverbootreceiverkt"></a>
```kotlin
package com.yourname.expensetracker.receiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.yourname.expensetracker.service.NotificationCaptureService
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // We can't start the service directly from background on Android 8+
            // But we can try to request a rebind if the component is enabled.
            // However, NotificationListenerService is special. The system binds to it.
            // This receiver mainly serves to ensure our process is woken up.
            // On some aggressive OSes, starting a foreground service or just 'being' alive
            // helps the system re-bind the listener.
            // For now, we'll just log/noop, as the critical piece is
            // android:enabled="true" in manifest and user toggle.
            // Extending this: we could schedule a WorkManager job here.
            Log.d("BootReceiver", "Boot completed - Service should be restarted by system or user interaction.")
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\service\NotificationCaptureService.kt <a name="mainjavacomyournameexpensetrackerservicenotificationcaptureservicekt"></a>
```kotlin
package com.yourname.expensetracker.service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.repository.NotificationRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
@AndroidEntryPoint
class NotificationCaptureService : NotificationListenerService() {
    @Inject
    lateinit var repository: NotificationRepository
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    // Thread-safe, bounded deduplication cache
    private val processedNotifications = ConcurrentHashMap<String, Long>()
    private val processCount = java.util.concurrent.atomic.AtomicInteger(0)
    companion object {
        private const val TAG = "NotificationCapture"
        const val ACTION_REFRESH_NOTIFICATIONS = "com.yourname.expensetracker.REFRESH_NOTIFICATIONS"
        private const val FOREGROUND_ID = 1001
        private const val CHANNEL_ID = "expense_tracker_service"
        private const val DEDUP_WINDOW_MS = 5000L
        private const val CACHE_CLEANUP_THRESHOLD = 50
        private const val CACHE_MAX_AGE_MS = 60_000L
        // Packages filtering logic...
        private val MONITORED_PACKAGES = setOf(
            "com.revolut.revolut",
            "com.google.android.apps.walletnfcrel",
            "com.google.android.apps.nbu.paisa.user", // Google Pay (old/new variants)
            "gr.nbg.mobilebanking", // National Bank of Greece
            "com.eurobank.mobile",
            "gr.alpha.mobile",
            "com.winbank.mobile", // Piraeus
            "com.viber.voip",
            "com.google.android.gm", // Gmail
            "com.android.mms", // SMS (generic)
            "com.google.android.apps.messaging", // Google Messages
            "com.samsung.android.messaging" // Samsung Messages
        )
        private val IGNORED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.whatsapp",
            "com.facebook.orca",
            "com.instagram.android",
            "com.snapchat.android",
            "com.google.android.youtube"
        )
        // Heuristic detection patterns
        private val REGEX_CURRENCY = Regex("""[€$£¥]|(EUR|USD|GBP|CHF)""")
        private val REGEX_AMOUNT = Regex("""\d+[.,]\d{2}""")
        private val FINANCIAL_KEYWORDS = setOf(
            "paid", "spent", "purchase", "charged", "payment", "transaction", "amount", 
            "card", "debit", "credit", "bank", "wallet",
            // Greek Keywords (Properly Encoded)
            "πληρωμ",   // πληρωμή
            "αγορ",     // αγορά
            "χρέωσ",    // χρέωση
            "συναλλαγ", // συναλλαγή
            "κάρτα",    // κάρτα
            "μεταφορ"   // μεταφορά
        )
    }
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Expense Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors transactions in background"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        if (intent?.action == ACTION_REFRESH_NOTIFICATIONS) {
            refreshActiveNotifications()
        }
        return super.onStartCommand(intent, flags, startId)
    }
    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "NotificationListener connected! Starting foreground service.")
        startForegroundWithNotification()
    }
    private fun startForegroundWithNotification() {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Expense Tracker Active")
                .setContentText("Monitoring your transactions")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setSilent(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(FOREGROUND_ID, notification, 
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start foreground with type DATA_SYNC, fallback to generic", e)
                    startForeground(FOREGROUND_ID, notification)
                }
            } else {
                startForeground(FOREGROUND_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Failed to start foreground service", e)
        }
    }
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "NotificationListener disconnected - attempting rebind")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(ComponentName(this, NotificationCaptureService::class.java))
        }
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val packageName = sbn.packageName
        // Extract notification data for both filtering and deduplication
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        if (!shouldCapture(packageName, title, text, bigText)) return
        // Better deduplication using notification key + content
        // sbn.key is unique to the notification slot
        // contentHash ensures we catch updates to the same notification if content differs
        val contentHash = (title.orEmpty() + text.orEmpty() + bigText.orEmpty()).hashCode()
        val dedupeKey = "${sbn.key}:$contentHash"
        val now = System.currentTimeMillis()
        val lastProcessed = processedNotifications[dedupeKey]
        if (lastProcessed != null && (now - lastProcessed) < DEDUP_WINDOW_MS) {
            // Already processed this exact content recently
            return
        }
        // Update cache
        processedNotifications[dedupeKey] = now
        cleanupCacheIfNeeded()
        serviceScope.launch {
            processNotification(sbn, packageName, title, text, bigText, extras)
        }
    }
    private fun cleanupCacheIfNeeded() {
        if (processCount.incrementAndGet() >= CACHE_CLEANUP_THRESHOLD) {
            processCount.set(0)
            val now = System.currentTimeMillis()
            processedNotifications.entries.removeIf { 
                now - it.value > CACHE_MAX_AGE_MS 
            }
        }
    }
    private suspend fun processNotification(
        sbn: StatusBarNotification,
        packageName: String,
        title: String?,
        text: String?,
        bigText: String?,
        extras: android.os.Bundle
    ) {
        if (repository.isPackageBlocked(packageName)) {
            Log.d(TAG, "Ignoring blocked package: $packageName")
            return
        }
        // Extract additional useful data for banking apps (sometimes hidden here)
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()
        val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
        // Combine text for robust parsing - some apps put the real info in odd places
        val effectiveBigText = bigText ?: infoText ?: summaryText
        val extrasJson = try {
            buildExtrasJson(extras)
        } catch (e: Exception) {
            "{\"error\": \"${e.message}\"}"
        }
        val appName = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        val rawNotification = RawNotification(
            packageName = packageName,
            appName = appName,
            title = title,
            text = text,
            bigText = effectiveBigText,
            subText = subText,
            extrasJson = extrasJson,
            timestamp = sbn.postTime,
            capturedAt = System.currentTimeMillis()
        )
        try {
            repository.processAndSave(rawNotification)
            Log.d(TAG, "Processed: $packageName | Title: $title")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process notification", e)
        }
    }
    private fun refreshActiveNotifications() {
        Log.d(TAG, "Manual refresh triggered")
        try {
            val activeNotifications = activeNotifications
            activeNotifications.forEach { sbn ->
                onNotificationPosted(sbn)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing active notifications", e)
        }
    }
    private fun shouldCapture(packageName: String, title: String, text: String, bigText: String): Boolean {
        if (IGNORED_PACKAGES.contains(packageName)) return false
        if (MONITORED_PACKAGES.contains(packageName)) return true
        // Discovery Mode: Heuristic check for unmonitored packages
        val content = (title + " " + text + " " + bigText).lowercase()
        // Must contain an amount or currency, PLUS a financial keyword
        val hasAmount = REGEX_CURRENCY.containsMatchIn(content) || REGEX_AMOUNT.containsMatchIn(content)
        if (!hasAmount) return false
        return FINANCIAL_KEYWORDS.any { content.contains(it) }
    }
    private fun buildExtrasJson(extras: android.os.Bundle): String {
        return try {
            val json = org.json.JSONObject()
            val sensitiveKeys = setOf(
                "android.largeIcon", "android.picture", "android.icon",
                "android.wearable.EXTENSIONS", "android.people.list",
                "account_number", "card_number", "card_last_four", "balance"
            )
            for (key in extras.keySet()) {
                if (sensitiveKeys.any { key.equals(it, ignoreCase = true) }) continue
                val value = extras.get(key)
                if (value != null) {
                    val valueStr = value.toString()
                    // Basic sanity: skip extremely large strings that are likely bitmaps
                    if (valueStr.length < 2000) {
                        json.put(key, valueStr)
                    }
                }
            }
            json.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build extras JSON", e)
            "{}"
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        serviceJob.cancel() // Stop all active coroutines
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\MainActivity.kt <a name="mainjavacomyournameexpensetrackeruimainactivitykt"></a>
```kotlin
package com.yourname.expensetracker.ui
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.activity.viewModels
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.ui.screens.analytics.AnalyticsScreen
import com.yourname.expensetracker.ui.screens.budget.BudgetScreen
import com.yourname.expensetracker.ui.screens.home.HomeScreen
import com.yourname.expensetracker.ui.screens.review.ReviewScreen
import com.yourname.expensetracker.ui.screens.transactions.TransactionsScreen
import com.yourname.expensetracker.ui.theme.ExpenseTrackerTheme
import com.yourname.expensetracker.ui.util.HapticType
import com.yourname.expensetracker.ui.util.rememberHapticFeedback
import dagger.hilt.android.AndroidEntryPoint
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            ExpenseTrackerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }
    private fun handleIntent(intent: android.content.Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "expensetracker") {
            when (data.host) {
                "dashboard" -> mainViewModel.navigateToTab(0)
                "activity" -> mainViewModel.navigateToTab(1)
                "review" -> mainViewModel.navigateToTab(2)
                "plan" -> mainViewModel.navigateToTab(3)
                "add" -> mainViewModel.navigateToTab(0)
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }
    val mainViewModel: MainViewModel = hiltViewModel()
    val pendingCount by mainViewModel.pendingReviewCount.collectAsState()
    LaunchedEffect(Unit) {
        mainViewModel.navigationRequest.collect { tabIndex ->
            selectedTab = tabIndex
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != 
                PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    val haptic = rememberHapticFeedback()
    var showAddExpense by remember { mutableStateOf(false) }
    var showScanReceipt by remember { mutableStateOf(false) }
    var showRecurringExpenses by remember { mutableStateOf(false) }
    var isFabExpanded by remember { mutableStateOf(false) }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // ... (rest of bottomBar)
            NavigationBar(
                tonalElevation = 0.dp // Cleaner Bento look
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { 
                        if (selectedTab != 0) haptic(HapticType.Standard)
                        selectedTab = 0 
                    },
                    icon = { Icon(Icons.Rounded.GridView, contentDescription = "Dashboard") },
                    label = { Text("Dashboard") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { 
                        if (selectedTab != 1) haptic(HapticType.Standard)
                        selectedTab = 1 
                    },
                    icon = { Icon(Icons.Rounded.History, contentDescription = "Activity") },
                    label = { Text("Activity") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { 
                        if (selectedTab != 2) haptic(HapticType.Standard)
                        selectedTab = 2 
                    },
                    icon = { 
                        BadgedBox(
                            badge = {
                                if (pendingCount > 0) {
                                    Badge { Text("$pendingCount") }
                                }
                            }
                        ) {
                            Icon(Icons.Rounded.FactCheck, contentDescription = "Review")
                        }
                    },
                    label = { Text("Review") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { 
                        if (selectedTab != 3) haptic(HapticType.Standard)
                        selectedTab = 3 
                    },
                    icon = { Icon(Icons.Rounded.PieChart, contentDescription = "Plan") },
                    label = { Text("Plan") }
                )
            }
        },
        floatingActionButton = {
            val reviewViewModel: com.yourname.expensetracker.ui.screens.review.ReviewViewModel = hiltViewModel()
            SmartFAB(
                selectedTab = selectedTab,
                isExpanded = isFabExpanded,
                onToggleExpand = { isFabExpanded = !isFabExpanded },
                onAddExpense = { 
                    showAddExpense = true 
                    isFabExpanded = false
                },
                onScanReceipt = {
                    showScanReceipt = true
                    isFabExpanded = false
                },
                onApproveAll = { reviewViewModel.approveAll() }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier.padding(padding)
        ) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                },
                label = "TabTransition"
            ) { targetTab ->
                when (targetTab) {
                    0 -> HomeScreen(
                        onNavigateToReview = { selectedTab = 2 },
                        onNavigateToRecurring = { showRecurringExpenses = true }
                    )
                    1 -> TransactionsScreen()
                    2 -> ReviewScreen()
                    3 -> BudgetScreen()
                }
            }
            if (showAddExpense) {
                val clipboardManager = LocalClipboardManager.current
                var initialAmount by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    val text = clipboardManager.getText()?.text ?: ""
                    val regex = Regex("""(\d+[\.,]\d{2})""")
                    val match = regex.find(text)
                    if (match != null) {
                        initialAmount = match.value
                    }
                }
                com.yourname.expensetracker.ui.screens.addexpense.AddExpenseSheet(
                    onDismiss = { showAddExpense = false },
                    initialAmount = initialAmount
                )
            }
            if (showScanReceipt) {
                com.yourname.expensetracker.ui.screens.receiptscan.ReceiptScanScreen(
                    onDismiss = { showScanReceipt = false }
                )
            }
            if (showRecurringExpenses) {
                com.yourname.expensetracker.ui.screens.recurring.RecurringExpensesScreen(
                    onNavigateBack = { showRecurringExpenses = false }
                )
            }
        }
    }
}
@Composable
fun SmartFAB(
    selectedTab: Int,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onAddExpense: () -> Unit,
    onScanReceipt: () -> Unit,
    onApproveAll: () -> Unit
) {
    val haptic = rememberHapticFeedback()
    // Use native ClipboardManager to listen for changes
    val context = LocalContext.current
    val clipboardManager = remember {
        context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    }
    var clipboardAmount by remember { mutableStateOf<String?>(null) }
    // Helper to check clipboard content
    fun checkClipboard() {
        try {
            if (clipboardManager.hasPrimaryClip()) {
                val item = clipboardManager.primaryClip?.getItemAt(0)
                val text = item?.text?.toString() ?: ""
                val regex = Regex("""(\d+[\.,]\d{2})""")
                val match = regex.find(text)
                if (match != null) {
                    clipboardAmount = match.value
                } else {
                    clipboardAmount = null
                }
            } else {
                clipboardAmount = null
            }
        } catch (e: Exception) {
            // Ignore clipboard errors
        }
    }
    // Listen for clipboard changes while this composable is active
    DisposableEffect(clipboardManager) {
        val listener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
            checkClipboard()
        }
        clipboardManager.addPrimaryClipChangedListener(listener)
        // Initial check
        checkClipboard()
        onDispose {
            clipboardManager.removePrimaryClipChangedListener(listener)
        }
    }
    // Also check on resume to handle background changes
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                checkClipboard()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    val (icon, label) = when (selectedTab) {
        2 -> Pair(Icons.Rounded.CheckCircle, "Approve All")
        else -> {
            if (clipboardAmount != null) {
                Pair(Icons.Rounded.ContentPaste, "Add €$clipboardAmount")
            } else {
                Pair(Icons.Rounded.Add, "Add Expense")
            }
        }
    }
    Column(horizontalAlignment = Alignment.End) {
        // Speed Dial Actions
        AnimatedVisibility(
            visible = isExpanded && selectedTab != 2,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                SmallFloatingActionButton(
                    onClick = { 
                        haptic(HapticType.Standard)
                        onScanReceipt() 
                    },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.ReceiptLong, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan Receipt")
                    }
                }
                SmallFloatingActionButton(
                    onClick = { 
                        haptic(HapticType.Standard)
                        onAddExpense() 
                    },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Edit, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add Manual")
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { 
                haptic(HapticType.Heavy)
                if (selectedTab == 2) {
                    onApproveAll()
                } else {
                    onToggleExpand()
                }
            },
            icon = { 
                Icon(
                    if (isExpanded && selectedTab != 2) Icons.Rounded.Close else icon, 
                    contentDescription = label
                ) 
            },
            text = { Text(if (isExpanded && selectedTab != 2) "Close" else label) },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\MainViewModel.kt <a name="mainjavacomyournameexpensetrackeruimainviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.repository.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: NotificationRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : ViewModel() {
    private val _navigationRequest = kotlinx.coroutines.flow.MutableSharedFlow<Int>()
    val navigationRequest = _navigationRequest.asSharedFlow()
    val pendingReviewCount: StateFlow<Int> = repository
        .getPendingReviewCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    fun navigateToTab(tabIndex: Int) {
        viewModelScope.launch {
            _navigationRequest.emit(tabIndex)
        }
    }
    fun isNotificationServiceEnabled(): Boolean {
        val packageName = context.packageName
        val flat = android.provider.Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        return flat != null && flat.contains(packageName)
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\components\BentoCard.kt <a name="mainjavacomyournameexpensetrackeruicomponentsbentocardkt"></a>
```kotlin
package com.yourname.expensetracker.ui.components
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.util.Currency
/**
 * Atomic BentoCard — the building block for the Bento Grid layout.
 * Features: Glassmorphism (semi-transparency + hairline border).
 */
@Composable
fun BentoCard(
    modifier: Modifier = Modifier,
    containerColor: Color = SemanticColors.GlassSurface,
    cornerRadius: Dp = 24.dp, // Modern, rounder look
    contentPadding: PaddingValues = PaddingValues(16.dp),
    border: BorderStroke = BorderStroke(1.dp, SemanticColors.GlassBorder),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick != null) {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(cornerRadius),
            colors = CardDefaults.cardColors(containerColor = containerColor),
            border = border,
            onClick = onClick
        ) {
            Column(
                modifier = Modifier.padding(contentPadding),
                content = content
            )
        }
    } else {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(cornerRadius),
            colors = CardDefaults.cardColors(containerColor = containerColor),
            border = border
        ) {
            Column(
                modifier = Modifier.padding(contentPadding),
                content = content
            )
        }
    }
}
/**
 * Hero BentoCard — larger, primary-colored gradient, for the main metric.
 */
@Composable
fun HeroBentoCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    // Gradient for a more vibrant Hero card
    val heroGradient = remember {
        Brush.linearGradient(
            colors = listOf(
                SemanticColors.PrimaryIndigo.copy(alpha = 0.4f),
                SemanticColors.PrimaryLight.copy(alpha = 0.2f)
            )
        )
    }
    BentoCard(
        modifier = modifier,
        containerColor = Color.Transparent, // Overridden by custom modifier or nested content if needed
        cornerRadius = 28.dp,
        contentPadding = PaddingValues(24.dp),
        border = BorderStroke(1.dp, SemanticColors.PrimaryLight.copy(alpha = 0.2f))
    ) {
        // We use a Surface/Box inside if we want a complex gradient background, 
        // but for now, the BentoCard's containerColor is our base.
        // Let's refine the BentoCard to support custom backgrounds better or just use containerColor.
        content()
    }
}
/**
 * Compact stat label used inside BentoCards.
 */
@Composable
fun StatLabel(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = SemanticColors.TextPrimary
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = SemanticColors.TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum"
            ),
            color = valueColor
        )
    }
}
/**
 * Amount text with tabular figures and premium weights.
 */
@Composable
fun AmountText(
    amount: Double,
    currency: String = Currency.getInstance("EUR").symbol,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.displaySmall,
    color: Color = SemanticColors.TextPrimary
) {
    Text(
        text = "$currency${String.format("%.2f", amount)}",
        style = style.copy(fontFeatureSettings = "tnum"),
        fontWeight = FontWeight.ExtraBold, // More premium weight
        color = color,
        modifier = modifier
    )
}

```

---

## main\java\com\yourname\expensetracker\ui\components\FinancialWeatherCard.kt <a name="mainjavacomyournameexpensetrackeruicomponentsfinancialweathercardkt"></a>
```kotlin
package com.yourname.expensetracker.ui.components
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourname.expensetracker.data.repository.WeatherState
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.UpcomingItem
import com.yourname.expensetracker.domain.model.PlannedExpensePriority
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.EventNote
@Composable
fun FinancialWeatherCard(
    state: WeatherState,
    headline: String,
    summary: String,
    icon: String,
    totalCommitted: Double,
    totalLikely: Double,
    discretionaryBudget: Double,
    pastSpendingPoints: List<Double> = emptyList(),
    projectedSpendingPoints: List<Double> = emptyList(),
    upcomingItems: List<UpcomingItem> = emptyList(),
    details: List<com.yourname.expensetracker.domain.model.NarrativeSection> = emptyList(),
    totalRecurringCount: Int = 0,
    onManageClick: () -> Unit = {},
    onPlanClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val gradient = when (state) {
        WeatherState.CLEAR_SKIES -> Brush.verticalGradient(
            colors = listOf(
                Color(0xFF4CAF50).copy(alpha = 0.1f),
                Color(0xFF4CAF50).copy(alpha = 0.05f)
            )
        )
        WeatherState.PARTLY_CLOUDY -> Brush.verticalGradient(
            colors = listOf(
                Color(0xFF8BC34A).copy(alpha = 0.1f),
                Color(0xFF8BC34A).copy(alpha = 0.05f)
            )
        )
        WeatherState.CLOUDY -> Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFFC107).copy(alpha = 0.1f),
                Color(0xFFFFC107).copy(alpha = 0.05f)
            )
        )
        WeatherState.RAINY -> Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFF9800).copy(alpha = 0.12f),
                Color(0xFFFF9800).copy(alpha = 0.06f)
            )
        )
        WeatherState.STORMY -> Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFF5722).copy(alpha = 0.15f),
                Color(0xFFFF5722).copy(alpha = 0.05f)
            )
        )
        WeatherState.UNKNOWN -> Brush.verticalGradient(
            colors = listOf(
                SemanticColors.GlassSurface,
                SemanticColors.GlassSurface
            )
        )
    }
    val textColor = when (state) {
        WeatherState.CLEAR_SKIES -> SemanticColors.SuccessGreen
        WeatherState.PARTLY_CLOUDY -> Color(0xFF8BC34A)
        WeatherState.CLOUDY -> SemanticColors.WarningOrange
        WeatherState.RAINY -> Color(0xFFFF9800)
        WeatherState.STORMY -> SemanticColors.DangerRed
        WeatherState.UNKNOWN -> SemanticColors.TextSecondary
    }
    BentoCard(
        modifier = modifier.background(gradient, RoundedCornerShape(24.dp)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
        ) {
            // Header Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Weather Icon Circle
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(textColor.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = icon, fontSize = 28.sp)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "FINANCIAL WEATHER",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SemanticColors.TextSecondary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = headline.uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = textColor
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = SemanticColors.TextPrimary,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(24.dp))
            // Forecast Metrics Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ForecastMetric("COMMITTED", totalCommitted, SemanticColors.TextSecondary)
                ForecastMetric("LIKELY", totalLikely, SemanticColors.TextSecondary)
                ForecastMetric("AVAILABLE", discretionaryBudget, textColor)
            }
            Spacer(modifier = Modifier.height(24.dp))
            // Forecast Trajectory Chart (Full Width)
            ForecastTimeline(
                pastPoints = pastSpendingPoints,
                projectedPoints = projectedSpendingPoints,
                budgetLimit = totalCommitted + totalLikely + discretionaryBudget,
                modifier = Modifier.fillMaxWidth()
            )
            if (details.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                OutlinedButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SemanticColors.PrimaryIndigo
                    )
                ) {
                    Text(
                        text = if (expanded) "HIDE BREAKDOWN" else "SEE BREAKDOWN",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                androidx.compose.animation.AnimatedVisibility(visible = expanded) {
                    Column(modifier = Modifier.padding(top = 16.dp)) {
                        details.forEach { section ->
                            DetailSection(section)
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = SemanticColors.GlassBorder, thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))
            // Management Section
            if (upcomingItems.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "UPCOMING (NEXT 30 DAYS)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SemanticColors.TextSecondary,
                        letterSpacing = 0.5.sp
                    )
                    Row {
                        TextButton(
                            onClick = onPlanClick,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "PLAN", 
                                style = MaterialTheme.typography.labelSmall,
                                color = SemanticColors.PrimaryIndigo
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = onManageClick,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Text(
                                "MANAGE ALL", 
                                style = MaterialTheme.typography.labelSmall,
                                color = SemanticColors.PrimaryIndigo
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                upcomingItems.take(3).forEach { item ->
                    UpcomingRow(item)
                    Spacer(modifier = Modifier.height(12.dp))
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$totalRecurringCount RECURRING ITEMS TRACKED",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SemanticColors.TextSecondary,
                        letterSpacing = 0.5.sp
                    )
                    Row {
                        TextButton(
                            onClick = onPlanClick,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "PLAN", 
                                style = MaterialTheme.typography.labelSmall,
                                color = SemanticColors.PrimaryIndigo
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = onManageClick,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Text(
                                "MANAGE RECURRING", 
                                style = MaterialTheme.typography.labelSmall,
                                color = SemanticColors.PrimaryIndigo
                            )
                        }
                    }
                }
            }
        }
    }
}
@Composable
fun ForecastMetric(label: String, amount: Double, color: Color) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = SemanticColors.TextMuted,
            letterSpacing = 0.5.sp
        )
        Text(
            text = "€${String.format(Locale.US, "%.0f", amount)}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}
@Composable
fun UpcomingRow(item: UpcomingItem) {
    val dateFormat = remember { SimpleDateFormat("EEE, MMM d", Locale.getDefault()) }
    val daysUntil = ((item.date - System.currentTimeMillis()) / (1000 * 60 * 60 * 24)).toInt()
    val dateLabel = when {
        daysUntil <= 0 -> "Today"
        daysUntil == 1 -> "Tomorrow"
        else -> dateFormat.format(Date(item.date))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            // Distinction Icon
            val icon = if (item is UpcomingItem.Recurring) Icons.Default.Repeat else Icons.Default.EventNote
            val badgeText = if (item is UpcomingItem.Recurring) {
                item.pattern.frequency.name.lowercase().capitalize()
            } else {
                "Planned"
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(SemanticColors.GlassBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon, 
                    contentDescription = null, 
                    modifier = Modifier.size(16.dp),
                    tint = SemanticColors.TextSecondary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SemanticColors.TextPrimary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dateLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (daysUntil <= 1) SemanticColors.WarningOrange else SemanticColors.TextSecondary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "• $badgeText",
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.TextMuted
                    )
                }
            }
        }
        Text(
            text = "€${String.format(Locale.US, "%.0f", item.amount)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = SemanticColors.TextPrimary
        )
    }
}
@Composable
fun DetailSection(section: com.yourname.expensetracker.domain.model.NarrativeSection) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(SemanticColors.PrimaryIndigo.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(text = section.icon, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = section.title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = SemanticColors.TextSecondary,
                letterSpacing = 0.5.sp
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        section.items.forEach { item ->
            Row(
                modifier = Modifier
                    .padding(start = 36.dp, bottom = 4.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextMuted,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextPrimary,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\components\ForecastTimeline.kt <a name="mainjavacomyournameexpensetrackeruicomponentsforecasttimelinekt"></a>
```kotlin
package com.yourname.expensetracker.ui.components
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.core.chart.line.LineChart
import com.patrykandpatrick.vico.core.entry.FloatEntry
import com.patrykandpatrick.vico.core.entry.ChartEntryModel
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.yourname.expensetracker.ui.theme.SemanticColors
@Composable
fun ForecastTimeline(
    pastPoints: List<Double>,
    projectedPoints: List<Double>,
    budgetLimit: Double,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "FORECAST TRAJECTORY",
            style = MaterialTheme.typography.labelSmall,
            color = SemanticColors.TextMuted,
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (pastPoints.isEmpty() && projectedPoints.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text("No data available", style = MaterialTheme.typography.labelSmall)
            }
            return
        }
        // Vico model creation - Optimized: wrap in remember to avoid allocation spikes
        val chartEntryModel: ChartEntryModel = remember(pastPoints, projectedPoints, budgetLimit) {
            val pastEntries = pastPoints.mapIndexed { index, value -> 
                FloatEntry(index.toFloat(), value.toFloat()) 
            }
            val projectionEntries = projectedPoints.mapIndexed { index, value -> 
                FloatEntry((pastPoints.size + index).toFloat(), value.toFloat()) 
            }
            val budgetLimitEntries = listOf(
                FloatEntry(0f, budgetLimit.toFloat()),
                FloatEntry((pastPoints.size + projectionEntries.size).toFloat(), budgetLimit.toFloat())
            )
            entryModelOf(pastEntries, projectionEntries, budgetLimitEntries)
        }
        val lineSpecs = remember {
            listOf(
                LineChart.LineSpec(
                    lineColor = SemanticColors.PrimaryIndigo.toArgb(),
                ),
                LineChart.LineSpec(
                    lineColor = SemanticColors.PrimaryIndigo.copy(alpha = 0.3f).toArgb(),
                ),
                LineChart.LineSpec(
                    lineColor = SemanticColors.WarningOrange.copy(alpha = 0.5f).toArgb(),
                    lineThicknessDp = 1f
                )
            )
        }
        Chart(
            chart = lineChart(lines = lineSpecs),
            model = chartEntryModel,
            startAxis = rememberStartAxis(),
            bottomAxis = rememberBottomAxis(),
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
             LegendItem("Actual", SemanticColors.PrimaryIndigo)
             Spacer(modifier = Modifier.width(16.dp))
             LegendItem("Projected", SemanticColors.PrimaryIndigo.copy(alpha = 0.3f))
        }
    }
}
@Composable
private fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color)
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = SemanticColors.TextSecondary)
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\components\PulseDot.kt <a name="mainjavacomyournameexpensetrackeruicomponentspulsedotkt"></a>
```kotlin
package com.yourname.expensetracker.ui.components
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.ui.theme.SemanticColors
/**
 * Animated pulse dot that indicates the background service is running.
 */
@Composable
fun PulseDot(
    modifier: Modifier = Modifier,
    color: Color = SemanticColors.SuccessGreen,
    size: Dp = 8.dp,
    isActive: Boolean = true
) {
    if (!isActive) {
        Box(
            modifier = modifier
                .size(size)
                .background(SemanticColors.TextMuted, CircleShape)
        )
        return
    }
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )
    Box(modifier = modifier) {
        // Outer pulse ring
        Box(
            modifier = Modifier
                .size(size)
                .scale(scale)
                .alpha(alpha)
                .background(color, CircleShape)
        )
        // Inner solid dot
        Box(
            modifier = Modifier
                .size(size)
                .background(color, CircleShape)
        )
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\components\SpendingPaceGauge.kt <a name="mainjavacomyournameexpensetrackeruicomponentsspendingpacegaugekt"></a>
```kotlin
package com.yourname.expensetracker.ui.components
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.ui.theme.SemanticColors
@Composable
fun SpendingPaceGauge(
    pace: SpendingPace,
    modifier: Modifier = Modifier
) {
    val paceColor = when (pace.paceStatus) {
        PaceStatus.UNDER_PACE -> SemanticColors.SuccessGreen
        PaceStatus.ON_PACE -> SemanticColors.PrimaryIndigo
        PaceStatus.OVER_PACE -> SemanticColors.WarningOrange
        PaceStatus.NO_BASELINE -> SemanticColors.TextMuted
    }
    // Animate the sweep angle (240 degree range)
    val maxPacePercent = 200f
    val targetSweep = (pace.pacePercentage / maxPacePercent).coerceIn(0f, 1f) * 240f
    val animatedSweep by animateFloatAsState(
        targetValue = targetSweep,
        animationSpec = tween(800), // More responsive
        label = "pace_sweep_${pace.paceStatus}"
    )
    val statusLabel = when (pace.paceStatus) {
        PaceStatus.UNDER_PACE -> "Under pace"
        PaceStatus.ON_PACE -> "On track"
        PaceStatus.OVER_PACE -> "Over pace"
        PaceStatus.NO_BASELINE -> "Calculating..."
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(130.dp), // Slightly larger
            contentAlignment = Alignment.Center
        ) {
            val trackColor = SemanticColors.SurfaceLight.copy(alpha = 0.5f)
            Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                val strokeWidth = 10.dp.toPx()
                val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
                // Background arc
                drawArc(
                    color = trackColor,
                    startAngle = 150f,
                    sweepAngle = 240f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                // Foreground arc (Current Pace)
                drawArc(
                    color = paceColor,
                    startAngle = 150f,
                    sweepAngle = animatedSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
            // Center metric
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${pace.pacePercentage.toInt()}%",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = SemanticColors.TextPrimary
                )
                Text(
                    text = "Day ${pace.daysElapsed}/${pace.daysInMonth}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SemanticColors.TextSecondary
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            color = paceColor.copy(alpha = 0.15f),
            shape = CircleShape
        ) {
            Text(
                text = statusLabel.uppercase(),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = paceColor,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\components\SpendingTrendChart.kt <a name="mainjavacomyournameexpensetrackeruicomponentsspendingtrendchartkt"></a>
```kotlin
package com.yourname.expensetracker.ui.components
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.patrykandpatrick.vico.core.entry.entryOf
import com.yourname.expensetracker.ui.theme.SemanticColors
@Composable
fun SpendingTrendChart(
    currentMonthData: List<Float>,
    previousMonthData: List<Float>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "TREND", 
                    style = MaterialTheme.typography.labelSmall, 
                    fontWeight = FontWeight.Bold,
                    color = SemanticColors.TextSecondary,
                    letterSpacing = 1.sp
                )
                Text(
                    "This month vs Last", 
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextMuted
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (currentMonthData.isEmpty() && previousMonthData.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("No data", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            val chartEntryModel = remember(currentMonthData, previousMonthData) {
                entryModelOf(
                    currentMonthData.mapIndexed { index, value -> entryOf(index, value) }, 
                    previousMonthData.mapIndexed { index, value -> entryOf(index, value) }
                )
            }
            Chart(
                chart = lineChart(
                    lines = listOf(
                        com.patrykandpatrick.vico.compose.chart.line.lineSpec(lineColor = SemanticColors.PrimaryIndigo),
                        com.patrykandpatrick.vico.compose.chart.line.lineSpec(lineColor = SemanticColors.TextMuted.copy(alpha = 0.5f))
                    )
                ),
                model = chartEntryModel,
                startAxis = rememberStartAxis(
                    label = null,
                    tick = null,
                    guideline = null,
                    axis = null
                ),
                bottomAxis = rememberBottomAxis(
                    label = null,
                    tick = null,
                    guideline = null,
                    axis = null
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            )
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\addexpense\AddExpenseSheet.kt <a name="mainjavacomyournameexpensetrackeruiscreensaddexpenseaddexpensesheetkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.addexpense
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.dao.MerchantSuggestion
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId
import java.util.*
import java.util.Currency
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseSheet(
    onDismiss: () -> Unit,
    initialAmount: String? = null,
    initialMerchant: String? = null,
    viewModel: AddExpenseViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    // Handle save result
    LaunchedEffect(state.saveResult) {
        when (state.saveResult) {
            is SaveResult.Success -> {
                viewModel.reset()
                onDismiss()
            }
            else -> { /* handled in UI */ }
        }
    }
    // Set initial values once
    LaunchedEffect(Unit) {
        if (initialAmount != null || initialMerchant != null) {
            viewModel.setInitialValues(initialAmount, initialMerchant)
        }
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top bar
            TopAppBar(
                title = { Text(stringResource(com.yourname.expensetracker.R.string.add_expense_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.reset()
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(com.yourname.expensetracker.R.string.close_content_description))
                    }
                },
                actions = {
                    Button(
                        onClick = { viewModel.save() },
                        enabled = !state.isSaving,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(stringResource(com.yourname.expensetracker.R.string.save_button))
                        }
                    }
                }
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                // === Merchant Field with Autocomplete ===
                MerchantFieldWithSuggestions(
                    merchant = state.merchant,
                    onMerchantChange = { viewModel.updateMerchant(it) },
                    suggestions = state.suggestions,
                    showSuggestions = state.showSuggestions,
                    onSuggestionSelected = { viewModel.selectSuggestion(it) },
                    onDismissSuggestions = { viewModel.dismissSuggestions() },
                    error = state.merchantError,
                    categories = categories,
                    onNextFocus = { focusManager.moveFocus(FocusDirection.Down) }
                )
                // === Amount Field ===
                OutlinedTextField(
                    value = state.amount,
                    onValueChange = { viewModel.updateAmount(it) },
                    label = { Text(stringResource(com.yourname.expensetracker.R.string.amount_label)) },
                    placeholder = { Text(stringResource(com.yourname.expensetracker.R.string.amount_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() }
                    ),
                    isError = state.amountError != null,
                    supportingText = state.amountError?.let { { Text(it) } },
                    leadingIcon = { Text(Currency.getInstance("EUR").symbol, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                    modifier = Modifier.fillMaxWidth()
                )
                // === Payment Method ===
                Text(
                    stringResource(com.yourname.expensetracker.R.string.payment_method_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PaymentMethodChip(
                        label = stringResource(com.yourname.expensetracker.R.string.payment_method_card),
                        selected = state.paymentMethod == PaymentMethod.CARD,
                        onClick = { viewModel.selectPaymentMethod(PaymentMethod.CARD) },
                        modifier = Modifier.weight(1f)
                    )
                    PaymentMethodChip(
                        label = stringResource(com.yourname.expensetracker.R.string.payment_method_cash),
                        selected = state.paymentMethod == PaymentMethod.CASH,
                        onClick = { viewModel.selectPaymentMethod(PaymentMethod.CASH) },
                        modifier = Modifier.weight(1f)
                    )
                    PaymentMethodChip(
                        label = stringResource(com.yourname.expensetracker.R.string.payment_method_transfer),
                        selected = state.paymentMethod == PaymentMethod.BANK_TRANSFER,
                        onClick = { viewModel.selectPaymentMethod(PaymentMethod.BANK_TRANSFER) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // === Category Selector ===
                Text(
                    stringResource(com.yourname.expensetracker.R.string.category_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                CategoryGrid(
                    categories = categories,
                    selectedId = state.selectedCategoryId,
                    onSelect = { viewModel.selectCategory(it) }
                )
                // === Date Picker ===
                DateSelector(
                    dateMs = state.date,
                    onDateSelected = { viewModel.updateDate(it) }
                )
                // === Transaction Type (collapsible) ===
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleTransactionType() },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(com.yourname.expensetracker.R.string.transaction_type_prefix, state.transactionType.name.lowercase()
                            .replaceFirstChar { it.uppercase() }),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        if (state.showTransactionType) Icons.Default.KeyboardArrowUp
                        else Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(com.yourname.expensetracker.R.string.toggle_content_description)
                    )
                }
                AnimatedVisibility(visible = state.showTransactionType) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TransactionType.values().filter { it != TransactionType.UNKNOWN }.forEach { type ->
                            FilterChip(
                                selected = state.transactionType == type,
                                onClick = { viewModel.selectTransactionType(type) },
                                label = {
                                    Text(
                                        type.name.lowercase().replaceFirstChar { it.uppercase() },
                                        fontSize = 12.sp
                                    )
                                }
                            )
                        }
                    }
                }
                // === Notes (collapsible) ===
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleNotes() },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(com.yourname.expensetracker.R.string.notes_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        if (state.showNotes) Icons.Default.KeyboardArrowUp
                        else Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(com.yourname.expensetracker.R.string.toggle_content_description)
                    )
                }
                AnimatedVisibility(visible = state.showNotes) {
                    OutlinedTextField(
                        value = state.notes,
                        onValueChange = { viewModel.updateNotes(it) },
                        label = { Text(stringResource(com.yourname.expensetracker.R.string.notes_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )
                }
                // === Recurring Options ===
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(com.yourname.expensetracker.R.string.repeat_transaction_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Switch(
                        checked = state.isRecurring,
                        onCheckedChange = { viewModel.toggleRecurring() }
                    )
                }
                AnimatedVisibility(visible = state.isRecurring) {
                    Column {
                        Text(
                            stringResource(com.yourname.expensetracker.R.string.frequency_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // Simple horizontal scroll for frequencies
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            com.yourname.expensetracker.domain.model.RecurrenceFrequency.values()
                                .filter { it != com.yourname.expensetracker.domain.model.RecurrenceFrequency.IRREGULAR }
                                .forEach { freq ->
                                    FilterChip(
                                        selected = state.recurrenceFrequency == freq,
                                        onClick = { viewModel.setRecurrenceFrequency(freq) },
                                        label = { 
                                            Text(freq.name.lowercase().replaceFirstChar { it.uppercase() }) 
                                        }
                                    )
                                }
                        }
                    }
                }
                // === Error Messages ===
                when (val result = state.saveResult) {
                    is SaveResult.Duplicate -> {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(com.yourname.expensetracker.R.string.error_duplicate_transaction),
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    is SaveResult.Error -> {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "❌ ${result.message}",
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    else -> {}
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}
@Composable
fun MerchantFieldWithSuggestions(
    merchant: String,
    onMerchantChange: (String) -> Unit,
    suggestions: List<MerchantSuggestion>,
    showSuggestions: Boolean,
    onSuggestionSelected: (MerchantSuggestion) -> Unit,
    onDismissSuggestions: () -> Unit,
    error: String?,
    categories: List<Category>,
    onNextFocus: () -> Unit
) {
    val categoryMap = remember(categories) { categories.associateBy { it.id } }
    Column {
        OutlinedTextField(
            value = merchant,
            onValueChange = onMerchantChange,
            label = { Text(stringResource(com.yourname.expensetracker.R.string.merchant_label)) },
            placeholder = { Text(stringResource(com.yourname.expensetracker.R.string.merchant_placeholder)) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onNextFocus() }),
            modifier = Modifier.fillMaxWidth()
        )
        // Suggestions dropdown
        AnimatedVisibility(visible = showSuggestions && suggestions.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column {
                    suggestions.forEach { suggestion ->
                        val category = suggestion.categoryId?.let { categoryMap[it] }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSuggestionSelected(suggestion) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Category icon
                                if (category != null) {
                                    val catColor = remember(category.color) {
                                        try {
                                            Color(android.graphics.Color.parseColor(category.color))
                                        } catch (e: Exception) {
                                            Color.Gray
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .background(catColor, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(category.icon, fontSize = 16.sp)
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        suggestion.merchant,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        buildString {
                                            if (category != null) append(category.name)
                                            if (suggestion.txCount > 0) {
                                                if (isNotEmpty()) append(" · ")
                                                append(stringResource(com.yourname.expensetracker.R.string.visits_suffix_format, suggestion.txCount))
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    stringResource(com.yourname.expensetracker.R.string.avg_amount_format, suggestion.avgAmount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        if (suggestion != suggestions.last()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }
}
@Composable
fun PaymentMethodChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (selected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp,
                color = if (selected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
@Composable
fun CategoryGrid(
    categories: List<Category>,
    selectedId: Long?,
    onSelect: (Long) -> Unit
) {
    // Wrapping flow layout using multiple rows
    val chunked = remember(categories) { categories.chunked(4) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chunked.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                row.forEach { category ->
                    val isSelected = selectedId == category.id
                    val catColor = remember(category.color) {
                        try {
                            Color(android.graphics.Color.parseColor(category.color))
                        } catch (e: Exception) {
                            Color.Gray
                        }
                    }
                    Surface(
                        onClick = { onSelect(category.id) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) catColor.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        border = if (isSelected) androidx.compose.foundation.BorderStroke(
                            2.dp, catColor
                        ) else null
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(category.icon, fontSize = 20.sp)
                            Text(
                                category.name,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                // Fill remaining space in last row
                repeat(4 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateSelector(
    dateMs: Long,
    onDateSelected: (Long) -> Unit
) {
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEE, dd MMM yyyy, HH:mm", Locale.getDefault()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = dateMs
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDatePicker = true },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.DateRange,
            contentDescription = stringResource(com.yourname.expensetracker.R.string.date_label),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                stringResource(com.yourname.expensetracker.R.string.date_label),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                dateFormat.format(Instant.ofEpochMilli(dateMs).atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selectedDate ->
                            // Preserve time of day, just change the date
                            val calOld = Calendar.getInstance().apply { timeInMillis = dateMs }
                            val calNew = Calendar.getInstance().apply { timeInMillis = selectedDate }
                            calNew.set(Calendar.HOUR_OF_DAY, calOld.get(Calendar.HOUR_OF_DAY))
                            calNew.set(Calendar.MINUTE, calOld.get(Calendar.MINUTE))
                            calNew.set(Calendar.SECOND, calOld.get(Calendar.SECOND))
                            onDateSelected(calNew.timeInMillis)
                        }
                        showDatePicker = false
                    }
                ) {
                    Text(stringResource(com.yourname.expensetracker.R.string.ok_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(com.yourname.expensetracker.R.string.cancel_button))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\addexpense\AddExpenseViewModel.kt <a name="mainjavacomyournameexpensetrackeruiscreensaddexpenseaddexpenseviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.addexpense
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.dao.MerchantSuggestion
import com.yourname.expensetracker.data.database.dao.RecurringExpenseDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.isActive
data class AddExpenseState(
    val merchant: String = "",
    val amount: String = "",
    val selectedCategoryId: Long? = null,
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,
    val transactionType: TransactionType = TransactionType.PURCHASE,
    val date: Long = System.currentTimeMillis(),
    val notes: String = "",
    val showNotes: Boolean = false,
    val showTransactionType: Boolean = false,
    val isRecurring: Boolean = false,
    val recurrenceFrequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
    val suggestions: List<MerchantSuggestion> = emptyList(),
    val showSuggestions: Boolean = false,
    val isSaving: Boolean = false,
    val saveResult: SaveResult? = null,
    val merchantError: String? = null,
    val amountError: String? = null
)
sealed class SaveResult {
    object Success : SaveResult()
    object Duplicate : SaveResult()
    data class Error(val message: String) : SaveResult()
}
@HiltViewModel
class AddExpenseViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository,
    private val recurringExpenseDao: RecurringExpenseDao
) : ViewModel() {
    private val _state = MutableStateFlow(AddExpenseState())
    val state: StateFlow<AddExpenseState> = _state.asStateFlow()
    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private var searchJob: Job? = null
    fun updateMerchant(value: String) {
        val sanitized = value.take(100) // Max 100 chars
        _state.update {
            it.copy(
                merchant = sanitized,
                merchantError = null,
                saveResult = null
            )
        }
        // Debounced search
        searchJob?.cancel()
        if (sanitized.length >= 2) {
            searchJob = viewModelScope.launch {
                delay(300)
                if (!isActive) return@launch
                val suggestions = repository.searchMerchants(sanitized)
                if (!isActive) return@launch
                _state.update {
                    it.copy(
                        suggestions = suggestions,
                        showSuggestions = suggestions.isNotEmpty()
                    )
                }
            }
        } else {
            _state.update { it.copy(suggestions = emptyList(), showSuggestions = false) }
        }
    }
    fun selectSuggestion(suggestion: MerchantSuggestion) {
        _state.update {
            it.copy(
                merchant = suggestion.merchant,
                selectedCategoryId = suggestion.categoryId ?: it.selectedCategoryId,
                amount = if (it.amount.isBlank()) String.format("%.2f", suggestion.avgAmount) else it.amount,
                suggestions = emptyList(),
                showSuggestions = false,
                merchantError = null
            )
        }
    }
    fun dismissSuggestions() {
        _state.update { it.copy(showSuggestions = false) }
    }
    fun updateAmount(value: String) {
        // Only allow valid decimal input
        val filtered = value.filter { it.isDigit() || it == '.' || it == ',' }
        _state.update {
            it.copy(
                amount = filtered,
                amountError = null,
                saveResult = null
            )
        }
    }
    fun selectCategory(categoryId: Long) {
        _state.update { it.copy(selectedCategoryId = categoryId) }
    }
    fun selectPaymentMethod(method: PaymentMethod) {
        _state.update { it.copy(paymentMethod = method) }
    }
    fun selectTransactionType(type: TransactionType) {
        _state.update { it.copy(transactionType = type) }
    }
    fun updateDate(dateMs: Long) {
        _state.update { it.copy(date = dateMs) }
    }
    fun updateNotes(value: String) {
        _state.update { it.copy(notes = value.take(500)) } // Max 500 chars
    }
    fun toggleNotes() {
        _state.update { it.copy(showNotes = !it.showNotes) }
    }
    fun toggleTransactionType() {
        _state.update { it.copy(showTransactionType = !it.showTransactionType) }
    }
    fun toggleRecurring() {
        _state.update { it.copy(isRecurring = !it.isRecurring) }
    }
    fun setRecurrenceFrequency(frequency: RecurrenceFrequency) {
        _state.update { it.copy(recurrenceFrequency = frequency) }
    }
    fun save() {
        val currentState = _state.value
        // Validate
        val merchantTrimmed = currentState.merchant.trim()
        if (merchantTrimmed.isBlank()) {
            _state.update { it.copy(merchantError = "Merchant name is required") }
            return
        }
        val amountStr = currentState.amount.replace(",", ".")
        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            _state.update { it.copy(amountError = "Enter a valid amount") }
            return
        }
        if (amount > 1_000_000) { // Reasonable upper limit
            _state.update { it.copy(amountError = "Amount is too large") }
            return
        }
        // Normalize to 2 decimal places
        val normalizedAmount = java.math.BigDecimal(amount)
            .setScale(2, java.math.RoundingMode.HALF_UP)
            .toDouble()
        _state.update { it.copy(isSaving = true, saveResult = null) }
        viewModelScope.launch {
            try {
                // 1. Save the actual transaction
                val result = repository.addManualExpense(
                    merchant = merchantTrimmed,
                    amount = normalizedAmount,
                    currency = "EUR",
                    categoryId = currentState.selectedCategoryId,
                    transactionType = currentState.transactionType,
                    paymentMethod = currentState.paymentMethod,
                    date = currentState.date,
                    notes = currentState.notes.takeIf { it.isNotBlank() }
                )
                if (result == -1L) {
                    _state.update {
                        it.copy(isSaving = false, saveResult = SaveResult.Duplicate)
                    }
                } else {
                    // 2. If recurring, save the rule
                    if (currentState.isRecurring) {
                        saveRecurringRule(merchantTrimmed, normalizedAmount, currentState.recurrenceFrequency, currentState.date)
                    }
                    _state.update {
                        it.copy(isSaving = false, saveResult = SaveResult.Success)
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isSaving = false,
                        saveResult = SaveResult.Error(e.message ?: "Unknown error")
                    )
                }
            }
        }
    }
    private suspend fun saveRecurringRule(
        merchant: String, 
        amount: Double, 
        frequency: RecurrenceFrequency, 
        lastDate: Long
    ) {
        // Calculate next date based on frequency using java.time for accuracy (DST/Leap years)
        val lastLocalDate = java.time.Instant.ofEpochMilli(lastDate)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
        val nextLocalDate = when (frequency) {
            RecurrenceFrequency.WEEKLY -> lastLocalDate.plusWeeks(1)
            RecurrenceFrequency.BIWEEKLY -> lastLocalDate.plusWeeks(2)
            RecurrenceFrequency.MONTHLY -> lastLocalDate.plusMonths(1)
            RecurrenceFrequency.QUARTERLY -> lastLocalDate.plusMonths(3)
            RecurrenceFrequency.SEMI_ANNUALLY -> lastLocalDate.plusMonths(6)
            RecurrenceFrequency.ANNUALLY -> lastLocalDate.plusYears(1)
            RecurrenceFrequency.IRREGULAR -> lastLocalDate // Should not happen for recurring rule
            else -> lastLocalDate.plusDays(frequency.days.toLong()) // Fallback
        }
        val nextDate = nextLocalDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val rule = ManualRecurringExpense(
            merchant = merchant,
            amount = amount,
            currency = "EUR",
            frequency = frequency,
            nextDate = nextDate,
            note = "Created from manual entry"
        )
        recurringExpenseDao.insert(rule)
    }
    fun reset() {
        _state.value = AddExpenseState()
    }
    fun setInitialValues(amount: String? = null, merchant: String? = null) {
        _state.update { 
            it.copy(
                amount = amount ?: it.amount,
                merchant = merchant ?: it.merchant
            )
        }
    }
    fun clearSaveResult() {
        _state.update { it.copy(saveResult = null) }
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\analytics\AnalyticsScreen.kt <a name="mainjavacomyournameexpensetrackeruiscreensanalyticsanalyticsscreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.analytics
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.column.columnChart
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.analytics.*
import com.yourname.expensetracker.ui.components.*
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.util.Locale
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    viewModel: AnalyticsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Financial Insights", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 1. Period Selector (Top Level)
                item { PeriodSelector(state.selectedPeriod) { viewModel.selectPeriod(it) } }
                // 2. Main Hero Bento: Total Spent + Change
                item { TotalSpentHero(state) }
                // 3. AI Insights (Natural Language)
                if (state.insights.isNotEmpty()) {
                    item { NaturalLanguageInsightBento(state.insights.first()) }
                }
                // 4. Daily Spending Chart
                item { SpendingChartBento(state) }
                // 5. Category Breakdown
                if (state.categoryBreakdown.isNotEmpty()) {
                    item { SectionHeader("Breakdown by Category") }
                    items(state.categoryBreakdown) { CategoryItem(it) }
                }
                // 6. Deep Insights Carousel
                if (state.insights.size > 1) {
                    item { SectionHeader("Deep Insights") }
                    item { InsightsRow(state.insights.drop(1)) }
                }
                // 7. Merchant Breakdown
                if (state.merchantBreakdown.isNotEmpty()) {
                    item { SectionHeader("Top Merchants") }
                    items(state.merchantBreakdown.take(8)) { MerchantItem(it) }
                }
                // 8. Recurring
                if (state.recurring.isNotEmpty()) {
                    item { SectionHeader("Subscription Detection") }
                    items(state.recurring) { RecurringItem(it) }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}
@Composable
fun TotalSpentHero(state: AnalyticsState) {
    HeroBentoCard {
        Column {
            Text(
                text = "${state.selectedPeriod.name} Total",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            AmountText(
                amount = state.currentTotal,
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            state.changePercent?.let { change ->
                val isIncrease = change > 0
                val color = if (isIncrease) SemanticColors.DangerRed else SemanticColors.SuccessGreen
                val icon = if (isIncrease) "📈" else "📉"
                Surface(
                    color = Color.White.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(icon, fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${if (change > 0) "+" else ""}${String.format("%.1f", change)}% vs last period",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "${state.transactionCount} transactions recorded in this period.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
            )
        }
    }
}
@Composable
fun NaturalLanguageInsightBento(insight: SpendingInsight) {
    BentoCard(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.White.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(insight.icon, fontSize = 24.sp)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = insight.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = insight.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                    lineHeight = 20.sp
                )
            }
        }
    }
}
@Composable
fun SpendingChartBento(state: AnalyticsState) {
    BentoCard {
        Column {
            Text(
                "Spending Distribution",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            if (state.dailyTotals.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    Text("Insufficient data for visualization", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                // Optimized: convert map to entries once and remember
                val chartEntryModel = remember(state.dailyTotals) {
                    val entries = state.dailyTotals.values.map { it.toFloat() }
                    entryModelOf(*entries.toTypedArray())
                }
                Chart(
                    chart = columnChart(),
                    model = chartEntryModel,
                    startAxis = rememberStartAxis(),
                    bottomAxis = rememberBottomAxis(),
                    modifier = Modifier.fillMaxWidth().height(180.dp)
                )
            }
        }
    }
}
@Composable
fun PeriodSelector(selected: TimePeriod, onSelect: (TimePeriod) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(TimePeriod.values()) { period ->
            FilterChip(
                selected = selected == period,
                onClick = { onSelect(period) },
                label = { Text(period.name.lowercase().capitalize()) },
                shape = RoundedCornerShape(20.dp)
            )
        }
    }
}
@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}
@Composable
fun InsightsRow(insights: List<SpendingInsight>) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 4.dp)
    ) {
        items(insights) { insight ->
            Card(
                modifier = Modifier.width(260.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(insight.icon, fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            insight.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        insight.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
@Composable
fun CategoryItem(item: CategoryBreakdown) {
    val categoryColor = remember(item.category.color) {
        try { Color(android.graphics.Color.parseColor(item.category.color)) } 
        catch (e: Exception) { Color.Gray }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(categoryColor.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(item.category.icon, fontSize = 20.sp)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.category.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("€${String.format("%.2f", item.total)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { item.percentage / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                color = categoryColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${item.percentage.toInt()}% of total spending",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}
@Composable
fun MerchantItem(item: MerchantBreakdown) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(item.name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text("${item.transactionCount} visits", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("€${String.format("%.2f", item.totalSpent)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
    }
}
@Composable
fun RecurringItem(item: RecurringCandidate) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("🔄", fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.merchant, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text("Estimated every ${item.intervalDays} days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("€${String.format("%.2f", item.amount)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(item.confidence.let { if (it > 0.8) "High confidence" else "Plausible" }, style = MaterialTheme.typography.labelSmall, color = if (item.confidence > 0.8) SemanticColors.SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
// Extension to help with capitalizing names
fun String.capitalize() = this.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

```

---

## main\java\com\yourname\expensetracker\ui\screens\analytics\AnalyticsViewModel.kt <a name="mainjavacomyournameexpensetrackeruiscreensanalyticsanalyticsviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.analytics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.domain.analytics.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject
data class AnalyticsState(
    val selectedPeriod: TimePeriod = TimePeriod.MONTH,
    val currentTotal: Double = 0.0,
    val previousTotal: Double? = null,
    val changePercent: Float? = null,
    val transactionCount: Int = 0,
    val categoryBreakdown: List<CategoryBreakdown> = emptyList(),
    val merchantBreakdown: List<MerchantBreakdown> = emptyList(),
    val dailyTotals: Map<String, Double> = emptyMap(),
    val insights: List<SpendingInsight> = emptyList(),
    val recurring: List<RecurringCandidate> = emptyList(),
    val isLoading: Boolean = true
)
@OptIn(kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository,
    private val insightsEngine: InsightsEngine
) : ViewModel() {
    private val _state = MutableStateFlow(AnalyticsState())
    val state: StateFlow<AnalyticsState> = _state.asStateFlow()
    private val _selectedPeriod = MutableStateFlow(TimePeriod.MONTH)
    init {
        viewModelScope.launch {
            combine(
                repository.getAllExpenses(),
                categoryRepository.allCategories,
                _selectedPeriod
            ) { expenses, categories, period ->
                Triple(expenses, categories, period)
            }
            .debounce(300)
            .flowOn(Dispatchers.Default)
            .collectLatest { (expenses, categories, period) ->
                _state.update { it.copy(isLoading = true, selectedPeriod = period) }
                computeAnalytics(expenses, categories, period)
            }
        }
    }
    fun selectPeriod(period: TimePeriod) {
        _selectedPeriod.value = period
    }
    private suspend fun computeAnalytics(
        allExpenses: List<Expense>,
        categories: List<Category>,
        period: TimePeriod
    ) {
        val purchases = allExpenses.filter { it.transactionType == TransactionType.PURCHASE }
        val now = System.currentTimeMillis()
        val categoryMap = categories.associateBy { it.id }
        // Calculate date ranges
        val (currentStart, currentEnd) = getPeriodRange(period, now)
        val periodLength = currentEnd - currentStart
        val previousStart = currentStart - periodLength
        val previousEnd = currentStart
        // Current period expenses
        val currentExpenses = purchases.filter { it.date in currentStart..currentEnd }
        val previousExpenses = purchases.filter { it.date in previousStart..previousEnd }
        val currentTotal = currentExpenses.sumOf { it.amount }
        val previousTotal = previousExpenses.sumOf { it.amount }
        val changePercent = if (previousTotal > 0) {
            ((currentTotal - previousTotal) / previousTotal * 100).toFloat()
        } else null
        // Category breakdown
        val categoryBreakdown = currentExpenses
            .groupBy { it.categoryId }
            .mapNotNull { (catId, exps) ->
                val cat = catId?.let { categoryMap[it] } ?: return@mapNotNull null
                val totalAmount = exps.sumOf { it.amount }
                CategoryBreakdown(
                    category = cat,
                    total = totalAmount,
                    count = exps.size,
                    percentage = if (currentTotal > 0)
                        (totalAmount / currentTotal * 100).toFloat()
                    else 0f
                )
            }
            .sortedByDescending { it.total }
        // Merchant breakdown
        val merchantBreakdown = currentExpenses
            .groupBy { it.merchant.uppercase() }
            .map { (_, exps) ->
                val totalAmount = exps.sumOf { it.amount }
                MerchantBreakdown(
                    name = exps.first().merchant,
                    totalSpent = totalAmount,
                    transactionCount = exps.size,
                    averageTransaction = totalAmount / exps.size,
                    categoryId = exps.firstOrNull()?.categoryId
                )
            }
            .sortedByDescending { it.totalSpent }
        // Daily totals for chart
        val chartDays = when (period) {
            TimePeriod.TODAY -> 1
            TimePeriod.WEEK -> 7
            TimePeriod.MONTH -> 30
            TimePeriod.YEAR -> 365
            TimePeriod.ALL -> {
                val oldest = purchases.minOfOrNull { it.date } ?: now
                ((now - oldest) / 86_400_000L).toInt().coerceIn(7, 365)
            }
        }
        val dailyTotals = insightsEngine.buildDailyTotals(currentExpenses, chartDays)
        // Insights
        val insightsSnapshot = insightsEngine.generateInsights(categories, allExpenses)
        val insights = insightsEngine.getLegacyInsights(insightsSnapshot)
        // Recurring (use the list from snapshot but mapped to legacy if needed, or just legacy detection)
        // Using duplicate detection logic for now to stay compatible with UI model
        val recurring = insightsEngine.detectRecurring(purchases)
        _state.update {
            it.copy(
                selectedPeriod = period,
                currentTotal = currentTotal,
                previousTotal = if (previousTotal > 0) previousTotal else null,
                changePercent = changePercent,
                transactionCount = currentExpenses.size,
                categoryBreakdown = categoryBreakdown,
                merchantBreakdown = merchantBreakdown,
                dailyTotals = dailyTotals,
                insights = insights,
                recurring = recurring,
                isLoading = false
            )
        }
    }
    private fun getPeriodRange(period: TimePeriod, now: Long): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        return when (period) {
            TimePeriod.TODAY -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                Pair(cal.timeInMillis, now)
            }
            TimePeriod.WEEK -> {
                cal.add(Calendar.DAY_OF_YEAR, -7)
                Pair(cal.timeInMillis, now)
            }
            TimePeriod.MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                Pair(cal.timeInMillis, now)
            }
            TimePeriod.YEAR -> {
                cal.set(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                Pair(cal.timeInMillis, now)
            }
            TimePeriod.ALL -> {
                Pair(0L, now)
            }
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\budget\BudgetScreen.kt <a name="mainjavacomyournameexpensetrackeruiscreensbudgetbudgetscreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.budget
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.budget.BudgetSuggestion
import java.text.SimpleDateFormat
import java.util.*
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BudgetScreen(
    viewModel: BudgetViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val categories by viewModel.categories.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingBudget by remember { mutableStateOf<BudgetStatus?>(null) }
    val dateFormat = remember { SimpleDateFormat("MMM dd", Locale.getDefault()) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Budgets") },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Budget")
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { BudgetSummaryCard(uiState.budgets) }
                if (uiState.suggestions.isNotEmpty()) {
                    item { SuggestionsBanner(uiState.suggestions, categories, onAdd = { viewModel.addBudget(it) }) }
                }
                if (uiState.budgets.isEmpty()) {
                    item { EmptyBudgetsState { showAddDialog = true } }
                } else {
                    items(uiState.budgets) { budgetStatus ->
                        BudgetCard(
                            status = budgetStatus,
                            dateFormat = dateFormat,
                            onEdit = { editingBudget = budgetStatus },
                            onToggle = { isActive -> viewModel.toggleBudget(budgetStatus.budget.id, isActive) },
                            onDelete = { viewModel.deleteBudget(it) }
                        )
                    }
                }
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
        if (showAddDialog) {
            AddEditBudgetDialog(
                onDismiss = { showAddDialog = false },
                onConfirm = { viewModel.addBudget(it) },
                categories = categories
            )
        }
        editingBudget?.let { status ->
            AddEditBudgetDialog(
                initialBudget = status.budget,
                onDismiss = { editingBudget = null },
                onConfirm = { viewModel.updateBudget(it) },
                categories = categories
            )
        }
    }
}
@Composable
fun BudgetSummaryCard(budgets: List<BudgetStatus>) {
    val onTrack = budgets.count { it.healthStatus == BudgetHealthStatus.ON_TRACK }
    val warning = budgets.count { it.healthStatus == BudgetHealthStatus.WARNING || it.healthStatus == BudgetHealthStatus.CRITICAL }
    val exceeded = budgets.count { it.healthStatus == BudgetHealthStatus.EXCEEDED }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            SummaryItem("On Track", onTrack, Color(0xFF4CAF50))
            SummaryItem("Warning", warning, Color(0xFFFFC107))
            SummaryItem("Exceeded", exceeded, Color(0xFFFF5722))
        }
    }
}
@Composable
fun SummaryItem(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}
@Composable
fun BudgetCard(
    status: BudgetStatus,
    dateFormat: SimpleDateFormat,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: (Budget) -> Unit
) {
    val progressColor = when (status.healthStatus) {
        BudgetHealthStatus.ON_TRACK -> Color(0xFF4CAF50)
        BudgetHealthStatus.WARNING -> Color(0xFFFFC107)
        BudgetHealthStatus.CRITICAL -> Color(0xFFFF9800)
        BudgetHealthStatus.EXCEEDED -> Color(0xFFFF5722)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onEdit
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    status.category?.icon ?: "💰",
                    fontSize = 24.sp
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        status.category?.name ?: "Overall Budget",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Text(
                        "${status.budget.period.name.lowercase().capitalize()} • Starts ${dateFormat.format(Date(status.budget.startDate))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = status.budget.isActive,
                    onCheckedChange = onToggle,
                    modifier = Modifier.budgetScale(0.8f)
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "€${"%.2f".format(status.spentAmount)} spent",
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
                Text(
                    "€${"%.2f".format(status.budget.amount)} limit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { status.percentUsed.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = progressColor,
                trackColor = progressColor.copy(alpha = 0.2f)
            )
            if (status.percentUsed > 1f) {
                Text(
                    "€${"%.2f".format(status.spentAmount - status.budget.amount)} over budget",
                    color = Color(0xFFFF5722),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Text(
                    "€${"%.2f".format(status.remainingAmount)} remaining",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
@Composable
fun SuggestionsBanner(
    suggestions: List<BudgetSuggestion>,
    categories: List<Category>,
    onAdd: (Budget) -> Unit
) {
    var currentIndex by remember { mutableIntStateOf(0) }
    val suggestion = suggestions.getOrNull(currentIndex) ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Smart Suggestion", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "You spend a lot on ${suggestion.categoryName}. How about a monthly budget of €${"%.0f".format(suggestion.suggestedAmount)}?",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { if (currentIndex < suggestions.size - 1) currentIndex++ else currentIndex = 0 }) {
                    Text("Skip")
                }
                Button(onClick = {
                    onAdd(Budget(
                        categoryId = suggestion.categoryId,
                        amount = suggestion.suggestedAmount,
                        period = BudgetPeriod.MONTHLY,
                        startDate = System.currentTimeMillis()
                    ))
                }) {
                    Text("Create Budget")
                }
            }
        }
    }
}
@Composable
fun EmptyBudgetsState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("No budgets set yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Track your spending by category to save more money.",
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Button(onClick = onAdd) {
            Text("Set Your First Budget")
        }
    }
}
@Composable
fun AddEditBudgetDialog(
    initialBudget: Budget? = null,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onConfirm: (Budget) -> Unit
) {
    var amount by remember { mutableStateOf(initialBudget?.amount?.toString() ?: "") }
    var selectedCategory by remember { mutableStateOf(initialBudget?.categoryId) }
    var period by remember { mutableStateOf(initialBudget?.period ?: BudgetPeriod.MONTHLY) }
    var rollover by remember { mutableStateOf(initialBudget?.rollover ?: false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialBudget == null) "Create Budget" else "Edit Budget") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Budget Amount (€)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                )
                Text("Category", style = MaterialTheme.typography.labelMedium)
                CategorySelector(
                    categories = categories,
                    selectedId = selectedCategory,
                    onSelect = { selectedCategory = it }
                )
                Text("Period", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BudgetPeriod.values().forEach { p ->
                        FilterChip(
                            selected = period == p,
                            onClick = { period = p },
                            label = { Text(p.name.lowercase().capitalize(), fontSize = 12.sp) }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rollover, onCheckedChange = { rollover = it })
                    Text("Rollover unspent amount", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amt = amount.toDoubleOrNull() ?: 0.0
                    if (amt > 0) {
                        val budgetToSave = initialBudget?.copy(
                            categoryId = selectedCategory,
                            amount = amt,
                            period = period,
                            rollover = rollover
                        ) ?: Budget(
                            categoryId = selectedCategory,
                            amount = amt,
                            period = period,
                            startDate = System.currentTimeMillis(),
                            rollover = rollover
                        )
                        onConfirm(budgetToSave)
                        onDismiss()
                    }
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategorySelector(
    categories: List<Category>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selectedId == null,
            onClick = { onSelect(null) },
            label = { Text("Overall") }
        )
        categories.forEach { category ->
            FilterChip(
                selected = selectedId == category.id,
                onClick = { onSelect(category.id) },
                label = { Text("${category.icon} ${category.name}") }
            )
        }
    }
}
// Extension to avoid repetitive logic
fun String.capitalize() = replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
// Helper for UI scaling using graphicsLayer for better performance
fun Modifier.budgetScale(scale: Float): Modifier = this.then(Modifier.graphicsLayer(scaleX = scale, scaleY = scale))

```

---

## main\java\com\yourname\expensetracker\ui\screens\budget\BudgetViewModel.kt <a name="mainjavacomyournameexpensetrackeruiscreensbudgetbudgetviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.budget
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.budget.BudgetSuggestion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
data class BudgetUiState(
    val budgets: List<BudgetStatus> = emptyList(),
    val suggestions: List<BudgetSuggestion> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)
@HiltViewModel
class BudgetViewModel @Inject constructor(
    private val budgetRepository: BudgetRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(BudgetUiState(isLoading = true))
    val uiState: StateFlow<BudgetUiState> = _uiState.asStateFlow()
    val categories = categoryRepository.allCategories.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    init {
        loadData()
    }
    private fun loadData() {
        viewModelScope.launch {
            combine(
                budgetRepository.getBudgetStatuses(),
                flow { emit(budgetRepository.getSuggestions()) }
            ) { statuses, suggestions ->
                BudgetUiState(
                    budgets = statuses,
                    suggestions = suggestions,
                    isLoading = false
                )
            }.catch { e ->
                _uiState.emit(BudgetUiState(error = e.message, isLoading = false))
            }.collect {
                _uiState.emit(it)
            }
        }
    }
    fun addBudget(budget: Budget) {
        if (!validateThresholds(budget)) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = budgetRepository.addBudget(budget)
            when (result) {
                is com.yourname.expensetracker.domain.model.Result.Success -> {
                    _uiState.update { it.copy(isLoading = false) }
                }
                is com.yourname.expensetracker.domain.model.Result.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = result.message) }
                }
                else -> {}
            }
        }
    }
    fun updateBudget(budget: Budget) {
        if (!validateThresholds(budget)) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = budgetRepository.updateBudget(budget)
            when (result) {
                is com.yourname.expensetracker.domain.model.Result.Success -> {
                    _uiState.update { it.copy(isLoading = false) }
                }
                is com.yourname.expensetracker.domain.model.Result.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = result.message) }
                }
                else -> {}
            }
        }
    }
    private fun validateThresholds(budget: Budget): Boolean {
        if (budget.notifyAtWarning <= 0f || budget.notifyAtWarning >= 1f) {
            _uiState.update { it.copy(error = "Warning threshold must be between 0 and 1") }
            return false
        }
        if (budget.notifyAtCritical <= budget.notifyAtWarning || budget.notifyAtCritical >= 1.05f) {
            _uiState.update { it.copy(error = "Critical threshold must be between warning and 100%") }
            return false
        }
        return true
    }
    fun deleteBudget(budget: Budget) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = budgetRepository.deleteBudget(budget)
             when (result) {
                is com.yourname.expensetracker.domain.model.Result.Success -> {
                    _uiState.update { it.copy(isLoading = false) }
                }
                is com.yourname.expensetracker.domain.model.Result.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = result.message) }
                }
                else -> {}
            }
        }
    }
    fun toggleBudget(id: Long, isActive: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
             val result = budgetRepository.toggleBudget(id, isActive)
             when (result) {
                is com.yourname.expensetracker.domain.model.Result.Success -> {
                    _uiState.update { it.copy(isLoading = false) }
                }
                is com.yourname.expensetracker.domain.model.Result.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = result.message) }
                }
                else -> {}
            }
        }
    }
    fun refreshSuggestions() {
        viewModelScope.launch {
            val suggestions = budgetRepository.getSuggestions()
            _uiState.update { it.copy(suggestions = suggestions) }
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\categories\CategoryScreen.kt <a name="mainjavacomyournameexpensetrackeruiscreenscategoriescategoryscreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.categories
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Category
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(
    onDismiss: () -> Unit,
    viewModel: CategoryViewModel = hiltViewModel()
) {
    val categories by viewModel.categories.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Categories") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Category")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(categories) { category ->
                CategoryItem(category)
            }
        }
        if (showAddDialog) {
            AddCategoryDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { name, icon, color ->
                    viewModel.addCategory(name, icon, color)
                    showAddDialog = false
                }
            )
        }
    }
}
@Composable
fun CategoryItem(category: Category) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val color = remember(category.color) {
                try {
                    Color(android.graphics.Color.parseColor(category.color))
                } catch (e: Exception) {
                    Color.Gray
                }
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(category.icon, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(category.name, style = MaterialTheme.typography.bodyLarge)
            if (category.isDefault) {
                Spacer(modifier = Modifier.weight(1f))
                Text("Default", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
    }
}
@Composable
fun AddCategoryDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("📦") } // Default icon
    var color by remember { mutableStateOf("#607D8B") } // Default color
    var isNameError by remember { mutableStateOf(false) }
    // Simple list of preset icons/colors could be added here for better UX
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { 
                        name = it
                        if (it.isNotBlank()) isNameError = false
                    },
                    label = { Text("Name") },
                    isError = isNameError,
                    supportingText = { if (isNameError) Text("Name cannot be empty") },
                    singleLine = true
                )
                // In a real app, use a proper picker. For now, text fields or presets.
                OutlinedTextField(
                    value = icon,
                    onValueChange = { if (it.length <= 2) icon = it }, // Limit to emoji size
                    label = { Text("Icon (Emoji)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { 
                    if (name.isNotBlank()) {
                        onAdd(name, icon, color)
                    } else {
                        isNameError = true
                    }
                }
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\categories\CategoryViewModel.kt <a name="mainjavacomyournameexpensetrackeruiscreenscategoriescategoryviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.categories
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.CategoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
@HiltViewModel
class CategoryViewModel @Inject constructor(
    private val repository: CategoryRepository
) : ViewModel() {
    val categories: StateFlow<List<Category>> = repository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    init {
        // Seed default categories on first run
        viewModelScope.launch {
            repository.ensureDefaultCategories()
        }
    }
    fun addCategory(name: String, icon: String, color: String) {
        viewModelScope.launch {
            repository.addCategory(name, icon, color)
        }
    }
    // Future: delete, edit
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\debug\DebugScreen.kt <a name="mainjavacomyournameexpensetrackeruiscreensdebugdebugscreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.debug
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import java.text.SimpleDateFormat
import java.util.*
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    onDismiss: () -> Unit,
    viewModel: DebugViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val notifications by viewModel.filteredNotifications.collectAsState()
    val count by viewModel.notificationCount.collectAsState()
    val packages by viewModel.packages.collectAsState()
    val selectedFilter by viewModel.selectedPackageFilter.collectAsState()
    var expandedNotificationId by remember { mutableStateOf<Long?>(null) }
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault()) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug: Notifications ($count)") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clearAll() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear all")
                    }
                }
            )
        }
    ) { padding ->
        // Root list for the entire screen to ensure scrolling
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            // 1. Permission Button
            item {
                Button(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text("Open Notification Access Settings")
                }
            }
            // 2. Mass Simulation Section
            item {
                val isSimulating by viewModel.isSimulating.collectAsState()
                var simulationCount by remember { mutableStateOf(50f) }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "🧪 Mass Simulation",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Quantity: ${simulationCount.toInt()}")
                        Slider(
                            value = simulationCount,
                            onValueChange = { simulationCount = it },
                            valueRange = 10f..500f,
                            steps = 9
                        )
                        Button(
                            onClick = { viewModel.simulateMassData(simulationCount.toInt()) },
                            enabled = !isSimulating,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isSimulating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text("Generate ${simulationCount.toInt()} Transactions")
                            }
                        }
                    }
                }
            }
            // 3. Test & Sync Buttons
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Button(
                        onClick = { viewModel.simulateTestNotification() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
                        )
                    ) {
                        Text("Simulate Single Purchase (€12.50)")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.triggerManualSync(context) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Text("Sync Active Notifications")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.resetExpenses() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Reset All Expenses")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.resetBudgets() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        )
                    ) {
                        Text("Reset All Budgets")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.resetSourceStats() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                        )
                    ) {
                        Text("Reset Trust Scores")
                    }
                }
            }
            // 4. ML Stats
            item {
                val classifierStats by viewModel.classifierStats.collectAsState()
                val sourceStatsList by viewModel.sourceStats.collectAsState()
                Spacer(modifier = Modifier.height(16.dp))
                MlStatsSection(
                    classifierStats = classifierStats,
                    sourceStats = sourceStatsList,
                    onRetrain = { viewModel.retrainClassifier() }
                )
            }
            // 5. Filters
            item {
                if (packages.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = selectedFilter == null,
                                onClick = { viewModel.setPackageFilter(null) },
                                label = { Text("All") }
                            )
                        }
                        items(packages) { pkg ->
                            FilterChip(
                                selected = selectedFilter == pkg,
                                onClick = { viewModel.setPackageFilter(pkg) },
                                label = { 
                                    Text(
                                        pkg.split(".").lastOrNull() ?: pkg,
                                        maxLines = 1
                                    ) 
                                }
                            )
                        }
                    }
                }
            }
            // 6. Blocked Apps
            item {
                val blockedApps by viewModel.blockedPackages.collectAsState()
                if (blockedApps.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Blocked Apps:",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(blockedApps) { blocked ->
                            InputChip(
                                selected = true,
                                onClick = { viewModel.unblockPackage(blocked.packageName) },
                                label = { 
                                    Text(
                                        blocked.packageName.split(".").lastOrNull() ?: blocked.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    ) 
                                },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Unblock",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                colors = InputChipDefaults.inputChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            // 7. Notification List
            if (notifications.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No notifications captured yet")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Make sure notification access is enabled",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            } else {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Captured Notifications (${notifications.size})",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                items(notifications.take(100), key = { it.id }) { notification ->
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        NotificationCard(
                            notification = notification,
                            dateFormat = dateFormat,
                            isExpanded = expandedNotificationId == notification.id,
                            onClick = {
                                expandedNotificationId = 
                                    if (expandedNotificationId == notification.id) null 
                                    else notification.id
                            },
                            onMarkRelevant = { viewModel.markAsRelevant(notification.id, true) },
                            onMarkIrrelevant = { viewModel.markAsRelevant(notification.id, false) },
                            onBlockPackage = { viewModel.blockPackage(notification.packageName) }
                        )
                    }
                }
            }
        }
    }
}
@Composable
fun NotificationCard(
    notification: RawNotification,
    dateFormat: SimpleDateFormat,
    isExpanded: Boolean,
    onClick: () -> Unit,
    onMarkRelevant: () -> Unit,
    onMarkIrrelevant: () -> Unit,
    onBlockPackage: () -> Unit
) {
    val relevanceColor = when (notification.isRelevant) {
        true -> Color(0xFF4CAF50).copy(alpha = 0.1f)
        false -> Color(0xFFF44336).copy(alpha = 0.1f)
        null -> Color.Transparent
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .background(relevanceColor)
                .padding(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = notification.appName ?: notification.packageName.split(".").last(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = dateFormat.format(Date(notification.capturedAt)),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            // Title
            notification.title?.let {
                Text(
                    text = it,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Text
            val displayText = notification.bigText ?: notification.text
            displayText?.let {
                Text(
                    text = it,
                    fontSize = 12.sp,
                    maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Expanded content
            if (isExpanded) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                // Package name
                Text(
                    text = "Package: ${notification.packageName}",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline
                )
                // SubText if present
                notification.subText?.let {
                    Text(
                        text = "SubText: $it",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                // Extras JSON
                notification.extrasJson?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Extras:",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = it,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                // Action buttons
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = onMarkRelevant,
                        label = { Text("Expense ✓", fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                        }
                    )
                    AssistChip(
                        onClick = onMarkIrrelevant,
                        label = { Text("Ignore ✗", fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                        }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    AssistChip(
                        onClick = onBlockPackage,
                        label = { Text("Block App", fontSize = 11.sp) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            labelColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete, 
                                null, 
                                Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    )
                }
            }
        }
    }
}
@Composable
fun MlStatsSection(
    classifierStats: com.yourname.expensetracker.domain.intelligence.ClassifierStats,
    sourceStats: List<com.yourname.expensetracker.data.database.entity.SourceStats>,
    onRetrain: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "🧠 ML Classifier",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "Status: ${if (classifierStats.isReady) "✅ Active" else "⏳ Training"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Positive samples: ${classifierStats.totalPositive}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Negative samples: ${classifierStats.totalNegative}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Vocabulary: ${classifierStats.vocabularySize} words",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedButton(
                    onClick = onRetrain,
                    enabled = classifierStats.totalPositive + classifierStats.totalNegative >= 20
                ) {
                    Text("Retrain", fontSize = 12.sp)
                }
            }
            // Source trust scores
            if (sourceStats.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "📊 Source Trust Scores",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                sourceStats.take(5).forEach { stats ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stats.packageName.split(".").lastOrNull() ?: stats.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${stats.acceptedAsExpense}/${stats.totalNotifications}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val trustColor = when {
                            stats.trustScore > 0.7f -> Color(0xFF4CAF50)
                            stats.trustScore > 0.3f -> Color(0xFFFFC107)
                            else -> Color(0xFFFF5722)
                        }
                        Text(
                            text = "${(stats.trustScore * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = trustColor
                        )
                    }
                }
            }
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\debug\DebugViewModel.kt <a name="mainjavacomyournameexpensetrackeruiscreensdebugdebugviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.debug
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
@HiltViewModel
class DebugViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val budgetRepository: com.yourname.expensetracker.data.repository.BudgetRepository,
    private val categoryRepository: com.yourname.expensetracker.data.repository.CategoryRepository,
    private val notificationSeeder: com.yourname.expensetracker.domain.debug.NotificationSeeder
) : ViewModel() {
    val notifications: StateFlow<List<RawNotification>> = repository
        .getRecentNotifications(200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30000), emptyList())
    val notificationCount: StateFlow<Int> = repository
        .getCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30000), 0)
    val packages: StateFlow<List<String>> = repository
        .getAllPackages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30000), emptyList())
    val blockedPackages: StateFlow<List<com.yourname.expensetracker.data.database.entity.BlockedPackage>> = repository
        .getBlockedPackages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30000), emptyList())
    val totalSpent: StateFlow<Double> = repository
        .getTotalSpent()
        .map { it ?: 0.0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30000), 0.0)
    val sourceStats: StateFlow<List<com.yourname.expensetracker.data.database.entity.SourceStats>> = repository
        .getSourceStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30000), emptyList())
    val classifierStats: StateFlow<com.yourname.expensetracker.domain.intelligence.ClassifierStats> = repository
        .getClassifierStatsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30000), com.yourname.expensetracker.domain.intelligence.ClassifierStats(0, 0, 0, false))
    private val _selectedPackageFilter = MutableStateFlow<String?>(null)
    val selectedPackageFilter: StateFlow<String?> = _selectedPackageFilter
    val filteredNotifications: StateFlow<List<RawNotification>> = combine(
        notifications,
        _selectedPackageFilter
    ) { notifs, filter ->
        if (filter == null) notifs
        else notifs.filter { it.packageName == filter }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(30000), emptyList())
    fun setPackageFilter(packageName: String?) {
        _selectedPackageFilter.value = packageName
    }
    fun clearAll() {
        viewModelScope.launch {
            repository.deleteAll()
        }
    }
    fun resetExpenses() {
        viewModelScope.launch {
            repository.deleteAllExpenses()
        }
    }
    fun resetBudgets() {
        viewModelScope.launch {
            budgetRepository.deleteAll()
        }
    }
    fun markAsRelevant(id: Long, isRelevant: Boolean) {
        viewModelScope.launch {
            repository.markAsRelevant(id, isRelevant)
        }
    }
    fun blockPackage(packageName: String) {
        viewModelScope.launch {
            repository.blockPackage(packageName)
        }
    }
    fun unblockPackage(packageName: String) {
        viewModelScope.launch {
            repository.unblockPackage(packageName)
        }
    }
    fun retrainClassifier() {
        viewModelScope.launch {
            repository.retrainClassifier()
        }
    }
    fun resetSourceStats() {
        viewModelScope.launch {
            repository.resetSourceStats()
        }
    }
    private val _isSimulating = MutableStateFlow(false)
    val isSimulating: StateFlow<Boolean> = _isSimulating
    fun simulateMassData(count: Int) {
        viewModelScope.launch {
            _isSimulating.value = true
            // 1. Ensure categories exist
            categoryRepository.ensureDefaultCategories()
            // 2. Pre-seed mappings so categorization works
            val cats = categoryRepository.allCategories.first()
            val catMap = cats.associate { it.name to it.id }
            notificationSeeder.categories.forEach { (catName, merchants) ->
                val catId = catMap[catName]
                if (catId != null) {
                    merchants.forEach { merchant ->
                        try {
                            categoryRepository.learnMerchantCategory(merchant, catId)
                        } catch (e: Exception) {
                            // Ignore duplicates
                        }
                    }
                }
            }
            // 3. Generate data
            val simulated = notificationSeeder.generate(count)
            repository.processAndSaveAll(simulated)
            _isSimulating.value = false
        }
    }
    fun simulateTestNotification() {
        viewModelScope.launch {
            val fakeNotification = RawNotification(
                packageName = "com.test.bank",
                appName = "Test Bank",
                title = "Purchase Alert",
                text = "You paid €12.50 at Amazon",
                timestamp = System.currentTimeMillis(),
                capturedAt = System.currentTimeMillis()
            )
            repository.processAndSave(fakeNotification)
        }
    }
    fun triggerManualSync(context: android.content.Context) {
        val intent = android.content.Intent(context, com.yourname.expensetracker.service.NotificationCaptureService::class.java).apply {
            action = com.yourname.expensetracker.service.NotificationCaptureService.ACTION_REFRESH_NOTIFICATIONS
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\home\HomeScreen.kt <a name="mainjavacomyournameexpensetrackeruiscreenshomehomescreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.home
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.rounded.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.PlannedExpensePriority
import com.yourname.expensetracker.ui.components.*
import com.yourname.expensetracker.ui.screens.receiptscan.ReceiptScanScreen
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.text.SimpleDateFormat
import java.util.*
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToReview: () -> Unit,
    onNavigateToRecurring: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.dashboard.collectAsState()
    var showQuickSettings by remember { mutableStateOf(false) }
    var showCategories by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }
    var showAddPlannedExpenseDialog by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = SemanticColors.BaseNavy,
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulseDot(isActive = state.isServiceRunning)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "DASHBOARD", 
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            color = SemanticColors.TextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleEditMode() }) {
                        Icon(
                            if (state.isEditMode) Icons.Rounded.Check else Icons.Rounded.EditAttributes, 
                            contentDescription = "Edit Layout",
                            tint = if (state.isEditMode) SemanticColors.SuccessGreen else SemanticColors.TextSecondary
                        )
                    }
                    IconButton(onClick = { showQuickSettings = true }) {
                        Icon(
                            Icons.Rounded.Settings, 
                            contentDescription = "Settings",
                            tint = SemanticColors.TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SemanticColors.BaseNavy,
                    titleContentColor = SemanticColors.TextPrimary
                )
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = SemanticColors.PrimaryIndigo)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(
                    items = state.widgets,
                    key = { getWidgetId(it) },
                    span = { widget ->
                        GridItemSpan(if (isFullSpan(widget)) 2 else 1)
                    },
                    contentType = { it.javaClass.simpleName }
                ) { widget ->
                    WidgetWrapper(
                        widget = widget,
                        isEditMode = state.isEditMode,
                        onMoveUp = { viewModel.moveWidget(getWidgetId(widget), true) },
                        onMoveDown = { viewModel.moveWidget(getWidgetId(widget), false) },
                        onToggleVisibility = { viewModel.toggleWidgetVisibility(getWidgetId(widget)) }
                    ) {
                        when (widget) {
                            is DashboardWidget.SafeToSpend -> {
                                HeroBentoCard {
                                    Text(
                                        text = "SAFE TO SPEND",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = SemanticColors.PrimaryLight,
                                        letterSpacing = 1.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    AmountText(
                                        amount = widget.amount,
                                        style = MaterialTheme.typography.displayMedium,
                                        color = SemanticColors.TextPrimary
                                    )
                                    if (widget.totalBudget != null) {
                                        LinearProgressIndicator(
                                            progress = { ((widget.totalBudget - widget.amount) / widget.totalBudget).toFloat().coerceIn(0f, 1f) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 12.dp)
                                                .height(8.dp)
                                                .clip(CircleShape),
                                            color = SemanticColors.PrimaryIndigo,
                                            trackColor = SemanticColors.PrimaryIndigo.copy(alpha = 0.2f)
                                        )
                                        Text(
                                            "${widget.daysRemaining} DAYS REMAINING",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = SemanticColors.TextSecondary,
                                            letterSpacing = 0.5.sp
                                        )
                                    }
                                }
                            }
                            is DashboardWidget.SpendingPaceWidget -> {
                                BentoCard {
                                    Text(
                                        "PACE", 
                                        style = MaterialTheme.typography.labelSmall, 
                                        fontWeight = FontWeight.Bold,
                                        color = SemanticColors.TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    SpendingPaceGauge(pace = widget.pace)
                                }
                            }
                            is DashboardWidget.PendingReviewAlert -> {
                                val badgeColor = if (widget.count > 0) SemanticColors.WarningOrange else SemanticColors.TextMuted
                                BentoCard(
                                    containerColor = if (widget.count > 0) 
                                        SemanticColors.WarningOrange.copy(alpha = 0.05f) 
                                        else SemanticColors.GlassSurface,
                                    border = BorderStroke(
                                        1.dp, 
                                        if (widget.count > 0) SemanticColors.WarningOrange.copy(alpha = 0.3f) 
                                        else SemanticColors.GlassBorder
                                    ),
                                    onClick = onNavigateToReview
                                ) {
                                    Text(
                                        "REVIEW", 
                                        style = MaterialTheme.typography.labelSmall, 
                                        fontWeight = FontWeight.Bold,
                                        color = SemanticColors.TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            "${widget.count}", 
                                            style = MaterialTheme.typography.displaySmall, 
                                            fontWeight = FontWeight.ExtraBold, 
                                            color = badgeColor
                                        )
                                        Text(
                                            "PENDING", 
                                            style = MaterialTheme.typography.labelSmall, 
                                            color = badgeColor,
                                            letterSpacing = 1.sp
                                        )
                                    }
                                }
                            }
                            is DashboardWidget.SpendingTrend -> {
                                BentoCard {
                                    SpendingTrendChart(
                                        currentMonthData = widget.currentMonthData,
                                        previousMonthData = widget.previousMonthData
                                    )
                                }
                            }
                            is DashboardWidget.NaturalLanguageInsight -> {
                                BentoCard(
                                    containerColor = SemanticColors.PrimaryIndigo.copy(alpha = 0.1f),
                                    border = BorderStroke(1.dp, SemanticColors.PrimaryIndigo.copy(alpha = 0.2f))
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            modifier = Modifier.size(40.dp),
                                            shape = CircleShape,
                                            color = SemanticColors.PrimaryIndigo.copy(alpha = 0.2f)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(widget.icon, fontSize = 20.sp)
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Text(
                                            text = widget.text,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = SemanticColors.TextPrimary,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                            is DashboardWidget.PeriodSummary -> {
                                BentoCard {
                                    Text(
                                        "PERIOD SUMMARY", 
                                        style = MaterialTheme.typography.labelSmall, 
                                        fontWeight = FontWeight.Bold,
                                        color = SemanticColors.TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        StatLabel("TODAY", "€${String.format("%.2f", widget.todaySpent)}", modifier = Modifier.weight(1f))
                                        StatLabel("WEEK", "€${String.format("%.2f", widget.weekSpent)}", modifier = Modifier.weight(1f))
                                        StatLabel("MONTH", "€${String.format("%.2f", widget.monthSpent)}", modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                            is DashboardWidget.BudgetHealthWidget -> {
                                BentoCard {
                                    Text(
                                        "BUDGET HEALTH", 
                                        style = MaterialTheme.typography.labelSmall, 
                                        fontWeight = FontWeight.Bold,
                                        color = SemanticColors.TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        widget.summary ?: "ALL BUDGETS ON TRACK", 
                                        style = MaterialTheme.typography.titleMedium, 
                                        fontWeight = FontWeight.Bold,
                                        color = if (widget.summary?.contains("exceeded", ignoreCase = true) == true) SemanticColors.DangerRed else SemanticColors.SuccessGreen
                                    )
                                }
                            }
                            is DashboardWidget.TopCategories -> {
                                BentoCard {
                                    Text(
                                        "TOP CATEGORIES", 
                                        style = MaterialTheme.typography.labelSmall, 
                                        fontWeight = FontWeight.Bold,
                                        color = SemanticColors.TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    widget.categories.forEach { CategorySpendingRow(it) }
                                }
                            }
                            is DashboardWidget.RecentTransactions -> {
                                BentoCard {
                                    Text(
                                        "RECENT ACTIVITY", 
                                        style = MaterialTheme.typography.labelSmall, 
                                        fontWeight = FontWeight.Bold,
                                        color = SemanticColors.TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    widget.expenses.forEach { RecentExpenseRow(it) }
                                }
                            }
                            is DashboardWidget.FinancialWeatherWidget -> {
                                FinancialWeatherCard(
                                    state = widget.weather.state,
                                    headline = widget.weather.headline,
                                    summary = widget.weather.summary,
                                    icon = widget.weather.icon,
                                    totalCommitted = widget.weather.totalCommitted,
                                    totalLikely = widget.weather.totalLikely,
                                    discretionaryBudget = widget.weather.discretionaryBudget,
                                    pastSpendingPoints = widget.weather.pastSpendingPoints,
                                    projectedSpendingPoints = widget.weather.projectedSpendingPoints,
                                    upcomingItems = widget.weather.upcomingItems,
                                    totalRecurringCount = widget.weather.totalRecurringCount,
                                    details = widget.weather.details,
                                    onManageClick = onNavigateToRecurring,
                                    onPlanClick = { showAddPlannedExpenseDialog = true }
                                )
                            }
                        }
                    }
                }
            }
        }
        if (showQuickSettings) {
            QuickSettingsDialog(
                onDismiss = { showQuickSettings = false },
                onNavigateToCategories = { 
                    showQuickSettings = false
                    showCategories = true 
                },
                onNavigateToDebug = {
                    showQuickSettings = false
                    showDebug = true
                }
            )
        }
        if (showCategories) {
            com.yourname.expensetracker.ui.screens.categories.CategoryScreen(
                onDismiss = { showCategories = false }
            )
        }
        if (showDebug) {
            com.yourname.expensetracker.ui.screens.debug.DebugScreen(
                onDismiss = { showDebug = false }
            )
        }
        if (showAddPlannedExpenseDialog) {
            AddPlannedExpenseDialog(
                onDismiss = { showAddPlannedExpenseDialog = false },
                onConfirm = { desc, amount, date, catId, priority ->
                    viewModel.addPlannedExpense(desc, amount, date, catId, priority)
                    showAddPlannedExpenseDialog = false
                }
            )
        }
    }
}
@Composable
fun WidgetWrapper(
    widget: DashboardWidget,
    isEditMode: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleVisibility: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        content()
        if (isEditMode) {
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(24.dp))
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onMoveUp) {
                        Icon(Icons.Rounded.ArrowUpward, "Move Up", tint = Color.White)
                    }
                    IconButton(onClick = onToggleVisibility) {
                        Icon(Icons.Rounded.VisibilityOff, "Hide", tint = Color.White)
                    }
                    IconButton(onClick = onMoveDown) {
                        Icon(Icons.Rounded.ArrowDownward, "Move Down", tint = Color.White)
                    }
                }
            }
        }
    }
}
private fun getWidgetId(widget: DashboardWidget): String = when (widget) {
    is DashboardWidget.SafeToSpend -> "safe_to_spend"
    is DashboardWidget.SpendingPaceWidget -> "spending_pace"
    is DashboardWidget.PendingReviewAlert -> "review_alert"
    is DashboardWidget.SpendingTrend -> "spending_trend"
    is DashboardWidget.NaturalLanguageInsight -> "insight"
    is DashboardWidget.PeriodSummary -> "period_summary"
    is DashboardWidget.BudgetHealthWidget -> "budget_health"
    is DashboardWidget.TopCategories -> "top_categories"
    is DashboardWidget.RecentTransactions -> "recent_transactions"
    is DashboardWidget.FinancialWeatherWidget -> "financial_weather"
}
private fun isFullSpan(widget: DashboardWidget): Boolean = when (widget) {
    is DashboardWidget.SpendingPaceWidget,
    is DashboardWidget.PendingReviewAlert -> false
    else -> true
}
@Composable
fun QuickSettingsDialog(
    onDismiss: () -> Unit,
    onNavigateToCategories: () -> Unit,
    onNavigateToDebug: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quick Settings") },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text("Categories") },
                    leadingContent = { Text("🏷️") },
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onNavigateToCategories() }
                )
                ListItem(
                    headlineContent = { Text("Debug Menu") },
                    leadingContent = { Text("🛠️") },
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onNavigateToDebug() }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
@Composable
fun PeriodCard(label: String, amount: Double, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(
                "€${String.format("%.2f", amount)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
@Composable
fun CategorySpendingRow(item: CategorySpending) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val categoryColor = remember(item.category.color) {
            try { Color(android.graphics.Color.parseColor(item.category.color)) } 
            catch (e: Exception) { Color.Gray }
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(categoryColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(item.category.icon, fontSize = 18.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.category.name, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(
                progress = { item.percentage / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = categoryColor,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "€${String.format("%.2f", item.total)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "${String.format("%.0f", item.percentage)}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
@Composable
fun RecentExpenseRow(expense: Expense) {
    val dateFormat = remember { SimpleDateFormat("MMM dd", Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(expense.merchant, style = MaterialTheme.typography.bodyMedium)
                if (expense.isManualEntry) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("✏️", fontSize = 12.sp)
                }
            }
            Text(
                dateFormat.format(Date(expense.date)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "€${String.format("%.2f", expense.amount)}",
            fontWeight = FontWeight.Bold
        )
    }
}
@Composable
fun BudgetSummaryWidget(onTrack: Int, warning: Int, exceeded: Int, summary: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Budget Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                StatusBadge("On Track", onTrack, Color(0xFF4CAF50))
                StatusBadge("Warning", warning, Color(0xFFFFC107))
                StatusBadge("Exceeded", exceeded, Color(0xFFFF5722))
            }
            if (summary != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (exceeded > 0) Color(0xFFFF5722) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
@Composable
fun StatusBadge(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape).background(color.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Text(count.toString(), fontWeight = FontWeight.Bold, color = color)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPlannedExpenseDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Double, Long, Long?, PlannedExpensePriority) -> Unit
) {
    var description by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(PlannedExpensePriority.LIKELY) }
    var date by remember { mutableStateOf(System.currentTimeMillis()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                "PLAN AN EXPENSE", 
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = SemanticColors.PrimaryIndigo
            ) 
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("What are you planning?") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SemanticColors.PrimaryIndigo,
                        unfocusedBorderColor = SemanticColors.GlassBorder
                    )
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount (€)") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SemanticColors.PrimaryIndigo,
                        unfocusedBorderColor = SemanticColors.GlassBorder
                    )
                )
                Column {
                    Text(
                        "PRIORITY", 
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.TextSecondary,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PlannedExpensePriority.values().forEach { p ->
                            FilterChip(
                                selected = priority == p,
                                onClick = { priority = p },
                                label = { Text(p.name) }
                            )
                        }
                    }
                }
                // Date Selector
                DateSelector(
                    dateMs = date,
                    onDateSelected = { date = it }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amt = amount.toDoubleOrNull() ?: 0.0
                    if (description.isNotBlank() && amt > 0) {
                        onConfirm(description, amt, date, null, priority)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = SemanticColors.PrimaryIndigo)
            ) {
                Text("ADD TO FORECAST")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = SemanticColors.TextSecondary)
            }
        },
        containerColor = SemanticColors.BaseNavy,
        shape = RoundedCornerShape(28.dp)
    )
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateSelector(
    dateMs: Long,
    onDateSelected: (Long) -> Unit
) {
    val dateFormat = remember { java.text.SimpleDateFormat("EEE, dd MMM yyyy", java.util.Locale.getDefault()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = dateMs
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDatePicker = true }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.DateRange,
            contentDescription = "Date",
            tint = SemanticColors.PrimaryIndigo
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                "Date",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = SemanticColors.TextSecondary
            )
            Text(
                dateFormat.format(java.util.Date(dateMs)),
                style = MaterialTheme.typography.bodyMedium,
                color = SemanticColors.TextPrimary
            )
        }
    }
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selectedDate ->
                            // Preserve time of day (roughly, or just set to noon to avoid timezone issues/start of day)
                            // Here we just use the selected date (which is usually UTC midnight) + current time offset if needed?
                            // Material3 DatePicker returns UTC start of day. 
                            // Let's just use it as is, or add current time component if we cared about exact time.
                            // For forecast, date is most important.
                            onDateSelected(selectedDate)
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("OK", color = SemanticColors.PrimaryIndigo)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel", color = SemanticColors.TextSecondary)
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\home\HomeViewModel.kt <a name="mainjavacomyournameexpensetrackeruiscreenshomehomeviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.home
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.data.repository.DashboardRepository
import com.yourname.expensetracker.data.database.model.DashboardWidgetConfig
import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.data.repository.FinancialWeatherRepository
import com.yourname.expensetracker.data.repository.FinancialWeather
import com.yourname.expensetracker.data.repository.WeatherState
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.data.repository.PlannedExpenseRepository
import com.yourname.expensetracker.data.database.entity.PlannedExpensePriority
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject
// === State Widget sealed class for Bento Grid ===
sealed class DashboardWidget {
    data class SafeToSpend(
        val amount: Double,
        val totalBudget: Double?,
        val daysRemaining: Int
    ) : DashboardWidget()
    data class SpendingPaceWidget(
        val pace: SpendingPace
    ) : DashboardWidget()
    data class PendingReviewAlert(
        val count: Int
    ) : DashboardWidget()
    data class PeriodSummary(
        val todaySpent: Double,
        val weekSpent: Double,
        val monthSpent: Double
    ) : DashboardWidget()
    data class TopCategories(
        val categories: List<CategorySpending>
    ) : DashboardWidget()
    data class BudgetHealthWidget(
        val statuses: List<BudgetStatus>,
        val summary: String?
    ) : DashboardWidget()
    data class RecentTransactions(
        val expenses: List<Expense>
    ) : DashboardWidget()
    data class NaturalLanguageInsight(
        val text: String,
        val icon: String
    ) : DashboardWidget()
    data class SpendingTrend(
        val currentMonthData: List<Float>,
        val previousMonthData: List<Float>
    ) : DashboardWidget()
    data class FinancialWeatherWidget(
        val weather: FinancialWeather
    ) : DashboardWidget()
}
data class CategorySpending(
    val category: Category,
    val total: Double,
    val percentage: Float
)
data class DashboardState(
    val widgets: List<DashboardWidget> = emptyList(),
    val totalSpent: Double = 0.0,
    val transactionCount: Int = 0,
    val isServiceRunning: Boolean = true, // For pulse dot
    val isEditMode: Boolean = false,
    val isLoading: Boolean = true
)
@OptIn(kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val dashboardRepository: DashboardRepository,
    private val insightsEngine: InsightsEngine,
    private val financialWeatherRepository: FinancialWeatherRepository,
    private val plannedExpenseRepository: PlannedExpenseRepository
) : ViewModel() {
    private val isEditMode = MutableStateFlow(false)
    // distinct intermediate flow for data to avoid 5-arg limit
    init {
        // Recover from destructive migration items if needed
        viewModelScope.launch {
            categoryRepository.ensureDefaultCategories()
        }
    }
    private val dataFlow = combine(
        repository.getAllExpenses().catch { emit(emptyList()) },
        categoryRepository.allCategories.catch { emit(emptyList()) },
        budgetRepository.getBudgetStatuses().catch { emit(emptyList()) },
        repository.getPendingReviewCount().catch { emit(0) },
        financialWeatherRepository.getFinancialWeather().catch { 
            // Return a default "Unknown" state if the weather engine fails to prevent stalling the dashboard
            emit(FinancialWeather(
                state = WeatherState.UNKNOWN,
                headline = "Weather Unavailable",
                summary = "We couldn't calculate your financial outlook right now.",
                icon = "❓",
                riskLevel = 0,
                totalCommitted = 0.0,
                totalLikely = 0.0,
                predictedDiscretionary = 0.0,
                discretionaryBudget = 0.0
            ))
        }
    ) { expenses, categories, budgetStatuses, pendingCount, weather ->
        FiveData(expenses, categories, budgetStatuses, pendingCount, weather)
    }
    .debounce(300)
    // Optimized: Process heavy data separately and cache the result
    private val processedDataFlow = dataFlow.map { data ->
        val (expenses, categories, budgetStatuses, pendingCount, weather) = data
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        // Time boundaries
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val daysToMonday = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
        val weekStart = cal.timeInMillis - (daysToMonday * 86400000L)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val monthStart = cal.timeInMillis
        // Single-pass aggregation
        var totalSpent = 0.0
        var monthSpent = 0.0
        var weekSpent = 0.0
        var todaySpent = 0.0
        var previousMonthTotal = 0.0
        val categoryTotalsMap = mutableMapOf<Long, Double>()
        val purchases = ArrayList<Expense>(expenses.size)
        val previousMonthStart = insightsEngine.getMonthPeriod(now, -1).startMs
        val previousMonthEnd = monthStart
        for (expense in expenses) {
            if (expense.transactionType != TransactionType.PURCHASE) continue
            val amount = expense.amount
            val date = expense.date
            purchases.add(expense)
            totalSpent += amount
            if (date >= monthStart) {
                monthSpent += amount
            } else if (date >= previousMonthStart && date < previousMonthEnd) {
                previousMonthTotal += amount
            }
            if (date >= weekStart) {
                weekSpent += amount
                if (date >= todayStart) {
                    todaySpent += amount
                }
            }
            expense.categoryId?.let { catId ->
                categoryTotalsMap[catId] = (categoryTotalsMap[catId] ?: 0.0) + amount
            }
        }
        val categoryMap = categories.associateBy { it.id }
        val txCount = purchases.size
        // Days remaining in month
        val daysInMonth = Calendar.getInstance().getActualMaximum(Calendar.DAY_OF_MONTH)
        val dayOfMonth = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        val daysRemaining = daysInMonth - dayOfMonth
        // Overall budget (if set)
        val overallBudget = budgetStatuses.find { it.budget.categoryId == null }
        val safeToSpend = weather.discretionaryBudget 
        // Finalize Category totals
        val categoryTotals = categoryTotalsMap.entries
            .mapNotNull { (catId, catTotal) ->
                val cat = catId.let { categoryMap[it] } ?: return@mapNotNull null
                CategorySpending(
                    category = cat,
                    total = catTotal,
                    percentage = if (totalSpent > 0) (catTotal / totalSpent * 100).toFloat() else 0f
                )
            }
            .sortedByDescending { it.total }
        val baseline = overallBudget?.budget?.amount ?: if (previousMonthTotal > 0) previousMonthTotal else null
        // Handle Day 1 Noise (LOG-005 Fix)
        // If it's the first day, we use the average of (baseline / daysInMonth) and current monthSpent 
        // to avoid extreme swings if a user makes a big purchase on Day 1.
        val dayOfMonthCoerced = dayOfMonth.coerceAtLeast(1)
        val projectedTotal = if (dayOfMonth == 1) {
            // Weighted average on day 1: 70% baseline, 30% current spend extrapolated
            if (baseline != null) (baseline * 0.7) + (monthSpent * 0.3 * daysInMonth)
            else monthSpent * daysInMonth
        } else {
            monthSpent * daysInMonth.toDouble() / dayOfMonth
        }
        // Validated Pace Logic: Handle dayOfMonth=1 or 0 gracefully
        val pacePercentage = if (baseline != null && baseline > 0) {
            val expected = baseline * dayOfMonthCoerced / daysInMonth
            val calculated = (monthSpent / expected * 100).toFloat()
            // Dampen day 1 pace
            if (dayOfMonth == 1) {
                if (calculated > 110f) 110f else if (calculated < 90f) 90f else calculated
            } else {
                if (calculated.isFinite()) calculated else 0f
            }
        } else 0f
        val pace = SpendingPace(
            currentMonthSpent = monthSpent,
            daysElapsed = dayOfMonth,
            daysInMonth = daysInMonth,
            projectedTotal = projectedTotal,
            previousMonthTotal = if (previousMonthTotal > 0) previousMonthTotal else null,
            averageMonthlyTotal = null,
            pacePercentage = pacePercentage,
            paceStatus = when {
                baseline == null || baseline <= 0 -> PaceStatus.NO_BASELINE
                pacePercentage < 90f -> PaceStatus.UNDER_PACE
                pacePercentage > 110f -> PaceStatus.OVER_PACE
                else -> PaceStatus.ON_PACE
            }
        )
        // Cumulative Spend Trend Data - Optimized single pass
        val calInstance = Calendar.getInstance()
        val previousMonthDays = calInstance.apply { 
            timeInMillis = previousMonthStart 
        }.getActualMaximum(Calendar.DAY_OF_MONTH)
        val currentAmountByDay = DoubleArray(dayOfMonth + 1)
        val previousAmountByDay = DoubleArray(previousMonthDays + 1)
        for (expense in purchases) {
            if (expense.date >= monthStart) {
                calInstance.timeInMillis = expense.date
                val day = calInstance.get(Calendar.DAY_OF_MONTH)
                if (day <= dayOfMonth) currentAmountByDay[day] += expense.amount
            } else if (expense.date >= previousMonthStart && expense.date < monthStart) {
                calInstance.timeInMillis = expense.date
                val day = calInstance.get(Calendar.DAY_OF_MONTH)
                if (day <= previousMonthDays) previousAmountByDay[day] += expense.amount
            }
        }
        var runningTotalCur = 0.0
        val currentMonthDaily = (1..dayOfMonth).map { day ->
            runningTotalCur += currentAmountByDay[day]
            runningTotalCur.toFloat()
        }
        var runningTotalPrev = 0.0
        val previousMonthDaily = (1..previousMonthDays).map { day ->
            runningTotalPrev += previousAmountByDay[day]
            runningTotalPrev.toFloat()
        }
        val trend = DashboardWidget.SpendingTrend(
            currentMonthData = currentMonthDaily,
            previousMonthData = previousMonthDaily
        )
        // Natural language insight
        val insightText = buildNaturalLanguageInsight(
            monthSpent, previousMonthTotal, todaySpent, purchases.size
        )
        // Budget summary
        val exceeded = budgetStatuses.count { it.healthStatus == BudgetHealthStatus.EXCEEDED }
        val budgetSummary = if (budgetStatuses.isNotEmpty()) {
            if (exceeded > 0) "$exceeded budgets exceeded!" else "All budgets on track"
        } else null
        // === Build widget list ===
        val widgets = mutableListOf<DashboardWidget>()
        // Financial Weather (Always added, visibility controlled by config)
        widgets.add(DashboardWidget.FinancialWeatherWidget(weather))
        // Hero: Safe-to-Spend (or total spent if no overall budget)
        widgets.add(
            DashboardWidget.SafeToSpend(
                amount = if (overallBudget != null) safeToSpend else monthSpent,
                totalBudget = overallBudget?.budget?.amount,
                daysRemaining = daysRemaining
            )
        )
        // Spending Pace
        if (pace.paceStatus != PaceStatus.NO_BASELINE) {
            widgets.add(DashboardWidget.SpendingPaceWidget(pace))
        }
        // Spending Trend
        widgets.add(trend)
        // Pending Review Alert
        if (pendingCount > 0) {
            widgets.add(DashboardWidget.PendingReviewAlert(pendingCount))
        }
        // Natural language insight
        if (insightText != null) {
            widgets.add(DashboardWidget.NaturalLanguageInsight(insightText.first, insightText.second))
        }
        // Period summary
        widgets.add(DashboardWidget.PeriodSummary(todaySpent, weekSpent, monthSpent))
        // Budget health
        if (budgetStatuses.isNotEmpty()) {
            widgets.add(DashboardWidget.BudgetHealthWidget(budgetStatuses, budgetSummary))
        }
        // Top categories
        if (categoryTotals.isNotEmpty()) {
            widgets.add(DashboardWidget.TopCategories(categoryTotals.take(5)))
        }
        // Recent transactions
        if (purchases.isNotEmpty()) {
            widgets.add(DashboardWidget.RecentTransactions(purchases.take(5)))
        }
        CompiledDashboardData(
            allWidgets = widgets,
            totalSpent = totalSpent,
            txCount = txCount
        )
    }
    .flowOn(Dispatchers.Default) // Compuation on BG thread
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CompiledDashboardData(emptyList(), 0.0, 0)) // Cache results
    val dashboard: StateFlow<DashboardState> = combine(
        processedDataFlow,
        isEditMode,
        dashboardRepository.configFlow
    ) { compiledData, editMode, configList ->
        // === Apply Custom Layout ===
        val sortedWidgets = configList
            .filter { it.isVisible || editMode } // Show all in edit mode, otherwise filter
            .mapNotNull { conf ->
                compiledData.allWidgets.find { w -> getWidgetId(w) == conf.id }
            }
        DashboardState(
            widgets = sortedWidgets,
            totalSpent = compiledData.totalSpent,
            transactionCount = compiledData.txCount,
            isEditMode = editMode,
            isLoading = false
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardState())
    fun toggleEditMode() {
        isEditMode.value = !isEditMode.value
    }
    fun moveWidget(widgetId: String, moveUp: Boolean) {
        val currentConfig = dashboardRepository.getDashboardConfig().toMutableList()
        val index = currentConfig.indexOfFirst { it.id == widgetId }
        if (index == -1) return
        val newIndex = if (moveUp) index - 1 else index + 1
        if (newIndex !in currentConfig.indices) return
        val temp = currentConfig[index]
        currentConfig[index] = currentConfig[newIndex].copy(order = index)
        currentConfig[newIndex] = temp.copy(order = newIndex)
        dashboardRepository.saveDashboardConfig(currentConfig.sortedBy { it.order })
        // Trigger recomposition by refreshing dashboard flow (implicitly via combining with a triggered state if needed)
        // Here we can just nudge the isEditMode or use a dedicated Refresh trigger
        // isEditMode.value = isEditMode.value 
    }
    fun toggleWidgetVisibility(widgetId: String) {
        val currentConfig = dashboardRepository.getDashboardConfig().map {
            if (it.id == widgetId) it.copy(isVisible = !it.isVisible) else it
        }
        dashboardRepository.saveDashboardConfig(currentConfig)
        // isEditMode.value = isEditMode.value
    }
    private fun getWidgetId(widget: DashboardWidget): String = when (widget) {
        is DashboardWidget.SafeToSpend -> "safe_to_spend"
        is DashboardWidget.SpendingPaceWidget -> "spending_pace"
        is DashboardWidget.PendingReviewAlert -> "review_alert"
        is DashboardWidget.SpendingTrend -> "spending_trend"
        is DashboardWidget.NaturalLanguageInsight -> "insight"
        is DashboardWidget.PeriodSummary -> "period_summary"
        is DashboardWidget.BudgetHealthWidget -> "budget_health"
        is DashboardWidget.TopCategories -> "top_categories"
        is DashboardWidget.RecentTransactions -> "recent_transactions"
        is DashboardWidget.FinancialWeatherWidget -> "financial_weather"
    }
    private fun buildNaturalLanguageInsight(
        monthSpent: Double,
        previousMonthTotal: Double,
        todaySpent: Double,
        txCount: Int
    ): Pair<String, String>? {
        if (previousMonthTotal > 0) {
            val diff = monthSpent - previousMonthTotal
            return if (diff < 0) {
                Pair(
                    "You've spent €${String.format("%.0f", -diff)} less than last month so far.",
                    "📉"
                )
            } else if (diff > previousMonthTotal * 0.2) {
                Pair(
                    "Spending is €${String.format("%.0f", diff)} higher than last month.",
                    "📈"
                )
            } else null
        }
        if (txCount > 0 && todaySpent > 0) {
            return Pair(
                "You've spent €${String.format("%.2f", todaySpent)} today across $txCount transactions.",
                "💡"
            )
        }
        return null
    }
    fun addPlannedExpense(
        description: String,
        amount: Double,
        date: Long,
        categoryId: Long?,
        priority: PlannedExpensePriority
    ) {
        viewModelScope.launch {
            plannedExpenseRepository.addPlannedExpense(
                PlannedExpense(
                    description = description,
                    amount = amount,
                    date = date,
                    categoryId = categoryId,
                    priority = priority
                )
            )
        }
    }
}
data class FiveData(
    val expenses: List<Expense>,
    val categories: List<Category>,
    val budgetStatuses: List<BudgetStatus>,
    val pendingCount: Int,
    val weather: FinancialWeather
)
data class CompiledDashboardData(
    val allWidgets: List<DashboardWidget>,
    val totalSpent: Double,
    val txCount: Int
)

```

---

## main\java\com\yourname\expensetracker\ui\screens\receiptscan\ReceiptScanScreen.kt <a name="mainjavacomyournameexpensetrackeruiscreensreceiptscanreceiptscanscreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.receiptscan
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.ui.screens.addexpense.CategoryGrid
import com.yourname.expensetracker.ui.screens.addexpense.DateSelector
import com.yourname.expensetracker.ui.screens.addexpense.PaymentMethodChip
import kotlinx.coroutines.delay
import java.util.Currency
private fun getCurrencySymbol(currencyCode: String?): String {
    return try { Currency.getInstance(currencyCode ?: "EUR").symbol } catch(e: Exception) { "€" }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptScanScreen(
    onDismiss: () -> Unit,
    viewModel: ReceiptScanViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    // Camera launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) viewModel.processPhoto()
    }
    // Gallery launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.processGalleryImage(it) }
    }
    // Camera permission launcher
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val uri = viewModel.createTempPhotoUri()
            cameraLauncher.launch(uri)
        }
    }
    // Handle done step - auto-dismiss
    LaunchedEffect(state.step) {
        if (state.step == ScanStep.DONE) {
            delay(1500)
            onDismiss()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (state.step) {
                            ScanStep.CAPTURE -> "Scan Receipt"
                            ScanStep.PROCESSING -> "Processing..."
                            ScanStep.REVIEW -> "Review & Save"
                            ScanStep.DONE -> "Saved!"
                            ScanStep.ERROR -> "Error"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.reset()
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Close, "Close")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (state.step) {
                ScanStep.CAPTURE -> CaptureStep(
                    imageUri = state.imageUri,
                    onCameraClick = {
                        val hasCameraPermission = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasCameraPermission) {
                            val uri = viewModel.createTempPhotoUri()
                            cameraLauncher.launch(uri)
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    onGalleryClick = {
                        galleryLauncher.launch("image/*")
                    }
                )
                ScanStep.PROCESSING -> ProcessingStep()
                ScanStep.REVIEW -> ReviewStep(
                    state = state,
                    categories = categories,
                    viewModel = viewModel
                )
                ScanStep.DONE -> DoneStep()
                ScanStep.ERROR -> ErrorStep(
                    errorMessage = state.errorMessage ?: "Unknown error",
                    onRetry = { viewModel.retry() }
                )
            }
        }
    }
}
@Composable
private fun CaptureStep(
    imageUri: Uri?,
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit
) {
    Spacer(modifier = Modifier.height(32.dp))
    // Image preview area
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            if (imageUri != null) {
                AsyncImage(
                    model = imageUri,
                    contentDescription = "Receipt preview",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Fit
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("🧾", fontSize = 64.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Take a photo or select from gallery",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(24.dp))
    // Action buttons
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onCameraClick,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("📷 Camera")
        }
        OutlinedButton(
            onClick = onGalleryClick,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("🖼️ Gallery")
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
    // Tips
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "📌 Tips for best results:",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text("• Place receipt on a flat, dark surface", style = MaterialTheme.typography.bodySmall)
            Text("• Ensure good lighting with no shadows", style = MaterialTheme.typography.bodySmall)
            Text("• Capture the entire receipt in frame", style = MaterialTheme.typography.bodySmall)
            Text("• Keep the camera steady", style = MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable
private fun ProcessingStep() {
    Spacer(modifier = Modifier.height(80.dp))
    CircularProgressIndicator(
        modifier = Modifier.size(64.dp),
        strokeWidth = 4.dp
    )
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        "Scanning receipt...",
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "Reading text and extracting details",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewStep(
    state: ReceiptScanState,
    categories: List<Category>,
    viewModel: ReceiptScanViewModel
) {
    val parsed = state.parsedReceipt
    // Image preview (small)
    if (state.imageUri != null) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            AsyncImage(
                model = state.imageUri,
                contentDescription = "Receipt",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
    // Confidence indicator
    ConfidenceIndicator(confidence = state.ocrConfidence)
    Spacer(modifier = Modifier.height(16.dp))
    // Merchant
    OutlinedTextField(
        value = state.editMerchant,
        onValueChange = { viewModel.updateMerchant(it) },
        label = { Text("Merchant") },
        placeholder = { Text("Store name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(12.dp))
    // Amount
    OutlinedTextField(
        value = state.editAmount,
        onValueChange = { viewModel.updateAmount(it) },
        label = { Text("Total Amount") },
        leadingIcon = { 
            Text(getCurrencySymbol(parsed?.currency), fontSize = 18.sp, fontWeight = FontWeight.Bold) 
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(12.dp))
    // Date
    DateSelector(
        dateMs = state.editDate,
        onDateSelected = { viewModel.updateDate(it) }
    )
    Spacer(modifier = Modifier.height(12.dp))
    // Payment Method
    Text(
        "Payment Method",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium
    )
    Spacer(modifier = Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PaymentMethodChip(
            label = "💳 Card",
            selected = state.paymentMethod == PaymentMethod.CARD,
            onClick = { viewModel.selectPaymentMethod(PaymentMethod.CARD) },
            modifier = Modifier.weight(1f)
        )
        PaymentMethodChip(
            label = "💵 Cash",
            selected = state.paymentMethod == PaymentMethod.CASH,
            onClick = { viewModel.selectPaymentMethod(PaymentMethod.CASH) },
            modifier = Modifier.weight(1f)
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
    // Category
    Text(
        "Category",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium
    )
    Spacer(modifier = Modifier.height(4.dp))
    CategoryGrid(
        categories = categories,
        selectedId = state.selectedCategoryId,
        onSelect = { viewModel.selectCategory(it) }
    )
    // Line items preview
    if (parsed?.lineItems?.isNotEmpty() == true) {
        Spacer(modifier = Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Detected Items (${parsed.lineItems.size})",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                parsed.lineItems.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            item.description,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "${getCurrencySymbol(parsed.currency)}${String.format("%.2f", item.totalPrice)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (index < parsed.lineItems.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 2.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    }
                }
                // Tax if detected
                parsed.tax?.let { tax ->
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Tax/VAT",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "${getCurrencySymbol(parsed.currency)}${String.format("%.2f", tax)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
    // Notes
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        value = state.notes,
        onValueChange = { viewModel.updateNotes(it) },
        label = { Text("Notes (optional)") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 1,
        maxLines = 3
    )
    // Raw OCR toggle
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { viewModel.toggleRawText() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Raw OCR Text",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Icon(
            if (state.showRawText) Icons.Default.KeyboardArrowUp
            else Icons.Default.KeyboardArrowDown,
            contentDescription = "Toggle",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    AnimatedVisibility(visible = state.showRawText) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        ) {
            Text(
                text = state.rawOcrText.ifBlank { "No text detected" },
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }
    }
    // Error messages
    state.errorMessage?.let { error ->
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "⚠️ $error",
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    when (state.saveResult) {
        is SaveReceiptResult.Duplicate -> {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "⚠️ A similar transaction already exists",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        is SaveReceiptResult.Error -> {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "❌ ${(state.saveResult as SaveReceiptResult.Error).message}",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        else -> {}
    }
    Spacer(modifier = Modifier.height(16.dp))
    // Save button
    Button(
        onClick = { viewModel.saveExpense() },
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        enabled = !state.isSaving,
        shape = RoundedCornerShape(12.dp)
    ) {
        if (state.isSaving) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp
            )
        } else {
            Text("💾 Save Expense", fontSize = 16.sp)
        }
    }
    Spacer(modifier = Modifier.height(32.dp))
}
@Composable
private fun ConfidenceIndicator(confidence: Float) {
    val percentage = (confidence * 100).toInt()
    val color = when {
        confidence >= 0.7f -> Color(0xFF4CAF50)
        confidence >= 0.4f -> Color(0xFFFFC107)
        else -> Color(0xFFFF5722)
    }
    val label = when {
        confidence >= 0.7f -> "High confidence"
        confidence >= 0.4f -> "Medium confidence"
        else -> "Low confidence - please verify"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            "$label ($percentage%)",
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}
@Composable
private fun DoneStep() {
    Spacer(modifier = Modifier.height(80.dp))
    Text("✅", fontSize = 72.sp)
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        "Expense saved!",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "Your receipt has been processed and saved.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
@Composable
private fun ErrorStep(
    errorMessage: String,
    onRetry: () -> Unit
) {
    Spacer(modifier = Modifier.height(80.dp))
    Text("❌", fontSize = 64.sp)
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        "Something went wrong",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        errorMessage,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error
    )
    Spacer(modifier = Modifier.height(24.dp))
    Button(
        onClick = onRetry,
        shape = RoundedCornerShape(12.dp)
    ) {
        Text("🔄 Try Again")
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\receiptscan\ReceiptScanViewModel.kt <a name="mainjavacomyournameexpensetrackeruiscreensreceiptscanreceiptscanviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.receiptscan
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
enum class ScanStep {
    CAPTURE,
    PROCESSING,
    REVIEW,
    DONE,
    ERROR
}
data class ReceiptScanState(
    val step: ScanStep = ScanStep.CAPTURE,
    val imageUri: Uri? = null,
    val tempCameraUri: Uri? = null,
    val parsedReceipt: ReceiptParser.ParsedReceipt? = null,
    val receiptId: Long? = null,
    val rawOcrText: String = "",
    val showRawText: Boolean = false,
    // Editable fields
    val editMerchant: String = "",
    val editAmount: String = "",
    val editDate: Long = System.currentTimeMillis(),
    val selectedCategoryId: Long? = null,
    val paymentMethod: PaymentMethod = PaymentMethod.CARD,
    val notes: String = "",
    // Meta
    val ocrConfidence: Float = 0f,
    val errorMessage: String? = null,
    val isSaving: Boolean = false,
    val saveResult: SaveReceiptResult? = null
)
sealed class SaveReceiptResult {
    data object Success : SaveReceiptResult()
    data object Duplicate : SaveReceiptResult()
    data class Error(val message: String) : SaveReceiptResult()
}
@HiltViewModel
class ReceiptScanViewModel @Inject constructor(
    private val receiptRepository: ReceiptRepository,
    private val categoryRepository: CategoryRepository,
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle
) : ViewModel() {
    private val _state = MutableStateFlow(ReceiptScanState(
        tempCameraUri = savedStateHandle.get<Uri>("temp_uri")
    ))
    val state: StateFlow<ReceiptScanState> = _state.asStateFlow()
    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    /**
     * Create a URI for camera to write photo to
     */
    fun createTempPhotoUri(): Uri {
        val uri = receiptRepository.createTempPhotoUri()
        savedStateHandle["temp_uri"] = uri
        _state.update { it.copy(tempCameraUri = uri) }
        return uri
    }
    /**
     * Called after camera successfully captures a photo
     */
    fun processPhoto() {
        val uri = _state.value.tempCameraUri ?: return
        processImageUri(uri)
    }
    /**
     * Called when user selects image from gallery
     */
    fun processGalleryImage(uri: Uri) {
        processImageUri(uri)
    }
    private fun processImageUri(uri: Uri) {
        _state.update {
            it.copy(
                step = ScanStep.PROCESSING,
                imageUri = uri,
                errorMessage = null
            )
        }
        viewModelScope.launch {
            try {
                // Manual scans do NOT auto-create review items (User confirms in this UI)
                val (receipt, parsed) = receiptRepository.processReceipt(uri, autoCreateReview = false)
                _state.update {
                    it.copy(
                        step = ScanStep.REVIEW,
                        imageUri = Uri.fromFile(java.io.File(receipt.imagePath)),
                        parsedReceipt = parsed,
                        receiptId = receipt.id,
                        rawOcrText = receipt.rawOcrText,
                        editMerchant = parsed.merchantName ?: "",
                        editAmount = parsed.total?.let { total ->
                            String.format("%.2f", total)
                        } ?: "",
                        editDate = parsed.date ?: System.currentTimeMillis(),
                        ocrConfidence = parsed.confidence,
                        selectedCategoryId = null // Will be auto-detected on save
                    )
                }
            } catch (e: Exception) {
                try {
                    val (receipt, parsed) = receiptRepository.saveManualReceiptRecord(uri)
                    _state.update {
                        it.copy(
                            step = ScanStep.REVIEW,
                            imageUri = uri,
                            parsedReceipt = parsed,
                            receiptId = receipt.id,
                            errorMessage = "OCR Failed: ${e.message}. You can enter details manually."
                        )
                    }
                } catch (fallbackError: Exception) {
                    _state.update {
                        it.copy(
                            step = ScanStep.ERROR,
                            errorMessage = "Total failure: ${fallbackError.message}"
                        )
                    }
                }
            }
        }
    }
    fun updateMerchant(value: String) {
        _state.update { it.copy(editMerchant = value) }
    }
    fun updateAmount(value: String) {
        val filtered = value.filter { it.isDigit() || it == '.' || it == ',' }
        _state.update { it.copy(editAmount = filtered) }
    }
    fun updateDate(dateMs: Long) {
        _state.update { it.copy(editDate = dateMs) }
    }
    fun selectCategory(categoryId: Long) {
        _state.update { it.copy(selectedCategoryId = categoryId) }
    }
    fun selectPaymentMethod(method: PaymentMethod) {
        _state.update { it.copy(paymentMethod = method) }
    }
    fun updateNotes(value: String) {
        _state.update { it.copy(notes = value) }
    }
    fun toggleRawText() {
        _state.update { it.copy(showRawText = !it.showRawText) }
    }
    fun saveExpense() {
        val currentState = _state.value
        // Validate
        val merchant = currentState.editMerchant.trim()
        if (merchant.isBlank()) {
            _state.update {
                it.copy(errorMessage = "Merchant name is required")
            }
            return
        }
        val amount = currentState.editAmount.replace(",", ".").toDoubleOrNull()
        if (amount == null || amount <= 0) {
            _state.update {
                it.copy(errorMessage = "Enter a valid amount")
            }
            return
        }
        val receiptId = currentState.receiptId ?: return
        _state.update { it.copy(isSaving = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val result = receiptRepository.createExpenseFromReceipt(
                    receiptId = receiptId,
                    merchant = merchant,
                    amount = amount,
                    currency = "EUR",
                    categoryId = currentState.selectedCategoryId,
                    date = currentState.editDate,
                    paymentMethod = currentState.paymentMethod,
                    notes = currentState.notes.takeIf { it.isNotBlank() }
                )
                if (result == -1L) {
                    _state.update {
                        it.copy(
                            isSaving = false,
                            saveResult = SaveReceiptResult.Duplicate
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            isSaving = false,
                            step = ScanStep.DONE,
                            saveResult = SaveReceiptResult.Success
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isSaving = false,
                        saveResult = SaveReceiptResult.Error(
                            e.message ?: "Unknown error"
                        )
                    )
                }
            }
        }
    }
    fun retry() {
        _state.update {
            ReceiptScanState()  // Reset to initial state
        }
    }
    fun reset() {
        _state.update { ReceiptScanState() }
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\recurring\RecurringExpensesScreen.kt <a name="mainjavacomyournameexpensetrackeruiscreensrecurringrecurringexpensesscreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.recurring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.dao.RecurringExpenseDao
import com.yourname.expensetracker.data.repository.FinancialWeatherRepository
import com.yourname.expensetracker.domain.model.RecurringPattern
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import javax.inject.Inject
@HiltViewModel
class RecurringExpensesViewModel @Inject constructor(
    private val repository: FinancialWeatherRepository,
    private val recurringExpenseDao: RecurringExpenseDao,
    private val plannedExpenseDao: com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
) : ViewModel() {
    // Helper flow to trigger updates
    private val refreshTrigger = MutableStateFlow(0)
    val patterns: StateFlow<List<RecurringPattern>> = combine(
        repository.getFinancialWeather(), // This already emits on db changes if set up correctly, but let's see
        refreshTrigger
    ) { weather, _ ->
        // We actually need the full list, not just upcomingBills from weather.
        // But repository exposes upcomingBills via weather. 
        // Ideally we expose all patterns separately.
        // For now, let's assume we add a method to Repo or use Engine directly if needed.
        // To be simpler, let's expose patterns from Repo.
        emptyList<RecurringPattern>() // Placeholder until Repo updated
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    // Better approach: Expose patterns flow from Repository
    val allPatterns = repository.getAllRecurringPatterns()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val plannedExpenses = repository.getAllPlannedExpenses()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun deleteManualRule(pattern: RecurringPattern) {
        viewModelScope.launch {
            if (pattern.id != null) {
                recurringExpenseDao.deleteById(pattern.id)
                refreshTrigger.value += 1
            } else {
                // Legacy fallback: Delete by merchant name if ID is missing (e.g. old local data)
                val rules = recurringExpenseDao.getAll()
                val rule = rules.find { it.merchant == pattern.merchantName }
                if (rule != null) {
                    recurringExpenseDao.delete(rule)
                    refreshTrigger.value += 1
                }
            }
        }
    }
    fun deletePlannedExpense(planned: com.yourname.expensetracker.domain.model.PlannedExpense) {
        viewModelScope.launch {
            plannedExpenseDao.deletePlannedExpenseById(planned.id)
            refreshTrigger.value += 1
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringExpensesScreen(
    onNavigateBack: () -> Unit,
    viewModel: RecurringExpensesViewModel = hiltViewModel()
) {
    val patterns by viewModel.allPatterns.collectAsState()
    val planned by viewModel.plannedExpenses.collectAsState()
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("Recurring", "Planned")
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Upcoming") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title) }
                    )
                }
            }
            if (selectedTabIndex == 0) {
                // Recurring Tab
                if (patterns.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No recurring expenses found.")
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(patterns) { pattern ->
                            RecurringExpenseItem(
                                pattern = pattern,
                                onDelete = { viewModel.deleteManualRule(pattern) }
                            )
                        }
                    }
                }
            } else {
                // Planned Tab
                if (planned.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No planned expenses found.")
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(planned) { item ->
                            PlannedExpenseItem(
                                expense = item,
                                onDelete = { viewModel.deletePlannedExpense(item) }
                            )
                        }
                    }
                }
            }
        }
    }
}
@Composable
fun PlannedExpenseItem(
    expense: com.yourname.expensetracker.domain.model.PlannedExpense,
    onDelete: () -> Unit
) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = expense.description,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "€${String.format("%.2f", expense.amount)} • ${expense.priority.name.lowercase().capitalize()}",
                    style = MaterialTheme.typography.bodyMedium
                )
                val dateFormat = remember { DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.getDefault()) }
                Text(
                    text = "Date: ${dateFormat.format(Instant.ofEpochMilli(expense.date).atZone(ZoneId.systemDefault()))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete, 
                    contentDescription = "Delete Planned",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
@Composable
fun RecurringExpenseItem(
    pattern: RecurringPattern,
    onDelete: () -> Unit
) {
    val isManual = pattern.id != null || pattern.confidence >= 0.99f
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pattern.merchantName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${String.format("%.2f", pattern.averageAmount)} ${pattern.currency} • ${pattern.frequency.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.bodyMedium
                )
                val dateFormat = remember { DateTimeFormatter.ofPattern("MMM dd", Locale.getDefault()) }
                Text(
                    text = "Next: ${dateFormat.format(Instant.ofEpochMilli(pattern.nextExpectedDate).atZone(ZoneId.systemDefault()))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isManual) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete, 
                        contentDescription = "Delete Rule",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                SuggestionChip(
                    onClick = { /* TODO: Confirm/Convert to Manual */ },
                    label = { Text("Detected") }
                )
            }
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\review\ReviewScreen.kt <a name="mainjavacomyournameexpensetrackeruiscreensreviewreviewscreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.review
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.ui.components.AmountText
import com.yourname.expensetracker.ui.theme.SemanticColors
import com.yourname.expensetracker.ui.util.HapticType
import com.yourname.expensetracker.ui.util.rememberHapticFeedback
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId
import java.util.*
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import coil.compose.AsyncImage
import java.io.File
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel = hiltViewModel()
) {
    val pendingReviews by viewModel.pendingReviews.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    var editingReview by remember { mutableStateOf<PendingReview?>(null) }
    // Guard against double-swipes/rapid-fire actions
    val processingIds = remember { mutableStateListOf<Long>() }
    val haptic = rememberHapticFeedback()
    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage by viewModel.errorMessage.collectAsState()
    val isBatchProcessing by viewModel.isBatchProcessing.collectAsState()
    val batchProgress by viewModel.batchProgress.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    var showDebugMenu by remember { mutableStateOf(false) }
    val batchLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50)
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.processBatch(uris)
        }
    }
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }
    Scaffold(
        containerColor = SemanticColors.BaseNavy,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Text(
                        "REVIEW QUEUE ($pendingCount)", 
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        color = SemanticColors.TextPrimary
                    ) 
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = SemanticColors.BaseNavy,
                    titleContentColor = SemanticColors.TextPrimary
                ),
                actions = {
                    Box {
                        IconButton(onClick = { showDebugMenu = !showDebugMenu }) {
                            Icon(Icons.Rounded.MoreVert, "Debug Options")
                        }
                        DropdownMenu(
                            expanded = showDebugMenu,
                            onDismissRequest = { showDebugMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Mass Insert (Batch)") },
                                onClick = {
                                    showDebugMenu = false
                                    batchLauncher.launch(
                                        androidx.activity.result.PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                },
                                leadingIcon = { Icon(Icons.Rounded.Layers, null) }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Export Parser Data") },
                                onClick = {
                                    showDebugMenu = false
                                    coroutineScope.launch {
                                        val data = viewModel.getDebugExportData()
                                        clipboardManager.setText(AnnotatedString(data))
                                        snackbarHostState.showSnackbar("Parser info copied to clipboard")
                                    }
                                },
                                leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Clear Scanned Data") },
                                onClick = {
                                    showDebugMenu = false
                                    viewModel.clearScannedData()
                                },
                                leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) },
                                colors = MenuDefaults.itemColors(
                                    textColor = MaterialTheme.colorScheme.error,
                                    leadingIconColor = MaterialTheme.colorScheme.error
                                )
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (pendingReviews.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("✅", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "All caught up!",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "No transactions need your review",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(
                        "Swipe right to approve, left to reject",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
                items(pendingReviews, key = { it.review.id }) { item ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { dismissValue ->
                            if (processingIds.contains(item.review.id)) return@rememberSwipeToDismissBoxState false
                            when (dismissValue) {
                                SwipeToDismissBoxValue.StartToEnd -> {
                                    processingIds.add(item.review.id)
                                    haptic(HapticType.Success)
                                    viewModel.approveReview(item.review.id)
                                    true
                                }
                                SwipeToDismissBoxValue.EndToStart -> {
                                    processingIds.add(item.review.id)
                                    haptic(HapticType.Error)
                                    viewModel.rejectReview(item.review.id)
                                    true
                                }
                                else -> false
                            }
                        }
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {
                            val color = when (dismissState.dismissDirection) {
                                SwipeToDismissBoxValue.StartToEnd -> SemanticColors.SuccessGreen
                                SwipeToDismissBoxValue.EndToStart -> SemanticColors.DangerRed
                                else -> Color.Transparent
                            }
                            val alignment = when (dismissState.dismissDirection) {
                                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                                else -> Alignment.Center
                            }
                            val icon = when (dismissState.dismissDirection) {
                                SwipeToDismissBoxValue.StartToEnd -> Icons.Rounded.CheckCircle
                                SwipeToDismissBoxValue.EndToStart -> Icons.Rounded.Delete
                                else -> Icons.AutoMirrored.Rounded.ArrowForward
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(color.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                                    .padding(horizontal = 24.dp),
                                contentAlignment = alignment
                            ) {
                                Icon(icon, null, tint = Color.White)
                            }
                        },
                        content = {
                            ReviewCard(
                                item = item,
                                onApprove = { viewModel.approveReview(item.review.id) },
                                onReject = { viewModel.rejectReview(item.review.id) },
                                onEdit = { editingReview = item.review }
                            )
                        }
                    )
                }
            }
        }
        editingReview?.let { review ->
            EditReviewDialog(
                review = review,
                categories = categories,
                onDismiss = { editingReview = null },
                onSave = { amount, merchant, categoryId ->
                    viewModel.approveReviewWithEdits(
                        reviewId = review.id,
                        finalAmount = amount,
                        finalMerchant = merchant,
                        finalCategoryId = categoryId
                    )
                    editingReview = null
                }
            )
        }
        // Batch processing overlay
        if (isBatchProcessing) {
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = SemanticColors.PrimaryLight)
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        "PROCESSING BATCH...",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    batchProgress?.let { (current, total) ->
                        Text(
                            "$current / $total",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { current.toFloat() / total },
                            modifier = Modifier.width(200.dp),
                            color = SemanticColors.PrimaryIndigo
                        )
                    }
                }
            }
        }
    }
}
@Composable
fun ReviewCard(
    item: PendingReviewWithReceipt,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onEdit: () -> Unit
) {
    val review = item.review
    val dateFormat = remember { DateTimeFormatter.ofPattern("MMM dd, HH:mm", Locale.getDefault()) }
    var showTrustSignal by remember { mutableStateOf(false) }
    val haptic = rememberHapticFeedback()
    val confidenceColor = when {
        review.confidence >= 0.85f -> SemanticColors.SuccessGreen
        review.confidence >= 0.65f -> SemanticColors.WarningOrange
        else -> SemanticColors.DangerRed
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SemanticColors.GlassSurface),
        border = BorderStroke(1.dp, SemanticColors.GlassBorder)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = review.packageName.split(".").lastOrNull()?.uppercase() ?: "SYSTEM",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Surface(
                    color = confidenceColor.copy(alpha = 0.15f),
                    shape = androidx.compose.foundation.shape.CircleShape
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${(review.confidence * 100).toInt()}% CONFIDENCE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = confidenceColor,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                // Receipt Thumbnail if available
                if (item.receipt != null) {
                    Card(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        border = BorderStroke(1.dp, SemanticColors.GlassBorder)
                    ) {
                        AsyncImage(
                            model = File(item.receipt.imagePath),
                            contentDescription = "Receipt Thumbnail",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = review.suggestedMerchant,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = SemanticColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = dateFormat.format(Instant.ofEpochMilli(review.createdAt).atZone(ZoneId.systemDefault())),
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.TextSecondary,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    AmountText(
                        amount = review.suggestedAmount,
                        style = MaterialTheme.typography.headlineSmall,
                        color = SemanticColors.TextPrimary
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            // Trust Signal / Detailed Evidence
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        SemanticColors.BaseNavy.copy(alpha = 0.5f),
                        RoundedCornerShape(16.dp)
                    )
                    .border(1.dp, SemanticColors.GlassBorder.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .clickable { 
                        haptic(HapticType.Standard)
                        showTrustSignal = !showTrustSignal 
                    }
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "RAW SOURCE EVIDENCE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SemanticColors.TextSecondary,
                        letterSpacing = 1.sp
                    )
                    Icon(
                        if (showTrustSignal) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                        null,
                        tint = SemanticColors.TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
                AnimatedVisibility(visible = showTrustSignal) {
                    Column(modifier = Modifier.padding(top = 16.dp)) {
                        Text(
                            text = review.notificationText ?: "No raw data captured.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SemanticColors.TextPrimary,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedIconButton(
                    onClick = {
                        haptic(HapticType.Heavy)
                        onEdit()
                    },
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Edit, "Edit", modifier = Modifier.size(20.dp))
                }
                Button(
                    onClick = {
                        haptic(HapticType.Error)
                        onReject()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text("Reject", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {
                        haptic(HapticType.Success)
                        onApprove()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SemanticColors.SuccessGreen,
                        contentColor = Color.White
                    )
                ) {
                    Text("Approve", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
@Composable
fun EditReviewDialog(
    review: PendingReview,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onSave: (Double?, String?, Long?) -> Unit
) {
    var amount by remember { mutableStateOf(String.format("%.2f", review.suggestedAmount)) }
    var merchant by remember { mutableStateOf(review.suggestedMerchant) }
    var selectedCategoryId by remember { mutableStateOf(review.suggestedCategoryId) }
    val haptic = rememberHapticFeedback()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fix Extraction Details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("Merchant Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount (€)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Text(
                    "Assign Category",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    categories.forEach { category ->
                        Surface(
                            onClick = { 
                                haptic(HapticType.Standard)
                                selectedCategoryId = category.id 
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selectedCategoryId == category.id)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, if (selectedCategoryId == category.id) MaterialTheme.colorScheme.primary else Color.Transparent)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(category.icon, fontSize = 20.sp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(category.name, style = MaterialTheme.typography.bodyMedium)
                                if (selectedCategoryId == category.id) {
                                    Spacer(modifier = Modifier.weight(1f))
                                    Icon(Icons.Rounded.Check, null, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    haptic(HapticType.Success)
                    val parsedAmount = amount.replace(",", ".").toDoubleOrNull()
                    val editedAmount = if (parsedAmount != null && kotlin.math.abs(parsedAmount - review.suggestedAmount) > 0.001) parsedAmount else null
                    val editedMerchant = merchant.takeIf { it != review.suggestedMerchant }
                    val editedCategory = selectedCategoryId.takeIf { it != review.suggestedCategoryId }
                    onSave(editedAmount, editedMerchant, editedCategory)
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Confirm Fix")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                haptic(HapticType.Standard)
                onDismiss()
            }) {
                Text("Cancel")
            }
        }
    )
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\review\ReviewViewModel.kt <a name="mainjavacomyournameexpensetrackeruiscreensreviewreviewviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.review
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.data.repository.NotificationRepository
// ...
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import javax.inject.Inject
@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository,
    private val receiptRepository: com.yourname.expensetracker.data.repository.ReceiptRepository
) : ViewModel() {
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()
    private val _batchProgress = MutableStateFlow<Pair<Int, Int>?>(null) // current, total
    val batchProgress = _batchProgress.asStateFlow()
    private val _isBatchProcessing = MutableStateFlow(false)
    val isBatchProcessing = _isBatchProcessing.asStateFlow()
    private var batchJob: Job? = null
    val pendingReviews: StateFlow<List<PendingReviewWithReceipt>> = repository
        .getPendingReviews()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pendingCount: StateFlow<Int> = repository
        .getPendingReviewCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun approveReview(reviewId: Long) {
        viewModelScope.launch {
            try {
                repository.approveReview(reviewId)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to approve: ${e.message}"
            }
        }
    }
    fun rejectReview(reviewId: Long) {
        viewModelScope.launch {
            repository.rejectReview(reviewId)
        }
    }
    fun approveReviewWithEdits(
        reviewId: Long,
        finalAmount: Double?,
        finalMerchant: String?,
        finalCategoryId: Long?
    ) {
        viewModelScope.launch {
            try {
                repository.approveReview(
                    reviewId = reviewId,
                    finalAmount = finalAmount,
                    finalMerchant = finalMerchant,
                    finalCategoryId = finalCategoryId
                )
            } catch (e: Exception) {
                _errorMessage.value = "Failed to approve edits: ${e.message}"
            }
        }
    }
    fun clearError() {
        _errorMessage.value = null
    }
    fun approveAll() {
        viewModelScope.launch {
            try {
                repository.approveAllReview()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to approve all: ${e.message}"
            }
        }
    }
    fun processBatch(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        batchJob?.cancel() // Cancel previous if any
        batchJob = viewModelScope.launch {
            try {
                _isBatchProcessing.value = true
                _batchProgress.value = Pair(0, uris.size)
                val result = receiptRepository.processBatch(uris) { current, total ->
                    _batchProgress.value = Pair(current, total)
                }
                if (result.failureCount > 0) {
                    val firstError = result.errors.firstOrNull()?.let { 
                        if (it.length > 60) it.take(57) + "..." else it 
                    }
                    _errorMessage.value = "Processed ${result.successCount} ok. ${result.failureCount} failed: $firstError"
                } else {
                    _errorMessage.value = "Successfully processed all ${result.successCount} receipts!"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Batch failed: ${e.message}"
            } finally {
                _isBatchProcessing.value = false
                _batchProgress.value = null
            }
        }
    }
    fun cancelBatchProcessing() {
        batchJob?.cancel()
        _isBatchProcessing.value = false
        _batchProgress.value = null
        _errorMessage.value = "Batch processing cancelled."
    }
    fun processStatement(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                _isBatchProcessing.value = true // Reuse batch loading state
                _batchProgress.value = Pair(0, 1)
                val result = receiptRepository.processStatement(uri)
                if (result.failureCount > 0) {
                    _errorMessage.value = "Failed to parse screenshot: ${result.errors.firstOrNull()}"
                } else {
                    _errorMessage.value = "Imported ${result.successCount} transactions from screenshot!"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Import failed: ${e.message}"
            } finally {
                _isBatchProcessing.value = false
                _batchProgress.value = null
            }
        }
    }
    suspend fun getDebugExportData(): String {
        return receiptRepository.exportParserDebugData()
    }
    fun clearScannedData() {
        viewModelScope.launch {
            receiptRepository.clearAllScannedReceipts()
            _errorMessage.value = "All scanned debug data cleared."
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\transactions\TransactionsScreen.kt <a name="mainjavacomyournameexpensetrackeruiscreenstransactionstransactionsscreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.transactions
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.ui.res.stringResource
import java.util.Locale
@Composable
fun RecurrencePickerDialog(
    onDismiss: () -> Unit,
    onFrequencySelected: (RecurrenceFrequency) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(com.yourname.expensetracker.R.string.select_frequency_title)) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(RecurrenceFrequency.values()) { frequency ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onFrequencySelected(frequency) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = frequency.name.replace("_", " ").lowercase()
                                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.yourname.expensetracker.R.string.cancel_button))
            }
        }
    )
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    viewModel: TransactionsViewModel = hiltViewModel()
) {
    val transactions by viewModel.transactions.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    var expenseToDelete by remember { mutableStateOf<Expense?>(null) }
    var expenseToCategorize by remember { mutableStateOf<Expense?>(null) }
    var expenseToRecurring by remember { mutableStateOf<Expense?>(null) }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(com.yourname.expensetracker.R.string.transactions_title)) }
                )
                ScrollableTabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    edgePadding = 16.dp,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    divider = {}
                ) {
                    TransactionsViewModel.TransactionTab.values().forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { viewModel.selectTab(tab) },
                            text = { 
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        if (transactions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(com.yourname.expensetracker.R.string.no_transactions_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(com.yourname.expensetracker.R.string.no_transactions_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = transactions,
                    key = { item -> item.expense.id },
                    contentType = { "transaction" }
                ) { item ->
                    TransactionItem(
                        transaction = item,
                        onDelete = { expenseToDelete = item.expense },
                        onEditCategory = { expenseToCategorize = item.expense },
                        onMarkRecurring = { expenseToRecurring = item.expense }
                    )
                }
            }
        }
        // ... Existing Dialogs ...
        if (expenseToDelete != null) {
            AlertDialog(
                onDismissRequest = { expenseToDelete = null },
                title = { Text(stringResource(com.yourname.expensetracker.R.string.delete_transaction_title)) },
                text = { Text(stringResource(com.yourname.expensetracker.R.string.delete_transaction_confirmation, expenseToDelete?.merchant ?: "")) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            expenseToDelete?.let { viewModel.deleteExpense(it) }
                            expenseToDelete = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(com.yourname.expensetracker.R.string.delete_button))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { expenseToDelete = null }) {
                        Text(stringResource(com.yourname.expensetracker.R.string.cancel_button))
                    }
                }
            )
        }
        // Recurrence Picker Dialog
        if (expenseToRecurring != null) {
            RecurrencePickerDialog(
                onDismiss = { expenseToRecurring = null },
                onFrequencySelected = { frequency ->
                    expenseToRecurring?.let { viewModel.markAsRecurring(it, frequency) }
                    expenseToRecurring = null
                }
            )
        }
        // Category Selection Dialog
        if (expenseToCategorize != null) {
            CategoryPickerDialog(
                categories = categories,
                onDismiss = { expenseToCategorize = null },
                onCategorySelected = { categoryId ->
                    expenseToCategorize?.let { viewModel.updateCategory(it, categoryId) }
                    expenseToCategorize = null
                }
            )
        }
    }
}
@Composable
fun CategoryPickerDialog(
    categories: List<Category>,
    onDismiss: () -> Unit,
    onCategorySelected: (Long) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(com.yourname.expensetracker.R.string.select_category_title)) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(categories) { category ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCategorySelected(category.id) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(category.icon, modifier = Modifier.padding(end = 12.dp))
                            Text(category.name)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.yourname.expensetracker.R.string.cancel_button))
            }
        }
    )
}
@Composable
fun TransactionItem(
    transaction: ExpenseWithCategory,
    onDelete: () -> Unit,
    onEditCategory: () -> Unit,
    onMarkRecurring: () -> Unit
) {
    val expense = transaction.expense
    val category = transaction.category
    val categoryColor = Color(transaction.categoryColor.toInt())
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Category Icon (Clickable to change)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = categoryColor,
                        shape = CircleShape
                    )
                    .clickable { onEditCategory() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = category?.icon ?: "❓",
                    fontSize = 24.sp
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            // Info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = expense.merchant,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (expense.isManualEntry) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("✏️", fontSize = 12.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val methodIcon = when(expense.paymentMethod) {
                        com.yourname.expensetracker.data.database.entity.PaymentMethod.CASH -> "💵"
                        com.yourname.expensetracker.data.database.entity.PaymentMethod.BANK_TRANSFER -> "🏦"
                        com.yourname.expensetracker.data.database.entity.PaymentMethod.CARD -> "💳"
                        else -> ""
                    }
                    if (methodIcon.isNotEmpty()) {
                        Text(methodIcon, fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = category?.name ?: stringResource(com.yourname.expensetracker.R.string.uncategorized_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { onEditCategory() }
                    )
                }
                Text(
                    text = transaction.formattedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            // Amount
            Text(
                text = transaction.formattedAmount,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp)
            )
            // Recurring Action
            IconButton(onClick = onMarkRecurring) {
                Icon(
                    Icons.Default.Repeat,
                    contentDescription = stringResource(com.yourname.expensetracker.R.string.mark_recurring_content_description),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp)
                )
            }
            // Delete Action
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(com.yourname.expensetracker.R.string.delete_button),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\transactions\TransactionsViewModel.kt <a name="mainjavacomyournameexpensetrackeruiscreenstransactionstransactionsviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.transactions
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository,
    private val recurringExpenseDao: com.yourname.expensetracker.data.database.dao.RecurringExpenseDao
) : ViewModel() {
    enum class TransactionTab(val label: String) {
        TODAY("Today"),
        WEEK("Week"),
        MONTH("Month"),
        QUARTER("Quarter"),
        YEAR("Year"),
        ALL("All")
    }
    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _selectedTab = MutableStateFlow(TransactionTab.MONTH)
    val selectedTab: StateFlow<TransactionTab> = _selectedTab.asStateFlow()
    private val _currentPage = MutableStateFlow(0)
    private val PAGE_SIZE = 50
    // For pagination, we'll manually append for TransactionTab.ALL
    private val _pagedExpenses = MutableStateFlow<List<ExpenseWithCategory>>(emptyList())
    fun selectTab(tab: TransactionTab) {
        _selectedTab.value = tab
        _currentPage.value = 0
        if (tab == TransactionTab.ALL) {
            loadInitialAll()
        }
    }
    private fun loadInitialAll() {
        viewModelScope.launch {
            val initial = repository.getExpensesPaged(PAGE_SIZE, 0)
            _pagedExpenses.value = initial
        }
    }
    fun loadMore() {
        if (_selectedTab.value != TransactionTab.ALL) return
        viewModelScope.launch {
            val nextPage = _currentPage.value + 1
            val nextItems = repository.getExpensesPaged(PAGE_SIZE, nextPage * PAGE_SIZE)
            if (nextItems.isNotEmpty()) {
                _pagedExpenses.value = _pagedExpenses.value + nextItems
                _currentPage.value = nextPage
            }
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    val transactions: StateFlow<List<ExpenseWithCategory>> = _selectedTab
        .flatMapLatest { tab ->
            if (tab == TransactionTab.ALL) {
                _pagedExpenses
            } else {
                val range = getRangeForTab(tab)
                repository.getExpensesWithCategoryInPeriod(range.first, range.second)
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    private fun getRangeForTab(tab: TransactionTab): Pair<Long, Long> {
        val cal = java.util.Calendar.getInstance()
        val now = System.currentTimeMillis()
        cal.timeInMillis = now
        return when (tab) {
            TransactionTab.TODAY -> {
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                Pair(cal.timeInMillis, now)
            }
            TransactionTab.WEEK -> {
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                val day = cal.get(java.util.Calendar.DAY_OF_WEEK)
                val diff = if (day == java.util.Calendar.SUNDAY) 6 else day - java.util.Calendar.MONDAY
                cal.add(java.util.Calendar.DAY_OF_YEAR, -diff)
                Pair(cal.timeInMillis, now)
            }
            TransactionTab.MONTH -> {
                cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                Pair(cal.timeInMillis, now)
            }
            TransactionTab.QUARTER -> {
                cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                val month = cal.get(java.util.Calendar.MONTH)
                cal.set(java.util.Calendar.MONTH, month - (month % 3))
                Pair(cal.timeInMillis, now)
            }
            TransactionTab.YEAR -> {
                cal.set(java.util.Calendar.DAY_OF_YEAR, 1)
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                Pair(cal.timeInMillis, now)
            }
            TransactionTab.ALL -> Pair(0L, now)
        }
    }
    fun deleteExpense(expense: Expense) {
        viewModelScope.launch {
            repository.deleteExpense(expense)
        }
    }
    fun updateCategory(expense: Expense, categoryId: Long) {
        viewModelScope.launch {
            repository.updateExpenseCategory(expense, categoryId)
        }
    }
    fun markAsRecurring(expense: Expense, frequency: com.yourname.expensetracker.domain.model.RecurrenceFrequency) {
        viewModelScope.launch {
            val rule = com.yourname.expensetracker.data.database.entity.ManualRecurringExpense(
                merchant = expense.merchant,
                amount = expense.amount,
                frequency = frequency,
                nextDate = System.currentTimeMillis() + frequency.intervalInMs
            )
            recurringExpenseDao.insert(rule)
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\theme\Theme.kt <a name="mainjavacomyournameexpensetrackeruithemethemekt"></a>
```kotlin
package com.yourname.expensetracker.ui.theme
import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
// === Semantic Colors (optimized for Midnight Navy) ===
object SemanticColors {
    val BaseNavy = Color(0xFF0F172A)
    val SurfaceLight = Color(0xFF1E293B)
    val PrimaryIndigo = Color(0xFF6366F1)
    val PrimaryLight = Color(0xFF818CF8)
    val SuccessGreen = Color(0xFF10B981)
    val WarningOrange = Color(0xFFF97316)
    val DangerRed = Color(0xFFEF4444)
    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0x9994A3B8) // 60% alpha
    val GlassSurface = Color(0x661E293B) // 40% alpha SurfaceLight
    val GlassBorder = Color(0x1A94A3B8)   // 10% alpha TextSecondary
    // Budget health
    val OnTrack = SuccessGreen
    val Warning = WarningOrange
    val Critical = DangerRed
    val Exceeded = Color(0xFFFF5722)
    // Pace
    val UnderPace = SuccessGreen
    val OnPace = PrimaryIndigo
    val OverPace = WarningOrange
    // Confidence
    fun confidenceColor(confidence: Float): Color = when {
        confidence >= 0.85f -> SuccessGreen
        confidence >= 0.65f -> WarningOrange
        else -> DangerRed
    }
}
// === Typography with Tabular Lining Figures ===
val ExpenseTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 57.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 64.sp,
        fontFeatureSettings = "tnum",
        color = SemanticColors.TextPrimary
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 45.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 52.sp,
        fontFeatureSettings = "tnum",
        color = SemanticColors.TextPrimary
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 36.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 44.sp,
        fontFeatureSettings = "tnum",
        color = SemanticColors.TextPrimary
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 32.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 40.sp,
        color = SemanticColors.TextPrimary
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 28.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 36.sp,
        color = SemanticColors.TextPrimary
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 24.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 32.sp,
        fontFeatureSettings = "tnum",
        color = SemanticColors.TextPrimary
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 28.sp,
        color = SemanticColors.TextPrimary
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 24.sp,
        color = SemanticColors.TextPrimary
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 20.sp,
        color = SemanticColors.TextSecondary
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 24.sp,
        color = SemanticColors.TextPrimary
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 20.sp,
        color = SemanticColors.TextPrimary
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 16.sp,
        color = SemanticColors.TextSecondary
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 20.sp,
        color = SemanticColors.TextPrimary
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 16.sp,
        color = SemanticColors.TextSecondary
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 16.sp,
        color = SemanticColors.TextMuted
    )
)
private val DarkColorScheme = darkColorScheme(
    primary = SemanticColors.PrimaryIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0x336366F1), // PrimaryIndigo @ 20%
    onPrimaryContainer = SemanticColors.PrimaryLight,
    secondary = SemanticColors.SurfaceLight,
    onSecondary = SemanticColors.TextPrimary,
    background = SemanticColors.BaseNavy,
    onBackground = SemanticColors.TextPrimary,
    surface = SemanticColors.SurfaceLight,
    onSurface = SemanticColors.TextPrimary,
    surfaceVariant = SemanticColors.GlassSurface,
    onSurfaceVariant = SemanticColors.TextSecondary,
    outline = SemanticColors.GlassBorder,
    error = SemanticColors.DangerRed
)
private val LightColorScheme = DarkColorScheme // Focusing on the Midnight Theme as requested
@Composable
fun ExpenseTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ExpenseTypography,
        content = content
    )
}

```

---

## main\java\com\yourname\expensetracker\ui\util\HapticFeedback.kt <a name="mainjavacomyournameexpensetrackeruiutilhapticfeedbackkt"></a>
```kotlin
package com.yourname.expensetracker.ui.util
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView
object AppHaptics {
    fun performStandard(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
    fun performSuccess(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }
    fun performError(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
    }
    fun performHeavy(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}
@Composable
fun rememberHapticFeedback(): (HapticType) -> Unit {
    val view = LocalView.current
    return { type ->
        when (type) {
            HapticType.Standard -> AppHaptics.performStandard(view)
            HapticType.Success -> AppHaptics.performSuccess(view)
            HapticType.Error -> AppHaptics.performError(view)
            HapticType.Heavy -> AppHaptics.performHeavy(view)
        }
    }
}
enum class HapticType {
    Standard, Success, Error, Heavy
}

```

---

## main\res\drawable\ic_launcher_background.xml <a name="mainresdrawableic_launcher_backgroundxml"></a>
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#008577"
        android:pathData="M0,0h108v108h-108z" />
</vector>

```

---

## main\res\drawable\ic_launcher_foreground.xml <a name="mainresdrawableic_launcher_foregroundxml"></a>
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M79,35h-9.92C68.42,32.49,67.6,30.33,66.6,28.62L74.02,21.2c0.78-0.78,0.78-2.05,0-2.83L69.2,13.55c-0.78-0.78-2.05-0.78-2.83,0L58.95,20.97c-1.71-1-3.87-1.82-6.38-2.48V8.42c0-1.1-0.9-2-2-2h-6.83c-1.1,0-2,0.9-2,2v10.07c-2.51,0.66-4.67,1.48-6.38,2.48L27.63,13.55c-0.78-0.78-2.05-0.78-2.83,0L19.98,18.37c-0.78,0.78-0.78,2.05,0,2.83l7.42,7.42c-1,1.71-1.82,3.87-2.48,6.38H14.85c-1.1,0-2,0.9-2,2v6.83c0,1.1,0.9,2,2,2h10.07c0.66,2.51,1.48,4.67,2.48,6.38L19.98,60.85c-0.78,0.78-0.78,2.05,0,2.83l4.82,4.82c0.78,0.78,2.05,0.78,2.83,0l7.42-7.42c1.71,1,3.87,1.82,6.38,2.48v10.07c0,1.1,0.9,2,2,2h6.83c1.1,0,2-0.9,2-2V73.15c2.51-0.66,4.67-1.48,6.38-2.48l7.42,7.42c0.78,0.78,2.05,0.78,2.83,0l4.82-4.82c0.78-0.78,0.78-2.05,0-2.83l-7.42-7.42c1-1.71,1.82-3.87,2.48-6.38h10.07c1.1,0,2-0.9,2-2V48.17c0-1.1-0.9-2-2-2H79z M54,52.17c-4.42,0-8-3.58-8-8s3.58-8,8-8s8,3.58,8,8S58.42,52.17,54,52.17z" />
</vector>

```

---

## main\res\mipmap-anydpi-v26\ic_launcher.xml <a name="mainresmipmap-anydpi-v26ic_launcherxml"></a>
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>

```

---

## main\res\mipmap-anydpi-v26\ic_launcher_round.xml <a name="mainresmipmap-anydpi-v26ic_launcher_roundxml"></a>
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>

```

---

## main\res\mipmap\ic_launcher.xml <a name="mainresmipmapic_launcherxml"></a>
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>

```

---

## main\res\mipmap\ic_launcher_round.xml <a name="mainresmipmapic_launcher_roundxml"></a>
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>

```

---

## main\res\values\strings.xml <a name="mainresvaluesstringsxml"></a>
```xml
<resources>
    <string name="app_name">ExpenseTracker</string>
    <!-- Add Expense -->
    <string name="add_expense_title">Add Expense</string>
    <string name="close_content_description">Close</string>
    <string name="save_button">Save</string>
    <string name="merchant_label">Merchant / Place</string>
    <string name="merchant_placeholder">e.g. Sklavenitis, Starbucks...</string>
    <string name="amount_label">Amount (€)</string>
    <string name="amount_placeholder">0.00</string>
    <string name="payment_method_label">Payment Method</string>
    <string name="payment_method_card">💳 Card</string>
    <string name="payment_method_cash">💵 Cash</string>
    <string name="payment_method_transfer">🏦 Transfer</string>
    <string name="category_label">Category</string>
    <string name="date_label">Date</string>
    <string name="transaction_type_prefix">Transaction Type: %1$s</string>
    <string name="toggle_content_description">Toggle</string>
    <string name="notes_label">Notes</string>
    <string name="notes_placeholder">Optional notes</string>
    <string name="repeat_transaction_label">Repeat Transaction?</string>
    <string name="frequency_label">Frequency</string>
    <string name="error_duplicate_transaction">⚠️ A similar transaction already exists</string>
    <string name="avg_amount_format">~€%.2f</string>
    <string name="visits_suffix_format">%d visits</string>
    <string name="ok_button">OK</string>
    <string name="cancel_button">Cancel</string>
    <!-- Error Messages -->
    <string name="error_merchant_required">Merchant name is required</string>
    <string name="error_invalid_amount">Enter a valid amount</string>
    <string name="error_amount_too_large">Amount is too large</string>
    <string name="error_unknown">Unknown error</string>
    <string name="recurring_note_default">Created from manual entry</string>
    <!-- Transactions Screen -->
    <string name="select_frequency_title">Select Frequency</string>
    <string name="transactions_title">Transactions</string>
    <string name="no_transactions_title">No transactions found</string>
    <string name="no_transactions_subtitle">Parsed expenses in this period will appear here</string>
    <string name="delete_transaction_title">Delete Transaction</string>
    <string name="delete_transaction_confirmation">Are you sure you want to delete this transaction from %1$s?</string>
    <string name="delete_button">Delete</string>
    <string name="select_category_title">Select Category</string>
    <string name="uncategorized_label">Uncategorized</string>
    <string name="mark_recurring_content_description">Mark as Recurring</string>
</resources>

```

---

## main\res\values\themes.xml <a name="mainresvaluesthemesxml"></a>
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.ExpenseTracker" parent="Theme.Material3.DayNight.NoActionBar">
    </style>
</resources>

```

---

## main\res\xml\file_paths.xml <a name="mainresxmlfile_pathsxml"></a>
```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="receipt_images" path="receipt_images/" />
    <files-path name="receipts" path="receipts/" />
</paths>

```

---

