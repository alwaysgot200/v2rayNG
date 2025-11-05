#!/usr/bin/env sh

# v2rayNG Build Tool (macOS zsh & Linux bash compatible)
# 交互式构建脚本，适配仓库结构与任务名称

set -u

ORIGINAL_DIR="$(pwd)"
SCRIPT_DIR="$(cd "$(dirname "$0")" >/dev/null 2>&1 && pwd)"

APP_ID="com.v2ray.ang"
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

# 构建任务封装
do_clean() { gradle clean; }
do_build() { gradle build; }
do_test() { gradle test; }

do_assemble_fdroid_debug() { gradle :app:assembleFdroidDebug; }
do_assemble_playstore_debug() { gradle :app:assemblePlaystoreDebug; }
do_assemble_fdroid_release() { gradle :app:assembleFdroidRelease; }
do_assemble_playstore_release() { gradle :app:assemblePlaystoreRelease; }

do_install_fdroid_debug() {
  if has_cmd adb; then
    gradle :app:installFdroidDebug
  else
    echo "未找到 adb，请先安装 Android Platform-Tools。"
    return 1
  fi
}

do_install_playstore_debug() {
  if has_cmd adb; then
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

do_kill_emulator() {
  if has_cmd adb; then
    adb -e emu kill
  else
    echo "未找到 adb，请先安装 Android Platform-Tools。"
    return 1
  fi
}

while true; do
  print_header
  warn_if_missing_aar
  echo " 1) 清理项目（clean）"
  echo " 2) 构建项目（build）"
  echo " 3) 运行测试（test）"
  echo " 4) 组装 F-Droid Debug（:app:assembleFdroidDebug）"
  echo " 5) 组装 Play Store Debug（:app:assemblePlaystoreDebug）"
  echo " 6) 组装 F-Droid Release（:app:assembleFdroidRelease）"
  echo " 7) 组装 Play Store Release（:app:assemblePlaystoreRelease）"
  echo " 8) 安装 F-Droid Debug 到设备（需 adb）"
  echo " 9) 安装 Play Store Debug 到设备（需 adb）"
  echo "10) adb 停止应用 (${APP_ID})"
  echo "11) adb 关闭模拟器"
  echo "12) 安装并跟踪日志（F-Droid Debug）"
  echo "13) 安装并跟踪日志（Play Store Debug）"
  echo " 0) 退出"
  echo "==================================="
  printf "输入数字并回车（0 退出）： "
  read -r choice
  case "$choice" in
    1) do_clean ;;
    2) do_build ;;
    3) do_test ;;
    4) do_assemble_fdroid_debug ;;
    5) do_assemble_playstore_debug ;;
    6) do_assemble_fdroid_release ;;
    7) do_assemble_playstore_release ;;
    8) do_install_fdroid_debug ;;
    9) do_install_playstore_debug ;;
    10) do_stop_app ;;
    11) do_kill_emulator ;;
    12) gradle :app:installFdroidDebugAndLogcat ;;
    13) gradle :app:installPlaystoreDebugAndLogcat ;;
    0) break ;;
    *) echo "无效选择：$choice" ;;
  esac
  pause
done

cd "$ORIGINAL_DIR" || true
exit 0