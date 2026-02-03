# Xposed module - keep hook entry
-keep class moe.chenxy.oppoheadset.hook.** { *; }
-keep class moe.chenxy.oppoheadset.Constants { *; }

# Keep Xposed API
-keep class de.robv.android.xposed.** { *; }
-keepclassmembers class * {
    @de.robv.android.xposed.* *;
}

# Keep reflection targets
-keepattributes *Annotation*
-keepattributes Signature
