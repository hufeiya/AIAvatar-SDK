#!/usr/bin/env bash
# 把仓库根目录 secrets.properties 的所有 API Key 写入 adb 设备上 demo app 的
# SharedPreferences(demo_settings.xml)。应用未安装的设备直接跳过,不做任何事。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
PROPS="$REPO_ROOT/secrets.properties"

PKG="com.neethu.aiavatar_sdk"
PREFS_NAME="demo_settings"
MAIN_ACTIVITY="$PKG/.MainActivity"
PREFS_PATH="/data/data/$PKG/shared_prefs/$PREFS_NAME.xml"

# secrets.properties 键 → SharedPreferences 键(正本 app/src/main/java/com/neethu/aiavatar_sdk/AiChat.kt)
MAPPING=(
  "SILICONFLOW_API_KEY:ai_api_key_siliconflow"
  "VOLCANO_ARK_API_KEY:ai_api_key_volcano"
  "VOLCANO_TTS_API_KEY:ai_api_key_volcano_tts"
  "OPENROUTER_API_KEY:ai_api_key_openrouter"
)

mask() {
  local v="$1"
  if [ "${#v}" -le 10 ]; then printf '%s***' "${v:0:3}"; else printf '%s…%s(长度%s)' "${v:0:6}" "${v: -4}" "${#v}"; fi
}

xml_escape() {
  printf '%s' "$1" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g'
}

# sed 替换串里 \ 和 & 是特殊字符,先转义
sed_escape() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/&/\\&/g'
}

upsert_string() {
  local file="$1" name="$2" value="$3"
  local esc; esc="$(xml_escape "$value")"
  local esc_sed; esc_sed="$(sed_escape "$esc")"
  if grep -q "<string name=\"$name\">" "$file"; then
    sed -i "s|<string name=\"$name\">[^<]*</string>|<string name=\"$name\">$esc_sed</string>|" "$file"
  else
    sed -i "s|</map>|  <string name=\"$name\">$esc_sed</string>\n</map>|" "$file"
  fi
}

read_prop() { # 读 secrets.properties 里某键的值(取首个命中,容忍首尾空白);空输出=缺失
  grep -E "^$1[[:space:]]*=" "$PROPS" | head -1 | cut -d= -f2- | tr -d '\r' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

process_device() {
  local dev="$1" tag="[$dev]"

  # 应用未安装 → 什么都不做
  if ! adb -s "$dev" shell pm path "$PKG" 2>/dev/null | grep -q '^package:'; then
    echo "$tag 应用未安装,跳过(不做任何事)。"
    return 0
  fi

  # run-as 需要 debug 包
  if ! adb -s "$dev" shell run-as "$PKG" true 2>/dev/null; then
    echo "$tag ❌ run-as 不可用(装的是 release 包?)——无法写入 prefs,请安装 debug 变体。"
    return 1
  fi

  local tmp; tmp="$(mktemp -d)"
  local was_running=0
  if [ -n "$(adb -s "$dev" shell pidof "$PKG" 2>/dev/null | tr -d '[:space:]')" ]; then was_running=1; fi

  # app 存活时内存里的 prefs 会在下一次任意保存时整体覆盖磁盘,先杀再改
  adb -s "$dev" shell am force-stop "$PKG" >/dev/null 2>&1 || true

  if adb -s "$dev" shell run-as "$PKG" cat "$PREFS_PATH" 2>/dev/null | tr -d '\r' > "$tmp/settings.xml" \
    && [ -s "$tmp/settings.xml" ] && grep -q '<map' "$tmp/settings.xml"; then
    :
  else
    printf '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n</map>\n' > "$tmp/settings.xml"
    echo "$tag prefs 文件不存在,从空白模板创建。"
  fi

  local count=0 entry sp_key pref_key value
  for entry in "${MAPPING[@]}"; do
    sp_key="${entry%%:*}"; pref_key="${entry##*:}"
    value="$(read_prop "$sp_key")"
    if [ -z "$value" ]; then
      echo "$tag ⚠ $sp_key 在 secrets.properties 中缺失或为空,跳过(保留设备上现有值)。"
      continue
    fi
    upsert_string "$tmp/settings.xml" "$pref_key" "$value"
    echo "$tag ✔ $pref_key = $(mask "$value")"
    count=$((count + 1))
  done

  if [ "$count" -eq 0 ]; then
    echo "$tag 没有可写入的 key,设备未改动。"
    rm -rf "$tmp"; return 0
  fi

  # 写回:外层双引号 + 绝对路径(相对路径会被 adb 拆词落到 device shell 的 cwd=/ 报 can't create file)
  adb -s "$dev" shell "run-as $PKG sh -c 'cat > $PREFS_PATH'" < "$tmp/settings.xml"

  # 回读校验(固定串匹配,避免 API key 里的 . + 等字符干扰正则)
  local fail=0 esc
  for entry in "${MAPPING[@]}"; do
    sp_key="${entry%%:*}"; pref_key="${entry##*:}"
    value="$(read_prop "$sp_key")"
    [ -z "$value" ] && continue
    esc="$(xml_escape "$value")"
    if ! adb -s "$dev" shell run-as "$PKG" cat "$PREFS_PATH" 2>/dev/null | tr -d '\r' | grep -qF "<string name=\"$pref_key\">$esc</string>"; then
      echo "$tag ❌ 回读校验失败:$pref_key"
      fail=1
    fi
  done

  if [ "$fail" -eq 1 ]; then
    echo "$tag ❌ 写入未通过校验,请手动检查 $PREFS_PATH"
    rm -rf "$tmp"; return 1
  fi
  echo "$tag ✔ 全部 key 写入并回读校验通过($count 个)。"

  if [ "$was_running" -eq 1 ]; then
    adb -s "$dev" shell am start -n "$MAIN_ACTIVITY" >/dev/null 2>&1 || true
    echo "$tag 应用之前在运行,已重启使新 key 生效。"
  else
    echo "$tag 应用未运行,下次启动自动生效。"
  fi
  rm -rf "$tmp"
}

main() {
  if [ ! -f "$PROPS" ]; then
    echo "❌ 找不到 $PROPS"; exit 1
  fi
  local devices rc=0
  mapfile -t devices < <(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}')
  if [ "${#devices[@]}" -eq 0 ]; then
    echo "❌ 没有已连接且授权的 adb 设备。"; exit 1
  fi
  local dev
  for dev in "${devices[@]}"; do
    process_device "$dev" || rc=1
  done
  exit "$rc"
}

main "$@"
