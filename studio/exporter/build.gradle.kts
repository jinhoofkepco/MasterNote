plugins { kotlin("jvm") }
dependencies {
    api(project(":project"))
    implementation("com.maternote:model:1.0.0")
    implementation("com.maternote:codec:1.0.0")
    implementation("com.maternote:validator:1.0.0")
    testImplementation(kotlin("test"))
}
tasks.test { useJUnitPlatform() }
