plugins {
    id("com.android.application") version "8.13.2" apply false
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.serialization") version "2.3.21" apply false
}

subprojects {
    group = "com.maternote.studio"
    version = "0.1.0"
}
