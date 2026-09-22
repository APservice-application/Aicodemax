plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    api(project(":core:common"))
    api(project(":tools:registry"))
    testImplementation(libs.junit4)
}

tasks.withType<Test> {
    useJUnit()
}
