#!/bin/sh
# Offline-rebuilt project: official gradle-wrapper.jar is NOT bundled.
# This fallback uses an existing Gradle 8.9 executable. Generate the official
# wrapper later by running `gradle wrapper --gradle-version 8.9`.
if [ -f "$(dirname "$0")/gradle/wrapper/gradle-wrapper.jar" ]; then
  exec java -classpath "$(dirname "$0")/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
fi
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
echo 'Gradle 8.9 not found. See BUILD_APK.md or use GitHub Actions.' >&2
exit 127
