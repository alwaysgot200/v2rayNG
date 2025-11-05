# v2rayNG

一个运行在 Android 上的代理客户端，支持 [Xray-core](https://github.com/XTLS/Xray-core) 与 [v2fly core](https://github.com/v2fly/v2ray-core)。

[![API](https://img.shields.io/badge/API-21%2B-yellow.svg?style=flat)](https://developer.android.com/about/versions/lollipop)
[![Kotlin Version](https://img.shields.io/badge/Kotlin-2.2.0-blue.svg)](https://kotlinlang.org)
[![GitHub commit activity](https://img.shields.io/github/commit-activity/m/2dust/v2rayNG)](https://github.com/2dust/v2rayNG/commits/master)
[![CodeFactor](https://www.codefactor.io/repository/github/2dust/v2rayng/badge)](https://www.codefactor.io/repository/github/2dust/v2rayng)
[![GitHub Releases](https://img.shields.io/github/downloads/2dust/v2rayNG/latest/total?logo=github)](https://github.com/2dust/v2rayNG/releases)
[![Chat on Telegram](https://img.shields.io/badge/Chat%20on-Telegram-brightgreen.svg)](https://t.me/v2rayn)

### Telegram Channel
[github_2dust](https://t.me/github_2dust)

---

## 项目介绍

v2rayNG 是一款面向 Android 的图形化客户端，集成了代理协议解析、配置管理、VPN/本地代理服务与测速统计功能。应用通过 gomobile 生成的 `libv2ray.aar` 集成 Xray/V2Ray 核心，提供易用的界面与路由控制能力。

### APK主要功能
- 支持常见代理协议：`VMess`、`VLESS`、`Trojan`、`Shadowsocks`、`Socks`、`HTTP`、`WireGuard`（见 `fmt/*`）。
- 配置管理：导入/导出、订阅、二维码扫描解析、手动编辑与多配置切换。
- 路由规则：支持 GeoIP/Geosite 数据文件，规则集增删改、排序与启用状态切换。
- 连接模式：`VPN`（基于 `VpnService`）与仅代理（本地端口转发）。
- 测速与延迟：单配置测试、批量测速、核心运行状态与速率统计（上行/下行）。
- 通知与前台服务：在系统通知栏显示连接状态、速率与错误信息。
- 多架构打包：支持 `arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86`，可生成 `universal` 包。

### GeoIP/Geosite 数据
- 默认路径：`Android/data/com.v2ray.ang/files/assets`（设备差异可能存在）。
- 可直接从 [Loyalsoldier/v2ray-rules-dat](https://github.com/Loyalsoldier/v2ray-rules-dat) 获取增强版本（需要可用代理）。
- 也可导入第三方数据文件，如 [h2y](https://guide.v2fly.org/routing/sitedata.html#%E5%A4%96%E7%BD%AE%E7%9A%84%E5%9F%9F%E5%90%8D%E6%96%87%E4%BB%B6)。

---

## 项目结构与功能说明

仓库根目录关键子项目与用途：

- `V2rayNG/`：Android 应用主工程（Kotlin/Java）。
  - `app/src/main/java/com/v2ray/ang/`：业务代码，包含配置解析（`handler/`、`fmt/`）、服务（`service/`）、界面（`ui/`）等。
  - `app/libs/`：本地依赖目录，须放置通过 gomobile 生成的 `libv2ray.aar`（提供 `libv2ray.*` 与 `go.Seq` 绑定类）。
  - `app/build.gradle.kts`：应用的构建配置，定义 `fdroid/playstore` 两个 flavor、ABI splits、打包文件名与依赖。
  - 运行模式：`V2RayVpnService`（VPN）、`V2RayProxyOnlyService`（本地代理）。

- `AndroidLibXrayLite/`：Go + gomobile 项目，生成 Android AAR（`libv2ray.aar`）。
  - 核心入口：`libv2ray_main.go`（定义 `CoreController`、`CoreCallbackHandler`、`InitCoreEnv` 等与 Xray-core 交互的逻辑）。
  - 产物：`libv2ray.aar`、`libv2ray-sources.jar`。
  - 依赖：`github.com/xtls/xray-core`、`golang.org/x/mobile/asset` 等。

- `badvpn/`、`hev-socks5-tunnel/`、`libancillary/`、`tun2socks.mk`、`compile-tun2socks.sh`：
  - 可选的底层网络/隧道组件源码，用于特定网络方案（如 tun2socks、Socks5 隧道等）。多数情况下构建 APK 不需要手动编这些模块；如需启用相关功能，可参见各目录的构建说明与脚本。

- `hysteria/`：Hysteria2 相关 Go 工程（服务端/客户端示例与工具）。默认 APK 构建不依赖；如需集成，可参考 `libhysteria2.sh` 等脚本并按需改造。

---

## 构建环境与版本（参考）

以下为在 macOS + VS Code 环境下成功构建的参考版本：

- 操作系统：macOS（Intel/Apple Silicon 皆可）
- JDK：`Java 17`
- Kotlin：`2.2.0`
- Android SDK：`Android API 35`，`cmdline-tools: latest`，`platform-tools` 安装完成
- Android NDK：`r26b (26.1.10909125)`
- Gradle：使用仓库自带 Gradle Wrapper（`V2rayNG/gradlew`）
- Go：`go1.25.x`（示例为 `go1.25.0 darwin/amd64`）
- gomobile/gobind：`latest`（通过 `go install` 安装）

> 说明：实际版本可按最新稳定版调整，但建议保持 SDK/NDK 与 gomobile 的兼容性；若下载 Go 模块网络受限，可设置 `GOPROXY=https://goproxy.cn,direct`。

---

## 在 macOS + VS Code 的详细构建步骤

下面以 VS Code 内置终端为例，给出从环境准备到 APK 产出的完整流程。

### 1. 准备 Android SDK/NDK
1) 安装 Android SDK（路径示例：`/Users/Android/SDK`），并确保包含以下组件：
   - `cmdline-tools`、`platform-tools`、`build-tools`、`platforms;android-35`。
2) 安装并接受许可：
   - `yes | /Users/Android/SDK/cmdline-tools/latest/bin/sdkmanager --licenses`
3) 安装 NDK（推荐 r26b）：
   - `/Users/Android/SDK/cmdline-tools/latest/bin/sdkmanager "ndk;26.1.10909125"`

### 2. 安装 Go 与 gomobile 工具
1) 安装 Go（`go1.25.x`）。
2) 在终端执行：
```
export GOPATH=$(go env GOPATH)
export PATH="$PATH:$GOPATH/bin"
export GOPROXY=https://goproxy.cn,direct
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
```
3) 初始化 gomobile（指定 SDK/NDK 环境变量）：
```
export ANDROID_HOME=/Users/Android/SDK
export ANDROID_NDK_HOME=/Users/Android/SDK/ndk/26.1.10909125
gomobile init
```

### 3. 生成 `libv2ray.aar`（AndroidLibXrayLite）
1) 进入目录：
```
cd /Volumes/exfat/code/v2rayNG/AndroidLibXrayLite
```
2) 可选：整理 Go 依赖：
```
go mod tidy -v
```
3) 运行 gomobile 绑定生成 AAR：
```
gomobile bind -v -androidapi 21 -ldflags='-s -w' ./
```
4) 生成的文件：`libv2ray.aar`、`libv2ray-sources.jar`（在当前目录）。

### 4. 集成 AAR 到 Android 应用
1) 创建 `libs` 目录并复制 AAR：
```
mkdir -p /Volumes/exfat/code/v2rayNG/V2rayNG/app/libs
cp -f /Volumes/exfat/code/v2rayNG/AndroidLibXrayLite/libv2ray.aar /Volumes/exfat/code/v2rayNG/V2rayNG/app/libs/
```
2) `V2rayNG/app/build.gradle.kts` 已包含：
```
implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
sourceSets { getByName("main") { jniLibs.srcDirs("libs") } }
```
   无需额外修改，Gradle 会自动加入 `libv2ray.aar`。

### 5. 编译 APK
1) 在 VS Code 终端进入应用目录：
```
cd /Volumes/exfat/code/v2rayNG/V2rayNG
```
2) 构建 F-Droid Debug（可选用 Play Store）：
```
./gradlew :app:assembleFdroidDebug --info
# 或者
./gradlew :app:assemblePlaystoreDebug --info
```
3) 输出 APK 路径：
```
V2rayNG/app/build/outputs/apk/<flavor>/debug/
```
   示例文件名：
   - `v2rayNG_1.10.26-fdroid_arm64-v8a.apk`
   - `v2rayNG_1.10.26-fdroid_armeabi-v7a.apk`
   - `v2rayNG_1.10.26-fdroid_x86.apk`
   - `v2rayNG_1.10.26-fdroid_x86_64.apk`
   - `v2rayNG_1.10.26-fdroid_universal.apk`

### 6. 可选子项目构建（按需）

> 这些模块一般不影响基础 APK 的构建，仅在需要特定网络栈/隧道功能时编译集成。

- `badvpn/`（tun2socks 等）：
  - 依赖：Android NDK r26b、CMake/ndk-build。
  - 常用脚本：`compile-tun2socks.sh`、`tun2socks.mk`。
  - 产物：对应架构的 `.so` 或可执行，按需放置于 `V2rayNG/app/src/main/jniLibs/<ABI>/` 或 `app/libs/`。

- `hev-socks5-tunnel/`：
  - 依赖：Android NDK r26b。
  - 构建（示例）：
    - 进入目录并执行 `ndk-build`（需要 `Android.mk` 与 `Application.mk`）。
    - 输出 `.so` 放置到 `jniLibs/<ABI>/`。

- `hysteria/`（Hysteria2）：
  - 依赖：Go1.2x、gomobile（如需 AAR 绑定）。
  - 可参考仓库脚本如 `libhysteria2.sh` 进行构建，或将其作为独立组件运行。

---

## 可选子项目一键脚本（macOS + VS Code）

- 新增脚本：`scripts/build-optionals-macos.sh`，在 VS Code 终端中一键构建并自动复制到 `jniLibs/`。
  - 先决条件：
    - `ANDROID_HOME` 指向 Android SDK（默认 `/Users/Android/SDK`）。
    - `ANDROID_NDK_HOME` 指向 NDK（默认 `${ANDROID_HOME}/ndk/26.1.10909125`）。
    - 可选：`ABIS` 指定需要的 ABI 列表（默认 `armeabi-v7a arm64-v8a x86 x86_64`）。
    - Hysteria2 需安装 `Go 1.25.x`。
  - 用法：
    - 构建 tun2socks + hev 并复制到 `V2rayNG/app/src/main/jniLibs/`：
      - `./scripts/build-optionals-macos.sh tun`
    - 构建 Hysteria2 并复制到 `jniLibs/`：
      - `./scripts/build-optionals-macos.sh hy2`
    - 构建全部：
      - `./scripts/build-optionals-macos.sh all`
    - 清理 `jniLibs/` 中的可选组件：
      - `./scripts/build-optionals-macos.sh clean`
    - 指定 ABI（示例只构建 `arm64-v8a`）：
      - `ABIS="arm64-v8a" ./scripts/build-optionals-macos.sh all`
  - 脚本逻辑要点：
    - 自动检测 macOS 主机架构（Intel/Apple Silicon），选择正确的 `NDK_HOST_TAG`（`darwin-x86_64`/`darwin-arm64`）。
    - 调用根目录已有脚本 `compile-tun2socks.sh` 构建 tun2socks 与 hev，并把产物统一放入根目录 `libs/<ABI>/`，随后复制到 `app/src/main/jniLibs/<ABI>/`。
    - Hysteria2 使用 `Go + CGO + NDK clang` 为各 ABI 构建 `libhysteria2.so`，并同样自动复制到 `jniLibs/`。

---

## 常见问题与构建技巧

- `libv2ray.aar` 缺失导致编译期 `Unresolved reference 'libv2ray'/'go.Seq'`：
  - 请确保按第 3–4 步生成并复制 AAR 到 `V2rayNG/app/libs/`。

- Go 模块拉取超时：
  - 设置代理：`export GOPROXY=https://goproxy.cn,direct`，避免 `gomobile bind` 阶段工具链校验失败。

- NDK 版本不兼容：
  - 推荐使用 `r26b`（`26.1.10909125`）。如遇到构建脚本对版本敏感，按脚本说明调整。

- Flavors 与 ABI 输出：
  - `fdroid` 与 `playstore` flavor 使用不同的版本号策略与输出文件名。`splits` 会为每个 ABI 输出独立 APK，或生成 `universal` 包。

---

## 运行与调试

- v2rayNG 可在 Android 模拟器运行；WSA 环境需授予 VPN 权限：
```
appops set [package name] ACTIVATE_VPN allow
```

更多使用与开发文档请参考我们的 [Wiki](https://github.com/2dust/v2rayNG/wiki)。
