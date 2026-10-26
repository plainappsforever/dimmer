import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing (launch step 2). The owner's UPLOAD key lives OUTSIDE every repo; this file only names the path.
// The properties file holds storeFile, storePassword, keyAlias, keyPassword. Nothing from it is printed or copied.
// Override the path with -Pdimmer.uploadProperties=/path/to/file. Missing file -> release falls back to the debug key
// with a warning (such a build is for emulator tests only; Play refuses debug-signed bundles).
val uploadPropertiesFile = file(
    (findProperty("dimmer.uploadProperties") as String?) ?: (System.getProperty("user.home") + "/Developer/.keys/dimmer-upload.properties")
)
val uploadProperties: Properties? = uploadPropertiesFile.takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}?.takeIf { p ->
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !p.getProperty(it).isNullOrBlank() } &&
        file(p.getProperty("storeFile")).isFile
}
if (uploadProperties == null) {
    logger.warn(
        "WARNING: upload key properties not found or incomplete at ${uploadPropertiesFile.path} - " +
            "the RELEASE build is signed with the DEBUG key (emulator testing only, not uploadable to Google Play)."
    )
}

android {
    namespace = "com.plainapps.dimmer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.plainapps.dimmer"
        minSdk = 26
        targetSdk = 36
        versionCode = 30102 // scheme MMmmpp: 3.1.2 -> 30102 (3.1.2 = Plain Apps privacy link, 2026-10-16); every Play upload must be higher (RELEASE_KIT section 2)
        versionName = "3.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (uploadProperties != null) {
            create("upload") {
                storeFile = file(uploadProperties.getProperty("storeFile"))
                storePassword = uploadProperties.getProperty("storePassword")
                keyAlias = uploadProperties.getProperty("keyAlias")
                keyPassword = uploadProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
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

    buildFeatures {
        compose = true
    }

    // F-Droid reproducible builds: no Play dependency metadata (a signed blob) in the APK/AAB (RELEASE_KIT section 2).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // v3.1: DimmerCommandsTest runs the real commands on the JVM; android.util.Log (and the
    // TileService/ICU calls they never reach) return defaults instead of throwing "not mocked".
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
