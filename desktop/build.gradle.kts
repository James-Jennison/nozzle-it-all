import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
 id("org.jetbrains.kotlin.jvm")
 id("org.jetbrains.kotlin.plugin.compose")
 id("org.jetbrains.compose") version "1.9.3"
}
// Nozzle It All for Desktop: the original Nozzle interface (Compose Desktop). It depends on the shared printer API, the
// PAXX LAN adapter and the project format. It must never depend on :stock-u1-adapter or any vendor-cloud library; the
// Stock U1 helper is found and launched at run time only when the user enables it. verifyPaxxBaseline enforces that on
// the resolved runtime classpath.
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
 implementation(project(":printer-api"))
 // Printer adapters are discovered at run time (ServiceLoader); Desktop's own code never names a vendor. Which adapter
 // modules ship is a build choice: -PnozzleAdapters=paxx,octoprint,prusa,bambu (PAXX U1, the flagship, is the default
 // minimum). Building with fewer proves that removing one vendor never breaks another.
 providers.gradleProperty("nozzleAdapters").orElse("paxx,octoprint,prusa,bambu").get().split(",").map { it.trim() }.filter { it.isNotEmpty() }
  .forEach { runtimeOnly(project(":adapter-$it")) }
 testImplementation(project(":adapter-paxx"))
 implementation(project(":project-format"))
 implementation(compose.desktop.currentOs)
 implementation(compose.foundation)
 implementation(compose.ui)
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
 implementation("org.json:json:20240303")
 testImplementation("junit:junit:4.13.2")
 testImplementation(compose.desktop.uiTestJUnit4)
}

// One source for printer profiles: the flattened OrcaSlicer profiles Android already bundles (scripts/flatten_orca_profile.py).
tasks.named<ProcessResources>("processResources") {
 from(rootProject.file("app/src/main/assets/slicer_profiles")) { into("profiles"); exclude("PROVENANCE.md") }
 // Profiles the desktop engine (Snapmaker Orca base) can't slice yet; the catalog leaves them out.
 from(rootProject.file("engine/snapmaker/unsupported-profiles.json")) { into("profiles") }
 // Snapmaker Orca's filament colour library (Full Spectrum's recommended palette), from the pinned commit.
 from(rootProject.file("engine/snapmaker/resources/filaments_colours.json")) { into("fullspectrum") }
 // Per-slot filament profiles, per printer profile (scripts/bundle_filament_library.py).
 from(rootProject.file("engine/snapmaker/filaments")) { into("filaments") }
 // Every engine setting (exported from the engine itself) and Nozzle's own grouping of them.
 from(rootProject.file("schemas/slicing")) { into("settings"); include("settings-schema.json", "settings-groups.json") }
}

val nozzleVersion = providers.gradleProperty("nozzleDesktopVersion").orElse("0.1.0")

// The native slicing engine (engine/native: the same patched OrcaSlicer source and shared bridge as Android and the Web
// App). -PnozzleEngine=/path/to/nozzle-engine; defaults to engine/native/scripts/build_engine_snapmaker.sh's output. When the file
// exists it is bundled into the Linux distribution's app resources, where SliceEngine.locateNative() looks first.
val nozzleEngine = providers.gradleProperty("nozzleEngine").orElse("/mnt/faststorage/build-work/nozzle-native-sm/dist/nozzle-engine")
val engineResources = layout.buildDirectory.dir("engine-resources")
val prepareEngineResources = tasks.register<Sync>("prepareEngineResources") {
 from(nozzleEngine.map { path -> files(path).filter { it.isFile } }) {
  into("linux-x64")
  rename { "nozzle-engine" }
  filePermissions { unix("rwxr-xr-x") }
 }
 into(engineResources)
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(prepareEngineResources) }

compose.desktop {
 application {
  mainClass = "com.nozzleitall.desktop.MainKt"
  jvmArgs += listOf("-Dnozzle.version=${nozzleVersion.get()}", "-Dsun.java2d.uiScale.enabled=true",
   // Lets Main.kt name the window class so Linux docks show the right icon.
   "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED")
  nativeDistributions {
   appResourcesRootDir.set(engineResources)
   targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi)
   packageName = "nozzle-it-all"
   packageVersion = nozzleVersion.get()
   description = "Nozzle It All: printer management, project preparation, slicing and monitoring"
   vendor = "Nozzle It All"
   copyright = "Copyright 2026 Nozzle It All contributors. AGPL-3.0-or-later."
   licenseFile.set(rootProject.file("LICENSE"))
   // Only what the app uses, so the bundled runtime stays small.
   modules("java.desktop", "java.logging", "java.net.http", "java.xml", "jdk.unsupported", "java.naming")
   linux {
    packageName = "nozzle-it-all"
    debMaintainer = "support@nozzleitall.com"
    menuGroup = "Graphics;3DGraphics;Engineering"
    appCategory = "graphics"
    iconFile.set(project.file("packaging/icons/nozzle-it-all-512.png"))
   }
   windows {
    upgradeUuid = "7c4f6a1e-5d9b-4c52-9a0e-6b2f0d1c8e41"
    menuGroup = "Nozzle It All"
    perUserInstall = true
    iconFile.set(project.file("packaging/icons/nozzle-it-all.ico"))
   }
  }
 }
}

// PAXX baseline guard: nothing on Desktop's runtime classpath may be the Stock helper or a vendor-cloud/Flutter library.
val verifyPaxxBaseline = tasks.register("verifyPaxxBaseline") {
 val runtime = configurations.named("runtimeClasspath")
 doLast {
  // Vendor clouds, Flutter and the optional Stock U1 helper must never be part of Desktop. (Local protocol libraries
  // such as Bambu's LAN MQTT client may be, inside their own adapter module.)
  val banned = listOf("stock-u1-adapter", "paho", "flutter", "firebase", "snapmaker-cloud")
  val offenders = runtime.get().resolvedConfiguration.resolvedArtifacts.map { it.moduleVersion.id.toString() + " (" + it.file.name + ")" }
   .filter { name -> banned.any { name.contains(it, ignoreCase = true) } }
  if (offenders.isNotEmpty()) throw GradleException("PAXX baseline violated; Desktop's runtime classpath contains: $offenders")
  val sources = fileTree("src/main") { include("**/*.kt") }.files
  val badImports = sources.filter { f -> f.readLines().any { it.startsWith("import com.nozzleitall.stocku1") || it.startsWith("import com.nozzleitall.adapter.") } }
  if (badImports.isNotEmpty()) throw GradleException("Desktop must not import adapter modules (they're discovered at run time): $badImports")
 }
}
tasks.named("check") { dependsOn(verifyPaxxBaseline) }

tasks.withType<Test>().configureEach {
 // Room for real-world models (a million-triangle painted 3MF needs more than the 512 MB default).
 maxHeapSize = "4g"
 // Tests slice with the native engine when it has been built (SliceEngine.locateNative()).
 systemProperty("nozzle.engine", nozzleEngine.get())
 // LocalConnectorTest sends a forged Host header to prove the DNS-rebinding check.
 systemProperty("sun.net.http.allowRestrictedHeaders", "true")
 systemProperty("nozzle.adapters", providers.gradleProperty("nozzleAdapters").orElse("paxx,octoprint,prusa,bambu").get())
}
