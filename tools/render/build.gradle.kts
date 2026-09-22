plugins {
    id("org.jetbrains.kotlin.jvm")
    id("kotlinx-serialization")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":tools:capability"))
    implementation(project(":tools:video"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
