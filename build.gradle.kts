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

intellijPlatform {
    pluginConfiguration {
        group = "com.nikcapko"
        version = "1.0"

        ideaVersion {
            sinceBuild = "231"
            untilBuild = "254.*"
        }
    }
}