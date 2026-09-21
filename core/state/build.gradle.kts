plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    api(project(":core:common"))
    implementation(libs.coroutines.core)
    testImplementation(libs.junit4)
}

tasks.withType<Test> {
    useJUnit()
}
