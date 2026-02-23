#!/usr/bin/env sh
set -eu

# Builds jars for all supported Minecraft targets.
# Pass extra Gradle flags through, e.g.:
#   ./scripts/build-all-versions.sh --stacktrace
exec ./gradlew buildMc12110 buildMc12111 "$@"
