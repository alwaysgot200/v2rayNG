import java.io.File
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
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
                    output.outputFileName = "v2plus_${variant.versionName}-fdroid_${abi}.apk"
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

                    output.outputFileName = "v2plus_${variant.versionName}_${abi}.apk"
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
    // 允许通过项目属性或环境变量覆盖 adb 路径
    val prop = ((project.findProperty("adbCmd") as? String)?.trim()
        ?: System.getenv("ADB_CMD")?.trim()).orEmpty()
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
        // 允许通过属性跳过启动（若设备已在线或用户不希望自动启动）
        val skipStart = ((project.findProperty("skipEmulatorStart") as? String)?.lowercase() == "true")
        if (skipStart) {
            println("[startEmulator] skipEmulatorStart=true; skip starting emulator.")
            return@doLast
        }
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
            // 兼容两种输出格式：
            // 1) 逐行 Name:/Status:
            // 2) 表格：Name | ADB Serial | UUID | On/Off
            var selected: String? = null
            var currentName: String? = null
            for (line in lines) {
                val t = line.trim()
                if (t.contains("|") && !t.startsWith("Name:")) {
                    val parts = t.split("|").map { it.trim() }
                    if (parts.size >= 4) {
                        val name = parts[0]
                        val status = parts[3].lowercase()
                        if (status == "off" || status == "stopped") { return name }
                        if (selected == null && (status == "on" || status == "running")) selected = name
                    }
                } else if (t.startsWith("Name:")) {
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

        fun isGenyDeviceRunning(name: String): Boolean {
            val (code, out) = runCmdCapture(gmtoolExe, "admin", "list")
            if (code != 0) return false
            val lines = out.lines()
            for (line in lines) {
                val t = line.trim()
                if (t.contains("|")) {
                    val parts = t.split("|").map { it.trim() }
                    if (parts.size >= 4 && parts[0] == name) {
                        val status = parts[3].lowercase()
                        return status == "on" || status == "running"
                    }
                } else if (t.startsWith("Name:") && t.contains(name)) {
                    // 回看后一行 Status
                    // 简化处理：若下一行包含 Status: running 则视为运行中
                    val idx = lines.indexOf(line)
                    val next = lines.getOrNull(idx + 1)?.trim()?.lowercase() ?: ""
                    if (next.startsWith("status:") && next.contains("running")) return true
                }
            }
            return false
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
                    if (isGenyDeviceRunning(targetName)) {
                        println("[startEmulator] Genymotion device already running: '$targetName'.")
                        return@doLast
                    }
                    try {
                        val pb = ProcessBuilder(gmtoolExe, "admin", "start", targetName)
                        pb.redirectErrorStream(true)
                        pb.start() // 非阻塞启动，避免长时间卡住在 startEmulator
                        println("[startEmulator] Genymotion device start requested: '$targetName'.")
                    } catch (_: Exception) {
                        println("[startEmulator] gmtool admin start failed; opening Genymotion app as fallback.")
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
        // 若启用了 Genymotion，尝试主动与正在运行的设备建立 ADB 连接（根据 gmtool 输出的 Serial）
        fun tryConnectGenymotion(): Boolean {
            if (!useGenymotion || !gmtoolExists) return false
            fun runCmdCapture(vararg args: String): Pair<Int, String> {
                val pb = ProcessBuilder(*args)
                pb.redirectErrorStream(true)
                val proc = pb.start()
                val out = proc.inputStream.bufferedReader().readText()
                val code = try { proc.waitFor() } catch (_: InterruptedException) { -1 }
                return code to out
            }
            val (code, out) = runCmdCapture(gmtoolExe, "admin", "list")
            if (code != 0) return false
            // 解析第一条处于 On 状态的设备的 ADB Serial（形如 192.168.56.x:5555）并尝试连接
            val serial = out.lines()
                .firstOrNull { it.contains("|") && it.contains(" On ") }
                ?.split("|")
                ?.map { it.trim() }
                ?.getOrNull(1) // ADB Serial 列
                ?.takeIf { it.isNotBlank() }
                ?: return false
            val (connCode, _) = runCmdCapture(adbCmd, "connect", serial)
            return connCode == 0
        }
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
        // 尝试先行建立与 Genymotion 设备的连接，避免后续一直无设备可用
        tryConnectGenymotion()
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

        fun resolveDeviceSerial(): String {
            val (_, listOut) = runCmdCapture(adbCmd, "devices")
            val first = listOut.lines().map { it.trim() }
                .firstOrNull { it.endsWith("\tdevice") }?.split("\t")?.firstOrNull().orEmpty()
            if (useGenymotion && gmtoolExists) {
                val (gcode, gout) = runCmdCapture(gmtoolExe, "admin", "list")
                if (gcode == 0) {
                    val s = gout.lines()
                        .firstOrNull { it.contains("|") && it.contains(" On ") }
                        ?.split("|")?.map { it.trim() }?.getOrNull(1).orEmpty()
                    if (s.isNotBlank()) return s
                }
            }
            return first
        }

        val deviceSerial = resolveDeviceSerial()
        println("[setupJdwpForwardPlaystore] deviceSerial=${deviceSerial.ifBlank { "<auto>" }}")

        fun runAdb(vararg sub: String): Int {
            val args = mutableListOf(adbCmd)
            if (deviceSerial.isNotBlank()) { args.add("-s"); args.add(deviceSerial) }
            args.addAll(sub)
            return runCmd(*args.toTypedArray())
        }

        fun runAdbCapture(vararg sub: String): Pair<Int, String> {
            val args = mutableListOf(adbCmd)
            if (deviceSerial.isNotBlank()) { args.add("-s"); args.add(deviceSerial) }
            args.addAll(sub)
            return runCmdCapture(*args.toTypedArray())
        }

        runAdb("logcat", "-c")
        // 使用完整类名确保组件名解析正确，并以调试模式启动以便 JDWP 就绪
        runAdb("shell", "am", "start", "-D", "-n", "com.v2ray.ang/com.v2ray.ang.ui.MainActivity")
        // 轮询等待 PID，避免刚启动时未就绪
        fun waitForPid(pkg: String, timeoutMs: Long = 10000, intervalMs: Long = 500): String {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                var p = runAdbCapture("shell", "pidof", pkg).second.trim()
                if (p.isNotBlank()) return p
                val psOut = runAdbCapture("shell", "ps", "-A").second
                val first = psOut.split(Regex("\\r?\\n")).firstOrNull { it.contains(pkg) }?.trim()
                p = first?.split(Regex("\\s+"))?.getOrNull(1) ?: ""
                if (p.isNotBlank()) return p
            try { Thread.sleep(intervalMs.toLong()) } catch (_: InterruptedException) {}
            }
            return ""
        }
        var pid = waitForPid("com.v2ray.ang")
        // 如果主进程未在 JDWP 列表中，尝试切换到守护进程（:RunSoLibV2RayDaemon）
        fun findPidByPs(pattern: String): String {
            val psOut = runAdbCapture("shell", "ps", "-A").second
            val line = psOut.split(Regex("\\r?\\n")).firstOrNull { it.contains(pattern) }?.trim()
            // line may be null; ensure split result is safely accessed
            return line?.split(Regex("\\s+"))?.getOrNull(1) ?: ""
        }
        // 轮询 JDWP 可用的 PID（优先主进程，其次守护进程），最多 10s
        fun resolveJdwpPid(preferredName: String, daemonName: String): String {
            val timeoutMs = 10_000
            val intervalMs = 500
            var elapsed = 0
            while (elapsed < timeoutMs) {
                val jdwpSet = runAdbCapture("jdwp").second
                    .lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                val psOut = runAdbCapture("shell", "ps", "-A").second
                val preferredLine = psOut.split(Regex("\\r?\\n")).firstOrNull { it.contains(preferredName) }?.trim()
                val daemonLine = psOut.split(Regex("\\r?\\n")).firstOrNull { it.contains(daemonName) }?.trim()
                val prefPid = preferredLine?.split(Regex("\\s+"))?.getOrNull(1)?.trim()
                val daemonPid = daemonLine?.split(Regex("\\s+"))?.getOrNull(1)?.trim()
                if (prefPid != null && jdwpSet.contains(prefPid)) return prefPid
                if (daemonPid != null && jdwpSet.contains(daemonPid)) return daemonPid
                try { Thread.sleep(intervalMs.toLong()) } catch (_: InterruptedException) {}
                elapsed += intervalMs
            }
            return ""
        }
        val resolvedPlayPid = resolveJdwpPid("com.v2ray.ang", "com.v2ray.ang:RunSoLibV2RayDaemon")
        if (resolvedPlayPid.isNotBlank()) {
            if (resolvedPlayPid != pid) {
                println("[JDWP] 使用 JDWP 就绪进程 PID ${resolvedPlayPid} 替换主进程 PID ${pid}")
            }
            pid = resolvedPlayPid
        }
        if (pid.isBlank()) {
            runAdb("shell", "am", "force-stop", "com.v2ray.ang")
            runAdb("shell", "am", "start", "-D", "-n", "com.v2ray.ang/com.v2ray.ang.ui.MainActivity")
            pid = waitForPid("com.v2ray.ang")
        }
        if (pid.isBlank()) throw GradleException("PID not found for com.v2ray.ang")
        // 映射 JDWP 到本机端口（默认 5005，可由 -PjdwpPort 或环境变量 V2PLUS_JDWP_PORT 覆盖）
        val jdwpPort = ((project.findProperty("jdwpPort") as? String)?.toIntOrNull()
            ?: System.getenv("V2PLUS_JDWP_PORT")?.toIntOrNull()
            ?: 5005)
        // 清理可能存在的旧映射（忽略失败）
        runAdb("forward", "--remove", "tcp:${jdwpPort}")
        val (_, jdwpOutPlay) = runAdbCapture("jdwp")
        val listedPlay = jdwpOutPlay.lines().any { it.trim() == pid }
        if (!listedPlay) {
            println("[JDWP] 警告：PID ${pid} 暂未出现在 'adb jdwp' 列表，稍后仍尝试映射。")
        }
        val (fwdCodePlay, fwdMsgPlay) = runAdbCapture("forward", "tcp:${jdwpPort}", "jdwp:${pid}")
        if (fwdCodePlay != 0) {
            throw GradleException("JDWP 端口映射失败：tcp:${jdwpPort} -> pid ${pid} (exit=${fwdCodePlay})\n${fwdMsgPlay}")
        }
        val (_, fwdList) = runAdbCapture("forward", "--list")
        val okForward = fwdList.lines().any { it.contains(":${jdwpPort} ") && (deviceSerial.isBlank() || it.startsWith(deviceSerial)) }
        if (!okForward) {
            val (fc2, fm2) = runAdbCapture("forward", "tcp:${jdwpPort}", "jdwp:${pid}")
            if (fc2 != 0) throw GradleException("JDWP 端口映射失败：tcp:${jdwpPort} -> pid ${pid} (exit=${fc2})\n${fm2}")
        }
        println("[JDWP] 已就绪：localhost:${jdwpPort} -> PID ${pid} (com.v2ray.ang)")
        println("[JDWP] VS Code 附加：host=localhost, port=${jdwpPort}")
        val pbArgs = if (deviceSerial.isNotBlank()) arrayOf(adbCmd, "-s", deviceSerial, "logcat", "--pid", pid, "-v", "time", "v2plus:D", "AndroidRuntime:E", "*:W") else arrayOf(adbCmd, "logcat", "--pid", pid, "-v", "time", "v2plus:D", "AndroidRuntime:E", "*:W")
        val pb = ProcessBuilder(*pbArgs)
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

        fun resolveDeviceSerial(): String {
            val (_, listOut) = runCmdCapture(adbCmd, "devices")
            val first = listOut.lines().map { it.trim() }
                .firstOrNull { it.endsWith("\tdevice") }?.split("\t")?.firstOrNull().orEmpty()
            if (useGenymotion && gmtoolExists) {
                val (gcode, gout) = runCmdCapture(gmtoolExe, "admin", "list")
                if (gcode == 0) {
                    val s = gout.lines()
                        .firstOrNull { it.contains("|") && it.contains(" On ") }
                        ?.split("|")?.map { it.trim() }?.getOrNull(1).orEmpty()
                    if (s.isNotBlank()) return s
                }
            }
            return first
        }

        val deviceSerial = resolveDeviceSerial()

        fun runAdb(vararg sub: String): Int {
            val args = mutableListOf(adbCmd)
            if (deviceSerial.isNotBlank()) { args.add("-s"); args.add(deviceSerial) }
            args.addAll(sub)
            return runCmd(*args.toTypedArray())
        }

        fun runAdbCapture(vararg sub: String): Pair<Int, String> {
            val args = mutableListOf(adbCmd)
            if (deviceSerial.isNotBlank()) { args.add("-s"); args.add(deviceSerial) }
            args.addAll(sub)
            return runCmdCapture(*args.toTypedArray())
        }

        runAdb("logcat", "-c")
        // fdroid 变体下使用完整类名（Activity 类仍在 com.v2ray.ang.ui），正常启动（不启用 -D 等待调试器）
        runAdb("shell", "am", "start", "-n", "com.v2ray.ang.fdroid/com.v2ray.ang.ui.MainActivity")
        // 轮询等待 PID，避免刚启动时未就绪
        fun waitForPidFd(pkg: String, timeoutMs: Long = 10000, intervalMs: Long = 500): String {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                var p = runAdbCapture("shell", "pidof", pkg).second.trim()
                if (p.isNotBlank()) return p
                val psOut = runAdbCapture("shell", "ps", "-A").second
                val first = psOut.split(Regex("\\r?\\n")).firstOrNull { it.contains(pkg) }?.trim()
                p = first?.split(Regex("\\s+"))?.getOrNull(1) ?: ""
                if (p.isNotBlank()) return p
                try { Thread.sleep(intervalMs.toLong()) } catch (_: InterruptedException) {}
            }
            return ""
        }
        var pid = waitForPidFd("com.v2ray.ang.fdroid")
        // 如果主进程未在 JDWP 列表中，尝试切换到守护进程（:RunSoLibV2RayDaemon）
        fun findPidByPsFd(pattern: String): String {
            val psOut = runAdbCapture("shell", "ps", "-A").second
            val line = psOut.split(Regex("\\r?\\n")).firstOrNull { it.contains(pattern) }?.trim()
            // line may be null; ensure split result is safely accessed
            return line?.split(Regex("\\s+"))?.getOrNull(1) ?: ""
        }
        // 轮询 JDWP 可用的 PID（优先主进程，其次守护进程），最多 10s
        fun resolveJdwpPidFd(preferredName: String, daemonName: String): String {
            val timeoutMs = 10_000
            val intervalMs = 500
            var elapsed = 0
            while (elapsed < timeoutMs) {
                val jdwpSet = runAdbCapture("jdwp").second
                    .lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                val psOut = runAdbCapture("shell", "ps", "-A").second
                val preferredLine = psOut.split(Regex("\\r?\\n")).firstOrNull { it.contains(preferredName) }?.trim()
                val daemonLine = psOut.split(Regex("\\r?\\n")).firstOrNull { it.contains(daemonName) }?.trim()
                val prefPid = preferredLine?.split(Regex("\\s+"))?.getOrNull(1)?.trim()
                val daemonPid = daemonLine?.split(Regex("\\s+"))?.getOrNull(1)?.trim()
                if (prefPid != null && jdwpSet.contains(prefPid)) return prefPid
                if (daemonPid != null && jdwpSet.contains(daemonPid)) return daemonPid
                try { Thread.sleep(intervalMs.toLong()) } catch (_: InterruptedException) {}
                elapsed += intervalMs
            }
            return ""
        }
        val resolvedFdPid = resolveJdwpPidFd("com.v2ray.ang.fdroid", "com.v2ray.ang.fdroid:RunSoLibV2RayDaemon")
        if (resolvedFdPid.isNotBlank()) {
            if (resolvedFdPid != pid) {
                println("[JDWP] 使用 JDWP 就绪进程 PID ${resolvedFdPid} 替换主进程 PID ${pid}")
            }
            pid = resolvedFdPid
        }
        if (pid.isBlank()) {
            runAdb("shell", "am", "force-stop", "com.v2ray.ang.fdroid")
            runAdb("shell", "am", "start", "-n", "com.v2ray.ang.fdroid/com.v2ray.ang.ui.MainActivity")
            pid = waitForPidFd("com.v2ray.ang.fdroid")
        }
        if (pid.isBlank()) throw GradleException("PID not found for com.v2ray.ang.fdroid")
        // 映射 JDWP 到本机端口（默认 5005，可由 -PjdwpPort 或环境变量 V2PLUS_JDWP_PORT 覆盖）
        val jdwpPortFd = ((project.findProperty("jdwpPort") as? String)?.toIntOrNull()
            ?: System.getenv("V2PLUS_JDWP_PORT")?.toIntOrNull()
            ?: 5005)
        // 清理可能存在的旧映射（忽略失败）
        runAdb("forward", "--remove", "tcp:${jdwpPortFd}")
        val (_, jdwpOutFd) = runAdbCapture("jdwp")
        val listedFd = jdwpOutFd.lines().any { it.trim() == pid }
        if (!listedFd) {
            println("[JDWP] 警告：PID ${pid} 暂未出现在 'adb jdwp' 列表，稍后仍尝试映射。")
        }
        val (fwdCodeFd, fwdMsgFd) = runAdbCapture("forward", "tcp:${jdwpPortFd}", "jdwp:${pid}")
        if (fwdCodeFd != 0) {
            throw GradleException("JDWP 端口映射失败：tcp:${jdwpPortFd} -> pid ${pid} (exit=${fwdCodeFd})\n${fwdMsgFd}")
        }
        val (_, fwdListFd) = runAdbCapture("forward", "--list")
        val okForwardFd = fwdListFd.lines().any { it.contains(":${jdwpPortFd} ") && (deviceSerial.isBlank() || it.startsWith(deviceSerial)) }
        if (!okForwardFd) {
            val (fc2, fm2) = runAdbCapture("forward", "tcp:${jdwpPortFd}", "jdwp:${pid}")
            if (fc2 != 0) throw GradleException("JDWP 端口映射失败：tcp:${jdwpPortFd} -> pid ${pid} (exit=${fc2})\n${fm2}")
        }
        println("[JDWP] 已就绪：localhost:${jdwpPortFd} -> PID ${pid} (com.v2ray.ang.fdroid)")
        println("[JDWP] VS Code 附加：host=localhost, port=${jdwpPortFd}")
        val pbArgs = if (deviceSerial.isNotBlank()) arrayOf(adbCmd, "-s", deviceSerial, "logcat", "--pid", pid, "-v", "time", "v2plus:D", "AndroidRuntime:E", "*:W") else arrayOf(adbCmd, "logcat", "--pid", pid, "-v", "time", "v2plus:D", "AndroidRuntime:E", "*:W")
        val pb = ProcessBuilder(*pbArgs)
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
        pb.start().waitFor()
    }
}

