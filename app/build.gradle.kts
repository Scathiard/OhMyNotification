import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.prince_dulb.ohmynotification"
    testNamespace = "io.github.prince_dulb.ohmynotification.test"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "io.github.prince_dulb.ohmynotification"
        minSdk = 36
        targetSdk = 36
        // A third UID for the emulator-only carrier experiment; never changes the main APK ID.
        testApplicationId = if (providers.gradleProperty("omnCarrierSourceFixture").orNull == "true") {
            "io.github.prince_dulb.ohmynotification.sourcefixture"
        } else {
            "io.github.prince_dulb.ohmynotification.test"
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 22
        versionName = "1.1.0"
        // Basic logs are always available; extra events/counters require this explicit switch.
        resValue("bool", "omn_listener_diagnostics", (providers.gradleProperty("omnListenerDiagnostics").orNull == "true").toString())
    }

    buildTypes {
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

    buildFeatures {
        resValues = true
        buildConfig = false
        compose = true
    }

    sourceSets {
        getByName("test").resources.directories += rootProject.file("testdata").absolutePath
        getByName("androidTest").assets.directories += rootProject.file("testdata").absolutePath
        getByName("androidTest").assets.directories += file("schemas").absolutePath
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Explicit, local emulator fixtures only. Release outputs never use these versions.
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        providers.gradleProperty("omnUpdateFixture").orNull?.let { fixture ->
            require(fixture in setOf("baseline", "candidate", "signature-baseline")) { "Unknown update fixture" }
            variant.outputs.forEach { output ->
                output.versionCode.set(when (fixture) { "baseline" -> 19; "candidate" -> 20; else -> 16 })
                output.versionName.set(when (fixture) { "baseline" -> "1.1.0"; "candidate" -> "1.1.1"; else -> "1.0.99" })
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.room.runtime)

    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
