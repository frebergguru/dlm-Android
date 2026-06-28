# JNI-bound classes must keep their names/signatures.
-keep class guru.freberg.dlm.core.jni.** { *; }
-keep class guru.freberg.dlm.core.model.** { *; }

# youtubedl-android loads native + python by reflection.
-keep class com.yausername.** { *; }
-dontwarn com.yausername.**
