import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// API key lives in apikey.properties (gitignored), never in source.
val apiKeyProps = Properties()
val apiKeyFile = rootProject.file("apikey.properties")
if (apiKeyFile.exists()) apiKeyProps.load(apiKeyFile.inputStream())
val anthropicKey: String = apiKeyProps.getProperty("ANTHROPIC_API_KEY", "")

android {
    namespace = "com.agentos.shell"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.agentos.shell"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0"
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"$anthropicKey\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
