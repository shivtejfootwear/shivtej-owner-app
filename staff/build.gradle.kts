// Shivtej Staff - a separate app (own name, own icon, own package) for the shop staff:
// read-only catalog (website products, MRP only) + location in shop hours. Built by the same GitHub workflow
// and signed with the same release key as the owner app, so updates install over the old version.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseKeystorePath: String? = System.getenv("SHIVTEJ_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.shivtej.staff"
    compileSdk = 34

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("SHIVTEJ_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SHIVTEJ_KEY_ALIAS")
                keyPassword = System.getenv("SHIVTEJ_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.shivtej.staff"
        minSdk = 26
        targetSdk = 34
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0"
        // Same Supabase project as the owner app and the website. The publishable key is public by design;
        // the staff functions need the staff login key, which only the owner's code can create.
        buildConfigField("String", "SUPABASE_URL", "\"https://rvufigsdclnzelmepigb.supabase.co\"")
        buildConfigField("String", "SUPABASE_KEY", "\"sb_publishable_YFlygJkQiunpibtfKOBv5w_sR96do0w\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystorePath != null) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Barcode scanner for "Make Bill" (live camera, same as the owner app)
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}
