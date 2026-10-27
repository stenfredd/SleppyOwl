#!/bin/bash
# L0: проверка mpv на файлах медиатеки до начала ПК-версии (итоги в docs/desktop.md). Нужны: mpv 0.37 ($MPV037 или mpv в PATH), Flatpak io.mpv.Mpv (0.41), python3-xlib для float_test.py.
# Прогон всех файлов: 0.37 и 0.41 в vo=gpu (как render API), HDR/DV ещё в gpu-next 0.41, чересстрочные с деинтерлейсом.
# Список файлов — L0_CASES из local/dev.env (строки «<имя> <itemId> [опции run_case.py]»).
D=$(dirname "$0"); source "$D/../../devenv.sh"; CASES="$D/../../../${L0_CASES:?set L0_CASES in local/dev.env}"; OUT=${L0_OUT:-${XDG_RUNTIME_DIR:-/tmp}/l0/out}; mkdir -p $OUT; R=$OUT/results.jsonl
run() { timeout 150 python3 -I $D/run_case.py "$@" --play 15 >> $R || echo "{\"name\":\"$4\",\"ver\":\"$1\",\"vo\":\"$2\",\"error\":\"timeout\"}" >> $R; }
while read -r name id rest; do
  for ver in 037 041; do run $ver gpu $id $name $rest; done
  case $name in dv*|hlg*|hdr*|av1_4k*) run 041 gpu-next $id $name $rest;; esac
  case $name in dvd_ps_576i|h264_1080i50)
    run 037 gpu $id ${name}_deint --extra=--deinterlace=yes
    run 041 gpu $id ${name}_deint --extra=--deinterlace=yes
    run 041 gpu $id ${name}_deintauto --extra=--deinterlace=auto;;
  esac
done < "$CASES"
echo DONE