// 后台写入 F-Droid 变体的 logcat 到文件（不阻塞任务），供 VS Code 一键调试使用
tasks.register("startLogcatFdroidToFile") {
    dependsOn("waitForDevice")
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

        fun resolveDeviceSerial(): String {
            val (_, listOut) = runCmdCapture(adbCmd, "devices")
            val first = listOut.lines().map { it.trim() }
                .firstOrNull { it.endsWith("\tdevice") }?.split("\t")?.firstOrNull().orEmpty()
            if (useGenymotion && gmtoolExists) {
                val (gcode, gout) = runCmdCapture(gmtoolExe, "admin", "list")
                if (gcode == 0) {
                    val s = gout.lines()
                        .firstOrNull { it.contains("|") && it.contains(" On ") }
                        ?.split("|")?.map { it.trim() }?.getOrNull(1).orEmpty()
                    if (s.isNotBlank()) return s
                }
            }
            return first
        }

        val deviceSerial = resolveDeviceSerial()

        fun runAdb(vararg sub: String): Int {
            val args = mutableListOf(adbCmd)
            if (deviceSerial.isNotBlank()) { args.add("-s"); args.add(deviceSerial) }
            args.addAll(sub)
            return runCmd(*args.toTypedArray())
        }

        fun runAdbCapture(vararg sub: String): Pair<Int, String> {
            val args = mutableListOf(adbCmd)
            if (deviceSerial.isNotBlank()) { args.add("-s"); args.add(deviceSerial) }
            args.addAll(sub)
            return runCmdCapture(*args.toTypedArray())
        }

        runAdb("logcat", "-c")
        fun waitForPidFd(pkg: String, timeoutMs: Long = 10000, intervalMs: Long = 500): String {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                var p = runAdbCapture("shell", "pidof", pkg).second.trim()
                if (p.isNotBlank()) return p
                val psOut = runAdbCapture("shell", "ps", "-A").second
                val first = psOut.split(Regex("\\r?\\n")).firstOrNull { it.contains(pkg) }?.trim()
                p = first?.split(Regex("\\s+"))?.getOrNull(1) ?: ""
                if (p.isNotBlank()) return p
                try { Thread.sleep(intervalMs.toLong()) } catch (_: InterruptedException) {}
            }
            return ""
        }

        var pid = waitForPidFd("com.v2ray.ang.fdroid")
        if (pid.isBlank()) {
            runAdb("shell", "am", "force-stop", "com.v2ray.ang.fdroid")
            runAdb("shell", "am", "start", "-n", "com.v2ray.ang.fdroid/com.v2ray.ang.ui.MainActivity")
            pid = waitForPidFd("com.v2ray.ang.fdroid")
        }
        if (pid.isBlank()) throw GradleException("PID not found for com.v2ray.ang.fdroid")

        val logsDir = File(project.rootProject.projectDir, "logs")
        if (!logsDir.exists()) logsDir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
        val logfile = File(logsDir, "v2plus-fdroid-${ts}.log")

        val args = mutableListOf(adbCmd)
        if (deviceSerial.isNotBlank()) { args.add("-s"); args.add(deviceSerial) }
        args.addAll(listOf("logcat", "--pid", pid, "-v", "time", "v2plus:D", "AndroidRuntime:E", "*:W"))
        val pb = ProcessBuilder(*args.toTypedArray())
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logfile))
        pb.start()
        println("[logcat] app=com.v2ray.ang.fdroid pid=${pid} file=${logfile.absolutePath}")
    }
}

