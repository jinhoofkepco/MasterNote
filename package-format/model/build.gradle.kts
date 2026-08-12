plugins { kotlin("jvm"); kotlin("plugin.serialization") }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies { api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0") }
