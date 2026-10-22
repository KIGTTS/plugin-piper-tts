plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "com.kigtts.engines.piper"
    compileSdk = 36
    defaultConfig { minSdk = 26; ndk { abiFilters += "arm64-v8a" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlin { jvmToolchain(17) }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation(project(":engine-plugins:tts-support"))
    compileOnly(project(":plugin-android-api"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
    implementation("org.tukaani:xz:1.9")
    testImplementation(project(":plugin-android-api"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
tasks.register<Copy>("collectPluginDependencies") {
    dependsOn(":engine-plugins:tts-support:assembleDebug")
    from(project(":engine-plugins:tts-support").layout.buildDirectory.file("outputs/aar/tts-support-debug.aar"))
    from(configurations.getByName("debugRuntimeClasspath").incoming.artifactView {
        componentFilter { it !is org.gradle.api.artifacts.component.ProjectComponentIdentifier }
    }.files)
    into(layout.buildDirectory.dir("plugin-dependencies"))
}
