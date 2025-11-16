#!/usr/bin/env bash
set -euo pipefail

# macOS + VSCode 一键脚本：构建可选子项目并复制到 jniLibs
# 支持：
# - tun2socks + hev-socks5-tunnel（NDK）
# - Hysteria2（Go + CGO + NDK clang）
#
# 用法：
#   ./scripts/build-optionals-macos.sh tun         # 构建 tun2socks+hev 并复制到 jniLibs
#   ./scripts/build-optionals-macos.sh hy2         # 构建 Hysteria2 并复制到 jniLibs
#   ./scripts/build-optionals-macos.sh all         # 构建所有
#   ./scripts/build-optionals-macos.sh clean       # 清理 jniLibs 中的可选组件
#   ABIS="armeabi-v7a arm64-v8a x86 x86_64" ./scripts/build-optionals-macos.sh tun
#
# 先决条件：
# - ANDROID_HOME 指向 Android SDK（默认 /Users/Android/SDK）
# - ANDROID_NDK_HOME 指向 NDK（默认 $ANDROID_HOME/ndk/26.1.10909125）
# - Go 1.25.x（用于 Hysteria2，可选）
# - 在 VSCode 终端执行（bash）

ROOT_DIR="$(cd "$(dirname "$0")"/.. && pwd)"
APP_JNILIBS_DIR="$ROOT_DIR/v2plus/app/src/main/jniLibs"
ROOT_LIBS_DIR="$ROOT_DIR/libs"
HY2_DIR="$ROOT_DIR/hysteria"

ANDROID_HOME_DEFAULT="/Users/Android/SDK"
ANDROID_HOME="${ANDROID_HOME:-$ANDROID_HOME_DEFAULT}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/26.1.10909125}"

# 检测 NDK 主机标签（macOS Intel 与 Apple Silicon）
detect_ndk_host_tag() {
  local uname_s uname_m
  uname_s=$(uname -s)
  uname_m=$(uname -m)
  if [[ "$uname_s" == "Darwin" ]]; then
    if [[ "$uname_m" == "x86_64" ]]; then
      echo "darwin-x86_64"
    else
      # Apple Silicon
      echo "darwin-arm64"
    fi
  else
    # 兜底（通常不会在本脚本命中）
    echo "linux-x86_64"
  fi
}

NDK_HOST_TAG=$(detect_ndk_host_tag)

ABIS_DEFAULT=("armeabi-v7a" "arm64-v8a" "x86" "x86_64")
IFS=' ' read -r -a ABIS <<< "${ABIS:-${ABIS_DEFAULT[*]}}"

info() { echo -e "\033[1;34m[INFO]\033[0m $*"; }
warn() { echo -e "\033[1;33m[WARN]\033[0m $*"; }
err()  { echo -e "\033[1;31m[ERR ]\033[0m $*"; }

ensure_dirs() {
  mkdir -p "$ROOT_LIBS_DIR"
  mkdir -p "$APP_JNILIBS_DIR"
  for abi in "${ABIS[@]}"; do
    mkdir -p "$APP_JNILIBS_DIR/$abi"
    mkdir -p "$ROOT_LIBS_DIR/$abi"
  done
}

check_ndk() {
  if [[ ! -d "$ANDROID_NDK_HOME" ]]; then
    err "未找到 ANDROID_NDK_HOME=$ANDROID_NDK_HOME。请安装 NDK 并设置 ANDROID_NDK_HOME。"
    exit 1
  fi
}

copy_root_libs_to_jni() {
  info "复制 $ROOT_LIBS_DIR -> $APP_JNILIBS_DIR"
  for abi in "${ABIS[@]}"; do
    if [[ -d "$ROOT_LIBS_DIR/$abi" ]]; then
      rsync -a --delete "$ROOT_LIBS_DIR/$abi/" "$APP_JNILIBS_DIR/$abi/"
    fi
  done
}

build_tun_hev() {
  check_ndk
  ensure_dirs
  info "构建 tun2socks + hev-socks5-tunnel (NDK=$ANDROID_NDK_HOME)"
  (cd "$ROOT_DIR" && NDK_HOME="$ANDROID_NDK_HOME" bash ./compile-tun2socks.sh)
  copy_root_libs_to_jni
  info "tun2socks/hev 构建并复制完成"
}

# Hysteria2 构建（Go + CGO + NDK clang），生成 libhysteria2.so 并复制到 jniLibs
build_hysteria2() {
  check_ndk
  ensure_dirs
  if ! command -v go >/dev/null 2>&1; then
    err "Go 未安装，无法构建 Hysteria2。"
    exit 1
  fi

  info "构建 Hysteria2 (NDK=$ANDROID_NDK_HOME, HOST_TAG=$NDK_HOST_TAG)"

  local targets=(
    "aarch64-linux-android21 arm64 arm64-v8a"
    "armv7a-linux-androideabi21 arm armeabi-v7a"
    "x86_64-linux-android21 amd64 x86_64"
    "i686-linux-android21 386 x86"
  )

  pushd "$HY2_DIR" >/dev/null
  mkdir -p "$ROOT_LIBS_DIR"
  for target in "${targets[@]}"; do
    IFS=' ' read -r triple goarch abi <<< "$target"
    # 仅构建选定 ABIs
    if [[ ! " ${ABIS[*]} " =~ " ${abi} " ]]; then
      continue
    fi

    local clang="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$NDK_HOST_TAG/bin/$triple-clang"
    if [[ ! -x "$clang" ]]; then
      err "未找到 clang: $clang"
      exit 1
    fi

    info "ABI=$abi 使用 CC=$clang GOARCH=$goarch"
    mkdir -p "libs/$abi"
    CC="$clang" CGO_ENABLED=1 GOOS=android GOARCH="$goarch" \
      GOPROXY="${GOPROXY:-https://goproxy.cn,direct}" \
      go build -o "libs/$abi/libhysteria2.so" -trimpath -ldflags "-s -w -buildid=" -buildvcs=false ./app

    # 复制到根 libs 以便统一复制到 jniLibs
    mkdir -p "$ROOT_LIBS_DIR/$abi"
    cp -f "libs/$abi/libhysteria2.so" "$ROOT_LIBS_DIR/$abi/"
  done
  popd >/dev/null

  copy_root_libs_to_jni
  info "Hysteria2 构建并复制完成"
}

clean_optional() {
  info "清理 jniLibs 中可选组件 (.so)"
  for abi in "${ABIS[@]}"; do
    if [[ -d "$APP_JNILIBS_DIR/$abi" ]]; then
      rm -f "$APP_JNILIBS_DIR/$abi/libtun2socks.so" || true
      rm -f "$APP_JNILIBS_DIR/$abi/libhev-socks5-tunnel.so" || true
      rm -f "$APP_JNILIBS_DIR/$abi/libhysteria2.so" || true
    fi
  done
  info "清理完成"
}

cmd="${1:-}" || true
case "$cmd" in
  tun)
    build_tun_hev
    ;;
  hy2)
    build_hysteria2
    ;;
  all)
    build_tun_hev
    build_hysteria2
    ;;
  clean)
    clean_optional
    ;;
  *)
    echo "用法: $0 {tun|hy2|all|clean}"
    echo "环境: ANDROID_HOME, ANDROID_NDK_HOME 可选；ABIS 可选 (默认: ${ABIS_DEFAULT[*]})"
    exit 2
    ;;
esac