#!/usr/bin/env sh

# v2rayNG Build Tool (macOS zsh & Linux bash compatible)
# 交互式构建脚本，适配仓库结构与任务名称

set -u

ORIGINAL_DIR="$(pwd)"
SCRIPT_DIR="$(cd "$(dirname "$0")" >/dev/null 2>&1 && pwd)"

APP_ID="com.v2ray.ang.fdroid"
APP_DIR="$SCRIPT_DIR/V2rayNG"

cd "$APP_DIR" || {
  echo "无法进入应用目录：$APP_DIR"
  echo "请确认脚本位于仓库根目录，并存在 V2rayNG/ 子目录。"
  exit 1
}

print_header() {
  clear 2>/dev/null || printf "\033c"
  echo "==================================="
  echo "       v2rayNG Build Tool (Unix)"
  echo "==================================="
  echo "工作目录：$APP_DIR"
}

pause() {
  printf "按回车返回菜单..." && read -r _
}

has_cmd() {
  command -v "$1" >/dev/null 2>&1
}

ensure_gradlew() {
  if [ ! -x "./gradlew" ]; then
    if [ -f "./gradlew" ]; then
      chmod +x ./gradlew 2>/dev/null || true
    else
      echo "未找到 ./gradlew，请确认在 V2rayNG/ 目录执行。"
      return 1
    fi
  fi
  return 0
}

gradle() {
  ensure_gradlew || return 1
  ./gradlew "$@"
}

warn_if_missing_aar() {
  if [ ! -f "./app/libs/libv2ray.aar" ]; then
    echo "提示：未检测到 app/libs/libv2ray.aar。"
    echo "  - 请参考 README 在 AndroidLibXrayLite 中构建并复制到此目录。"
    echo "  - 未提供 AAR 仍可编译，但运行可能缺失核心库。"
  fi
}

