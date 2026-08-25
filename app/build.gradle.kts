plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val masterNoteKeystorePath = providers.environmentVariable("MASTERNOTE_KEYSTORE_PATH").orNull
val masterNoteVersionCode = providers.environmentVariable("MASTERNOTE_VERSION_CODE").orNull?.toIntOrNull()
val masterNoteVersionName = providers.environmentVariable("MASTERNOTE_VERSION_NAME").orNull
val releaseTaskRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }

if (releaseTaskRequested) {
    require(!masterNoteKeystorePath.isNullOrBlank()) {
        "Release builds require MASTERNOTE_KEYSTORE_PATH so the update signing identity cannot change."
    }
    require(masterNoteVersionCode != null && masterNoteVersionCode > 10_009) {
        "Release builds require MASTERNOTE_VERSION_CODE greater than the last published version (10009)."
    }
    require(!masterNoteVersionName.isNullOrBlank()) {
        "Release builds require MASTERNOTE_VERSION_NAME."
    }
}

android {
    namespace = "com.studyink.app"
    compileSdk = 36
    compileSdkExtension = 19

    defaultConfig {
        applicationId = "com.studyink.app"
        minSdk = 31
        targetSdk = 36
        versionCode = masterNoteVersionCode ?: 1
        versionName = masterNoteVersionName ?: "0.0.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            if (masterNoteKeystorePath != null) {
                storeFile = file(masterNoteKeystorePath)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            // A development build is a separate app, so it can never replace or downgrade the
            // fixed-signature APK downloaded from GitHub Releases.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":feature:library"))
    implementation(project(":monitor:core"))
    implementation(project(":monitor:telegram"))
    implementation(project(":monitor:render"))
    implementation(project(":library:data"))
    implementation(project(":annotation:storage"))
    implementation(project(":sync:lan"))
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.4")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
    androidTestImplementation("androidx.fragment:fragment-ktx:1.8.9")
    androidTestImplementation(project(":annotation:storage"))
    androidTestImplementation(project(":library:data"))
    androidTestImplementation(project(":sync:lan"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
