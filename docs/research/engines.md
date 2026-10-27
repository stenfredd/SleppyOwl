# Несколько движков воспроизведения в Android-клиенте Jellyfin: архитектура, выбор движка, fallback

Дата: 2026-10-12. Метод: shallow-клоны репозиториев (HEAD на 10–12.10.2026) и чтение исходников, Maven Central (maven-metadata, POM, скачанные AAR: размеры `.so`, выравнивание ELF LOAD через `readelf`), GitHub API, документация Android/Kodi.
Всё, что помечено «(не проверено)», не подтверждено кодом или первоисточником.

---

## 0. TL;DR

1. **Почти все клиенты выбирают движок вручную, глобальной настройкой** (Findroid, AFinity, JellyPlay, Swiftfin, Streamyfin, Nova, Just Player). Автоматика есть только у трёх:
   - **Wholphin**: режим `PREFER_MPV` — если контент HDR (или 4K при software-декоде), берётся ExoPlayer, иначе mpv.
   - **Void**: режим `AUTO` — HDR или Dolby Vision идут в ExoPlayer, всё остальное в mpv.
   - **Kodi**: правила в `playercorefactory.xml` по протоколу, расширению, кодеку, разрешению и `hdrtype`; плюс меню «Play using…».
2. **Автоматическое переключение на другой движок при runtime-ошибке декодера реализовано только в Plezy** (Flutter-клиент Plex, нативная часть на Kotlin). Схема ExoPlayer → mpv:
   - сначала попытка восстановиться внутри ExoPlayer;
   - срабатывает по `onPlayerError`, когда трек видео не выбран, по watchdog «декодер завис» (входные буферы есть, выходных нет) и по watchdog «чёрный экран»;
   - воспроизведение продолжается с той же позиции.

   Wholphin и AFinity при ошибке переходят на **серверный транскод тем же движком**. JellyPlay сознательно не делает «тихого» fallback.
3. **DeviceProfile везде свой для каждого движка, объединения возможностей нет ни у кого.**
   - Wholphin: `if (backend == EXO_PLAYER) deviceProfileService… else mpvDeviceProfile`.
   - Swiftfin: `VideoPlayerType.directPlayProfiles/transcodingProfiles/subtitleProfiles`.
   - JellyPlay: `DeviceProfileProvider.forPlayer(PlayerType)`.
   - Findroid: шлёт пустой профиль «Direct play all» и всегда берёт статический поток.
4. **Абстракция бывает двух видов.**
   - Движок как media3 `Player`. Findroid и AFinity: `MPVPlayer : BasePlayer`. Wholphin: `MpvPlayer : BasePlayer`, стаб `: SimpleBasePlayer`.
   - Собственный интерфейс: jellyfin-androidtv `PlayerBackend`, JellyPlay `MediaEngine` (~640 строк контракта + `EngineCapabilities`), Swiftfin `MediaPlayerProxy` + capability-протоколы, Nova `IMediaPlayer`.

   Реализации «mpv как Player» полны `TODO()` и `UnsupportedOperationException`. В Findroid около 15 мест `TODO("Not yet implemented")`, включая `setVideoSurface` и `setVideoTextureView`.
5. **Рекомендация по архитектуре.** Свой узкий интерфейс `PlaybackEngine`, разбитый на capability-интерфейсы (как у Swiftfin), и **один** адаптер `EngineSessionPlayer : SimpleBasePlayer` в ядре. Адаптер нужен для MediaSession, состояний media3-ui-compose и `PlayerSurface`; подход как у jellyfin-androidtv `MediaSessionPlayer : SimpleBasePlayer`. Каждый движок живёт в отдельном Gradle-модуле и регистрируется через Hilt `@IntoSet`. Наборы движков задаются product flavors `full`/`lite`. Стаб-модули не нужны.
6. **Fallback-движок сейчас: libVLC 3.7.x (`org.videolan.android:libvlc-all:3.7.7`).**
   - официальный артефакт VideoLAN с регулярными релизами (3.7.6 — 11.09.2026, 3.7.7 — 01.10.2026);
   - стабильный Java API;
   - HDR через MediaCodec на Surface (не проверено в этом раунде);
   - внутри libass, PGS, DVB и VobSub;
   - выравнивание 16 КБ на месте.

   Минус — размер: arm64 `.so` занимают 55 МБ без сжатия и 24 МБ со сжатием. libmpv стоит держать вторым движком на будущее, но не делать основным fallback: у него нет официального Android-артефакта, и в стоковом виде он не выводит HDR (тонмапит в SDR через GLES).

---

## 1. Как устроено в существующих клиентах

### 1.1 Findroid (jarnedemeulemeester/findroid, Kotlin)
- **Модули:** `:player:core`, `:player:local`, `:app:phone`, `:app:tv`. Движки лежат в `player/local/src/main/java/dev/jdtech/jellyfin/player/local/`:
  - `mpv/MPVPlayer.kt` (1659 строк) — `class MPVPlayer(...) : BasePlayer(), MPVLib.EventObserver, AudioManager.OnAudioFocusChangeListener`.
  - ExoPlayer используется напрямую.
- **Выбор:** `PlayerViewModel.kt` читает `appPreferences.playerBackend` и делает `when ("exoplayer" | "mpv")` (стр. 135–173). Это глобальная пользовательская настройка, по умолчанию `"exoplayer"` (`AppPreferences.kt:29`). Автоматики нет.
- **Ошибки:** fallback отсутствует, `onPlayerError` в ViewModel не используется для смены движка.
- **DeviceProfile:** один для всех движков — `DeviceProfile(name="Direct play all", directPlayProfiles=empty, transcodingProfiles=empty, …, subtitleProfiles=[srt,ass EXTERNAL])` (`data/.../JellyfinRepositoryImpl.kt:~315`). Дальше всегда `getVideoStreamUrl(static=true)`, то есть клиент сознательно не пользуется серверным транскодом.
- **Зависимость:** `dev.jdtech.mpv:libmpv:1.0.0` подключена как `implementation` в `:player:local` и как `compileOnly` в `:app:phone`.
- **Сборка:** один flavor `libre`, ABI splits для `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`. Для сборки bundle splits отключаются из-за issue AGP 402800800.
- **Состояние MPVPlayer:** около 15 `TODO("Not yet implemented")`, включая `setVideoSurface`, `setVideoSurfaceHolder` и `setVideoTextureView`. Реализован только `setVideoSurfaceView`.

### 1.2 Wholphin (damontecres/Wholphin, Android TV, Compose)
- **Модули:** `:app` и `:wholphin-mpv-stub`. Настоящие нативные модули живут в отдельном репозитории `damontecres/wholphin-extensions`: `wholphin-mpv` и `wholphin-media3` (ffmpeg- и av1-декодеры). Они публикуются в **GitHub Packages, а там анонимное чтение невозможно** — README требует classic PAT.
- **Подключение в `app/build.gradle.kts` (стр. 30–36, 370–397), каскадом:**
  1. локальный `libs/wholphin-mpv-release.aar`;
  2. иначе prebuilt из репозитория `WholphinExtensions…`;
  3. иначе `project(":wholphin-mpv-stub")`.

  То же сделано для ffmpeg и av1.
