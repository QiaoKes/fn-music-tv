import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val versionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use(::load)
}
val managedVersionCode = requireNotNull(versionProperties.getProperty("VERSION_CODE")) {
    "VERSION_CODE is required in version.properties"
}.toInt()
val managedVersionName = requireNotNull(versionProperties.getProperty("VERSION_NAME")) {
    "VERSION_NAME is required in version.properties"
}
val releaseSigningDirectory = file(System.getProperty("user.home")).resolve(".config/fn-music-tv")
val releaseKeystoreFile = providers.gradleProperty("fnMusicReleaseStoreFile").orNull
    ?.let(::file)
    ?: releaseSigningDirectory.resolve("release.jks")
val releasePasswordFile = releaseSigningDirectory.resolve("release.password")
val releaseSigningPassword = providers.gradleProperty("fnMusicReleasePassword").orNull
    ?: providers.environmentVariable("FN_MUSIC_RELEASE_PASSWORD").orNull
    ?: releasePasswordFile.takeIf { it.isFile }?.readText()?.trim()
val releaseSigningReady = releaseKeystoreFile.isFile && !releaseSigningPassword.isNullOrBlank()
val allowUnsignedRelease = providers.gradleProperty("allowUnsignedRelease").orNull?.toBoolean() == true

android {
    namespace = "com.fnmusic.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fnmusic.tv"
        minSdk = 23
        targetSdk = 36
        versionCode = managedVersionCode
        versionName = managedVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("sideload") { dimension = "distribution" }
        create("store") { dimension = "distribution" }
    }

    signingConfigs {
        create("release") {
            if (releaseSigningReady) {
                storeFile = releaseKeystoreFile
                storePassword = requireNotNull(releaseSigningPassword)
                keyAlias = "fn-music-tv"
                keyPassword = releaseSigningPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    lint {
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = true
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }
}

base {
    archivesName.set("fn-music-tv-$managedVersionName")
}

val verifyReleaseSigning by tasks.registering {
    group = "verification"
    description = "Verifies the local Fn Music TV release signing identity."
    doLast {
        if (allowUnsignedRelease) return@doLast
        check(releaseKeystoreFile.isFile) {
            "Release keystore is missing: $releaseKeystoreFile"
        }
        check(!releaseSigningPassword.isNullOrBlank()) {
            "Release signing password is missing. Set fnMusicReleasePassword, FN_MUSIC_RELEASE_PASSWORD, or $releasePasswordFile"
        }
    }
}

tasks.configureEach {
    if (
        name == "packageSideloadRelease" ||
        name == "packageStoreRelease" ||
        name == "bundleSideloadRelease" ||
        name == "bundleStoreRelease"
    ) {
        dependsOn(verifyReleaseSigning)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:playback"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.tv.material)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.uiautomator)
    testImplementation(libs.junit)
}