tasks.register("startLogcatPlaystoreToFile") {
    dependsOn("waitForDevice")
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

        fun resolveDeviceSerial(): String {
            val (_, listOut) = runCmdCapture(adbCmd, "devices")
            val first = listOut.lines().map { it.trim() }
                .firstOrNull { it.endsWith("\tdevice") }?.split("\t")?.firstOrNull().orEmpty()
            if (useGenymotion && gmtoolExists) {
                val (gcode, gout) = runCmdCapture(gmtoolExe, "admin", "list")
                if (gcode == 0) {
                    val s = gout.lines()
                        .firstOrNull { it.contains("|") && it.contains(" On ") }
                        ?.split("|")?.map { it.trim() }?.getOrNull(1).orEmpty()
                    if (s.isNotBlank()) return s
                }
            }
            return first
        }

        val deviceSerial = resolveDeviceSerial()

        fun runAdb(vararg sub: String): Int {
            val args = mutableListOf(adbCmd)
            if (deviceSerial.isNotBlank()) { args.add("-s"); args.add(deviceSerial) }
            args.addAll(sub)
            return runCmd(*args.toTypedArray())
        }

        fun runAdbCapture(vararg sub: String): Pair<Int, String> {
            val args = mutableListOf(adbCmd)
            if (deviceSerial.isNotBlank()) { args.add("-s"); args.add(deviceSerial) }
            args.addAll(sub)
            return runCmdCapture(*args.toTypedArray())
        }

        runAdb("logcat", "-c")
        fun waitForPid(pkg: String, timeoutMs: Long = 10000, intervalMs: Long = 500): String {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                var p = runAdbCapture("shell", "pidof", pkg).second.trim()
                if (p.isNotBlank()) return p
                val psOut = runAdbCapture("shell", "ps", "-A").second
                val first = psOut.split(Regex("\\r?\\n")).firstOrNull { it.contains(pkg) }?.trim()
                p = first?.split(Regex("\\s+"))?.getOrNull(1) ?: ""
                if (p.isNotBlank()) return p
                try { Thread.sleep(intervalMs.toLong()) } catch (_: InterruptedException) {}
            }
            return ""
        }

        var pid = waitForPid("com.v2ray.ang")
        if (pid.isBlank()) {
            runAdb("shell", "am", "force-stop", "com.v2ray.ang")
            runAdb("shell", "am", "start", "-n", "com.v2ray.ang/com.v2ray.ang.ui.MainActivity")
            pid = waitForPid("com.v2ray.ang")
        }
        if (pid.isBlank()) throw GradleException("PID not found for com.v2ray.ang")

        val logsDir = File(project.rootProject.projectDir, "logs")
        if (!logsDir.exists()) logsDir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
        val logfile = File(logsDir, "v2plus-playstore-${ts}.log")

        val args = mutableListOf(adbCmd)
        if (deviceSerial.isNotBlank()) { args.add("-s"); args.add(deviceSerial) }
        args.addAll(listOf("logcat", "--pid", pid, "-v", "time", "v2plus:D", "AndroidRuntime:E", "*:W"))
        val pb = ProcessBuilder(*args.toTypedArray())
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logfile))
        pb.start()
        println("[logcat] app=com.v2ray.ang pid=${pid} file=${logfile.absolutePath}")
    }
}

