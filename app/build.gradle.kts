plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun String.asBuildConfigString(): String = "\"" +
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

val layerHubApiBaseUrl = providers.gradleProperty("LAYER_HUB_API_BASE_URL")
    .orElse(providers.environmentVariable("LAYER_HUB_API_BASE_URL")).getOrElse("")
android {
    namespace = "com.example.androidtest"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.androidtest"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "LAYER_HUB_API_BASE_URL", layerHubApiBaseUrl.asBuildConfigString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":downloader-engine"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
