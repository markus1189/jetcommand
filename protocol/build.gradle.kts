// Pure Kotlin: everything here runs as fast JVM tests, without Android.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.android.lint)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(libs.coroutines.core)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.serialization.json)
    testImplementation(libs.kotest.property)
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test { useJUnitPlatform() }
