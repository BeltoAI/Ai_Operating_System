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
        minSdk = 29   // Android 10+ — covers Note 20 (max Android 13) and most Samsungs since ~2019
        targetSdk = 35
        // Auto-bump: every build gets a higher versionCode (minutes since 2024) so a rebuilt APK always
        // installs OVER the old one as an update. Makes republishing effortless — just rebuild.
        versionCode = ((System.currentTimeMillis() / 60000) - 28_900_000).toInt()
        versionName = "0.3." + (((System.currentTimeMillis() / 86_400_000) - 19_700)).toString()
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"$anthropicKey\"")
        buildConfigField("String", "TWITTER_API_KEY", "\"${apiKeyProps.getProperty("TWITTER_API_KEY", "")}\"")
        buildConfigField("String", "TWITTER_API_SECRET", "\"${apiKeyProps.getProperty("TWITTER_API_SECRET", "")}\"")
        buildConfigField("String", "TWITTER_ACCESS_TOKEN", "\"${apiKeyProps.getProperty("TWITTER_ACCESS_TOKEN", "")}\"")
        buildConfigField("String", "TWITTER_ACCESS_SECRET", "\"${apiKeyProps.getProperty("TWITTER_ACCESS_SECRET", "")}\"")
        buildConfigField("String", "TELEGRAM_BOT_TOKEN", "\"${apiKeyProps.getProperty("TELEGRAM_BOT_TOKEN", "")}\"")
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
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
