// AGP 8 build for the TopoDroid source tree (upstream Ant layout), read in place from the
// clone. The clone is not modified by this project: the debug network-security override and
// the JVM unit tests live here, beside the build script.
plugins {
    id("com.android.application") version "8.13.1"
}

// Override with -PtopodroidDir=/path/to/clone (or a worktree of it).
val td = file((findProperty("topodroidDir") as String?) ?: "/home/devuser1/projects/topodroid")

android {
    namespace = "com.topodroid.TDX"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.topodroid.TDX"
        minSdk = 21
        targetSdk = 37
        // versionCode / versionName come from the clone's manifest
        multiDexEnabled = true
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile(File(td, "AndroidManifest.xml"))
            java.setSrcDirs(listOf(File(td, "src")))
            res.setSrcDirs(listOf(File(td, "res")))
            assets.setSrcDirs(listOf(File(td, "assets")))
        }
        // src/debug/res overrides res/xml/network_security_config.xml for debug builds only
        // (cleartext to the emulator's host alias 10.0.2.2 and to loopback).
        getByName("test") {
            java.setSrcDirs(listOf("src/test/java"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(File(td, "proguard.cfg"))
        }
    }

    testOptions {
        // android.* calls made by app classes under test return defaults instead of throwing
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // MyActivity and MyDialog import androidx.annotation.RequiresApi; the platform
    // android.jar does not carry it. Compile-time only, nothing is packaged.
    compileOnly("androidx.annotation:annotation:1.9.1")

    testImplementation("junit:junit:4.13.2")
}
