import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // SFTP / SSH transport. Shipped inside the plugin jar.
    implementation("com.github.mwiede:jsch:2.28.7")

    intellijPlatform {
        val localPath = providers.gradleProperty("localIdePath").orNull
        if (!localPath.isNullOrBlank() && file(localPath).exists()) {
            local(localPath)
        } else {
            phpstorm("2026.2")
        }
        testFramework(TestFrameworkType.Platform)
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Without this Kotlin copies every default method of the platform interfaces into
        // the implementing class, which the Plugin Verifier then reports as internal,
        // deprecated and experimental API usage the plugin never actually wrote.
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false

    // Marketplace requires a Plugin Verifier run on every upload; keep it runnable locally
    // with ./gradlew verifyPlugin.
    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    wrapper {
        gradleVersion = "9.7.1"
    }
}
