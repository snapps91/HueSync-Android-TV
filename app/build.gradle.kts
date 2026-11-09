plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.snapps91.huesync"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    signingConfigs {
        val keystorePath = System.getenv("HUESYNC_KEYSTORE_PATH")
        if (!keystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("HUESYNC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HUESYNC_KEY_ALIAS")
                keyPassword = System.getenv("HUESYNC_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.snapps91.huesync"
        minSdk = 23
        targetSdk = 36
        versionCode = 2
        versionName = "0.0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    packaging {
        resources.merges += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
    }
    // Exercise the DTLS client on both the host JVM and Android's native crypto providers.
    sourceSets.getByName("test").kotlin.srcDir("src/sharedTest/java")
    sourceSets.getByName("androidTest").kotlin.srcDir("src/sharedTest/java")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("org.bouncycastle:bctls-jdk18on:1.86.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
