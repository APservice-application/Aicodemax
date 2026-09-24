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
        versionCode = 4
        versionName = "0.1.5"
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

    // CP-118: embedded native tools (.so) must extract to nativeLibraryDir with
    // the exec bit set (same approach as the owner's previous app).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    androidResources {
        // CP-120: bootstrap .gguf model must not be compressed.
        noCompress += "gguf"
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:state"))
    implementation(project(":core:resources"))
    implementation(project(":ai:core"))
    implementation(project(":ai:runtime"))
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
    implementation(project(":tools:runtime"))
    implementation(project(":tools:project"))
    implementation(project(":tools:tester"))
    implementation(project(":tools:builder"))
    implementation(project(":tools:git"))
    implementation(project(":tools:git_runtime"))
    implementation(project(":tools:debug"))
    implementation(project(":tools:debug_runtime"))
    implementation(project(":tools:memory_runtime"))
    implementation(project(":data:skills"))
    implementation(project(":tools:skill_runtime"))
    implementation(project(":tools:github"))
    implementation(project(":tools:browser"))
    implementation(project(":tools:browser_runtime"))
    implementation(project(":tools:voice"))
    implementation(project(":tools:voice_runtime"))
    implementation(project(":tools:image"))
    implementation(project(":tools:image_runtime"))
    implementation(project(":tools:audio"))
    implementation(project(":tools:audio_runtime"))
    implementation(project(":tools:video"))
    implementation(project(":tools:video_runtime"))
    implementation(project(":tools:subtitle"))
    implementation(project(":tools:subtitle_runtime"))
    implementation(project(":tools:render"))
    implementation(project(":tools:render_runtime"))
    implementation(project(":data:media"))
    implementation(project(":tools:media"))
    implementation(project(":tools:media_runtime"))
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
