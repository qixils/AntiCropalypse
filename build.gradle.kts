plugins {
    kotlin("jvm") version "1.8.0"
    kotlin("plugin.serialization") version "1.8.10"
}

group = "dev.qixils.acropalypse"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://jitpack.io/")
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("net.dv8tion:JDA:${property("jda.version")}")
    implementation("com.github.minndevelopment:jda-ktx:${property("jda-ktx.version")}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:${property("serialization.version")}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${property("coroutines.version")}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:${property("coroutines.version")}")
    implementation("ch.qos.logback:logback-classic:${property("logback.version")}")
    implementation("org.slf4j:slf4j-api:${property("slf4j.version")}")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}