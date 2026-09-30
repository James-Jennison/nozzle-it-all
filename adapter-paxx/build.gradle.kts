plugins { id("org.jetbrains.kotlin.jvm") }
// The PAXX LAN adapter: native Moonraker HTTP to a Snapmaker U1 running PAXX in LAN mode. Required, in process, and
// LAN-only: no vendor account, no cloud host, no Flutter. verifyNoCloud below fails the build if that ever changes.
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
  val banned = listOf("snapmaker.com", "snapmaker.cn", "import org.eclipse.paho", "import com.hivemq", "com.nozzleitall.stocku1", "flutter", "WebView")
  val offenders = sources.files.flatMap { f -> f.readLines().withIndex().filter { (_, l) -> banned.any { l.contains(it, ignoreCase = true) } }.map { "${f.name}:${it.index + 1}" } }
  if (offenders.isNotEmpty()) throw GradleException("The PAXX adapter must not reference cloud, Stock or Flutter code: " + offenders.joinToString())
 }
}
tasks.named("check") { dependsOn(verifyNoCloud) }
