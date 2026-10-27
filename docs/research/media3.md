# media3/ExoPlayer как основной движок Jellyfin-клиента для Android TV: что умеет, что нет

Дата: 2026-10-12. Версии: media3 **1.11.1** (2026-09-10), jellyfin-androidtv master `0032f4a` (2026-10-12), Jellyfin server 12.2, jellyfin-sdk-kotlin 1.9.2.

Метод: исходники читались напрямую (клоны `androidx/media` main `8c6678b` = 1.11.1, `jellyfin/jellyfin-androidtv` master, `jellyfin/jellyfin-androidx-media`, `peerless2012/libass-android`, `anilbeesetti/nextlib`, `moneytoo/Player` (Just Player), `xbmc/xbmc` raw). Issues/PR проверялись через GitHub API или загрузкой страниц. Всё, что не подтверждено кодом или первоисточником, помечено **(не проверено)**.

---

## 0. TL;DR

| Тема | Вывод |
|---|---|
| DV P5 / P8.x / P10 | Работает, если есть DV-декодер (`video/dolby-vision`) **и** дисплей сообщает `HDR_TYPE_DOLBY_VISION`. Без DV-дисплея media3 сам переключается на HEVC/AVC/AV1 для P4/P8/P9/P10 (кроме 10.0). Для P5 фолбэка нет. |
| DV P7 (FEL/MEL, BD-remux) | В media3 **нет фолбэка на HEVC** (`DvheDtb` исключён в `getAlternativeCodecMimeType`). FEL никогда не обрабатывается (только BL+RPU, если декодер SoC умеет). Фикс фолбэка: PR [#3160](https://github.com/androidx/media/pull/3160), ещё открыт. |
| DV в MKV | `MatroskaExtractor` парсит `BlockAdditionMapping` с `BlockAddIDType` = `dvcC`/`dvvC` → MIME `video/dolby-vision`. В MP4 парсятся `dvcC`/`dvvC`/`dvwC`. |
| HDR10 / HLG / HDR10+ | Статические метаданные идут через `ColorInfo.hdrStaticInfo` → `KEY_HDR_STATIC_INFO`. HDR10+ для HEVC/AV1 передаётся внутри потока (SEI/OBU), для VP9 в MKV через BlockAdditional T.35 → `PARAMETER_KEY_HDR10_PLUS_INFO`. Гибриды DV+HDR10+ ломаются на устройствах MediaTek (Fire TV 4K/Max, Google TV Streamer). |
| Tunneling | Включается через `DefaultTrackSelector.Parameters.setTunnelingEnabled(true)`. Полезен для 4K/HDR на слабых SoC, но ломает часть сценариев (точный seek в конец, видео без аудио, эффекты). jellyfin-androidtv его **не включает**. |
| Частота обновления | media3 сам умеет только `C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS` (через `Surface.setFrameRate`). На ТВ переключение почти всегда «не бесшовное», поэтому Kodi, Just Player и jellyfin-androidtv ставят `WindowManager.LayoutParams.preferredDisplayModeId`. |
| Режим HDR | Приложение не может явно включить HDR-режим HDMI. Он переключается автоматически, если HDR-кадры идут в `SurfaceView` (зависит от прошивки и настройки «Match dynamic range»). Обязательно `SurfaceView`, не `TextureView`. Обратного переключения в SDR после остановки может не быть: [#3406](https://github.com/androidx/media/issues/3406). |
| Passthrough | AC3, E-AC3, E-AC3-JOC (Atmos), DTS, DTS-HD, DTS:X P2 (`ENCODING_DTS_UHD_P2`, API 34) и TrueHD (`ENCODING_DOLBY_TRUEHD`) через `DefaultAudioSink` + `AudioCapabilities`. DTS-HD MA пока идёт как `ENCODING_DTS_HD`; PR [#3234](https://github.com/androidx/media/pull/3234) под `ENCODING_DTS_HD_MA` открыт. |
| FFmpeg-расширение | Официальное `media3-decoder-ffmpeg` декодирует **только аудио**: `ExperimentalFfmpegVideoRenderer` в 1.11.1 пустая заглушка. Готовый AAR: `org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1` (только аудио, FFmpeg 6.0, LGPL-сборка, POM помечен GPL-3.0). |
| Программное видео | Официально VP9 (libvpx) и AV1 (**dav1d** с 1.9.0, раньше libgav1); ни то, ни другое не публикуется в Maven. Сторонний `io.github.anilbeesetti:nextlib-media3ext:1.11.1-0.16.0` добавляет FFmpeg-видео (h264, hevc, mpeg1/2, vp8, vp9, av1-dav1d), но вывод **8-bit RGBA/YUV420P**, то есть без HDR. VC-1, MPEG-4 ASP и WMV ни в одной готовой сборке нет. |
| ASS/SSA | `io.github.peerless2012:ass-media:0.5.1` (libass, MIT) поддерживает вложенные шрифты MKV. В jellyfin-androidtv подключён за опцией `libass_enabled` (по умолчанию false), режим `AssRenderType.OVERLAY_OPEN_GL`. |
| PGS / VobSub / DVB | Внутри MKV (`S_HDMV/PGS`, `S_VOBSUB`, `S_DVBSUB`) и DVB в TS работают. Внешние `.sup` и `.idx/.sub` через `SubtitleConfiguration` фактически не годятся **(не проверено)**. |
| Контейнеры | MKV: header stripping поддерживается, zlib нет ([#2361](https://github.com/androidx/media/issues/2361)), ordered chapters и linked segments нет. M2TS (192-байтные пакеты) не поддерживается ([#608](https://github.com/androidx/media/issues/608)). ASF/WMV, ISO, VIDEO_TS и BDMV не поддерживаются. AVI есть (`AviExtractor`). |

---

## 1. Dolby Vision в media3

### 1.1 Профили и сопоставление
Источник: `libraries/common/.../CodecSpecificDataUtil.java` и `libraries/exoplayer/.../mediacodec/MediaCodecUtil.java`.

| Профиль DV | Константа `CodecProfileLevel` | Codec string | Базовый слой | Фолбэк media3 без DV-дисплея |
|---|---|---|---|---|
| 4 | `DolbyVisionProfileDvheDtr` | dvhe.04 | HEVC | → `video/hevc` |
| 5 | `DolbyVisionProfileDvheStn` | dvhe.05 | IPTPQc2, без обратной совместимости | **нет** |
| 7 | `DolbyVisionProfileDvheDtb` | dvhe.07 | HEVC Main10 HDR10 + EL | **нет**, явно исключён: «deprecated and is not always backward compatible» |
| 8.1 / 8.2 / 8.4 | `DolbyVisionProfileDvheSt` | dvhe.08 (dvh1.08 в MP4) | HEVC HDR10 / SDR / HLG | → `video/hevc` |
| 9 | `DolbyVisionProfileDvavSe` | dvav.09 | AVC | → `video/avc` |
| 10.x | `DolbyVisionProfileDvav110` | dav1.10 | AV1 | → `video/av01`, **кроме 10.0** (PQ + full range означает отсутствие совместимости) |

- Поддержку P10 добавили в 1.10.0 ([PR #2830](https://github.com/androidx/media/pull/2830)). В 1.10.1 исправили артефакты из-за initialization data для DV-AV1 ([#3153](https://github.com/androidx/media/pull/3153)).
- MIME: `MimeTypes.VIDEO_DOLBY_VISION = "video/dolby-vision"`. `MediaCodecUtil.getCodecMimeType` учитывает вендорские MIME: `OMX.MS.HEVCDV.Decoder` → `video/hevcdv`, а Realtek (`OMX.RTK.video.decoder`, `OMX.realtek.video.decoder.tunneled`) → `video/dv_hevc`.
- `MediaCodecVideoRenderer.onConfigure`: для DV в `MediaFormat` явно пишется `KEY_PROFILE`, потому что некоторые устройства этого требуют.

### 1.2 Как выбирается декодер (`MediaCodecVideoRenderer.getDecoderInfos`)
```
if (SDK_INT >= 26 && format is DV && !Api26.doesDisplaySupportDolbyVision(context)) {
    alt = MediaCodecUtil.getAlternativeDecoderInfos(...)   // HEVC/AVC/AV1 по таблице выше
    if (!alt.isEmpty()) return alt;
}
return MediaCodecUtil.getDecoderInfosSoftMatch(...)        // DV-декодеры + альтернативные
```
- `doesDisplaySupportDolbyVision` проверяет `display.isHdr()` и наличие `HDR_TYPE_DOLBY_VISION` в `Display.getHdrCapabilities().getSupportedHdrTypes()` для `DEFAULT_DISPLAY`.
- Если DV-дисплея нет, `supportsFormat` возвращает `DECODER_SUPPORT_FALLBACK_MIMETYPE`.
- `getDecoderInfosSoftMatch` даёт полный список: декодеры для основного MIME плюс для альтернативного (см. javadoc). Отсюда следствие: если на устройстве **есть** DV-декодер, а дисплей DV не поддерживает, для P5 и P7 будет использован DV-декодер. Что он выведет на не-DV экран (тон-маппинг или фиолетовая картинка), зависит от SoC **(не проверено)**.
- Если ни DV-декодера, ни фолбэка нет (например, P7 на Chromecast/Google TV Streamer без DV), трек получает статус unsupported и видео не играет. Решения: (а) DeviceProfile, чтобы сервер срезал DV или транскодировал; (б) на клиенте подменить `sampleMimeType` на `video/hevc` через собственный `MediaCodecSelector` или обёртку экстрактора (так делают сторонние плееры **(не проверено)**).
- Из release notes:
  - 1.9.x: «Fix issue where some playbacks of Dolby Vision files fail when attempting to use a fallback AVC or HEVC codec».
  - Ранее: «Disable codec reuse for Dolby-Vision content with different profiles» и «Support CEA-608 in DV content».
  - 1.11.0: трек с распознанным, но неподдерживаемым профилем/уровнем теперь `NO_EXCEEDS_CAPABILITIES`, а не `YES`.
  - 1.10.1: [#3135](https://github.com/androidx/media/issues/3135) перенёс HDR-предпочтения в quality preferences.
  - Открыт PR [#3461](https://github.com/androidx/media/pull/3461) «Select HDR video tracks by display HDR capabilities».

### 1.3 Профиль 7 (FEL/MEL)
- В MKV (MakeMKV) BL+EL+RPU объединены в **один трек** с NAL type 62/63, codec `dvhe.07.06`.
- media3 не делает ни композиции EL, ни конвертации P7 в P8. Feature request [#1865](https://github.com/androidx/media/issues/1865) («DV Profile 7 + FEL on Android TV») открыт с 2024-11, без ответа мейнтейнеров.
- Фолбэк P7 → HEVC (BL HDR10/HDR10+) появится только после PR [#3160](https://github.com/androidx/media/pull/3160) (open, approve от контрибьютора 2026-08-14).
- Устройства: Nvidia Shield 2019 фактически играет P7 (MEL и FEL как BL+RPU), но **не сообщает** об этом в `MediaCodecList`. jellyfin-androidtv держит это в `KnownDefects.unreportedDoviProfile7Support` (`Build.MODEL == "SHIELD Android TV"`).
- Ugoos/CoreELEC с `dovi.ko` и полноценным FEL недоступны обычному Android-приложению.
- Свежий баг jellyfin-androidtv: [#5794](https://github.com/jellyfin/jellyfin-androidtv/issues/5794), «TrueHD passthrough and DV P7 fallback broken on Shield 2019». В 0.20 beta частично исправлен; на не-DV Samsung P7 играет в SDR вместо HDR10.

### 1.4 DV в MKV и MP4
- **MKV**: `MatroskaExtractor` понимает `ID_BLOCK_ADDITION_MAPPING (0x41E4)`, `ID_BLOCK_ADD_ID_TYPE (0x41E7)`, `ID_BLOCK_ADD_ID_EXTRA_DATA (0x41ED)`. При `BlockAddIDType == 0x64766343 ('dvcC')` или `0x64767643 ('dvvC')` байты конфигурации парсятся через `DolbyVisionConfig.parse()`, после чего `mimeType = video/dolby-vision`, `codecs = dvhe.PP.LL`. `dvwC` для MKV не обрабатывается, для MP4 обрабатывается. `BlockAdditional` с ID 4 (ITU-T T.35) берётся **только для VP9**, остальные block additions пропускаются.
- **MP4/fMP4**: боксы `dvcC`, `dvvC`, `dvwC`, sample entries `dvhe`, `dvh1`, `dav1`, `dvav`/`dva1` (`Mp4Box.java`).
- **TS/HLS**: DV определяется через `SUPPLEMENTAL-CODECS` в HLS. В «сыром» TS с DV-дескриптором (Blu-ray m2ts) нет **(не проверено)**; M2TS всё равно не открывается, см. §7.

### 1.5 Известные открытые проблемы DV
- [#1895](https://github.com/androidx/media/issues/1895): DV P8.1 + HDR10+ (гибридные релизы) на Fire TV 4K Max и других MediaTek. Симптомы: зависание, чёрный или пурпурный экран. Фикс PR [#3085](https://github.com/androidx/media/pull/3085) («Skip HDR10+ metadata processing when DV Profile 8 is detected»), open, результаты смешанные. jellyfin-androidtv обходит это через `KnownDefects.hevcDoviHdr10PlusBug` для моделей `AFTKRT`, `AFTKA`, `AFTKM`, `AFTMM`.
- [#5184 (jellyfin-androidtv)](https://github.com/jellyfin/jellyfin-androidtv/issues/5184): чёрное мигание на DoVi при смене сцен.
- [#1635](https://github.com/androidx/media/issues/1635): DASH, нельзя выбрать DV AdaptationSet. Для нас неактуально.

---

## 2. HDR10 / HDR10+ / HLG и tunneling

### 2.1 Метаданные
- **Статические** (MaxCLL/MaxFALL/mastering display): из MKV `Colour/MasteringMetadata`, MP4 `mdcv`/`clli` или VUI/SEI попадают в `ColorInfo.hdrStaticInfo`, затем `MediaFormatUtil.maybeSetColorInfo` записывает `MediaFormat.KEY_HDR_STATIC_INFO`, `KEY_COLOR_TRANSFER`, `KEY_COLOR_STANDARD`, `KEY_COLOR_RANGE`. В 1.11.0 добавлено «Matroska: Use codec bitstream metadata to populate accurate ColorInfo», так что неверные или пустые Colour-элементы MKV больше не ломают HDR.
- **HDR10+ (динамические)**:
  - HEVC и AV1: метаданные лежат в SEI/OBU потока и уходят в декодер вместе с кадром. Для MKV ничего специального не нужно, декодер должен поддерживать `HEVCProfileMain10HDR10Plus`.
  - VP9 в MKV/WebM: BlockAdditional ID 4 → `supplementalData` → `MediaCodec.setParameters(PARAMETER_KEY_HDR10_PLUS_INFO)` (API 29+, `setHdr10PlusInfoV29`).
- **HLG**: определяется по `COLOR_TRANSFER_HLG`, отдельной обработки нет.
- **Новое**: в 1.11.0 добавлена экспериментальная поддержка HAGC (SMPTE ST 2094-50) для MP4/MKV, работает на API 37+. С 1.11.1 отключается через `ExtractorsFactory.setParseHagcMetadata(false)`.
- Открыт PR [#3463](https://github.com/androidx/media/pull/3463): «Map 10-bit AV1 to AV1ProfileMain10HDR10 only for PQ».

### 2.2 Tunneled playback
- API: `DefaultTrackSelector.Parameters.Builder.setTunnelingEnabled(true)`. Проверка: `ExoPlayer.isTunnelingEnabled()`. Работает, только если **и видео-, и аудиорендерер** поддерживают tunneling. Для видео нужен декодер с `FEATURE_TunneledPlayback`, поддерживающий формат и seamless adaptation (`MediaCodecVideoRenderer.supportsFormat`). Нужен audio session id: AV-sync идёт по аудио (HW sync).
- **Плюсы**: кадры идут от декодера сразу в HW-композитор, минуя app/SurfaceFlinger. Меньше дропов для 4K60/HDR на слабых SoC (Amlogic/MediaTek-стики), точнее A/V sync. В Just Player опция «Tunneled playback» описана как «can improve playback of 4K/HDR content on Android TV». Подробнее: [AOSP multimedia tunneling](https://source.android.com/devices/tv/multimedia-tunneling).
- **Минусы и что ломается**:
  - Без аудиотрека не работает ([#2541](https://github.com/androidx/media/issues/2541)). Пауза делается через AudioTrack.
  - Seek в конец может зависнуть, не доходя до `STATE_ENDED` ([#1574](https://github.com/androidx/media/issues/1574)).
  - Гонка с audio session id: crash `IllegalStateException` (исправлен в 1.10.1, [#3099](https://github.com/androidx/media/issues/3099)).
  - DTS:X P2 в HLS-fMP4 теряет первые 2 секунды в tunnel ([#1245](https://github.com/androidx/media/issues/1245)).
  - Нельзя применять video effects или GL-обработку к кадрам. Не работают `VideoFrameMetadataListener` и покадровый контроль, render-first-frame на паузе после seek ненадёжен **(частично не проверено)**.
  - Нет гарантии нескольких одновременных tunneled-декодеров.
  - Часть HDR-метаданных на некоторых SoC в tunnel теряется **(не проверено)**.
- **Субтитры**: text-рендерер не зависит от tunneling, cues приходят как обычно, `SubtitleView` или ASS-overlay (`AssRenderType.OVERLAY_*`) рисуются поверх SurfaceView. Не будут работать только режимы, встраивающие субтитры в видеокадр (`AssRenderType.EFFECTS_*`, deprecated) **(логический вывод, не проверено на устройстве)**.
- **Рекомендация**: опция «Tunneling: Авто/Вкл/Выкл», по умолчанию выкл. Если для конкретной модели есть подтверждённые проблемы с плавностью, включать через device-list. jellyfin-androidtv tunneling не использует, а только репортит поддержку (`ExoPlayerPlaySupportReport.tunnelingFromFlags`).

---

## 3. Режим дисплея: частота и HDR

### 3.1 Что умеет media3
- `ExoPlayer.setVideoChangeFrameRateStrategy(int)` принимает только `C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS` (по умолчанию) или `..._OFF`. На API 30+ `VideoFrameReleaseHelper.Api30.setSurfaceFrameRate` вызывает `Surface.setFrameRate(rate, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)` (или 0/DEFAULT на паузе). Вариант `CHANGE_FRAME_RATE_ALWAYS` в media3 **не реализован**.
- По [гайду Android](https://developer.android.com/media/optimize/performance/frame-rate):
  - для фильмов использовать `Surface.setFrameRate(fps, FIXED_SOURCE, CHANGE_FRAME_RATE_ALWAYS)` (API 31), но только если пользователь выбрал `DisplayManager.getMatchContentFrameRateUserPreference() == MATCH_CONTENT_FRAMERATE_ALWAYS`;
  - дождаться `DisplayListener.onDisplayChanged`, затем выдержать паузу около 2 с и запускать воспроизведение;
  - на Android TV «тяжёлую» смену режима (HDMI re-negotiation) `setFrameRate` может не вызвать, и гайд прямо советует для этого `preferredDisplayModeId`.
- Связанные баги:
  - [#2258](https://github.com/androidx/media/issues/2258): после смены частоты Shield падал на PCM вместо passthrough. Исправлено в 2025-05, рекомендуется `setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)`.
  - [#3120](https://github.com/androidx/media/issues/3120) и [#2941](https://github.com/androidx/media/issues/2941): чёрные кадры и сброс кодека при смене fps на API < 30 (исправлено в 1.10.x).

### 3.2 Как делают другие
- **jellyfin-androidtv** (`ui/playback/PlaybackController.java`, `findBestDisplayMode`/`setRefreshRate`): перебирает `Display.getSupportedModes()` и пропускает режимы ниже 1280×720 и ниже разрешения видео. Подходят частоты `== fps`, `== 2×fps`, `== 2.5×fps` (в сотых герца). Вес: `100000 − rateDiff + 100000 − resolutionDiff`. Режимы `SCALE_ON_DEVICE` / `SCALE_ON_TV` (`RefreshRateSwitchingBehavior`). Применяет `window.attributes.preferredDisplayModeId`, по выходу сбрасывает в 0 (`CustomPlaybackOverlayFragment`).
- **Just Player** (`Utils.handleFrameRate`): fps берётся из `MediaExtractor`. Ищется режим **того же разрешения** с частотой, кратной fps; если такого нет, берётся максимальная. Ставится `preferredDisplayModeId`, воспроизведение стартует в `DisplayManager.DisplayListener.onDisplayChanged`. Комментарий в коде: «ExoPlayer already uses Surface.setFrameRate() on Android 11+».
- **Kodi** (`XBMCApp.cpp::SetDisplayMode` / `SetDisplayModeCallback`): тоже `preferredDisplayModeId`. HDR-возможности берёт из `Display.getHdrCapabilities().getSupportedHdrTypes()` (`AndroidUtils.cpp`).
- **Nova Video Player**: `preferredDisplayModeId` с похожей логикой **(не проверено по коду)**.
- **Рекомендация**: свой `DisplayModeManager`:
  1. До `prepare()` взять fps из Jellyfin `MediaStream.RealFrameRate`/`AverageFrameRate`.
  2. Если режим «Всегда», выбрать `Display.Mode` и выставить `preferredDisplayModeId` (на API 31+ можно ещё `Surface.setFrameRate(..., CHANGE_FRAME_RATE_ALWAYS)`).
  3. Дождаться `onDisplayChanged` с таймаутом.
  4. Включить `setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)`, потому что после переподключения HDMI меняются AudioCapabilities.
  5. Учесть 23.976 против 24.000 (сравнивать с точностью около 0.01 Гц) и фильтровать режимы с меньшим разрешением.

### 3.3 Переключение HDR
- У обычного приложения нет публичного API «включить HDR на HDMI». Платформа или прошивка переключают выход, когда в `SurfaceView` поступают кадры с HDR-dataspace. Это зависит от настроек «Match content dynamic range» (Shield, Google TV, Fire TV) **(не проверено для каждой прошивки)**. На API 34+ есть `DisplayManager.setHdrConversionMode` / `HdrConversionMode`, но это system API **(не проверено)**.
- Проверка возможностей: `Display.isHdr()` и `Display.getHdrCapabilities().supportedHdrTypes` (`HDR_TYPE_DOLBY_VISION`, `HDR_TYPE_HDR10`, `HDR_TYPE_HLG` с API 24, `HDR_TYPE_HDR10_PLUS` с API 29). На API 34+ также `Display.Mode.getSupportedHdrTypes()` **(не проверено)**.
- **Обязателен `SurfaceView`**: `TextureView` HDR не передаёт. В Compose это `PlayerSurface(surfaceType = SURFACE_TYPE_SURFACE_VIEW)` из `media3-ui-compose` или `AndroidExternalSurface`.
- Открытый баг [#3406](https://github.com/androidx/media/issues/3406): после остановки HDR-видео дисплей остаётся в HDR (Mi Box S, Android TV 11/14). Kodi этим не страдает. Гипотеза: нужно освобождать или отсоединять Surface, сбрасывать `preferredDisplayModeId` и закрывать активити плеера **(не проверено)**.

---

## 4. Аудио: passthrough и FFmpeg

### 4.1 Passthrough (`DefaultAudioSink`, `AudioCapabilities`)
- Таблица `AudioCapabilities.ALL_SURROUND_ENCODINGS_AND_MAX_CHANNELS`: `AC3` 6, `AC4` 6, `DTS` 6, `DTS_UHD_P2` 10, `E_AC3_JOC` 6, `E_AC3` 8, `DTS_HD` 8, `DOLBY_TRUEHD` 8.
- Встроенные фолбэки: E-AC3-JOC → E-AC3 (Atmos-метаданные при этом остаются внутри потока); DTS-HD и DTS-UHD-P2 → DTS core, если HD не поддерживается.
- На API 33+ для TV используется `AudioManager.getDirectProfilesForAttributes`. На API 29+ для TV учитываются `external_surround_sound_enabled` (Fire TV) и `force_external_surround_sound`. Для Amazon и Xiaomi есть отдельная ветка.
- **Atmos**: E-AC3-JOC (DD+ Atmos) битстримится как `ENCODING_E_AC3_JOC` (API 28+). TrueHD Atmos битстримится как `ENCODING_DOLBY_TRUEHD` (MAT); Atmos в TrueHD передаётся прозрачно.
- **DTS-HD MA / DTS:X (Blu-ray)**: идут как `ENCODING_DTS_HD`, DTS:X прозрачно внутри. Корректный `ENCODING_DTS_HD_MA` (API 34) появится с PR [#3234](https://github.com/androidx/media/pull/3234) (open), связанный issue [#2487](https://github.com/androidx/media/issues/2487). Детект DTS-HD в MKV и MP4 доработан в 1.11 (PR #3226, [#3229](https://github.com/androidx/media/pull/3229)), в TS склеиваются core и ext ([#3147](https://github.com/androidx/media/pull/3147)).
- **TrueHD**:
  - MKV: `TrueHdSampleRechunker` группирует sync-фреймы для passthrough.
  - fMP4: TrueHD не поддерживается ([#1519](https://github.com/androidx/media/issues/1519)), PR [#3411](https://github.com/androidx/media/pull/3411) открыт. Для нас это значит: не просить сервер ремуксить TrueHD в HLS-fMP4.
  - Программный декод 2.0 TrueHD сломан в FFmpeg-расширении с media3 1.2.1 (FFmpeg 6.0, [#1843](https://github.com/androidx/media/issues/1843), open). 5.1/7.1 работают.
  - [#3329](https://github.com/androidx/media/issues/3329): сложности учёта данных в `DefaultAudioSink` при TrueHD → MAT.
- Реальность устройств: TrueHD/DTS-HD битстримит Shield. Chromecast/Google TV Streamer и большинство стиков — только DD/DD+ (часто «Dolby Atmos via DD+»). Сводки нет **(не проверено)**.
- Наблюдение jellyfin-androidtv [PR #5822](https://github.com/jellyfin/jellyfin-androidtv/pull/5822): если объявить TrueHD как поддерживаемый, а устройство не может его битстримить, сервер отдаст оригинал, клиент декодирует его в PCM 7.1, а TV в цепочке может свести это в стерео. Лучше просить сервер перекодировать в AC3 5.1. Контрпример: Chromecast напрямую в ресивер. Нужна пользовательская настройка.

### 4.2 Расширение FFmpeg
- Официальный модуль `libraries/decoder_ffmpeg`. Внутри `FfmpegAudioRenderer` и `ExperimentalFfmpegVideoRenderer`. Последний в 1.11.1 явно **нерабочий**: `supportsFormat` возвращает `FORMAT_UNSUPPORTED_TYPE`, `// TODO: Merge actual implementation in PR #1591`, `b/470357054`. `DefaultRenderersFactory` подхватывает его рефлексией, но толку нет.
- Маппинг MIME → FFmpeg (`FfmpegLibrary.getCodecName`): aac, mp3, ac3, eac3 (+JOC), truehd, dca (DTS / Express / HD), vorbis, opus, amrnb/wb, flac, alac, pcm_mulaw/alaw, h264, hevc.
- В Maven Google модуль **не публикуется** ([ExoPlayer #2781](https://github.com/google/ExoPlayer/issues/2781)). Сборка: NDK r26b, FFmpeg `release/6.0`, `build_ffmpeg.sh` с `--disable-everything --enable-decoder=...`, без `--enable-gpl`, то есть получается **LGPL-2.1+**.
- **Готовый AAR от Jellyfin**: `org.jellyfin.media3:media3-ffmpeg-decoder` (Maven Central, последняя `1.9.0+1`, 2025-12-29). Репозиторий [jellyfin/jellyfin-androidx-media](https://github.com/jellyfin/jellyfin-androidx-media): submodules `androidx/media` (ветка release) и FFmpeg `release/6.0`. `ENABLED_DECODERS=(flac alac pcm_mulaw pcm_alaw mp3 aac ac3 eac3 dca mlp truehd)`, ABI 23, **только аудио**. Лицензия репозитория и POM: GPL-3.0; сам libffmpeg собран без `--enable-gpl` (LGPL). jellyfin-androidtv использует `1.9.0+1` вместе с media3 `1.11.1`: бинарно совместимо на практике, но это риск при обновлениях. Режим `EXTENSION_RENDERER_MODE_ON` или `_PREFER` (опция «prefer ffmpeg»).
- **Лицензия для нас**: при GPL-клиенте (клиенты Jellyfin под GPL-2.0/3.0) конфликта нет. LGPL требует возможности перелинковки, у открытого проекта это выполнено. Патентные вопросы по AC3/E-AC3/DTS/TrueHD декодерам в FFmpeg лежат на дистрибьюторе **(юридически не проверено)**. Для Google Play многие плееры (Just Player, Next Player, jellyfin-androidtv) так и поставляются.

---

## 5. Программное декодирование видео

| Источник | Кодеки | Статус / артефакт | HDR / 10-bit |
|---|---|---|---|
| `decoder_vp9` (`LibvpxVideoRenderer`) | VP9 (+VP8?) | собирать самим, в Maven нет | 10-bit выводится через GL YUV-рендер **(не проверено)** |
| `decoder_av1` (`Libdav1dVideoRenderer`) | AV1 | с **1.9.0** на dav1d вместо libgav1; собирать самим (NDK r27, meson) | **(не проверено)** |
| `ExperimentalFfmpegVideoRenderer` (официальный) | — | заглушка | — |
| PR [#1591](https://github.com/androidx/media/pull/1591) «Implement an FfmpegVideoRenderer» (rabbitknight) | VP8/9, MPEG-1/2, VC-1, ProRes, AV1, H.264 Hi10 | open с 2024-08, без ревью мейнтейнеров; адаптирован к 1.11.1 (2026-09); есть баги (green screen при zero stride, `pixelWidthHeightRatio=1`) | 10-bit приводится к 8-bit |
| **NextLib** [anilbeesetti/nextlib](https://github.com/anilbeesetti/nextlib), `io.github.anilbeesetti:nextlib-media3ext:1.11.1-0.16.0` (2026-09-13) | видео: h264, hevc, mpeg2video, mpegvideo, vp8, vp9, libdav1d; аудио: как у Jellyfin + opus, vorbis, amr | Maven Central, синхронизирован с media3 1.11.1, используется Next Player; FFmpeg 9.0.1, `--enable-version3` + mbedTLS (LGPLv3), репозиторий GPL-3.0 | вывод `WINDOW_FORMAT_RGBA_8888` / YUV420P через `sws_scale`, то есть **HDR и 10-bit теряются** |

Что остаётся **невозможным без libVLC/libmpv** (или без серверной транскодировки):
- **VC-1 / WMV3 / WMV9**: нет ASF-экстрактора. VC-1 в MKV распознаётся (FourCC `WVC1` → `video/wvc1`) и играет только при HW-декодере. Он есть на Shield и части Amlogic, на Chromecast/Google TV, по-видимому, нет **(не проверено)**. Программного VC-1 нет ни в одной готовой сборке (можно пересобрать NextLib с `vc1`/`wmv3`).
- **MPEG-2** без HW: NextLib (программный mpeg2video). HW MPEG-2 есть почти везде, но на свежих SoC иногда выкинут **(не проверено)**.
- **H.264 Hi10P (High 10)**: HW-поддержки практически нет. jellyfin-androidtv проверяет `AVCProfileHigh10`. Программно через NextLib, но с 8-bit выводом и большой нагрузкой на CPU (1080p ещё реально, 4K нет).
- **MPEG-4 ASP / DivX / Xvid**: `AviExtractor` и MKV (`V_MPEG4/ISO/ASP`, FourCC DIVX/XVID/DX50/FMP4) отдают `video/mp4v-es`. Работает только при HW-декодере; программный `c2.android.mpeg4.decoder` надёжен лишь для Simple Profile (B-frames, QPEL, GMC ASP не тянет) **(не проверено)**. MS MPEG-4 v2/v3 (`MP42`/`MP43`) распознаются, но декодеров нет.
- **HEVC 4:2:2 / 4:4:4, 12-bit, ProRes, RealVideo, Theora, WMV, H.263+ экзотика, MPEG-1 (без HW)**: только FFmpeg-движок.
- **Любой HDR при программном декоде через media3-экстеншены**: только SDR-вывод.

---

## 6. Субтитры

### 6.1 Встроенная поддержка
- **Парсеры** (`DefaultSubtitleParserFactory`): SSA/ASS (упрощённо: стили, позиционирование, без анимаций и шрифтов), WebVTT, MP4VTT, SubRip, TX3G, **PGS**, **VobSub**, **DVB**, TTML.
- **MKV codec IDs**: `S_TEXT/UTF8`, `S_TEXT/ASS`, `S_TEXT/SSA`, `S_TEXT/WEBVTT`, `S_VOBSUB`, `S_HDMV/PGS`, `S_DVBSUB`.
- **TS**: DVB-subs (`0x59`); PGS в TS (`0x90`) не поддерживается.
- **MP4**: VobSub (1.9), tx3g, wvtt.
- Последние исправления: VobSub SPU START/STOP (1.10), PGS RLE color index 0, пропуски субтитров VobSub.
- Проблема [#2667](https://github.com/androidx/media/issues/2667): **все** image-based дорожки (PGS/VobSub) парсятся в Bitmap, даже невыбранные. При десятках PGS-дорожек это бьёт по памяти и CPU. Обход: пересоздавать или отключать треки, или просить сервер отдавать без лишних дорожек **(не проверено)**.
- **Внешние (side-loaded)**: `MediaItem.SubtitleConfiguration(uri).setMimeType(...).setLanguage(...).setSelectionFlags(...)` → `SubtitleExtractor` (один файл = один парсер). Надёжно: SRT, ASS/SSA, VTT, TTML. Внешние `.sup` (PGS) и `.idx/.sub` (VobSub) так подключить нельзя или ненадёжно: `.sup` содержит PTS в заголовках сегментов, которые `PgsParser` не читает, а VobSub требует idx как initialization data и MPEG-PS для .sub **(не проверено)**. jellyfin-androidtv для DVBSUB/DVDSUB/IDX/PGS объявляет только `EMBED` + `ENCODE` (burn-in), внешних нет.

### 6.2 ASS/SSA через libass: `io.github.peerless2012:ass-media`
- Репозиторий [peerless2012/libass-android](https://github.com/peerless2012/libass-android), MIT, последний коммит 2026-10-09. Версии: `0.5.1` (2026-08), до этого 0.5.0 и 0.4.0. Это 0.x, API менялся (`EFFECTS_*` → deprecated).
- Модули: `lib_ass` (prefab, libass + freetype/harfbuzz/fribidi/fontconfig), `lib_ass_kt` (JNI-обёртка), `lib_ass_media` (интеграция с media3). Внутренне собран против media3 1.8.0.
- Классы: `AssHandler(AssRenderType)`, `AssHandlerConfig(glyphSize, cacheSize, maxRenderPixels)` (ограничение разрешения рендера, полезно для 4K), `AssRenderersFactory`, `AssSubtitleParserFactory`, `AssMatroskaExtractor` (наследник `MatroskaExtractor`, **достаёт вложенные шрифты из MKV Attachments** → `AssHandler.addFont`), `AssSubtitleView`.
- `AssRenderType`: `CUES` (через `SubtitleView`, bitmap-cues), `OVERLAY_CANVAS`, `OVERLAY_OPEN_GL`, deprecated `EFFECTS_CANVAS` / `EFFECTS_OPEN_GL`.
- Пользователи: Jellyfin Android TV, Fladder, NuvioTV, AfuseKt.
- Интеграция в jellyfin-androidtv (`playback/media3/exoplayer/src/main/kotlin/ExoPlayerBackend.kt`):
  ```kotlin
  val assHandler = AssHandler(AssRenderType.OVERLAY_OPEN_GL)
  val parserFactory = AssSubtitleParserFactory(assHandler)
  val extractors = DefaultExtractorsFactory().withAssMkvSupport(parserFactory, assHandler)
  DefaultMediaSourceFactory(dataSourceFactory, extractors).setSubtitleParserFactory(parserFactory)
  val renderers = AssRenderersFactory(assHandler, DefaultRenderersFactory(context).apply {
      setEnableDecoderFallback(true); setExtensionRendererMode(ON / PREFER) })
  // после build(): assHandler.init(player)
  // в SubtitleView: addView(AssSubtitleView(context, assHandler))
  ```
  Включается пользовательской настройкой `assDirectPlay` (ключ `libass_enabled`, по умолчанию `false`). Без неё ASS/SSA в DeviceProfile получает `ENCODE` (burn-in на сервере).
- **Риски**: `AssMatroskaExtractor` наследует внутренности `MatroskaExtractor` (protected-методы), поэтому может ломаться при обновлениях media3. Производительность тяжёлых typesetting-субтитров на слабых ТВ-SoC не измерена.

---

## 7. Контейнеры и экстракторы

| Формат | Статус в media3 1.11.1 |
|---|---|
| **MKV/WebM** | Основной. Поддерживаются lacing (Xiph/EBML/fixed), **header stripping** (`ContentCompAlgo = 3`). **zlib/bzlib/lzo** (`ContentCompAlgo` 0/1/2) приводят к `ParserException "ContentCompAlgo N not supported"` ([#2361](https://github.com/androidx/media/issues/2361), open). Шифрование только AES (WebM). Главы парсятся в `Chapter` metadata (1.11.0), но **ordered chapters, linked segments (SegmentUID) и editions не реализованы** (воспроизводится сегмент как есть). Треки после кластеров исправлены в 1.11.1 ([#3377](https://github.com/androidx/media/issues/3377)), треки в последнем кластере в [#3250](https://github.com/androidx/media/issues/3250). ALAC, float64 PCM. `V_MS/VFW/FOURCC` распознаёт только DIVX, H263 и WVC1. Theora и RealVideo не поддерживаются. |
| **MP4/MOV/fMP4** | Полный (DV, HDR10+, главы с 1.11, `mfra`-seek). TrueHD в fMP4 нет (#1519). |
| **AVI** | `AviExtractor`: H.264, MPEG-4 ASP (DivX/Xvid), MJPEG, MP42/MP43; аудио PCM, MP3, AAC, AC3, DTS. Исправлена потеря аудио без keyframe-флагов (1.11.0). Индексы и OpenDML у больших AVI могут работать с ограничениями **(не проверено)**. |
| **MPEG-TS** (188 байт) | H.262/H.264/H.265, AAC/AC3/E-AC3/AC4/DTS/DTS-HD/DTS-UHD, DVB-subs. **Нет TrueHD (0x83), LPCM BD (0x80), PGS (0x90), VC-1 (0xEA)**. |
| **M2TS/MTS** (192 байта, BDAV) | **Не поддерживается**: sniff требует sync-байт через каждые 188 байт. [#608](https://github.com/androidx/media/issues/608) закрыт как дубль [ExoPlayer #1488](https://github.com/google/ExoPlayer/issues/1488): «isn't supported», PR приветствуется. |
| **MPEG-PS/VOB** | `PsExtractor` (MPEG-2 + AC3/MPA/LPCM?) работает для простых случаев; навигации DVD нет. |
| **ISO, VIDEO_TS, BDMV** | Не поддерживаются: нет UDF/ISO, IFO/MPLS, меню. Только через сервер: Jellyfin сам разбирает BDMV/VIDEO_TS и отдаёт поток (remux/transcode) **(поведение 12.2 не проверено)**. |
| **ASF/WMV** | Экстрактора **нет**. Мейнтейнер в [#3249](https://github.com/androidx/media/issues/3249) (2026-06): «WMV ... isn't supported as per CDD». Сторонние ASF-экстракторы есть в форках (mytv-android, FongMi), качество не оценено. |
| FLV, OGG, WAV, FLAC, MP3, AMR, AC3/AC4/ADTS, HEIF/AVIF/JPEG/PNG | есть. Ogg с несколькими треками не поддерживается ([#2876](https://github.com/androidx/media/issues/2876)). |

**Вывод**: в DeviceProfile для media3 **не** объявлять как direct play: `asf`, `wmv`, `m2ts`, `iso`, `vob` (кроме простого MPEG-PS), MKV с zlib-сжатием (сервер это не видит, нужен фолбэк по ошибке), а также кодеки без HW-декодера. При этом jellyfin-androidtv объявляет `ASF`, `WMV`, `VOB`, `XVID`, `OGM` в `directPlayProfile`, что выглядит как источник ошибок. Для нашего гибрида такие контейнеры правильнее маршрутизировать в libVLC/libmpv.

---

## 8. DeviceProfile в jellyfin-androidtv: что переиспользовать

Файлы: `app/src/main/java/org/jellyfin/androidtv/util/profile/`: `deviceProfile.kt`, `MediaCodecCapabilitiesTest.kt`, `KnownDefects.kt`, `deviceProfileReport.kt`, `codec/{MediaCodecQuery, AvcCodecCapabilities, HevcCodecCapabilities, Av1CodecCapabilities, audioPassthrough}.kt`. Сборка через `org.jellyfin.sdk.model.deviceprofile.buildDeviceProfile { }` из SDK (DSL: `transcodingProfile`, `directPlayProfile`, `codecProfile { conditions; applyConditions }`, `subtitleProfile`).

### 8.1 Опрос MediaCodec (`MediaCodecQuery`)
- `MediaCodecList(REGULAR_CODECS)`, только декодеры. Опция `softwareCodecsEnabled`: на API 29+ отбрасываются `isSoftwareOnly`.
- `hasCodecForMime`, `hasDecoder(mime, profile, level)` (по `capabilities.profileLevels`, с особым случаем H.263 Level45), `getDecoderLevel` (максимальный level для профиля), `getMaxResolution` (`videoCapabilities.supportedWidths/Heights.upper`), `supportsMultiInstance` (`maxSupportedInstances > 1`).
- **AVC**: `AVCProfileHigh10 @ Level4` даёт поддержку «high 10». Уровни переводятся в ffprobe-шкалу (×10, 1b = 9) через `LEVEL_MAP`.
- **HEVC**:
  - `Main10 @ MainTierLevel4`;
  - HDR10 = `HEVCProfileMain10HDR10`, HDR10+ = `HEVCProfileMain10HDR10Plus` (API 29+);
  - DV = есть хоть какой-то декодер `video/dolby-vision` (API 24+);
  - **DV с EL** = `DolbyVisionProfileDvheDtb @ LevelHd24` **и** multi-instance HEVC (для двухдекодерной схемы).
  - Уровни ×30 (4.1 → 123).
- **AV1**: Main10 @ Level5, `AV1ProfileMain10HDR10` / `HDR10Plus`, DV P10 = `DolbyVisionProfileDvav110`. Для Fire OS, где AV1 есть ниже API Q, значения констант захардкожены.
- **VC-1**: `hasCodecForMime(video/wvc1)`.
- **Аудио passthrough** (`audioPassthrough.kt`): `AudioCapabilities.getCapabilities(context, AudioAttributes(USAGE_MEDIA, MOVIE), null, [STEREO, 5.1])` → `isPassthroughPlaybackSupported(Format(mime, 2ch))`. Режимы `BitstreamAudioMode.AUTO`, `ENABLE`, `DISABLE` для AC3, EAC3, DTS и TrueHD по отдельности.

### 8.2 Логика профиля
- **Direct play**:
  - контейнеры asf, hls, m4v, mkv, mov, mp4, ogm, ogv, ts, vob, webm, wmv, xvid;
  - видео av1, h264, hevc, mpeg, mpeg2video, vc1, vp8, vp9;
  - аудио: список с фильтром по bitstream-настройкам; при «downmix to stereo» только aac, mp2, mp3.
- **Transcoding**:
  - HLS-TS (hevc если есть, иначе h264; аудио aac/ac3/eac3/mp3);
  - HLS-fMP4 (+alac/flac/opus/dts);
  - `copyTimestamps=false`, `enableSubtitlesInManifest=true`.
- **CodecProfiles**:
  - H.264: допустимые профили; level ≤ max (отдельно для High10); ref frames ≤ 12 при ширине ≥ 1200 и ≤ 4 при ширине ≥ 1900. Это эвристика для слабых декодеров.
  - HEVC: main и, если есть, main 10, плюс level.
  - AV1: `notEquals "main 10"`, если нет Main10.
  - VC-1: `profile == "none"`, если нет декодера.
  - Max width/height для каждого кодека.
  - `AUDIO_CHANNELS ≤ 8` (или 2).
- **HDR через `VideoRangeType`**: трюк с `conditions { VIDEO_RANGE_TYPE notEquals "A|B|C" }` плюс `applyConditions { VIDEO_RANGE_TYPE inCollection [...] }`. Профиль применяется только к медиа с неподдерживаемым range type, а сервер берёт список из notEquals для StreamBuilder: может срезать DV и оставить HDR10 BL вместо полной транскодировки **(механизм на сервере не проверен)**. Множества:
  - всегда `DOVI_INVALID`;
  - без DV-EL (и не Shield-исключение) → `DOVI_WITH_EL` (+`DOVI_WITH_ELHDR10_PLUS`);
  - без DV → `DOVI`; `DOVI_WITH_HDR10` только если и HDR10 нет;
  - без HDR10+ → `HDR10_PLUS` (+`HDR10`, если нет и его);
  - на Fire TV 4K (`AFTKRT`, `AFTKA`, `AFTKM`, `AFTMM`) всегда исключать `DOVI_WITH_HDR10_PLUS` / `DOVI_WITH_ELHDR10_PLUS`.
  - Пользовательские оверрайды по форматам (`HdrFormat`: HDR10, HDR10+, DV P5, P7, P8) с режимами ENABLE/DISABLE/AUTO.
- **Субтитры**:
  - VTT/WebVTT: embed, external, hls;
  - SRT/SubRip/TTML: embed, external;
  - DVBSUB/DVDSUB/IDX: embed + encode;
  - PGS: embed (если `pgsDirectPlay`) + encode;
  - ASS/SSA: embed/external при libass, иначе encode.
- **KnownDefects**: всего 2 списка (Fire TV DV+HDR10+, Shield P7). Отдельной базы «плохих» устройств нет.
- **Чего там нет, но нам нужно**:
  1. Проверки, что **дисплей** поддерживает DV/HDR10/HDR10+ (`HdrCapabilities`). Сейчас смотрят только декодер. media3 при этом сам уйдёт в фолбэк, а профиль может ошибочно разрешить P5 на не-DV ТВ.
  2. Учёта P5 (без фолбэка).
  3. Маршрутизации на второй движок (VLC/mpv) для VC-1/WMV/M2TS/Hi10P и т.п.
  4. HDR10+ на VP9.
  5. `HLG` и `DOVIWithHLG` не рассматриваются явно.

### 8.3 Прочее из ExoPlayerBackend jellyfin-androidtv
- `DefaultRenderersFactory.setEnableDecoderFallback(true)`.
- `DefaultExtractorsFactory.setConstantBitrateSeekingEnabled(true)` + `AlwaysEnabled`. `TsExtractor` timestamp search bytes `TS_PACKET_SIZE * 1800` на low-RAM устройствах.
- `AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED`, `setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)`.
- `PlaySupportReport` из `RendererCapabilities` (формат, adaptive, tunneling, HW-accel, decoder support) для `supportsStream()`. Это готовый паттерн для выбора движка: если media3 не может, идём во второй движок.

---

## 9. Риски и рекомендации для проекта

1. **DV P7 (самые популярные remux)**: на всём, кроме Shield, P7 не даст DV. Без DV-декодера он не даст и картинки, пока не смержен PR #3160. Варианты: (а) свой `MediaCodecSelector`, отдающий HEVC-декодеры для `video/dolby-vision` с профилем DvheDtb, когда нет DV-дисплея или декодера; (б) DeviceProfile с `DOVI_WITH_EL` в unsupported, чтобы сервер срезал DV.
2. **Гибриды DV + HDR10+ на MediaTek** (Fire TV, Google TV Streamer): держать KnownDefects-список и опцию «предпочитать DV / HDR10+» (так сделано в Wholphin, судя по ссылке в #1895).
3. **FFmpeg-видео в media3** официально не готово. NextLib жизнеспособен, но даёт SDR 8-bit и CPU-нагрузку. Для HDR или экзотики при программном декоде нужен второй движок; HDR-вывод libmpv/libVLC на Android отдельно не исследован здесь **(не проверено)**.
4. **Совместимость бинарных расширений**: `org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1` и `ass-media` (собран под 1.8.0) работают с 1.11.1, но это не гарантировано. Лучше собирать FFmpeg-аудио самим в CI под точную версию media3 или взять NextLib, у которого версия привязана к media3 (`1.11.1-0.16.0`).
5. **Смена частоты и HDR**: делать самим через `preferredDisplayModeId` + `DisplayListener`, с паузой. Явно сбрасывать режим и освобождать Surface при выходе (см. #3406).
6. **TrueHD**: не просить HLS-fMP4 с TrueHD. Для 2.0 TrueHD программный декод FFmpeg сломан (#1843). Без passthrough лучше транскод в AC3/E-AC3 на сервере.
7. **Tunneling**: опционально и по умолчанию выкл. Тестировать seek, паузу, субтитры и HDR на целевых устройствах.
8. **Контейнеры**: M2TS, ASF/WMV, ISO, VIDEO_TS и MKV-zlib сразу отправлять во второй движок или на серверный remux.

---

## Источники (проверено)
- androidx/media main @ `8c6678b` (1.11.1): `MatroskaExtractor.java`, `MediaCodecUtil.java`, `MediaCodecVideoRenderer.java`, `VideoFrameReleaseHelper.java`, `AudioCapabilities.java`, `DefaultRenderersFactory.java`, `decoder_ffmpeg/*`, `decoder_av1/*`, `TsExtractor.java`, `avi/StreamFormatChunk.java`, `DefaultSubtitleParserFactory.java`, `RELEASENOTES.md`. https://github.com/androidx/media
- jellyfin-androidtv master @ `0032f4a`: https://github.com/jellyfin/jellyfin-androidtv/tree/master/app/src/main/java/org/jellyfin/androidtv/util/profile, `playback/media3/exoplayer/src/main/kotlin/ExoPlayerBackend.kt`, `ui/playback/PlaybackController.java`
- https://github.com/jellyfin/jellyfin-androidx-media (`build.sh`, `.gitmodules`); Maven: https://repo1.maven.org/maven2/org/jellyfin/media3/media3-ffmpeg-decoder/
- https://github.com/peerless2012/libass-android; Maven: https://repo1.maven.org/maven2/io/github/peerless2012/ass-media/
- https://github.com/anilbeesetti/nextlib; Maven: https://repo1.maven.org/maven2/io/github/anilbeesetti/nextlib-media3ext/
- https://github.com/moneytoo/Player (Just Player, `Utils.java`, `PlayerActivity.java`)
- Kodi: https://github.com/xbmc/xbmc/blob/master/xbmc/platform/android/activity/XBMCApp.cpp, `xbmc/windowing/android/AndroidUtils.cpp`
- https://developer.android.com/media/optimize/performance/frame-rate
- https://source.android.com/devices/tv/multimedia-tunneling
- Issues/PR: androidx/media #1865, #3160, #1895, #3085, #3406, #3461, #3463, #2541, #1574, #3099, #1245, #2258, #3120, #2941, #3234, #2487, #3229, #3147, #1519, #3411, #1843, #3329, #1591, #3249, #608, #2361, #2667, #2876, #2830, #3153, #3135; jellyfin-androidtv #5794, #5822, #5184.
