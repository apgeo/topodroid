// AGP 8 build for the TopoDroid source tree (upstream Ant layout), read in place from the
// clone. The clone is not modified by this project: the debug network-security override and
// the JVM unit tests live here, beside the build script.
plugins {
    id("com.android.application") version "8.13.1"
}

// Override with -PtopodroidDir=/path/to/clone (or a worktree of it).
val td = file((findProperty("topodroidDir") as String?) ?: "/home/devuser1/projects/topodroid")

// -PsideBySide=true builds a copy that installs next to the Play Store TopoDroid on one phone:
// its own application id, its own file-provider authority (two installed apps may not share one)
// and its own data folder Documents/TDX-SilexGIS, so it never opens the real Documents/TDX surveys.
// The clone is not modified: the two files that hard-code those names are copied, patched, and
// compiled in place of the originals. Each patch must match exactly once or the build stops.
val sideBySide = (findProperty("sideBySide") as String?) == "true"
val sbsDir = layout.buildDirectory.dir("sidebyside").get().asFile
fun patchOnce(text: String, old: String, new: String, what: String): String {
    val n = text.split(old).size - 1
    check(n == 1) { "side-by-side patch '$what' matched $n times, expected 1" }
    return text.replace(old, new)
}
if (sideBySide) {
    val src = File(sbsDir, "src")
    src.deleteRecursively()
    File(td, "src").copyRecursively(src)
    val tdFile = File(src, "com/topodroid/util/TDFile.java")
    var t = tdFile.readText()
    t = patchOnce(t, "getExternalStoragePublicDirectory( documents ), \"TDX\" );", "getExternalStoragePublicDirectory( documents ), \"TDX-SilexGIS\" );", "folder")
    t = patchOnce(t, "getExternalStoragePublicDirectory( documents ), \"TDX/\" + type );", "getExternalStoragePublicDirectory( documents ), \"TDX-SilexGIS/\" + type );", "folder/type")
    tdFile.writeText(t)
    var m = File(td, "AndroidManifest.xml").readText()
    m = patchOnce(m, "android:authorities=\"com.topodroid.TDX.fileprovider\"", "android:authorities=\"com.topodroid.TDX.silexgis.fileprovider\"", "authority")
    m = patchOnce(m, "android:label=\"@string/app_name\"", "android:label=\"TopoDroid SilexGIS\"", "label")
    File(sbsDir, "AndroidManifest.xml").writeText(m)
    val paths = File(sbsDir, "res/xml/file_paths.xml")
    paths.parentFile.mkdirs()
    paths.writeText(patchOnce(File(td, "res/xml/file_paths.xml").readText(), "path=\"Documents/TDX/\"", "path=\"Documents/TDX-SilexGIS/\"", "file paths"))
}

android {
    namespace = "com.topodroid.TDX"
    compileSdk = 37

    defaultConfig {
        applicationId = if (sideBySide) "com.topodroid.TDX.silexgis" else "com.topodroid.TDX"
        minSdk = 21
        targetSdk = 37
        // versionCode / versionName come from the clone's manifest
        multiDexEnabled = true
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile(if (sideBySide) File(sbsDir, "AndroidManifest.xml") else File(td, "AndroidManifest.xml"))
            java.setSrcDirs(listOf(if (sideBySide) File(sbsDir, "src") else File(td, "src")))
            res.setSrcDirs(listOf(File(td, "res")))
            assets.setSrcDirs(listOf(File(td, "assets")))
        }
        // src/debug/res overrides res/xml/network_security_config.xml for debug builds only
        // (cleartext to the emulator's host alias 10.0.2.2 and to loopback).
        // The side-by-side copy shares its zips from Documents/TDX-SilexGIS, so the file provider's
        // path list (res/xml/file_paths.xml) is replaced for debug builds of that copy.
        if (sideBySide) getByName("debug") { res.srcDir(File(sbsDir, "res")) }
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
    // android.jar carries org.json only as stubs; the JVM tests of SilexGisClient need the real one.
    // Test classpath only, nothing is packaged.
    testImplementation("org.json:json:20240303")
}

// The JVM unit tests compile against the JDK as well as android.jar: AGP puts android.jar on the
// boot class path of every Java compile, which hides JDK-only classes such as
// com.sun.net.httpserver.HttpServer (the local server the SilexGisClient tests talk to).
// android.jar stays on the class path, so app classes that mention android types still resolve.
afterEvaluate {
    tasks.withType<JavaCompile>().configureEach {
        if (name.contains("UnitTest")) {
            val boot = options.bootstrapClasspath
            if (boot != null) classpath = classpath.plus(boot)
            options.bootstrapClasspath = null
        }
    }
}
