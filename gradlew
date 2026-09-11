#!/usr/bin/env sh
set -eu
VERSION=9.3.1
BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DIST_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/githubk-distributions/gradle-$VERSION"
GRADLE_BIN="$DIST_DIR/bin/gradle"
if [ -x "$GRADLE_BIN" ]; then exec "$GRADLE_BIN" "$@"; fi
if [ -n "${GRADLE_HOME:-}" ] && [ -x "$GRADLE_HOME/bin/gradle" ]; then exec "$GRADLE_HOME/bin/gradle" "$@"; fi
if command -v gradle >/dev/null 2>&1; then
  SYS_VER=$(gradle --version 2>/dev/null | awk '/^Gradle / {print $2; exit}') || SYS_VER=""
  if [ "$SYS_VER" = "$VERSION" ]; then exec gradle "$@"; fi
fi
echo "GitHubK Studio: Gradle $VERSION is not installed. Open the IDE Environment Center first." >&2
exit 1
