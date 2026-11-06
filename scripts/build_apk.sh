#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if command -v gradle >/dev/null 2>&1; then
  gradle :app:testDebugUnitTest :app:assembleDebug --no-daemon
elif [ -f gradle/wrapper/gradle-wrapper.jar ]; then
  ./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon
else
  echo 'Android Studio / Gradle 8.9 / Android SDK 35 required.' >&2
  echo 'Alternatively upload this folder to a GitHub repository and run Actions > Build Android Debug APK.' >&2
  exit 2
fi
printf '\nAPK: %s\n' "$(pwd)/app/build/outputs/apk/debug/app-debug.apk"
