plugins { id("com.android.application") }

val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()

android {
    namespace = "se.cruzi.tvhem"
    compileSdk = 35
    defaultConfig {
        applicationId = "se.cruzi.tvhem"
        minSdk = 30
        targetSdk = 34
        versionCode = 1000 + run
        versionName = "4.$run"
    }
    signingConfigs {
        create("hem") {
            storeFile = file("hem.keystore")
            storePassword = "hemhemhem"
            keyAlias = "hem"
            keyPassword = "hemhemhem"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("hem")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
}
