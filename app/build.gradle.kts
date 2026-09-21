plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.aicodemax.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aicodemax"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:state"))
    implementation(project(":core:resources"))
    implementation(project(":ai:core"))
    implementation(project(":ai:tasks"))
    implementation(project(":ai:agents"))
    implementation(project(":ai:models"))
    implementation(project(":tools:registry"))
    implementation(project(":tools:capability"))
    implementation(project(":tools:gateway"))
    implementation(project(":tools:files"))
    implementation(project(":tools:editor"))
    implementation(project(":tools:terminal"))
    implementation(project(":tools:terminal_runtime"))
    implementation(project(":tools:project"))
    implementation(project(":tools:tester"))
    implementation(project(":tools:builder"))
    implementation(project(":tools:git"))
    implementation(project(":tools:git_runtime"))
    implementation(project(":tools:browser"))
    implementation(project(":tools:browser_runtime"))
    implementation(project(":data:checkpoint"))
    implementation(project(":data:audit"))
    implementation(project(":data:memory"))
    implementation(project(":data:conversations"))
    implementation(project(":data:settings"))
    implementation(project(":ui:designsystem"))
    implementation(project(":ui:chat"))
    implementation(project(":ui:workspace"))
    implementation(project(":ui:settings"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)
}
