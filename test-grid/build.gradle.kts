plugins { id("org.jetbrains.kotlin.jvm") }
// Nozzle Test Grid: versioned test manifests, the step runner and its journal, redaction, evidence bundles and the
// compatibility report. Pure JVM (no android.*, no network client): Android's Test Mode and the command-line tool both
// drive it through the TestTarget interface. Printer rules come from :domain (firmware identity, COSMOS gating, the
// Elegoo stock-command guard), never from a second copy here.
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
 api(project(":domain"))
 // Android supplies org.json at runtime; the command-line tool adds it itself (cliRuntime below).
 compileOnly("org.json:json:20240303")
 testImplementation("junit:junit:4.13.2"); testImplementation("org.json:json:20240303")
}

val verifyNoAndroidImports = tasks.register("verifyNoAndroidImports") {
 val sources = fileTree("src") { include("**/*.kt") }
 inputs.files(sources)
 doLast {
  val banned = listOf("import android.", "import androidx.", "import okhttp3.")
  val offenders = sources.files.filter { f -> f.readLines().any { line -> banned.any { line.startsWith(it) } } }
  if (offenders.isNotEmpty()) throw GradleException("test-grid must stay platform- and transport-neutral: " + offenders.joinToString { it.name })
 }
}
tasks.named("check") { dependsOn(verifyNoAndroidImports) }

// Command-line tool: ./gradlew -q :test-grid:cli --args="help". Runs from the repository root so relative paths behave.
val cliRuntime by configurations.creating
dependencies { cliRuntime("org.json:json:20240303") }
tasks.register<JavaExec>("cli") {
 group = "application"
 description = "Nozzle Test Grid command-line tool (suites, simulated runs, bundle verification, compatibility report)."
 mainClass.set("com.nozzleitall.testgrid.cli.MainKt")
 classpath = sourceSets.main.get().runtimeClasspath + cliRuntime
 workingDir = rootProject.projectDir
 standardInput = System.`in`
}
