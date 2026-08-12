plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val masterNoteKeystorePath = providers.environmentVariable("MASTERNOTE_KEYSTORE_PATH").orNull
val masterNoteBuildNumber = providers.environmentVariable("MASTERNOTE_BUILD_NUMBER").orNull?.toIntOrNull()

android {
    namespace = "com.studyink.app"
    compileSdk = 36
    compileSdkExtension = 19

    defaultConfig {
        applicationId = "com.studyink.app"
        minSdk = 31
        targetSdk = 36
        versionCode = masterNoteBuildNumber?.let { 10_000 + it } ?: 2
        versionName = "0.2.${masterNoteBuildNumber ?: 0}-test"
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
    implementation(project(":feature:progress"))
    implementation(project(":feature:reader"))
    implementation(project(":feature:teacher"))
    androidTestImplementation(project(":annotation:storage"))
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
    androidTestImplementation("androidx.fragment:fragment-ktx:1.8.9")
}