tasks.register("stopApp") {
    dependsOn("waitForDevice")
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
        val distro = ((project.findProperty("distribution") as? String)
            ?: System.getenv("V2PLUS_DISTRIBUTION")
            ?: "playstore").lowercase()
        val appId = if (distro == "fdroid") "com.v2ray.ang.fdroid" else "com.v2ray.ang"
        fun resolveDeviceSerial(): String {
            val (_, listOut) = runCmdCapture(adbCmd, "devices")
            val first = listOut.lines().map { it.trim() }
                .firstOrNull { it.endsWith("\tdevice") }?.split("\t")?.firstOrNull().orEmpty()
            if (useGenymotion && gmtoolExists) {
                val (gcode, gout) = runCmdCapture(gmtoolExe, "admin", "list")
                if (gcode == 0) {
                    val s = gout.lines()
                        .firstOrNull { it.contains("|") && it.contains(" On ") }
                        ?.split("|")?.map { it.trim() }?.getOrNull(1).orEmpty()
                    if (s.isNotBlank()) return s
                }
            }
            return first
        }
        val deviceSerial = resolveDeviceSerial()
        val args = mutableListOf(adbCmd)
        if (deviceSerial.isNotBlank()) { args.add("-s"); args.add(deviceSerial) }
        args.addAll(listOf("shell", "am", "force-stop", appId))
        val rc = runCmd(*args.toTypedArray())
        if (rc != 0) throw GradleException("force-stop failed for $appId (exit=$rc)")
    }
}

