plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

dependencies {
    implementation(libs.serialization.json)
    api(project(":core:common"))
    api(project(":tools:registry"))
    api(project(":data:media"))
    implementation(project(":tools:image"))
    implementation(project(":tools:audio"))
    implementation(project(":tools:video"))
    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.core)
}

tasks.withType<Test> {
    useJUnit()
}
