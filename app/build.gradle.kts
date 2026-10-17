plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.asleep.car"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.asleep.car"
        minSdk = 22
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("signing/asleep.jks")
            storePassword = "asleepcar"
            keyAlias = "asleep"
            keyPassword = "asleepcar"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("fixed") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
