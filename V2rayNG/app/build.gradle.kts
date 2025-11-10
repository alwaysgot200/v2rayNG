import java.io.File
import java.io.ByteArrayOutputStream
import org.gradle.api.GradleException

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
            // 包含模块内 libs 以及仓库根 ../libs（compile-tun2socks.sh 默认输出到仓库根 libs）
            jniLibs.srcDirs("libs", "../libs")
        }
    }


    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // 使用 Kotlin compilerOptions/jvmToolchain，移除弃用的 kotlinOptions 配置

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

    // Release 签名配置：从环境变量或项目属性读取并应用到 release 构建
    // 环境变量/属性：APP_KEYSTORE_FILE, APP_KEYSTORE_PASSWORD, APP_KEYSTORE_ALIAS, APP_KEY_PASSWORD
    val ksFilePath = ((project.findProperty("APP_KEYSTORE_FILE") as? String)
        ?: System.getenv("APP_KEYSTORE_FILE"))?.trim()
    val ksStorePass = (project.findProperty("APP_KEYSTORE_PASSWORD") as? String)
        ?: System.getenv("APP_KEYSTORE_PASSWORD")
    val ksAlias = (project.findProperty("APP_KEYSTORE_ALIAS") as? String)
        ?: System.getenv("APP_KEYSTORE_ALIAS")
    val ksKeyPass = (project.findProperty("APP_KEY_PASSWORD") as? String)
        ?: System.getenv("APP_KEY_PASSWORD")

    if (!ksFilePath.isNullOrBlank() && File(ksFilePath).exists()
        && !ksStorePass.isNullOrBlank() && !ksAlias.isNullOrBlank() && !ksKeyPass.isNullOrBlank()) {
        println("[Gradle] 检测到 Release 签名配置，已应用 keystore: $ksFilePath")
        signingConfigs {
            create("release") {
                storeFile = File(ksFilePath)
                storePassword = ksStorePass
                keyAlias = ksAlias
                keyPassword = ksKeyPass
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    } else {
        println("[Gradle] 未检测到完整签名配置，将生成未签名的 Release APK（包含 -unsigned.apk）")
    }

}

// 统一 Kotlin Toolchain 到 JDK 17
kotlin {
    jvmToolchain(17)
}

// 可覆盖的命令/参数（默认适配 macOS/Linux PATH）
// 解析 ADB 可执行文件（Windows/macOS 通用）：
// 优先使用项目属性，其次查找 ANDROID_SDK_ROOT/ANDROID_HOME 下的 platform-tools，再从 PATH 中查找。
fun resolveAdbExec(): String {
    val prop = (project.findProperty("adbCmd") as? String)?.trim().orEmpty()
    val exeName = if (isWindows) "adb.exe" else "adb"
    val sdkRoot = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")
    val envPath = sdkRoot?.let { File(File(it, "platform-tools"), exeName).absolutePath }
    fun whichCmd(): String {
        return try {
            if (isWindows) {
                val pb = ProcessBuilder("cmd", "/c", "where adb 2>nul")
                pb.redirectErrorStream(true)
                val p = pb.start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor()
                out.lines().firstOrNull()?.trim().orEmpty()
            } else {
                val pb = ProcessBuilder("sh", "-lc", "command -v adb || true")
                pb.redirectErrorStream(true)
                val p = pb.start()
                val out = p.inputStream.bufferedReader().readText().trim()
                p.waitFor()
                out
            }
        } catch (_: Exception) { "" }
    }
    return when {
        prop.isNotBlank() && File(prop).exists() -> prop
        envPath != null && File(envPath).exists() -> envPath
        whichCmd().isNotBlank() -> whichCmd()
        else -> "adb"
    }
}

val adbCmd = resolveAdbExec()
val emulatorCmd = (project.findProperty("emulatorCmd") as? String) ?: "emulator"

// OS 检测：明确区分 Windows/macOS/Linux，便于后续分支处理
val osName = System.getProperty("os.name").lowercase()
val isMac = osName.contains("mac")
val isWindows = osName.contains("win")

// AVD 名称：优先项目属性，其次使用通用稳定默认（避免在 macOS 下使用 Genymotion 机型名称作为 AVD）
val avdName = (project.findProperty("avdName") as? String)
    ?: "Pixel_6_API_34"

// 基础 Exec 任务，避免脚本中解析输出
// 跨平台设备启动：优先使用 Genymotion（macOS），否则启动 Android Emulator（Windows/macOS）
// 解析 Genymotion gmtool（仅 macOS 自动解析）：
fun resolveGmtoolExec(): String {
    val prop = (project.findProperty("gmtoolExe") as? String)?.trim().orEmpty()
    if (prop.isNotBlank() && File(prop).exists()) return prop
    if (isMac) {
        // 先尝试 PATH 中的 gmtool
        try {
            val pb = ProcessBuilder("sh", "-lc", "command -v gmtool || true")
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            if (out.isNotBlank() && File(out).exists()) return out
        } catch (_: Exception) { }
        // 再尝试应用内默认路径
        val defaultPath = "/Applications/Genymotion.app/Contents/MacOS/gmtool"
        if (File(defaultPath).exists()) return defaultPath
    }
    return ""
}

val gmtoolExe = resolveGmtoolExec()
val genyDeviceName = (project.findProperty("genyDeviceName") as? String)
    ?: System.getenv("GENY_DEVICE_NAME")
    ?: ""
val useGenymotionProp = (project.findProperty("useGenymotion") as? String)
    ?: if (isMac) "auto" else "false"
val gmtoolExists = gmtoolExe.isNotBlank() && File(gmtoolExe).exists()
val useGenymotion = when (useGenymotionProp.lowercase()) {
    "true" -> isMac && gmtoolExists
    "false" -> false
    "auto" -> isMac && gmtoolExists
    else -> isMac && gmtoolExists
}

tasks.register("startEmulator") {
    doLast {
        fun runCmdCapture(vararg args: String): Pair<Int, String> {
            val pb = ProcessBuilder(*args)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = try { proc.waitFor() } catch (_: InterruptedException) { -1 }
            return code to out
        }

        fun resolveEmuExec(): String {
            val emuProp = emulatorCmd
            val sdkRoot = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")
            val exeName = if (isWindows) "emulator.exe" else "emulator"
            val envPath = sdkRoot?.let { File(File(it, "emulator"), exeName).absolutePath }
            val whichPath = try {
                if (isWindows) {
                    val (_, out) = runCmdCapture("cmd", "/c", "where emulator 2>nul")
                    out.lines().firstOrNull()?.trim().orEmpty()
                } else {
                    val (_, out) = runCmdCapture("sh", "-lc", "command -v emulator || true")
                    out.trim()
                }
            } catch (_: Exception) { "" }
            return when {
                File(emuProp).exists() -> emuProp
                envPath != null && File(envPath).exists() -> envPath
                whichPath.isNotBlank() -> whichPath
                else -> emuProp
            }
        }

        fun pickGenyDeviceName(): String? {
            val (code, out) = runCmdCapture(gmtoolExe, "admin", "list")
            if (code != 0) return null
            val lines = out.lines()
            var selected: String? = null
            var currentName: String? = null
            for (line in lines) {
                val t = line.trim()
                if (t.startsWith("Name:")) {
                    currentName = t.substringAfter("Name:").trim().ifBlank { null }
                } else if (t.startsWith("Status:")) {
                    val status = t.substringAfter("Status:").trim().lowercase()
                    if (currentName != null) {
                        if (status == "stopped") { selected = currentName; break }
                        if (selected == null && status == "running") { selected = currentName }
                    }
                }
            }
            return selected ?: lines.firstOrNull { it.trim().startsWith("Name:") }?.substringAfter("Name:")?.trim()
        }

        fun startAndroidEmu(): Boolean {
            val emuExec = resolveEmuExec()
            fun listAvds(): List<String> {
                val (code, out) = runCmdCapture(emuExec, "-list-avds")
                if (code != 0) return emptyList()
                return out.lines().map { it.trim() }.filter { it.isNotBlank() }
            }
            val availableAvds = listAvds()
            val chosenAvd = if (avdName.isNotBlank() && availableAvds.contains(avdName)) avdName else (availableAvds.firstOrNull() ?: "")
            if (chosenAvd.isBlank()) {
                println("[startEmulator] No AVD available; please create one in Android Studio.")
                return false
            }
            println("[startEmulator] Fallback to Android Emulator AVD: $chosenAvd")
            val logDir = File(project.rootDir, ".emulator_logs").apply { mkdirs() }
            val logFile = File(logDir, "emulator-$chosenAvd.log")
            val pb = ProcessBuilder(
                emuExec,
                "-avd", chosenAvd,
                "-netdelay", "none",
                "-netspeed", "full",
                "-no-boot-anim",
                "-no-audio",
                "-gpu", "auto"
            )
            pb.redirectErrorStream(true)
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            return try {
                pb.start()
                println("[startEmulator] Emulator process started; log: ${logFile.absolutePath}")
                true
            } catch (e: Exception) {
                println("[startEmulator] ERROR starting emulator: ${e.message}")
                false
            }
        }

        // 检查是否已有就绪设备
        val (code, devicesOut) = runCmdCapture(adbCmd, "devices")
        if (code != 0) {
            println("[startEmulator] adb devices returned non-zero ($code), continuing...")
        }
        val lines = devicesOut.lines()
        val hasReadyDevice = lines.any { it.endsWith("\tdevice") }
        val hasStartingDevice = lines.any { it.endsWith("\toffline") || it.endsWith("\tunauthorized") }
        if (hasReadyDevice) {
            println("[startEmulator] Device already ready; skip emulator start.")
            return@doLast
        }
        if (hasStartingDevice) {
            println("[startEmulator] Device is starting; skip new emulator start.")
            return@doLast
        }

        if (useGenymotion) {
            println("[startEmulator] Using Genymotion on macOS.")
            try {
                val targetName = if (genyDeviceName.isNotBlank()) genyDeviceName else (pickGenyDeviceName() ?: "")
                if (targetName.isNotBlank()) {
                    val (gmCode, _) = runCmdCapture(gmtoolExe, "admin", "start", targetName)
                    if (gmCode != 0) {
                        println("[startEmulator] gmtool admin start failed (exit=$gmCode); opening Genymotion app as fallback.")
                        if (isMac) {
                            try {
                                val (openCode, _) = runCmdCapture("open", "-a", "Genymotion")
                                if (openCode == 0) {
                                    println("[startEmulator] Genymotion app opened; please ensure a device starts.")
                                    return@doLast
                                }
                            } catch (_: Exception) { }
                        }
                        println("[startEmulator] Fallback to Android Emulator.")
                        startAndroidEmu()
                    } else {
                        println("[startEmulator] Genymotion device start requested: '$targetName'.")
                    }
                } else {
                    println("[startEmulator] No Genymotion device found; opening Genymotion app.")
                    if (isMac) {
                        try {
                            val (openCode, _) = runCmdCapture("open", "-a", "Genymotion")
                            if (openCode == 0) {
                                println("[startEmulator] Genymotion app opened; waiting for device via ADB.")
                                return@doLast
                            }
                        } catch (_: Exception) { }
                    }
                    println("[startEmulator] Genymotion app open failed; falling back to Android Emulator.")
                    startAndroidEmu()
                }
            } catch (e: Exception) {
                println("[startEmulator] WARNING: ${e.message}; trying to open Genymotion app.")
                if (isMac) {
                    try {
                        val (openCode, _) = runCmdCapture("open", "-a", "Genymotion")
                        if (openCode == 0) {
                            println("[startEmulator] Genymotion app opened; waiting for device via ADB.")
                            return@doLast
                        }
                    } catch (_: Exception) { }
                }
                println("[startEmulator] Fallback to Android Emulator.")
                startAndroidEmu()
            }
            return@doLast
        }

        // 非 Genymotion 分支：统一使用增强解析的 Android Emulator 启动
        val ok = startAndroidEmu()
        if (!ok) {
            throw GradleException("Emulator start failed; ensure Android SDK emulator installed or set -PemulatorCmd=<path>.")
        }
    }
}

tasks.register("waitForDevice") {
    dependsOn("startEmulator")
    doLast {
        fun runCmdCapture(vararg args: String): Pair<Int, String> {
            val pb = ProcessBuilder(*args)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = try { proc.waitFor() } catch (_: InterruptedException) { -1 }
            return code to out
        }

        fun resolveEmuExec(): String {
            val emuProp = emulatorCmd
            val sdkRoot = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")
            val exeName = if (isWindows) "emulator.exe" else "emulator"
            val envPath = sdkRoot?.let { File(File(it, "emulator"), exeName).absolutePath }
            val whichPath = try {
                if (isWindows) {
                    val (_, out) = runCmdCapture("cmd", "/c", "where emulator 2>nul")
                    out.lines().firstOrNull()?.trim().orEmpty()
                } else {
                    val (_, out) = runCmdCapture("sh", "-lc", "command -v emulator || true")
                    out.trim()
                }
            } catch (_: Exception) { "" }
            return when {
                File(emuProp).exists() -> emuProp
                envPath != null && File(envPath).exists() -> envPath
                whichPath.isNotBlank() -> whichPath
                else -> emuProp
            }
        }

        fun startAndroidEmuFallback(): Boolean {
            val emuExec = resolveEmuExec()
            fun listAvds(): List<String> {
                val (code, out) = runCmdCapture(emuExec, "-list-avds")
                if (code != 0) return emptyList()
                return out.lines().map { it.trim() }.filter { it.isNotBlank() }
            }
            val availableAvds = listAvds()
            val chosenAvd = if (avdName.isNotBlank() && availableAvds.contains(avdName)) avdName else (availableAvds.firstOrNull() ?: "")
            if (chosenAvd.isBlank()) {
                println("[waitForDevice] No AVD available; please create one in Android Studio.")
                return false
            }
            val logDir = File(project.rootDir, ".emulator_logs").apply { mkdirs() }
            val logFile = File(logDir, "emulator-$chosenAvd.log")
            val pb = ProcessBuilder(
                emuExec,
                "-avd", chosenAvd,
                "-netdelay", "none",
                "-netspeed", "full",
                "-no-boot-anim",
                "-no-audio",
                "-gpu", "auto"
            )
            pb.redirectErrorStream(true)
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            return try {
                pb.start()
                println("[waitForDevice] Fallback emulator launched; log: ${logFile.absolutePath}")
                true
            } catch (e: Exception) {
                println("[waitForDevice] ERROR starting fallback emulator: ${e.message}")
                false
            }
        }

        runCmdCapture(adbCmd, "start-server")
        val timeoutSec = (project.findProperty("deviceWaitTimeoutSec") as? String)?.toIntOrNull() ?: 240
        var elapsed = 0
        var attemptedFallback = false
        while (true) {
            val (_, devicesText) = runCmdCapture(adbCmd, "devices")
            val connected = devicesText.lines().any { it.endsWith("\tdevice") || it.endsWith("\toffline") || it.endsWith("\tunauthorized") }
            if (connected) {
                val (_, bootText) = runCmdCapture(adbCmd, "shell", "getprop", "sys.boot_completed")
                if (bootText.trim() == "1") {
                    println("Emulator boot completed and device is ready.")
                    break
                }
            }
            if (!attemptedFallback && useGenymotion && elapsed >= 60) {
                println("[waitForDevice] Genymotion device not ready; attempting fallback to Android Emulator...")
                attemptedFallback = startAndroidEmuFallback()
            }
            Thread.sleep(1000)
            elapsed++
            if (elapsed >= timeoutSec) throw GradleException("Emulator did not boot within ${timeoutSec}s")
        }
        runCmdCapture(adbCmd, "devices")
    }
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
tasks.register("installPlaystoreDebugAndLogcat") {
    dependsOn("waitForDevice", ":app:installPlaystoreDebug")
    doLast {
        fun runCmd(vararg args: String): Int {
            val pb = ProcessBuilder(*args)
            pb.redirectErrorStream(true)
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
            val p = pb.start()
            return try { p.waitFor() } catch (_: InterruptedException) { -1 }
        }

        fun runCmdCapture(vararg args: String): Pair<Int, String> {
            val pb = ProcessBuilder(*args)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = try { proc.waitFor() } catch (_: InterruptedException) { -1 }
            return code to out
        }

        runCmd(adbCmd, "logcat", "-c")
        runCmd(adbCmd, "shell", "am", "start", "-n", "com.v2ray.ang/.ui.MainActivity")
        var pid = runCmdCapture(adbCmd, "shell", "pidof", "com.v2ray.ang").second.trim()
        if (pid.isBlank()) {
            val psOut = runCmdCapture(adbCmd, "shell", "ps", "-A").second
            val first = psOut.split(Regex("\\r?\\n")).firstOrNull { it.contains("com.v2ray.ang") }?.trim()
            pid = first?.split(Regex("\\s+"))?.getOrNull(1) ?: ""
        }
        if (pid.isBlank()) throw GradleException("PID not found for com.v2ray.ang")
        val pb = ProcessBuilder(adbCmd, "logcat", "--pid", pid, "-v", "time", "v2rayNG:D", "AndroidRuntime:E", "*:W")
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
        pb.start().waitFor()
    }
}

tasks.register("installFdroidDebugAndLogcat") {
    dependsOn("waitForDevice", ":app:installFdroidDebug")
    doLast {
        fun runCmd(vararg args: String): Int {
            val pb = ProcessBuilder(*args)
            pb.redirectErrorStream(true)
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
            val p = pb.start()
            return try { p.waitFor() } catch (_: InterruptedException) { -1 }
        }

        fun runCmdCapture(vararg args: String): Pair<Int, String> {
            val pb = ProcessBuilder(*args)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = try { proc.waitFor() } catch (_: InterruptedException) { -1 }
            return code to out
        }

        runCmd(adbCmd, "logcat", "-c")
        runCmd(adbCmd, "shell", "am", "start", "-n", "com.v2ray.ang.fdroid/.ui.MainActivity")
        var pid = runCmdCapture(adbCmd, "shell", "pidof", "com.v2ray.ang.fdroid").second.trim()
        if (pid.isBlank()) {
            val psOut = runCmdCapture(adbCmd, "shell", "ps", "-A").second
            val first = psOut.split(Regex("\\r?\\n")).firstOrNull { it.contains("com.v2ray.ang.fdroid") }?.trim()
            pid = first?.split(Regex("\\s+"))?.getOrNull(1) ?: ""
        }
        if (pid.isBlank()) throw GradleException("PID not found for com.v2ray.ang.fdroid")
        val pb = ProcessBuilder(adbCmd, "logcat", "--pid", pid, "-v", "time", "v2rayNG:D", "AndroidRuntime:E", "*:W")
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
        pb.start().waitFor()
    }
}

// 通用安装并跟踪日志任务：通过 -Pdistribution 或环境变量选择 playstore/fdroid（默认 playstore）
tasks.register("installDebugAndLogcat") {
    val distro = ((project.findProperty("distribution") as? String)
        ?: System.getenv("V2RAYNG_DISTRIBUTION")
        ?: "playstore").lowercase()
    if (distro == "fdroid") {
        dependsOn("installFdroidDebugAndLogcat")
    } else {
        dependsOn("installPlaystoreDebugAndLogcat")
    }
}

// ------- 组装并验证 Release APK 的 Gradle 任务（可在本地和 CI 复用） -------
fun resolveApksignerExec(): String {
    val exeName = if (isWindows) "apksigner.bat" else "apksigner"
    val sdkRoot = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")
    val envCandidate = sdkRoot?.let {
        val bt = File(it, "build-tools")
        val latest = bt.listFiles()?.filter { f -> f.isDirectory }?.sortedByDescending { f -> f.name }?.firstOrNull()
        latest?.let { File(it, exeName).absolutePath }
    }
    fun whichPath(): String {
        return try {
            if (isWindows) {
                val pb = ProcessBuilder("cmd", "/c", "where apksigner 2>nul")
                pb.redirectErrorStream(true)
                val p = pb.start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor()
                out.lines().firstOrNull()?.trim().orEmpty()
            } else {
                val pb = ProcessBuilder("sh", "-lc", "command -v apksigner || true")
                pb.redirectErrorStream(true)
                val p = pb.start()
                val out = p.inputStream.bufferedReader().readText().trim()
                p.waitFor()
                out
            }
        } catch (_: Exception) { "" }
    }
    return when {
        envCandidate != null && File(envCandidate).exists() -> envCandidate
        whichPath().isNotBlank() -> whichPath()
        else -> exeName // 退回到 PATH 中（若无则在运行时提示）
    }
}

fun listBuiltApks(releaseDir: File): List<File> {
    val meta = File(releaseDir, "output-metadata.json")
    if (meta.exists()) {
        val content = meta.readText()
        val regex = Regex("\"outputFile\"\\s*:\\s*\"([^\"]+)\"")
        val files = regex.findAll(content).map { File(releaseDir, it.groupValues[1]) }.toList()
        if (files.isNotEmpty()) return files.filter { it.exists() }
    }
    // 兜底：直接枚举 .apk 文件
    return releaseDir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }?.toList() ?: emptyList()
}

fun verifyApksForFlavor(projectDir: File, flavor: String) {
    val releaseDir = File(projectDir, "build/outputs/apk/$flavor/release")
    if (!releaseDir.exists()) throw GradleException("未找到 ${flavor} Release 输出目录：${releaseDir.absolutePath}")
    val apksigner = resolveApksignerExec()
    if (!File(apksigner).exists() && apksigner == (if (isWindows) "apksigner.bat" else "apksigner")) {
        println("[verify] 未在 ANDROID_HOME/ANDROID_SDK_ROOT 找到 apksigner，尝试使用 PATH 中的 $apksigner")
    }
    val apks = listBuiltApks(releaseDir).filterNot { it.name.contains("-unsigned.apk") }
    if (apks.isEmpty()) throw GradleException("未在 ${releaseDir.absolutePath} 找到可验证的 APK（排除 -unsigned.apk）")
    var ok = 0
    var fail = 0
    apks.forEach { apk ->
        println("\n[verify] 使用 apksigner 验证：${apk.name}")
        println("[verify] 目录：${releaseDir.absolutePath}")
        val cmd = if (isWindows) listOf("cmd", "/c", apksigner, "verify", "--verbose", "--print-certs", apk.absolutePath)
                  else listOf(apksigner, "verify", "--verbose", "--print-certs", apk.absolutePath)
        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = try { proc.waitFor() } catch (_: InterruptedException) { -1 }
        println(out)
        if (code == 0) {
            println("[verify] 签名验证结果：通过")
            ok++
        } else {
            println("[verify] 签名验证结果：失败（退出码 $code）")
            fail++
        }
    }
    println("\n[verify] ${flavor} Release 验证汇总：总计 ${apks.size}，通过 ${ok}，失败 ${fail}")
    if (fail > 0) throw GradleException("签名验证失败：共有 ${fail} 个 APK 未通过验证")
}

tasks.register("verifyFdroidReleaseApks") {
    dependsOn("assembleFdroidRelease")
    doLast { verifyApksForFlavor(project.projectDir, "fdroid") }
}

tasks.register("verifyPlaystoreReleaseApks") {
    dependsOn("assemblePlaystoreRelease")
    doLast { verifyApksForFlavor(project.projectDir, "playstore") }
}

tasks.register("assembleFdroidReleaseAndVerify") {
    dependsOn("verifyFdroidReleaseApks")
}

tasks.register("assemblePlaystoreReleaseAndVerify") {
    dependsOn("verifyPlaystoreReleaseApks")
}

tasks.register("assembleAllReleasesAndVerify") {
    dependsOn("assembleFdroidReleaseAndVerify", "assemblePlaystoreReleaseAndVerify")
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
