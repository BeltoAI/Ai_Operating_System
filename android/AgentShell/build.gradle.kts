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

// Google OAuth (for creating Google Meet links on real Calendar events). The client ID is the app's
// public identity to Google — NOT a secret, and NOT your account: each user signs into their own
// Google account. The redirect scheme is the reversed client ID, required statically in the manifest.
val googleClientId: String = apiKeyProps.getProperty("GOOGLE_OAUTH_CLIENT_ID", "")
val googleRedirectScheme: String = if (googleClientId.endsWith(".apps.googleusercontent.com"))
    "com.googleusercontent.apps." + googleClientId.substringBefore(".apps.googleusercontent.com")
else "com.agentos.shell.noauth"

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
        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"$googleClientId\"")
        buildConfigField("String", "GOOGLE_REDIRECT_SCHEME", "\"$googleRedirectScheme\"")
        // SlyOS account/sync backend (Supabase). The anon key is safe to ship in the client; the URL is public.
        // Both come from apikey.properties (gitignored). See ACCOUNT_AND_SYNC.md for the DB contract.
        buildConfigField("String", "SUPABASE_URL", "\"${apiKeyProps.getProperty("SUPABASE_URL", "")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${apiKeyProps.getProperty("SUPABASE_ANON_KEY", "")}\"")
        // Shared Vercel token (BeltoAI-owned) so agents can ship user sites LIVE to a RENDERED URL with zero
        // per-user setup. Supabase Storage serves HTML as text/plain, so it can't host live pages. From apikey.properties.
        buildConfigField("String", "VERCEL_TOKEN", "\"${apiKeyProps.getProperty("VERCEL_TOKEN", "")}\"")
        buildConfigField("String", "NETLIFY_TOKEN", "\"${apiKeyProps.getProperty("NETLIFY_TOKEN", "")}\"")
        // Image model key (prompt-based generate + edit — Claude has no image model). Add ONE of these to
        // apikey.properties: OPENAI_API_KEY (gpt-image-1) or GEMINI_API_KEY. Blank = native edits still work.
        buildConfigField("String", "OPENAI_API_KEY", "\"${apiKeyProps.getProperty("OPENAI_API_KEY", "")}\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"${apiKeyProps.getProperty("GEMINI_API_KEY", "")}\"")
        // PERSONAL-ONLY features (e.g. Chess Coach) — only ON when ENABLE_CHESS=true is present in the local,
        // gitignored apikey.properties. CI / the public website build never has that line → defaults false →
        // the feature is compiled out of every release everyone else downloads.
        buildConfigField("boolean", "ENABLE_CHESS", apiKeyProps.getProperty("ENABLE_CHESS", "false"))
        manifestPlaceholders["googleRedirectScheme"] = googleRedirectScheme
    }
    // Consistent signing across every machine AND CI, so a newer APK always installs OVER the old one as
    // an update (no uninstall needed). This is the standard Android DEBUG key (password "android") — it
    // carries no secrecy value, it just fixes the signature so releases are interchangeable. Committed on
    // purpose. (For a Play-Store-grade release key, override via CI secrets instead.)
    signingConfigs {
        val ks = rootProject.file("debug.keystore")
        if (ks.exists()) {
            getByName("debug") {
                storeFile = ks; storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
            }
        }
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
    // Chrome Custom Tabs for the in-app Google sign-in flow.
    implementation("androidx.browser:browser:1.8.0")
    // CameraX — live viewfinder + frame capture for "Look" mode.
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    // On-device object detection — draws a real box around the object you tap in Look mode.
    implementation("com.google.mlkit:object-detection:17.0.2")
    // On-device background removal (Powers → rembg runs natively, zero setup, no Termux/server).
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    // On-device photo understanding — labels + faces, so we index thousands of gallery photos for FREE
    // (no per-image API cost) to find "a full-body photo of me" without captioning the whole library.
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:face-detection:16.1.7")
    // On-device pose detection (BlazePose) — confirms a FULL body (ankles in frame), not a phone-killer like
    // OpenPose. Free, offline. Runs only on the person photos where the face is small enough to be unsure.
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
    // On-device OCR — reads the TEXT inside every photo/screenshot (whiteboards, receipts, book pages) so it
    // becomes searchable in the brain. And barcodes/QR. Both free, offline.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // Text intelligence — all on-device, free, offline:
    implementation("com.google.mlkit:translate:17.0.3")                 // translate any message/doc
    implementation("com.google.mlkit:language-id:17.0.6")               // detect language
    implementation("com.google.mlkit:entity-extraction:16.0.0-beta6")  // pull phones/emails/addresses/dates
    implementation("com.google.mlkit:smart-reply:17.0.4")              // suggested replies
    // On-device LLM inference (free, offline endpoint). Called by reflection in LocalLlm, so the app still
    // compiles if this line is removed; present here it powers the local model.
    implementation("com.google.mediapipe:tasks-genai:0.10.24")
    // On-device text embeddings (Universal Sentence Encoder) — free, private, unlimited semantic memory.
    implementation("com.google.mediapipe:tasks-text:0.10.21")
}
