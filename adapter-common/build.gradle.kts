plugins { id("org.jetbrains.kotlin.jvm") }
// Bridge from the existing, device-tested :transport clients (Android's) to the shared printer-api session contract,
// so each vendor adapter module reuses real protocol code instead of re-implementing it.
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
 api(project(":printer-api")); api(project(":transport"))
 compileOnly("org.json:json:20240303")
 testImplementation("junit:junit:4.13.2"); testImplementation("org.json:json:20240303")
}
