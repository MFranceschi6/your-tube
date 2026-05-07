plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.yourtube.core.player"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    testOptions {
        // Required so JVM unit tests can invoke MediaItem.Builder().setUri(...)
        // without crashing on `android.net.Uri.parse`. Mirrors `core/network`'s
        // setting. Tests assert against the resulting MediaItem's URI surface,
        // which Media3 stores back without re-parsing in the cases under test.
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:network"))
    implementation(project(":core:data"))

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Unit tests
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // mockk is used solely by `PlaybackPlayerAdapterTest` to fabricate a
    // `MediaSource` instance for the HLS-routing assertions (final/abstract
    // surface is impractical to subclass). Other player tests continue to use
    // hand-rolled fakes per the established pattern.
    testImplementation(libs.mockk)
    // Robolectric is required for `PlaybackPlayerAdapterTest` because
    // `MediaItem.Builder.setUri` indirectly initializes Android framework
    // classes (`android.net.Uri`, `android.os.Bundle`) that have no JVM stub.
    // Other player tests don't touch those classes, so they run on the
    // standard JUnit runner and ignore Robolectric.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
