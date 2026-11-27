#!/usr/bin/env bash
# Jalankan Straik Cam di emulator Android buat lihat perubahan dengan cepat.
#
#   scripts/emu.sh          build + pasang + buka aplikasi (emulator dinyalakan kalau belum)
#   scripts/emu.sh run      pasang + buka tanpa build ulang
#   scripts/emu.sh log      lihat logcat aplikasi saja
#   scripts/emu.sh stop     matikan emulator
#
# Variabel opsional: AVD (default hp-dev), CAM (default emulated; isi webcam0 untuk kamera laptop).
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
EMU="$SDK/emulator/emulator"
AVD="${AVD:-hp-dev}"
CAM="${CAM:-emulated}"
PKG="id.straikcam"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
MODEL_URL="https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

emu_running() { "$ADB" devices | grep -q '^emulator-.*device$'; }

start_emu() {
  if emu_running; then return; fi
  echo ">> Nyalain emulator $AVD (kamera: $CAM, mic laptop aktif)…"
  nohup "$EMU" -avd "$AVD" -camera-back "$CAM" -allow-host-audio -memory 4096 \
    -no-boot-anim >/tmp/straik-emu.log 2>&1 &
  "$ADB" wait-for-device
  until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
  echo ">> Emulator siap."
}

ensure_model() {
  local dir="$ROOT/app/src/main/assets/model"
  if [ -f "$dir/README" ]; then return; fi
  echo ">> Model Vosk belum ada, unduh dulu…"
  local tmp; tmp="$(mktemp -d)"
  curl -fsSL "$MODEL_URL" -o "$tmp/model.zip"
  unzip -q "$tmp/model.zip" -d "$tmp"
  mkdir -p "$dir"
  cp -r "$tmp"/vosk-model-small-en-us-0.15/* "$dir/"
  rm -rf "$tmp"
}

install_run() {
  echo ">> Pasang APK…"
  "$ADB" install -r -d -g "$APK"
  "$ADB" shell am force-stop "$PKG"
  "$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  echo ">> Straik Cam terbuka di emulator."
}

case "${1:-}" in
  stop) "$ADB" emu kill ;;
  log)  "$ADB" logcat --pid="$("$ADB" shell pidof -s "$PKG" | tr -d '\r')" ;;
  run)  start_emu; install_run ;;
  *)
    start_emu &
    ensure_model
    (cd "$ROOT" && ./gradlew assembleDebug -q)
    wait
    install_run
    ;;
esac
