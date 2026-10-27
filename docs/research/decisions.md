# Часть 7. Гибридный движок воспроизведения (дополнение от 2026-10-12)

Цель проекта уточнена: не «клиент на libVLC», а **клиент, который воспроизводит всё возможное без серверного транскода и с корректным HDR/Dolby Vision**. Полные отчёты: [media3.md](media3.md), [engines.md](engines.md), [device-xiaomi.md](device-xiaomi.md); сырые дампы тестового ТВ — [tv-dump/](tv-dump/).

## 7.1 Принятые решения

| Вопрос | Решение |
|---|---|
| Движок | Гибрид: **media3/ExoPlayer — основной** (аппаратный MediaCodec, HDR10/HDR10+/HLG/DV, passthrough), **libVLC 3.7.7 — запасной** (программный ffmpeg для того, что не тянет железо/media3). mpv и libVLC 4 — возможные будущие модули. |
| Расширяемость | Свой узкий интерфейс `PlaybackEngine` + capability-интерфейсы (AudioDelay, SubtitleDelay, Passthrough, SubtitleStyle…); каждый движок — отдельный Gradle-модуль, регистрируется через Hilt `@IntoSet`; один адаптер `SimpleBasePlayer` в ядре для MediaSession/UI. Треки адресуются индексами Jellyfin `MediaStream`. |
| Выбор движка | Автоматический, **до** `PlaybackInfo`: данные файла (контейнер, кодек, профиль, битность, `VideoRangeType`, DV-профиль, субтитры, аудио) × возможности устройства (`MediaCodecList`, HDR-типы дисплея, `AudioCapabilities`, `RendererCapabilities` media3). Порядок: ручной выбор → media3 Direct → вариант с наименьшими потерями (HDR важнее всего) → серверный транскод. DeviceProfile — свой для каждого движка. |
| Runtime-fallback | Как в Plezy: при ошибке декодера / зависании / 0 кадров — один переход на другой движок с сохранением позиции и треков; если упал и он — транскод. Ни один Jellyfin-клиент этого не делает — это ключевое отличие проекта. |
| Сервер / SDK | Только Jellyfin 12.x, `jellyfin-sdk-kotlin` 1.9.x. |
| Платформа | Сначала Android TV, общие модули для будущего phone-UI. |
| DI / лицензия | Hilt (позже заменён на Koin); GPL-3.0-or-later; собственное название без Jellyfin-бренда. |
| Сборки | `armeabi-v7a` обязателен (тестовый ТВ — 32-bit userspace), сплиты по ABI; flavors `full` (Exo+VLC) / `lite` (только Exo). Dynamic Feature Delivery не подходит (sideload/F-Droid). |

## 7.2 media3: что играет и что нет (кратко)

