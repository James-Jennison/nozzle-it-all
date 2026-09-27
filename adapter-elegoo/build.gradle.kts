plugins { id("org.jetbrains.kotlin.jvm") }
// The Elegoo adapter: Centauri Carbon (SDCP over a WebSocket) and Centauri Carbon 2 (MQTT) on the LAN, with the CANVAS
// filament switcher's slots. Ported from Elegoo's elegoo-link (Apache-2.0) and the SDCP V3 documentation; see
// docs/upstream/PROVENANCE.md. Independent of every other adapter: removing this module leaves the rest of Nozzle intact.
// LAN-only: no Elegoo cloud, no account. verifyNoCloud below fails the build if that ever changes.
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
 api(project(":printer-api"))
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 compileOnly("org.json:json:20240303")
 testImplementation("junit:junit:4.13.2"); testImplementation("org.json:json:20240303")
 testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

val verifyNoCloud = tasks.register("verifyNoCloud") {
 val sources = fileTree("src/main") { include("**/*.kt") }
 inputs.files(sources)
 doLast {
  // Elegoo's cloud hosts and the cloud half of elegoo-link (RTM, cloud MQTT, account HTTP) must never be reached from here.
  val banned = listOf("elegoo.com", "elegoo.com.cn", "agora", "import org.eclipse.paho", "import com.hivemq", "cloud_service", "pinCode")
  val offenders = sources.files.flatMap { f -> f.readLines().withIndex().filter { (_, l) -> banned.any { l.contains(it, ignoreCase = true) } }.map { "${f.name}:${it.index + 1}" } }
  if (offenders.isNotEmpty()) throw GradleException("The Elegoo adapter must stay LAN-only: " + offenders.joinToString())
 }
}
tasks.named("check") { dependsOn(verifyNoCloud) }
