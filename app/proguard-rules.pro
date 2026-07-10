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

# Suppress warnings for optional PDF image decoding libraries
-dontwarn com.gemalto.jp2.JP2Decoder
