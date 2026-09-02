import com.diffplug.gradle.spotless.SpotlessExtension
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension

plugins {
    // AGP 9 compiles Kotlin itself, so there is no org.jetbrains.kotlin.android here.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
}

// Formatting and static analysis are configured once at the root and applied to every
// module, so a new module can never quietly opt out of them.
allprojects {
    apply(plugin = rootProject.libs.plugins.spotless.get().pluginId)
    apply(plugin = rootProject.libs.plugins.detekt.get().pluginId)

    extensions.configure<SpotlessExtension> {
        // Targets are fileTrees rooted at src/ rather than "src/**" globs: a glob is
        // resolved against the whole project directory, so Gradle walks build/ too and
        // trips over the empty ABI folders AGP leaves in merged_native_libs.
        kotlin {
            target(fileTree("src") { include("**/*.kt") })
            ktlint(rootProject.libs.versions.ktlint.get())
            trimTrailingWhitespace()
            endWithNewline()
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(rootProject.libs.versions.ktlint.get())
        }
        format("xml") {
            target(fileTree("src") { include("**/*.xml") })
            trimTrailingWhitespace()
            endWithNewline()
        }
        format("misc") {
            target("*.md", ".gitignore", "*.yml", "*.yaml")
            trimTrailingWhitespace()
            endWithNewline()
        }
    }

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        baseline = rootProject.file("config/detekt/baseline.xml").takeIf { it.exists() }
        parallel = true
        // The base detekt plugin only knows the JVM convention (src/main/kotlin), so on an
        // Android module it silently analyses ZERO files. Point it at the whole src tree
        // instead — that covers main/test/androidTest and any source set added later.
        source.setFrom(layout.projectDirectory.dir("src"))
    }

    dependencies {
        add("detektPlugins", rootProject.libs.detekt.formatting)
    }

    tasks.withType<Detekt>().configureEach {
        jvmTarget = JavaVersion.VERSION_17.toString()
        reports {
            html.required.set(true)
            xml.required.set(true)
            sarif.required.set(true)
            txt.required.set(false)
            md.required.set(false)
        }
    }
}

// One command for the whole gate, so CI and a local pre-push run the same thing.
tasks.register("qualityCheck") {
    group = "verification"
    description = "Runs formatting and static-analysis checks across every module."
    dependsOn("spotlessCheck", "detekt")
}
