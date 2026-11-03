plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// One fixed signing key for every build (Actions secret, never in git) so new APKs
// install over old ones. Falls back to the local debug key when the env is not set.
val fixedKeystore: File? = System.getenv("MIBOT_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.dl1991.feiyu"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dl1991.feiyu"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (fixedKeystore != null) {
            create("fixed") {
                storeFile = fixedKeystore
                storePassword = System.getenv("MIBOT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MIBOT_KEY_ALIAS") ?: "mibot"
                keyPassword = System.getenv("MIBOT_KEY_PASSWORD") ?: System.getenv("MIBOT_KEYSTORE_PASSWORD")
                storeType = "pkcs12"
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }
    val signing = if (fixedKeystore != null) signingConfigs.getByName("fixed") else signingConfigs.getByName("debug")

    buildTypes {
        getByName("debug") {
            signingConfig = signing
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signing
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/*.kotlin_module", "META-INF/versions/**")
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // RTMP push (pedroSG94/RootEncoder, Apache-2.0). 2.6.1 is built with Kotlin 2.1.20,
    // readable by the Kotlin 2.1.0 compiler used here (2.6.4+ need Kotlin 2.2+).
    implementation("com.github.pedroSG94.RootEncoder:library:2.6.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
