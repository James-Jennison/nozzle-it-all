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
 implementation(project(":adapter-paxx"))
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
 from(rootProject.file("app/src/main/assets/slicer_profiles/snapmaker_u1")) { into("profiles/snapmaker_u1") }
}

val nozzleVersion = providers.gradleProperty("nozzleDesktopVersion").orElse("0.1.0")

compose.desktop {
 application {
  mainClass = "com.nozzleitall.desktop.MainKt"
  jvmArgs += listOf("-Dnozzle.version=${nozzleVersion.get()}", "-Dsun.java2d.uiScale.enabled=true")
  nativeDistributions {
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
  val banned = listOf("stock-u1-adapter", "paho", "hivemq", "flutter", "firebase", "snapmaker")
  val offenders = runtime.get().resolvedConfiguration.resolvedArtifacts.map { it.moduleVersion.id.toString() + " (" + it.file.name + ")" }
   .filter { name -> banned.any { name.contains(it, ignoreCase = true) } }
  if (offenders.isNotEmpty()) throw GradleException("PAXX baseline violated; Desktop's runtime classpath contains: $offenders")
  val sources = fileTree("src/main") { include("**/*.kt") }.files
  val badImports = sources.filter { f -> f.readLines().any { it.startsWith("import com.nozzleitall.stocku1") } }
  if (badImports.isNotEmpty()) throw GradleException("Desktop must not import the Stock U1 helper: $badImports")
 }
}
tasks.named("check") { dependsOn(verifyPaxxBaseline) }
