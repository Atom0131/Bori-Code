import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Load signing credentials from keystore.properties (never committed — see .gitignore)
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(FileInputStream(keystorePropertiesFile))
}

// The GitHub OAuth App behind the full build's browser sign-in (never committed). Without it the
// Linux screen falls back to gh's code sign-in. See docs/FEATURES.md → GitHub.
val githubOAuthFile = rootProject.file("github-oauth.properties")
val githubOAuth = Properties().apply {
    if (githubOAuthFile.exists()) load(FileInputStream(githubOAuthFile))
}

android {
    namespace = "com.bori.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.bori.code"
        minSdk = 26
        targetSdk = 37
        versionCode = 15
        versionName = "4.5.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                keyAlias     = keystoreProperties["keyAlias"]     as String
                keyPassword  = keystoreProperties["keyPassword"]  as String
                storeFile    = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        release {
            signingConfig    = signingConfigs.getByName("release")
            isMinifyEnabled  = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            versionNameSuffix   = "-debug"
        }
        // Release code (R8, not debuggable, so ART runs AOT code) under the debug app ID and key:
        // it installs over fullDebug and keeps Linux, sign-ins and settings. For daily use on a
        // test phone; reinstall fullDebug for run-as and the adb build receiver.
        create("fast") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            versionNameSuffix = "-fast"
            matchingFallbacks += "release"
        }
    }

    // Two distributions. Play can't take the Linux environment (it downloads and runs native code
    // from outside Play), so only `full` ships it, on GitHub.
    // Each flavor's flavor/LinuxFlavor.kt is the switch (HAS_LINUX) and the hooks.
    // App IDs are set per variant in androidComponents below.
    flavorDimensions += "dist"
    productFlavors {
        create("play") {
            dimension = "dist"
        }
        create("full") {
            dimension = "dist"
            versionNameSuffix = "-full"
            buildConfigField("String", "GITHUB_CLIENT_ID", "\"${githubOAuth.getProperty("clientId", "")}\"")
            buildConfigField("String", "GITHUB_CLIENT_SECRET", "\"${githubOAuth.getProperty("clientSecret", "")}\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Libs must be extracted on install: ToolsManager runs libaapt2.so from nativeLibraryDir.
            // Extracted libs only need 16 KB ELF alignment (not zip alignment) for 16 KB devices;
            // every bundled .so has it — check new native deps before adding them.
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/BCKEY.DSA"
            excludes += "META-INF/BCKEY.SF"
            excludes += "META-INF/BCCERTS.SF"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
            excludes += "META-INF/DEPENDENCIES"
        }
    }

    bundle {
        language { enableSplit = true }
        density  { enableSplit = true }
        abi      { enableSplit = true }
    }
}

// Exact app IDs, since flavor and build-type suffixes would stack:
//   playRelease com.bori.code (Play)   fullRelease com.bori.code.full (GitHub)
//   fullDebug   com.bori.code.debug (the dev build on test devices)   playDebug com.bori.code.play.debug
//   fullFast    com.bori.code.debug (release code, debug key; installs over fullDebug)
androidComponents {
    onVariants { variant ->
        val base = "com.bori.code"
        variant.applicationId.set(
            when (variant.name) {
                "playRelease" -> base
                "fullRelease" -> "$base.full"
                "fullDebug"   -> "$base.debug"
                "fullFast"    -> "$base.debug"
                "playDebug"   -> "$base.play.debug"
                else -> return@onVariants // a new flavor/build type keeps AGP's default ID
            }
        )
    }
}

// Export Room schemas so future migrations can be authored and tested against them.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// LiteRT-LM 0.16.1 is built with Kotlin 2.3 and calls SendChannel.close$default as an
// interface static, which kotlinx-coroutines only has from 1.11 on. With 1.9 on the
// classpath every on-device Gemma reply crashed with NoSuchMethodError when it finished.
// 1.11 keeps the DefaultImpls bridges the rest of the app (Kotlin 2.0) was compiled against.
configurations.all {
    resolutionStrategy.force(
        "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0",
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0",
    )
}

dependencies {
    implementation(project(":core:ai"))
    implementation(project(":core:security"))
    implementation(project(":core:editor"))
    implementation(project(":core:project"))
    implementation(project(":core:build"))
    implementation(project(":core:terminal"))
    implementation(project(":core:keyboard"))
    implementation(project(":core:theme"))
    // The Linux environment: full build only (see productFlavors).
    "fullImplementation"(project(":core:linux"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.window)
    // Applies baseline-prof.txt at install, so the hot paths are AOT-compiled from the first launch.
    implementation(libs.androidx.profileinstaller)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)
    implementation(libs.compose.markdown)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)
    // The real org.json for JVM unit tests (android.jar only has stubs that throw).
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
