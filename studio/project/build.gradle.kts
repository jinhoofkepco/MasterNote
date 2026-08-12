plugins { kotlin("jvm"); kotlin("plugin.serialization") }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation(kotlin("test"))
}
tasks.test { useJUnitPlatform() }
