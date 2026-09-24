# Phase 9g: rules for the releaseSmoke build type's androidTest APK only - keep the test framework whole and
# silence optional-annotation warnings from its dependencies.
-dontwarn com.google.errorprone.annotations.**
-dontwarn kotlinx.serialization.**
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**
-keep class androidx.test.** { *; }
-keep class org.junit.** { *; }
-keep class junit.** { *; }
-keep class net.jamesjennison.klippercompanion.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class androidx.compose.** { *; }
