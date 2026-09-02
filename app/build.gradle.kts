import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.util.Properties

plugins {
    // AGP 9 compiles Kotlin itself (android.builtInKotlin, on by default) and rejects
    // the standalone org.jetbrains.kotlin.android plugin. The Kotlin compiler version
    // is raised above AGP's bundled default by the compose/KSP plugin markers below,
    // which pull a newer kotlin-gradle-plugin onto the shared buildscript classpath.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release signing is driven entirely by environment variables in CI (see release.yml)
// and by an untracked keystore.properties locally. Absent either, the release build
// falls back to unsigned rather than failing configuration, so `assembleDebug` and
// `check` still work on a fresh clone.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

fun signingValue(propertyKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propertyKey) ?: System.getenv(envKey)

val releaseStoreFile = signingValue("storeFile", "SIGNING_KEYSTORE_PATH")
val releaseStorePassword = signingValue("storePassword", "SIGNING_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "io.github.hasanismail.themachine"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    // Pinned so a machine with several NDKs installed always builds the same binaries.
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "io.github.hasanismail.themachine"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // whisper.cpp and llama.cpp are built for 64-bit ARM only. Every device that
        // can plausibly run a 1B LLM at conversational latency is arm64-v8a.
        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                // -DANDROID_STL is set by the NDK toolchain file; c++_shared is needed
                // because whisper, llama, ggml and the bridge are separate .so files
                // that pass std:: types across their boundaries.
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
        jniLibs {
            // Extract native libraries at install time instead of loading them straight
            // from the APK. This is not the modern default and it costs ~15 MB of install
            // size, but ggml's backend loader enumerates a real directory with
            // std::filesystem::directory_iterator, scoring each libggml-cpu-*.so it finds.
            // With libraries left inside the APK there is nothing to enumerate and ggml
            // registers ZERO backends — verified on device, where it silently reported
            // "0 backends" rather than failing. llama.cpp's own Android example sets
            // extractNativeLibs="true" for exactly this reason.
            //
            // 16 KB page support is unaffected: that requirement is about ELF segment
            // alignment (checked in CI-adjacent tooling and verified locally), and zip
            // alignment only matters when mapping directly out of the APK.
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        // AGP 9 always generates the HTML/SARIF reports; the toggles were removed.
        disable += setOf(
            // The version catalog is bumped deliberately through Dependabot PRs; a lint
            // failure on "a newer version exists" would make every build a race.
            "GradleDependency",
            "AndroidGradlePluginVersion",
            "NewerVersionAvailable",
            // arm64-v8a only is a deliberate scope decision (see abiFilters above):
            // whisper.cpp and llama.cpp are built for 64-bit ARM, and ChromeOS is not
            // a target for a device-assistant app bound to the phone's side button.
            "ChromeOsAbiSupport",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // No -Xjvm-default / -opt-in flags: interface default methods and RequiresOptIn
        // are both the compiler default from Kotlin 2.2 onwards.
    }
}

/**
 * Runs the instrumented tests over adb and believes the test runner's own verdict.
 *
 * `connectedDebugAndroidTest` is unusable against the reference Galaxy device: AGP's
 * runner writes test-result-exit-code.txt = 1 and fails the task even when its own
 * HTML report says "100% successful, 0 failures". Reproduced with a single trivial
 * test that touches nothing native, so it is independent of test content — an AGP
 * and One UI interaction, not a defect in the code under test.
 *
 * Rather than leave a permanently red gate, this task shells out to `am instrument`
 * and fails only on what the runner actually reports: "FAILURES!!!", or the absence
 * of a final "OK (n tests)".
 */
abstract class AdbInstrumentationTest : DefaultTask() {

    @get:Inject
    abstract val execOps: ExecOperations

    @get:InputFile
    abstract val appApk: RegularFileProperty

    @get:InputFile
    abstract val testApk: RegularFileProperty

    @get:InputFile
    abstract val adbExecutable: RegularFileProperty

    @get:Input
    abstract val instrumentation: Property<String>

    /** ANDROID_SERIAL if set; otherwise adb picks the only attached device. */
    @get:Input
    @get:Optional
    abstract val deviceSerial: Property<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    private fun adb(vararg args: String): String {
        val out = ByteArrayOutputStream()
        val serial = deviceSerial.orNull
        val prefix = if (serial.isNullOrBlank()) emptyList() else listOf("-s", serial)
        execOps.exec {
            commandLine(listOf(adbExecutable.get().asFile.absolutePath) + prefix + args)
            standardOutput = out
            errorOutput = out
            isIgnoreExitValue = true
        }
        return out.toString(Charsets.UTF_8.name())
    }

    @TaskAction
    fun run() {
        // -r replaces, -t allows a test-only APK, -g grants runtime permissions so tests
        // never block on a dialog.
        logger.lifecycle(adb("install", "-r", "-t", "--user", "0", appApk.get().asFile.absolutePath).trim())
        logger.lifecycle(adb("install", "-r", "-t", "-g", "--user", "0", testApk.get().asFile.absolutePath).trim())

        val output = adb("shell", "am", "instrument", "-w", "-r", instrumentation.get())
        report.get().asFile.also { it.parentFile.mkdirs() }.writeText(output)

        val summary = output.lineSequence()
            .filter { it.startsWith("OK (") || it.contains("FAILURES!!!") || it.startsWith("Tests run:") }
            .joinToString(" | ")
        logger.lifecycle("instrumentation: ${summary.ifBlank { "no summary line" }}")

        if (output.contains("FAILURES!!!") || !output.contains(Regex("""OK \(\d+ tests?\)"""))) {
            throw GradleException(
                "Instrumented tests did not pass. Full runner output: ${report.get().asFile}",
            )
        }
    }
}

tasks.register<AdbInstrumentationTest>("deviceTest") {
    group = "verification"
    description = "Runs instrumented tests on an attached device via adb (see AdbInstrumentationTest)."
    dependsOn("assembleDebug", "assembleDebugAndroidTest")

    // AGP 9 removed android.sdkDirectory; SDK paths now come from androidComponents.
    adbExecutable.set(androidComponents.sdkComponents.adb)
    appApk.set(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    testApk.set(layout.buildDirectory.file("outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
    instrumentation.set("io.github.hasanismail.themachine.debug.test/androidx.test.runner.AndroidJUnitRunner")
    deviceSerial.set(providers.environmentVariable("ANDROID_SERIAL").orElse(""))
    report.set(layout.buildDirectory.file("reports/deviceTest/instrumentation.txt"))
}

dependencies {
    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))

    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // androidx.test.ext:junit supplies the AndroidJUnit4 runner that Robolectric needs;
    // androidx.test:core alone only provides ApplicationProvider.
    testImplementation(libs.androidx.test.junit)

    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
