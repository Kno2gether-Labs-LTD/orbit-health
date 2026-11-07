plugins {
    kotlin("jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":ring-core"))
    testImplementation(testFixtures(project(":ring-core")))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
