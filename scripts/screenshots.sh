#!/bin/sh
# Play screenshots: installs the debug app and the screenshot tests on the
# running emulator, prepares it (SystemUI demo status bar at 9:41, clean app
# data, per-app locale), runs ScreenshotTests in demo mode (the bundled seed
# with fixed statuses, see app/src/main/kotlin/…/data/DemoMode.kt), pulls the
# shots and pillarboxes them to the 9:16 Play requires.
#
#   scripts/screenshots.sh [--lang de,en]     # default: de (the Play listing)
#
# Needs one running emulator or device (adb), Android 13+ for the per-app
# locale — the shots were made on the Pixel 4 profile (1080 x 2280) — and
# ImageMagick (magick or convert). The GitHub workflow
# (.github/workflows/screenshots.yml) runs exactly this on an emulator.
#
# Output: play/screenshots/*.png for German, play/screenshots/<lang>/*.png
# for any other language; 1080 x 1920, the native shot scaled to 1920 high
# and centred on the app's ground (read from the shot's top-left pixel, so
# dark shots get the dark ground). Raw shots in build/screenshots/<lang>/
# (gitignored).
set -eu

REPO="$(cd "$(dirname "$0")/.." && pwd)"
LANGUAGES="de"
while [ $# -gt 0 ]; do
  case "$1" in
    --lang) LANGUAGES="$(printf '%s' "$2" | tr ',' ' ')"; shift 2 ;;
    *) echo "usage: $0 [--lang de,en]" >&2; exit 2 ;;
  esac
done

APP=com.a11yland.Hissi
RUNNER=$APP.test/androidx.test.runner.AndroidJUnitRunner
SHOTS_ON_DEVICE=/sdcard/Android/data/$APP/files/screenshots
if command -v magick >/dev/null 2>&1; then IM=magick; else IM=convert; fi

region_of() { case "$1" in en) echo US ;; *) echo DE ;; esac; }

demo_status_bar() {   # enter | exit
  adb shell settings put global sysui_demo_allowed 1
  if [ "$1" = exit ]; then
    adb shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null
    return
  fi
  for command in \
    "-e command enter" \
    "-e command clock -e hhmm 0941" \
    "-e command battery -e level 100 -e plugged false" \
    "-e command network -e wifi show -e level 4 -e mobile show -e datatype none -e level 4" \
    "-e command notifications -e visible false"; do
    # shellcheck disable=SC2086
    adb shell am broadcast -a com.android.systemui.demo $command >/dev/null
  done
}

(cd "$REPO" && ./gradlew --quiet :app:installDebug :app:installDebugAndroidTest)
demo_status_bar enter
trap 'demo_status_bar exit' EXIT

for language in $LANGUAGES; do
  echo "== $language"
  raw="$REPO/build/screenshots/$language"
  if [ "$language" = de ]; then out="$REPO/play/screenshots"; else out="$REPO/play/screenshots/$language"; fi
  rm -rf "$raw"
  mkdir -p "$raw" "$out"

  # Clean state per language: the welcome motif needs a first launch.
  adb shell pm clear "$APP" >/dev/null
  adb shell cmd locale set-app-locales "$APP" --locales "$language-$(region_of "$language")"
  log="$raw/instrument.log"
  adb shell am instrument -w -e class com.a11yland.hissi.ScreenshotTests "$RUNNER" | tee "$log"
  grep -q "^OK (" "$log" || { echo "error: screenshot tests failed for $language" >&2; exit 1; }

  adb pull "$SHOTS_ON_DEVICE/." "$raw" >/dev/null
  for png in "$raw"/*.png; do
    [ -e "$png" ] || { echo "error: no screenshots were written for $language" >&2; exit 1; }
    ground="$($IM "$png" -format '%[pixel:p{0,0}]' info:)"
    $IM "$png" -resize x1920 -background "$ground" -gravity center -extent 1080x1920 \
      -strip "$out/$(basename "$png")"
  done
  echo "$(ls "$raw"/*.png | wc -l | tr -d ' ') shots in $out"
done