- **Стаб:** `wholphin-mpv-stub/.../MpvPlayer.kt` — `class MpvPlayer(...) : SimpleBasePlayer(Looper.getMainLooper()) { override fun getState() = throw MpvStubException() }`. Он нужен потому, что код `:app` напрямую ссылается на класс `MpvPlayer`.
- **Абстракция:** media3 `Player`. `services/PlayerFactory.kt` возвращает `PlayerCreation(player: Player, assHandler)`. Настоящий `MpvPlayer` в extensions — `BasePlayer()`, около 1600 строк; часть методов бросает `UnsupportedOperationException`.
- **MediaSession:** `MediaSessionPlayer : ForwardingSimpleBasePlayer(player)`.
- **Видео:** `PlayerSurface(player, surfaceType = SURFACE_TYPE_SURFACE_VIEW)` из media3-ui-compose, один и тот же для Exo и mpv.
- **Выбор** (`ui/playback/PlaybackViewModel.kt:234–270`):
  ```kotlin
  PlayerBackend.PREFER_MPV -> if (isHdr || (is4k && softwareDecoding)) EXO_PLAYER else MPV
  ```
  Варианты `PlayerBackend`: `EXO_PLAYER`, `MPV`, `PREFER_MPV`, `EXTERNAL_PLAYER`. Движок можно передать и через `Destination.Playback.backend`, то есть выбрать «Play with…» для конкретного элемента. Если движок изменился, старый `Player` уничтожается и создаётся новый.
- **Ошибки** (`onPlayerError`, стр. ~1377): при `DIRECT_PLAY`/`DIRECT_STREAM` вызывается `changeStreams(..., positionMs = player.currentPosition, enableDirectPlay=false, enableDirectStream=false)`, то есть **переход на транскод тем же движком** с той же позиции. Смены движка нет.
- **DeviceProfile на каждый движок** (стр. ~660):
  ```kotlin
  deviceProfile = if (backend == EXO_PLAYER) deviceProfileService.getOrCreateDeviceProfile(...) else mpvDeviceProfile
  ```
  `util/mpv/MpvDeviceProfile.kt` — статический профиль с широким набором контейнеров и кодеков, у PGS/DVDSUB/DVBSUB стоят флаги `embedded=true, encode=true`.
- **Упаковка:** `packaging.jniLibs.pickFirsts += "lib/*/libc++_shared.so"`, потому что libass-android и wholphin-mpv оба кладут `libc++_shared`. Flavors `default`, `appstore`, `firetv` отличаются фичами, не движками.

### 1.3 JellyPlay (raulshma/jellyplay, KMP, бета, создан в мае 2026)
- **Документ:** `docs/player-engines.md`. Три движка: ExoPlayer (по умолчанию), libmpv (`io.github.abdallahmehiz:mpv-android-lib:0.1.12`), LibVLC (`libvlc-all:3.7.5`). Есть ещё External.
- **Абстракция:** собственный `MediaEngine` (`shared/core/player-contract/src/commonMain/.../engine/MediaEngine.kt`, 640 строк):
  - `load(PlaybackRequest)`, `play/pause/seekTo`, `StateFlow` для состояния, позиции, треков и cues;
  - `capabilities: EngineCapabilities` — около 25 флагов: `supportsAudioDelay`, `supportsSubtitleDelay`, `supportsAudioPassthrough`, `supportsAssOverride`, `supportsVideoFilters` и т. д.;
  - `updateConfig(EngineConfig)`, `setAspectRatio`, `zoomSafeSubtitleStrategy`.

  UI скрывает элементы управления по флагам capability. Поверхность вынесена в платформенный seam `AndroidSurfaceProvider`.
- **MediaSession:** только у Exo, через `Media3PlayerHost`/`asMedia3Player()`. Для mpv и VLC «JellyPlay provides its own».
- **Выбор:** в документе сказано прямо — «The choice is **per-device and manual** — JellyPlay does not auto-pick an engine based on the file's codec». Раздел «**No silent fallback.** If the chosen engine fails to decode a file, JellyPlay reports the error…»
- **DeviceProfile на каждый движок:** `shared/core/network/.../DeviceProfileProvider.kt` → `forPlayer(PlayerType)`:
  - MPV получает свой профиль (два варианта, с direct play для PGS и без);
  - EXO_PLAYER и LIBVLC получают общий `hardwareProfile`.

  Passthrough-кодеки убираются из direct-play только для движков, которые умеют bitstream.
- **Сборка:** flavors `phone`/`tv` (по платформе, не по движкам). ABI split по умолчанию только arm64 (`extraAbis` через property).
- **Неточности в документе:**
  - «ExoPlayer — no audio passthrough» неверно: media3 делает passthrough AC3, E-AC3, DTS и TrueHD через `AudioCapabilities`, а Plezy поверх этого реализует IEC-упаковку.
  - «LibVLC does not render ASS» неверно для libVLC как такового: в `libvlc.so` из 3.7.7 есть `ass_render_frame` и исходники `libass/*.c`, это проверено через `strings`.

  Скорее всего, оба утверждения описывают их собственную интеграцию, а не возможности библиотек.

### 1.4 AFinity (MakD/AFinity)
- Использует `dev.jdtech.mpv:libmpv:1.0.0`. `app/.../player/mpv/MPVPlayer.kt` — производная от Findroid, media3 `Player`.
- **Выбор:** булева настройка `preferencesRepository.useExoPlayer` (`PlayerViewModel.initializePlayer`). Автоматики нет.
- **Ошибки:** `retryWithTranscodeFallback(error)` — переход на транскод, движок не меняется.
- **MediaSession:** строится поверх любого `Player` (`MediaSession.Builder(context, player)`). Это главный плюс подхода «движок = Player».

### 1.5 Void for Jellyfin (hritwikjohri/Void-for-jellyfin; VoidTV отдельно)
- **mpv:** `dev.marcelsoftware.mpvcompose:mpv-compose:1.0.0` — Compose-обёртка, исходники в nitanmarcel/mpv-compose, единственная версия от 29.06.2025. В `MPVPlayerView.kt` работа идёт прямо через `MPVLib`.
- **Выбор:** `PlayerType { AUTO, MPV, EXOPLAYER }`. В `VideoPlayerScreen.kt:~120–170` для `AUTO` действует правило `selectedVideoStream.isHdrOrDovi() → EXOPLAYER, иначе MPV`; если выбранный движок не может получить URL, берётся второй. Это решение в момент запуска, а не реакция на ошибку.
- **Активность:** последний push Void-for-jellyfin — 31.03.2026, VoidTV — 30.01.2026. Проект стоит.

### 1.6 Streamyfin (React Native/Expo)
- **Модули:** собственные Expo-модули `modules/mpv-player` (Android: `dev.jdtech.mpv:libmpv:1.0.0`, iOS: свой) и `modules/exoplayer-player`.
- **Выбор:** `utils/atoms/settings.ts`. **MPV — «universal default», ExoPlayer — opt-in только на Android TV**: `getActiveVideoPlayerEngine()`, `isExoPlayerSupported`. VLC из проекта убрали (раньше был основным, не проверено).

