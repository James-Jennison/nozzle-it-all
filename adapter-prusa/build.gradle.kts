plugins { id("org.jetbrains.kotlin.jvm") }
// The prusa printer adapter. Independent of every other adapter: removing this module leaves the rest of Nozzle intact.
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
 api(project(":adapter-common"))
 compileOnly("org.json:json:20240303")
 testImplementation("junit:junit:4.13.2"); testImplementation("org.json:json:20240303")
 testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
