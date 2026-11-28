#!/usr/bin/env bash
# Rebuilds src/main/extensions/gboard-patches.rve from the sources in extension/.
#
# The extension dex carries every runtime hook the patches call into, so the sources it is built
# from must stay in the repository. Prerequisites: Java 21+, the Android SDK, and the
# app.morphe.patches Gradle plugin 2.1.2 available to Gradle (see extension/README.md).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
EXT_DIR="${REPO_DIR}/extension"

if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    :
elif [ -x "${HOME}/.local/jdk-21/bin/java" ]; then
    export JAVA_HOME="${HOME}/.local/jdk-21"
elif command -v java >/dev/null 2>&1; then
    :
else
    echo "Error: Java 21+ is required but not found." >&2
    exit 1
fi

if [ ! -f "${EXT_DIR}/local.properties" ] &&
    [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
    echo "Error: the Android SDK is not configured." >&2
    echo "Set ANDROID_HOME, or write sdk.dir into extension/local.properties." >&2
    exit 1
fi

echo "=== Building Gboard extension dex ==="
"${EXT_DIR}/gradlew" -p "${EXT_DIR}" :extensions:extension:syncExtension --console=plain

BUILT="${EXT_DIR}/extensions/extension/build/morphe/extensions/gboard-patches.rve"
TARGET="${REPO_DIR}/src/main/extensions/gboard-patches.rve"
if [ ! -f "${BUILT}" ]; then
    echo "Error: ${BUILT} was not produced." >&2
    exit 1
fi

cp "${BUILT}" "${TARGET}"
echo "Wrote ${TARGET} ($(wc -c < "${TARGET}") bytes)"
