plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
 namespace = "net.jamesjennison.klippercompanion"
 compileSdk = 36
 defaultConfig {
  // minSdk raised 26 -> 28 for WO-13's slicing engine: Boost.Locale (a libslic3r dependency)
  // needs iconv(), only __INTRODUCED_IN(28) in Bionic, and every other engine dependency is
  // fine as low as 21 - so 28 is Boost.Locale's floor, not an arbitrary choice, and it narrows
  // this app's supported devices to Android 9+ (2018). See docs/WORK_ORDER.md's WO-13 entry.
  applicationId = "net.jamesjennison.klippercompanion"; minSdk = 28; targetSdk = 36; versionCode = 1; versionName = "0.1.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  // arm64-v8a only, matching every physical device this app has ever been verified on (Razr
  // 2023) and the only ABI the vendored slicing engine's dependencies were built for. NDK
  // 27.1.12297006 (pinned below) matches what that engine was built and verified with.
  ndk { abiFilters += "arm64-v8a" }
  externalNativeBuild {
   cmake {
    // CMAKE_BUILD_TYPE=Release regardless of the Gradle Debug/Release variant - matches
    // orcaslicer-android-engine's own build_libslic3r.sh exactly (its env.sh hardcodes
    // Release). This is load-bearing, not cosmetic: GCode.hpp forces its own
    // ORCA_CHECK_GCODE_PLACEHOLDERS strictness check on whenever NDEBUG is undefined (Debug
    // builds), and that check validates custom-gcode placeholder keys against a static
    // allow-list (PrintConfig.cpp) that's demonstrably stale against what real gcode
    // processing passes at runtime for filament_start_gcode/layer_change_gcode - a genuine
    // upstream data inconsistency, not anything Android-specific. It threw
    // "Some EditGcodeDialog defs were not specified properly" the first time this engine ran
    // through Nozzle It All's own (Debug-mode) native build, despite identical config to the
    // already-verified CLI tool - found via __android_log_print diagnostics added
    // temporarily to the vendored GCode.cpp, then reverted once root-caused. See
    // docs/WORK_ORDER.md's WO-13 entry.
    arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release")
    // Without this, AGP discovers and builds every CMake target in the whole configured
    // project tree - including OrcaSlicer's desktop GUI executable (needs wxWidgets, which
    // isn't cross-compiled here) and its i18n tooling. slic3rengine is the only target this
    // app actually needs; libslic3r comes along transitively as its dependency. Matches what
    // orcaslicer-android-engine's own build_libslic3r.sh does explicitly (`ninja libslic3r
    // slic3rengine slic3r_cli_test`), just scoped through Gradle instead of a raw ninja call.
    targets += "slic3rengine"
   }
  }
 }
 buildFeatures { compose = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 // Pinned rather than "latest": the exact NDK this native build has been verified against
 // (see the oneTBB cross-compile proof in the WO-13 plan/commit history). A different NDK
 // silently changes native codegen - do not bump this without re-verifying the native build.
 ndkVersion = "27.1.12297006"
 externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
 // Several of Netty's jars (pulled in transitively by hivemq-mqtt-client) each carry their own
 // copy of this JAR-signing-era index file; it's not needed at runtime, so drop it rather than
 // pick one arbitrarily.
 packaging { resources { excludes += "META-INF/INDEX.LIST"; pickFirsts += "META-INF/io.netty.versions.properties" } }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2026.06.00"))
 implementation("androidx.activity:activity-compose:1.11.0")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 implementation("androidx.security:security-crypto:1.1.0")
 // Bespok3d bridge (Snapmaker U1/PAXX): SSH probe/enrollment + OpenPGP-signed plugin catalog
 // verification, ported from Helix. com.github.mwiede:jsch is the actively maintained fork of
 // the abandoned com.jcraft:jsch (same package name, drop-in API) — Helix itself pins this same
 // fork/version. bcpg/bcprov provide the OpenPGP signature verification Bespok3dBootstrapPackages
 // needs; bctls (TLS) is not used since Bespok3dClient pins certificates via javax.net.ssl directly.
 implementation("com.github.mwiede:jsch:2.28.6")
 implementation("org.bouncycastle:bcprov-jdk18on:1.83")
 implementation("org.bouncycastle:bcpg-jdk18on:1.83")
 // Bambu Lab LAN support (PrinterKind.BAMBU_LAB), ported from Helix, which pins these same
 // versions. hivemq-mqtt-client is the MQTT 3.1.1 client BambuMqttConnection uses for the
 // printer's TLS status/control channel on 8883 (chosen for its pluggable TrustManagerFactory,
 // which is what serial pinning hangs off). commons-net supplies the FTPS client for the
 // implicit-TLS upload port 990. bctls is BouncyCastle's JSSE provider: Android's own JSSE
 // cannot ask a data socket to resume the control socket's TLS session, which Bambu's vsftpd
 // requires, and only BCJSSE exposes an API for it (see BambuFtpsClient).
 implementation("com.hivemq:hivemq-mqtt-client:1.3.17")
 implementation("commons-net:commons-net:3.13.0")
 implementation("org.bouncycastle:bctls-jdk18on:1.83")
 // Home-screen widget (P18): Glance renders it in Compose, matching this app's own style,
 // instead of hand-written RemoteViews/XML layouts. 1.2.0 is the current stable release.
 implementation("androidx.glance:glance-appwidget:1.2.0")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
 testImplementation("org.json:json:20240303")
 testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
 androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.00"))
 androidTestImplementation("androidx.compose.ui:ui-test-junit4")
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
 debugImplementation("androidx.compose.ui:ui-test-manifest")
}
