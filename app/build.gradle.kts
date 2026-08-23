plugins {
    id("com.android.application")
}

android {
    namespace = "cl.streambox.tv"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    val ciKeystorePath = System.getenv("VIBEM3U_KEYSTORE_PATH")
    val ciSigningConfig = if (!ciKeystorePath.isNullOrBlank()) {
        signingConfigs.create("github") {
            storeFile = file(ciKeystorePath)
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    } else {
        null
    }

    defaultConfig {
        applicationId = "cl.streambox.tv"
        minSdk = 23
        targetSdk = 36
        versionCode = 29
        versionName = "0.4.24"
    }

    buildTypes {
        getByName("debug") {
            ciSigningConfig?.let { signingConfig = it }
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            ciSigningConfig?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core:1.17.0")
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")

    testImplementation("junit:junit:4.13.2")
}
