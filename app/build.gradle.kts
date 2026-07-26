plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "space.linuxct.glyphmatrixtoycompat"
    compileSdk = 35

    defaultConfig {
        applicationId = "space.linuxct.glyphmatrixtoycompat"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // R8 removes unused library code (Compose unshrunk is ~15 MB of
            // dex) but does NOT strip logging: log calls only disappear under
            // an -assumenosideeffects rule, which proguard-rules.pro forbids,
            // and DebugLog + the Nothing SDK + component names are kept there.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
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
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    systemProperty("updateGoldens", System.getProperty("updateGoldens") ?: "false")
}
