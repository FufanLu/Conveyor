plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.gradle.spring.boot)
    implementation(libs.gradle.spotless)
    implementation(libs.gradle.errorprone)
    implementation(libs.gradle.protobuf)
}
