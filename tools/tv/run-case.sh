#!/bin/bash
# Прогон одного файла: tools/tv/run-case.sh <name> <itemId> [exo|vlc]; WAIT=секунды ожидания.
# Скриншот и журнал кладутся в $OUT (по умолчанию /tmp/movies-tv).
source "$(dirname "$0")/env.sh"
$A logcat -c
play "$2" "$3" </dev/null
sleep "${WAIT:-25}"
shot "case_$1" >/dev/null
plog | tee "$OUT/case_$1.log"
