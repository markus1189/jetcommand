plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless) apply false
}

subprojects {
    apply(plugin = "dev.detekt")
    apply(plugin = "com.diffplug.spotless")

    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("detekt.yml"))
        baseline = file("detekt-baseline.xml")
    }

    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktfmt(rootProject.libs.versions.ktfmt.get()).kotlinlangStyle()
        }
        kotlinGradle { ktfmt(rootProject.libs.versions.ktfmt.get()).kotlinlangStyle() }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
        compilerOptions.allWarningsAsErrors = true
    }
}
