import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing for the Google Play (Play App Signing) upload key. Values are
// read from keystore.properties at the repo root, falling back to ANDROID_*
// environment variables for a CI/release runner. The keystore and its passwords
// are never committed (see .gitignore). When no signing config is present (e.g.
// CI, which only builds debug), release builds stay unsigned so the build still
// runs; `bundleRelease` for the store must be run where the keystore exists.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun releaseSigningValue(propKey: String, envKey: String): String? =
    (keystoreProperties.getProperty(propKey) ?: System.getenv(envKey))?.takeIf { it.isNotBlank() }

val releaseStoreFile = releaseSigningValue("storeFile", "ANDROID_KEYSTORE_FILE")
    ?.let { rootProject.file(it) }
val releaseStorePassword = releaseSigningValue("storePassword", "ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("keyAlias", "ANDROID_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("keyPassword", "ANDROID_KEY_PASSWORD")
val hasReleaseSigning = releaseStoreFile?.exists() == true &&
    releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

android {
    namespace = "ai.sealgate.stdiod"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.sealgate.stdiod"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Install alongside signed/release builds during live device testing.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "COMPUTER_USE_AVAILABLE", "true")
        }
        release {
            // Google Play build: the accessibility service is not merged into
            // the manifest and the capability is never registered.
            buildConfigField("boolean", "COMPUTER_USE_AVAILABLE", "false")
            // Signed with the upload key when the keystore is available (release
            // runner / local). Absent in CI, where only debug is built.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("private") {
            initWith(getByName("release"))
            applicationIdSuffix = ".private"
            versionNameSuffix = "-private"
            matchingFallbacks += "release"
            buildConfigField("boolean", "COMPUTER_USE_AVAILABLE", "true")
        }
        create("enterprise") {
            initWith(getByName("release"))
            applicationIdSuffix = ".enterprise"
            versionNameSuffix = "-enterprise"
            matchingFallbacks += "release"
            buildConfigField("boolean", "COMPUTER_USE_AVAILABLE", "true")
        }
    }

    sourceSets {
        listOf("debug", "private", "enterprise").forEach { buildType ->
            getByName(buildType).apply {
                manifest.srcFile("src/computerUse/AndroidManifest.xml")
                res.directories.add("src/computerUse/res")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        // TunnelService reports BuildConfig.VERSION_NAME as client_version.
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.quickjs.kt)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// quickjs-kt's Android artifact contains native libraries. JVM unit tests use
// the matching desktop artifact so the real runtime can be exercised locally.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:${libs.versions.quickjsKt.get()}"))
    }
}
