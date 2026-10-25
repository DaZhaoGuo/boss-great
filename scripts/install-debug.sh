#!/usr/bin/env bash
# Incremental debug APK build + reinstall for boss-greet.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export ANDROID_HOME="${ANDROID_HOME:-/Users/darrenguo/Library/Android/sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

GRADLE=(./gradlew)
if [[ ! -x ./gradlew ]]; then
  GRADLE=(
    /Users/darrenguo/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle
  )
fi

RUN_TESTS=0
WAIT_SEC=120
while [[ $# -gt 0 ]]; do
  case "$1" in
    --test) RUN_TESTS=1; shift ;;
    --wait) WAIT_SEC="${2:?}"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

has_device() {
  adb devices | awk 'NR>1 && $2=="device"{found=1} END{exit !found}'
}

if ! has_device; then
  echo "waiting for device (${WAIT_SEC}s)..."
  end=$((SECONDS + WAIT_SEC))
  while ! has_device; do
    if (( SECONDS >= end )); then
      echo "no device within ${WAIT_SEC}s" >&2
      exit 1
    fi
    sleep 1
  done
fi

TASKS=(:app:assembleDebug)
if [[ "$RUN_TESTS" -eq 1 ]]; then
  TASKS=(:app:testDebugUnitTest :app:assembleDebug)
fi

echo "gradle ${TASKS[*]}"
"${GRADLE[@]}" "${TASKS[@]}" --quiet

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
echo "adb install -r $APK"
adb install -r "$APK"
echo "installed $(date '+%H:%M:%S')"
