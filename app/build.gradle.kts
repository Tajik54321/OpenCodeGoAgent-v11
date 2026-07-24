plugins {
    id("com.android.application")
}

android {
    namespace = "com.qandil.opencodego"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.qandil.opencodego"
        minSdk = 26
        targetSdk = 37
        versionCode = 1100
        versionName = "11.0.0"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("sideload") {
            dimension = "distribution"
            targetSdk = 28
            versionNameSuffix = "-sideload"
        }
        create("modern") {
            dimension = "distribution"
            targetSdk = 37
            applicationIdSuffix = ".modern"
            versionNameSuffix = "-modern"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/liboc_*.so"
        }
    }
}
