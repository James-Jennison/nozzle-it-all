import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose"); id("com.google.devtools.ksp") }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

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
  // Phase 10: optional MyMiniFactory developer credentials, supplied by the owner at build time (never committed).
  // MMF_API_KEY enables Discover browsing; MMF_CLIENT_KEY enables sign-in (file downloads). Empty = the user enters their own in Discover.
  buildConfigField("String", "MMF_API_KEY", "\"${providers.gradleProperty("MMF_API_KEY").orElse(providers.environmentVariable("MMF_API_KEY")).getOrElse("")}\"")
  buildConfigField("String", "MMF_CLIENT_KEY", "\"${providers.gradleProperty("MMF_CLIENT_KEY").orElse(providers.environmentVariable("MMF_CLIENT_KEY")).getOrElse("")}\"")
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
    // Phase 0 (Consumer Slicer Plan §16): CI (.github/workflows/ci.yml) runs on a self-hosted
    // runner (gthost-build01) where orcaslicer-android-engine lives at a different absolute path
    // than this dev box's /mnt/faststorage/orcaslicer-android-engine - CMakeLists.txt's own
    // ORCASLICER_ENGINE_ROOT cache default. Passing the override here, only when the CI
    // environment variable is actually set, keeps local dev builds (no env var) completely
    // unaffected - this is additive, not a behavior change for anyone not running CI.
    System.getenv("ORCASLICER_ENGINE_ROOT")?.let { arguments += "-DORCASLICER_ENGINE_ROOT=$it" }
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
 // WO-31 (owner request): real R8 shrinking/minification for release, measured to cut the
 // release APK from 109.9MB to 73.6MB installed (47.1MB to 34.8MB download) - see
 // docs/WORK_ORDER.md's WO-31 entry for the full before/after. proguard-rules.pro's own comments
 // justify every rule (the JNI native-bridge keep rule is load-bearing, not boilerplate - see
 // that file).
 // Phase 9g: release signing comes from the environment or ~/.gradle/gradle.properties - never from this repo.
 // Set NOZZLE_KEYSTORE (path), NOZZLE_KEYSTORE_PASSWORD, NOZZLE_KEY_ALIAS and NOZZLE_KEY_PASSWORD to sign; without
 // them assembleRelease still works and produces an unsigned APK (the owner's signing identity is decided outside
 // this repo, see docs/RELEASE.md).
 val signingValue = { name: String -> providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orNull }
 val releaseKeystore = signingValue("NOZZLE_KEYSTORE")
 signingConfigs {
  if (releaseKeystore != null) create("release") {
   storeFile = file(releaseKeystore)
   storePassword = signingValue("NOZZLE_KEYSTORE_PASSWORD"); keyAlias = signingValue("NOZZLE_KEY_ALIAS"); keyPassword = signingValue("NOZZLE_KEY_PASSWORD")
  }
 }
 if (providers.gradleProperty("nozzleSmoke").isPresent) testBuildType = "releaseSmoke"
 buildTypes {
  release {
   isMinifyEnabled = true; isShrinkResources = true
   proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
   if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
  }
  // Phase 9g: the release recipe (R8 shrinking + obfuscation of every dependency) with the app's own classes and
  // Kotlin kept, debug-signed, so the whole instrumented suite can run against minified libraries:
  //   ./gradlew assembleReleaseSmoke assembleAndroidTest -PnozzleSmoke   (-PnozzleSmoke points the androidTest APK at this type)
  // Never shipped. See proguard-smoke.pro and docs/RELEASE.md.
  create("releaseSmoke") {
   initWith(getByName("release"))
   signingConfig = signingConfigs.getByName("debug")
   matchingFallbacks += "release"
   proguardFiles("proguard-smoke.pro")
   testProguardFiles("proguard-smoke-test.pro")
  }
 }
 buildFeatures { compose = true; buildConfig = true }
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
 implementation(project(":domain"))
 implementation(project(":transport"))
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
 // Phase 0 (Consumer Slicer Plan §16): Project/ProjectObject persistence infrastructure - added
 // now, empty/unused this phase, because every later phase (1 through 13) depends on it and
 // retrofitting it after the fact would be far more expensive. 2.8.5 is current stable.
 implementation("androidx.room:room-runtime:2.8.5")
 implementation("androidx.room:room-ktx:2.8.5")
 ksp("androidx.room:room-compiler:2.8.5")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
 testImplementation("org.json:json:20240303")
 testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
 androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.00"))
 androidTestImplementation("androidx.compose.ui:ui-test-junit4")
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0") // 3.5.0 calls InputManager.getInstance, removed in Android 17
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
 debugImplementation("androidx.compose.ui:ui-test-manifest")
 add("releaseSmokeImplementation", "androidx.compose.ui:ui-test-manifest")
}

