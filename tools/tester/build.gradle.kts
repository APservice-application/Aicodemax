plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    api(project(":core:common"))
    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.core)
}

tasks.withType<Test> {
    useJUnit()
}
