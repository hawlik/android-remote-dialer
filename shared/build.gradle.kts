plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Pure Kotlin/JVM library: the wire protocol has zero Android dependencies, so
// both :phone and :tablet (Android app modules) can depend on it, and it builds
// and unit-tests fast without the Android toolchain.

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // kotlin-test + JUnit4 bridge; version is aligned to the Kotlin plugin
    // automatically, so nothing to pin.
    testImplementation(kotlin("test-junit"))
}
