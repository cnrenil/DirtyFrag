plugins {
    // AGP 9 ships built-in Kotlin support: the standalone
    // 'org.jetbrains.kotlin.android' plugin must NOT be applied.
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "df.root"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "df.root"
        minSdk = 33
        targetSdk = 37
        versionCode = 32
        versionName = "1.15"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("keystore") {
            storeFile = file("keystore.jks")
            storePassword = "dirtyfrag"
            keyAlias = "dirtyfrag"
            keyPassword = "dirtyfrag"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("keystore")
        }
        release {
            signingConfig = signingConfigs.getByName("keystore")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // AGP 9 removed the old applicationVariants API; this is the replacement.
    androidComponents {
        onVariants { variant ->
            variant.outputs.forEach { output ->
                output.outputFileName.set("dirtyfrag.apk")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
    }

    buildFeatures {
        compose = true
        // The About section reads the packaged application version.
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path("src/main/jni/CMakeLists.txt")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.preference)
    // Native miuix backdrop blur for the page bars and navigation.
    implementation(libs.miuix.blur)
    testImplementation("junit:junit:4.13.2")

    // The Android starting-window theme still inherits Material DayNight.
    implementation("com.google.android.material:material:1.13.0")
}
