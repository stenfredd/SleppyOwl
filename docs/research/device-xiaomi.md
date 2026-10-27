# Xiaomi TV S Pro Mini LED 75: что важно знать для Jellyfin-клиента (media3 + libVLC/libmpv)

Дата исследования: 2026-10-12. Пометка **(не проверено)** означает, что в первоисточнике факт не подтверждён: это вывод по аналогии, данные агрегатора или сообщение одного пользователя.

---

## 0. Главное: под одним названием продаются три разных устройства

| Вариант | Где продаётся | ОС | SoC | RAM/ROM | Комментарий |
|---|---|---|---|---|---|
| **Xiaomi TV S Pro Mini LED 75 2026** (глобальная, RU: артикул вида L75MB-S**RU**/-SME) | Глобальный и российский рынок, с конца 2025 / начала 2026 | **Google TV на Android TV 14** | Quad Cortex-A73 + **Mali-G52 (2EE) MC1**, по классу это MediaTek Pentonic 700 (вероятно MT9653) **(модель чипа не проверена)** | 3 / 32 ГБ | Наиболее вероятный тестовый девайс, если ТВ куплен в РФ/ЕС с Google TV |
| **Xiaomi TV S Mini LED 75 2025** (без «Pro», глобальная) | ЕС/РФ с конца 2024 | Google TV | Тот же Quad A73 + Mali-G52 MC1 | 3 / 32 ГБ | Платформа та же. На 4PDA модель 2026 называют «урезанной китайской S 2025» |
| **Xiaomi TV S Pro 75 2025/2026, китайская версия** | Китай (бывает в серых поставках) | **HyperOS 2/3** (Xiaomi TV OS, без GMS) | **MediaTek MT9655 (Pentonic 800)** + сопроцессор Xiaomi XM9000 | 4 / 64 ГБ | 240–330 Гц, до 5200 нит. Отдельный issue Jellyfin про DV (см. §3) |

**Что сделать сразу:** определить вариант по `adb shell getprop ro.product.model`, `ro.board.platform`, `ro.hardware`, `ro.build.version.release` и `ro.product.device`. Список кодеков взять из `adb shell dumpsys media.player` или `/vendor/etc/media_codecs*.xml`, плюс посмотреть `dumpsys audio` и `dumpsys display`. Дальше в отчёте основной вариант — глобальная модель на Google TV, особенности китайской указаны отдельно.

---

## 1. Характеристики (глобальная S Pro Mini LED 75 2026)

Источник: официальная страница mi.com/global, проверено загрузкой страницы.

- **CPU/GPU:** Quad Cortex-A73, Mali-G52 (2EE) MC1. **RAM 3 ГБ, ROM 32 ГБ.**
  - По таблице Wikipedia сочетание A73 @1.4 ГГц + Mali-G52 MC1 соответствует **Pentonic 700 (MT9618 / MT9653 / MT9689)**. Pentonic 800 (MT9655) использует уже Mali-G57.
  - Clubic называет для S Mini LED 2025 чип **MT9653** **(не проверено официально)**.
  - Похожее сочетание есть у MT9613 (A73 1.3 ГГц, G52 MC1), но у него максимум 120 Гц, а у Pentonic 700 VRR до 144 Гц. Поэтому MT9653 вероятнее.
