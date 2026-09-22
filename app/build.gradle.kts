plugins {
    // AGP 9 has built-in Kotlin support, so the standalone
    // org.jetbrains.kotlin.android plugin is gone — AGP refuses to apply it.
    // The Compose compiler plugin is still applied separately.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "org.soulstone.overwatch"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.soulstone.overwatch"
        minSdk = 26
        targetSdk = 35
        versionCode = 31
        versionName = "0.5.15"
    }

    // Fixed debug keystore committed to the repo (a debug key is non-secret — its
    // password is the well-known "android") so CI and local builds sign
    // identically. Without it each CI build minted a fresh debug key and updates
    // wouldn't install over the previous one.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Generates WazeProto from src/main/proto/waze.proto. `lite` keeps the runtime and
// the generated code small enough for an app that only speaks one protocol.
protobuf {
    protoc { artifact = libs.protobuf.protoc.get().toString() }
    generateProtoTasks {
        all().forEach { task ->
            // Android projects get no default `java` builtin from the plugin, so
            // create it (maybeCreate keeps this correct either way) and ask for the
            // lite generator to match the protobuf-javalite runtime.
            task.builtins {
                maybeCreate("java").option("lite")
            }
        }
    }
}

dependencies {
    // Play services drags in androidx.fragment 1.1.0, whose FragmentActivity
    // predates the ActivityResult APIs: it failed to call
    // super.onRequestPermissionsResult() and used invalid request codes. Lint
    // fails the release build over it (InvalidFragmentVersionForActivityResult)
    // because MainActivity registers the permission prompt that way. Nothing in
    // this app uses Fragments, so this is a constraint rather than a dependency —
    // it raises a version already on the classpath instead of adding an edge.
    constraints {
        implementation(libs.androidx.fragment) {
            because("play-services pulls fragment 1.1.0, which is unsafe with registerForActivityResult")
        }
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.play.services.location)
    implementation(libs.osmdroid.android)
    implementation(libs.protobuf.javalite)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
