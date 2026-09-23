plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    api(project(":core:common"))
    api(project(":tools:registry"))
    api(project(":tools:image"))
    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.core)
}

tasks.withType<Test> {
    useJUnit()
}
