plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "link.thesymbolic.jetcommand"
    compileSdk = 37
    // Must match flake.nix: the Nix SDK is read-only, so AGP cannot fetch its default.
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "link.thesymbolic.jetcommand"
        // 31: BLUETOOTH_SCAN/CONNECT runtime permissions replace the location-permission dance.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { compose = true }

    lint {
        checkAllWarnings = true
        warningsAsErrors = true
        checkDependencies = true
        // Network lookups would make `check` fail on a later day with identical code;
        // synthetic accessors cost nothing on ART; strong skipping (default since Kotlin 2.0.20)
        // skips on instance equality, so unstable collection params no longer defeat skipping.
        disable +=
            setOf(
                "NewerVersionAvailable",
                "GradleDependency",
                "SyntheticAccessor",
                "ComposeUnstableCollections",
            )
        baseline = file("lint-baseline.xml")
    }
}

kotlin { jvmToolchain(21) }

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    implementation(project(":protocol"))
    implementation(libs.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.room.runtime)
    implementation(libs.vico.compose.m3)
    ksp(libs.room.compiler)
    debugImplementation(libs.compose.ui.tooling)
    lintChecks(libs.compose.lint.checks)
}
