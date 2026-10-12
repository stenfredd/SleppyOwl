#!/bin/bash
# Helpers for driving the test TV over adb. Usage: source tools/tv/env.sh
# TV_ADB: адрес из «Беспроводной отладки» (порт меняется после перезагрузки ТВ).
export JAVA_HOME=${JAVA_HOME:-$HOME/.jdks/jdk-21.0.12.1+1}
TV_ADB=${TV_ADB:-192.168.1.20:34277}
A="$HOME/Android/Sdk/platform-tools/adb -s $TV_ADB"
OUT=${OUT:-/tmp/movies-tv}; mkdir -p "$OUT"
k() { for x in "$@"; do $A shell input keyevent "$x"; sleep 0.7; done; }
shot() { $A exec-out screencap -p > "$OUT/$1.png"; echo "$OUT/$1.png"; }
install_debug() { ./gradlew :app:assembleDebug -q && $A install -r app/build/outputs/apk/debug/app-debug.apk; }
# Открыть карточку (и сразу играть, если указан движок или play=1): play <itemId> [exo|vlc]
play() {
  if [ -n "$2" ]; then $A shell am start -S -n ru.stenfredd.movies/.MainActivity --es item "$1" --es engine "$2" >/dev/null
  else $A shell am start -S -n ru.stenfredd.movies/.MainActivity --es item "$1" --ez play true >/dev/null; fi
}
# Журнал решений сессии воспроизведения
plog() { $A logcat -d | grep -E " (I|W|E) PlaybackSession" | sed 's/^.*PlaybackSession: //' | cut -c1-300; }
# Токен с ТВ (только debug-сборка: run-as читает DataStore приложения)
tv_token() { $A exec-out run-as ru.stenfredd.movies cat files/datastore/jellyfin_session.preferences_pb | strings -n 30 | grep -oE '[0-9a-f]{32}' | head -1; }
# Что играет на ТВ по данным сервера: название, позиция (с), PlayMethod. X-Emby-Token сервер 12.x не принимает.
sess() {
  curl -s -H "Authorization: MediaBrowser Token=\"${TV_TOKEN:=$(tv_token)}\"" "http://192.168.1.10:8096/Sessions?activeWithinSeconds=60" |
    python3 -c 'import json,sys
for s in json.load(sys.stdin):
  n=s.get("NowPlayingItem")
  if n and s.get("Client","").startswith("Movies"): print(n["Name"], round(s["PlayState"].get("PositionTicks",0)/1e7,1), s["PlayState"].get("PlayMethod"), "paused" if s["PlayState"].get("IsPaused") else "")'
}
# Реально ли звук уходит на выход: потоки AudioFlinger, число записей и активные треки
aout() { $A shell dumpsys media.audio_flinger | grep -E "^Output thread|Total writes|Tracks of which"; }
# Журнал локального прокси VLC (диапазоны, кэш, сколько скачано с сервера)
vproxy() { $A logcat -d | grep "VLC proxy" | sed -E 's/^[0-9-]+ ([0-9:.]+).*VLC proxy/\1/' | cut -c1-170; }
