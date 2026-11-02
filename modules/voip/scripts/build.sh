#!/usr/bin/env bash
# Build the modules/voip APK.
# NOTE: the legacy call-bridge build.sh ran a manual aapt2/javac/d8/apksigner
# pipeline against the flat src/+res+AndroidManifest.xml layout. The module is
# now a standalone Gradle project, so this script is a thin wrapper.
set -euo pipefail
PROJ=$(cd "$(dirname "$0")/.." && pwd)
cd "$PROJ"
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
./gradlew :app:assembleDebug
ls -la app/build/outputs/apk/debug/
