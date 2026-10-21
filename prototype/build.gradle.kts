plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "local.dshx.prototype"
    compileSdk = 34

    defaultConfig {
        applicationId = "local.dshx.prototype"
        minSdk = 26
        targetSdk = 28
        versionCode = 1
        versionName = "0.0.1-p0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint {
        // PLAN 的 v1 明确采用 target 28 手动安装；Google Play 上架门槛不适用于此原型。
        disable += "ExpiredTargetSdkVersion"
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.15.1")
}
