# SleepyOwl

SleepyOwl — open-source клиент Jellyfin на Kotlin: сначала для Android TV, теперь есть и версия для Linux (ПК).
Лицензия GPL-3.0-or-later. Название и логотип свои, без бренда Jellyfin. Пакет `ru.stenfredd.sleepyowl`
(прежнее рабочее название — Movies, `ru.stenfredd.movies`).

**Цель:** воспроизводить всё, что можно, без серверного транскода и с правильным HDR и Dolby Vision. Другие клиенты
на тестовом ТВ спотыкались на разных файлах: транскодировали, давали тусклый HDR или зависали.

- **Android TV:** гибрид media3/ExoPlayer (основной: аппаратный MediaCodec, HDR10/HDR10+/HLG/DV, passthrough) +
  libVLC (запасной, программный ffmpeg). Движок выбирается автоматически по «весу потерь»; при падении или зависании
  сессия переходит на другой движок с той же позиции. Серверный транскод — последний вариант. На тестовой медиатеке
  (12 671 файл: MKV/AVI/MP4, DivX, H.264 Hi10P, HEVC HDR10, DV, AV1, MPEG-2, AC3/DTS/TrueHD, ASS/PGS) транскод
  не понадобился ни разу.
- **Linux (ПК):** движок libmpv, плеер в окне, во весь экран и плавающим окном поверх остальных, MPRIS, пакет .deb.
  HDR на ПК сводится к SDR.
- **Общий код** — модули Kotlin Multiplatform (android + desktop): `core:*`, `playback:api`, `playback:core`,
  `ui:shared` (Compose Multiplatform). DI — Koin.
- **Стек:** Kotlin, Jetpack Compose / Compose Multiplatform, Navigation 3, Coil 3, `jellyfin-sdk-kotlin` 1.9
  (сервер Jellyfin 12.x), media3, libVLC, ass-media (libass), libmpv (JNA), OkHttp, DataStore.
- **Тестовое устройство:** Xiaomi TV S Pro Mini LED 75 (MediaTek MT9689, Android 14, только 32-bit, 2,4 ГБ RAM,
  слабый Mali GPU). APK содержит `armeabi-v7a` и `arm64-v8a`.

## Документы

| Файл | О чём |
|---|---|
| [development.md](development.md) | Сборка, инструменты в `tools/`, личные настройки в `local/` |
| [playback.md](playback.md) | **Главный документ по воспроизведению:** архитектура, выбор движка, все найденные проблемы media3/libVLC/ТВ MediaTek и их решения, сеть, память, как тестировали |
| [tv-app.md](tv-app.md) | Приложение для Android TV: архитектура интерфейса, решения, ловушки фокуса и пульта, особенности сервера, по экранам |
| [ui-performance.md](ui-performance.md) | Производительность интерфейса на слабом GPU ТВ: что сколько стоит, как мерить, какие приёмы использовать |
| [launcher-feed.md](launcher-feed.md) | Контракт «Продолжить просмотр» для своей оболочки главного экрана ТВ |
| [desktop.md](desktop.md) | Версия для Linux: решения, архитектура (mpv в Compose, окна X11, MPRIS), находки и ловушки, тестовый стенд |
| [research/](research/) | Исходные исследования до кода: Jellyfin API и клиенты, media3, libVLC против libmpv, тестовый ТВ, профиль медиатеки, дампы ТВ |
