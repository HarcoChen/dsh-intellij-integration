import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

plugins {
    id("java")
    // The settings plugin supplies the IntelliJ Platform Gradle Plugin version.
    id("org.jetbrains.intellij.platform")
}

group = "top.harcochen"
version = providers.gradleProperty("pluginVersion").orElse("0.1.0").get()

dependencies {
    intellijPlatform {
        // Build against the common IntelliJ IDEA Community platform. The
        // implementation only uses IntelliJ Platform APIs, so the resulting
        // plugin can load in IntelliJ Platform-based JetBrains IDE products.
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").orElse("2024.3.6").get())
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"
        }
        changeNotes = "<p>Initial IntelliJ Platform port of the DeepSeek Harness IDE integration.</p>"
    }
    buildSearchableOptions = false
    pluginVerification {
        ides {
            val target = providers.gradleProperty("platformVersion").orElse("2024.3.6").get()
            // Verify the common platform baseline and the PyCharm product
            // that motivated this port. Both use only shared plugin APIs.
            ide("IC", target)
            ide("PC", target)
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
