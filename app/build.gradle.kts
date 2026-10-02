plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Her GitHub Actions derlemesi bir öncekinden büyük sürüm numarası alır: telefonda güncelleme
// eskisinin üstüne kurulur (aynı imza + daha büyük versionCode).
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "tr.egemen.pusula"
    compileSdk = 35

    defaultConfig {
        applicationId = "tr.egemen.pusula"
        minSdk = 26
        targetSdk = 35
        versionCode = runNumber
        versionName = "0.1.$runNumber"
        buildConfigField("String", "BASE_URL", "\"https://para.egemen.tr\"")
    }

    // Uygulama Play Store'a konmuyor, doğrudan telefona kuruluyor. Anahtar deponun içinde (depo özel):
    // her derleme aynı imzayı taşısın ki güncellemeler eskisinin üstüne kurulabilsin.
    signingConfigs {
        create("pusula") {
            storeFile = file("pusula.keystore")
            storePassword = "pusula-android"
            keyAlias = "pusula"
            keyPassword = "pusula-android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("pusula")
        }
        debug {
            signingConfig = signingConfigs.getByName("pusula")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
