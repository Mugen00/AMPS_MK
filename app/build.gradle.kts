plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.amps.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "dev.amps.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 7
        versionName = "1.0.5"
        resourceConfigurations += listOf("ru", "en")

        ndk {
            // Только настоящие телефоны. ML Kit тянет за собой
            // `libmlkitcommonpipeline.so` весом около 12 МБ на каждую
            // архитектуру, и без фильтра в APK попадают ещё и x86 с x86_64 —
            // а это примерно 24 МБ, нужные исключительно эмуляторам. Приложение
            // на эмуляторе не запускают, а APK раздаётся напрямую одним файлом,
            // поэтому эти две лишние архитектуры — чистый вес.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            val storePath = rootProject.file("keystore/amps.jks")
            if (storePath.exists()) {
                storeFile = storePath
                storePassword = (rootProject.file("keystore/keystore.properties").takeIf { it.exists() }
                    ?.readLines()
                    ?.firstOrNull { it.startsWith("storePassword=") }
                    ?.substringAfter("=")) ?: ""
                keyAlias = "amps"
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val storePath = rootProject.file("keystore/amps.jks")
            signingConfig = if (storePath.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    lint {
        // lint-gradle is a separate download; the release APK must build offline.
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // 1.0.2: object/scene labels for the frame itself, fully on-device.
    implementation(libs.mlkit.image.labeling)
    implementation(libs.mlkit.image.labeling.common)
}
