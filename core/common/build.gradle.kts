plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    implementation(libs.coroutines.core)
    testImplementation(libs.junit4)
}

tasks.withType<Test> {
    useJUnit()
}
