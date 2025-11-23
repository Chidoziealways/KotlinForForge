import java.net.URL

gradle.settingsEvaluated {
    fun isOnline(): Boolean {
        return try {
            URL("https://maven.neoforged.net/releases").openConnection().apply {
                connectTimeout = 2000
                readTimeout = 2000
                connect()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    if (!isOnline()) {
        println("⚡ No internet detected. Enabling Gradle offline mode.")
        gradle.startParameter.isOffline = true
    } else {
        println("✅ Internet detected. Running in normal mode.")
    }
}

pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention").version("0.9.0")
}
// Really wanted to avoid subprojects, but oh well. Needed for proper JarJar metadata
include("combined", "combined:kfflang", "combined:kfflib", "combined:kffmod")

rootProject.name = "KotlinForForge"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

