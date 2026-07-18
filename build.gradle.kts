import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

group = "com.github.iamr8"
// Single source of truth for the plugin version (also consumed by CI / releases).
version = file("VERSION").readText().trim()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Locally: build against the installed Rider (no big download, exact API match).
        // Elsewhere (CI, no local install): download the matching Rider SDK.
        if (file("/Applications/Rider.app").exists()) {
            local("/Applications/Rider.app")
        } else {
            rider("2026.1.4")
        }
        testFramework(TestFrameworkType.Platform)
    }

    // Bundled with the plugin; used by the pure JSON parser.
    implementation("com.google.code.gson:gson:2.14.0")

    // Bundled; used only by the error reporter to send opt-in crash reports.
    implementation("io.sentry:sentry:7.22.6")

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    // No custom settings/searchable options in this plugin.
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            // 243 = Rider 2024.3, the oldest release on JBR 21 (our Java-21 bytecode needs it).
            sinceBuild = "243"
            // No upper bound: stay compatible with future IDE builds (Marketplace-friendly).
            untilBuild = provider { null }
        }
    }

    // `publishPlugin` uses this token (JetBrains Marketplace); provided via env in CI.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    // `verifyPlugin` (IntelliJ Plugin Verifier) checks binary compatibility across the range —
    // the floor (2024.3), a mid build, and the latest — so older-Rider support stays honest.
    pluginVerification {
        ides {
            // useInstaller = false: Rider is verified from the non-installer distribution.
            create(IntelliJPlatformType.Rider, "2024.3.6") { useInstaller = false }
            create(IntelliJPlatformType.Rider, "2025.2.4") { useInstaller = false }
            create(IntelliJPlatformType.Rider, "2026.1.4") { useInstaller = false }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // Emit real Java default methods instead of Kotlin delegating overrides, so implementing
        // platform interfaces (e.g. ToolWindowFactory) doesn't generate usages of their
        // deprecated/experimental default methods (Plugin Verifier warnings).
        freeCompilerArgs.add("-jvm-default=all")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.test {
    useJUnit()
}

// Bake the Sentry DSN into sentry.properties at build time from the SENTRY_DSN env var
// (a GitHub Actions secret in CI). Nothing sentry-related is committed; a blank value
// disables reporting. The DSN is a write-only client key, not a build credential.
tasks.processResources {
    val sentryDsn = providers.environmentVariable("SENTRY_DSN").orElse("")
    inputs.property("sentryDsn", sentryDsn)
    filesMatching("sentry.properties") {
        expand("sentryDsn" to sentryDsn.get())
    }
}
