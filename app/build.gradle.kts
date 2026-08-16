import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
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
val updateManifestUrl = providers.gradleProperty("fnMusicUpdateManifestUrl").orNull
    ?: providers.environmentVariable("FN_MUSIC_UPDATE_MANIFEST_URL").orNull
    ?: ""

fun String.asBuildConfigString(): String = buildString {
    append('"')
    this@asBuildConfigString.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            else -> append(character)
        }
    }
    append('"')
}

android {
    namespace = "com.fnmusic.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fnmusic.tv"
        minSdk = 29
        targetSdk = 36
        versionCode = managedVersionCode
        versionName = managedVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("sideload") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE_ENABLED", "true")
            buildConfigField("String", "UPDATE_MANIFEST_URL", updateManifestUrl.asBuildConfigString())
        }
        create("store") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE_ENABLED", "false")
            buildConfigField("String", "UPDATE_MANIFEST_URL", "".asBuildConfigString())
        }
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

    buildFeatures {
        buildConfig = true
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    lint {
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = true
        disable += setOf(
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "NewerVersionAvailable",
            "OldTargetApi",
        )
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

val verifySideloadUpdateConfiguration by tasks.registering {
    group = "verification"
    description = "Verifies the sideload release update manifest URL."
    doLast {
        val parsed = runCatching { URI(updateManifestUrl) }.getOrNull()
        check(
            parsed?.scheme.equals("https", ignoreCase = true) &&
                !parsed?.host.isNullOrBlank() &&
                parsed?.userInfo == null &&
                parsed?.fragment == null,
        ) {
            "Sideload Release requires an HTTPS fnMusicUpdateManifestUrl without credentials or fragments."
        }
    }
}

tasks.configureEach {
    if (
        name == "packageSideloadRelease" ||
        name == "bundleSideloadRelease" ||
        name == "packageStoreRelease" ||
        name == "bundleStoreRelease"
    ) {
        dependsOn(verifyReleaseSigning)
    }
    if (name == "packageSideloadRelease" || name == "bundleSideloadRelease") {
        dependsOn(verifySideloadUpdateConfiguration)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:lyrics"))
    implementation(project(":core:data"))
    implementation(project(":core:playback"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.tv.material)
    implementation(libs.accompanist.lyrics.ui)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    baselineProfile(project(":baselineprofile"))
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.uiautomator)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.androidx.test.core)
}
