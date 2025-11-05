plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.jaredsburrows.license")
}

android {
    namespace = "com.v2ray.ang"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.v2ray.ang"
        minSdk = 21
        targetSdk = 35
        versionCode = 677
        versionName = "1.10.26"
        multiDexEnabled = true

        val abiFilterList = (properties["ABI_FILTERS"] as? String)?.split(';')
        splits {
            abi {
                isEnable = true
                reset()
                if (abiFilterList != null && abiFilterList.isNotEmpty()) {
                    include(*abiFilterList.toTypedArray())
                } else {
                    include(
                        "arm64-v8a",
                        "armeabi-v7a",
                        "x86_64",
                        "x86"
                    )
                }
                isUniversalApk = abiFilterList.isNullOrEmpty()
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    flavorDimensions.add("distribution")
    productFlavors {
        create("fdroid") {
            dimension = "distribution"
            applicationIdSuffix = ".fdroid"
            buildConfigField("String", "DISTRIBUTION", "\"F-Droid\"")
        }
        create("playstore") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"Play Store\"")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }


    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    applicationVariants.all {
        val variant = this
        val isFdroid = variant.productFlavors.any { it.name == "fdroid" }
        if (isFdroid) {
            val versionCodes =
                mapOf(
                    "armeabi-v7a" to 2, "arm64-v8a" to 1, "x86" to 4, "x86_64" to 3, "universal" to 0
                )

            variant.outputs
                .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
                .forEach { output ->
                    val abi = output.getFilter("ABI") ?: "universal"
                    output.outputFileName = "v2rayNG_${variant.versionName}-fdroid_${abi}.apk"
                    if (versionCodes.containsKey(abi)) {
                        output.versionCodeOverride =
                            (100 * variant.versionCode + versionCodes[abi]!!).plus(5000000)
                    } else {
                        return@forEach
                    }
                }
        } else {
            val versionCodes =
                mapOf("armeabi-v7a" to 4, "arm64-v8a" to 4, "x86" to 4, "x86_64" to 4, "universal" to 4)

            variant.outputs
                .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
                .forEach { output ->
                    val abi = if (output.getFilter("ABI") != null)
                        output.getFilter("ABI")
                    else
                        "universal"

                    output.outputFileName = "v2rayNG_${variant.versionName}_${abi}.apk"
                    if (versionCodes.containsKey(abi)) {
                        output.versionCodeOverride =
                            (1000000 * versionCodes[abi]!!).plus(variant.versionCode)
                    } else {
                        return@forEach
                    }
                }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

}

// 统一 Kotlin Toolchain 到 JDK 17
kotlin {
    jvmToolchain(17)
}

// 可覆盖的命令/参数（默认适配 macOS/Linux PATH）
val adbCmd = (project.findProperty("adbCmd") as? String) ?: "adb"
val emulatorCmd = (project.findProperty("emulatorCmd") as? String) ?: "emulator"
val avdName = (project.findProperty("avdName") as? String) ?: "Pixel_6_API_34"

// 基础 Exec 任务，避免脚本中解析输出
tasks.register<Exec>("startEmulator") {
    // 启动模拟器（可能阻塞；如需后台可改用 shell 包裹）
    commandLine(emulatorCmd, "-avd", avdName, "-netdelay", "none", "-netspeed", "full", "-no-snapshot-load", "-no-boot-anim", "-no-audio", "-gpu", "auto")
    isIgnoreExitValue = true
}

tasks.register<Exec>("adbWaitForDevice") {
    commandLine(adbCmd, "wait-for-device")
    isIgnoreExitValue = true
}

tasks.register<Exec>("adbEnsureBootCompleted") {
    // 使用 shell 循环等待 sys.boot_completed=1
    commandLine("sh", "-lc", "while [ \"\$(adb shell getprop sys.boot_completed)\" != \"1\" ]; do sleep 1; done")
    isIgnoreExitValue = true
}

tasks.register("waitForDevice") {
    dependsOn("startEmulator", "adbWaitForDevice", "adbEnsureBootCompleted")
}

tasks.register<Exec>("uninstallOldApkPlaystore") {
    dependsOn("waitForDevice")
    commandLine(adbCmd, "uninstall", "com.v2ray.ang")
    isIgnoreExitValue = true
}

tasks.register<Exec>("uninstallOldApkFdroid") {
    dependsOn("waitForDevice")
    commandLine(adbCmd, "uninstall", "com.v2ray.ang.fdroid")
    isIgnoreExitValue = true
}

// 让 install 任务在执行前依赖设备就绪与卸载旧版
tasks.matching { it.name == "installPlaystoreDebug" }.configureEach { dependsOn("waitForDevice", "uninstallOldApkPlaystore") }
tasks.matching { it.name == "installFdroidDebug" }.configureEach { dependsOn("waitForDevice", "uninstallOldApkFdroid") }

// 组合任务：安装并跟踪 logcat（Playstore/F-Droid Debug）
tasks.register<Exec>("installPlaystoreDebugAndLogcat") {
    dependsOn("waitForDevice", ":app:installPlaystoreDebug")
    // 清理日志、拉起应用并按 PID 过滤日志
    commandLine(
        "sh", "-lc",
        "adb logcat -c && adb shell am start -n com.v2ray.ang/.ui.MainActivity && PID=\$(adb shell pidof com.v2ray.ang || true); if [ -z \"\$PID\" ]; then PID=\$(adb shell ps -A | awk '/com.v2ray.ang/ {print \$2; exit}'); fi; if [ -z \"\$PID\" ]; then echo 'PID not found' && exit 1; fi; adb logcat --pid \$PID -v time v2rayNG:D AndroidRuntime:E *:W"
    )
}

tasks.register<Exec>("installFdroidDebugAndLogcat") {
    dependsOn("waitForDevice", ":app:installFdroidDebug")
    commandLine(
        "sh", "-lc",
        "adb logcat -c && adb shell am start -n com.v2ray.ang.fdroid/.ui.MainActivity && PID=\$(adb shell pidof com.v2ray.ang.fdroid || true); if [ -z \"\$PID\" ]; then PID=\$(adb shell ps -A | awk '/com.v2ray.ang.fdroid/ {print \$2; exit}'); fi; if [ -z \"\$PID\" ]; then echo 'PID not found' && exit 1; fi; adb logcat --pid \$PID -v time v2rayNG:D AndroidRuntime:E *:W"
    )
}

dependencies {
    // Core Libraries
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // AndroidX Core Libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.preference.ktx)
    implementation(libs.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)

    // UI Libraries
    implementation(libs.material)
    implementation(libs.toasty)
    implementation(libs.editorkit)
    implementation(libs.flexbox)

    // Data and Storage Libraries
    implementation(libs.mmkv.static)
    implementation(libs.gson)

    // Reactive and Utility Libraries
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Language and Processing Libraries
    implementation(libs.language.base)
    implementation(libs.language.json)

    // Intent and Utility Libraries
    implementation(libs.quickie.foss)
    implementation(libs.core)

    // AndroidX Lifecycle and Architecture Components
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Background Task Libraries
    implementation(libs.work.runtime.ktx)
    implementation(libs.work.multiprocess)

    // Multidex Support
    implementation(libs.multidex)

    // Testing Libraries
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.org.mockito.mockito.inline)
    testImplementation(libs.mockito.kotlin)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