tasks.register("killEmulator") {
    doLast {
        fun runCmd(vararg args: String): Int {
            val pb = ProcessBuilder(*args)
            pb.redirectErrorStream(true)
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
            val p = pb.start()
            return try { p.waitFor() } catch (_: InterruptedException) { -1 }
        }
        val isMacLocal = System.getProperty("os.name").lowercase().contains("mac")
        val gmtoolPath = gmtoolExe
        val genyName = genyDeviceName
        if (isMacLocal && gmtoolPath.isNotBlank() && File(gmtoolPath).exists()) {
            if (genyName.isNotBlank()) {
                runCmd(gmtoolPath, "admin", "stop", genyName)
            }
            runCmd("osascript", "-e", "tell application \"Genymotion\" to quit")
        } else {
            runCmd(adbCmd, "-e", "emu", "kill")
        }
    }
}

// 为 F-Droid 变体设置 JDWP 端口映射并输出详细日志，便于 VS Code 一键附加
tasks.register("setupJdwpForwardFdroid") {
    dependsOn("waitForDevice")
    doLast {
        // 步骤总览：
        // 1) 解析设备序列号（ADB/Genymotion），统一封装 adb 调用
        // 2) 以 -D 启动 F-Droid 变体主 Activity，应用进入“等待调试器”状态
        // 3) 轮询主进程 PID（pidof/ps 兜底），打印每次尝试的 PID 以便观察是否卡住
        // 4) 若主进程 PID 为空则重启应用重试
        // 5) 解析 JDWP 端口（属性/环境变量/默认 5005），清理旧映射并尝试映射到主进程
        // 6) 主进程映射失败则尝试守护进程 :RunSoLibV2RayDaemon 作为后备
        // 7) 打印 forward --list 的完整输出，验证端口映射是否成功
        // 8) 成功后打印 VS Code 附加所需的 host/port，并清理“调试应用”设置
        // 设计目标：一次性准备 JDWP 端口，快速返回，避免阻塞到 66% 进度
        // 通用执行与捕获输出工具（简化 adb/gmtool 调用）
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

        // 解析设备序列号（优先已连接设备，其次 Genymotion）
        fun resolveDeviceSerial(): String {
            val (_, listOut) = runCmdCapture(adbCmd, "devices")
            val first = listOut.lines().map { it.trim() }
                .firstOrNull { it.endsWith("\tdevice") }?.split("\t")?.firstOrNull().orEmpty()
            if (useGenymotion && gmtoolExists) {
                val (gcode, gout) = runCmdCapture(gmtoolExe, "admin", "list")
                if (gcode == 0) {
                    val s = gout.lines()
                        .firstOrNull { it.contains("|") && it.contains(" On ") }
                        ?.split("|")?.map { it.trim() }?.getOrNull(1).orEmpty()
                    if (s.isNotBlank()) return s
                }
            }
            return first
        }

        val deviceSerial = resolveDeviceSerial()
        println("[setupJdwpForwardFdroid] deviceSerial=${deviceSerial.ifBlank { "<auto>" }}")

        // 封装 adb 子命令（自动附加 -s <serial>）
        fun runAdb(vararg sub: String): Int {
            val args = mutableListOf(adbCmd)
            if (deviceSerial.isNotBlank()) { args.add("-s"); args.add(deviceSerial) }
            args.addAll(sub)
            return runCmd(*args.toTypedArray())
        }

        fun runAdbCapture(vararg sub: String): Pair<Int, String> {
            val args = mutableListOf(adbCmd)
            if (deviceSerial.isNotBlank()) { args.add("-s"); args.add(deviceSerial) }
            args.addAll(sub)
            return runCmdCapture(*args.toTypedArray())
        }

        // 启动 F-Droid 变体主界面，并启用 -D 等待调试器模式
        println("[setupJdwpForwardFdroid] starting app in debug mode (-D)")
        runAdb("shell", "am", "start", "-D", "-n", "com.v2ray.ang.fdroid/com.v2ray.ang.ui.MainActivity")

        // 轮询等待应用主进程 PID（兼容 pidof 与 ps）
        fun waitForPidFd(pkg: String, timeoutMs: Long = 10000, intervalMs: Long = 500): String {
            val deadline = System.currentTimeMillis() + timeoutMs
            var attempt = 0
            while (System.currentTimeMillis() < deadline) {
                attempt += 1
                var p = runAdbCapture("shell", "pidof", pkg).second.trim()
                if (p.isBlank()) {
                    val psOut = runAdbCapture("shell", "ps", "-A").second
                    val first = psOut.split(Regex("\\r?\\n")).firstOrNull { it.contains(pkg) }?.trim()
                    p = first?.split(Regex("\\s+"))?.getOrNull(1) ?: ""
                }
                println("[setupJdwpForwardFdroid] waitForPid attempt=${attempt} pid=${p.ifBlank { "<none>" }}")
                if (p.isNotBlank()) return p
                try { Thread.sleep(intervalMs.toLong()) } catch (_: InterruptedException) {}
            }
            return ""
        }

        var pid = waitForPidFd("com.v2ray.ang.fdroid")
        println("[setupJdwpForwardFdroid] initial pid=${pid.ifBlank { "<none>" }}")

        // 查找守护进程 PID（当主进程不可映射时作为后备）
        fun findDaemonPidFd(daemonName: String): String {
            val psOut = runAdbCapture("shell", "ps", "-A").second
            val daemonLine = psOut.split(Regex("\\r?\\n")).firstOrNull { it.contains(daemonName) }?.trim()
            val daemonPid = daemonLine?.split(Regex("\\s+"))?.getOrNull(1)?.trim().orEmpty()
            println("[setupJdwpForwardFdroid] findDaemonPidFd daemonPid=${daemonPid.ifBlank { "<none>" }}")
            return daemonPid
        }
        if (pid.isBlank()) {
            println("[setupJdwpForwardFdroid] PID empty, restart app and retry")
            runAdb("shell", "am", "force-stop", "com.v2ray.ang.fdroid")
            runAdb("shell", "am", "start", "-D", "-n", "com.v2ray.ang.fdroid/com.v2ray.ang.ui.MainActivity")
            pid = waitForPidFd("com.v2ray.ang.fdroid")
            println("[setupJdwpForwardFdroid] pid after restart=${pid.ifBlank { "<none>" }}")
        }
        if (pid.isBlank()) throw GradleException("PID not found for com.v2ray.ang.fdroid")

        val jdwpPortFd = ((project.findProperty("jdwpPort") as? String)?.toIntOrNull()
            ?: System.getenv("V2PLUS_JDWP_PORT")?.toIntOrNull()
            ?: 5005)
        println("[setupJdwpForwardFdroid] jdwpPort=${jdwpPortFd}")

        val rmCode = runAdb("forward", "--remove", "tcp:${jdwpPortFd}")
        if (rmCode != 0) println("[setupJdwpForwardFdroid] forward --remove returned ${rmCode}, continue")
        var (fwdCodeFd, fwdMsgFd) = runAdbCapture("forward", "tcp:${jdwpPortFd}", "jdwp:${pid}")
        if (fwdCodeFd != 0) {
            println("[setupJdwpForwardFdroid] forward to main pid failed (exit=${fwdCodeFd}); trying daemon...")
            val daemonPid = findDaemonPidFd("com.v2ray.ang.fdroid:RunSoLibV2RayDaemon")
            if (daemonPid.isNotBlank()) {
                val res = runAdbCapture("forward", "tcp:${jdwpPortFd}", "jdwp:${daemonPid}")
                fwdCodeFd = res.first
                fwdMsgFd = res.second
            }
            if (fwdCodeFd != 0) {
                throw GradleException("JDWP forward failed: tcp:${jdwpPortFd} -> pid ${pid} (exit=${fwdCodeFd})\n${fwdMsgFd}")
            }
        }
        val (_, fwdListFd) = runAdbCapture("forward", "--list")
        println("[setupJdwpForwardFdroid] forward --list:\n${fwdListFd}")
        val okForwardFd = fwdListFd.lines().any { it.contains(":${jdwpPortFd} ") && (deviceSerial.isBlank() || it.startsWith(deviceSerial)) }
        if (!okForwardFd) {
            val (fc2, fm2) = runAdbCapture("forward", "tcp:${jdwpPortFd}", "jdwp:${pid}")
            if (fc2 != 0) throw GradleException("JDWP forward failed: tcp:${jdwpPortFd} -> pid ${pid} (exit=${fc2})\n${fm2}")
        }
        println("[JDWP] Ready: localhost:${jdwpPortFd} -> PID ${pid} (com.v2ray.ang.fdroid)")
        println("[JDWP] VS Code attach: host=localhost, port=${jdwpPortFd}")
        val clr = runAdb("shell", "cmd", "activity", "clear-debug-app")
        println("[setupJdwpForwardFdroid] clear-debug-app exit=${clr}")
    }
}

