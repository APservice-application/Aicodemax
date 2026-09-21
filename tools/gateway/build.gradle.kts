plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.aicodemax.tools.gateway"
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
    api(project(":tools:registry"))
    api(project(":tools:capability"))
    implementation(project(":data:audit"))
    implementation(libs.coroutines.android)
    testImplementation(libs.junit4)
    testImplementation(project(":tools:files"))
    testImplementation(project(":tools:editor"))
    testImplementation(project(":tools:git"))
    testImplementation(project(":tools:git_runtime"))
    testImplementation(project(":tools:browser"))
    testImplementation(project(":tools:browser_runtime"))
    testImplementation(project(":data:audit"))
}
