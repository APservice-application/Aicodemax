plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    api(project(":core:common"))
    api(project(":core:state"))
    api(project(":data:checkpoint"))
    implementation(libs.coroutines.core)
    testImplementation(libs.junit4)
}

tasks.withType<Test> {
    useJUnit()
}
