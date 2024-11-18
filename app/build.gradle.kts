plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("kotlin-kapt")
}

android {
    namespace = "com.linkitmediagroup.linkitmediaplayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.linkitmediagroup.linkitmediaplayer"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    kapt {
        arguments {
            arg("room.schemaLocation", "$projectDir/schemas") // For Room database schema export
        }
    }
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.4.0")
    implementation("androidx.leanback:leanback:1.0.0")

    // Firebase
    implementation("com.google.firebase:firebase-database-ktx:20.2.1")
    implementation("com.google.firebase:firebase-storage-ktx:20.2.1")
    implementation("com.google.firebase:firebase-common-ktx:20.2.1")

    // Room (for offline caching)
    implementation("androidx.room:room-runtime:2.5.1")
    kapt("androidx.room:room-compiler:2.5.1")

    // Glide (for image loading and caching)
    implementation("com.github.bumptech.glide:glide:4.15.1") // Updated Glide version
    kapt("com.github.bumptech.glide:compiler:4.15.1")
}
