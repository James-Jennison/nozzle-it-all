# Phase 9g: extra rules for the releaseSmoke build type only (never shipped).
# The instrumented test APK is compiled against the app's own classes and bundles its own copy of some Kotlin
# runtime; keeping both lets the whole suite run while every third-party dependency (Tink, Netty/HiveMQ,
# BouncyCastle, jsch, Room, Glance, OkHttp) still goes through the exact release shrinking and obfuscation.
-keep class net.jamesjennison.klippercompanion.** { *; }
-keep class org.orcaslicer.engine.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlin.**
# The test APK links against the app's copy of these at runtime, so R8 must not drop anything the test framework
# reaches. Room, Glance, security-crypto (Tink), OkHttp, HiveMQ/Netty, BouncyCastle and jsch are deliberately NOT kept.
-keep class androidx.tracing.** { *; }
-keep class androidx.compose.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.core.** { *; }
-keep class androidx.savedstate.** { *; }
-keep class androidx.annotation.** { *; }
-dontwarn androidx.test.**
# Test code calls library APIs (e.g. androidx.room.Room) by their real names, so obfuscation is off here; the real
# obfuscation of the shipped build is exercised separately by launching the fully minified release APK (docs/RELEASE.md).
-dontobfuscate
-keep class androidx.collection.** { *; }
-keep class androidx.customview.** { *; }
-keep class androidx.startup.** { *; }
-keep class androidx.arch.** { *; }
-keep class androidx.emoji2.** { *; }
-keep class androidx.profileinstaller.** { *; }
-keep class androidx.versionedparcelable.** { *; }
-keep class androidx.interpolator.** { *; }
-keep class androidx.window.** { *; }
-keep class androidx.room.Room { *; }
