# Hilt
-keep public class * extends android.app.Service
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep class dagger.hilt.android.internal.managers.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Compose
-keepclassmembers class **.Screen { *; }
-keepclassmembers class **.Composable { *; }

# General
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Gson (RT-C3): reflective model classes. Gson maps JSON keys to field names, so
# R8 must keep the field names of every class serialized/deserialized by Gson.
# Scoped per class on purpose (no broad package wildcard, no -dontwarn).
# TypeToken subclasses must keep their generic Signature (also needs
# -keepattributes Signature above); anonymous TypeToken subclasses are scoped to
# this app's package.
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class com.yourname.expensetracker.** extends com.google.gson.reflect.TypeToken
-keepclassmembers class com.yourname.expensetracker.data.repository.SavingsContributionEvent { <fields>; }
-keepclassmembers class com.yourname.expensetracker.data.repository.SavingsContributionHistoryRepository$PersistedState { <fields>; }
-keepclassmembers class com.yourname.expensetracker.data.repository.AutomatedSavingsRuleStateRepository$PersistedState { <fields>; <init>(); }
-keepclassmembers class com.yourname.expensetracker.data.repository.AutomatedSavingsRuleStateRepository$WeeklyRewardReservation { <fields>; }
-keepclassmembers class com.yourname.expensetracker.data.repository.AutomatedSavingsRuleStateRepository$MonthlyCapUsage { <fields>; }
-keepclassmembers class com.yourname.expensetracker.data.database.entity.SplitShare { <fields>; }
-keepclassmembers class com.yourname.expensetracker.ui.PersistedVisualSplit { <fields>; }
-keepclassmembers enum com.yourname.expensetracker.data.database.entity.SplitTemplate$SplitType { <fields>; }

# Suppress warnings for optional PDF image decoding libraries
-dontwarn com.gemalto.jp2.JP2Decoder
