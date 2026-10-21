plugins {
    alias(libs.plugins.android.lint)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
tasks.test {
    systemProperty("omymoney.repoRoot", rootProject.projectDir.absolutePath)
    testLogging { events("failed", "skipped") }
}
