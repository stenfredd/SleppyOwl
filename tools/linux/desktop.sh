#!/bin/bash
# SleepyOwl for the desktop without Gradle around the process: ./tools/linux/desktop.sh
# DESKTOP_NOBUILD=1 — do not build first. SLEEPYOWL_SCALE=1.25 — interface scale over the setting «Масштаб интерфейса».
# Files go to the XDG places (~/.config/sleepyowl, ~/.cache/sleepyowl, ~/.local/state/sleepyowl/log); the test stand
# (tools/linux/stand.sh) points them at its own directory.
set -e
R=$(cd "$(dirname "$0")/../.." && pwd)
export JAVA_HOME=${JAVA_HOME:-$HOME/.jdks/jdk-21.0.12.1+1}
[ -n "$DESKTOP_NOBUILD" ] || (cd "$R" && ./gradlew -q :desktop:classes :desktop:desktopClasspath)
exec "$JAVA_HOME/bin/java" --add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED --add-opens=java.desktop/sun.awt=ALL-UNNAMED \
  -cp "$(cat "$R/desktop/build/desktop.classpath")" ru.stenfredd.sleepyowl.desktop.MainKt "$@"
