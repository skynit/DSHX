plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "local.dshx.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "local.dshx.app"
        minSdk = 26
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0-dev"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    androidResources { noCompress += "zip" }
    lint { disable += "ExpiredTargetSdkVersion" }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("top.yukonga.miuix.kmp:miuix:0.8.8")
    implementation("com.composables:icons-lucide:1.1.0")
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation("androidx.compose.ui:ui:1.10.5")
    implementation("androidx.compose.foundation:foundation:1.10.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.15.1")
}

tasks.matching { it.name == "preBuild" }.configureEach {
    doFirst {
        check(listOf("core.zip", "core.json", "core-info.json").all { file("src/main/assets/$it").isFile }) {
            "缺少真实运行环境，请先执行 python3 tools/core.py build --abi arm64-v8a"
        }
    }
}
