plugins {
    id("org.jetbrains.intellij.platform") version "2.10.5"
    java
    kotlin("jvm") version "2.2.0"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2025.3")
        bundledPlugins("com.intellij.java")
        bundledPlugins("org.jetbrains.kotlin")
    }
}
