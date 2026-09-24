plugins { id("org.jetbrains.kotlin.jvm") }
// Phase 9S: printer network transports (Moonraker/HTTP, Prusa Link, Bambu MQTT/FTPS/camera, Bespok3d SSH). Depends only
// on :domain and JVM libraries, so a desktop or service build can reuse it.
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
 api(project(":domain"))
 api("com.squareup.okhttp3:okhttp:4.12.0")
 api("com.github.mwiede:jsch:2.28.6")
 api("org.bouncycastle:bcprov-jdk18on:1.83"); api("org.bouncycastle:bcpg-jdk18on:1.83"); api("org.bouncycastle:bctls-jdk18on:1.83")
 api("com.hivemq:hivemq-mqtt-client:1.3.17")
 api("commons-net:commons-net:3.13.0")
 compileOnly("org.json:json:20240303")
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
