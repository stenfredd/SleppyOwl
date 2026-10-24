#!/bin/bash
# Helpers for driving the test TV over adb. Usage: source tools/tv/env.sh
# TV_ADB: адрес из «Беспроводной отладки» (порт меняется после перезагрузки ТВ).
export JAVA_HOME=${JAVA_HOME:-$HOME/.jdks/jdk-21.0.12.1+1}
TV_ADB=${TV_ADB:-192.168.1.20:5555}
A="$HOME/Android/Sdk/platform-tools/adb -s $TV_ADB"
OUT=${OUT:-/tmp/movies-tv}; mkdir -p "$OUT"
k() { for x in "$@"; do $A shell input keyevent "$x"; sleep 0.7; done; }
shot() { $A exec-out screencap -p > "$OUT/$1.png"; echo "$OUT/$1.png"; }
install_debug() { ./gradlew :app:assembleDebug -q && $A install -r app/build/outputs/apk/debug/app-debug.apk; }
# Открыть карточку (и сразу играть, если указан движок или play=1): play <itemId> [exo|vlc]
play() {
  if [ -n "$2" ]; then $A shell am start -S -n ru.stenfredd.sleepyowl/.MainActivity --es item "$1" --es engine "$2" >/dev/null
  else $A shell am start -S -n ru.stenfredd.sleepyowl/.MainActivity --es item "$1" --ez play true >/dev/null; fi
}
# Журнал решений сессии воспроизведения
plog() { $A logcat -d | grep -E " (I|W|E) PlaybackSession" | sed 's/^.*PlaybackSession: //' | cut -c1-300; }
# Токен с ТВ (только debug-сборка: run-as читает DataStore приложения)
# (текущий аккаунт: первый в списке — последний использованный)
tv_token() { $A exec-out run-as ru.stenfredd.sleepyowl cat files/datastore/jellyfin_session.preferences_pb | grep -aoE '"token":"[0-9a-f]{32}"' | head -1 | grep -oE '[0-9a-f]{32}'; }
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
# Замер плавности интерфейса (только на release-сборке!): ui_bench [right|down] [N]
# Листает ряд (или экран) вперёд и назад N раз и печатает перцентили кадра и GPU, затем разбивку по фазам.
ui_bench() {
  local dir=${1:-right} n=${2:-8} back P=ru.stenfredd.sleepyowl
  [ "$dir" = right ] && back=left || back=up
  $A shell dumpsys gfxinfo $P reset >/dev/null
  for i in $(seq 1 $n); do $A shell input keyevent DPAD_${dir^^}; sleep 0.35; done
  for i in $(seq 1 $n); do $A shell input keyevent DPAD_${back^^}; sleep 0.35; done
  $A shell dumpsys gfxinfo $P | grep -E 'Janky frames:|50th percentile|90th percentile|99th percentile|50th gpu|90th gpu'
  $A shell dumpsys gfxinfo $P framestats | python3 tools/tv/framestats.py
}
# Release + сразу компиляция по baseline profile (иначе ТВ скомпилирует его только при фоновом обслуживании).
install_release() {
  ./gradlew :app:assembleRelease -q && $A install -r app/build/outputs/apk/release/app-release.apk || return
  $A shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE ru.stenfredd.sleepyowl/androidx.profileinstaller.ProfileInstallReceiver >/dev/null
  $A shell cmd package compile -f -m speed-profile ru.stenfredd.sleepyowl
}
# Снять baseline profile заново на ТВ (~8 мин, ТВ должен быть с выполненным входом) и положить в app/src/release.
# Gradle может закончить с «failing tests» при успешном прогоне — тогда профиль копируется вручную.
# PROFILE_SERIES — id сериала для прохода по странице сериала.
gen_profile() {
  ANDROID_SERIAL=$TV_ADB ./gradlew :app:generateReleaseBaselineProfile -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
    -Pandroid.testInstrumentationRunnerArguments.seriesId=${PROFILE_SERIES:-00000000000000000000000000000000}
  local src="baselineprofile/build/outputs/connected_android_test_additional_output/nonMinifiedRelease/connected"
  src=$(find "$src" -name 'BaselineProfileGenerator_generate-startup-prof.txt' | head -1)
  [ -n "$src" ] || { echo "профиль не найден"; return 1; }
  cp "$src" app/src/release/generated/baselineProfiles/baseline-prof.txt
  cp "$src" app/src/release/generated/baselineProfiles/startup-prof.txt
  echo "профиль: $(wc -l < "$src") правил"
}
