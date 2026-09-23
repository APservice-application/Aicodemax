plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.aicodemax.tools.debug_runtime"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":core:common"))
    implementation(project(":tools:registry"))
    implementation(project(":tools:debug"))
    implementation(project(":tools:gateway"))
    implementation(project(":tools:runtime"))
    implementation(project(":tools:capability"))
    implementation(libs.coroutines.android)
    testImplementation(libs.junit4)
}
