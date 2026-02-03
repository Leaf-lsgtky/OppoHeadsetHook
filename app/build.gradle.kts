plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin)
}

android {
    namespace = "moe.chenxy.oppoheadset"
    compileSdk = 35

    defaultConfig {
        applicationId = "moe.chenxy.oppoheadset"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(17)
        }
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.coreKtx)
    compileOnly(libs.xposedApi)
}