### 1.7 Swiftfin (jellyfin/Swiftfin, iOS/tvOS)
- **Перечисление движков:** `Shared/Objects/VideoPlayerType/VideoPlayerType.swift` — `enum VideoPlayerType { avPlayer = "native", vlc, mpv }`. mpv включается экспериментальным флагом `Defaults[.Experimental.mpvPlayer]`.
- **Профиль:** каждый тип **сам отдаёт** `directPlayProfiles`, `transcodingProfiles` и `subtitleProfiles` (vlc и mpv пока делят VLC-профили). DeviceProfile собирается из профилей выбранного движка: `MediaPlayerItem+Build.swift` → `for: videoPlayerType`.
- **Абстракция** (`MediaPlayerProxy.swift`) — набор протоколов, разделённых по возможностям. Это удачный шаблон:
  - `MediaPlayerProxy` (play/pause/stop/jump/setRate/setSeconds);
  - `VideoMediaPlayerProxy` (videoSize, droppedFrames);
  - `MediaPlayerAudioTrackConfigurable`, `MediaPlayerSubtitleTrackConfigurable` (работают с Jellyfin `MediaStream`, а не с внутренними треками движка);
  - `MediaPlayerOffsetConfigurable` (`setAudioOffset`/`setSubtitleOffset`);
  - `MediaPlayerPictureInPictureCapable`, `MediaPlayerSubtitleConfigurable`, `VideoMediaPlayerLayoutConfigurable`.

  Реализации: `MediaPlayerProxy+VLC.swift`, `+AVPlayer.swift`, `+MPV.swift`.
- **Выбор:** глобальная настройка `Defaults[.VideoPlayer.videoPlayerType]`, по умолчанию `.vlc`. Автоматики и fallback нет.

### 1.8 Plezy (edde746/plezy, Flutter, клиент Plex) — единственный с авто-fallback
- **Схема:** на Android основной движок ExoPlayer (`android/app/.../exoplayer/ExoPlayerCore.kt`), запасной — mpv (`.../mpv/MpvPlayerCore.kt`). Оба управляются плагином `ExoPlayerPlugin.kt`, который хранит `playerCore` и `mpvCore` и флаг `usingMpvFallback`. Настройка `android_use_exoplayer` позволяет сразу выбрать mpv.
- **Триггеры fallback**, все сводятся к `requestFormatFallback(...)` → `delegate.onFormatUnsupported(uri, headers, positionMs, playWhenReady, errorMessage)`:
  1. `onPlayerError`, если не помогла попытка восстановиться внутри Exo (`retryVideoDecoderInPlace`);
  2. `onTracksChanged`: группа видео есть, но ни одна не выбрана, то есть нет декодера (`requestFallbackForUnsupportedTracks`);
  3. **watchdog «decoder hang»**: `queuedInputBufferCount > 0`, а выходных буферов 0 после таймаута;
  4. **watchdog «чёрный экран»**: 0 отрисованных кадров за `WATCHDOG_TIMEOUT_MS` при `isPlaying`;
  5. отказ аудио-sink после исчерпания восстановления внутри Exo.
- **Политика повторов** (`VideoDecoderRecoveryPolicy`): если кадр уже был отрисован, формат заведомо поддерживается, поэтому сначала повтор в Exo (`MAX_CONSECUTIVE_ATTEMPTS=1`, `MAX_ATTEMPTS_PER_MEDIA=3`), и только потом mpv. Для Dolby Vision перед fallback делается попытка `retryWithDvConversion` (DV → HEVC/HDR10).
- **Передача между движками:**
  - счётчик `mediaGeneration` отсекает устаревшие события;
  - повторные запросы fallback схлопываются;
  - позиция ограничивается у конца файла (`EndOfStreamPolicy.fallbackStartPositionMs`);
  - внешние субтитры и mpv-свойства, выставленные до fallback, воспроизводятся заново;
  - таймаут инициализации mpv — 10 с.
- **mpv:** собственная сборка `edde746/mpv-build` (`mpv-build.lock.json` с SHA-256, ffmpeg 8.0.1). При HW-декоде используется **форк `vo=mediacodec`**: буферы MediaCodec сразу идут в compositor, 10-бит и HDR10/HLG dataspace сохраняются, субтитры рисуются на соседней OSD-поверхности. Комментарий в `MpvPlayerCore.kt:165–189` фиксирует, что `gpu-next` + HW-декод ломается на Tegra (синий экран на Shield). Это хорошо показывает, что HDR в стоковом mpv-android не решён.

### 1.9 jellyfin-androidtv (официальный)
- **Модули:** `playback/core` (`backend/PlayerBackend.kt`, `BackendService.kt`, `support/PlaySupportReport.kt`), `playback/media3/exoplayer` (`ExoPlayerBackend`), `playback/media3/session` (`MediaSessionPlayer : SimpleBasePlayer(looper)` поверх `PlaybackManager`), `playback/jellyfin`.
- **`PlayerBackend`:**
  - `supportsStream(MediaStream): PlaySupportReport` — Exo реализует через `exoPlayer.getPlaySupportReport(stream.toFormats())` по `RendererCapabilities`;
  - `setSurfaceView`, `setSubtitleView`, `prepareItem/playItem`, `seekTo`, `setSpeed`, `setTimedEvents`.

  `BackendService.switchBackend()` останавливает старый backend и переносит на новый surface, subtitle view и listener. Задел под несколько движков есть, но в коде только ExoPlayer; libVLC из клиента давно убрали, VLC остался внешним плеером (`VlcExternalPlayerApi.kt`).

### 1.10 Just Player (moneytoo/Player)
Движок один — ExoPlayer с локальными AAR `lib-decoder-ffmpeg`, `av1`, `iamf`, `mpegh`. Настройка `decoderPriority` → `setExtensionRendererMode` (OFF/ON/PREFER). Это «выбор декодера внутри одного движка», а не выбор движка. Приём полезен и нам: `EXTENSION_RENDERER_MODE_ON` плюс `setEnableDecoderFallback(true)`.

### 1.11 Nova Video Player (nova-video-player/aos-*)
- **Интерфейс:** `aos-MediaLib/src/com/archos/medialib/IMediaPlayer.java` с `TYPE_AVOS=0` и `TYPE_ANDROID=1`. Методы `setSubtitleDelay`, `setAvDelay`, `setSubtitleRatio`, `setAudioFilter` присутствуют прямо в интерфейсе.
- **Фабрика** `MediaFactory.createPlayer(ctx, forceSoftwareDecoding)`:
  - настройка `KEY_DEC_CHOICE == -1` → `AndroidMediaPlayer`;
  - иначе `AvosMediaPlayer` — свой нативный плеер на ffmpeg с `MP_DECODER_ANY` (HW, затем SW) или `MP_DECODER_SW`;
  - если `LibAvos` недоступен, используется Android-плеер.
- В манифесте `aos-AVP/exoplayer.xml` есть экспериментальная ветка `dev/exoplayer`.

### 1.12 Kodi
- **Выбор плеера:** `system/playercorefactory.xml` и пользовательский `userdata/playercorefactory.xml` со списком `<players>` и `<rules>`. Атрибуты правил (`xbmc/cores/playercorefactory/PlayerSelectionRule.cpp`):
  - `internetstream`, `remote`, `audio`, `video`, `dvd`, `bd`, `discimage`;
  - `protocols`, `filetypes`, `mimetypes`, `filename` (regex);
  - `audiocodec`, `audiochannels`, `videocodec`, `videoresolution`, `videoaspect`, **`hdrtype`**.

  Правила по stream details работают, только если включено извлечение флагов. Правила вкладываются друг в друга. Плеер по умолчанию задаётся алиасами `videodefaultplayer`/`audiodefaultplayer`. В контекстном меню «Play using…» показываются все подходящие плееры.
- **Fallback внутри VideoPlayer:** при открытии декодера Kodi перебирает HW-кодеки (на Android — MediaCodec (Surface) и MediaCodec) и затем ffmpeg SW. Это делается на уровне декодера, а не движка (по знанию архитектуры, в этом раунде код не открывался).

