plugins { id("org.jetbrains.kotlin.jvm") }
// The vendor-neutral printer model and device-adapter contract shared by Desktop (and, later, Mobile and the Engine
// Service). It must stay free of vendor transports, cloud SDKs and UI toolkits: adapters depend on it, never the
// reverse. verifyAdapterBoundary below enforces that mechanically.
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
 // Android supplies org.json at runtime; desktop adds it as a runtime dependency.
 compileOnly("org.json:json:20240303")
 testImplementation("junit:junit:4.13.2"); testImplementation("org.json:json:20240303")
}

val verifyAdapterBoundary = tasks.register("verifyAdapterBoundary") {
 val sources = fileTree("src/main") { include("**/*.kt") }
 inputs.files(sources)
 doLast {
  val banned = listOf("import android.", "import androidx.", "import okhttp3.", "import net.jamesjennison.", "import com.nozzleitall.adapter.", "import com.nozzleitall.stocku1.", "import org.eclipse.paho", "import com.hivemq")
  val offenders = sources.files.filter { f -> f.readLines().any { line -> banned.any { line.startsWith(it) } } }
  if (offenders.isNotEmpty()) throw GradleException("printer-api must stay vendor- and transport-neutral: " + offenders.joinToString { it.name })
 }
}
tasks.named("check") { dependsOn(verifyAdapterBoundary) }