- **ОС:** Google TV, Android TV 14 (обзоры Onliner и FormulaTV на 55" 2026, модель L55MB-SRU). Это Android 14, API 34.
- **Панель:** QD-Mini LED VA, 4K, 704 зоны (75"), пиковая яркость 1700 нит. Xiaomi заявляет 144 Гц, Onliner пишет о **нативных 120 Гц** (144 Гц доступны в игровом режиме / HDMI enhanced). Game Boost даёт 288 Гц ценой падения разрешения до 1440p и глубины цвета до 8 бит. MEMC 4K120. VRR (FreeSync Premium), ALLM.
- **HDR:** Dolby Vision, HDR10+, HDR10, HLG, Filmmaker Mode.
  - Dolby Vision IQ и IMAX Enhanced официально указаны только у S Mini LED 2025. У 2026 DV IQ, по Onliner, нет.
  - Отзывы: в DV тёмные сцены теряют детали и выглядят темнее, чем в HDR10+ (Onliner, FormulaTV). Choose.tv о HDR тоже отзывается негативно. Can Buy or Not (индийская S Mini LED 2026) пишет, что на их образце DV не было вовсе. Индийская линейка, вероятно, на другой платформе **(не проверено)**.
- **Звук:** 2×15 Вт, Dolby Atmos, Harman AudioEFX (приходит через OTA).
  - На глобальной странице mi.com **DTS не указан**. В российских обзорах (Onliner, FormulaTV) и в обзоре AbsoluteGeeks упомянут **DTS:X**. Сам чип MediaTek поддерживает «DTS Virtual:X».
  - Вывод: декодер DTS в системе, скорее всего, есть, но это нужно проверить через `AudioManager`/`dumpsys audio` **(не проверено)**.
- **HDMI:** 3× HDMI 2.1 (CEC, ALLM, VRR). HDMI 1 и 2 поддерживают 4K144. **eARC на HDMI 2.** Третий порт без FRL (4K60, 8 бит). Есть оптический выход, 3.5 мм, USB 2.0 + USB 3.0, LAN, Wi-Fi 6, BT 5.2.
- **Netflix:** по Onliner, приложение не сертифицировано («эта версия несовместима с устройством»), FormulaTV пишет обратное. Это косвенный признак «сырой» сертификации прошивки.
- **Китайская версия (HyperOS):** MT9655, 4/64 ГБ, HyperOS 3.0, заявлен DTS:X. Источник — блог-обзоры, **(частично не проверено)**.

---

## 2. Аппаратные видеодекодеры

По данным MediaTek для MT9613 и таблицы Wikipedia для семейства MT96xx и Pentonic.

| Кодек | Поддержка | Комментарий |
|---|---|---|
| HEVC Main10 4K60 | Да | Основной путь. Имена декодеров на MTK TV: `OMX.MTK.VIDEO.DECODER.HEVC` или `c2.mtk.hevc.decoder` |
| AV1 4K60 10-bit | Да | Заявлен у всего семейства MT96xx и Pentonic |
| VP9 Profile 2 (10-bit) | Да | 4K60@10bit по спецификации. Поддержку HDR-профиля проверить в MediaCodecList |
| H.264 | Да | High 4K. Hi10P (10-bit H.264) аппаратно почти наверняка **нет** — нужен софт-фоллбэк **(не проверено)** |
| AVS2/AVS3 | Да | Для нас неважно |
| MPEG-2 | Почти наверняка да | ТВ с DVB-T2/C/S2 обязан декодировать MPEG-2. Выставлен ли он в MediaCodec, **(не проверено)** |
| VC-1 / WMV3 | Неизвестно | На MTK TV чипах исторически есть, но наружу в MediaCodec часто не выставляется **(не проверено)**. Предусмотреть софт (ffmpeg в libVLC/libmpv) |
| Dolby Vision | Да (декодер `video/dolby-vision`) | Имена у MTK: `OMX.MTK.VIDEO.DECODER.DVHE.STN` (видели на Fire TV на MTK) или `…DVHE.STH`. Профили 5 и 8.x — да. **Профиль 7 (dual-layer, FEL/MEL) аппаратно — скорее всего нет** (общая ситуация для Android TV, PR Jellyfin #5470) **(для этого SoC не проверено)** |

**Квирк MTK DV-декодера (Kodi).** Декодер `OMX.MTK…DVHE` ожидает 4-байтовые start code (`00 00 00 01`) для NAL-юнитов типов 62/63 (RPU и EL). Kodi включает «DoviWorkaround» в BitstreamConverter для `OMX.MTK*` + `video/dolby-vision` и для `OMX.MTK` задаёт invalid-PTS = −1 («use DTS»). Источник: патч quietvoid, вошёл в Kodi 20 через PR #19099. Для собственного пайплайна (не media3) это обязательно.

---

## 3. Известные проблемы на этом ТВ и SoC-семействе

### 3.1. Прямо про Xiaomi S Pro

- **jellyfin-androidtv #5825 (2026-09, открыт).** Xiaomi S Pro 2025 65", китайская, **HyperOS 3.0**. После обновления до HyperOS 3.0 Dolby Vision во встроенном плеере Jellyfin (ExoPlayer/media3) **не включается**: «неправильные цвета», т.е. сигнал идёт как HEVC/HDR10 или вообще без HDR. То же в Wholphin. А **Just Player** (тоже media3) и системный плеер Xiaomi включают DV на том же файле (WEB-DL DV P8 без HDR10+). Подтверждено вторым пользователем на beta2.
  - Вывод: на этой платформе ExoPlayer-путь Jellyfin неверно выбирает декодер или неверно определяет DV, а у Just Player это работает. Стоит изучить, как Just Player выбирает декодер `video/dolby-vision` (MediaCodecSelector / DV→HEVC fallback) и как сигнализирует HDR.
- **jellyfin-androidtv #5366.** «Xiaomi TV / Mi Box», Android 9: E-AC3 в passthrough звучит «как из коробки», Kodi же декодирует в stereo нормально. Закрыт без решения. Модель не указана, поэтому к нашему ТВ относится только косвенно.
- **4PDA (тема S Mini LED 2025):** со временем перестаёт работать MEMC и появляются подёргивания. **Проблемы со звуком по SPDIF при включённом MEMC** (вероятно, рассинхрон или задержка) **(не проверено, пересказ)**. Тема S Pro 2026: https://4pda.to/forum/index.php?showtopic=1115060 (сам 4PDA вернул 403, содержание взято из поисковых сниппетов).
- Onliner (55" 2025, глобальная): жалобы на зависания и проблемы с приложениями.

### 3.2. Семейство MediaTek Pentonic 700 / MT96xx (TCL, Hisense, Philips, Sony)

- **Kodi на Hisense U7K (MT9618, Pentonic 700):** в dev-сборке Kodi 21 DV-файлы давали чёрный экран при рабочем звуке, после ноябрьского nightly MP4-DV заиграл. На U8H предыдущего поколения DV через Kodi работал (forum.kodi.tv tid=374191). Там же сказано, что Jellyfin и Plex DV на U7K играют.
- **Hisense:** Kodi видит DV-дисплей, но при DV-воспроизведении ТВ показывает HDR10+. Значит, сигнализация HDR через MediaCodec не всегда соответствует ожиданиям.
- **DV P7:** по общему консенсусу Kodi-форума, Android-устройства P7 «как есть» не поддерживают. Варианты: режим совместимости Kodi (P7 → P8.1, EL отбрасывается, по качеству это примерно MEL) или фоллбэк на HDR10 base layer. FEL на встроенных ТВ-приложениях фактически недостижим.
- **Philips Android TV на MediaTek (jellyfin #5827, media3 #3421):** `OMX.MTK.VIDEO.DECODER.HEVC` **падает посреди потока** (vendor-internal error после успешной инициализации, при resume/seek в конкретный файл). `setEnableDecoderFallback` это не ловит, потому что работает только при ошибке init. Обходной путь: свой `MediaCodecSelector`, который исключает упавший декодер, затем re-prepare + seek, с переходом на `c2.android.hevc.decoder`. Jellyfin PR отклонил, issue в media3 открыт.
- **media3 #2711 / #2765:** MediaTek HEVC перестаёт выдавать кадры или выдаёт чёрный/замёрзший кадр после OS-апдейтов (это телефоны Dimensity, но драйверы того же вендора).
- **media3 #3347:** `c2.mtk.hevc.decoder` на Google TV Streamer (MT8696) отдаёт около 30% кадров с опозданием 30–130 мс на некоторых x265-файлах, отсюда постоянный stutter. **VLC на том же железе работает нормально.** Аргумент в пользу libVLC/libmpv как фоллбэка.
- **media3 #3407:** Sony Bravia (Android 12, **MediaTek audio HAL**), AC-3 passthrough без tunneling. После двух быстрых seek плеер навсегда остаётся в BUFFERING: позиция AudioTrack идёт назад и трактуется как 32-битный wrap. В tunneled-режиме (HW_AV_SYNC) не воспроизводится.
- **jellyfin #5028:** Philips (MediaTek) — E-AC3 7.1 при транскоде даёт slow-motion без звука, downmix в stereo помогает.
- **jellyfin #5093:** TCL C855 (MediaTek, Android 12) — регрессии в capability-репорте 0.19.x (DOVIInvalid, P8 с CCID 6, «range type not supported») вызывают лишний транскод. Урок: не отбрасывать «невалидные» DV-потоки, а давать фоллбэк на HDR10.
- **jellyfin #4021:** файлы DV + HDR10+ на многих Android TV дают чёрный экран. Нужна настройка «игнорировать HDR10+ в DV-файлах», как «Allowed HDR dynamic metadata formats» в Kodi. Для нашего ТВ (поддерживает и DV, и HDR10+) это менее критично, но правило полезно для универсальности.

### 3.3. Аудио passthrough на ТВ (встроенные приложения → eARC)

- По опыту TCL 2022/2023 (MediaTek) из XDA: из **внутренних приложений** ТВ отдаёт по eARC DD, DD+, DD+ Atmos (JOC) и AC-4, а **TrueHD — «no support»** (по сообщениям, конвертируется в DD+ без Atmos). DTS-HD MA и DTS:X на TCL 2022 тоже отмечены как «no support». Форумная формулировка: «нет ТВ на рынке, который выдаёт TrueHD из внутренних приложений».
- Для Xiaomi это **не проверено**, но ожидаемо то же самое: **TrueHD и DTS-HD MA bitstream из приложения, скорее всего, не пройдут**. Правильное поведение клиента — опрашивать `AudioManager` (`getDirectProfilesForAttributes` / `isDirectPlaybackSupported` на API 33+, `AudioCapabilities` в media3) и при отсутствии поддержки декодировать в PCM на клиенте (ffmpeg/libVLC: TrueHD → PCM 7.1 или EAC3-реэнкод), а не транскодировать на сервере.
- **DTS:** у глобальной модели в спецификации нет, в российских обзорах указан DTS:X **(не проверено)**. Опираться только на рантайм-проверку `ENCODING_DTS`/`ENCODING_DTS_HD` и иметь софтверный DTS-декодер (ffmpeg dca) как фоллбэк. Тренд на отказ от DTS подтверждается: LG убрал DTS из ТВ 2025 года, Google TV Streamer не умеет DTS-HD/TrueHD passthrough.
- Сценарий «Jellyfin/Kodi на внешнем боксе → HDMI → ТВ → eARC → AVR» пропускает TrueHD и DTS-HD. Для приложения на самом ТВ это не применимо.

---

## 4. Общие особенности MediaTek Android TV для media3 и libVLC/libmpv

1. **Dolby Vision через MediaCodec.**
   - Опрашивать `MediaCodecList` по `video/dolby-vision` с профилями: DvheStn = P5, DvheSt = P8. P7 на MTK обычно не объявлен.
   - При отсутствии P7: преобразовывать P7 → P8.1 на лету (выкинуть EL, переписать RPU; так делают Kodi, `DvCompatVideoRenderer` из PR #5470 и dovi_tool) или играть BL как HDR10.
   - Учесть, что media3 `MatroskaExtractor` может не распознать DV при «раздутом» CodecPrivate от MakeMKV (~800 байт). Фоллбэк — `videoRangeType` от сервера Jellyfin.
   - На этом ТВ (особенно HyperOS 3.0) проверить, какой декодер выбирает media3 и запускается ли DV-режим: см. #5825, Just Player справляется.
   - Для libVLC/libmpv: DV через mediacodec в libmpv (`hwdec=mediacodec`) зависит от передачи `video/dolby-vision`. libmpv обычно отдаёт HEVC, и получается HDR10 base layer. libVLC 4.x умеет DV через MediaCodec ограниченно **(не проверено)**. Реалистично DV только через media3, а libVLC/libmpv использовать для HDR10/HLG/SDR и экзотических кодеков.
2. **HDR-сигнализация.** На ТВ (в отличие от бокса) режим HDR включает видеопайплайн по метаданным декодера, переключения HDMI-режима нет. Нужен корректный `KEY_COLOR_TRANSFER`/`KEY_HDR_STATIC_INFO`, иначе картинка «блёклая». Для libmpv лучше `vo=mediacodec_embed` (прямой вывод в Surface с HDR-метаданными), а не gpu/gpu-next: GPU-рендер на Mali-G52 MC1 4K HDR не потянет, и HDR-режим ТВ не включится.
3. **Tunneled playback.** MTK TV декодеры обычно объявляют `FEATURE_TunneledPlayback` **(для этого SoC не проверено)**. Плюсы: точный A/V sync с HW_AV_SYNC, меньше рывков, в #3405 туннелирование помогло пропускной способности, баг #3407 в туннельном режиме не воспроизводится. Минусы: не все аудиоформаты (passthrough + tunneling на некоторых HAL ломается), не работают субтитры поверх с некоторыми рендерерами, ограничения seek. Нужен пользовательский переключатель и тест на устройстве.
4. **Смена частоты (AFR).**
   - Встроенная панель 120/144 Гц: «переключение режима HDMI» как на боксе не нужно, но ТВ должен перейти на кратную частоту (24p → 120 Гц, 5:5) без judder.
   - На Android 12+ есть системная опция «Match content frame rate» (Display & Sound → Advanced display) и API `Surface.setFrameRate` (media3: `setVideoChangeFrameRateStrategy`). Seamless-переход поддерживается, non-seamless требует `preferredDisplayModeId`.
   - Как Xiaomi реализует это на 144-герцовой панели и не сбивает ли MEMC, **(не проверено)**. Протестировать 23.976/25/50/59.94 и проверить `dumpsys display` (supportedModes) и `dumpsys SurfaceFlinger`.
5. **Слабое железо.** 3 ГБ RAM и Mali-G52 MC1: софт-декод 4K HEVC/AV1 невозможен. libVLC/libmpv использовать только с hwdec (mediacodec). ASS-субтитры (libass) на 4K-Surface — нагрузка на CPU, а overlay-рендер поверх HDR-видео может ломать HDR (#5576: PGS + DV/HDR вызывает ошибку). Высокие битрейты (UHD-ремуксы 80–100 Мбит/с) требуют большого буфера и быстрой сети.
   - **Проверить скорость Ethernet-порта:** у многих ТВ он 100 Мбит/с **(для этой модели не проверено)**. Для ремуксов UHD BD это критично, Wi-Fi 6 может оказаться быстрее.
6. **Ошибки декодера посреди потока** (MTK HEVC, #5827/#3421): реализовать runtime-fallback (исключить декодер → re-prepare → seek) и/или переход на libVLC/libmpv.
7. **AudioTrack passthrough на MTK HAL** (#3407): после быстрых seek возможна «вечная буферизация». Нужен watchdog: если плеер буферизуется дольше N секунд при полном буфере, делать re-seek или flush.
8. **E-AC3 passthrough** на некоторых Xiaomi/MTK звучит искажённо (#5366). Нужна настройка «декодировать EAC3 в PCM» на клиенте.

---

## 5. Чек-лист проверок на реальном ТВ

1. `adb shell getprop | grep -iE "model|platform|hardware|board|version.release|build.fingerprint"`: какая из трёх моделей и какой SoC.
2. `adb shell dumpsys media.player` и `cat /vendor/etc/media_codecs*.xml`: список декодеров, профили DV (dvhe.05/.07/.08?), VC-1, MPEG-2, H.264 Hi10, tunneling.
3. `adb shell dumpsys audio | grep -iA5 "encodings\|formats"` + `dumpsys media.audio_flinger`: какие форматы HAL поддерживает в direct/offload при подключённом eARC-ресивере. Проверить AC3/EAC3/EAC3-JOC/TrueHD/DTS/DTS-HD.
4. `adb shell dumpsys display | grep -iE "mode|hdr"`: supportedModes (60/120/144?), HdrCapabilities (DV, HDR10, HDR10+, HLG).
5. Тест-файлы: DV P5 (MP4), P8.1 (MKV WEB-DL), P7 MEL/FEL (MakeMKV), DV + HDR10+; HDR10+ AV1; VP9.2; VC-1 и MPEG-2 (BD/DVD ремуксы); H.264 Hi10P; TrueHD Atmos, DTS-HD MA, DTS:X, EAC3-JOC.
6. Сравнить media3 и Just Player на одном DV-файле: воспроизвести #5825.

---

## Источники

- Официальные спецификации: https://www.mi.com/global/product/xiaomi-tv-s-pro-mini-led-75-2026/specs/ , https://www.mi.com/global/product/xiaomi-tv-s-mini-led-75-2025/specs/ , FAQ https://www.mi.com/ph/support/faq/details/KA-483202/
- MediaTek MT9613: https://www.mediatek.com/products/pentonic/mt9613 ; таблица SoC: https://en.wikipedia.org/wiki/List_of_MediaTek_systems_on_chips
- Обзоры: https://tech.onliner.by/2026/02/12/xiaomi-s-pro-mini-led-2026 , https://mo.formulatv.ru/obzory/televizory/obzor-televizora-xiaomi-tv-s-2026.html , https://www.absolutegeeks.com/reviews/xiaomi-tv-s-pro-mini-led-75-2026-review-mini-led-mastery-unleashed/ , https://xiaomiforall.com/xiaomi-tv-s-pro-mini-led-2026-review/ (китайская: MT9655, 4/64, HyperOS 3), https://www.clubic.com/544037-xiaomi-s-mini-led-2025.html (MT9653), https://www.notebookcheck.net/2026-Xiaomi-TV-S-Pro-Mini-LED-series-goes-global-but-specs-fall-short-of-Chinese-models.1122730.0.html
- 4PDA: https://4pda.to/forum/index.php?showtopic=1115060 , https://4pda.to/forum/index.php?showtopic=1102774
- Jellyfin: https://github.com/jellyfin/jellyfin-androidtv/issues/5825 , /issues/5366 , /pull/5827 , /issues/5093 , /pull/5470 , /issues/5028 , /issues/4021
- media3: https://github.com/androidx/media/issues/3421 , /issues/3347 , /issues/3407 , /issues/3405 , /issues/2711
- Kodi: https://forum.kodi.tv/showthread.php?tid=374191 (U7K MT9618), https://gist.github.com/quietvoid/db7ac49bd8621a82f914888bca730c74 (MTK DV NAL quirk), https://github.com/xbmc/xbmc/pull/22950 (OMX.MTK.VIDEO.DECODER.DVHE.STN)
- Аудио: https://xdaforums.com/t/tcl-2023-google-tvs-p745-p755-c645-c745-c755-c805-c845-c855-c955-x955.4576005/page-36 , https://xdaforums.com/t/tcl-2022-google-tvs-p635-p735-c635-c735-c835-c935.4432635/page-41 , https://www.techradar.com/televisions/lg-removes-dts-audio-from-its-2025-tvs-heres-what-that-means-in-practice , https://www.aftvnews.com/google-tv-streamer-does-not-support-dolby-truehd-dts-hd-or-dtsx-audio-passthrough/
