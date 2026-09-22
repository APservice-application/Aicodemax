plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.aicodemax.tools.memory_runtime"
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
    implementation(project(":data:memory"))
    implementation(project(":tools:gateway"))
    implementation(libs.coroutines.android)
    testImplementation(libs.junit4)
}
