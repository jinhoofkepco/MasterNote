plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.studyink.remote.feature"
    compileSdk = 36
    compileSdkExtension = 19
    defaultConfig { minSdk = 31 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    api(project(":remote:session"))
    implementation(project(":remote:transport-nearby"))
    implementation(project(":remote:sync"))
    implementation(project(":feature:reader"))
    implementation(project(":annotation:storage"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
