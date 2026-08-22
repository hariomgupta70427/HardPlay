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

// ---------------------------------------------------------------------------
// Telegram credentials.
//
// Both values are validated here rather than trusted, because every way of
// getting them wrong fails somewhere far from the cause:
//
//  * api_id is an int32 in MTProto, and TdApi.SetTdlibParameters declares it as a
//    Java `int`. An over-large value used to surface as "error: integer number
//    too large" in generated BuildConfig.java, with nothing pointing at the file
//    that produced it.
//  * api_hash is exactly 32 hex characters. Anything else reaches
//    setTdlibParameters and comes back as an opaque Telegram auth error three
//    screens into the login flow.
//
// The bot-token check is here because that is the mistake that actually happened:
// a @BotFather token looks like `8856503031:AAHc…`, and splitting it at the colon
// puts a 10-digit id in apiId and a 35-character secret in apiHash. Both halves
// then look vaguely plausible, and a bot account cannot read channel history the
// way this app needs to — so it is worth naming rather than leaving as two
// separate format complaints.
//
// Unusable credentials fall back to demo mode rather than failing the build: a
// build that can't run is worse than one that runs in demo.
// ---------------------------------------------------------------------------
val rawApiId: String? = localProp("hardplay.telegram.apiId")
val parsedApiId: Int? = rawApiId?.toIntOrNull()
val telegramApiId: String = (parsedApiId ?: 0).toString()
val rawApiHash: String? = localProp("hardplay.telegram.apiHash")
val telegramApiHash: String = rawApiHash ?: ""

val apiHashLooksValid: Boolean = rawApiHash != null && Regex("^[0-9a-fA-F]{32}$").matches(rawApiHash)

/** A @BotFather token's secret half: ~35 chars, conventionally opening "AA". */
val looksLikeBotToken: Boolean =
    rawApiHash != null && !apiHashLooksValid && rawApiHash.startsWith("AA") && rawApiHash.length > 32

val credentialProblems: List<String> = buildList {
    if (looksLikeBotToken) {
        add(
            "local.properties holds a @BotFather BOT TOKEN, not app credentials — " +
                "apiHash is ${rawApiHash!!.length} chars starting \"AA\" and apiId is " +
                "${rawApiId?.length ?: 0} digits, which is a token split at its colon. " +
                "HardPlay signs in as a user (phone + OTP) to read channel history; a bot " +
                "account cannot do that. Get api_id and api_hash from " +
                "my.telegram.org -> API development tools.",
        )
    } else {
        when {
            rawApiId == null -> Unit
            parsedApiId == null && rawApiId.all(Char::isDigit) ->
                add(
                    "hardplay.telegram.apiId has ${rawApiId.length} digits and does not fit " +
                        "in a 32-bit int, so it cannot be a Telegram api_id (real ones are " +
                        "6-8 digits). Check my.telegram.org.",
                )
            parsedApiId == null -> add("hardplay.telegram.apiId is not a number.")
            parsedApiId <= 0 -> add("hardplay.telegram.apiId must be positive.")
        }
        if (rawApiHash != null && !apiHashLooksValid) {
            add(
                "hardplay.telegram.apiHash is ${rawApiHash.length} characters; a Telegram " +
                    "api_hash is exactly 32 hexadecimal characters.",
            )
        }
    }
}

val hasTelegramCredentials = parsedApiId != null && parsedApiId > 0 && apiHashLooksValid

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

        // Derived from versionName rather than counted, as major*10000 + minor*100 +
        // patch. Android only requires this to increase, so the value is free to carry
        // information — and a code that can be read back as a version is one fewer thing
        // to keep in step by hand. 1.2.0 -> 10200.
        //
        // Everything user-visible reads `BuildConfig.VERSION_NAME`: the Settings footer,
        // the Manage tab, and the `applicationVersion` TDLib reports to Telegram for the
        // session. There is no second copy of this string to update.
        versionCode = 10_200
        versionName = "1.2.0"

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
            // FlowRow — the wrapping chip rows in the filter sheet and tag editor.
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.animation.ExperimentalSharedTransitionApi",
            // FontVariation — needed to drive Archivo's wght/wdth axes.
            "-opt-in=androidx.compose.ui.text.ExperimentalTextApi",
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
        // Room's exported schemas, as instrumentation assets. `MigrationTestHelper`
        // reads them from the *test* APK's assets, so without this the migration test
        // fails with "Cannot find the schema file" rather than with anything about the
        // migration — and the one code path a clean install never exercises stays
        // untested.
        getByName("androidTest") {
            assets.srcDir("$projectDir/schemas")
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

    testOptions {
        unitTests {
            // Unit tests run on the JVM with a stub android.jar whose methods throw
            // by default. `MediaFileRepair` logs on its failure path, and a test of
            // that path is exactly the one worth having, so an unmocked `Log.w` must
            // not be what fails it.
            //
            // Anything that genuinely depends on framework behaviour — `android.net.Uri`
            // escaping, for one — belongs in `androidTest` instead, because here it
            // would silently return a default and pass without testing anything.
            isReturnDefaultValues = true
        }
    }

    // -----------------------------------------------------------------------
    // ABI splits.
    //
    // libtdjni.so is 17–27 MB per ABI, so a universal APK is ~92 MB of which two
    // thirds is native code for architectures the target device cannot run. This
    // app is distributed by copying an APK to a phone, which makes that a real
    // cost rather than a theoretical one — the per-ABI output is around 45 MB.
    //
    // The universal APK is kept as well: it is the one to reach for when you don't
    // know what you're installing onto, and it is what an emulator wants.
    // -----------------------------------------------------------------------
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    lint {
        // TdApi.java is generated and enormous; linting it is pure noise.
        disable += setOf("TypographyDashes", "MissingTranslation", "ExtraTranslation")
        // Media3 marks most of its extension surface @UnstableApi. It is not a
        // Kotlin opt-in marker — it is enforced by a bundled lint check — and the
        // TDLib DataSource, the load control and the renderers factory are all on
        // that surface by necessity. Silenced here rather than annotated in a dozen
        // places, since the version is pinned in libs.versions.toml anyway.
        disable += "UnsafeOptInUsageError"
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
    implementation(libs.okio)

    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.biometric)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.collections.immutable)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.room.testing)
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
    credentialProblems.forEach { logger.warn("HardPlay: $it") }
}
