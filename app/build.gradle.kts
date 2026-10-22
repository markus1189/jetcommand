plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":protocol"))
    implementation(libs.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.ui.tooling)
}
