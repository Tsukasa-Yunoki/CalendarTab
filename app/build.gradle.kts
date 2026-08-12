plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "my.app.calendarkiosk"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "my.app.calendarkiosk"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
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
}


dependencies {
        // 1.12.xなどはAndroid 5で動かないため、古い安定版(1.2.0付近)に固定します
        implementation("androidx.appcompat:appcompat:1.2.0")
        implementation("com.google.android.material:material:1.3.0")
        implementation("androidx.constraintlayout:constraintlayout:2.0.4")
}