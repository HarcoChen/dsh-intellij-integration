import org.gradle.api.plugins.quality.Checkstyle
import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginSignatureTask

plugins {
    id("java")
    id("checkstyle")
    id("com.diffplug.spotless") version "8.10.1"
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

spotless {
    encoding("UTF-8")

    java {
        target("src/*/java/**/*.java")
        googleJavaFormat("1.36.0").aosp()
        removeUnusedImports()
        forbidWildcardImports()
        trimTrailingWhitespace()
        endWithNewline()
    }

    format("buildFiles") {
        target("*.gradle.kts", "*.properties", ".editorconfig", "config/**/*.xml")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

checkstyle {
    toolVersion = "14.1.0"
    configDirectory = layout.projectDirectory.dir("config/checkstyle")
    isIgnoreFailures = false
    maxErrors = 0
    maxWarnings = 0
}

tasks.withType<Checkstyle>().configureEach {
    reports {
        xml.required = true
        html.required = true
        sarif.required = true
    }
}

tasks.register("lint") {
    group = "verification"
    description = "Checks Java formatting and static-analysis rules."
    dependsOn("spotlessCheck", "checkstyleMain")
}

tasks.register("format") {
    group = "formatting"
    description = "Formats Java and repository build files."
    dependsOn("spotlessApply")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"
        }
        changeNotes = providers.environmentVariable("PLUGIN_CHANGE_NOTES")
            .orElse("<p>See the <a href=\"https://github.com/HarcoChen/dsh-intellij-integration/releases\">GitHub Releases</a> page for the full changelog.</p>")
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

// Plugin 2.6.0 passes an in-memory certificate to the verifier as if it were a
// path. Materialize the public certificate in build/tmp and declare the missing
// sign -> verify task edge so Gradle 9 can validate the release invocation.
val verificationCertificate = layout.buildDirectory.file("tmp/pluginSigning/chain.crt")
val prepareVerificationCertificate = tasks.register("preparePluginVerificationCertificate") {
    outputs.file(verificationCertificate)
    outputs.upToDateWhen { false }
    doLast {
        val output = verificationCertificate.get().asFile
        output.parentFile.mkdirs()
        output.writeText(providers.environmentVariable("CERTIFICATE_CHAIN").get())
    }
}

tasks.named<VerifyPluginSignatureTask>("verifyPluginSignature") {
    dependsOn("signPlugin", prepareVerificationCertificate)
    certificateChain.unsetConvention()
    certificateChainFile.set(verificationCertificate)
}
