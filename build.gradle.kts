plugins {
    kotlin("jvm") version "1.8.10" apply true
    kotlin("plugin.serialization") version "1.8.10" apply false
}

allprojects {
    group = "dev.qixils.acropalypse"
    version = "1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

    dependencies {
        testImplementation(kotlin("test"))
    }

    tasks.test {
        useJUnitPlatform()
    }

    kotlin {
        jvmToolchain(17)
    }
}
