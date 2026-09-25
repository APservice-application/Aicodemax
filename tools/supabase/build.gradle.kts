plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

dependencies {
    api(project(":core:common"))
    api(project(":tools:registry"))
    implementation(libs.serialization.json)
    testImplementation(libs.junit4)
}

tasks.withType<Test> {
    useJUnit()
}
