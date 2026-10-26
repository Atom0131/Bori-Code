plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.bori.core.linux"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
        // :core:ai brings in LiteRT-LM's newer kotlin-stdlib (see app/build.gradle.kts).
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }
}

dependencies {
    implementation(project(":core:project"))
    implementation(project(":core:ai"))
    implementation(project(":core:security"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.commons.compress)
    implementation(libs.xz)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
