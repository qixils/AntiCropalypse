plugins {
    application
}

repositories {
    mavenCentral()
    maven("https://jitpack.io/")
}

dependencies {
    implementation("net.dv8tion:JDA:${property("jda.version")}")
    implementation("com.github.minndevelopment:jda-ktx:${property("jda-ktx.version")}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:${property("serialization.version")}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${property("coroutines.version")}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:${property("coroutines.version")}")
    implementation("ch.qos.logback:logback-classic:${property("logback.version")}")
    implementation("org.slf4j:slf4j-api:${property("slf4j.version")}")
}

application {
    mainClass.set("dev.qixils.acropalypse.BotKt")
}

tasks {
    jar {
        archiveBaseName.set("anticropalypse")
    }
}
