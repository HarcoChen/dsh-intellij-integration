import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

plugins {
    id("java")
    // The settings plugin supplies the IntelliJ Platform Gradle Plugin version.
    id("org.jetbrains.intellij.platform")
}

group = "top.harcochen"
// gradle.properties is the single source of truth for the release version:
// patchPluginXml writes it into plugin.xml, and the release workflow refuses to
// publish when it disagrees with the tag. The fallback is a sentinel that can
// never be mistaken for a release.
version = providers.gradleProperty("pluginVersion").orElse("0.0.0-dev").get()

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
        changeNotes = """
            <p>Brings the IntelliJ Platform port to parity with the VS Code integration.</p>
            <ul>
              <li>Projects the runtime prompt queue, background jobs, slash commands, skills,
                  token usage, session statistics, and TODOs; registered slash commands now run
                  through the command registry instead of reaching the model.</li>
              <li>Adds the Goal panel and the Subagent tree, with preview, follow-up, and interrupt.</li>
              <li>Rebuilds tool-call diffs from the recorded hunks and adds Git-backed turn change
                  reviews with a guarded restore.</li>
              <li>Applying a code block now picks its target, previews the change as a diff, and
                  re-validates the file before writing.</li>
              <li>Adds diagnostics and folder context attachments, and AppShot capture on macOS.</li>
              <li>Replaces the raw settings, workspace, provider, and agent-preset dialogs with
                  native surfaces.</li>
            </ul>
        """.trimIndent()
    }
    buildSearchableOptions = false

    // The signing inputs come from the environment, and signPlugin skips itself
    // when they are absent — so the same command signs on CI and stays usable
    // locally without a certificate. The block has to be declared for the task
    // to consider itself configured at all.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // A prerelease must not land on the channel every stable user upgrades
        // into. Its SemVer identifier names the channel instead: 0.3.0-beta.1
        // publishes to "beta", which users opt into by adding that repository.
        channels = providers.provider {
            val suffix = version.toString().substringAfter('-', "")
            listOf(if (suffix.isEmpty()) "default" else suffix.substringBefore('.'))
        }
    }

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
