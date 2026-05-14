plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.yourtube.core.ui"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.reorderable)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Round 4 — pure-Kotlin unit tests for `PlayerOverlayState` (animatable drivers) and
    // the lerp helpers used by `PlayerOverlay`. Animatable.animateTo / snapTo are suspend
    // functions, so the tests use kotlinx-coroutines-test's `runTest` to advance virtual
    // time deterministically. Compose UI tests live in `app/src/test/` where Robolectric is
    // already wired.
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    val testComposeBom = platform(libs.androidx.compose.bom)
    testImplementation(testComposeBom)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
}
