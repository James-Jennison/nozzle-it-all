plugins {
 id("com.android.application") version "8.13.2" apply false
 id("org.jetbrains.kotlin.android") version "2.2.21" apply false
 id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
 // Room's annotation processor (Phase 0, Consumer Slicer Plan §16: Project/ProjectObject
 // infrastructure). Version pinned to the exact Kotlin 2.2.21 build KSP publishes for it -
 // see https://github.com/google/ksp/releases, not just "latest".
 id("com.google.devtools.ksp") version "2.2.21-2.0.5" apply false
}
