import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Meta Wearables DAT credentials — read from local.properties (git-ignored) so the secret
// CLIENT_TOKEN never lands in the tracked gradle.properties. Falls back to gradle property / "0".
val mwdatLocalProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun mwdatCredential(key: String): String =
    mwdatLocalProps.getProperty(key)
        ?: providers.gradleProperty(key).getOrElse("0")

android {
    namespace = "com.ctl.metaglasses"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ctl.metaglasses"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["mwdat_application_id"] = mwdatCredential("mwdat_application_id")
        manifestPlaceholders["mwdat_client_token"] = mwdatCredential("mwdat_client_token")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign with the debug key (its hash is registered in the Wearables Developer Center,
            // so DAT attestation still passes) but keep the build non-debuggable — that stops the
            // Android 16 "16 KB compatibility" nag dialog, which only shows for debuggable apps.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // Store native libs uncompressed + page-aligned in the APK (better 16 KB behaviour).
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    implementation(libs.mwdat.display)
    implementation(libs.mwdat.mockdevice)

    // On-device face detection (real-time) + fine-tuned age classifiers.
    implementation(libs.mlkit.face.detection)
    implementation(libs.tensorflow.lite)
    // Second age model (ONNX logistic-regression) used in a voting ensemble with the TFLite one.
    implementation(libs.onnxruntime.android)

    // YouTube live: Google Sign-In (OAuth token for the YouTube scope) + RootEncoder (RTMP push).
    implementation(libs.play.services.auth)
    implementation(libs.rootencoder)

    // Phone-camera trial mode (standalone, no glasses).
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)

    // Local/on-phone sign-language pipeline pieces.
    // Uncomment after choosing exact model/runtime versions.
    // implementation("com.google.mediapipe:tasks-vision:0.10.26")
    // implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    // Meta Wearables DAT SDK placeholder.
    // Uncomment/replace after receiving Meta DAT developer-preview access and the exact artifact name.
    // implementation("com.meta.wearables:device-access-toolkit-android:<version>")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
