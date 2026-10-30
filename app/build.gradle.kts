import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    ndkVersion = "28.2.13676358"
    namespace = "local.dshx.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "local.dshx.app"
        minSdk = 26
        targetSdk = 28
        versionCode = 2
        versionName = "0.1.0-dev.1"
        val info = file("src/main/assets/core-info.json")
        val abi = providers.gradleProperty("dshxAbi").orNull
            ?: if (info.isFile) Regex("\"abi\"\\s*:\\s*\"([^\"]+)\"").find(info.readText())?.groupValues?.get(1) else null
        ndk { abiFilters += (abi ?: "arm64-v8a") }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release") } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    androidResources { noCompress += "zip" }
    lint { disable += "ExpiredTargetSdkVersion" }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("io.github.reandroid:ARSCLib:1.4.0")
    implementation("com.android.tools.build:apksig:8.5.2")
    implementation(files("libs/sherpa-onnx-1.13.2.aar"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("top.yukonga.miuix.kmp:miuix:0.8.8")
    implementation("com.composables:icons-lucide:1.1.0")
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation("androidx.compose.ui:ui:1.10.5")
    implementation("androidx.compose.foundation:foundation:1.10.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.15.1")
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.79")
}

// 精简运行壳由同一份网页/存储/手机代码构建；手机导出无需下载 SDK 或调用服务器。
val microAssets = layout.buildDirectory.dir("generated/microAssets")
val prepareMicroApk by tasks.registering(Copy::class) {
    dependsOn(":micro:assembleRelease")
    from(project(":micro").layout.buildDirectory.file("outputs/apk/release/micro-release-unsigned.apk"))
    into(microAssets.map { it.dir("micro-apk") })
    rename { "runtime.apk" }
}
android.sourceSets.getByName("main").assets.srcDir(microAssets)
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(prepareMicroApk) }

tasks.matching { it.name == "preBuild" }.configureEach {
    doFirst {
        val voiceRuntime = file("libs/sherpa-onnx-1.13.2.aar")
        check(voiceRuntime.isFile && file("src/main/assets/voice/models.json").isFile) {
            "缺少本地语音依赖，请先执行 python3 tools/voice.py prepare"
        }
        val runtimeHash = MessageDigest.getInstance("SHA-256").digest(voiceRuntime.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        check(runtimeHash == "9b2a290b8c7f31bd0aba35abb4628e87fe8d0eb71796a98aa12f3acd089ceaed") { "语音运行库校验失败，请重新准备依赖。" }
        check(listOf("core.zip", "core.json", "core-info.json").all { file("src/main/assets/$it").isFile }) {
            "缺少真实运行环境，请先执行 python3 tools/core.py build --abi arm64-v8a"
        }
    }
}
