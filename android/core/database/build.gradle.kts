plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.yourtube.core.database"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)

    ksp(libs.androidx.room.compiler)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
