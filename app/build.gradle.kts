plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "top.cbug.adbx"
    compileSdk = 36

    defaultConfig {
        applicationId = "top.cbug.adbx"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0-beta1"
    }

    signingConfigs {
        create("release") {
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                val lines = propsFile.readLines()
                val map = lines.filter { it.contains("=") }.associate {
                    val (k, v) = it.split("=", limit = 2)
                    k.trim() to v.trim()
                }
                storeFile = rootProject.file(map.getOrDefault("storeFile", "adb_x.jks"))
                storePassword = map.getOrDefault("storePassword", "")
                keyAlias = map.getOrDefault("keyAlias", "")
                keyPassword = map.getOrDefault("keyPassword", "")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.preference:preference-ktx:1.2.1")
}


tasks.withType<Test> {
    useJUnit()
}
