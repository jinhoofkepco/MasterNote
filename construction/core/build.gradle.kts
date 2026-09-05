plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.studyink.construction.core"
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
    // Published Apache implementation; see PROVENANCE.md and packaged license assets.
    implementation("org.apache.commons:commons-math3:3.6.1")
    testImplementation("junit:junit:4.13.2")
}
