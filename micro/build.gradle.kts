plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val sharedRuntime = tasks.register<Sync>("prepareRuntime") {
    from("../app/src/main/java") {
        include("local/dshx/app/AppPackage.kt", "local/dshx/app/AppStore.kt", "local/dshx/app/AppHost.kt",
            "local/dshx/app/AppPhone.kt", "local/dshx/app/AppColors.kt", "local/dshx/app/PhoneService.kt")
    }
    into(layout.buildDirectory.dir("generated/runtime"))
}

android {
    namespace = "local.dshx.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "local.dshx.micro.template"
        minSdk = 26
        targetSdk = 28
        versionCode = 1
        versionName = "1.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").java.srcDir(sharedRuntime.map { it.destinationDir })
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/assets"))
    buildTypes.getByName("release") {
        isMinifyEnabled = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
    }
    lint { disable += "ExpiredTargetSdkVersion" }
}
tasks.named("preBuild") { dependsOn(sharedRuntime) }
val runtimeAssets = tasks.register<Sync>("prepareRuntimeAssets") {
    from("../app/src/main/assets") { include("micro-app/sdk.js") }
    into(layout.buildDirectory.dir("generated/assets"))
}
tasks.named("preBuild") { dependsOn(runtimeAssets) }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
