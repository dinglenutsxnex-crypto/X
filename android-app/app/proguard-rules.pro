-keep class com.rootdroid.inspector.** { *; }
-keepattributes *Annotation*
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
