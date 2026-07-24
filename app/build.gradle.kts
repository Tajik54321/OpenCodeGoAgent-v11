plugins {
    id("com.android.application")
}

android {
    namespace = "com.qandil.opencodego"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.qandil.opencodego.exclusive"
        minSdk = 26
        targetSdk = 36
        versionCode = 1100
        versionName = "11.0.0-exclusive"
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
            targetSdk = 36
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
        disable += "GestureBackNavigation"
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/liboc_*.so"
        }
    }
}