- **Да:** HDR10/HDR10+/HLG, DV в MKV и MP4 (`dvcC`/`dvvC`), passthrough AC3/E-AC3/JOC/TrueHD/DTS/DTS-HD, ASS через `io.github.peerless2012:ass-media` (libass), PGS/VobSub/DVB внутри MKV, AVI. Вывод только через `SurfaceView` (Compose `PlayerSurface` с `SURFACE_TYPE_SURFACE_VIEW`).
- **Нет (→ libVLC):** M2TS, ASF/WMV, ISO/VIDEO_TS/BDMV, MKV с zlib, ordered chapters; H.264 Hi10P; VC-1/MPEG-2/MPEG-4 ASP без HW-декодера; TrueHD/PGS/VC-1 в TS; внешние `.sup`/`.idx`. FFmpeg-расширение media3 — только аудио (`org.jellyfin.media3:media3-ffmpeg-decoder`).
- **Риски:** DV P7 без DV-декодера не имеет фолбэка на HEVC (нужен свой `MediaCodecSelector` или `DOVI_WITH_EL` как неподдерживаемый); DV P8.1+HDR10+ виснет на MediaTek (#1895); падения HEVC-декодера MTK посреди потока не ловятся `setEnableDecoderFallback`; смену частоты/HDR-режима дисплея делать самим (`preferredDisplayModeId` + ожидание `onDisplayChanged`); все PGS-дорожки декодируются даже невыбранные (#2667, память). Tunneling — опция, по умолчанию выкл.
- jellyfin-androidtv объявляет в direct play ASF/WMV/VOB, которые media3 не открывает, — вероятный источник ошибок официального клиента.

## 7.3 libVLC vs libmpv как запасной

libVLC 3.7.7 выбран: официальный артефакт VideoLAN с регулярными релизами, стабильный Java API, libass/PGS/VobSub внутри, passthrough, выравнивание 16 KB есть. libmpv (`dev.jdtech.mpv:libmpv`) меньше (≈25 МБ против 55 МБ несжатых `.so` на arm64), но артефакт неофициальный, а HDR в стоковом mpv-android уходит в SDR через GLES. Для VLC при `useLegacyPackaging=true` размер ≈ +24 МБ.

## 7.4 Тестовый ТВ: Xiaomi TV S Pro Mini LED 75 (снято через adb 2026-10-12)

| Параметр | Значение |
|---|---|
| Модель / ОС | `MiTV-MOEU1` (`lalaland`), Android 14 / API 34, `leanback_only` |
| SoC | MediaTek **MT9689** (`ro.hardware=mt5896`, платформа `merak`) |
| ABI | **только `armeabi-v7a`** (32-bit userspace) |
| RAM | 2,4 ГБ видно системе; `MemAvailable` ≈ 870 МБ (MemFree ≈ 60–240 МБ вводит в заблуждение — остальное кэш), swap 600 МБ почти занят — бюджет памяти умеренно жёсткий; хранилище 22 ГБ свободно |
| UI / дисплей | приложения рисуются в 1920×1080 (320 dpi), видеоплоскость 4K; Android видит **один режим 3840×2160@60** — переключение частоты через Android API недоступно; есть Xiaomi-настройка `mitv_vrr_frame_rate_enable_23` (не исследовано) |
| HDR дисплея | DV, HDR10, HLG, HDR10+ |
| HW-видео (`media_codecs-mt5896.xml`) | AVC/HEVC/VP9/AV1 до 8K; MPEG-2, VC-1, WMV3, MPEG-4, H.263, RealVideo, AVS/AVS2 до 1080p; DV-декодеры `dvav.per/pen`, `dvhe.der/den/dtr/stn/dth/dtb/st`, `dvav.se`, `dvav1` — т.е. профили 1–10, **включая P7 (`dvhe.dtb`)** |
| Аудио | Dolby MS12; выходы заявляют AC3, E-AC3, E-AC3-JOC, TrueHD, DTS, DTS-HD; сейчас активен только `speaker` |

Расхождения с веб-исследованием: исследование по открытым источникам предполагало отсутствие P7 и VC-1 в железе — дамп устройства это опровергает (наличие в XML ≠ гарантированная работа через MediaCodecList; проверить в прототипе).

Известные проблемы этого семейства (проверить на устройстве): jellyfin-androidtv #5825 — на Xiaomi S Pro (кит. HyperOS) media3-клиенты не включают DV, а Just Player включает; stutter `c2.mtk.hevc.decoder` (media3 #3347); вечная буферизация при AC-3 passthrough после быстрых seek на MTK audio HAL (#3407); искажённый E-AC3 passthrough на Xiaomi (jellyfin #5366); MTK DV-декодеру нужен 4-байтный start code для NAL 62/63 (квирк Kodi).

## 7.5 Что проверить первым прототипом

1. DV P5, P8.1, P7 (MEL/FEL), P8.1+HDR10+ через media3 — включается ли DV-режим ТВ (кейс #5825).
2. HDR10 / HDR10+ / HLG; возврат дисплея из HDR после выхода.
3. 24p-контент: есть ли judder при фиксированных 60 Гц; что делает `Surface.setFrameRate`.
4. VC-1 и MPEG-2 через media3+MediaCodec vs libVLC; H.264 Hi10P через libVLC (хватит ли CPU).
5. ASS со шрифтами (ass-media vs libVLC), PGS, VobSub.
6. Звук: динамики ТВ и (если есть) eARC — passthrough E-AC3/JOC, TrueHD, DTS-HD; быстрые seek при passthrough.
7. M2TS / WMV / ISO через libVLC; runtime-переход Exo → VLC при сбое.
8. Потребление памяти обоих движков на 2,4 ГБ RAM.
