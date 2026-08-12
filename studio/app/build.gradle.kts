plugins { id("com.android.application") }

android {
    namespace = "com.maternote.studio"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.maternote.studio"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":project"))
    implementation(project(":editor"))
    implementation(project(":preview"))
}
