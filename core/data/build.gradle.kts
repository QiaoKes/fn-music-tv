import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

data class FnConnectEncryptedValue(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
)

fun encryptFnConnectValue(value: ByteArray, key: ByteArray, random: SecureRandom): FnConnectEncryptedValue {
    val nonce = ByteArray(12).also(random::nextBytes)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    return FnConnectEncryptedValue(cipher.doFinal(value), nonce)
}

fun ByteArray.asKotlinByteArray(): String =
    joinToString(prefix = "byteArrayOf(", postfix = ")") { "${it.toInt() and 0xff}.toByte()" }

val fnConnectAuthxPrefix = providers.environmentVariable("FN_CONNECT_AUTHX_PREFIX")
val fnConnectApiKey = providers.environmentVariable("FN_CONNECT_API_KEY")
val fnConnectGeneratedSourceDirectory = layout.buildDirectory.dir("generated/source/fnConnect/kotlin")

val generateFnConnectPayload by tasks.registering {
    description = "Generates release connection configuration payloads."
    outputs.dir(fnConnectGeneratedSourceDirectory)
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }

    doLast {
        val authxPrefix = fnConnectAuthxPrefix.orNull
        val apiKey = fnConnectApiKey.orNull
        val hasAuthxPrefix = !authxPrefix.isNullOrEmpty()
        val hasApiKey = !apiKey.isNullOrEmpty()
        check(hasAuthxPrefix == hasApiKey) {
            "Connection signing configuration must be provided as a complete pair."
        }

        val configured = hasAuthxPrefix && hasApiKey
        var encryptedPrefix = FnConnectEncryptedValue(byteArrayOf(), byteArrayOf())
        var encryptedApiKey = FnConnectEncryptedValue(byteArrayOf(), byteArrayOf())
        var keyMask = byteArrayOf()
        var maskedKey = byteArrayOf()

        if (configured) {
            val random = SecureRandom()
            val contentKey = ByteArray(32).also(random::nextBytes)
            keyMask = ByteArray(contentKey.size).also(random::nextBytes)
            maskedKey = ByteArray(contentKey.size) { index ->
                (contentKey[index].toInt() xor keyMask[index].toInt()).toByte()
            }
            val prefixBytes = requireNotNull(authxPrefix).toByteArray(Charsets.UTF_8)
            val apiKeyBytes = requireNotNull(apiKey).toByteArray(Charsets.UTF_8)
            try {
                encryptedPrefix = encryptFnConnectValue(prefixBytes, contentKey, random)
                encryptedApiKey = encryptFnConnectValue(apiKeyBytes, contentKey, random)
            } finally {
                prefixBytes.fill(0)
                apiKeyBytes.fill(0)
                contentKey.fill(0)
            }
        }

        val generatedFile = fnConnectGeneratedSourceDirectory.get()
            .file("com/fnmusic/tv/core/data/server/GeneratedFnConnectPayload.kt")
            .asFile
        generatedFile.parentFile.mkdirs()
        generatedFile.writeText(
            """
            package com.fnmusic.tv.core.data.server

            internal object GeneratedFnConnectPayload {
                const val isConfigured: Boolean = $configured

                fun encryptedPrefix(): ByteArray = ${encryptedPrefix.ciphertext.asKotlinByteArray()}
                fun prefixNonce(): ByteArray = ${encryptedPrefix.nonce.asKotlinByteArray()}
                fun encryptedApiKey(): ByteArray = ${encryptedApiKey.ciphertext.asKotlinByteArray()}
                fun apiKeyNonce(): ByteArray = ${encryptedApiKey.nonce.asKotlinByteArray()}
                fun keyMask(): ByteArray = ${keyMask.asKotlinByteArray()}
                fun maskedKey(): ByteArray = ${maskedKey.asKotlinByteArray()}
            }
            """.trimIndent() + "\n",
        )
        keyMask.fill(0)
        maskedKey.fill(0)
    }
}

val verifyFnConnectReleaseConfiguration by tasks.registering {
    description = "Verifies release connection configuration."
    doLast {
        check(!fnConnectAuthxPrefix.orNull.isNullOrEmpty() && !fnConnectApiKey.orNull.isNullOrEmpty()) {
            "Release connection configuration is missing."
        }
    }
}

android {
    namespace = "com.fnmusic.tv.core.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures { buildConfig = false }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://maven.aliyun.com/repository/central")
        }
    }
    sourceSets.getByName("main").java.srcDir(fnConnectGeneratedSourceDirectory)
    sourceSets.getByName("test").assets.srcDir("$projectDir/schemas")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.configureEach {
    if ((name.startsWith("compile") || name.startsWith("ksp")) && name.endsWith("Kotlin")) {
        dependsOn(generateFnConnectPayload)
    }
    if (name == "compileReleaseKotlin" || name == "kspReleaseKotlin") {
        dependsOn(verifyFnConnectReleaseConfiguration)
    }
}

room { schemaDirectory("$projectDir/schemas") }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:lyrics"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.runner)
    testImplementation(libs.robolectric)
}
