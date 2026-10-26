import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// True when the environment names a readable keystore and a password (see "signingConfigs" below).
val hasSigningKey: Boolean = run {
    val path = System.getenv("RIMPYDROID_KEYSTORE")
    val password = System.getenv("RIMPYDROID_KEY_PASSWORD")
    !path.isNullOrBlank() && !password.isNullOrBlank() && file(path).isFile
}

android {
    namespace = "com.rimpydroid.manager"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rimpydroid.manager"
        minSdk = 26
        targetSdk = 35
        // versionName is what people read ("1.0"). versionCode is what Android compares: it may only go up, or an
        // installed copy cannot be updated by this one. (An earlier build used 2, so the next number is 3.)
        versionCode = 3
        versionName = "1.0"
    }

    // The signing key is deliberately NOT in the repository: whoever can read it can sign an "update" of this app
    // that installs over the real one. The build takes the key from two environment variables instead:
    //   RIMPYDROID_KEYSTORE      path of a PKCS12 keystore that holds a key with the alias "rimpydroid"
    //   RIMPYDROID_KEY_PASSWORD  the password of that keystore and of that key
    // The GitHub workflow sets both (from repository secrets, see the README). Without them the release APK stays
    // unsigned and the debug APK uses Android's own debug key.
    signingConfigs {
        if (hasSigningKey) {
            create("shared") {
                storeFile = file(System.getenv("RIMPYDROID_KEYSTORE"))
                storePassword = System.getenv("RIMPYDROID_KEY_PASSWORD")
                keyAlias = "rimpydroid"
                keyPassword = System.getenv("RIMPYDROID_KEY_PASSWORD")
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        debug {
            if (hasSigningKey) signingConfig = signingConfigs.getByName("shared")
        }
        release {
            // No code shrinking: JavaSteam / protobuf rely on reflection.
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasSigningKey) signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.md",
                "META-INF/NOTICE.txt",
                "META-INF/io.netty.versions.properties",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/versions/**/OSGI-INF/MANIFEST.MF",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Lint is not needed to produce the APK; do not let a lint finding stop a release build.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Compose and coroutines mark some APIs "experimental" and refuse to compile calls to them unless the
        // code opts in. Opt in once here instead of in every file (a marker that is not needed is simply ignored).
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

dependencies {
    // --- UI -------------------------------------------------------------------
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // --- Steam Workshop downloads (anonymous login, no Steam client needed) ------
    implementation("in.dragonbra:javasteam:1.8.0")
    implementation("in.dragonbra:javasteam-depotdownloader:1.8.0")
    // JavaSteam needs these on the classpath at runtime (see its README):
    implementation("org.bouncycastle:bcprov-jdk18on:1.83")
    implementation("com.github.luben:zstd-jni:1.5.7-6@aar")
    implementation("org.tukaani:xz:1.11")
    // Used directly to configure timeouts of the Steam HTTP client.
    implementation("com.squareup.okhttp3:okhttp:5.3.2")

    testImplementation("junit:junit:4.13.2")
}
