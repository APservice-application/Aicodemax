plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":tools:capability"))
    implementation(project(":tools:runtime"))
    implementation(project(":tools:render"))
    implementation(project(":tools:media"))
    implementation(libs.kotlinx.coroutines.core)
}
