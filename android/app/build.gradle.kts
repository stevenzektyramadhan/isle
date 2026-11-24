import java.security.KeyStore
import java.security.PrivateKey

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH")
val releaseKeystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD")

val validateReleaseCredentials = tasks.register("validateReleaseCredentials") {
    doLast {
        val values = mapOf(
            "ANDROID_KEYSTORE_PATH" to releaseKeystorePath.orNull,
            "ANDROID_KEYSTORE_PASSWORD" to releaseKeystorePassword.orNull,
            "ANDROID_KEY_ALIAS" to releaseKeyAlias.orNull,
            "ANDROID_KEY_PASSWORD" to releaseKeyPassword.orNull,
        )
        val missing = values.filterValues { it.isNullOrBlank() }.keys
        require(missing.isEmpty()) { "Missing release signing environment variables: ${missing.joinToString()}" }
        val keystore = file(releaseKeystorePath.get())
        require(keystore.isFile && keystore.canRead()) { "ANDROID_KEYSTORE_PATH must point to a readable keystore file" }
        try {
            val store = KeyStore.getInstance(KeyStore.getDefaultType())
            keystore.inputStream().use { store.load(it, releaseKeystorePassword.get().toCharArray()) }
            require(store.isKeyEntry(releaseKeyAlias.get())) { "ANDROID_KEY_ALIAS is not a private key entry" }
            require(store.getKey(releaseKeyAlias.get(), releaseKeyPassword.get().toCharArray()) is PrivateKey) {
                "ANDROID_KEY_ALIAS is not a private key entry"
            }
        } catch (e: Exception) {
            throw GradleException("Invalid release signing keystore, alias, or password", e)
        }
    }
}

tasks.configureEach {
    if (name in setOf("packageRelease", "packageReleaseBundle", "signReleaseBundle", "validateSigningRelease")) {
        dependsOn(validateReleaseCredentials)
    }
}

android {
    namespace = "io.github.stevenzektyramadhan.isle"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.stevenzektyramadhan.isle"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    signingConfigs {
        create("release") {
            storeFile = releaseKeystorePath.orNull?.takeIf { it.isNotBlank() }?.let { file(it) }
            storePassword = releaseKeystorePassword.orNull
            keyAlias = releaseKeyAlias.orNull
            keyPassword = releaseKeyPassword.orNull
        }
    }
    buildTypes {
        getByName("release") { signingConfig = signingConfigs.getByName("release") }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.activity.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.youtubedl)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
}