### 1.13 Сводная таблица

| Клиент | Движки | Абстракция | Выбор | Fallback при ошибке | DeviceProfile |
|---|---|---|---|---|---|
| Findroid | Exo, mpv | media3 `Player` (`MPVPlayer : BasePlayer`) | глобальная настройка | нет | один «Direct play all» |
| Wholphin | Exo, mpv (+ext) | media3 `Player` + стаб `SimpleBasePlayer` | настройка + `PREFER_MPV` (HDR→Exo) + per-item | транскод тем же движком | на каждый движок |
| JellyPlay | Exo, mpv, VLC | свой `MediaEngine` + `EngineCapabilities` | вручную, на устройство | нет, сознательно | на каждый движок |
| AFinity | Exo, mpv | media3 `Player` | булева настройка | транскод | (не проверено) |
| Void | Exo, mpv | Compose-view, без общего интерфейса | `AUTO`: HDR/DV→Exo, иначе mpv | нет | (не проверено) |
| Streamyfin | mpv, Exo (TV) | Expo-модули | настройка, mpv по умолчанию | нет | (не проверено) |
| Swiftfin | AVPlayer, VLC, mpv | `MediaPlayerProxy` + capability-протоколы | глобальная настройка | нет | на каждый движок |
| Plezy | Exo → mpv | плагин с двумя core | Exo по умолчанию, mpv по настройке | **да, автоматически** | n/a (Plex) |
| jellyfin-androidtv | Exo | `PlayerBackend` + `supportsStream` | — | — | — |
| Nova | AVOS (ffmpeg), Android MP | `IMediaPlayer` + `MediaFactory` | настройка декодера | при отсутствии lib | n/a |
| Kodi | VideoPlayer, PAPlayer, внешние | `IPlayer` + rules | правила (codec/res/hdrtype) + «Play using» | HW→SW внутри VideoPlayer | n/a |

---

## 2. Дизайн абстракции

### 2.1 Вариант A: каждый движок реализует media3 `Player`
**Плюсы**
- MediaSession, MediaController, уведомления и Google Assistant/«Now playing» на TV работают даром.
- Состояния из media3-ui-compose (`PlayPauseButtonState`, `ProgressStateWithTickInterval` и т. д.) и `PlayerSurface` работают с любым движком.
- Единая модель слушателей.

**Минусы, подтверждённые кодом**
- `Player` огромен: Timeline, плейлисты, `Tracks`/`Format`, `TrackSelectionParameters`, громкость, устройство. Реализации для mpv в Findroid, Wholphin и AFinity неполные (`TODO()`/`UnsupportedOperationException`) и падают, если сторонний код вызовет «не тот» метод. Пример — `setVideoSurface(Surface)` вместо `setVideoSurfaceView`.
- В `Player` нет audio delay, subtitle delay, aspect/zoom, deinterlace, passthrough и стилей ASS. Всё это всё равно приходится выносить в свой интерфейс и проверять `as?`, как в AFinity: `is MPVPlayer -> …` встречается десятки раз в `PlayerViewModel.kt`.
- Треки mpv и VLC приходится переупаковывать в `TrackGroup(Format)`. Связь с индексами Jellyfin `MediaStream.Index` теряется или требует хаков.
- `BasePlayer` вручную реализовывать сложно: потоки и согласованность событий. Если идти этим путём, брать только **`SimpleBasePlayer`**: он сам сравнивает состояния и рассылает события, а реализация отдаёт `State` и переопределяет `handle*`.

### 2.2 Вариант B: собственный `PlayerBackend`/`MediaEngine`
**Плюсы**
- Контракт описан в терминах домена. Треки адресуются индексами Jellyfin `MediaStream`, как в Swiftfin, где `setAudioStream(MediaStream)` удобно связывает с PlaybackInfo, отчётами о прогрессе и сменой потока на сервере.
- Возможности объявлены явно, UI показывает только поддерживаемое.
- Движок легко заменить: интерфейс маленький.

**Минусы:** MediaSession и Compose-состояния нужно соединить самостоятельно.

### 2.3 Рекомендация: вариант B и один `SimpleBasePlayer`-адаптер в ядре
Так устроен jellyfin-androidtv (`PlayerBackend` + `MediaSessionPlayer : SimpleBasePlayer`). Набор capability-интерфейсов взят у Swiftfin.

```kotlin
// :playback:api
interface PlaybackEngine {
    val id: EngineId                       // EXO, VLC, MPV…
    val state: StateFlow<EngineState>      // idle/buffering/ready/ended + playWhenReady
    val position: PositionSource           // currentPositionMs(), bufferedMs, durationMs
    val events: Flow<EngineEvent>          // FirstFrame, VideoSize, Error(EngineError), TracksChanged
    val tracks: StateFlow<EngineTracks>    // в терминах Jellyfin MediaStream.Index + engine id
    fun attach(output: VideoOutput)        // SurfaceView-based, см. 2.4
    fun detach()
    fun load(request: EngineLoadRequest)   // url, headers, startMs, внешние сабы, выбранные индексы
    fun play(); fun pause(); fun seekTo(ms: Long); fun setSpeed(x: Float)
    fun selectAudio(streamIndex: Int?); fun selectSubtitle(streamIndex: Int?)
    fun release()
}
// Опциональные возможности: проверка через `engine as? X`, UI скрывает ненужное
interface AudioDelayCapable    { fun setAudioDelay(ms: Long) }
interface SubtitleDelayCapable { fun setSubtitleDelay(ms: Long) }
interface AspectCapable        { fun setAspect(mode: AspectMode /* FIT, FILL, ZOOM, 16:9, 4:3, CUSTOM */) }
interface PassthroughCapable   { fun setPassthrough(codecs: Set<AudioCodec>) }
interface SubtitleStyleCapable { fun applyStyle(style: SubtitleStyle) }
interface DeinterlaceCapable   { fun setDeinterlace(mode: DeinterlaceMode) }
interface StatsCapable         { val stats: StateFlow<PlaybackStats> }   // decoder name, dropped frames, hw/sw

// Плагин движка: SPI, по одному на Gradle-модуль
interface EngineProvider {
    val id: EngineId
    val priority: Int
    fun capabilities(device: DeviceCaps): EngineCapabilities           // для профиля и выбора
    fun evaluate(source: MediaSourceInfo, sel: StreamSelection, device: DeviceCaps): Verdict
    fun deviceProfile(device: DeviceCaps, prefs: PlaybackPrefs): DeviceProfile
    fun create(context: Context): PlaybackEngine
}
```

Ядро (`:playback:core`):
- `PlaybackSessionController` хранит item, mediaSourceId, PlaySessionId, выбранные индексы, задержки, позицию и `generation`. Через него идут вся отчётность в Jellyfin (`/Sessions/Playing*`), выбор движка и переключение.
- `EngineSessionPlayer : SimpleBasePlayer` — **единственная** реализация `Player`. Она транслирует состояние контроллера в `State` (один `MediaItem`, позиция, длительность, playWhenReady, команды) и передаёт `handleSetPlayWhenReady`/`handleSeek`/`handleSetVideoOutput` текущему движку. На этом адаптере строятся `MediaSession`/`MediaSessionService` и Compose-состояния.
- ExoPlayer-движок держит `ExoPlayer` внутри. Ему MediaSession «из коробки» не нужна: сессия всегда смотрит на адаптер, поэтому при смене движка её не надо пересоздавать. Wholphin и AFinity, напротив, пересоздают сессию при новом `Player`.