// Phase 9g: CycloneDX 1.5 SBOM of everything that ships - the resolved release runtime dependencies (with SHA-256 of
// each artifact) plus the pinned native engine and its dependencies from engine/ENGINE_PIN.json. Offline, no plugin.
tasks.register("generateSbom") {
 group = "release"; description = "Writes build/sbom/nozzle-it-all.cdx.json"
 val runtime = configurations.named("releaseRuntimeClasspath")
 val pinFile = rootProject.file("engine/ENGINE_PIN.json")
 val out = layout.buildDirectory.file("sbom/nozzle-it-all.cdx.json")
 inputs.files(runtime); inputs.file(pinFile); outputs.file(out)
 doLast {
  fun esc(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"")
  fun sha256(f: java.io.File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
  val components = sortedMapOf<String, String>()
  runtime.get().incoming.artifactView { lenient(true) }.artifacts.artifacts.forEach { a ->
   val id = a.id.componentIdentifier
   if (id is org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
    val purl = "pkg:maven/${id.group}/${id.module}@${id.version}"
    components[purl] = """{"type":"library","group":"${esc(id.group)}","name":"${esc(id.module)}","version":"${esc(id.version)}","purl":"${esc(purl)}","hashes":[{"alg":"SHA-256","content":"${sha256(a.file)}"}]}"""
   }
  }
  val pin = groovy.json.JsonSlurper().parse(pinFile) as Map<*, *>
  val upstream = pin["upstream"] as Map<*, *>
  components["pkg:github/SoftFever/OrcaSlicer@${upstream["commit"]}"] = """{"type":"library","name":"OrcaSlicer (libslic3r, patched)","version":"${esc(upstream["commit"].toString())}","purl":"pkg:github/SoftFever/OrcaSlicer@${upstream["commit"]}","licenses":[{"license":{"id":"AGPL-3.0-or-later"}}],"properties":[{"name":"patch.sha256","value":"${esc((pin["patch"] as Map<*, *>)["sha256"].toString())}"}]}"""
  (pin["dependencies"] as List<*>).forEach { d ->
   d as Map<*, *>
   val n = d["file"].toString()
   components["pkg:generic/$n"] = """{"type":"library","name":"${esc(n)}","purl":"pkg:generic/${esc(n)}","hashes":[{"alg":"SHA-256","content":"${d["sha256"]}"}],"properties":[{"name":"linked","value":"native (arm64-v8a)"}]}"""
  }
  val body = components.values.joinToString(",\n    ")
  val serial = UUID.nameUUIDFromBytes(body.toByteArray()).toString()
  val stamp = (System.getenv("SOURCE_DATE_EPOCH")?.toLongOrNull()?.let { Instant.ofEpochSecond(it) } ?: Instant.now()).toString()
  out.get().asFile.also { it.parentFile.mkdirs() }.writeText("""{
  "bomFormat": "CycloneDX", "specVersion": "1.5", "serialNumber": "urn:uuid:$serial", "version": 1,
  "metadata": {"timestamp": "$stamp", "component": {"type": "application", "name": "Nozzle It All", "version": "${android.defaultConfig.versionName}", "purl": "pkg:generic/net.jamesjennison.klippercompanion@${android.defaultConfig.versionName}"}},
  "components": [
    $body
  ]
}
""")
 }
}

// AWS Device Farm rejects (at its own re-signing step) an instrumented test package above roughly 3.7-4.0 MB of code. These files skip
// themselves entirely without printer/LAN/API-key arguments, so they do nothing on Device Farm; -PdeviceFarmTests leaves them out of that build.
if (providers.gradleProperty("deviceFarmTests").isPresent) {
 val lanOnlyTests = listOf("ActiveToolDeviceTest", "AddPrinterWizardDeviceTest", "FakeBambuDeviceTest", "LiveFileHardwareTest", "LivePreviewHardwareTest",
  "LivePrinterReadOnlyTest", "MmfLiveDeviceTest", "OctoPrintDeviceTest", "PrinterScanDeviceTest", "SshHostKeyPinDeviceTest").map { "**/$it.kt" }
 tasks.matching { it.name.contains("AndroidTest") && it.name.startsWith("compile") }.configureEach {
  if (this is org.gradle.api.tasks.util.PatternFilterable) exclude(lanOnlyTests)
 }
}
