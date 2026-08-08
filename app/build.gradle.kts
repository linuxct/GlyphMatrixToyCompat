import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing is driven by a repo-root keystore.properties (written by the Release workflow
// from repo secrets, or created locally for a signed build). Absent it, release builds are
// unsigned and debug builds are unaffected.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}

android {
    namespace = "space.linuxct.glyphworks"
    compileSdk = 37

    defaultConfig {
        applicationId = "space.linuxct.glyphworks"
        minSdk = 33
        targetSdk = 37
        versionCode = 14
        versionName = "3.0.0"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                // storeFile is resolved against the repo root, where the workflow writes keystore.jks
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // ---------- distribution flavours ----------
    //
    // The Play build must not merely disable the design assistant and the update
    // checker — their CODE must not be in the APK. That is what makes three Play
    // filings unnecessary rather than merely favourable: no foreground-service
    // justification, no data-collection entry on the Data Safety form, and no
    // reviewer credentials for a sign-in. A runtime flag would leave the classes,
    // the strings and the INTERNET permission in the binary, and a reviewer reads
    // the binary.
    //
    // So `ai/`, `core/ai/`, `update/` and the three AI dialogs live in
    // `src/github/`, never in `src/main/`, and the two flavours agree only on a
    // seam — see `ui/OptionalFeatures.kt`, which exists twice with identical
    // signatures. Nothing checks that the two agree except building both, which
    // is why CI does.
    //
    // AGP names variants <flavour><BuildType>: githubDebug, githubRelease,
    // playDebug, playRelease. `github` is the default for local work.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            isDefault = true
        }
        create("play") {
            dimension = "distribution"
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // AGP 8.9's bundled lint crashes inside this Compose UI detector
        // (NoClassDefFoundError in ReturnFromAwaitPointerEventScopeDetector) —
        // a tooling version mismatch, not a code issue. Disable just that check.
        disable += "ReturnFromAwaitPointerEventScope"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(files("libs/glyph-matrix-sdk-2.0.aar"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    // 1.5.0-alpha23 is the first release exposing MotionScheme / MaterialTheme's
    // motionScheme parameter as public API (both were internal in 1.4.0), which
    // is what lets the app use MD3's real expressive springs instead of copying
    // token values. It pulls Compose 1.12.0-alpha03 transitively.
    implementation("androidx.compose.material3:material3:1.5.0-alpha23")
    // AnimatedContent lives in the non-core animation artifact, which material3
    // does NOT depend on (verified in the POMs of both 1.4.0 and 1.5.0-alpha23).
    implementation("androidx.compose.animation:animation:1.12.0-alpha03")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // WorkManager exists for ONE thing: the update checker's daily job. The Play
    // build has no update checker, so it does not get the library either —
    // `implementation` would leave it in that APK doing nothing, and `App`'s
    // `Configuration.Provider` (plus the manifest's WorkManagerInitializer
    // removal, which exists for Direct Boot) is github-only for the same reason.
    "githubImplementation"("androidx.work:work-runtime-ktx:2.10.0")
    // The only non-AndroidX/Compose runtime dependency in the project, and it earns
    // its place: the design format is a published interchange format, so hand-rolled
    // org.json parsing (as update/UpdateChecker does for a three-field API response)
    // would mean hand-rolling validation for every field of an attacker-controlled
    // file. 1.9.x is the line built against Kotlin 2.2; the serialization compiler
    // plugin above must stay pinned to the Kotlin version, this runtime need not.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    systemProperty("updateGoldens", System.getProperty("updateGoldens") ?: "false")
}
