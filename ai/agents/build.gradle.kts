plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.aicodemax.ai.agents"
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
    api(project(":ai:core"))
    implementation(project(":tools:gateway"))
    implementation(project(":tools:capability"))
    implementation(project(":ai:tasks"))
    implementation(project(":ai:models"))
    implementation(project(":ai:runtime"))
    testImplementation(project(":tools:runtime"))
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    testImplementation(libs.junit4)
}
