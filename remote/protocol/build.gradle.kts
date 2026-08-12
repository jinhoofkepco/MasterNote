plugins {
    id("com.android.library")
    id("com.google.protobuf")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.studyink.remote.protocol"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.35.1" }
    generateProtoTasks {
        all().configureEach {
            builtins { create("java") { option("lite") } }
        }
    }
}

dependencies {
    implementation("com.google.protobuf:protobuf-javalite:4.35.1")
    testImplementation("junit:junit:4.13.2")
}