tasks.register("setupJdwpForwardPlaystore") {
    dependsOn("waitForDevice")
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

        fun resolveDeviceSerial(): String {
            val (_, listOut) = runCmdCapture(adbCmd, "devices")
            val first = listOut.lines().map { it.trim() }
                .firstOrNull { it.endsWith("\tdevice") }?.split("\t")?.firstOrNull().orEmpty()
            if (useGenymotion && gmtoolExists) {
                val (gcode, gout) = runCmdCapture(gmtoolExe, "admin", "list")
                if (gcode == 0) {
                    val s = gout.lines()
                        .firstOrNull { it.contains("|") && it.contains(" On ") }
                        ?.split("|")?.map { it.trim() }?.getOrNull(1).orEmpty()
                    if (s.isNotBlank()) return s
                }
            }
            return first
        }

        val deviceSerial = resolveDeviceSerial()

        fun runAdb(vararg sub: String): Int {
            val args = mutableListOf(adbCmd)
            if (deviceSerial.isNotBlank()) { args.add("-s"); args.add(deviceSerial) }
            args.addAll(sub)
            return runCmd(*args.toTypedArray())
        }

        fun runAdbCapture(vararg sub: String): Pair<Int, String> {
            val args = mutableListOf(adbCmd)
            if (deviceSerial.isNotBlank()) { args.add("-s"); args.add(deviceSerial) }
            args.addAll(sub)
            return runCmdCapture(*args.toTypedArray())
        }

        println("[setupJdwpForwardPlaystore] starting app in debug mode (-D)")
        runAdb("shell", "am", "start", "-D", "-n", "com.v2ray.ang/com.v2ray.ang.ui.MainActivity")
        runAdb("shell", "cmd", "activity", "set-debug-app", "-w", "com.v2ray.ang")

        fun waitForPid(pkg: String, timeoutMs: Long = 10000, intervalMs: Long = 500): String {
            val deadline = System.currentTimeMillis() + timeoutMs
            var attempt = 0
            while (System.currentTimeMillis() < deadline) {
                attempt += 1
                var p = runAdbCapture("shell", "pidof", pkg).second.trim()
                if (p.isBlank()) {
                    val psOut = runAdbCapture("shell", "ps", "-A").second
                    val first = psOut.split(Regex("\\r?\\n")).firstOrNull { it.contains(pkg) }?.trim()
                    p = first?.split(Regex("\\s+"))?.getOrNull(1) ?: ""
                }
                println("[setupJdwpForwardPlaystore] waitForPid attempt=${attempt} pid=${p.ifBlank { "<none>" }}")
                if (p.isNotBlank()) return p
                try { Thread.sleep(intervalMs.toLong()) } catch (_: InterruptedException) {}
            }
            return ""
        }

        var pid = waitForPid("com.v2ray.ang")
        println("[setupJdwpForwardPlaystore] initial pid=${pid.ifBlank { "<none>" }}")

        // 查找守护进程 PID（作为主进程映射失败时的后备）
        fun findDaemonPid(daemonName: String): String {
            val psOut = runAdbCapture("shell", "ps", "-A").second
            val daemonLine = psOut.split(Regex("\\r?\\n")).firstOrNull { it.contains(daemonName) }?.trim()
            val daemonPid = daemonLine?.split(Regex("\\s+"))?.getOrNull(1)?.trim().orEmpty()
            return daemonPid
        }
        if (pid.isBlank()) {
            runAdb("shell", "am", "force-stop", "com.v2ray.ang")
            runAdb("shell", "am", "start", "-D", "-n", "com.v2ray.ang/com.v2ray.ang.ui.MainActivity")
            pid = waitForPid("com.v2ray.ang")
            println("[setupJdwpForwardPlaystore] pid after restart=${pid.ifBlank { "<none>" }}")
        }
        if (pid.isBlank()) throw GradleException("PID not found for com.v2ray.ang")

        val jdwpPort = ((project.findProperty("jdwpPort") as? String)?.toIntOrNull()
            ?: System.getenv("V2PLUS_JDWP_PORT")?.toIntOrNull()
            ?: 5005)
        println("[setupJdwpForwardPlaystore] jdwpPort=${jdwpPort}")

        runAdb("forward", "--remove", "tcp:${jdwpPort}")
        var (fwdCodePlay, fwdMsgPlay) = runAdbCapture("forward", "tcp:${jdwpPort}", "jdwp:${pid}")
        if (fwdCodePlay != 0) {
            val daemonPid = findDaemonPid("com.v2ray.ang:RunSoLibV2RayDaemon")
            if (daemonPid.isNotBlank()) {
                val res = runAdbCapture("forward", "tcp:${jdwpPort}", "jdwp:${daemonPid}")
                fwdCodePlay = res.first
                fwdMsgPlay = res.second
            }
            if (fwdCodePlay != 0) {
                throw GradleException("JDWP forward failed: tcp:${jdwpPort} -> pid ${pid} (exit=${fwdCodePlay})\n${fwdMsgPlay}")
            }
        }
        val (_, fwdList) = runAdbCapture("forward", "--list")
        println("[setupJdwpForwardPlaystore] forward --list:\n${fwdList}")
        val okForward = fwdList.lines().any { it.contains(":${jdwpPort} ") && (deviceSerial.isBlank() || it.startsWith(deviceSerial)) }
        if (!okForward) {
            val (fc2, fm2) = runAdbCapture("forward", "tcp:${jdwpPort}", "jdwp:${pid}")
            if (fc2 != 0) throw GradleException("JDWP forward failed: tcp:${jdwpPort} -> pid ${pid} (exit=${fc2})\n${fm2}")
        }
        println("[JDWP] Ready: localhost:${jdwpPort} -> PID ${pid} (com.v2ray.ang)")
        println("[JDWP] VS Code attach: host=localhost, port=${jdwpPort}")
        val clr = runAdb("shell", "cmd", "activity", "clear-debug-app")
        println("[setupJdwpForwardPlaystore] clear-debug-app exit=${clr}")
    }
}

