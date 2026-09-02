plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

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

    buildTypes {
        debug {
            // Install alongside signed/release builds during live device testing.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
