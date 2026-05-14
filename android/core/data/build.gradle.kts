plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.yourtube.core.data"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("test") {
            resources.srcDir("../../../docs/fixtures")
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    // Hilt provides the javax.inject annotations used on @Inject constructors.
    // Hilt graph generation still happens in :app (no ksp here).
    implementation(libs.hilt.android)
    // YT-0251: OkHttp is already on the version catalog; used by RemoteUpdateCheckRepository
    // for the hosted update-feed fetch. No new library is introduced.
    implementation(libs.okhttp)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    // YT-0251: MockWebServer for RemoteUpdateCheckRepositoryTest — same version as OkHttp.
    testImplementation(libs.okhttp.mockwebserver)
}
