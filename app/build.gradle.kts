plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "br.com.minhamidia"
    compileSdk = 35
    defaultConfig {
        applicationId = "br.com.cineverse"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("appVersionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("appVersionName").orNull ?: "1.0.0"
        buildConfigField("String", "TMDB_API_KEY", "\"ed317a1df951109bde5285a44be90bd9\"")
    }
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("CINEVERSE_KEYSTORE_FILE") ?: "release.keystore")
            storePassword = System.getenv("CINEVERSE_STORE_PASSWORD") ?: ""
            keyAlias = System.getenv("CINEVERSE_KEY_ALIAS") ?: "cineverse"
            keyPassword = System.getenv("CINEVERSE_KEY_PASSWORD") ?: ""
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { jvmToolchain(17) }
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.work:work-runtime-ktx:2.10.3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
}