plugins { id("org.jetbrains.kotlin.jvm") }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
 api(project(":environment-api"))
 api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
 compileOnly("org.json:json:20240303")
 testImplementation("org.json:json:20240303")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.xerial:sqlite-jdbc:3.49.1.0")
}
tasks.withType<Test>().configureEach {
 systemProperty("nia.environment.benchmark", providers.gradleProperty("environmentBenchmark").orElse("false").get())
}
