import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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

android {
    namespace = "com.bori.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bori.code"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "4.2.0"

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
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }

    buildFeatures {
        compose = true
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
androidComponents {
    onVariants { variant ->
        val base = "com.bori.code"
        variant.applicationId.set(
            when (variant.name) {
                "playRelease" -> base
                "fullRelease" -> "$base.full"
                "fullDebug"   -> "$base.debug"
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
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
