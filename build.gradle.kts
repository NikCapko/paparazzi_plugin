plugins {
    id ("org.jetbrains.intellij") version "1.17.2"
    java
    kotlin("jvm") version "1.9.0"
    id("org.jetbrains.compose") version "1.5.0"
}

group = "com.nikcapko.paparazzi"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
}

intellij {
    version.value("IC-2023.3.5")
//    version.set("232.10300.40.2321.11567975")
//    type.set("AI")
//    plugins.set(listOf("android"))

    plugins.set(listOf(
        "com.intellij.java",     // ← ВАЖНО
        "org.jetbrains.kotlin"   // если используешь Kotlin PSI
    ))
}

tasks.getByName<org.jetbrains.intellij.tasks.PatchPluginXmlTask>("patchPluginXml") {}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(compose.desktop.currentOs)           // базовый Compose
    implementation(compose.material3)
}
