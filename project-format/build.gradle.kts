plugins { id("org.jetbrains.kotlin.jvm") }
// Canonical 3MF + versioned Nozzle manifest: the project format every Nozzle It All platform reads and writes, and the
// interchange between Nozzle It All platforms. Pure JVM (java.util.zip, javax.xml, org.json); no UI, no network.
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
 compileOnly("org.json:json:20240303")
 testImplementation("junit:junit:4.13.2"); testImplementation("org.json:json:20240303")
}
