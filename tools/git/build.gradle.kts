plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    api(project(":core:common"))
    api(project(":tools:registry"))
    implementation(libs.jgit)
    testImplementation(libs.junit4)
}

tasks.withType<Test> {
    useJUnit()
}
