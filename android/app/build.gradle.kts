plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val versionCodeEnv = (System.getenv("APP_VERSION_CODE") ?: "1").toInt()

android {
    namespace = "com.frozzcolombia.domicilios"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.frozzcolombia.domicilios"
        minSdk = 24
        targetSdk = 35
        versionCode = versionCodeEnv
        versionName = "1.0.$versionCodeEnv"
    }

    signingConfigs {
        create("frozz") {
            storeFile = file("../keystore/frozz-domiciliarios.jks")
            storePassword = "frozz-domicilios"
            keyAlias = "frozz"
            keyPassword = "frozz-domicilios"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("frozz")
        }
        debug {
            signingConfig = signingConfigs.getByName("frozz")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
