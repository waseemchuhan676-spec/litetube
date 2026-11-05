plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.litetube.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.litetube.app"
        minSdk = 21
        targetSdk = 34
        versionCode = 6
        versionName = "1.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing reads from environment variables so no key material
    // ever lands in git. CI restores the keystore from GitHub secrets (see
    // the update hub GUIDE.md); local release builds need a release.keystore
    // file or KEYSTORE_PATH set, otherwise signing fails at package time.
    // Keep the keystore forever: Android refuses to update an app signed
    // with a different key.
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // R8/minify stays OFF for v1 on purpose: NewPipeExtractor does heavy
            // reflective parsing and Media3 keeps many entry points; shrinking
            // without a proven keep-rules set is the fastest way to break
            // extraction on low-end TV boxes. Enable it only after a full
            // release-test pass with a matching proguard-rules.pro.
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        // Required for BuildConfig.VERSION_CODE, used by UpdateManager.
        // AGP 8 does not generate BuildConfig otherwise.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required for NewPipeExtractor on minSdk < 33: the extractor uses
        // java.nio APIs that only exist on old devices through desugaring.
        // The _nio artifact (not the plain one) is what the extractor needs.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // AndroidX core + lifecycle (lifecycleScope for MainActivity).
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    // Search results list (not pulled in transitively by appcompat).
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Kotlin coroutines.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // HTTP client: used by the NewPipeExtractor Downloader implementation
    // and by the MediaStore download path (API 29+).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // AndroidX Media3 (ExoPlayer). All Media3 artifacts MUST share one version.
    val media3Version = "1.3.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    // OkHttp-backed data source so stream URLs can carry a User-Agent header.
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")

    // NewPipeExtractor: client-side stream resolution, no proxy, no server.
    // Pinned to a verified JitPack release; bump this one line when YouTube
    // changes break extraction (that is the only maintenance this app needs).
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")

    // Desugaring runtime for minSdk 21 (see compileOptions note above).
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")
}
