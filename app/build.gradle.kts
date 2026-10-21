plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "app.omymoney.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.omymoney.android"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("omymoneyQaVersionCode").orNull?.toInt()?.also {
            require(it == 11) { "Only isolated upgrade acceptance version 11 is supported" }
        } ?: 10
        versionName = if (versionCode == 11) "0.2.9-qa" else "0.2.8"
        buildConfigField("String", "SERVICE_ORIGIN", "\"https://omymoney.origenclub.cn:58952\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { animationsDisabled = true }
    sourceSets.getByName("androidTest").assets.srcDir("../core/src/test/resources")
    lint { checkDependencies = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":data"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.mlkit.text.chinese)
    implementation(libs.work.runtime)
    implementation(libs.okhttp)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.android.junit)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.room.runtime)
    androidTestImplementation(libs.work.testing)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.okhttp.tls)
}
