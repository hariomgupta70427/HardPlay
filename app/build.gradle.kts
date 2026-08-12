import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ---------------------------------------------------------------------------
// local.properties — never committed. See local.properties.example.
// Read through `providers.fileContents` rather than a bare File.readText() so
// Gradle tracks it as a build input; otherwise the configuration cache happily
// serves yesterday's api_id after you edit the file.
// ---------------------------------------------------------------------------
val localProperties: Properties =
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
        .asText
        .map { text -> Properties().apply { load(text.reader()) } }
        .orElse(providers.provider { Properties() })
        .get()

fun localProp(key: String): String? = localProperties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }

val telegramApiId: String = localProp("hardplay.telegram.apiId") ?: "0"
val telegramApiHash: String = localProp("hardplay.telegram.apiHash") ?: ""
val hasTelegramCredentials = telegramApiId != "0" && telegramApiHash.isNotEmpty()

// ---------------------------------------------------------------------------
// TDLib presence.
//
// libtdjni.so + the generated org.drinkless.tdlib bindings are produced by
// tools/build-tdlib.sh and are deliberately NOT in source control. So the app
// has to compile in both states. Everything talks to the `TelegramGateway`
// interface; the concrete TDLib implementation lives in a source set that is
// only wired in once the bindings actually exist, and `src/no-tdlib` supplies a
// same-shaped no-op factory otherwise. Nothing else in the codebase branches on
// this — see di/TelegramModule.kt.
// ---------------------------------------------------------------------------
val tdlibBindings = layout.projectDirectory.file("src/main/java/org/drinkless/tdlib/TdApi.java").asFile
val hasTdlib = tdlibBindings.exists()

android {
    namespace = "com.hardplay"
    compileSdk = 35

    defaultConfig {
        // Deliberately unrelated to the app's real name — this string is visible
        // in Settings > Apps and in any backup manifest. The launcher label and
        // icon are swapped separately via the activity-alias pair in the
        // manifest (see SettingsScreen -> discreet launcher toggle).
        applicationId = "com.northline.archive"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId)
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")
        buildConfigField("boolean", "HAS_TELEGRAM_CREDENTIALS", "$hasTelegramCredentials")
        buildConfigField("boolean", "HAS_TDLIB", "$hasTdlib")
    }

    signingConfigs {
        val storePath = localProp("hardplay.keystore.path")
        if (storePath != null) {
            create("release") {
                storeFile = file(storePath)
                storePassword = localProp("hardplay.keystore.storePassword")
                keyAlias = localProp("hardplay.keystore.keyAlias")
                keyPassword = localProp("hardplay.keystore.keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloaded app: fall back to debug signing so `assembleRelease`
            // works out of the box before a keystore exists.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.animation.ExperimentalSharedTransitionApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.srcDir(if (hasTdlib) "src/tdlib/kotlin" else "src/no-tdlib/kotlin")
        }
    }

    packaging {
        // Required for 16 KB page alignment on Android 15+, and lets the
        // platform mmap libtdjni.so straight out of the APK.
        jniLibs.useLegacyPackaging = false
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCY",
            "DebugProbesKt.bin",
        )
    }

    lint {
        // TdApi.java is generated and enormous; linting it is pure noise.
        ignore += "TypographyDashes"
        disable += setOf("MissingTranslation", "ExtraTranslation")
        abortOnError = false
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "false")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.splashscreen)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource)
    implementation(libs.media3.common)
    implementation(libs.media3.session)

    implementation(libs.coil.compose)

    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.biometric)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.collections.immutable)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Surface the TDLib state once per build instead of letting people discover it
// via a confusing runtime "Telegram unavailable" message.
gradle.projectsEvaluated {
    val tdlibState = if (hasTdlib) "present" else "MISSING (run tools/build-tdlib.sh) — demo mode only"
    val credState = if (hasTelegramCredentials) "present" else "MISSING (see local.properties.example)"
    logger.lifecycle("HardPlay: TDLib bindings $tdlibState; Telegram credentials $credState")
}
