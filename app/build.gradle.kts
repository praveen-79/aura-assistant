plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aura.assistant"
    compileSdk = 36

    val envFile = rootProject.file(".env")
    val geminiKeyFromEnv = if (envFile.exists()) {
        envFile.readLines()
            .firstOrNull { it.trim().startsWith("GEMINI_API_KEY=") }
            ?.substringAfter("GEMINI_API_KEY=")
            ?.trim() ?: ""
    } else ""

    defaultConfig {
        applicationId = "com.aura.voiceassistant"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "4.5.0"
        resValue("string", "default_gemini_key", geminiKeyFromEnv)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}
