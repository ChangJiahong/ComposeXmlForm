#!/usr/bin/env bash
#
# 把 iosApp 构建、安装并启动到「已连接的 iPhone 真机」上。
#
# 用法（Android Studio 里通过同名运行配置一键执行，也可直接在终端跑）：
#   ./scripts/run-ios-device.sh
#
# 可选环境变量：
#   DEVICE_UDID=xxx   指定设备（默认自动挑选第一台已连接的 iOS 真机）
#   BUILD_CONFIG=Debug|Release
#   SCHEME=iosApp
#   CONSOLE=1         启动后把设备控制台输出接到终端（Ctrl+C 断开，App 继续运行）
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
XCODE_PROJECT="$ROOT/iosApp/iosApp.xcodeproj"
SCHEME="${SCHEME:-iosApp}"
BUILD_CONFIG="${BUILD_CONFIG:-Debug}"
CONSOLE="${CONSOLE:-0}"

log()  { printf '\033[1;34m[iOS]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[提示]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[错误]\033[0m %s\n' "$*" >&2; exit 1; }

# Android Studio 里启动时可能没有 JAVA_HOME，而 Xcode 的构建阶段要调 Gradle
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
  [ -n "$JAVA_HOME" ] && export JAVA_HOME
fi

[ -d "$XCODE_PROJECT" ] || die "找不到 Xcode 工程：$XCODE_PROJECT"
command -v xcodebuild >/dev/null || die "找不到 xcodebuild，请安装 Xcode 并执行 xcode-select --switch"

# ---------------------------------------------------------------- 1. 选设备
# 取 xcodebuild 的 destination 列表：只要 platform:iOS（不含 Simulator）、且不是占位符
list_devices() {
  xcodebuild -project "$XCODE_PROJECT" -scheme "$SCHEME" -showdestinations 2>/dev/null \
    | awk '/platform:iOS,/ && !/platform:iOS Simulator/ && !/placeholder/ {
        id = ""; name = "";
        for (i = 1; i <= NF; i++) {
          if ($i ~ /^id:/)   { id = $i;   sub(/^id:/, "", id);     sub(/,$/, "", id) }
          if ($i ~ /^name:/) { name = $i; sub(/^name:/, "", name); sub(/,$/, "", name) }
        }
        if (id != "") print id "\t" name
      }'
}

if [ -n "${DEVICE_UDID:-}" ]; then
  UDID="$DEVICE_UDID"
  DEVICE_NAME="$(list_devices | awk -F'\t' -v u="$UDID" '$1 == u { print $2 }')"
  DEVICE_NAME="${DEVICE_NAME:-指定设备}"
else
  DEVICES="$(list_devices)"
  [ -n "$DEVICES" ] || die "没有检测到 iOS 真机。请用数据线连接 iPhone、解锁并在「信任此电脑」弹窗上点信任。"
  UDID="$(printf '%s\n' "$DEVICES" | head -n 1 | cut -f1)"
  DEVICE_NAME="$(printf '%s\n' "$DEVICES" | head -n 1 | cut -f2)"
  COUNT="$(printf '%s\n' "$DEVICES" | wc -l | tr -d ' ')"
  [ "$COUNT" -gt 1 ] && warn "检测到 $COUNT 台设备，使用第一台（可用 DEVICE_UDID 指定）"
fi

echo
log "目标设备：$DEVICE_NAME  ($UDID)"
log "构建配置：$SCHEME / $BUILD_CONFIG"
echo

# ---------------------------------------------------------------- 2. 构建
# Xcode 构建阶段会调用 ./gradlew :shared:embedAndSignAppleFrameworkForXcode 生成 Kotlin 框架
log "编译中（首次或改过 Kotlin 代码时会慢一些）..."
xcodebuild \
  -project "$XCODE_PROJECT" \
  -scheme "$SCHEME" \
  -configuration "$BUILD_CONFIG" \
  -destination "id=$UDID" \
  -allowProvisioningUpdates -allowProvisioningDeviceRegistration \
  -quiet \
  build

SETTINGS="$(xcodebuild -project "$XCODE_PROJECT" -scheme "$SCHEME" -configuration "$BUILD_CONFIG" \
  -destination "id=$UDID" -showBuildSettings 2>/dev/null)"

setting() { printf '%s\n' "$SETTINGS" | awk -F' = ' -v k="$1" '$1 ~ ("^ *" k " *$") { print $2; exit }'; }

TARGET_BUILD_DIR="$(setting TARGET_BUILD_DIR)"
APP_NAME="$(setting FULL_PRODUCT_NAME)"
BUNDLE_ID="$(setting PRODUCT_BUNDLE_IDENTIFIER)"
[ -n "$TARGET_BUILD_DIR" ] && [ -n "$APP_NAME" ] || die "无法从 xcodebuild 读取产物路径"
APP_PATH="$TARGET_BUILD_DIR/$APP_NAME"
[ -d "$APP_PATH" ] || die "构建产物不存在：$APP_PATH"

log "产物：$APP_PATH"

# ---------------------------------------------------------------- 3. 安装 + 启动
log "安装到设备..."
xcrun devicectl device install app --device "$UDID" "$APP_PATH" \
  || die "安装失败。常见原因：iPhone 处于锁屏状态、未信任此电脑，或描述文件过期（免费账号 7 天）。"

log "启动 $BUNDLE_ID ..."
if [ "$CONSOLE" = "1" ]; then
  exec xcrun devicectl device process launch --device "$UDID" \
    --terminate-existing --console "$BUNDLE_ID"
else
  xcrun devicectl device process launch --device "$UDID" \
    --terminate-existing "$BUNDLE_ID"
  echo
  log "已启动，去手机上看看。需要看日志时用：CONSOLE=1 ./scripts/run-ios-device.sh"
fi
