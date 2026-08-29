plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.studyink.assistant.core"
    compileSdk = 36
    compileSdkExtension = 19

    defaultConfig { minSdk = 31 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(project(":core:model"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
