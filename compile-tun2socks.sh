#!/bin/bash

# 脚本用途：
# - 使用 Android NDK 一键编译两套 TUN 方案的本地库：
#   1) badvpn 的 tun2socks（生成可执行型 SO：libtun2socks.so，供子进程执行）
#   2) hev-socks5-tunnel（生成 JNI 动态库：libhev-socks5-tunnel.so，供 System.loadLibrary 加载）
# - 编译完成后，会把不同 ABI 的产物拷贝到仓库根目录的 libs/ 下（arm64-v8a、armeabi-v7a、x86、x86_64）。
#
# 使用前置条件：
# - 设置环境变量 NDK_HOME 指向 Android NDK 根目录；仓库包含 badvpn/、libancillary/、hev-socks5-tunnel/ 源码。
# - 若要让 APK 自动打包这些本地库，请将 libs/ 放到 v2plus/app/ 或调整 app 的 jniLibs.srcDirs 指向仓库根 libs/。

# 开启严格模式：
# - errexit：任何命令失败立即退出；
# - pipefail：管道中任一命令失败都视为失败；
# - nounset：访问未定义变量时报错退出。
set -o errexit
set -o pipefail
set -o nounset

# 计算当前脚本路径相关变量，保证在任意工作目录都能定位到相关文件。
# Set magic variables for current file & dir
__dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
__file="${__dir}/$(basename "${BASH_SOURCE[0]}")"
__base="$(basename ${__file} .sh)"

# 校验 NDK 环境：若未设置或路径不存在，则给出引导提示并退出。
if [[ ! -d $NDK_HOME ]]; then
	echo "Android NDK: NDK_HOME not found. please set env \$NDK_HOME"
	exit 1
fi

# 为 badvpn/tun2socks 构建创建一个临时目录；退出或出错时清理。
TMPDIR=$(mktemp -d)
clear_tmp () {
  rm -rf $TMPDIR
}
# 捕获 ERR/INT：打印错误信息、执行清理并退出。
trap 'echo -e "Aborted, error $? in command: $BASH_COMMAND"; trap ERR; clear_tmp; exit 1' ERR INT

# 将自定义 NDK 构建脚本复制到临时目录中。
install -m644 $__dir/tun2socks.mk $TMPDIR/
# 切换到临时目录（pushd 会记录目录栈，便于稍后 popd 返回）。
pushd $TMPDIR

# 为 ndk-build 构建创建到源代码的符号链接：
# - badvpn：badvpn 源码根（里面含 tun2socks、lwIP 等）
# - libancillary：用于跨进程传递文件描述符的静态库源码
ln -s $__dir/badvpn badvpn
ln -s $__dir/libancillary libancillary

# 调用 ndk-build 构建 badvpn 的 tun2socks（libtun2socks.so）：
# - NDK_PROJECT_PATH=.      使用当前目录作为 NDK 工程根（配合 APP_BUILD_SCRIPT）
# - APP_BUILD_SCRIPT=./tun2socks.mk 指定我们自定义的 Android.mk（含 libancillary 与 tun2socks 目标）
# - APP_ABI=all             为所有已知 ABI 构建（可替换为具体列表，如 "armeabi-v7a arm64-v8a x86 x86_64"）
# - APP_PLATFORM=android-21 最低平台 API（与 app minSdk 保持一致）
# - NDK_LIBS_OUT=$TMPDIR/libs 构建产物（.so）输出目录（按 ABI 分类）
# - NDK_OUT=$TMPDIR/tmp     中间文件输出目录（obj/、依赖缓存等）
# - -B -j4                  强制重构（-B），并行编译 4 线程（-j4）
$NDK_HOME/ndk-build \
	NDK_PROJECT_PATH=. \
	APP_BUILD_SCRIPT=./tun2socks.mk \
	APP_ABI=all \
	APP_PLATFORM=android-21 \
	NDK_LIBS_OUT=$TMPDIR/libs \
	NDK_OUT=$TMPDIR/tmp \
	APP_SHORT_COMMANDS=false LOCAL_SHORT_COMMANDS=false -B -j4

# 将 badvpn/tun2socks 的各 ABI 产物拷贝到仓库根目录的 libs/ 下（例如 libs/arm64-v8a/libtun2socks.so）。
cp -r $TMPDIR/libs $__dir/
# 返回原目录，并清理临时目录。
popd
rm -rf $TMPDIR

# ===========================
# 构建 hev-socks5-tunnel 动态库
# ===========================
#build hev-socks5-tunnel
HEVTUN_TMP=$(mktemp -d)
# 在脚本退出（EXIT）时自动清理 hev 临时目录。
trap 'rm -rf "$HEVTUN_TMP"' EXIT

# 创建 NDK 期望的 jni 目录结构，并切换到临时目录。
mkdir -p "$HEVTUN_TMP/jni"
pushd "$HEVTUN_TMP"

# 顶层 Android.mk：让 ndk-build 递归构建 jni/ 下的所有子目录。
echo 'include $(call all-subdir-makefiles)' > jni/Android.mk

# 将 hev-socks5-tunnel 源码链接到 jni/ 下（其自身包含 Android.mk / 或可被递归构建）。
ln -s "$__dir/hev-socks5-tunnel" jni/hev-socks5-tunnel

# 调用 ndk-build 构建 hev-socks5-tunnel（libhev-socks5-tunnel.so），参数含义如下：
# - APP_ABI：指定构建的目标 ABI 列表（常见四种架构）
# - APP_PLATFORM：最低平台 API
# - NDK_LIBS_OUT/NDK_OUT：产物与中间文件输出目录
# - APP_CFLAGS：优化与包名宏（供 JNI 使用路径 com/v2ray/ang/service）
# - APP_LDFLAGS：关闭 build-id、设置 hash 风格
"$NDK_HOME/ndk-build" \
    NDK_PROJECT_PATH=. \
    APP_BUILD_SCRIPT=jni/Android.mk \
	"APP_ABI=armeabi-v7a arm64-v8a x86 x86_64" \
	APP_PLATFORM=android-21 \
    NDK_LIBS_OUT="$HEVTUN_TMP/libs" \
    NDK_OUT="$HEVTUN_TMP/obj" \
    "APP_CFLAGS=-O3 -DPKGNAME=com/v2ray/ang/service" \
    "APP_LDFLAGS=-WI,--build-id=none -WI,--hash-style=gnu" \

# 将 hev 的各 ABI 产物复制到仓库根目录 libs/（例如 libs/arm64-v8a/libhev-socks5-tunnel.so）。
cp -r "$HEVTUN_TMP/libs/"* "$__dir/libs/"
popd

# 使用说明与产物路径提示：
# - 运行方式：在仓库根目录执行 `NDK_HOME=/path/to/android-ndk ./compile-tun2socks.sh`
# - 产物：仓库根 libs/<abi>/ 下会产生：
#   * libtun2socks.so（badvpn）—— 供 Tun2SocksService 通过 ProcessBuilder 以子进程运行
#   * libhev-socks5-tunnel.so（hev）—— 供 TProxyService 通过 System.loadLibrary("hev-socks5-tunnel") 加载
# - APK 打包：确保 app 的 Gradle jniLibs.srcDirs 指向包含这些 .so 的目录（默认 v2plus/app/libs）。
rm -rf "$HEVTUN_TMP"
