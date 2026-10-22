// Pure Kotlin: everything here runs as fast JVM tests, without Android.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    testImplementation(libs.kotlin.test)
    testImplementation(libs.serialization.json)
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test { useJUnitPlatform() }