tasks.register("setupJdwpForward") {
    val distro = ((project.findProperty("distribution") as? String)
        ?: System.getenv("V2PLUS_DISTRIBUTION")
        ?: "playstore").lowercase()
    if (distro == "fdroid") {
        dependsOn("setupJdwpForwardFdroid")
    } else {
        dependsOn("setupJdwpForwardPlaystore")
    }
}

// 通用安装并跟踪日志任务：通过 -Pdistribution 或环境变量选择 playstore/fdroid（默认 playstore）
tasks.register("installDebugAndLogcat") {
    val distro = ((project.findProperty("distribution") as? String)
        ?: System.getenv("V2PLUS_DISTRIBUTION")
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

// 全局任务执行日志：在每个任务开始/结束时输出标记，便于定位失败位置
val taskStartTimes = mutableMapOf<String, Long>()

// 使用 Kotlin DSL 兼容的 TaskExecutionListener 替代已废弃的 beforeTask/afterTask 钩子
gradle.addListener(object : org.gradle.api.execution.TaskExecutionListener {
    override fun beforeExecute(task: org.gradle.api.Task) {
        taskStartTimes[task.path] = System.currentTimeMillis()
        println("===== BEGIN TASK: ${task.path} =====")
    }

    override fun afterExecute(
        task: org.gradle.api.Task,
        state: org.gradle.api.tasks.TaskState
    ) {
        val elapsed = taskStartTimes[task.path]?.let { System.currentTimeMillis() - it } ?: 0L
        val outcome = when {
            state.skipped -> "SKIPPED"
            state.failure != null -> "FAILED"
            else -> "SUCCESS"
        }
        println("===== END   TASK: ${task.path} [${outcome}] (${elapsed}ms) =====")
    }
})
