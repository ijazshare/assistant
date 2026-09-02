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
            // Loading .so straight from the APK avoids a copy into /data on install and
            // is required for 16 KB page-size compatibility on recent Android.
            useLegacyPackaging = false
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

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
