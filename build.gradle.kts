plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
    id("com.google.devtools.ksp") version "1.9.24-1.0.20"
}

// FLAT layout: every .kt file sits next to this file. They are copied into build/flat-src before compilation,
// so no other folder (src/main/java ...) is needed in the repository.
val copyFlat = tasks.register<Copy>("copyFlatSources") {
    from(projectDir.listFiles { f -> f.extension == "kt" }!!)
    into(layout.buildDirectory.dir("flat-src"))
}
tasks.configureEach {
    if (name.startsWith("ksp") || (name.startsWith("compile") && name.contains("Kotlin"))) dependsOn(copyFlat)
}

android {
    namespace = "com.streamtv.iptv"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.streamtv.iptv"
        minSdk = 23
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "2").toInt()
        versionName = "2.1.0"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") } // keeps the APK smaller (TV boxes are ARM)
    }
    sourceSets.getByName("main") {
        manifest.srcFile("AndroidManifest.xml")
        java.srcDir(layout.buildDirectory.dir("flat-src"))
    }
    // fixed signing key: every build (local or GitHub) has the same signature, so updates install over the old app
    signingConfigs {
        create("fixed") {
            storeFile = file("streamtv.p12"); storePassword = "streamtv123"
            keyAlias = "streamtv"; keyPassword = "streamtv123"; storeType = "pkcs12"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("fixed") }
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("fixed") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    lint { abortOnError = false; checkReleaseBuilds = false }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.pickFirsts += "**/libc++_shared.so"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.core:core-ktx:1.13.1")
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("net.zetetic:sqlcipher-android:4.5.6@aar")
    implementation("androidx.sqlite:sqlite:2.4.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.videolan.android:libvlc-all:3.6.0")
}
