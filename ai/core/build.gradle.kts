plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.aicodemax.ai.core"
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
    api(project(":core:state"))
    api(project(":ai:tasks"))
    api(project(":ai:models"))
    api(project(":tools:registry"))
    implementation(project(":data:memory"))
    implementation(project(":data:conversations"))
    implementation(project(":data:checkpoint"))
    implementation(libs.coroutines.android)
}
