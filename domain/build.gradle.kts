plugins { id("org.jetbrains.kotlin.jvm") }
// Phase 9S: platform-independent logic (no android.* / androidx.*): slicing settings, mesh editing, projects' pure rules,
// G-code parsing, printer capability and control models. Same package names as before the extraction.
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
 api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
 // Android supplies org.json at runtime; it is only a compile-time (and unit-test) dependency here.
 compileOnly("org.json:json:20240303")
 testImplementation("junit:junit:4.13.2"); testImplementation("org.json:json:20240303")
}

// Phase 9S guard: shared modules must never import Android APIs, or a desktop/service build could not reuse them.
val verifyNoAndroidImports = tasks.register("verifyNoAndroidImports") {
 val sources = fileTree("src") { include("**/*.kt") }
 inputs.files(sources)
 doLast {
  val offenders = sources.files.filter { f -> f.readLines().any { it.startsWith("import android.") || it.startsWith("import androidx.") } }
  if (offenders.isNotEmpty()) throw GradleException("Android imports are not allowed in ${project.name}: " + offenders.joinToString { it.name })
 }
}
tasks.named("check") { dependsOn(verifyNoAndroidImports) }

// The printer profiles the slicing engine can't slice yet (engine/profiles/unsupported-profiles.json, shared with Desktop
// and the Web App), bundled as a resource so SlicingEngineSupport reads the one list instead of a copy.
val engineSupportResources = tasks.register<Copy>("engineSupportResources") {
 from(rootProject.file("engine/profiles/unsupported-profiles.json"))
 into(layout.buildDirectory.dir("generated/engine-support/net/jamesjennison/klippercompanion"))
}
sourceSets.main { resources.srcDir(files(layout.buildDirectory.dir("generated/engine-support")).builtBy(engineSupportResources)) }
