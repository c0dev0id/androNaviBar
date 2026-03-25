plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun gitSha(): String = try {
    val proc = ProcessBuilder("git", "rev-parse", "--short=7", "HEAD")
        .redirectErrorStream(true).start()
    proc.inputStream.bufferedReader().readText().trim()
} catch (_: Exception) { "unknown" }

android {
    namespace = "de.codevoid.andronavibar"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.codevoid.andronavibar"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "GIT_SHA", "\"${gitSha()}\"")
        buildConfigField("String", "METEOBLUE_KEY", "\"${project.findProperty("meteoblue_key") ?: ""}\"")
        buildConfigField("String", "METEOBLUE_URI", "\"https://my.meteoblue.com/packages/basic-1h_basic-3h_basic-day_current\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