### 2.4 Видеоповерхность
- **Только `SurfaceView`.** HDR-вывод и tunneling работают лишь с SurfaceView. TextureView проходит через композицию GPU: HDR теряется, tunneling не поддерживается, расход энергии выше. Wholphin явно использует `SURFACE_TYPE_SURFACE_VIEW`.
- **Compose:** `PlayerSurface(player = engineSessionPlayer, surfaceType = SURFACE_TYPE_SURFACE_VIEW)` вызывает `Player::setVideoSurfaceView`/`clearVideoSurfaceView` (проверено в исходнике `media3/ui/compose/PlayerSurface.kt`, ветка release; media3 1.11.1 — последняя на 12.10.2026). У `SimpleBasePlayer` это приходит в `handleSetVideoOutput(Object)`, а адаптер передаёт `SurfaceView` текущему движку:
  - Exo: `setVideoSurfaceView`;
  - libVLC: `IVLCVout.setVideoView` (или `VLCVideoLayout` со своей subtitle-поверхностью);
  - mpv: `MPVLib.attachSurface(holder.surface)`.
- **Своя обёртка для VLC:** `VLCVideoLayout` создаёт свои SurfaceView, в том числе отдельную для субтитров при direct rendering. Поэтому лучше иметь собственный `@Composable EngineVideoSurface(engine)` через `AndroidView`, который сам решает, какой View отдать движку, а PlayerSurface оставить только для Exo. Объявить в `VideoOutput` sealed-варианты: `SurfaceViewOutput(SurfaceView)` и `ViewGroupOutput(FrameLayout)`.
- **Aspect/zoom:**
  - Exo — через `AspectRatioFrameLayout`/`Modifier.resizeWithContentScale` (media3-ui-compose) или `VIDEO_SCALING_MODE`;
  - VLC — `MediaPlayer.setAspectRatio`/`setScale`/`setVideoScale`;
  - mpv — `video-aspect-override`/`panscan`.

  Всё это закрывается одним `AspectCapable`.
- **Субтитры:**
  - Exo рисует `SubtitleView` с CueGroup, ASS — через `io.github.peerless2012:ass-media` (0.5.1 от 11.08.2026, используется в Wholphin и jellyfin-androidtv);
  - VLC и mpv рисуют субтитры сами (libass внутри движка).

  В контракте нужен флаг «движок рисует субтитры сам», чтобы ядро не накладывало оверлей поверх.

### 2.5 Смена движка посреди сессии (ручная и автоматическая)
Порядок, обобщающий Plezy, Wholphin и jellyfin-androidtv `switchBackend`:
1. `generation++`; события старого движка с прежним `generation` игнорируются. Повторные запросы fallback схлопываются флагом `fallbackInProgress`.
2. Запомнить `positionMs` (у конца файла ограничить: `min(pos, duration - 10 s)`), `playWhenReady`, Jellyfin-индексы аудио и субтитров, задержки, скорость.
3. Отправить `/Sessions/Playing/Stopped` для старой PlaySessionId (или Progress с понятной причиной).
4. Старый движок: `detach()` → `release()`. VLC и mpv отпускать до создания нового, чтобы избежать конфликтов поверхности и MediaCodec. На слабых TV-боксах два одновременных HW-декодера 4K часто недоступны.
5. Вызвать `POST /Items/{id}/PlaybackInfo` с **DeviceProfile нового движка**. Получится новая PlaySessionId; DirectPlay или транскод определится заново.
6. Новый движок: `attach(output)` → `load(startMs=pos, …)`. Воспроизвести заново внешние субтитры и настройки (задержки, стиль, passthrough).
7. Отправить `/Sessions/Playing` с новым PlayMethod. Показать ненавязчивый toast «Переключено на VLC: аппаратный декодер не поддерживает VC-1» и записать причину в статистику.
8. Защита от петель: один fallback на item за сессию; если упал и fallback-движок, перейти на серверный транскод (как Wholphin и AFinity), а не обратно.

---

## 3. Gradle-модуляризация

### 3.1 Предлагаемая структура
```
:playback:api          // Android-library без нативных зависимостей: PlaybackEngine, capabilities, EngineProvider, модели
:playback:core         // SessionController, EngineSelector, EngineSessionPlayer (SimpleBasePlayer), MediaSessionService, DeviceCaps (MediaCodecList/Display)
:playback:engine-exo   // media3-exoplayer, -hls, decoder-ffmpeg (аудио), ass-media; ExoEngineProvider
:playback:engine-vlc   // org.videolan.android:libvlc-all:3.7.7; VlcEngineProvider
:playback:engine-mpv   // (позже) libmpv AAR; MpvEngineProvider
:app                   // flavors: full / lite
```
- **Регистрация через Hilt multibinding.** В каждом engine-модуле:
  ```kotlin
  @Module @InstallIn(SingletonComponent::class)
  abstract class VlcEngineModule { @Binds @IntoSet abstract fun bind(p: VlcEngineProvider): EngineProvider }
  ```
  Ядро получает `Set<@JvmSuppressWildcards EngineProvider>`. Если модуль не подключён, движка просто нет. **Стаб не нужен**: Wholphin он понадобился только потому, что `:app` ссылается на класс `MpvPlayer` напрямую. Альтернатива без DI — `ServiceLoader` с `META-INF/services` (R8 его поддерживает).
- **Flavors по движкам:**
  ```kotlin
  flavorDimensions += "engines"
  productFlavors {
      create("full") { dimension = "engines"; isDefault = true }   // Exo + VLC
      create("lite") { dimension = "engines"; applicationIdSuffix = ".lite" }   // только Exo
  }
  dependencies {
      implementation(project(":playback:engine-exo"))
      "fullImplementation"(project(":playback:engine-vlc"))
  }
  ```
  Можно добавить второе измерение `store` (`github`, `play`, `amazon`), как Wholphin делает `default/appstore/firetv`.
- **Изоляция API движков.** Нативный AAR подключается как `implementation` только в engine-модуле; `api` для него не делать, чтобы классы libVLC не протекали в `:app`. Разработчикам, не трогающим VLC, можно быстро собирать `liteDebug`.
- **Конфликт `libc++_shared.so`:** у libVLC, libmpv и ass-media своя копия, нужен `packaging { jniLibs { pickFirsts += "lib/*/libc++_shared.so" } }` (как в Wholphin). Версии libc++ из разных NDK формально несовместимы. Лучше проверить, что `full` с VLC и ass-media не падает; ass-media в VLC-сборке не обязателен, так что риск можно убрать, оставив ass-media только в пути Exo.

### 3.2 Размеры (измерено по AAR с Maven Central, только arm64-v8a)

| Артефакт | Дата | `.so` без сжатия | `.so` в deflate | 16 КБ (`LOAD` align) |
|---|---|---|---|---|
| `org.videolan.android:libvlc-all:3.7.7` | 01.10.2026 | **55,3 МБ** (libvlc.so 45,9 + libc++ 9,3) | 24,1 МБ | 0x4000 ✔ |
| `dev.jdtech.mpv:libmpv:1.0.0` (mpv 0.41.0, ffmpeg 8.1) | 06.04.2026 | **25,0 МБ** | 11,6 МБ | 0x4000 ✔ |
| `io.github.abdallahmehiz:mpv-android-lib:0.1.12` | 13.02.2026 | 38,3 МБ (с avfilter и libxml2) | 15,8 МБ | 0x4000 ✔ |

