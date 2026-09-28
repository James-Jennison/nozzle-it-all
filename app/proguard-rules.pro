# WO-31 (owner request: real R8/minification, measured to save ~36MB installed / ~12MB download
# - see docs/WORK_ORDER.md). Every rule below is justified individually, not a blind dump of
# R8's own auto-generated missing_rules.txt - each group states *why* it's safe.

# --- JNI native bridge (critical, not something R8 itself warns about) ---
# org.orcaslicer.engine.NativeEngine's package/class/method names are hard-coded into the native
# bridge's exported JNI symbols (nozzle-engine's nozzle/bridge/android/slic3r_jni.cpp uses static linkage:
# Java_org_orcaslicer_engine_NativeEngine_<method>, not JNI_OnLoad/RegisterNatives). If R8 renamed
# or inlined this class or any of its `external fun` methods, System.loadLibrary("slic3rengine")
# would still succeed (a real, silent trap: linking the .so and resolving a specific native
# method are two separate steps) but the very first native call would throw
# UnsatisfiedLinkError - a real crash on real slicing hardware, not a build-time warning R8 would
# ever surface. keepclasseswithmembernames + includedescriptorclasses matches the standard,
# documented ProGuard/R8 recipe for static-linkage JNI.
-keepclasseswithmembernames,includedescriptorclasses class org.orcaslicer.engine.NativeEngine {
    native <methods>;
}

# --- Netty (transitive via hivemq-mqtt-client, for BambuMqttConnection) ---
# Netty's own code defensively guards every one of these behind reflection/Class.forName at
# runtime specifically because they're optional, separately-published artifacts
# (netty-transport-native-epoll, netty-codec-http, netty-handler-proxy, netty-tcnative) that this
# app's dependencies (app/build.gradle.kts) never pull in - confirmed absent from the real
# dependency tree, not merely unused. Epoll is a Linux-only native transport (irrelevant on
# Android, which uses NIO); the HTTP/websocket/proxy classes back Netty's optional
# MQTT-over-websocket and HTTP-proxy support, neither of which BambuMqttConnection's real MQTT
# client configuration uses (plain MQTT over TLS on port 8883 - see BambuMqttConnection.kt).
-dontwarn io.netty.channel.epoll.**
-dontwarn io.netty.handler.codec.http.**
-dontwarn io.netty.handler.proxy.**
-dontwarn io.netty.internal.tcnative.**
# Netty's pluggable logging backends (InternalLoggerFactory falls back to java.util.logging at
# runtime when none of these are present) - this app ships none of log4j/log4j2/slf4j.
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
# Pre-Java9 bootstrap-classpath ALPN/NPN providers for TLS - obsolete even on desktop JVMs,
# meaningless on Android (whose own SSLEngine has always supported ALPN natively).
-dontwarn org.eclipse.jetty.alpn.**
-dontwarn org.eclipse.jetty.npn.**
# A java.util.ServiceLoader hook for BlockHound (a Reactor debugging tool this app never
# includes) that Netty's own Hidden$NettyBlockHoundIntegration references defensively.
-dontwarn reactor.blockhound.integration.BlockHoundIntegration

# --- google-tink (transitive via androidx.security:security-crypto - confirmed via
# `./gradlew :app:dependencies --configuration releaseRuntimeClasspath`, not guessed - Tink is
# the crypto backend EncryptedSharedPreferences/EncryptedFile use). errorprone annotations are
# RetentionPolicy.CLASS/SOURCE, compile-time-only static-analysis hints with zero runtime
# behavior, safe to drop entirely. ---
-dontwarn com.google.errorprone.annotations.**

# --- Reflection-loaded crypto/network stacks (found broken in the shipped build by SelfCheck.kt, not guessed) ---
# BouncyCastle's providers register algorithms by class NAME strings (e.g. "SHA-512" -> a digest class looked up
# reflectively), and OpenPGP verification of the signed Bespok3d packages needs them; R8 cannot see those references and
# stripped them ("no such algorithm: SHA-512 for provider BC"). Kept whole - the standard recipe for BC.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
# jsch instantiates its ciphers, key exchanges and key-pair generators from a name->class table via Class.forName
# ("ClassNotFoundException: com.jcraft.jsch.jce.KeyPairGenRSA").
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
# HiveMQ's client wires itself with Dagger and Netty's channel/handler classes by name at static-init time.
-keep class com.hivemq.client.** { *; }
-keep class io.netty.** { *; }
-dontwarn com.hivemq.client.**
# Keeping Netty whole exposes its optional compression/serialization codecs, whose libraries this app does not ship
# (they are only reached when a channel is configured to use them).
-dontwarn io.netty.**
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.github.luben.zstd.**
-dontwarn com.jcraft.jzlib.**
-dontwarn com.ning.compress.**
-dontwarn lzma.sdk.**
-dontwarn net.jpountz.**
-dontwarn com.google.protobuf.**
-dontwarn org.jboss.marshalling.**
-dontwarn sun.security.x509.**
