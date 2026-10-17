import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Default relay: the GriipBuddy internal-beta deployment. For local development against a server on
// this machine, add griip.serverUrl=http://10.0.2.2:8080 to android/local.properties (emulator host).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val defaultServer: String = localProps.getProperty("griip.serverUrl") ?: "https://msg.griipbuddy.cloud"

android {
    // The app has its own language setting (docs/LANGUAGES.md): every language must be in every install, so the Play
    // bundle isn't split by language.
    bundle {
        language { enableSplit = false }
    }
    namespace = "com.griipbuddy.orbit"
    compileSdk = 36

    defaultConfig {
        // Orbit (2026-10-16 rebrand). A new application id installs as a separate app beside Griip Message
        // (com.griipbuddy.message); nothing migrates between them. The code's namespace is com.griipbuddy.orbit.
        // Capital O on purpose: it must equal the Play Console app's package name exactly (package names are
        // case-sensitive and permanent on Play). Builds before 2026-10-17 used com.griipbuddy.orbit.
        applicationId = "com.griipbuddy.Orbit"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.1.3-beta"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$defaultServer\"")
        manifestPlaceholders["appLabel"] = "@string/app_name"
        vectorDrawables.useSupportLibrary = true
        // Ship only ABIs modern phones/emulators use (drops legacy mips/armeabi/x86 from dependencies).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // -Porbit.sideBySide=true: a debug build that installs beside the Play app (com.griipbuddy.Orbit.test,
            // "Orbit Test") instead of clashing with its signature. Off by default, so testing APKs are unchanged.
            if (project.findProperty("orbit.sideBySide") == "true") {
                applicationIdSuffix = ".test"
                manifestPlaceholders["appLabel"] = "Orbit Test"
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
        jniLibs { useLegacyPackaging = false }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.bouncycastle)
    implementation(libs.livekit.android)
    implementation(libs.zxing.core)
    // In-app invite QR scanner (T4.2). Apache-2.0, like zxing.
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    // Standard/HD video quality for attachments (2026-10-17): re-encodes picked videos on the phone before encryption.
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.common)
    // Links open in a Custom Tab: the person's own browser, with its safe-browsing and TLS checks (no embedded WebView).
    implementation(libs.androidx.browser)
    // Automatic encrypted backups (docs/BACKUP.md).
    implementation(libs.androidx.work)
    // Message translation on the phone (docs/LANGUAGES.md › Translation): message text never leaves the device; only the
    // language models are downloaded, after the person agrees.
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sqlite.jdbc) // runs Room migration SQL on the JVM (MigrationsTest)
    testImplementation(libs.okhttp.mockwebserver) // resumable-download tests
}