- **Влияние `useLegacyPackaging`.** При `minSdk ≥ 23` AGP по умолчанию кладёт `.so` в APK без сжатия (`extractNativeLibs=false`), поэтому APK на GitHub или F-Droid вырастет на «несжатую» цифру: около +55 МБ для VLC. Варианты:
  - `packaging.jniLibs.useLegacyPackaging = true`: APK примерно +24 МБ, но после установки библиотеки распаковываются;
  - для Play (AAB) — download size примерно равен сжатому размеру.
- **ABI.** Нужны per-ABI APK (`splits.abi`) или AAB. **armeabi-v7a обязателен для Android TV**: многие TV-устройства работают в 32-битном userspace на 64-битном CPU (по известным данным, ряд Fire TV Stick и Chromecast with Google TV; не проверено в этом раунде). x86 и x86_64 нужны только для эмулятора. Findroid собирает 4 ABI, Wholphin 3 (+universal), JellyPlay по умолчанию только arm64.
- **16 КБ:** все три AAR уже выровнены по 16 КБ (`-Wl,-z,max-page-size=16384` есть в `libmpv-android/buildscripts/build.sh:51`). AGP ≥ 8.5.1 сам выравнивает несжатые `.so` в zip.

### 3.3 Dynamic Feature Delivery для нативного движка
- **Технически возможно.** `.so` можно положить в on-demand feature-модуль, загрузка через `SplitCompat`/`SplitInstallHelper.loadLibrary` или ReLinker, зависимые `.so` грузятся вручную по порядку (документация Play on-demand).
- **Практически не годится:**
  - работает только при установке из Google Play; при sideload возвращаются `REQUIRES_USER_CONFIRMATION`/`APP_NOT_OWNED`/`PLAY_STORE_NOT_FOUND`;
  - для F-Droid, GitHub APK и Amazon Appstore не работает;
  - поддержка on-demand на Android TV в документации не описана (не проверено);
  - Hilt и feature-модули сочетаются плохо: граф DI идёт `app → feature`, а не наоборот, поэтому нужен reflection или ServiceLoader.

  **Вывод:** использовать flavors `full`/`lite`; DFD откладывается до появления Play-дистрибуции, и тогда только как опция.

---

## 4. libVLC или libmpv в роли fallback-движка

### 4.1 Сравнение

