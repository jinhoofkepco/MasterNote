plugins { id("com.android.library"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android { namespace="com.studyink.library";compileSdk=36;compileSdkExtension=19;defaultConfig{minSdk=31};buildFeatures{compose=true};compileOptions{sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17} }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":annotation:storage")); implementation(project(":feature:progress")); implementation(project(":feature:reader"))
    val composeBom=platform("androidx.compose:compose-bom:2026.06.00");implementation(composeBom);implementation("androidx.activity:activity-compose:1.13.0");implementation("androidx.compose.material3:material3");implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
}
