plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

dependencies {
    api(project(":core:common"))
    api(project(":tools:registry"))
    api(project(":tools:video"))
    implementation(libs.serialization.json)
    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.core)
}

tasks.withType<Test> {
    useJUnit()
}
