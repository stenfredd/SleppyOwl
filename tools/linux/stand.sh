#!/bin/bash
# Test stand of the desktop app (LINUX_PLAN.md §3 «Как проверяем»): a private virtual display :87 with the real
# window manager (Muffin), so window, mouse and keyboard tests do not touch the user's screen. Muffin and the app share
# the stand's own D-Bus session bus: the app's MPRIS player and screensaver calls never reach the user's desktop.
# mpv plays into no sound device (ao=null, unless SLEEPYOWL_MPV_OPTIONS says otherwise): the stand never touches the
# user's PipeWire — WirePlumber remembers a stream's mute by its role, and that would follow the user's own viewing.
#
#   stand.sh up                 start Xvfb :87 (1920×1080), the stand's D-Bus and Muffin, if not running
#   stand.sh down               stop the app, Muffin and Xvfb
#   stand.sh app [args]         (re)start the app on :87 in the background; output in $STAND/app.out
#   stand.sh stop               stop the app
#   stand.sh shot NAME          screenshot of the display → $STAND/shots/NAME.png
#   stand.sh in CMD…            input on :87 (tools/linux/xinput.py): click X Y, move X Y, key NAME, type TEXT, …
#   stand.sh log                the app's log (sleepyowl.log)
#   stand.sh bus CMD…           run CMD on the stand's D-Bus (busctl --user, gdbus, dbus-monitor…)
#
# The app's files live in $STAND (default ~/.cache/sleepyowl-test): its own config with the test account,
# cache, log and lock — the user's own ~/.config/sleepyowl is never used. Gradle builds before `app` unless
# DESKTOP_NOBUILD=1. SLEEPYOWL_APP=<launcher> runs that instead of the Gradle build, e.g. the packaged app unpacked by
# `dpkg-deb -x sleepyowl_*.deb DIR` → SLEEPYOWL_APP=DIR/opt/sleepyowl/bin/sleepyowl (also for k2.py).
set -e
R=$(cd "$(dirname "$0")/../.." && pwd)
STAND=${STAND:-$HOME/.cache/sleepyowl-test}
D=:87
export JAVA_HOME=${JAVA_HOME:-$HOME/.jdks/jdk-21.0.12.1+1}
mkdir -p "$STAND/shots" "$STAND/run"

running() { [ -f "$STAND/run/$1.pid" ] && kill -0 "$(cat "$STAND/run/$1.pid")" 2>/dev/null; }

up() {
  if ! running xvfb; then
    Xvfb $D -screen 0 1920x1080x24 -nolisten tcp >"$STAND/run/xvfb.out" 2>&1 &
    echo $! >"$STAND/run/xvfb.pid"
    for _ in $(seq 50); do xdpyinfo -display $D >/dev/null 2>&1 && break; sleep 0.1; done
  fi
  if ! running dbus; then
    dbus-daemon --session --fork --print-address=3 --print-pid=4 3>"$STAND/run/dbus.addr" 4>"$STAND/run/dbus.pid"
  fi
  if ! running muffin; then
    DISPLAY=$D DBUS_SESSION_BUS_ADDRESS=$(cat "$STAND/run/dbus.addr") setsid muffin --replace >"$STAND/run/muffin.out" 2>&1 &
    echo $! >"$STAND/run/muffin.pid"
    sleep 2
  fi
  echo "stand up on $D"
}

stop_app() {
  if running app; then
    kill "$(cat "$STAND/run/app.pid")" 2>/dev/null || true
    for _ in $(seq 50); do running app || break; sleep 0.1; done
    running app && kill -9 "$(cat "$STAND/run/app.pid")" 2>/dev/null || true
  fi
  rm -f "$STAND/run/app.pid"
}

case "$1" in
  up) up ;;
  down)
    stop_app
    for p in muffin dbus xvfb; do running $p && pkill -P "$(cat "$STAND/run/$p.pid")" 2>/dev/null; running $p && kill "$(cat "$STAND/run/$p.pid")"; rm -f "$STAND/run/$p.pid"; done
    echo "stand down" ;;
  app)
    shift
    up >/dev/null
    stop_app
    [ -n "$DESKTOP_NOBUILD$SLEEPYOWL_APP" ] || (cd "$R" && ./gradlew -q :desktop:classes :desktop:desktopClasspath)
    DISPLAY=$D DBUS_SESSION_BUS_ADDRESS=$(cat "$STAND/run/dbus.addr") XDG_CONFIG_HOME="$STAND/config" XDG_CACHE_HOME="$STAND/cache" XDG_STATE_HOME="$STAND/state" \
      SLEEPYOWL_RUNTIME_DIR="$STAND/run" SLEEPYOWL_MPV_OPTIONS="${SLEEPYOWL_MPV_OPTIONS:-ao=null}" DESKTOP_NOBUILD=1 "${SLEEPYOWL_APP:-$R/tools/linux/desktop.sh}" "$@" >"$STAND/app.out" 2>&1 &
    echo $! >"$STAND/run/app.pid"
    echo "app started, pid $(cat "$STAND/run/app.pid")" ;;
  stop) stop_app ;;
  shot)
    import -display $D -window root "$STAND/shots/$2.png"
    echo "$STAND/shots/$2.png" ;;
  in) shift; DISPLAY=$D python3 "$R/tools/linux/xinput.py" "$@" ;;
  log) cat "$STAND/state/sleepyowl/log/sleepyowl.log" ;;
  bus) shift; DBUS_SESSION_BUS_ADDRESS=$(cat "$STAND/run/dbus.addr") "$@" ;;
  *) sed -n '2,21p' "$0"; exit 1 ;;
esac
