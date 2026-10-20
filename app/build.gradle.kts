import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.adityabalagere.photosorter"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.adityabalagere.photosorter"
        minSdk = 26
        // Play requires recent target levels for new apps (Android 16 = API 36)
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.0"
        ndk {
            // the VLM native lib is only built for 64-bit (modern phones)
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                // llama.cpp at -O0 (the Debug default) is 10-50x slower - a photo took minutes.
                // Always build the native code optimized, even in debug APKs.
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    // Upload key for Play: values live OUTSIDE the repo in keystore.properties
    // (storeFile, storePassword, keyAlias, keyPassword). Without it, release builds are unsigned.
    val keyProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keyProps.getProperty("storeFile") != null) {
            create("upload") {
                storeFile = file(keyProps.getProperty("storeFile"))
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("upload")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // On-device VLM (llama.cpp + Qwen2.5-VL) for local photo classification
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "29.0.14206865"
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // On-device image labeler (offline mode). 0.10.35+ ships 16 KB page-aligned native
    // libs (required on Android 15+ / 16 KB devices); older 0.10.x builds crash at load.
    implementation("com.google.mediapipe:tasks-vision:0.10.35")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.json:json:20240303") // real org.json for JVM tests (Android's is a stub)
}