# 查找 apksigner 可执行文件（优先 ANDROID_HOME/ANDROID_SDK_ROOT 的最新 build-tools）
find_apksigner() {
  local sdk=""
  if [ -n "${ANDROID_HOME:-}" ]; then
    sdk="$ANDROID_HOME"
  elif [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    sdk="$ANDROID_SDK_ROOT"
  else
    sdk="$HOME/Library/Android/sdk"
  fi

  local latest=""
  if [ -d "$sdk/build-tools" ]; then
    latest="$(ls "$sdk/build-tools" 2>/dev/null | sort -V | tail -n1)"
  fi
  if [ -n "$latest" ] && [ -x "$sdk/build-tools/$latest/apksigner" ]; then
    echo "$sdk/build-tools/$latest/apksigner"
    return 0
  fi

  if has_cmd apksigner; then
    command -v apksigner
    return 0
  fi

  echo ""
  return 1
}

# 遍历并验证 Release 目录下的所有 APK，输出汇总结果
verify_release_apk() {
  local flavor="$1"
  local release_dir="$APP_DIR/app/build/outputs/apk/$flavor/release"

  if [ ! -d "$release_dir" ]; then
    echo "未找到 ${flavor} Release 输出目录：$release_dir"
    return 1
  fi

  local apksigner_bin
  apksigner_bin="$(find_apksigner)"
  if [ -z "$apksigner_bin" ]; then
    echo "未找到 apksigner。请安装 Android SDK Build-Tools 或设置 ANDROID_HOME/ANDROID_SDK_ROOT。"
    echo "示例安装命令：sdkmanager --install 'build-tools;34.0.0'"
    return 1
  fi

  # 根据 Gradle 输出元数据精确定位 APK 名称；不可用时退回目录枚举
  list_built_apks() {
    local meta="$release_dir/output-metadata.json"
    if [ -f "$meta" ]; then
      # 提取所有 outputFile 字段并拼接为绝对路径
      sed -n 's/.*"outputFile":[[:space:]]*"\(.*\)".*/\1/p' "$meta" | while read -r fname; do
        [ -n "$fname" ] && echo "$release_dir/$fname"
      done
      return 0
    fi
    # 兜底：列出目录下的 apk
    ls -1 "$release_dir"/*.apk 2>/dev/null || true
  }

  local total=0 ok=0 fail=0
  local found_any="false"
  for apk in $(list_built_apks); do
    [ -e "$apk" ] || continue
    found_any="true"
    case "$apk" in
      *-unsigned.apk*)
        echo "跳过未签名产物：$apk"
        continue
        ;;
    esac
    total=$((total + 1))
    echo "\n使用 apksigner 验证：$apk"
    echo "apksigner 路径：$apksigner_bin"
    "$apksigner_bin" verify --verbose --print-certs "$apk"
    rc=$?
    if [ $rc -eq 0 ]; then
      echo "签名验证结果：通过"
      ok=$((ok + 1))
    else
      echo "签名验证结果：失败（退出码 $rc）"
      fail=$((fail + 1))
    fi
  done

  if [ "$found_any" != "true" ] || [ $total -eq 0 ]; then
    echo "未在目录中找到可验证的 APK：$release_dir"
    return 1
  fi

  echo "\n${flavor} Release 验证汇总：总计 ${total}，通过 ${ok}，失败 ${fail}"
  if [ $fail -eq 0 ]; then
    return 0
  else
    return 1
  fi
}

# 构建任务封装
do_clean() { gradle clean; }
do_build() { echo "先执行 clean 清理旧构建产物..."; gradle clean && gradle build; }
do_test() { gradle test; }

do_assemble_fdroid_debug() { echo "先执行 clean 清理旧构建产物..."; gradle clean && gradle :app:assembleFdroidDebug; }
do_assemble_playstore_debug() { echo "先执行 clean 清理旧构建产物..."; gradle clean && gradle :app:assemblePlaystoreDebug; }
do_assemble_fdroid_release() {
  echo "开始 F-Droid Release 构建并验证（委托 Gradle 任务）..."
  echo "先执行 clean 清理旧构建产物..."
  gradle clean && gradle :app:assembleFdroidReleaseAndVerify
}

do_assemble_playstore_release() {
  echo "开始 Play Store Release 构建并验证（委托 Gradle 任务）..."
  echo "先执行 clean 清理旧构建产物..."
  gradle clean && gradle :app:assemblePlaystoreReleaseAndVerify
}

do_install_fdroid_debug() {
  if has_cmd adb; then
    # 传入跨平台属性：macOS 优先 Genymotion；Windows/其他使用 Android Emulator
    local useGeny="false"
    local gmtool="/Applications/Genymotion.app/Contents/MacOS/gmtool"
    if [ "$(uname -s)" = "Darwin" ] && [ -x "$gmtool" ]; then
      useGeny="auto"
    fi
    # 自动启动模拟器并等待设备就绪后再安装（仅在设置了 GENY_DEVICE_NAME 时才传入该属性）
    local args=(":app:startEmulator" "-PuseGenymotion=$useGeny" "-PgmtoolExe=$gmtool")
    if [ -n "${GENY_DEVICE_NAME:-}" ]; then
      args+=("-PgenyDeviceName=${GENY_DEVICE_NAME}")
    fi
    gradle "${args[@]}"
    gradle :app:waitForDevice
    gradle :app:installFdroidDebug
  else
    echo "未找到 adb，请先安装 Android Platform-Tools。"
    return 1
  fi
}

do_install_playstore_debug() {
  if has_cmd adb; then
    local useGeny="false"
    local gmtool="/Applications/Genymotion.app/Contents/MacOS/gmtool"
    if [ "$(uname -s)" = "Darwin" ] && [ -x "$gmtool" ]; then
      useGeny="auto"
    fi
    local args=(":app:startEmulator" "-PuseGenymotion=$useGeny" "-PgmtoolExe=$gmtool")
    if [ -n "${GENY_DEVICE_NAME:-}" ]; then
      args+=("-PgenyDeviceName=${GENY_DEVICE_NAME}")
    fi
    gradle "${args[@]}"
    gradle :app:waitForDevice
    gradle :app:installPlaystoreDebug
  else
    echo "未找到 adb，请先安装 Android Platform-Tools。"
    return 1
  fi
}

do_stop_app() {
  if has_cmd adb; then
    adb shell am force-stop "$APP_ID"
  else
    echo "未找到 adb，请先安装 Android Platform-Tools。"
    return 1
  fi
}

# 安装并跟踪日志（F-Droid/Playstore Debug）传递 Genymotion 属性
do_install_fdroid_debug_and_logcat() {
  if has_cmd adb; then
    local useGeny="false"
    local gmtool="/Applications/Genymotion.app/Contents/MacOS/gmtool"
    if [ "$(uname -s)" = "Darwin" ] && [ -x "$gmtool" ]; then
      useGeny="auto"
    fi
    local args=(":app:installFdroidDebugAndLogcat" "-PuseGenymotion=$useGeny" "-PgmtoolExe=$gmtool")
    if [ -n "${GENY_DEVICE_NAME:-}" ]; then
      args+=("-PgenyDeviceName=${GENY_DEVICE_NAME}")
    fi
    gradle "${args[@]}"
  else
    echo "未找到 adb，请先安装 Android Platform-Tools。"
    return 1
  fi
}

do_install_playstore_debug_and_logcat() {
  if has_cmd adb; then
    local useGeny="false"
    local gmtool="/Applications/Genymotion.app/Contents/MacOS/gmtool"
    if [ "$(uname -s)" = "Darwin" ] && [ -x "$gmtool" ]; then
      useGeny="auto"
    fi
    local args=(":app:installPlaystoreDebugAndLogcat" "-PuseGenymotion=$useGeny" "-PgmtoolExe=$gmtool")
    if [ -n "${GENY_DEVICE_NAME:-}" ]; then
      args+=("-PgenyDeviceName=${GENY_DEVICE_NAME}")
    fi
    gradle "${args[@]}"
  else
    echo "未找到 adb，请先安装 Android Platform-Tools。"
    return 1
  fi
}

do_kill_emulator() {
  # macOS 上优先关闭 Genymotion；否则使用 Android Emulator 的 emu kill
  if [ "$(uname -s)" = "Darwin" ]; then
    local gmtool="/Applications/Genymotion.app/Contents/MacOS/gmtool"
    if [ -x "$gmtool" ]; then
      if [ -n "${GENY_DEVICE_NAME:-}" ]; then
        "$gmtool" admin stop "${GENY_DEVICE_NAME}" || true
      fi
      # 退出 Genymotion 应用（兜底）
      osascript -e 'tell application "Genymotion" to quit' || true
      return 0
    fi
  fi

  if has_cmd adb; then
    adb -e emu kill || true
  else
    echo "未找到 adb，请先安装 Android Platform-Tools。"
    return 1
  fi
}

while true; do
  print_header
  warn_if_missing_aar
  echo " 1) 清理"
  echo " 2) 全量构建（clean + build）"
  echo " 3) 运行测试"
  echo " 4) 构建 F-Droid Debug（clean + assemble）"
  echo " 5) 构建 F-Droid Release（clean + assemble + 验签）"
  echo " 6) 安装 F-Droid Debug（需 adb）"
  echo " 7) 跟踪日志（F-Droid Debug）"
  echo " 8) 停止应用（${APP_ID}）"
  echo " 9) 关闭模拟器"
  echo "10) 构建 Play Store Debug（clean + assemble）"
  echo "11) 构建 Play Store Release（clean + assemble + 验签）"
  echo "12) 安装 Play Store Debug（需 adb）"
  echo "13) 跟踪日志（Play Store Debug）"
  echo " 0) 退出"
  echo "==================================="
  printf "输入数字并回车（0 退出）： "
  read -r choice
  case "$choice" in
    1) do_clean ;;
    2) do_build ;;
    3) do_test ;;
    4) do_assemble_fdroid_debug ;;
    5) do_assemble_fdroid_release ;;
    6) do_install_fdroid_debug ;;
    7) do_install_fdroid_debug_and_logcat ;;
    8) do_stop_app ;;
    9) do_kill_emulator ;;
    10) do_assemble_playstore_debug ;;
    11) do_assemble_playstore_release ;;
    12) do_install_playstore_debug ;;
    13) do_install_playstore_debug_and_logcat ;;
    0) break ;;
    *) echo "无效选择：$choice" ;;
  esac
  pause
done

cd "$ORIGINAL_DIR" || true
exit 0
