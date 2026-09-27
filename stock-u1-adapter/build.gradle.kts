plugins { id("org.jetbrains.kotlin.jvm"); application }
// The optional Stock U1 adapter: a separate helper process (docs/protocols/ADAPTER_PROTOCOL.md), built and packaged
// as nozzle-stock-u1-adapter. Nothing in the core depends on this module; the core only launches it when the user
// enables Stock U1 support. This is the only module allowed to contact a Snapmaker cloud host.
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
application {
 mainClass.set("com.nozzleitall.stocku1.StockU1HelperKt")
 applicationName = "nozzle-stock-u1-adapter"
}
dependencies {
 implementation(project(":printer-api"))
 // Reuses the LAN Moonraker client and U1 rules; the dependency points from optional to core, never back.
 implementation(project(":adapter-paxx"))
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 implementation("org.json:json:20240303")
 testImplementation("junit:junit:4.13.2")
}