| Критерий | libVLC 3.7.x (`libvlc-all`) | libmpv (сторонние AAR) |
|---|---|---|
| Артефакт | **Официальный VideoLAN на Maven Central.** 3.7.6 — 11.09.2026, 3.7.7 — 01.10.2026; 4.0.0-eap29 — 08.07.2026 (libVLC 4 всё ещё EAP). vlc-android активен (push 07.10.2026). | Официального AAR нет. Варианты: `dev.jdtech.mpv:libmpv` (Findroid; релизы 0.5.1 — 07.2025, **1.0.0 — 04.2026**; в репозитории renovate регулярно обновляет зависимости, ffmpeg 9.0.2 от 20.09.2026, но релизов не было с апреля); `io.github.abdallahmehiz:mpv-android-lib` (0.1.12, 02.2026); `wholphin-extensions` (GitHub Packages, нужен токен); Plezy (`edde746/mpv-build`, свои tarball); `mpv-compose` (одна версия 2025). |
| Размер arm64 | 55 МБ без сжатия / 24 МБ сжато | 25 / 11,6 МБ (jdtech) |
| 16 КБ | ✔ | ✔ |
| Android API | Java/Kotlin API: `LibVLC`, `MediaPlayer`, `Media`, `IVLCVout`, `VLCVideoLayout`; треки, `setSpuDelay`/`setAudioDelay`, `setAspectRatio`/`setScale`, события. Стабилен много лет. | `MPVLib`: строковые свойства и команды через JNI плюс observer. Обёртку-плеер пишешь сам (~1600 строк в Findroid/Wholphin). Исторически глобальный синглтон, один экземпляр на процесс. |
| HW-декод | MediaCodec, с direct rendering в Surface | `hwdec=mediacodec` (в GLES-текстуру) или `mediacodec-copy` |
| **HDR на Android TV** | При HW-декоде с direct rendering кадр уходит на Surface с метаданными, телевизор получает HDR10/HLG, как в VLC for Android (по прошлому исследованию; не проверено в этом раунде). DV — только через HEVC base layer или профиль 8.1 на совместимых SoC (не проверено). | В стоковом mpv-android `vo=gpu`/`gpu-next` (GLES), **HDR тонмапится в SDR**: в `MPVView.kt` нет HDR-пути, `mediacodec_embed` по умолчанию отключён. Wholphin (`PREFER_MPV`) и Void (`AUTO`) поэтому отправляют HDR в Exo. Plezy понадобился форк `vo=mediacodec`. |
| Субтитры | libass внутри (подтверждено `strings`), DVB/PGS/VobSub через ffmpeg и spudec, есть отдельная субтитровая поверхность | libass с полным override стилей (`sub-ass-override`), лучший ASS-рендер; PGS/VobSub через libav |
| Exotic SW-декод | ffmpeg внутри (VC-1, WMV, MPEG-2, Hi10P, DivX/Xvid, RV) | то же: ffmpeg (VC-1, PGS, dvdsub подтверждены `strings`) |
| Passthrough | есть (SPDIF/HDMI encoded AudioTrack) | `audio-spdif=…` с `ao=audiotrack` (JellyPlay и Plezy пользуются) |
| Стабильность жизненного цикла | Известные подводные камни: строгий порядок `detachViews` → `stop` → `release`, один `LibVLC` на процесс, нативные падения при быстром release/re-create (из опыта сообщества; не проверено) | Глобальное состояние MPVLib, `vo`/surface attach/detach, баги GLES на конкретных SoC (Tegra + gpu-next → синий экран, Plezy #2010) |
| Перспектива | libVLC 4 сменит API (EAP, сроки неизвестны). При изолированном модуле переход займёт один модуль. | Зависимость от одного стороннего сопровождающего AAR. Сборка своими силами (buildscripts mpv-android) — заметная нагрузка на CI. |

### 4.2 Рекомендация
**Fallback-движок сейчас — libVLC 3.7.7.** Решающие факторы для клиента, у которого главная цель — **корректный HDR без транскода**:
1. При HW-декоде HDR не деградирует в SDR, и это стоковое поведение. У mpv без форка HDR теряется.
2. Официальный, регулярно обновляемый артефакт и стабильный Java API.
3. Полный набор субтитров, включая ASS, PGS и VobSub, плюс passthrough.

Цена — около +24–55 МБ на ABI; её компенсирует flavor `lite`.

**mpv** заложить как будущий `:playback:engine-mpv` для ценителей ASS и аниме и для тонких настроек. Выбирать его автоматически только для SDR-контента с «тяжёлым» ASS. Брать `dev.jdtech.mpv:libmpv` (Maven Central, 16 КБ, используется в Findroid, AFinity и Streamyfin) или собирать самим по `libmpv-android/buildscripts`.

**libVLC 4:** когда выйдет стабильная версия, добавить `:playback:engine-vlc4` рядом с текущим модулем. Провайдер с большим `priority` вытеснит старый без изменений в ядре.

---

## 5. Алгоритм автоматического выбора движка

### 5.1 Возможности устройства (`DeviceCaps`, кэшировать на запуск и смену дисплея)
- **`MediaCodecList(REGULAR_CODECS)`:** для каждого mime (`video/avc`, `video/hevc`, `video/x-vnd.on2.vp9`, `video/av01`, `video/dolby-vision`, `video/mpeg2`, `video/wvc1`/`video/vc1`, `video/mp4v-es`) собрать:
  - `isHardwareAccelerated` (API 29+) или эвристику по имени (`c2.android.*`/`OMX.google.*` — software);
  - `CodecCapabilities.profileLevels` (AVC High10 = `AVCProfileHigh10`, HEVC Main10 / Main10HDR10 / HDR10Plus, VP9 Profile2HDR, AV1 Main10HDR10, DV-профили `DolbyVisionProfileDvheSt` (P8) / `DvheStn` (P5) / `DvavSe` и т. д.);
  - `VideoCapabilities.areSizeAndRateSupported(w,h,fps)`.
- **Дисплей:** `Display.getHdrCapabilities().supportedHdrTypes` (до API 34) или `Display.Mode.getSupportedHdrTypes()` (API 34+), а также `isHdrSdrRatioAvailable`.
- **Аудио:** `AudioCapabilities` media3 — `isPassthroughSupported` для AC3, E-AC3, DTS, DTS-HD, TrueHD.
- **Exo-движок:** надёжнее всего спрашивать сам Exo, как jellyfin-androidtv: собрать `Format` из `MediaStream` и проверить `RendererCapabilities.supportsFormat` у видео- и аудиорендереров (`getPlaySupportReport`). Тогда учитываются и extension-декодеры (ffmpeg-аудио, dav1d).

### 5.2 Входные данные Jellyfin
`MediaSourceInfo.Container`, `MediaStreams[]`:
- **Видео:** `Codec`, `Profile`, `Level`, `BitDepth`, `Width/Height`, `RealFrameRate`, `IsInterlaced`, `VideoRange`, **`VideoRangeType`** (`SDR, HDR10, HDR10Plus, HLG, DOVI, DOVIWithHDR10, DOVIWithHLG, DOVIWithSDR, …`; у новых серверов есть варианты с EL и `DOVIInvalid` — проверить по версии SDK), `DvProfile`, `DvLevel`, `DvBlSignalCompatibilityId`, `RpuPresentFlag`, `ElPresentFlag`, `BlPresentFlag`, `CodecTag` (xvid/divx).
- **Аудио:** `Codec`, `Profile` (DTS-HD MA, TrueHD/Atmos), `Channels`.
- **Субтитры:** `Codec` (`subrip, ass, ssa, webvtt, pgssub, dvdsub, dvbsub, mov_text`), `IsExternal`, `DeliveryMethod`.

### 5.3 Вердикт по каждому движку
```kotlin
sealed interface Verdict {
    data class Direct(val quality: Int, val notes: Set<Note>) : Verdict  // quality: 100 = идеально
    data class Degraded(val quality: Int, val losses: Set<Loss>) : Verdict // HDR→SDR, ASS упрощён, нет passthrough, SW 4K
    data class No(val reasons: Set<Reason>) : Verdict
}
enum class Loss { HDR_TONEMAPPED, DV_TO_BASE_LAYER, ASS_SIMPLIFIED, NO_PASSTHROUGH, SOFTWARE_DECODE_HEAVY, NO_DEINTERLACE }
```

**ExoProvider.evaluate**
1. **Контейнер:** `mkv, webm, mp4, m4v, mov, ts, m2ts, mpegts, avi (AviExtractor), flv, ogg, mp3, flac, wav` → ок. `asf/wmv, rm/rmvb, vob/iso/BDMV, mpeg-ps (частично)` → `No(CONTAINER)`.
2. **Видео:**
   - если `supportsFormat` == `FORMAT_HANDLED` по HW (или dav1d для AV1) → ок;
   - `h264` с `BitDepth=10` (Hi10P) почти нигде нет в HW → `No(VIDEO_CODEC)`;
   - `vc1/wmv3`, `msmpeg4*`, `rv*`, `mpeg4` с packed bitstream (DivX) → обычно `No`;
   - `mpeg2video` → по MediaCodecList, на многих TV-SoC есть;
   - HEVC выше Level HW-декодера (например, 4K120) → `No`.
3. **HDR:** у Exo потерь нет, если HW-декодер поддерживает профиль, а дисплей — тип HDR (или OS тонмапит сама).
   - `DOVI*`: есть DV-декодер нужного профиля → Direct. Нет, но BL совместим (`DvBlSignalCompatibilityId` 1/2/4 → HDR10/SDR/HLG) → `Degraded(DV_TO_BASE_LAYER)` через подмену mime (так делает Plezy `DvBitstreamSanitizer`). P5 без DV-декодера → `No` (цвета будут неправильные).
   - `DOVIWithEL` (P7 FEL): Direct только на редких SoC, иначе BL HDR10 → Degraded.
4. **Субтитры** (выбранные по умолчанию или пользователем):
   - `subrip/webvtt/mov_text/ttml` → ок;
   - `pgssub`, `dvdsub` (VobSub в MKV), `dvbsub` → у media3 есть парсеры, ок (проверить на своих файлах);
   - `ass/ssa` → с ass-media ок, без него `Degraded(ASS_SIMPLIFIED)`.
5. **Аудио:** при passthrough-возможностях sink ок. TrueHD/DTS без passthrough → ffmpeg-extension (если в сборке) → ок, иначе `No(AUDIO)`.
6. **Interlaced** (`IsInterlaced`, типично MPEG-2/H.264 из ТВ-записей): Exo деинтерлейс не делает (на части SoC его делает сам декодер) → `Degraded(NO_DEINTERLACE)`.

**VlcProvider.evaluate**
- Контейнер и кодек → почти всегда ок.
- HDR через HW → Direct, если кодек есть в HW; при SW-декоде HDR → `Degraded(HDR_TONEMAPPED)` (не проверено).
- 4K HEVC 10-bit или Hi10P 1080p на слабом SoC → `Degraded(SOFTWARE_DECODE_HEAVY)` по эвристике «пиксели × fps × битность против класса CPU» (`Build.SUPPORTED_ABIS`, `ActivityManager.isLowRamDevice`, число ядер).
- ASS ок, PGS/VobSub ок, passthrough ок.

### 5.4 Правило выбора
```kotlin
fun choose(src: MediaSourceInfo, sel: StreamSelection, caps: DeviceCaps,
           providers: List<EngineProvider>, prefs: EnginePrefs): EngineChoice {
    prefs.forcedEngineFor(itemId = src.itemId, libraryId = src.libraryId)?.let { return it }  // per-item и per-library override
    if (prefs.mode is Manual) return EngineChoice(prefs.mode.engine)

    val verdicts = providers.associateWith { it.evaluate(src, sel, caps) }
    val direct = verdicts.filterValues { it is Verdict.Direct }
    val degraded = verdicts.filterValues { it is Verdict.Degraded }

    // 1. Основной движок (Exo), если он даёт Direct
    direct.keys.minByOrNull { it.priority }?.let { return EngineChoice(it.id, reason = "direct") }

    // 2. Конфликт «HDR против совместимости»: HDR важнее мелких потерь
    //    Exo Degraded(ASS_SIMPLIFIED | NO_DEINTERLACE) лучше, чем VLC с HDR_TONEMAPPED
    val ranked = degraded.entries.sortedWith(
        compareBy<Map.Entry<EngineProvider, Verdict>> { lossWeight((it.value as Verdict.Degraded).losses) }
            .thenBy { it.key.priority })
    ranked.firstOrNull()?.let { (p, v) ->
        // Если лучший вариант теряет HDR, а сервер может транскодировать с HDR (или в DV→HDR10) — спросить политику
        if (Loss.HDR_TONEMAPPED in (v as Verdict.Degraded).losses && prefs.preferServerOverHdrLoss)
            return EngineChoice(EXO, forceTranscode = true, reason = "keep HDR via server")
        return EngineChoice(p.id, reason = v.losses.toString())
    }
    // 3. Никто не может → транскод основным движком (профиль Exo)
    return EngineChoice(EXO, forceTranscode = true, reason = "no local engine")
}
// Пример весов: HDR_TONEMAPPED=100, DV_TO_BASE_LAYER=30, SOFTWARE_DECODE_HEAVY=40, ASS_SIMPLIFIED=20, NO_PASSTHROUGH=15, NO_DEINTERLACE=10
```
**Порядок вызовов с сервером:**
1. Решение принимается **до** `PlaybackInfo` по `MediaSources`/`MediaStreams` элемента (`/Items/{id}` с `Fields=MediaSources,MediaStreams` или результат предварительного PlaybackInfo).
2. Затем `PlaybackInfo` вызывается с **DeviceProfile выбранного движка**. Так делают Wholphin, Swiftfin и JellyPlay; объединять профили нельзя, иначе сервер разрешит DirectPlay того, что Exo не умеет.
3. Если сервер вернул транскод для профиля Exo, а VLC мог бы играть напрямую, сменить выбор и повторить PlaybackInfo с профилем VLC: прямое воспроизведение лучше транскода.
4. **Смена аудио- или субтитр-трека пользователем** запускает выбор заново. Пример: включили PGS в HDR-фильме, Exo справляется, остаёмся на нём; включили сложный ASS при SDR — можно предложить VLC или mpv с переключением как в 2.5.

### 5.5 Runtime-fallback (по образцу Plezy)
- **Триггеры:**
  - `PlaybackException` с кодами `ERROR_CODE_DECODER_INIT_FAILED`, `ERROR_CODE_DECODING_FAILED`, `ERROR_CODE_DECODING_FORMAT_UNSUPPORTED`/`EXCEEDS_CAPABILITIES`, `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`;
  - `onTracksChanged`: есть видеогруппа, но ни одна не выбрана;
  - watchdog: `DecoderCounters` показывают вход без выхода дольше N с;
  - 0 кадров (`onRenderedFirstFrame` не пришёл) дольше M с при `isPlaying`;
  - `AudioSink` init failure после восстановления.
- **Порядок:**
  1. если кадр уже был отрисован, один повтор в Exo (пересоздать декодер, `seekTo(pos)`);
  2. для DV — попытка DV → HDR10;
  3. переход на следующий движок по ранжированию из 5.4;
  4. серверный транскод.

  Не больше одного перехода на движок за item.
- **Наблюдаемость:** записывать в локальный журнал `(item, codec/profile/rangeType, device model/SoC, decoder name, exception code, выбранный fallback)`. По журналу потом уточнять правила 5.3, например заносить в denylist конкретные `codecName` на конкретных SoC.
- **Настройки UI:**
  - «Движок: Авто / ExoPlayer / VLC»;
  - «Автоматически переключаться при ошибке: вкл»;
  - «Предпочитать сохранение HDR (транскод) вместо SW-декода»;
  - per-item «Воспроизвести с помощью…» (как `Destination.Playback.backend` в Wholphin и «Play using…» в Kodi);
  - per-library override (например, «Аниме → mpv»).

---

## 6. Ссылки (проверены, если не указано иное)
- Findroid: https://github.com/jarnedemeulemeester/findroid — `player/local/.../mpv/MPVPlayer.kt`, `player/local/.../presentation/PlayerViewModel.kt`, `data/.../JellyfinRepositoryImpl.kt`
- Wholphin: https://github.com/damontecres/Wholphin — `app/.../services/PlayerFactory.kt`, `app/.../ui/playback/PlaybackViewModel.kt`, `app/.../util/mpv/MpvDeviceProfile.kt`, `wholphin-mpv-stub/`, `app/build.gradle.kts`
- wholphin-extensions: https://github.com/damontecres/wholphin-extensions (v0.2.4, 11.09.2026)
- JellyPlay: https://github.com/raulshma/jellyplay — `docs/player-engines.md`, `shared/core/player-contract/.../engine/MediaEngine.kt`, `shared/core/network/.../DeviceProfileProvider.kt`, `.../engine/Media3PlayerHost.kt`
- AFinity: https://github.com/MakD/AFinity — `app/.../ui/player/PlayerViewModel.kt`
- Void: https://github.com/hritwikjohri/Void-for-jellyfin — `domain/model/playback/PlayerType.kt`, `presentation/ui/screen/player/VideoPlayerScreen.kt`
- Streamyfin: https://github.com/streamyfin/streamyfin — `utils/atoms/settings.ts`, `modules/mpv-player`, `modules/exoplayer-player`
- Swiftfin: https://github.com/jellyfin/Swiftfin — `Shared/Objects/VideoPlayerType/VideoPlayerType.swift`, `Shared/Objects/MediaPlayerManager/MediaPlayerProxy/*.swift`
- Plezy: https://github.com/edde746/plezy — `android/app/.../exoplayer/ExoPlayerPlugin.kt`, `ExoPlayerCore.kt`, `VideoDecoderRecoveryPolicy.kt`, `.../mpv/MpvPlayerCore.kt`, `mpv-build.lock.json`
- jellyfin-androidtv: https://github.com/jellyfin/jellyfin-androidtv — `playback/core/src/main/kotlin/backend/PlayerBackend.kt`, `BackendService.kt`, `playback/media3/session/.../MediaSessionPlayer.kt`
- Just Player: https://github.com/moneytoo/Player — `Prefs.java` (`decoderPriority`)
- Nova: https://github.com/nova-video-player/aos-MediaLib — `IMediaPlayer.java`, `MediaFactory.java`; https://github.com/nova-video-player/aos-AVP (`exoplayer.xml`)
- Kodi: https://github.com/xbmc/xbmc/blob/master/system/playercorefactory.xml, `xbmc/cores/playercorefactory/PlayerSelectionRule.cpp`; https://kodi.wiki/view/External_players (403 при загрузке, не проверено)
- media3 PlayerSurface: https://github.com/androidx/media/blob/release/libraries/ui_compose/src/main/java/androidx/media3/ui/compose/PlayerSurface.kt
- libmpv-android: https://github.com/jarnedemeulemeester/libmpv-android (`buildscripts/include/depinfo.sh`, `build.sh`)
- mpv-android: https://github.com/mpv-android/mpv-android (`app/src/main/java/is/xyz/mpv/MPVView.kt`)
- Maven Central: https://repo1.maven.org/maven2/org/videolan/android/libvlc-all/ , https://repo1.maven.org/maven2/dev/jdtech/mpv/libmpv/ , https://repo1.maven.org/maven2/io/github/abdallahmehiz/mpv-android-lib/
- ass-media: https://repo1.maven.org/maven2/io/github/peerless2012/ass-media/ (0.5.1)
- Play Feature Delivery on-demand: https://developer.android.com/guide/playcore/feature-delivery/on-demand
