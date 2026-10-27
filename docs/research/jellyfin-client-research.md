# Исследование: нативный Kotlin-клиент Jellyfin для Android и Android TV на libVLC

Дата сбора: 2026-10-11. Документ собран из шести параллельных исследований (веб-поиск, чтение исходников, Maven/GitHub API). Все ссылки проверялись на доступность; непроверенные помечены «(не проверено)».

## Содержание

1. [Jellyfin API, официальные SDK, протокол](#часть-1-jellyfin-api-официальные-sdk-и-протокольная-документация)
2. [Существующие open-source клиенты: что заимствовать](#часть-2-обзор-open-source-jellyfin-клиентов-и-смежных-проектов)
3. [libVLC на Android: артефакты, API, субтитры, стриминг, лицензии](#часть-3-libvlc-на-android-как-движок-воспроизведения)
4. [Android-платформа: Compose for TV, медиа-интеграция, дистрибуция](#часть-4-android-платформа)
5. [Доменный слой воспроизведения: DeviceProfile, транскодирование, субтитры, изображения, тестовые медиа](#часть-5-доменный-слой-воспроизведения)
6. [Инструментарий разработки: сборка, эмуляторы Android TV с пультом, просмотр UI](#часть-6-инструментарий-разработки)
7. [Гибридный движок воспроизведения, тестовый ТВ, план прототипа](#часть-7-гибридный-движок-воспроизведения-дополнение-от-2026-10-12)

## Ключевые выводы (сводка)

- **Сервер и SDK.** Актуальный сервер — Jellyfin 12.2 (октябрь 2026, схема версий сменилась с 10.x на 12.x). Официальный `jellyfin-sdk-kotlin` 1.9.2 (LGPL-3.0, Ktor) покрывает API, discovery и WebSocket; использовать его как есть. С 12.0 legacy-авторизация выключена: только заголовок `Authorization: MediaBrowser ...` и query-параметр `ApiKey` там, где заголовок невозможен (URL для libVLC).
- **libVLC.** Артефакт `org.videolan.android:libvlc-all:3.7.7` (обновляется ежемесячно; 4.0 только EAP). Нативные библиотеки 40–63 МБ на ABI, поэтому только AAB или ABI-splits. Произвольных HTTP-заголовков нет, TLS через GnuTLS (нужен обработчик `Dialog`), HLS-seek слабый, Dolby Vision без RPU. Сильные стороны: ASS/PGS/VobSub рендерит сам, passthrough, любые контейнеры.
- **Референсы.** Ни один живой Kotlin-клиент не использует libVLC как основной движок; официальный Android TV клиент удалил его в 2024 из-за плохого управления жизненным циклом и размера APK. Лучшие образцы: Swiftfin (DeviceProfile для VLC), jellyfin-androidtv (архитектура playback-модулей, профиль под Android-железо), Findroid (`BasePlayer` поверх стороннего движка), Wholphin (Compose for TV без Leanback).
- **UI для TV.** Leanback официально deprecated, для нового проекта только Compose for TV (`androidx.tv:tv-material:1.1.0`, stable). TV-специфичные lazy-списки удалены, используются обычные `LazyRow/LazyColumn` с `BringIntoViewSpec`. libVLC можно обернуть в media3 `SimpleBasePlayer`/`BasePlayer` и отдать в `MediaSession` для Now Playing, PiP и медиакнопок; готовые шаблоны есть в jellyfin-androidtv и Findroid.
- **Лицензия.** Бинарный libVLC де-факто GPLv3-совместим, поэтому для приложения брать GPL-2.0-or-later или GPL-3.0, не GPL-2.0-only. Имя «Jellyfin» и логотип в названии и иконке использовать нельзя.
- **Инструменты.** Android Studio 2026.2.1, AGP 9.4, Gradle 9.8, Kotlin 2.4, Compose for TV `tv-material 1.1.0`. Android TV эмулятор управляется стрелками клавиатуры и `adb shell input keyevent`; рецепт запуска из командной строки в части 6.

---

# Часть 1. Jellyfin API, официальные SDK и протокольная документация

_Исходный заголовок: Jellyfin: API сервера, официальные SDK и протокольная документация — отчёт для нового Kotlin-клиента (Android + Android TV, libVLC)

Дата исследования: 2026-10-11. Все URL ниже были проверены (HTTP 200 / содержимое прочитано), кроме помеченных «(не проверено)».

## 0. Самое важное — контекст версий на октябрь 2026

| Что | Версия / дата | Источник |
|---|---|---|
| Jellyfin Server **12.2** (текущий stable) | 05.10.2026 | [GitHub release v12.2](https://github.com/jellyfin/jellyfin/releases/tag/v12.2) |
| Jellyfin 12.1 | 15.09.2026 | [GitHub release v12.1](https://github.com/jellyfin/jellyfin/releases/tag/v12.1) |
| Jellyfin **12.0** (бывший «10.12», смена схемы версий) | 07.09.2026 | [Release post 12.0](https://jellyfin.org/posts/jellyfin-release-12.0/) |
| Jellyfin 10.11.0 | 19.10.2025 | [Release post 10.11.0](https://jellyfin.org/posts/jellyfin-release-10.11.0/) |
| Jellyfin 10.10.0 | 26.10.2024 | [Release post 10.10.0](https://jellyfin.org/posts/jellyfin-release-10.10.0/) |
| Jellyfin 10.9.0 | 11.05.2024 | [Release post 10.9.0](https://jellyfin.org/posts/jellyfin-release-10.9.0/) |
| jellyfin-sdk-kotlin **1.9.2** (API 12.2.0, min server 12.0.0) | 06.10.2026 | [Releases](https://github.com/jellyfin/jellyfin-sdk-kotlin/releases), Maven Central metadata |
| jellyfin-sdk-kotlin 1.8.12 (ветка для 10.11.x; min 10.11.0) | 26.07.2026 | там же |

Ключевые следствия для нового клиента:
1. **Legacy-авторизация выключена по умолчанию с 12.0** (`X-Emby-Authorization`, `X-Emby-Token`, `X-MediaBrowser-Token`, query `api_key`, префиксы `/emby/*` и `/mediabrowser/*` удалены). Нужно использовать только `Authorization: MediaBrowser ...` и query-параметр `ApiKey` (с большой буквы) там, где заголовок невозможен (например, URL для libVLC).
2. HLS-эндпоинты (`master.m3u8` и др.) с 12.0 **скрыты из OpenAPI** — их нет в сгенерированном SDK; нужно использовать `MediaSourceInfo.TranscodingUrl` из ответа `PlaybackInfo`.
3. SDK 1.9.x несовместим с серверами ≤10.11; если хотите поддерживать 10.11 — придётся собирать на 1.8.x или писать свой клиент поверх OpenAPI.
4. В SDK 1.9 (под API 12) **переименованы классы операций** относительно старых примеров: `PlayStateApi.reportPlayback*` → `SessionApi.reportPlayback*`, `ItemsApi` → `LibraryApi` (`getItems`, `getResumeItems`, `getLatestMedia`), `TvShowsApi` → `ShowApi`, `VideosApi` → `VideoApi`, `UserViewsApi` → `UserViewApi`, `DynamicHlsApi` отсутствует. Проверено по Dokka 1.9.x.

---

## 1. Официальная документация API, аутентификация, OpenAPI

**Важно:** на jellyfin.org **нет** отдельного раздела «API docs» (`/docs/general/server/api/` → 404). Официальные источники по API — это: интерактивный ReDoc на api.jellyfin.org, сам OpenAPI JSON, документация SDK (kotlin-sdk.jellyfin.org / typescript-sdk.jellyfin.org), release-посты и gist мейнтейнера SDK Niels van Velzen.

- [api.jellyfin.org (ReDoc)](https://api.jellyfin.org/) — официальный интерактивный справочник API (рендерится из OpenAPI JS'ом, поэтому curl и простая загрузка страницы видят только заголовок страницы). Ссылка на него стоит в официальном contributing-гайде как «API documentation».
- [OpenAPI JSON stable](https://api.jellyfin.org/openapi/jellyfin-openapi-stable.json) — актуальная спецификация stable-сервера (сейчас 12.2.0, ~1.9 МБ, 294 пути). **Gotcha:** с CDN api.jellyfin.org скачивание у меня обрывалось на 14 КБ; надёжнее брать с зеркала ниже.
- [OpenAPI JSON unstable](https://api.jellyfin.org/openapi/jellyfin-openapi-unstable.json) — спецификация master-ветки.
- [Архив OpenAPI по версиям](https://repo.jellyfin.org/files/openapi/stable/) — файлы `jellyfin-openapi-<версия>.json` для каждого релиза: от 10.9.0 через 10.10.7, 10.11.0…10.11.11 до 12.0, 12.1, 12.2 (например, [jellyfin-openapi-12.2.json](https://repo.jellyfin.org/files/openapi/stable/jellyfin-openapi-12.2.json), [jellyfin-openapi-10.11.0.json](https://repo.jellyfin.org/files/openapi/stable/jellyfin-openapi-10.11.0.json)). Именно это «версионирование API по релизам сервера»: отдельного номера версии API нет, `info.version` = версии сервера. Удобно для diff'ов между версиями (см. раздел 7). Есть и каталог [unstable](https://repo.jellyfin.org/files/openapi/unstable/).
- [Gist Niels van Velzen «Jellyfin authorization»](https://gist.github.com/nielsvanvelzen/ea047d9028f676185832e51ffaf12a6f) — де-факто официальное описание схемы авторизации (на него ссылается release-пост 10.11). Схема `MediaBrowser`, параметры через запятую, значения в двойных кавычках и URL-encoded, ключи регистрозависимые, неизвестные ключи игнорируются. Перечисляет устаревшие методы (query `api_key`, заголовки `X-Emby-Token`, `X-MediaBrowser-Token`, `X-Emby-Authorization`) и подтверждает их отключение в 12.0. Рекомендует хешировать имя пользователя в `DeviceId`.

Формат заголовка (из gist + исходника SDK `AuthorizationHeaderBuilder.kt`):
```
Authorization: MediaBrowser Client="MyApp", Device="Pixel 8", DeviceId="<уникальный id>", Version="1.0.0", Token="<accessToken>"
```
До логина — тот же заголовок без `Token`. Для API-ключа достаточно `Authorization: MediaBrowser Token="<key>"`. В OpenAPI это security scheme `CustomAuthentication` (apiKey, header `Authorization`).

- [Исходник серверной проверки авторизации `AuthorizationContext.cs`](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Server.Implementations/Security/AuthorizationContext.cs) — «источник истины»: порядок поиска токена: заголовок `Authorization` → (только при `EnableLegacyAuthorization`) `X-Emby-Token`, `X-MediaBrowser-Token` → query `ApiKey` (всегда) → (legacy) query `api_key`; схема `Emby` принимается только в legacy-режиме. Проверено grep'ом по master.
- [SDK: AuthorizationHeaderBuilder.kt](https://github.com/jellyfin/jellyfin-sdk-kotlin/blob/master/jellyfin-api/src/commonMain/kotlin/org/jellyfin/sdk/api/client/util/AuthorizationHeaderBuilder.kt) — эталонная реализация сборки заголовка (`Client`, `Version`, `DeviceId`, `Device`, `Token`; `encodeURLPart` для значений).
- [Issue #15962 «Default EnableLegacyAuthorization=false breaks Jellyseerr»](https://github.com/jellyfin/jellyfin/issues/15962) — позиция мейнтейнеров: депрекация объявлена в 2023, в 10.11 добавлен opt-in флаг для тестирования, в 12.0 стало opt-out, в следующем мажоре legacy-опция будет удалена совсем; «откатывать не планируем». Временный обход для админов — `EnableLegacyAuthorization` в `system.xml` (см. также [форум-тред](https://forum.jellyfin.org/printthread.php?tid=15085)).
- [Help Net Security о 12.0](https://www.helpnetsecurity.com/2026/09/08/jellyfin-12-0-security-fixes/) — новостная заметка про отключение legacy-логинов и security-фиксы 12.0 (вторичный источник).

**DeviceId / идентификация клиента**
- Сервер хранит **один access token на `DeviceId`**: если на одном устройстве залогинены два пользователя Jellyfin с одинаковым `DeviceId`, второй логин инвалидирует первый. Рекомендация (gist + сообщество): `DeviceId = hash(installationId + username)` или отдельный id на пользователя.
- `Client`/`Version`/`Device` показываются в дашборде сервера (Devices, Sessions) и влияют на «Playback reporting».
- В SDK это `ClientInfo(name, version)` и `DeviceInfo(id, name)` ([Dokka ClientInfo](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-model/org.jellyfin.sdk.model/-client-info/index.html), [Dokka DeviceInfo](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-model/org.jellyfin.sdk.model/-device-info/index.html)); с 1.9.0 в `DeviceInfo` можно задать язык API (сервер локализует ответы; на сервере с 12.0 поддерживается `Accept-Language`).
- Эндпоинты устройств: `GET /Devices`, `GET /Devices/Info`, `GET/POST /Devices/Options`; разлогин — `POST /Sessions/Logout`; текущий пользователь — `GET /Users/Me`; публичный список пользователей для экрана логина — `GET /Users/Public`.

**Логин по паролю:** `POST /Users/AuthenticateByName` с телом `{"Username": "...", "Pw": "..."}` (пользователь без пароля — пустая строка) → `AuthenticationResult {User, SessionInfo, AccessToken, ServerId}`; 401 при неверных данных.

**Quick Connect** (все пути проверены по OpenAPI 12.2):
- `GET /QuickConnect/Enabled` → bool.
- `POST /QuickConnect/Initiate` → `QuickConnectResult {Authenticated, Secret, Code, DeviceId, DeviceName, AppName, AppVersion, DateAdded}`. **`GET /QuickConnect/Initiate` удалён в 12.0** — только POST.
- `GET /QuickConnect/Connect?secret=...` — опрос состояния (SDK рекомендует раз в ~5 с).
- `POST /Users/AuthenticateWithQuickConnect` с телом `{"Secret": "..."}` → `AuthenticationResult`.
- `POST /QuickConnect/Authorize?code=...&userId=...` — вызывается с уже залогиненного устройства (если хотите, чтобы ваш клиент мог *подтверждать* чужие коды).
- [Kotlin SDK guide: Authentication](https://kotlin-sdk.jellyfin.org/guide/authentication.html) — готовые сниппеты для `authenticateUserByName`, Quick Connect, `api.update(accessToken=...)`.
- [Docs: Quick Connect (пользовательская)](https://jellyfin.org/docs/general/server/quick-connect/) — поведение фичи, код из 6 символов, включён по умолчанию, список клиентов, умеющих логиниться/авторизовывать.

**Неофициальные зеркала (с осторожностью):**
- [mintlify.wiki: jellyfin/api/authentication/overview](https://mintlify.wiki/jellyfin/jellyfin/api/authentication/overview) — AI-сгенерированное «зеркало» документации, не официальное; примеры curl корректны, но детали (например, про `api_key`) местами противоречивы.

---

## 2. jellyfin-sdk-kotlin и legacy-репозиторий

- [github.com/jellyfin/jellyfin-sdk-kotlin](https://github.com/jellyfin/jellyfin-sdk-kotlin) — официальный Kotlin SDK (JVM + Android), сгенерирован из OpenAPI сервера. Лицензия **LGPL-3.0** (совместимо с GPL-приложением при обычном линковании). ~2190 коммитов, активно поддерживается (релизы в сентябре–октябре 2026).
- **Важно про `jellyfin-apiclient-java`:** [github.com/jellyfin/jellyfin-apiclient-java](https://github.com/jellyfin/jellyfin-apiclient-java) теперь **редиректит на jellyfin-sdk-kotlin** — это тот же репозиторий, переименованный; отдельного deprecated Java-клиента больше нет. Старое Java-API было окончательно убрано в SDK v1.7 («Java helper utilities removed», см. миграцию v1.7).
- [kotlin-sdk.jellyfin.org — Getting started](https://kotlin-sdk.jellyfin.org/guide/getting-started.html) — Maven Central, `implementation("org.jellyfin.sdk:jellyfin-core:$sdkVersion")`, DSL `createJellyfin { clientInfo = ClientInfo(name, version); context = androidContext }`, `jellyfin.createApi(baseUrl, accessToken, clientInfo, deviceInfo, httpClientOptions)`. Указано: Java 8+, «Android 4.4+» (устарело — см. ниже), desugaring для Android < 8. Snapshot-репозиторий `master-SNAPSHOT` на `https://s01.oss.sonatype.org/content/repositories/snapshots/` (работоспособность snapshot-репо не проверена). В разделе «Projects using the SDK» перечислены Findroid и Homedia — можно добавить свой проект PR'ом.
- [Kotlin SDK guide: WebSockets](https://kotlin-sdk.jellyfin.org/guide/websockets.html) — см. раздел 5.
- [Dokka (API reference)](https://kotlin-sdk.jellyfin.org/dokka/) — полный справочник по модулям.
- [Releases](https://github.com/jellyfin/jellyfin-sdk-kotlin/releases) и [v1.9.0](https://github.com/jellyfin/jellyfin-sdk-kotlin/releases/tag/v1.9.0): 1.9.0 (08.09.2026) — под Jellyfin 12.0, **minSdk 21, compileSdk 37**, Kotlin 2.4.20, OkHttp 5.5.0, «no longer compatible with Jellyfin 10.11 and earlier»; 1.9.1 — фикс отмены WebSocket-подписок; 1.9.2 (06.10.2026) — OpenAPI 12.2.0. 1.8.12 (26.07.2026) — бэкпорт для 10.11.x.
- Maven Central: группа `org.jellyfin.sdk`, артефакты (проверено по [repo1.maven.org](https://repo1.maven.org/maven2/org/jellyfin/sdk/)): `jellyfin-core` (+ `-android`, `-jvm`), `jellyfin-api` (+ `-jvm`), `jellyfin-api-okhttp`, `jellyfin-model` (+ `-jvm`), `jellyfin-platform-android`, и устаревший `jellyfin-api-ktor` (последняя 1.7.1). `maven-metadata.xml` для `jellyfin-core`: latest/release = **1.9.2**.
- **Сетевой стек — НЕ Ktor.** [Migrate to v1.7](https://kotlin-sdk.jellyfin.org/migration/v1.7.html): OkHttp 4 стал дефолтом, SDK перестал управлять потоками (вызовы нельзя делать на main thread), `User-Agent` формируется из `ClientInfo`; [Migrate to v1.8](https://kotlin-sdk.jellyfin.org/migration/v1.8.html): пакет `jellyfin-api-ktor` удалён. В 1.9 — OkHttp 5.5. Это удобно: тот же OkHttp можно переиспользовать для картинок (Coil) и при желании настраивать TLS.
- Версии зависимостей (из [gradle/libs.versions.toml](https://github.com/jellyfin/jellyfin-sdk-kotlin/blob/master/gradle/libs.versions.toml) на master): kotlin 2.4.20, kotlinx-coroutines 1.11.0, kotlinx-serialization 1.11.0, okhttp 5.5.0, koin 4.2.2, kotlin-logging 8.0.4, slf4j 2.0.19.
- [HttpClientOptions.kt](https://github.com/jellyfin/jellyfin-sdk-kotlin/blob/master/jellyfin-api/src/commonMain/kotlin/org/jellyfin/sdk/api/client/HttpClientOptions.kt): `followRedirects=true`, `requestTimeout=30s`, `connectTimeout=6s`, `socketTimeout=30s`, `socketReconnectPolicy=ExponentialDelayReconnect()`. Явной настройки доверенных сертификатов в опциях нет (не проверено глубже) — для self-signed придётся либо Android Network Security Config, либо смотреть, как подменить OkHttp-клиент.
- [Jellyfin.kt](https://github.com/jellyfin/jellyfin-sdk-kotlin/blob/master/jellyfin-core/src/commonMain/kotlin/org/jellyfin/sdk/Jellyfin.kt): `Jellyfin.minimumVersion = ServerVersion(12,0,0,0)`, `Jellyfin.apiVersion` = версия OpenAPI, по которой сгенерирован SDK.
- Пакет расширений [org.jellyfin.sdk.api.client.extensions](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-api/org.jellyfin.sdk.api.client.extensions/index.html): свойства `api.libraryApi`, `api.mediaInfoApi`, `api.sessionApi`, `api.userDataApi`, `api.showApi`, `api.userViewApi`, `api.videoApi`, `api.audioApi`, `api.imageApi`, `api.subtitleApi`, `api.trickPlayApi`, `api.mediaSegmentApi`, `api.lyricApi`, `api.liveTvApi`, `api.syncPlayApi`, `api.quickConnectApi`, `api.searchApi`, `api.systemApi`, `api.userApi` и т. д.; функции `authenticateUserByName`, `authenticateWithQuickConnect`, generic `api.get/post/delete<T>(pathTemplate, …)` для эндпоинтов, которых нет в генерации (например, HLS).
- Список всех классов операций 1.9: [org.jellyfin.sdk.api.operations](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-api/org.jellyfin.sdk.api.operations/index.html) (46 классов: `LibraryApi`, `MediaInfoApi`, `SessionApi`, `ShowApi`, `UserViewApi`, `VideoApi`, `AudioApi`, `ImageApi`, `SubtitleApi`, `TrickPlayApi`, `MediaSegmentApi`, `LyricApi`, `LiveTvApi`, `SyncPlayApi`, `QuickConnectApi`, `AuthenticationApi`, `UserApi`, `UserDataApi`, `SearchApi`, `SystemApi`, `PlaylistApi`, `CollectionApi`, `PersonApi`, `GenreApi`, `StudioApi`, …).
- Полезные Dokka-страницы (проверены): [MediaInfoApi.getPostedPlaybackInfo](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-api/org.jellyfin.sdk.api.operations/-media-info-api/get-posted-playback-info.html), [SessionApi.reportPlaybackProgress](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-api/org.jellyfin.sdk.api.operations/-session-api/report-playback-progress.html), [VideoApi.getVideoStreamUrl](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-api/org.jellyfin.sdk.api.operations/-video-api/get-video-stream-url.html), [AudioApi.getUniversalAudioStreamUrl](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-api/org.jellyfin.sdk.api.operations/-audio-api/get-universal-audio-stream-url.html), [ImageApi.getItemImageUrl](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-api/org.jellyfin.sdk.api.operations/-image-api/get-item-image-url.html), [SubtitleApi.getSubtitleUrl](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-api/org.jellyfin.sdk.api.operations/-subtitle-api/get-subtitle-url.html), [TrickPlayApi.getTrickplayTileImageUrl](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-api/org.jellyfin.sdk.api.operations/-trick-play-api/get-trickplay-tile-image-url.html), [MediaSegmentApi.getItemSegments](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-api/org.jellyfin.sdk.api.operations/-media-segment-api/get-item-segments.html), [LibraryApi.getItems](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-api/org.jellyfin.sdk.api.operations/-library-api/get-items.html), [LibraryApi.getResumeItems](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-api/org.jellyfin.sdk.api.operations/-library-api/get-resume-items.html), [LibraryApi.getLatestMedia](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-api/org.jellyfin.sdk.api.operations/-library-api/get-latest-media.html), [ShowApi.getNextUp](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-api/org.jellyfin.sdk.api.operations/-show-api/get-next-up.html), [UserViewApi.getUserViews](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-api/org.jellyfin.sdk.api.operations/-user-view-api/get-user-views.html), [SearchApi.getSearchHints](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-api/org.jellyfin.sdk.api.operations/-search-api/get-search-hints.html), [SessionApi.postFullCapabilities](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-api/org.jellyfin.sdk.api.operations/-session-api/post-full-capabilities.html), [SyncPlayApi.syncPlayJoinGroup](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-api/org.jellyfin.sdk.api.operations/-sync-play-api/sync-play-join-group.html), [LyricApi.getLyrics](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-api/org.jellyfin.sdk.api.operations/-lyric-api/get-lyrics.html), [SystemApi.getPublicSystemInfo](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-api/org.jellyfin.sdk.api.operations/-system-api/get-public-system-info.html).
- DSL для DeviceProfile: [org.jellyfin.sdk.model.deviceprofile](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-model/org.jellyfin.sdk.model.deviceprofile/index.html) — `buildDeviceProfile { directPlayProfile {…}; transcodingProfile {…}; codecProfile {…}; subtitleProfile(...) }`, `buildUpon`.
- Тики: [org.jellyfin.sdk.model.extensions](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-model/org.jellyfin.sdk.model.extensions/index.html) — `Long.ticks: Duration`, `Duration.inWholeTicks`.
- Discovery (jellyfin-core): [org.jellyfin.sdk.discovery](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-core/org.jellyfin.sdk.discovery/index.html), [DiscoveryService](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-core/org.jellyfin.sdk.discovery/-discovery-service/index.html) — `discoverLocalServers(timeout, maxServers): Flow<ServerDiscoveryInfo>`, `getAddressCandidates(input)`, `getRecommendedServers(input|servers, minimumScore)`. Детали в разделе 6. Отдельной страницы guide по discovery на сайте нет (404); пример есть в Getting started (`jellyfin.discovery.getRecommendedServers(candidates, RecommendedServerInfoScore.GOOD)`).
- Пример проекта: [samples/kotlin-cli](https://github.com/jellyfin/jellyfin-sdk-kotlin/tree/master/samples/kotlin-cli) — CLI с командами discovery, observe (websocket) и т. п.
- Эталонные потребители SDK: [jellyfin-androidtv](https://github.com/jellyfin/jellyfin-androidtv) (официальный TV-клиент на SDK), [jellyfin-android](https://github.com/jellyfin/jellyfin-android), [Findroid](https://github.com/jarnedemeulemeester/findroid) (сторонний нативный Kotlin-клиент на SDK, GPL).
- Для сравнения — [jellyfin-sdk-typescript](https://github.com/jellyfin/jellyfin-sdk-typescript) / [typescript-sdk.jellyfin.org](https://typescript-sdk.jellyfin.org/): таблица совместимости SDK↔сервер (1.0.0 → 12.x, 0.12/0.13 → 10.11.x, 0.11 → 10.10.x, 0.9/0.10 → 10.9.x), тот же паттерн ClientInfo/DeviceInfo/discovery/auth.

---

## 3. Воспроизведение (PlaybackInfo, DeviceProfile, стримы, прогресс, субтитры, trickplay, UserData)

Единицы времени: **1 tick = 100 нс; 1 с = 10 000 000 ticks**. Все `*Ticks` в API (`RunTimeTicks`, `PositionTicks`, `StartTimeTicks`, `StartPositionTicks`, `PlaybackPositionTicks`, `StartTicks/EndTicks`) — в этих единицах. libVLC оперирует миллисекундами → `ms * 10_000`.

### PlaybackInfo и DeviceProfile
- `POST /Items/{itemId}/PlaybackInfo` (query: `userId`, `maxStreamingBitrate`, `startTimeTicks`, `audioStreamIndex`, `subtitleStreamIndex`, `maxAudioChannels`, `mediaSourceId`, `liveStreamId`, `autoOpenLiveStream`, `enableDirectPlay`, `enableDirectStream`, `enableTranscoding`, `allowVideoStreamCopy`, `allowAudioStreamCopy`; тело `PlaybackInfoDto` с теми же полями + `DeviceProfile`, `AlwaysBurnInSubtitleWhenTranscoding`). Ответ `PlaybackInfoResponse { MediaSources: [MediaSourceInfo], PlaySessionId, ErrorCode }`. Есть и `GET`-вариант без профиля (бесполезен для решения о транскодировании).
- `MediaSourceInfo` (проверено по спеке 12.2): `Id`, `Container`, `Protocol`, `Path`, `Size`, `Bitrate`, `RunTimeTicks`, `SupportsDirectPlay`, `SupportsDirectStream`, `SupportsTranscoding`, `TranscodingUrl`, `TranscodingSubProtocol` (`http`|`hls`), `TranscodingContainer`, `MediaStreams`, `MediaAttachments`, `DefaultAudioStreamIndex`, `DefaultSubtitleStreamIndex`, `RequiredHttpHeaders`, `IsInfiniteStream`, `LiveStreamId`, `RequiresOpening/OpenToken/RequiresClosing` (Live TV), `HasSegments` (есть media segments), `ETag`.
- Логика выбора: сервер сравнивает `DeviceProfile` с ffprobe-данными файла: контейнер+кодеки проходят `DirectPlayProfiles` и `CodecProfiles` → **DirectPlay** (`/Videos/{id}/stream?static=true`); видео проходит, но контейнер/аудио/субтитры нет → **DirectStream** (ремукс, `TranscodingUrl` c `allowVideoStreamCopy`); иначе **Transcode** (`TranscodingUrl`, обычно HLS). Enum `PlayMethod`: `DirectPlay | DirectStream | Transcode` — его нужно сообщать в прогрессе.
- `DeviceProfile` поля: `Name`, `Id`, `MaxStreamingBitrate`, `MaxStaticBitrate`, `MusicStreamingTranscodingBitrate`, `MaxStaticMusicBitrate`, `DirectPlayProfiles[] {Container, AudioCodec, VideoCodec, Type(Audio|Video|Photo|Subtitle|Lyric)}`, `TranscodingProfiles[] {Container, Type, VideoCodec, AudioCodec, Protocol(http|hls), Context(Streaming|Static), MaxAudioChannels, MinSegments, SegmentLength, BreakOnNonKeyFrames, CopyTimestamps, EnableSubtitlesInManifest, Conditions, EnableAudioVbrEncoding, …}`, `ContainerProfiles`, `CodecProfiles[] {Type(Video|VideoAudio|Audio), Codec, Container, SubContainer, Conditions, ApplyConditions}`, `SubtitleProfiles[] {Format, Method, Language, Container, DidlMode}`. Условия (`ProfileConditionValue`): `Width, Height, VideoBitDepth, VideoBitrate, VideoFramerate, VideoLevel, VideoProfile, AudioChannels, AudioBitrate, AudioSampleRate, AudioBitDepth, RefFrames, IsAnamorphic, IsInterlaced, IsSecondaryAudio, VideoCodecTag, IsAvc, VideoRangeType, NumAudioStreams, NumVideoStreams, NumStreams (10.11+), VideoRotation (12.x)`. Схема DeviceProfile одинакова в 10.10.7 / 10.11.11 / 12.2.0 — можно писать один профиль.
- Для libVLC: он умеет почти всё (MKV/MP4/TS/AVI, H.264/HEVC/VP9/AV1 через MediaCodec или софт, AC3/EAC3/DTS/TrueHD/AAC/FLAC/Opus, PGS/ASS/SRT/VTT), поэтому агрессивный DirectPlay-профиль + HLS/TS fallback (как у официальных клиентов) — разумная стартовая точка. Опирайтесь на реальные профили:
  - [jellyfin-androidtv: util/profile/deviceProfile.kt](https://github.com/jellyfin/jellyfin-androidtv/blob/master/app/src/main/java/org/jellyfin/androidtv/util/profile/deviceProfile.kt) — `buildDeviceProfile { … }` с несколькими `transcodingProfile` (TS+HLS, MP4+HLS), `directPlayProfile`, множеством `codecProfile` (HDR-range, AV1/HEVC условия), `subtitleProfile(VTT/WEBVTT, embedded=true, hls=true, external=true)`; рядом `deviceProfileReport.kt` и `codec/` с проверкой MediaCodec-возможностей.
  - [jellyfin-android: DeviceProfileBuilder.kt](https://github.com/jellyfin/jellyfin-android/blob/master/app/src/main/java/org/jellyfin/mobile/player/deviceprofile/DeviceProfileBuilder.kt) — профиль для ExoPlayer + отдельный `getExternalPlayerProfile()` (для внешних плееров, по сути «играй всё, maxStreamingBitrate = Int.MAX_VALUE», субтитры EMBED + EXTERNAL) — ближайший аналог того, что нужно для libVLC.
  - [jellyfin-android: MediaSourceResolver.kt](https://github.com/jellyfin/jellyfin-android/blob/master/app/src/main/java/org/jellyfin/mobile/player/source/MediaSourceResolver.kt) — как официальный клиент выбирает MediaSource/PlayMethod из ответа PlaybackInfo.
  - [jellyfin-web: browserDeviceProfile.js](https://github.com/jellyfin/jellyfin-web/blob/master/src/scripts/browserDeviceProfile.js) и [playbackmanager.js](https://github.com/jellyfin/jellyfin-web/blob/master/src/components/playback/playbackmanager.js) — эталон полного playback-цикла (PlaybackInfo → выбор → отчёты → стоп), включая периодичность прогресса и обработку `TranscodingUrl`.
- [Docs: Codec Support](https://jellyfin.org/docs/general/clients/codec-support/) — таблицы кодеков/контейнеров по клиентам (Android / Android TV) и принцип «Direct Play всё, DirectStream при неподдерживаемом контейнере/аудио/субтитрах, Transcode при неподдерживаемом видео».
- [Docs: Transcoding](https://jellyfin.org/docs/general/post-install/transcoding/) — серверная сторона (HW accel), полезно для понимания причин транскодирования.

### URL потоков
- Direct play: `GET /Videos/{itemId}/stream` или `/Videos/{itemId}/stream.{container}` с `static=true&mediaSourceId=...&deviceId=...` (+ `tag`/ETag). Полный список query-параметров (проверено по спеке): `container, static, params, tag, deviceProfileId, playSessionId, segmentContainer, segmentLength, minSegments, mediaSourceId, deviceId, audioCodec, videoCodec, enableAutoStreamCopy, allowVideoStreamCopy, allowAudioStreamCopy, audioSampleRate, maxAudioBitDepth, audioBitRate, audioChannels, maxAudioChannels, profile, level, framerate, maxFramerate, copyTimestamps, startTimeTicks, width, height, maxWidth, maxHeight, videoBitRate, subtitleStreamIndex, subtitleMethod, maxRefFrames, maxVideoBitDepth, requireAvc, deInterlace, requireNonAnamorphic, transcodingMaxAudioChannels, cpuCoreLimit, liveStreamId, enableMpegtsM2TsMode, subtitleCodec, transcodeReasons, audioStreamIndex, videoStreamIndex, context, streamOptions, enableAudioVbrEncoding`.
- Transcode/DirectStream: берите готовый `MediaSourceInfo.TranscodingUrl` (относительный путь `/videos/{id}/master.m3u8?...` или `/Videos/{id}/stream?...`, уже содержит `PlaySessionId`, `DeviceId`, `MediaSourceId`, индексы потоков и ключ `ApiKey`; с 10.11 генерируемые URL используют `ApiKey` вместо `api_key`). Склеивайте с `baseUrl`.
- HLS-маршруты существуют, но с 12.0 **скрыты из OpenAPI** (`[ApiExplorerSettings(IgnoreApi = true)]` в [DynamicHlsController.cs](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/DynamicHlsController.cs)): `GET /Videos/{itemId}/master.m3u8`, `/Videos/{itemId}/main.m3u8`, `/Videos/{itemId}/live.m3u8`, `/Videos/{itemId}/hls1/{playlistId}/{segmentId}.{container}`, аналогично `/Audio/{itemId}/master.m3u8`. Релиз-пост 12.0 прямо говорит: «endpoints not in the OpenAPI specification … should not be used» — т. е. формировать их вручную не рекомендуется, используйте `TranscodingUrl`.
- **Gotcha для libVLC:** плееру нужен URL без возможности выставить заголовок `Authorization` → используйте query `ApiKey=<token>` (не legacy `api_key`!). libVLC 3 позволяет задать только User-Agent/referrer-подобные опции, не произвольные заголовки (общее знание, не проверялось в этом исследовании).
- Музыка: `GET /Audio/{itemId}/universal` (`container[]`, `audioCodec`, `maxStreamingBitrate`, `transcodingContainer`, `transcodingProtocol`, `maxAudioSampleRate`, `enableRedirection`, …) — сервер сам решает direct/transcode без PlaybackInfo. Скачивание: `GET /Items/{itemId}/Download`.
- Trailers/Intros: `GET /Items/{itemId}/Intros` (pre-roll), `RemoteTrailers` в `BaseItemDto`.

### Отчёты о воспроизведении (сессии)
- `POST /Sessions/Playing` — тело `PlaybackStartInfo`; `POST /Sessions/Playing/Progress` — `PlaybackProgressInfo`; `POST /Sessions/Playing/Stopped` — `PlaybackStopInfo`; `POST /Sessions/Playing/Ping?playSessionId=` — keep-alive транскодера. Поля Start/Progress: `ItemId`, `MediaSourceId`, `PlaySessionId`, `PositionTicks`, `IsPaused`, `IsMuted`, `VolumeLevel`, `CanSeek`, `PlayMethod`, `AudioStreamIndex`, `SubtitleStreamIndex`, `RepeatMode`, `PlaybackOrder`, `NowPlayingQueue`, `PlaylistItemId`, `LiveStreamId`, `SessionId`, `PlaybackStartTimeTicks`, `AspectRatio`, `Brightness`. Stop: `ItemId`, `MediaSourceId`, `PlaySessionId`, `PositionTicks`, `Failed`, `NextMediaType`, `LiveStreamId`. Исходник: [PlaystateController.cs](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/PlaystateController.cs).
- **Старые `POST/DELETE /PlayingItems/{itemId}[/Progress]` (OnPlayback*) депрекированы в 10.11 и удалены из спеки в 12.0** — не используйте. Также `POST/DELETE /Users/{userId}/PlayedItems/{id}` помечены `Obsolete` в коде; актуальные: `POST/DELETE /UserPlayedItems/{itemId}` (`?userId&datePlayed`), `POST/DELETE /UserFavoriteItems/{itemId}`, `GET/POST /UserItems/{itemId}/UserData`, `POST/DELETE /UserItems/{itemId}/Rating`.
- Периодичность прогресса: веб-клиент шлёт прогресс примерно раз в 10 с и при событиях pause/seek/stream switch (значение 10 с — по памяти, не проверено в этом исследовании; смотрите `playbackmanager.js`). Без прогресса сервер через таймаут считает сессию мёртвой и убивает транскодер — отсюда `Ping`.
- **Resume / UserData:** `BaseItemDto.UserData: UserItemDataDto {PlaybackPositionTicks, PlayedPercentage, Played, PlayCount, IsFavorite, Likes, LastPlayedDate, UnplayedItemCount, Rating, Key, ItemId}`. Список «Продолжить просмотр»: `GET /UserItems/Resume?userId=&limit=&mediaTypes=Video&fields=…&excludeActiveSessions=` (пути `/Users/{userId}/Items/Resume`, `/Users/{userId}/Items/Latest` в спеках 10.10.7+ уже отсутствуют — используйте `userId` как query).
- Для «кнопки Resume» нужны `fields=MediaSources,Chapters,Trickplay,MediaStreams` и `enableUserData=true` в запросах.

### Субтитры
- `SubtitleDeliveryMethod` enum: `Encode` (вжечь), `Embed` (в контейнере, плеер читает сам), `External` (отдельный URL), `Hls` (в манифесте), `Drop`. В `DeviceProfile.SubtitleProfiles` объявляете, какие форматы и каким методом умеете (например, для libVLC: `srt/subrip, ass, ssa, vtt, pgssub, dvdsub, dvbsub` как `Embed`; `srt, ass, ssa, vtt` как `External`).
- Внешний поток: `GET /Videos/{itemId}/{mediaSourceId}/Subtitles/{index}/Stream.{format}` и `GET /Videos/{itemId}/{mediaSourceId}/Subtitles/{index}/{startPositionTicks}/Stream.{format}` (query: `copyTimestamps`, `addVttTimeMap`, `endPositionTicks`). HLS-плейлист субтитров: `GET /Videos/{itemId}/{mediaSourceId}/Subtitles/{index}/subtitles.m3u8`. Загрузка/удаление: `POST /Videos/{itemId}/Subtitles`, `DELETE /Videos/{itemId}/Subtitles/{index}`; поиск онлайн: `GET /Items/{itemId}/RemoteSearch/Subtitles/{language}`, `POST .../Subtitles/{subtitleId}`. Исходник: [SubtitleController.cs](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/SubtitleController.cs). В ответе PlaybackInfo каждый `MediaStream` типа Subtitle несёт `DeliveryMethod`/`DeliveryUrl`/`IsExternal` (имена этих полей MediaStream не перепроверял по спеке — «не проверено»), что позволяет не собирать URL вручную. В 12.2 графические субтитры отдаются как raw-файлы с Range-поддержкой.
- Для libVLC внешние субтитры удобно подключать через `Media.addSlave(Subtitle, url)`.

### Trickplay (с 10.9) и главы
- Данные: `BaseItemDto.Trickplay` (нужен `fields=Trickplay`) — словарь `mediaSourceId → width → TrickplayInfoDto {Width, Height, TileWidth, TileHeight, ThumbnailCount, Interval (мс), Bandwidth}` ([Dokka TrickplayInfoDto](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-model/org.jellyfin.sdk.model.api/-trickplay-info-dto/index.html)).
- Картинки-тайлы: `GET /Videos/{itemId}/Trickplay/{width}/{index}.jpg?mediaSourceId=`; HLS image playlist: `GET /Videos/{itemId}/Trickplay/{width}/tiles.m3u8`. Исходник: [TrickplayController.cs](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/TrickplayController.cs). Индекс тайла = `floor(positionMs / Interval) / (TileWidth*TileHeight)`, позиция внутри — остаток.
- Главы: `BaseItemDto.Chapters[] ChapterInfo {StartPositionTicks, Name, ImageTag, ImagePath, ImageDateModified}` (`fields=Chapters`); картинка главы — `GET /Items/{itemId}/Images/Chapter/{chapterIndex}?tag=`.

### Media Segments (intro/outro skip, с 10.10)
- `GET /MediaSegments/{itemId}?includeSegmentTypes=Intro&includeSegmentTypes=Outro` → `QueryResult<MediaSegmentDto {Id, ItemId, Type, StartTicks, EndTicks}>`; `MediaSegmentType`: `Unknown, Commercial, Preview, Recap, Outro, Intro`. `MediaSourceInfo.HasSegments` подсказывает, стоит ли вообще запрашивать. Исходник: [MediaSegmentsController.cs](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/MediaSegmentsController.cs). Сегменты создают плагины (Chapter Segments Provider, Intro Skipper); сервер лишь хранит.
- [Docs: Media Segments](https://jellyfin.org/docs/general/server/metadata/media-segments) — описание фичи для пользователей/админов, типы сегментов.
- Эталон поведения в клиенте: [jellyfin-androidtv ui/playback/segment](https://github.com/jellyfin/jellyfin-androidtv/tree/master/app/src/main/java/org/jellyfin/androidtv/ui/playback/segment) ([MediaSegmentRepository.kt](https://github.com/jellyfin/jellyfin-androidtv/blob/master/app/src/main/java/org/jellyfin/androidtv/ui/playback/segment/MediaSegmentRepository.kt)): действия per type (Skip / Ask to skip / Nothing), минимум 1 с для автоскипа, 3 с для кнопки, автоскрытие кнопки через 8 с.

---

## 4. API библиотеки (browsing)

Всё проверено по OpenAPI 12.2.0:
- `GET /UserViews?userId=&includeExternalContent=&presetViews=&includeHidden=` — корневые «библиотеки» пользователя (`CollectionType`: movies, tvshows, music, books, …). `GET /UserViews/GroupingOptions`.
- `GET /Items` — универсальный запрос. Ключевые параметры: `userId, parentId, recursive, includeItemTypes[] (BaseItemKind: Movie, Series, Season, Episode, MusicAlbum, Audio, BoxSet, Playlist, …), excludeItemTypes, searchTerm, sortBy[] (ItemSortBy: SortName, DateCreated, PremiereDate, DatePlayed, CommunityRating, Random, Runtime, ProductionYear, SeriesSortName, …), sortOrder[] (Ascending|Descending), filters[] (ItemFilter: IsUnplayed, IsPlayed, IsFavorite, IsResumable, IsFolder, …), fields[] (ItemFields: Overview, Genres, People, MediaSources, MediaStreams, Chapters, Trickplay, ChildCount, DateCreated, ProviderIds, PrimaryImageAspectRatio, Path, …), startIndex, limit, enableTotalRecordCount, enableImages, imageTypeLimit, enableImageTypes[], enableUserData, genres/genreIds, studios/studioIds, years, tags, officialRatings, person/personIds/personTypes, artistIds/albumArtistIds, ids, isPlayed, isFavorite, minCommunityRating, hasSubtitles, isHd, is4K, nameStartsWith/nameStartsWithOrGreater/nameLessThan (алфавитная навигация), collapseBoxSetItems, audioLanguages, subtitleLanguages, mediaTypes`. **Gotcha 12.0:** `recursive` теперь применяется автоматически при фильтрах, но только если задан `includeItemTypes` — результаты могут отличаться от 10.11.
- `GET /Items/{itemId}?userId=` — карточка; `POST /Items/{itemId}` — обновление метаданных; `DELETE`.
- Сериалы: `GET /Shows/NextUp?userId=&seriesId=&parentId=&limit=&enableResumable=&enableRewatching=&nextUpDateCutoff=&fields=`; `GET /Shows/{seriesId}/Seasons`, `GET /Shows/{seriesId}/Episodes?seasonId=&userId=&fields=`; похожие: `GET /Items/{itemId}/Similar` (также `/Movies/{id}/Similar`, `/Shows/{id}/Similar`; в 10.11 добавлены фильтр по тегам и случайная сортировка).
- Новинки: `GET /Items/Latest?userId=&parentId=&includeItemTypes=&limit=&groupItems=&fields=`. Продолжить: `GET /UserItems/Resume` (см. выше). Рекомендации: `GET /Items/Suggestions`. Счётчики: `GET /Items/Counts`. Фильтры для UI: `GET /Items/Filters`, `GET /Items/Filters2` (жанры/теги/годы/рейтинги для выбранной библиотеки).
- Поиск: `GET /Search/Hints?searchTerm=&userId=&includeItemTypes=&limit=&includePeople/Media/Genres/Studios/Artists` (быстрые подсказки, `SearchHint`) **или** `GET /Items?searchTerm=&recursive=true&includeItemTypes=Movie,Series,Episode&fields=…` (полные `BaseItemDto`, то, что делает веб-клиент).
- Коллекции (BoxSet): `POST /Collections?name=&ids=&parentId=&isLocked=`, `POST/DELETE /Collections/{collectionId}/Items?ids=`, содержимое — `GET /Items?parentId={boxSetId}`; `GET /Items/{itemId}/Collections` (новое в 12.x: в каких коллекциях состоит элемент).
- Плейлисты: `POST /Playlists` (тело `CreatePlaylistDto`), `GET/POST /Playlists/{playlistId}`, `GET/POST/DELETE /Playlists/{playlistId}/Items`, `POST .../Items/{itemId}/Move/{newIndex}`, доступы `GET/POST/DELETE /Playlists/{playlistId}/Users[/{userId}]`. В 12.0 плейлисты/коллекции вынесены из родительского элемента в отдельное хранилище (быстрее для больших списков).
- Люди/жанры/студии/годы: `GET /Persons?searchTerm=&personTypes=Actor&appearsInItemId=`, `GET /Persons/{name}`; `GET /Genres`, `GET /Genres/{genreName}`; `GET /Studios`, `GET /Studios/{name}`; `GET /Years`, `GET /Years/{year}`; музыка: `GET /Artists`, `/Artists/AlbumArtists`, `/MusicGenres/{genreName}` — **помечены deprecated в 12.0** (рекомендация релиза: `GetPersons`/`GetGenre`/`GetItems` с типами). Фильтрация по ним — через `/Items?genreIds=…`, `personIds=…`, `studioIds=…`.
- Картинки: `GET /Items/{itemId}/Images/{imageType}[/{imageIndex}]` с query `tag, maxWidth, maxHeight, width, height, fillWidth, fillHeight, quality, format (jpg|png|webp|…), blur, backgroundColor, foregroundLayer, percentPlayed, unplayedCount` (+ `HEAD`). Также «длинный» путь `/Items/{itemId}/Images/{imageType}/{imageIndex}/{tag}/{format}/{maxWidth}/{maxHeight}/{percentPlayed}/{unplayedCount}`. `ImageType`: `Primary, Art, Backdrop, Banner, Logo, Thumb, Disc, Box, Screenshot, Menu, Chapter, BoxRear, Profile`. Теги берите из `BaseItemDto.ImageTags{type→tag}`, `BackdropImageTags[]`, `ParentBackdropItemId/ParentBackdropImageTags`, `SeriesPrimaryImageTag`, `AlbumPrimaryImageTag`, `ParentLogoItemId/ParentLogoImageTag`, `ParentThumbItemId/ParentThumbImageTag` — `tag` даёт корректное кеширование/инвалидацию. **BlurHash:** `BaseItemDto.ImageBlurHashes {ImageType → {tag → blurhash}}` ([Dokka](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-model/org.jellyfin.sdk.model.api/-base-item-dto/image-blur-hashes.html)). Картинки людей/жанров/студий: `/Persons/{name}/Images/{imageType}`, `/Genres/{name}/Images/{imageType}`, `/Studios/{name}/Images/{imageType}`, `/Artists/{name}/Images/{imageType}/{imageIndex}`. **Gotcha 12.0:** сервер больше не апскейлит картинку выше исходного разрешения.
- Брендинг сервера для экрана логина: `GET /Branding/Configuration` (LoginDisclaimer, SplashscreenEnabled), `GET /Branding/Splashscreen`, `GET /Branding/Css`.
- Live TV (для полноты): `GET /LiveTv/Channels`, `/LiveTv/Channels/{channelId}`, `GET/POST /LiveTv/Programs`, `/LiveTv/Programs/Recommended`, запись/стрим: `/LiveTv/LiveRecordings/{recordingId}/stream`, `/LiveTv/LiveStreamFiles/{streamId}/stream.{container}`; для живого эфира PlaybackInfo с `autoOpenLiveStream=true` возвращает `LiveStreamId`, который надо закрывать (`POST /LiveStreams/Close`, не перепроверял путь). 12.0: Live TV больше не возвращает недостижимые server-local URL.
- Lyrics (10.9+): `GET /Audio/{itemId}/Lyrics` (`LyricDto`, с 10.11 — ELRC-cues по словам), `GET /Audio/{itemId}/RemoteSearch/Lyrics`, `GET /Providers/Lyrics/{lyricId}`; флаг `BaseItemDto.HasLyrics`.

---

## 5. Realtime: WebSocket и удалённое управление

- Эндпоинт: `ws(s)://<host>/socket` (константа `SOCKET_URL = "/socket"` в [DefaultSocketApi.kt](https://github.com/jellyfin/jellyfin-sdk-kotlin/blob/master/jellyfin-api/src/commonMain/kotlin/org/jellyfin/sdk/api/sockets/DefaultSocketApi.kt)). Авторизация — query-параметры (`ApiKey` + id устройства; при отключённом legacy сервер отвечает «Token is required», если прислать `api_key` — см. форум-тред выше). В OpenAPI самого `/socket` нет, но все схемы сообщений описаны (`WebSocketMessage`, `InboundWebSocketMessage`, `OutboundWebSocketMessage` и ~40 конкретных типов).
- Формат сообщения: JSON `{ "MessageType": "<SessionMessageType>", "MessageId": "<guid>", "Data": … }`. Enum `SessionMessageType` (12.2): `ForceKeepAlive, GeneralCommand, UserDataChanged, Sessions, Play, SyncPlayCommand, SyncPlayGroupUpdate, Playstate, RestartRequired, ServerShuttingDown, ServerRestarting, LibraryChanged, UserDeleted, UserUpdated, SeriesTimerCreated, TimerCreated, SeriesTimerCancelled, TimerCancelled, RefreshProgress, ScheduledTaskEnded, PackageInstallationCancelled/Failed/Completed, PackageInstalling, PackageUninstalled, ActivityLogEntry, ScheduledTasksInfo, ActivityLogEntryStart/Stop, SessionsStart/Stop, ScheduledTasksInfoStart/Stop, KeepAlive` ([Dokka SessionMessageType](https://kotlin-sdk.jellyfin.org/dokka/jellyfin-model/org.jellyfin.sdk.model.api/-session-message-type/index.html)).
- **KeepAlive:** сервер после подключения присылает `ForceKeepAlive` с `Data` = таймаут в секундах; клиент обязан периодически слать `{"MessageType":"KeepAlive"}` (`InboundKeepAliveMessage`), иначе соединение закроется. В Kotlin SDK это делает `DefaultSocketApi` автоматически, плюс реконнект по `SocketReconnectPolicy.ExponentialDelayReconnect`.
- **Подписки:** периодические данные надо явно запросить: `SessionsStart` с `Data: "initialDelayMs,intervalMs"` (например `"0,1500"`), `SessionsStop`; аналогично `ScheduledTasksInfoStart/Stop`, `ActivityLogEntryStart/Stop`. SDK шлёт start/stop сам при появлении/исчезновении подписчиков (интервал 1 с).
- События, важные для клиента: `UserDataChanged` (`UserDataChangeInfo {UserId, UserDataList[]}` — обновлять прогресс/избранное в UI), `LibraryChanged` (`LibraryUpdateInfo {ItemsAdded, ItemsUpdated, ItemsRemoved, FoldersAddedTo, FoldersRemovedFrom, CollectionFolders, IsEmpty}` — инвалидировать кэш), `ServerRestarting` / `ServerShuttingDown` / `RestartRequired` (показать баннер, переподключаться; HTTP во время рестарта отвечает **503 с `Retry-After`** — введено в 10.11), `RefreshProgress`, `UserUpdated/UserDeleted`.
- **Удалённое управление («Play to» / Cast на ваш клиент):** чтобы ваш клиент появился в списке целей у других клиентов, после логина отправьте возможности: `POST /Sessions/Capabilities?playableMediaTypes=Video,Audio&supportedCommands=…&supportsMediaControl=true&supportsPersistentIdentifier=true` или `POST /Sessions/Capabilities/Full` с телом `ClientCapabilitiesDto {PlayableMediaTypes, SupportedCommands[GeneralCommandType], SupportsMediaControl, SupportsPersistentIdentifier, DeviceProfile, AppStoreUrl, IconUrl}`. Далее по WebSocket приходят: `Play` (`PlayRequest {ItemIds, StartPositionTicks, PlayCommand: PlayNow|PlayNext|PlayLast|PlayInstantMix|PlayShuffle, MediaSourceId, AudioStreamIndex, SubtitleStreamIndex, StartIndex, ControllingUserId}`), `Playstate` (`PlaystateRequest {Command: Stop|Pause|Unpause|NextTrack|PreviousTrack|Seek|Rewind|FastForward|PlayPause, SeekPositionTicks}`), `GeneralCommand` (`GeneralCommandType`: `SetVolume, Mute/Unmute/ToggleMute, SetAudioStreamIndex, SetSubtitleStreamIndex, DisplayMessage, DisplayContent, GoHome, GoToSearch, Back, Select, MoveUp/Down/Left/Right, SetRepeatMode, SetShuffleQueue, SetPlaybackOrder, SetMaxStreamingBitrate, PlayTrailers, ToggleFullscreen, ToggleOsd, SendString, SendKey, …` с `Arguments` map). SDK даёт `api.webSocket.subscribeGeneralCommand(...)`, `subscribePlayStateCommands(...)`, `subscribeSyncPlayCommands(...)`, `subscribe<PlayMessage>()`.
- **Управление другими сессиями (ваш клиент как пульт):** `GET /Sessions?controllableByUserId=&activeWithinSeconds=`; `POST /Sessions/{sessionId}/Playing?playCommand=PlayNow&itemIds=…&startPositionTicks=…&mediaSourceId=&audioStreamIndex=&subtitleStreamIndex=&startIndex=`; `POST /Sessions/{sessionId}/Playing/{command}` (`PlaystateCommand`, `?seekPositionTicks=`); `POST /Sessions/{sessionId}/Command/{command}` или `POST /Sessions/{sessionId}/Command` (тело `GeneralCommand`); `POST /Sessions/{sessionId}/Message` (`MessageCommand {Header, Text, TimeoutMs}`); `POST /Sessions/{sessionId}/Viewing` («сейчас просматривает»); `POST/DELETE /Sessions/{sessionId}/User/{userId}`. Исходник: [SessionController.cs](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/SessionController.cs). С 10.11 не-админ может подписываться на свои сессии.
- Заметки релизов: 12.0 — «WebSockets have moved to SDK subscriptions, duplicate `/socket` connections fixed»; 12.1 — закрытие сокета больше не роняет request handler.

---

## 6. Обнаружение серверов в LAN, публичная информация, мульти-сервер, reverse proxy / TLS

- **UDP discovery:** broadcast на порт **7359/UDP** строки `who is JellyfinServer?`; сервер отвечает JSON `ServerDiscoveryInfo { Address, Id, Name, EndpointAddress }` (модель: [ServerDiscoveryInfo.cs](https://github.com/jellyfin/jellyfin/blob/master/MediaBrowser.Model/ApiClient/ServerDiscoveryInfo.cs); схема есть и в OpenAPI). Эталон клиента — [LocalServerDiscovery.kt](https://github.com/jellyfin/jellyfin-sdk-kotlin/blob/master/jellyfin-core/src/jvmCommonMain/kotlin/org/jellyfin/sdk/discovery/LocalServerDiscovery.kt): `DISCOVERY_MESSAGE = "who is JellyfinServer?"`, `DISCOVERY_PORT = 7359`, буфер 1024 байта, таймаут 500 мс, максимум 15 серверов, `DatagramSocket` с broadcast. Работает только внутри подсети; на Android нужен `WifiManager.MulticastLock` для надёжного приёма (общая практика, не проверено в SDK).
- [Docs: Networking](https://jellyfin.org/docs/general/post-install/networking/) — порты 8096/TCP (HTTP), 8920/TCP (HTTPS, по умолчанию выключен), 7359/UDP (discovery, не настраивается), 1900/UDP (DLNA — отдельный плагин), замечание о «Known Proxies» и рекомендация терминировать HTTPS на reverse proxy.
- **Публичная информация без авторизации:** `GET /System/Info/Public` → `PublicSystemInfo {LocalAddress, ServerName, Version, ProductName, OperatingSystem, Id, StartupWizardCompleted}` (используйте `Id` как ключ сервера в вашем хранилище, `Version` — для проверки совместимости); `GET /System/Ping` (строка «Jellyfin Server»); `GET /System/Endpoint` (`IsLocal`, `IsInNetwork` — сервер сообщает, считает ли он вас локальным); `GET /Branding/Configuration`; `GET /Users/Public`. Полный `GET /System/Info` требует авторизации.
- **«Умный» подбор адреса (SDK):** [AddressCandidateHelper.kt](https://github.com/jellyfin/jellyfin-sdk-kotlin/blob/master/jellyfin-core/src/jvmCommonMain/kotlin/org/jellyfin/sdk/discovery/AddressCandidateHelper.kt) — из пользовательского ввода (`192.168.1.5`, `host`, `https://host/jellyfin`) строит кандидаты: добавляет `http://`/`https://`, порты 8096/8920, сохраняет base path; [RecommendedServerDiscovery.kt](https://github.com/jellyfin/jellyfin-sdk-kotlin/blob/master/jellyfin-core/src/commonMain/kotlin/org/jellyfin/sdk/discovery/RecommendedServerDiscovery.kt) — параллельно дёргает `/System/Info/Public`, измеряет `responseTime`, присваивает `RecommendedServerInfoScore` GREAT/GOOD/OK/BAD по версии (`< minimumServerVersion` → OK с issue `UnsupportedServerVersion`; устаревшая, но поддерживаемая → GOOD; HTTPS предпочтительнее HTTP) и возвращает `RecommendedServerInfo {address, responseTime, score, issues, systemInfo}`. Эта логика стоит копирования даже без SDK.
- **Мульти-сервер / мульти-пользователь:** в API нет понятия «аккаунта» — клиент сам хранит список `(serverId, baseUrl[, альтернативные URL LAN/WAN], userId, accessToken, deviceId)`. `AuthenticationResult.ServerId` и `PublicSystemInfo.Id` одинаковы — используйте для дедупликации одного сервера, найденного по разным адресам (так делают официальные клиенты). Для нескольких пользователей на одном устройстве используйте разные `DeviceId` (один токен на DeviceId). Токены не истекают сами, но могут быть отозваны в дашборде (с 12.1 отзыв устройства разлогинивает сессии) — на 401 показывать повторный логин.
- [Docs: Reverse Proxy](https://jellyfin.org/docs/general/post-install/networking/reverse-proxy/) — требования, влияющие на клиент: прокси должен пропускать **WebSocket** (`/socket`), корректно пробрасывать `X-Forwarded-For/Proto/Host` (иначе сервер считает всех «удалёнными» и может резать битрейт/доступ), поддержка base URL (например `https://example.com/jellyfin` — ваш клиент обязан уметь путь в baseUrl), HTTP/3 опционален. Все остальные гайды (Caddy/Nginx/Traefik/HAProxy/Apache) — подразделы этой страницы.
- **Self-signed сертификаты:** Jellyfin сам может слушать 8920 с PKCS#12-сертификатом, но документация настойчиво рекомендует TLS на прокси. На Android 7+ пользовательские CA не доверяются приложениям по умолчанию — нужен `network_security_config.xml` с `<certificates src="user"/>` и/или собственный trust manager в OkHttp (SDK использует OkHttp; как подменить `OkHttpClient` в SDK 1.9 — не проверено). Также учитывайте cleartext HTTP (`usesCleartextTraffic`) — большинство домашних серверов работают по http://.
- Поведение при старте/рестарте сервера: HTTP **503 + `Retry-After` (секунды)** — клиенту следует ретраить, а не показывать «сервер недоступен» (release notes 10.11).

---

## 7. Прочие полезные документы: фичи, клиенты, сообщество, release notes и ломающие изменения

**Фичи**
- [Docs: Media Segments](https://jellyfin.org/docs/general/server/metadata/media-segments) — см. раздел 3.
- [Docs: Quick Connect](https://jellyfin.org/docs/general/server/quick-connect/) — см. раздел 1.
- [Docs: Codec Support](https://jellyfin.org/docs/general/clients/codec-support/) — таблицы по клиентам.
- [Docs: Live TV](https://jellyfin.org/docs/general/server/live-tv/) и [Live TV Setup Guide](https://jellyfin.org/docs/general/server/live-tv/setup-guide/) — тюнеры (HDHomeRun, M3U), гайды (XMLTV, Schedules Direct); клиентской части там нет, API — см. раздел 4.
- [Docs: Music (lyrics)](https://jellyfin.org/docs/general/server/media/music/) — форматы `.lrc/.elrc/.txt`, синхронизированные/несинхронизированные тексты.
- [Docs: Books](https://jellyfin.org/docs/general/server/media/books/) — в 12.0 книги/комиксы стали нативными (плагин Bookshelf депрекирован).
- Отдельных страниц документации по **SyncPlay** и **Trickplay** на jellyfin.org нет (проверил несколько путей — 404). Источники: OpenAPI (тег SyncPlay: `POST /SyncPlay/New, Join, Leave, Ping, Ready, Buffering, Pause, Unpause, Seek, Stop, NextItem, PreviousItem, Queue, SetNewQueue, SetPlaylistItem, MovePlaylistItem, RemoveFromPlaylist, SetRepeatMode, SetShuffleMode, SetIgnoreWait`, `GET /SyncPlay/List`, `GET /SyncPlay/{id}` (10.11+)), WebSocket-сообщения `SyncPlayCommand`/`SyncPlayGroupUpdate`, серверный [SyncPlayController.cs](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/SyncPlayController.cs) и эталонная реализация в веб-клиенте [jellyfin-web/src/plugins/syncPlay](https://github.com/jellyfin/jellyfin-web/tree/master/src/plugins/syncPlay) (синхронизация часов через `GET /GetUtcTime` и `SyncPlay/Ping`). Известные грабли: сервер ждёт `Ready` от всех участников до 30 с и ставит группу на паузу ([issue #18121](https://github.com/jellyfin/jellyfin/issues/18121) — упомянут в поиске, содержимое не читал).
- Trickplay: единственная «документация» — [release post 10.9.0](https://jellyfin.org/posts/jellyfin-release-10.9.0/) + контроллер/схемы (раздел 3). В 10.10 добавлено извлечение только по keyframes; в 10.11 trickplay-данные переехали в отдельную подпапку и чистятся при пересканировании.

**Клиенты и требования к листингу**
- [Downloads → Clients](https://jellyfin.org/downloads/clients/) — только официальные клиенты (Android, Android TV/Fire TV, Desktop/JMP, Kodi/JellyCon, iOS, Roku, WebOS, Tizen, Xbox) с вкладками Recommended/All.
- [Docs: Clients](https://jellyfin.org/docs/general/clients/) — **критерии для попадания стороннего клиента в официальный список**: Jellyfin как first-class (настраиваемый URL), FOSS-лицензия из списка FSF, ≥1 года активной разработки или заметный интерес, хотя бы одна уникальная фича относительно уже перечисленных для платформы, соблюдение community standards и branding; подача — PR в файл данных клиентов в репозитории jellyfin.org (точный путь файла не проверял).
- [Docs: Branding](https://jellyfin.org/docs/general/contributing/branding/) — **важно для именования вашего приложения**: нельзя называть продукт «Jellyfin …», избегать паттернов «Jelly[слово]» и «[слово]fin», нельзя использовать логотип без разрешения; можно писать «X, a Jellyfin client» и использовать цветовой градиент при уникальной форме логотипа. Ассеты — [jellyfin-ux](https://github.com/jellyfin/jellyfin-ux).
- [awesome-jellyfin CLIENTS.md](https://github.com/awesome-jellyfin/awesome-jellyfin/blob/main/CLIENTS.md) — большой список сторонних клиентов. Android: Findroid, Fladder, Jellyflix, AFinity, JellyCine, Linthra, Mellow, Moonfin, Coppelia, Finamp (музыка), Jellify, Fintunes, JellyBook и др.; Android TV: Elefin, Wholphin, Void, Fladder, Moonfin, Ghosten-Player, AfuseKt TV, Plezy, VidHub, Yamby. Полезно для анализа конкурентов и поиска «уникальной фичи» (libVLC-клиентов среди них почти нет).
- [Findroid](https://github.com/jarnedemeulemeester/findroid), [Streamyfin](https://github.com/streamyfin/streamyfin) — крупнейшие сторонние клиенты (Kotlin+SDK и React Native соответственно), полезны как референс поведения.

**Контрибьюция и сообщество**
- [Docs: Contributing](https://jellyfin.org/docs/general/contributing/) и [Development](https://jellyfin.org/docs/general/contributing/development/) — fork/feature-branch/PR, обсуждение крупных идей в [jellyfin-meta Discussions](https://github.com/jellyfin/jellyfin-meta/discussions), ссылки на style guides и API docs.
- [Contact](https://jellyfin.org/contact/) — Matrix (предпочтительно): `#jellyfin:matrix.org`, `#jellyfin-dev:matrix.org`, **`#jellyfin-dev-client:matrix.org`** и **`#jellyfin-dev-android:matrix.org`** (прямо про сторонние/Android-клиенты), `#jellyfin-troubleshooting`, Space `#jellyfinorg:matrix.org`; Discord-мост `https://discord.gg/zHBxVSXdBV`; форум [forum.jellyfin.org](https://forum.jellyfin.org) (раздел [Development](https://forum.jellyfin.org/f-development)); фичреквесты [features.jellyfin.org](https://features.jellyfin.org) (HEAD вернул 405 — сайт отвечает только на GET, ссылка рабочая).
- README SDK ссылается на Matrix-комнату `+jellyfin-android-dev:matrix.org` (space для Android-разработки).

**Release notes и ломающие изменения API (10.9 → 12.2)**
- [10.9.0 (2024-05-11)](https://jellyfin.org/posts/jellyfin-release-10.9.0/): Trickplay; корректные HTTP-коды у всех эндпоинтов; удалён EasyPassword (PIN) — остался Quick Connect; DLNA вынесен в плагин; плейлисты приватны по умолчанию.
- [10.10.0 (2024-10-26)](https://jellyfin.org/posts/jellyfin-release-10.10.0/): Media Segments (хранение на сервере, провайдеры — плагины); keyframe-only trickplay; выбор провайдеров лирики; удалены «network paths» в библиотеках (сломало Jellyfin4Kodi); клиенты в основном совместимы.
- [10.11.0 (2025-10-19)](https://jellyfin.org/posts/jellyfin-release-10.11.0/) + [GitHub v10.11.0](https://github.com/jellyfin/jellyfin/releases/tag/v10.11.0): депрекированы `OnPlaybackStart/Progress/Stopped` → `ReportPlayback*` (`/Sessions/Playing*`); добавлен серверный флаг для отключения legacy-авторизации (для тестирования клиентов); **503 + `Retry-After`** при старте/рестарте; `GET /System/Info/Storage`, Backup API, `GET /SyncPlay/{id}`; `ratingScore` для возрастных рейтингов; ELRC-лирика; `parentPrimaryImageItemId` стал Guid; явные дефолты enum'ов в моделях; генерируемые URL используют `ApiKey`; библиотека переехала на EF Core (`jellyfin.db`). Diff OpenAPI 10.10.7→10.11.11, который я сделал локально: удалён только `GET /System/WakeOnLanInfo`; добавлены `/Backup*`, `/SyncPlay/{id}`, `/System/Configuration/Branding`, `/System/Info/Storage`.
- [12.0 (2026-09-07)](https://jellyfin.org/posts/jellyfin-release-12.0/) + [GitHub v12.0](https://github.com/jellyfin/jellyfin/releases/tag/v12.0): смена версионирования (10.12 → 12.0; клиенты, парсящие версии, должны это учитывать); **legacy-авторизация выключена по умолчанию, в т. ч. миграцией на существующих серверах**; удалены префиксы `/emby/*`, `/mediabrowser/*`; удалены `GET /QuickConnect/Initiate` (только POST), `POST /Users/{userId}/EasyPassword`, `GET /Items/{itemId}/CriticReviews`, `GET /Environment/NetworkShares`, `POST /System/MediaEncoder/Path`, `GET /LiveTv/Recordings/Groups/{groupId}`; HLS-контроллеры скрыты из OpenAPI; Swashbuckle v10 (SDK надо регенерировать); `GetItems` асинхронный и меняет семантику `recursive`; `ItemByName`-ответы урезаны, люди дедуплицированы; `UserDto.HasPassword` бесполезен; `Accept-Language`; имена пользователей case-insensitive; картинки не апскейлятся; obsolete (пока работают): `GetTrailers`, `GetArtists/GetAlbumArtists`, `GetArtistByName`, `GetMusicGenre`, instant-mix по жанру, Startup-роуты. Политика: «не используйте эндпоинты/параметры, которых нет в OpenAPI или помеченные obsolete; депрекация держится один мажорный цикл». Мой локальный diff OpenAPI 10.11.11→12.2.0 (25 удалённых операций): все HLS (`/Videos/{id}/master.m3u8|main.m3u8|live.m3u8|hls/*|hls1/*`, `/Audio/{id}/master.m3u8|main.m3u8|hls/*|hls1/*`), `POST/DELETE /PlayingItems/{itemId}`, `POST /PlayingItems/{itemId}/Progress`, `GET /Artists/InstantMix`, `GET /MusicGenres`, `GET /LiveTv/Recordings/Groups`, `GET /LiveTv/Recordings/Series`, `GET /Items/{itemId}/CriticReviews`, `GET /Environment/NetworkShares`, `GET /Tmdb/ClientConfiguration`, `DELETE /Videos/ActiveEncodings`; добавлен `GET /Items/{itemId}/Collections`.
- [12.1 (2026-09-15)](https://github.com/jellyfin/jellyfin/releases/tag/v12.1): только фиксы — отзыв устройства разлогинивает сессии, forced-субтитры перекрывают запомненные, trickplay берёт правильный видеопоток, `/UserViews` не течёт по памяти, `Filters2` возвращает теги, фикс WebSocket.
- [12.2 (2026-10-05)](https://github.com/jellyfin/jellyfin/releases/tag/v12.2): фиксы — Dolby Vision p8 → тег `hvc1`, графические субтитры с Range, исправлено имя типа `BaseItemKind.Channel` (проверьте, если матчите строки типов), Next Up при повторном просмотре, фильтры по людям.
- [State of the Fin 2026-01-06](https://jellyfin.org/posts/state-of-the-fin-2026-01-06) — анонс обсуждения смены версии на 12.0, новый «Experimental» layout веба по умолчанию, тема через CSS-переменные.

---

## 8. Сторонние гайды / статьи («как написать клиент Jellyfin»)

Качественных полноценных туториалов мало; большинство материалов — справочники SDK. Что реально полезно:
- [Gist nielsvanvelzen — Jellyfin authorization](https://gist.github.com/nielsvanvelzen/ea047d9028f676185832e51ffaf12a6f) — лучший короткий текст по авторизации от мейнтейнера SDK (см. раздел 1).
- [Gist kylehowells — «Jellyfin Video Playback.md»](https://gist.github.com/kylehowells/74f538c766a244a3666319860a937030) — неформальная заметка: пример тела `POST /Items/{id}/PlaybackInfo?UserId=` с DeviceProfile, пример ответа с `MediaSources`, `TranscodingUrl` (`/videos/<id>/master.m3u8?...`), direct-play `/Videos/{mediaSourceId}/stream.mp4`, внешние субтитры `/Videos/<id>/Subtitles/5/0/Stream.vtt`. JSON в примерах «рукописный» (не парсится), версия сервера не указана, но как ориентир по потоку — ок. Последняя активность 06.2025.
- [Kotlin SDK Getting started](https://kotlin-sdk.jellyfin.org/guide/getting-started.html) / [Authentication](https://kotlin-sdk.jellyfin.org/guide/authentication.html) / [WebSockets](https://kotlin-sdk.jellyfin.org/guide/websockets.html) — официальные мини-туториалы; вместе с `samples/kotlin-cli` это фактически «how to write a client».
- [typescript-sdk.jellyfin.org](https://typescript-sdk.jellyfin.org/) — README с пошаговым сценарием: ClientInfo/DeviceInfo → `getAddressCandidates`/`getRecommendedServers` → `createApi` → `getPublicSystemInfo` → `authenticateUserByName` → `getUserViews` → logout. Хороший чеклист для порта.
- [mintlify.wiki Jellyfin API «overview»](https://mintlify.wiki/jellyfin/jellyfin/api/authentication/overview) — AI-сгенерированная документация; curl/JS/Python примеры логина корректны, но не считайте её официальной.
- [Jellywatch: What's New in 10.10/10.11](https://jellywatch.app/blog/jellyfin-10-10-10-11-upgrade-guide-new-features-2026), [Jellywatch: 12.0 release](https://jellywatch.app/blog/jellyfin-12-release-everything-you-need-to-know) — сторонний блог; обзорно, местами путаница «10.12 vs 12.0», проверяйте по официальным постам (не проверено содержание детально).
- [deepwiki: jellyfin-meta — OpenAPI endpoint domains & playback lifecycle](https://deepwiki.com/jellyfin/jellyfin-meta/3.2-openapi-specification:-endpoint-domains-and-playback-lifecycle) — AI-сгенерированная wiki по репозиторию; при двух попытках вернула 429, содержимое не проверено.
- [PR mia-cx/pendia #91](https://github.com/mia-cx/pendia/pull/91) — сторонний сервер, реализующий Jellyfin API (PlaybackInfo → DeviceProfile → direct/remux/transcode, `/Sessions/Playing*`); интересен как «обратная» документация того, что ожидают клиенты (октябрь 2026; содержимое PR не читал подробно).
- Сообщество Dart: [dart_jellyfin docs](https://pub.dev/documentation/dart_jellyfin/0.2.0/) — неофициальная библиотека с описанием Quick Connect-потока и замечанием про один токен на DeviceId.

---

## Краткий чеклист практических «граблей» для вашего клиента
1. Только `Authorization: MediaBrowser Client=…, Device=…, DeviceId=…, Version=…, Token=…`; в URL для libVLC — `?ApiKey=`. Никаких `X-Emby-*`/`api_key`.
2. `DeviceId` уникален на пару (установка, пользователь); иначе логины вытесняют друг друга.
3. Решение о способе воспроизведения принимает сервер по `DeviceProfile` в `POST /Items/{id}/PlaybackInfo`; используйте `TranscodingUrl` как есть, `PlaySessionId` — во всех отчётах и в `Ping`.
4. Все времена — тики по 100 нс; `PositionTicks = ms * 10_000`.
5. Отчёты: `POST /Sessions/Playing`, `/Progress` (периодически + при pause/seek), `/Stopped` (обязательно, иначе сессия «висит» и транскодер не освобождается).
6. Запрашивайте `fields=` явно (`MediaSources, MediaStreams, Chapters, Trickplay, Overview, Genres, People, …`) — по умолчанию `BaseItemDto` неполный.
7. Используйте `tag` в URL картинок и `ImageBlurHashes` для плейсхолдеров.
8. WebSocket `/socket`: отвечать `KeepAlive` на `ForceKeepAlive`, отправить `/Sessions/Capabilities(/Full)`, чтобы принимать `Play`/`Playstate`/`GeneralCommand`.
9. Обрабатывайте 503 + `Retry-After` и `ServerRestarting`.
10. Поддержка base path в URL сервера (`https://host/jellyfin`), cleartext HTTP, пользовательские CA (network security config).
11. Название приложения — не «Jellyfin…», не «Jelly*», не «*fin» (branding guide), иначе в официальный список клиентов не попадёте.
12. Для совместимости с 10.11 и 12.x одновременно SDK не подходит (1.8 ↔ 1.9 ломают друг друга); либо целиться только в 12.x с SDK 1.9, либо писать тонкий клиент по OpenAPI и держать совместимость самостоятельно (разница в путях между 10.11.11 и 12.2 — см. список выше).

Для анализа использовались OpenAPI-спецификации 10.10.7 / 10.11.11 / 12.2.0 и выдержки исходников SDK (в репозиторий не входят).
---

# Часть 2. Обзор open-source Jellyfin-клиентов и смежных проектов

Дата среза: 2026-10-11. Звёзды/даты последнего push взяты из GitHub API в этот день (если не указано иное). Всё, что не удалось подтвердить напрямую, помечено «(не проверено)».

## 2.0 Главные выводы

1. **Ни один живой Kotlin-клиент Jellyfin не использует libVLC как основной движок.** Официальный `jellyfin-androidtv` удалил libVLC в июне 2024 (PR #3639, релиз 0.17). Единственный актуальный Kotlin-клиент с libVLC-бэкендом — **JellyPlay** (raulshma/jellyplay, 31★, бета, `libvlc-all 3.7.5`), где VLC — третий «запасной» движок после ExoPlayer и libmpv. Все остальные крупные native-клиенты (Findroid, Wholphin, AFinity, Void, JellyCine) выбрали пару **media3/ExoPlayer + libmpv**.
2. **Лучший референс по DeviceProfile для VLC-движка — Swiftfin** (`Shared/Objects/VideoPlayerType/VideoPlayerType+VLC.swift`, `Shared/Extensions/JellyfinAPI/DeviceProfile.swift`): полные списки direct-play/transcoding/subtitle/codec-профилей для libVLC, режимы совместимости, пользовательские профили. Swiftfin в 2026 перешёл с VLCKit на **SwiftVLC (libVLC 4.0)** и добавил экспериментальный **MPV**-бэкенд (MPVUI).
3. **Лучший референс по DeviceProfile для Android (MediaCodec-зондирование, HDR, passthrough, known-defects по моделям)** — `jellyfin-androidtv/app/src/main/java/org/jellyfin/androidtv/util/profile/` (использует DSL `buildDeviceProfile` из jellyfin-sdk-kotlin) и `jellyfin-android/.../player/deviceprofile/DeviceProfileBuilder.kt`.
4. **Уроки удаления libVLC из jellyfin-androidtv**: (а) устаревший артефакт `libvlc-all 3.5.1` (2022) с незакрытым CVE — F-Droid поднял вопрос (issue #3623); (б) «плохая» реализация JNI-биндингов: объекты не всегда уничтожались → утечки и краши при use-after-destroy; (в) смешанный код ExoPlayer+VLC в одном `VideoManager.java` → проблемы с памятью; (г) размер APK 107 МБ → 24 МБ после удаления. Сейчас на Maven Central есть `org.videolan.android:libvlc-all` **3.7.7** (ветка 3.x) и **4.0.0-eap29** (обновлено 2026-10-01), т.е. причина (а) снята.
5. Пользователи требовали libVLC ради **ASS/SSA и PGS субтитров, DTS-HD/TrueHD, Dolby Vision в MKV**, которые ExoPlayer не тянул. Сегодня официальный клиент закрывает ASS через `io.github.peerless2012:ass-media` (libass для media3) — это альтернатива, которую стоит знать.
6. Брендинг: название «Jellyfin» и логотип — зарегистрированные товарные знаки Jellyfin, Inc.; сторонним приложениям нельзя называться «Jellyfin X», нежелательны «Jelly[word]»/«[word]fin», логотип (и его повороты/вставки) запрещён без разрешения; можно использовать градиент цветов и писать «клиент для Jellyfin» в описании.

---

## 2.1 Официальные проекты Jellyfin

### jellyfin-androidtv — официальный Android TV-клиент
| | |
|---|---|
| URL | https://github.com/jellyfin/jellyfin-androidtv |
| Лицензия | GPL-2.0 |
| Стек | Kotlin (565 .kt / 36 .java файлов на master), Koin DI, Coil 3, jellyfin-sdk-kotlin 1.8.12 |
| Плеер | media3/ExoPlayer 1.11.1 + `org.jellyfin.media3:media3-ffmpeg-decoder` (аудио DTS/TrueHD и т.п.) + `io.github.peerless2012:ass-media` 0.5.1 (libass). Внешние плееры через Intent: VLC, mpv, MX Player, Vimu (`ui/playback/external/*ExternalPlayerApi.kt`) |
| Цель | только TV (Android TV, Shield, Fire TV), minSdk 23 (с 0.20) |
| UI | гибрид: Leanback 1.2.0 + Compose 1.12.1 (в 0.20 настройки и MainActivity переведены на Compose; 36 XML-layout ещё остались) |
| Статус | активно: 4.6k★, push 2026-10-08; стабильная 0.19.10 (авг 2026), 0.20.0-beta.3 (24 сен 2026), требует сервер 10.11 |

**История libVLC (проверено по PR/issue):**
- До 0.16.x включительно в `app/.../ui/playback/VideoManager.java` жили оба движка: ExoPlayer и `org.videolan.libvlc.LibVLC/MediaPlayer` (артефакт `org.videolan.android:libvlc-all:3.5.1`). VLC инициализировался опциями `--network-caching`, `--audio-time-stretch`, `--avcodec-skiploopfilter`, `--android-display-chroma`, `--vout=android-opaque,android-display`, опциональный `--video-filter=deinterlace`; субтитры рисовались во второй `SurfaceView` (`setSubtitlesView`), выбор дорожек через `getSpuTracks()/setSpuTrack()`, задержка через `setSpuDelay`. Полезно как список граблей, а не как образец.
- 2023-02-23 PR #2534 «Mark LibVLC as experimental»: «много крашей, потому что наша реализация просто плохая».
- Discussion #2546 (2023-02): мейнтейнер nielsvanvelzen — JNI-биндинги «не всегда корректно уничтожаются» → утечки/краши; старый код playback «end-of-life»; новый playback-модуль стартует только с ExoPlayer; libVLC/mpv «возможно позже, но с нуля, с тестами».
- 2024-06-02 PR #3639 «Remove LibVLC» (релиз 0.17.0), закрывает issue #3623 «CVE-2023-5217 — update libvlc» (поднято из-за F-Droid): последняя стабильная libvlc-jni 3.5.1 от августа 2022, незакрытая CVE; «смешение ExoPlayer и LibVLC могло вызывать проблемы с памятью»; артефакты сборки 107 МБ → 24 МБ. Мейнтейнер: «LibVLC может вернуться, но возможно выберем mpv или FFmpeg».
- Issue #3937 (авг 2024, «вернуть выбор плеера») закрыт как not planned. Форумный тред «SOLVED: Bring back libvlc» — совет ставить 0.16.11 либо внешний VLC/mpv.
- Issue #3888: после удаления гибридные DV/HDR10-файлы дают чёрный экран в ExoPlayer — closed/not planned.

**Что заимствовать:**
- Архитектуру playback-модулей: `playback/core` (PlaybackManager, PlayerBackend-абстракция, очередь с QueueSupplier/OrderIndexProvider, MediaStreamResolver, TimedEventService), `playback/jellyfin` (JellyfinMediaStreamResolver, PlaySessionService + socket-репортинг, MediaSegmentService, lyrics), `playback/media3/exoplayer` (ExoPlayerBackend, `support/*` — отчёт о поддержке формата, `mapping/*` — маппинг кодеков) и `playback/media3/session`. Это готовый чертёж, куда вместо ExoPlayerBackend подставляется VlcBackend.
- `util/profile/deviceProfile.kt` + `MediaCodecCapabilitiesTest`, `codec/*CodecCapabilities.kt`, `audioPassthrough.kt`, `KnownDefects.kt` (чёрные списки моделей Fire TV с багом DoVi/HDR10+, Shield с нераспознанным DV Profile 7). Профиль учитывает downmix, bitstream AC3/EAC3/DTS/TrueHD (auto-детект passthrough), `assDirectPlay`/`pgsDirectPlay`, пользовательские AVC/HEVC level override, принудительное включение/выключение HDR-форматов, два transcoding-профиля HLS (TS и fMP4) с `enableSubtitlesInManifest`.
- Внешние плееры: `VlcExternalPlayerApi.kt` реализует интенты VLC (`subtitles_location`, `position`, коды результата) — полезно для fallback «открыть в VLC».
- Боли из issues: TV-навигация и фокус на Leanback тяжело поддерживать (поэтому идёт миграция на Compose), нестабильность DV/HDR на Fire TV, отсутствие субтитров нужных форматов в ExoPlayer.

### jellyfin-android — официальный мобильный клиент
| | |
|---|---|
| URL | https://github.com/jellyfin/jellyfin-android |
| Лицензия | GPL-2.0 |
| Стек | Kotlin, WebView-обёртка jellyfin-web + нативный плеер; Koin 4.2.2, Compose 1.12.1 (частично), jellyfin-sdk 1.8.12 |
| Плеер | media3/ExoPlayer 1.11.1 (+hls, +cast, +ffmpeg-decoder), Chromecast только в proprietary-flavor |
| Цель | телефон/планшет |
| Статус | 2.8k★, push 2026-10-09, активен |

**Что заимствовать:** `app/src/main/java/org/jellyfin/mobile/player/deviceprofile/DeviceProfileBuilder.kt` — профиль строится динамически по `MediaCodecList`: таблица контейнер→допустимые видео/аудио-кодеки, `CodecProfile` c `VIDEO_PROFILE` из реальных возможностей декодера, набор `FORCED_AUDIO_CODECS` (кодеки от FFmpeg-расширения, которые MediaCodecList не объявляет), отдельный `getExternalPlayerProfile()` (всё direct-play, без transcoding — ровно то, что нужно для VLC-режима «играй всё сам»), профили субтитров EMBED/EXTERNAL с опцией `exoPlayerDirectPlayAss`. Также `player/source/MediaSourceResolver.kt`, `ExternalSubtitleStream.kt`, `QueueManager`, `TrickplayHelper`, `mediasegments/`, `PlayerService`/MediaSession.

### jellyfin-sdk-kotlin
| | |
|---|---|
| URL | https://github.com/jellyfin/jellyfin-sdk-kotlin (docs: https://kotlin-sdk.jellyfin.org) |
| Лицензия | LGPL-3.0 |
| Стек | Kotlin, Android + JVM; модули `jellyfin-core`, `jellyfin-api`, `jellyfin-model`, `jellyfin-api-okhttp`; код генерируется из `openapi.json` |
| Версия | **1.9.2** на Maven Central (обновлено 2026-10-06); клиенты в основном на 1.8.12 (10.11 SDK) |
| Статус | 125★, push 2026-10-08 |

**Что заимствовать:** использовать как есть. Есть DSL `org.jellyfin.sdk.model.deviceprofile.buildDeviceProfile { directPlayProfile {…}; transcodingProfile {…}; codecProfile { conditions { … } } }` (видно в jellyfin-androidtv), server discovery, socket API. Binary-compatibility validator — пример дисциплины для публичного API модуля плеера.

### Swiftfin (iOS/tvOS) — главный референс по VLC-профилям
| | |
|---|---|
| URL | https://github.com/jellyfin/Swiftfin ; docs: https://github.com/jellyfin/Swiftfin/blob/main/Documentation/players.md |
| Лицензия | MPL-2.0 |
| Стек | Swift, SwiftUI, jellyfin-sdk-swift 3.3.0 |
| Плеер | три бэкенда: `VideoPlayerType.avPlayer` (native), `.vlc` — **SwiftVLC 1.0.0 (harflabs/SwiftVLC, libVLC 4.0, MIT)**, `.mpv` — **MPVUI 0.5.0 (LePips/MPVUI)**, пока за флагом `Experimental.mpvPlayer`. Прокси: `Shared/Objects/MediaPlayerManager/MediaPlayerProxy/MediaPlayerProxy+{VLC,MPV,AVPlayer}.swift` |
| Цель | iOS 18+, tvOS 26+ |
| Статус | 4.2k★, push 2026-10-10, активен |

**Что заимствовать (конкретные файлы):**
- `Shared/Objects/VideoPlayerType/VideoPlayerType+VLC.swift`: `_vlcDirectPlayProfiles` (30 аудио- и ~25 видео-кодеков, AV1 только при `PlaybackCapabilities.supportsAV1`), `_vlcTranscodingProfiles` (HLS, `isBreakOnNonKeyFrames`, `maxAudioChannels 8`, `minSegments 2`, контейнер mp4; **отдельный профиль для VC1**, чтобы обойти серверное ограничение `_supportedHlsVideoCodecs`), `_vlcSubtitleProfiles` (embed: ass, ssa, pgssub, dvbsub, dvdsub, subrip, vtt, ttml, mov_text, sami, teletext…; external: subrip, ass, ssa, …; encode/burn-in: dvbsub, dvdsub, pgssub, vtt, xsub — т.е. внешние bitmap-субтитры VLC не грузит, их прожигают), `_vlcCodecProfiles` (условия `videoRangeType` для h264/hevc/av1/vp9, `isAnamorphic`, `isInterlaced`, список HDR-режимов `vlcHDRProfiles`: HLG/HDR10/HDR10+/DoVi-with-*).
- `Shared/Extensions/JellyfinAPI/DeviceProfile.swift`: `DeviceProfile.build(for:compatibilityMode:maxBitrate:maxResolution:)` — режимы `auto / mostCompatible / directPlay / custom`, принудительный burn-in субтитров (`forceSubtitleBurnIn`), методы `canPlay(...)` для проверки direct-play локально; `PlaybackDeviceProfile.swift` — пользовательские профили в UI (`CustomDeviceProfilesView`).
- `MediaPlayerProxy+VLC.swift`: выбор дорожек по индексу `MediaStream.index` ↔ `player.audioTracks/subtitleTracks`, `setAudioDelay`/`setSubtitleDelay`, протоколы `MediaPlayerSubtitleConfigurable`/`MediaPlayerOffsetConfigurable`; `TextSubtitlePresentation.swift` — overlay текстовых субтитров поверх MPV (у VLC субтитры рисует сам движок).
- Документ players.md фиксирует ограничения VLC: нет TLS 1.3 в libvlc, нет DTS-HD/TrueHD/MLP, нет PiP, external-display только зеркалом, повышенное энергопотребление при software-decoding, DV Profile 5 не поддерживается, HDR→SDR tonemapping «точность цвета может плавать».

### jellyfin-web / jellyfin-vue
- **jellyfin-web** — https://github.com/jellyfin/jellyfin-web, GPL-2.0, JS/TS (webpack+vite), 3.9k★, push 2026-10-11, v13.0.0. Плеер — HTML5 `<video>` + `hls.js 1.6.16`, ASS через `@jellyfin/libass-wasm 4.2.4`; профиль устройства — `src/scripts/browserDeviceProfile.js`. Полезен как «эталонная» логика выбора direct-play/transcode и как источник семантики полей DeviceProfile.
- **jellyfin-vue** — https://github.com/jellyfin/jellyfin-vue, GPL-3.0, Vue/Vite, 1.7k★, push 2026-10-11; «экспериментальный, стабильных релизов нет». `hls.js 1.7.3` в `packages/frontend/package.json`; libass/JASSUB — (не проверено).

### jellyfin-desktop (бывш. jellyfin-media-player) и jellyfin-mpv-shim
- **jellyfin-media-player → переименован в jellyfin-desktop** (старый URL редиректит): https://github.com/jellyfin/jellyfin-desktop, GPL-2.0, Qt WebEngine + libmpv, ~5.9k★. jellyfin-web в WebEngine + нативный mpv; пользовательский `mpv.conf`. Заимствовать: идею «веб-UI + нативный движок» не стоит, но полезны настройки passthrough/HW-decoding.
- **jellyfin-mpv-shim** — https://github.com/jellyfin/jellyfin-mpv-shim, Python, 2.5k★, push 2026-10-11. Cast-target + полноценный клиент; запоминание аудио/субтитров на сериал, задержка субтитров, SyncPlay (хрупкий), passthrough через `audio_mode hdmi/optical`, шейдер-паки. Заимствовать: UX «запомнить выбранные дорожки для всего сериала».

### jellyfin-roku, jellyfin-kodi / JellyCon, jellyfin-ios (ex-expo)
- **jellyfin-roku** — https://github.com/jellyfin/jellyfin-roku, GPL-2.0, BrighterScript, 236★, push 2026-10-10; нативный Roku Video node; есть trickplay, media segments, загрузка субтитров. Полезен как ещё одна TV-реализация D-pad-навигации, но стек не переносим.
- **jellyfin-kodi** — https://github.com/jellyfin/jellyfin-kodi, GPL-3.0, Python, 1.1k★, push 2026-10-11 — синхронизация библиотеки в БД Kodi (+ динамические списки); **JellyCon** — https://github.com/jellyfin/jellycon, GPL-2.0, Python, 559★ — чисто динамический браузинг. Плеер — внутренний Kodi (ffmpeg). Интересны как пример «offline-first» кэша метаданных.
- **jellyfin-expo** → редирект на **jellyfin-ios**: https://github.com/jellyfin/jellyfin-ios, MPL-2.0, Expo/React Native, 603★, push 2026-10-10 — web-wrapper для iOS с нативными расширениями.

---

## 2.2 Сторонние нативные Android-клиенты (Kotlin)

### Findroid
| | |
|---|---|
| URL | https://github.com/jarnedemeulemeester/findroid |
| Лицензия | GPL-3.0 |
| Стек | Kotlin, Compose + Material 3 (v1.0.0 «Compose» 30.12.2025), Hilt 2.60, Room 3.0.3, Paging, jellyfin-sdk 1.8.12 |
| Плеер | media3/ExoPlayer 1.11.1 + `org.jellyfin.media3:media3-ffmpeg-decoder`; **libmpv** через собственный артефакт `dev.jdtech.mpv:libmpv 1.0.0` (репо https://github.com/jarnedemeulemeester/libmpv-android, MIT, на базе mpv-android); только direct play, транскодинга нет |
| Цель | телефон; **в репо есть модуль `app/tv` (55 файлов, `androidx.tv:tv-material 1.1.0`)**, но README по-прежнему пишет «Android TV — planned» |
| Статус | 4.3k★, push 2026-10-10; v1.1.0 (18.07.2026): редактирование `mpv.conf`/`input.conf` из настроек, системные шрифты для mpv; v1.0.0 требует сервер 10.11 (Media Segments API) |

**Что заимствовать:** модульная структура `app/{phone,tv}`, `core`, `data`, `player/{core,local}`, `setup`, `settings`, `modes/film`. Главное — `player/local/src/main/java/dev/jdtech/jellyfin/player/local/mpv/MPVPlayer.kt`: **реализация `androidx.media3.common.Player` (через `BasePlayer`) поверх стороннего движка** с `ListenerSet`, audio focus, duck, `Commands`; тот же приём стоит повторить для libVLC (`VlcPlayer : BasePlayer`), чтобы переиспользовать media3-session, PlayerView-подобные контролы и уведомления. Также: offline downloads с локальными картинками, trickplay, Media Segments skip/auto-skip, `MpvCleanupWorker`. Боли из README: «SSA/ASS в ExoPlayer — ограниченная стилизация (google/ExoPlayer#8435)», отсюда и mpv.

### Wholphin
| | |
|---|---|
| URL | https://github.com/damontecres/Wholphin |
| Лицензия | GPL-2.0 |
| Стек | Kotlin, single-Activity MVVM, **Compose (BOM 2026.09) + `androidx.tv:tv-foundation/tv-material`**, Navigation 3, Hilt, Room, DataStore(proto), Coil, OkHttp, jellyfin-sdk 1.7.1 |
| Плеер | media3/ExoPlayer 1.11.1 (+dash, +hls, `media3-ui-compose`, `media3-effect`) + собственные расширения `com.github.damontecres.wholphin.media3:decoder-ffmpeg/decoder-av1` + **libmpv** (`wholphin-mpv`, с `wholphin-mpv-stub`, чтобы собираться без нативов) + `ass-media` 0.5.1 (libass) |
| Цель | только TV (Android TV/Fire TV, minSdk 23), сервер 10.10/10.11 |
| Статус | 3.0k★, push 2026-10-11, очень активен; «не форк официального клиента, UI в духе Plex» |

**Что заимствовать:** эталон современного **Compose-for-TV** клиента без Leanback: кастомный home с перестановкой рядов, Plex-подобные контролы плеера под D-pad, trickplay, Live TV/DVR, PIN-профили, screensaver. По их README они **адаптировали код direct-play DeviceProfile из официального клиента** — удобная «вырезка». `DEVELOPMENT.md` описывает слоистые настройки (proto DataStore / Room / key-value) и схему experimental-флагов. Паттерн «stub-модуль для нативного движка» (`wholphin-mpv-stub`) идеально переносится на libVLC (сборка без 60-мегабайтного .aar для CI/превью).

### JellyPlay — единственный живой Kotlin-клиент с libVLC
| | |
|---|---|
| URL | https://github.com/raulshma/jellyplay ; docs: `docs/player-engines.md` |
| Лицензия | GPL-3.0 (в LICENSE; GitHub API показывает NOASSERTION) |
| Стек | Kotlin Multiplatform + Compose Multiplatform; Android — Compose/Material 3 + Compose for TV; Koin 4.2.2; ~25 feature-модулей в `shared/feature/*`, `shared/core/player-contract` с engine-agnostic интерфейсом `MediaEngine`; jellyfin-sdk 1.8.12 |
| Плеер | **три движка на выбор per device**: media3/ExoPlayer 1.10.1 (+ffmpeg-decoder), libmpv (`io.github.abdallahmehiz:mpv-android-lib 0.1.12`), **libVLC `org.videolan.android:libvlc-all 3.7.5`**; desktop — libmpv через JNA |
| Цель | телефон + Android TV/Fire TV + Windows preview |
| Статус | 31★, 1 203 коммита, push 2026-10-11, «Active Beta» |

**Что заимствовать:** `docs/player-engines.md` — честная таблица компромиссов, в т.ч. **минусы libVLC на Android по их опыту**: нет runtime-аудиоэффектов (только стартовые `--audio-filter`), ограниченная стилизация субтитров (нет ASS override, цветов, border-style), **нет нативной поддержки trickplay sprite-sheets**, «реже Android-специфичные обновления», LL-HLS хуже, нет mini-player; плюсы — широчайшая поддержка контейнеров, HW-декодирование на большинстве чипов, passthrough и audio-delay, фильтры яркость/контраст, встроенный renderer/cast. Также: сохранение выбора дорожек при смене движка/транскода, WorkManager-загрузки с Range-resume, SyncPlay с коррекцией скорости. Минус как референса: мало пользователей, один открытый issue — реальная обкатка слабая.

### AFinity
- https://github.com/MakD/AFinity — GPL-3.0, Kotlin, Compose/M3, MVVM+Repository, Hilt, Room, Retrofit+SDK, Tink для секретов; **libmpv (libass для ASS) + ExoPlayer с FFmpeg-декодером**, переключаемые. Телефон/планшет, **Android 15+**. 243★, push 2026-10-10. Заимствовать: offline-home, download manager, SyncPlay, Chromecast, Live TV EPG; боль — release-сборка без `key.properties` подписывается debug-ключом.

### Void for Jellyfin
- https://github.com/hritwikjohri/Void-for-jellyfin — GPL-3.0, Kotlin, Compose/M3, Clean Architecture, Hilt, Room, WorkManager; плеер — **mpv через `dev.marcelsoftware.mpvcompose:mpv-compose`**, fallback media3 1.9.0. minSdk 26, телефон. ~474★ (по странице), ранняя альфа. Полезен как компактный пример mpv-in-Compose.

### JellyCine
- https://github.com/sureshfizzy/JellyCine — GPL-3.0, Kotlin 2.3 Multiplatform (Android + iOS в разработке), Compose/M3, Hilt+KSP, Ktor; плеер — **кастомный форк mpv + fallback media3**; HDR10/HDR10+/DV, passthrough TrueHD/DTS-HD; поддержка **Jellyfin и Emby** с авто-разрешением эндпоинтов; **модуль `tv/` на Leanback/D-pad** отдельно от `phone/`. 109★, push 2026-10-10. Заимствовать: разделение phone/tv с общим `core/` (shared player), Emby-совместимость.

### Mellow
- https://github.com/Malinskiy/mellow — Apache-2.0, Kotlin, Compose/M3, media3; offline-first музыкальный плеер с Android Auto, `MediaLibraryService`, screenshot-тесты на Robolectric (168 кейсов), in-app обновления с проверкой SHA-256 и ключа подписи. 26★, push 2026-10-07. Заимствовать: тестовую инфраструктуру и безопасный self-update.

### Форки официального Android TV клиента
- **JellyTV** — https://github.com/d8f6/JellyTV, GPL-2.0, форк jellyfin-androidtv с целью «вернуть libVLC по умолчанию и убрать телеметрию»; **все цели не выполнены**, 0★, последний push 2025-02-21 — мёртв, но подтверждает спрос.
- **CookeInCode/jellyfin-androidtv** (форум Jellyfin, 2024) — UI-урезанный форк с другим package name; мейнтейнер советовал апстримить. (не проверено состояние)
- **Moonfin**: ранее Kotlin-форк `Moonfin-Client/AndroidTV-FireTV` (~624★ по сторонним агрегаторам) — **репозиторий сейчас 404**; организация https://github.com/Moonfin-Client перешла на **Moonfin-Core** (Flutter/Dart, GPL-2.0+, 821★, push 2026-10-11) — единая кодовая база для Android TV/Apple TV/мобильных/десктопа/веб, Jellyfin 10.8+ и Emby 4.8+, mpv (`mpv.conf` в wiki; движок явно не назван — не проверено), «premium»-фичи через серверный плагин Moonbase, DV Profile 7 конвертация на лету на Apple/Android TV, OTA-обновления.

### Komorebi (не Jellyfin, но libVLC + Compose for TV)
- https://github.com/BeerEgg2001/Komorebi — MIT, Kotlin, **Compose for TV (TV Material3)**, Hilt, media3 (с FFmpeg-расширением) как основной движок и **libVLC для SMB/файловых библиотек**; клиент японского DTV (KonomiTV/EDCB/EPGStation). 118★, push 2026-10-08. Ценно: кастомный `FocusTicketManager` для стабильного фокуса под пультом, JNI-рендер ARIB-субтитров; боль — **libVLC сильно раздувает APK, Universal-сборка не ставится на слабые устройства → отдельные ABI-сборки; Fire TV — 32-битная ОС, arm64 не ставится**.

---

## 2.3 Кроссплатформенные клиенты (React Native / Flutter)

### Streamyfin
| | |
|---|---|
| URL | https://github.com/streamyfin/streamyfin |
| Лицензия | MPL-2.0 |
| Стек | Expo 57 / React Native (react-native-tvos 0.86), TypeScript, Bun, модули в `modules/` |
| Плеер | сейчас **MPV (MPVKit на Apple, нативный mpv-модуль `modules/mpv-player`)** как основной; с 0.55.0 (04.10.2026) — «полностью нативный плеер»: SwiftUI на Apple, **Jetpack Compose на Android/Android TV**, плюс opt-in **ExoPlayer-движок на Android TV** (`modules/exoplayer-player`, HDR10/HDR10+, DV P5, Atmos passthrough); KSPlayer как опция на iOS. До 0.51.0 (янв 2026) основным был **VLC** (релиз 0.51 говорит об «improved native VLC integration» на Apple); использование libVLC на Android в старых версиях — (не проверено) |
| Цель | iOS, tvOS, Android, Android TV |
| Статус | 5.3k★, push 2026-10-11, самый популярный сторонний клиент |

**Что заимствовать:** эволюция движков VLC → MPV → нативный Compose+ExoPlayer на TV — показатель, что команда ушла от libVLC (причины публично не раскрыты, кроме «лучше HW-декодирование/PiP»). Полезны: выбор дорожек «по идентичности, а не позиции», матчинг языков с регионом (pt-BR vs pt-PT), стилизация субтитров, поиск/скачивание субтитров в плеере, серверный плагин для синхронизации настроек, авторизация через кастомные заголовки (Cloudflare Zero Trust). Боли: загрузки через HLS→ffmpeg в реальном времени (медленно), Chromecast без субтитров.

### Fladder
- https://github.com/DonutWare/Fladder — GPL-3.0, Flutter/Dart, Android + **Android TV** + desktop + web/Docker; 2.6k★, push 2026-09-18. Плеер не назван в README (media_kit/mpv — не проверено); в числе пользователей `libass-android` (по README peerless2012). Фичи: direct/transcode/offline, Media Segments, trickplay, CBZ/CBR, Seerr. Боль: «улучшить D-pad-навигацию на не-TV» в планах, Chromecast не готов.

### Finamp (музыка)
- https://github.com/finamp-app/finamp (старый URL UnicornsOnLSD/finamp редиректит) — MPL-2.0, Flutter, 4.3k★, push 2026-10-11; Android/iOS/desktop; офлайн-загрузки, транскодинг, gapless, ReplayGain, Android Auto/CarPlay, отчёт прослушиваний офлайн (Playback Reporting plugin). Библиотека аудио (just_audio?) — (не проверено).

### Jellyflix
- https://github.com/jellyflix-app/jellyflix — GPL-3.0, Flutter, 466★, push 2026-09-17; mobile+desktop; офлайн-загрузки, HDR tonemapping; на Linux требует libmpv → движок mpv (вывод по зависимостям, media_kit — не проверено).

### Plezy (Plex + Jellyfin + Emby)
- https://github.com/edde746/plezy — GPL-3.0, Flutter; desktop/mobile/**Android TV, Apple TV**; ~2.1k★ (сторонний агрегатор). **Android: ExoPlayer по умолчанию, mpv опционально (нужен для шейдеров), ASS через libass-android; desktop — mpv (форк MPVKit); iOS/tvOS — mpv недоступен**. Watch Together, Quick Connect, мультисервер. Заимствовать: подход «ExoPlayer default + тяжёлый движок opt-in».

### Музыкальные RN/Java
- **Fintunes** — https://github.com/leinelissen/jellyfin-audio-player, MIT, TypeScript/RN, 1.4k★, push 2026-07-17 (react-native-track-player — не проверено).
- **Jellify** — https://github.com/Jellify-Music/App, MIT, RN, 1.8k★, push 2026-10-05.
- **Gelli** — https://github.com/dkanada/gelli, GPL-3.0, Java, 534★, **архив с 2023-06**.

---

## 2.4 Закрытые / коммерческие и прочие упомянутые

- **Symfonium** — проприетарный, offline-first музыкальный клиент (Jellyfin/Emby/Plex/Kodi), есть экспериментальная сборка под Android TV (support.symfonium.app). Движок видео — (не проверено).
- **Yatse** — проприетарный пульт/клиент (Jellyfin, Kodi, Plex, Emby) с Chromecast; тот же автор, что Symfonium. Код закрыт — заимствовать нечего.
- **JellyWatch TV** — закрытый freemium Android TV-клиент для Emby и Jellyfin (jellywatch.app; тема на emby.media). Исходников нет.
- **Infuse** (Firecore) — закрытый; VidHub, Hills, AfuseKt, **Yamby** — закрытые (так помечены в awesome-jellyfin). **Kinoplex**, **JellyCompose/«Jellyfin Compose»**, **lxwiq/JellyFish (KMP)**, **VoidStream-FireTV** — репозитории не найдены (404 / нет в поиске).
- **Emby**: репозиторий `MediaBrowser/MediaBrowser.AndroidTv` (когда-то открытый, 2015) сейчас **404**; текущие Emby-клиенты закрыты. На форуме Emby: встроенный плеер — ExoPlayer, VLC предлагается как внешний.
- **Plex-клиенты на libVLC** — открытых не найдено; Plezy использует ExoPlayer/mpv.
- **awesome-jellyfin/CLIENTS.md** дополнительно перечисляет для Android: Coppelia, Ghosten-Player (AGPL, Dart, 3.3k★ — универсальный плеер, Jellyfin-поддержка не проверена), JellyBook, JellyBox, Linthra, Litefin (Tizen/webOS), Breezyfin (webOS), JellyRock (Roku), Kofin PVR (Kodi).

---

## 2.5 Плееры и библиотеки-референсы для интеграции libVLC

- **VLC for Android** — https://github.com/videolan/vlc-android, GPL-2.0+ (libVLC — LGPL-2.1; README: «Android-библиотеки делают приложение де-факто GPLv3»), Kotlin, 4.1k★, push 2026-10-07; Android TV входит (UI — Leanback, не проверено). Разработка в GitLab code.videolan.org (там же `libvlcjni`, `libvlc-android-samples`). Единственный полноценный пример продакшен-использования libVLC на TV: вывод через `VLCVideoLayout`, передача `MediaPlayer` между Activity и сервисом, passthrough SPDIF/HDMI, HDR→SDR tonemapping, 8K.
- **Артефакт** `org.videolan.android:libvlc-all` на Maven Central: ветка 3.x — **3.7.7** (3.7.0…3.7.7 за 2025–26), ветка 4.x — **4.0.0-eap29** (metadata lastUpdated 2026-10-01). Выбор версии: 3.7.x стабильна (lunarr/vlc-player и JellyPlay сидят на 3.7.5), 4.0 EAP даёт новый API треков/рендереров (как SwiftVLC), но пока pre-release.
- **lunarr-app/vlc-player** — https://github.com/lunarr-app/vlc-player, MIT, Kotlin+Swift, RN New Architecture; оборачивает **libvlc-all 3.7.5 / MobileVLCKit 3.7.3 / TVVLCKit 3.7.3**; API: `onTracks`, `selectSubtitleTrack(-1)`, `setSubtitleFile`, subtitle/audio delay, `VLCHardwareDecoder {Automatic, Disabled, DecodeOnly, Full}`, эквалайзер, snapshot, фоновое аудио; Android TV поддержан, minSdk 24. Компактный и свежий (push 2026-09-02) Kotlin-пример корректного управления libVLC — стоит прочитать нативную часть.
- **mpv-android** — https://github.com/mpv-android/mpv-android, MIT, Kotlin, 3.6k★; **libmpv-android (Findroid)** — MIT; **mpv-android-lib (AbdallahMehiz)** — используется JellyPlay; альтернативная ветка решений, если libVLC не вытянет ASS.
- **libass-android / ass-media** — https://github.com/peerless2012/libass-android, MIT, 89★: `lib_ass_media` — расширение media3 для рендера ASS через libass; используется jellyfin-androidtv, Fladder, AfuseKt, FongMi/TV. Для VLC-движка не нужен (VLC рисует ASS сам), но полезен как fallback-путь на ExoPlayer.
- **jellyfin media3-ffmpeg-decoder** (`org.jellyfin.media3:media3-ffmpeg-decoder 1.9.0+1`, репо jellyfin/jellyfin-androidx-media — не проверено напрямую) — готовые FFmpeg-аудиодекодеры для ExoPlayer-fallback.
- **Just (Video) Player** — https://github.com/moneytoo/Player, Unlicense, Java, 2.7k★, ExoPlayer; эталон минималистичных жестов/контролов.
- **Nova Video Player** — https://github.com/nova-video-player/aos-AVP, Apache-2.0, 4.7k★; движок — собственный `avos` на FFmpeg (+ dav1d), **не libVLC**; Jellyfin не поддерживает (SMB/UPnP, скрейпинг TMDb/Trakt). Интересен Leanback-UI для TV и torrent/UPnP, но как референс плеера для нас — слабо.

---

## 2.6 Каталоги клиентов и брендинг

- Официальный список: https://jellyfin.org/downloads/clients/all (Swiftfin помечен как «uses VLC»); критерии включения сторонних клиентов — https://jellyfin.org/docs/general/clients/ : Jellyfin как «first-class», настраиваемый URL сервера, **FSF-свободная лицензия**, соблюдение community standards и branding guidelines, ≥1 год активной разработки (или значимый интерес), **хотя бы одна уникальная фича в публичном релизе**; удаление — за неподдержку последней стабильной версии сервера, поломки, нарушение правил.
- **awesome-jellyfin** — https://github.com/awesome-jellyfin/awesome-jellyfin (CC0-1.0, 9.6k★, push 2026-10-02), файл `CLIENTS.md` — таблицы по платформам с флагами OSS/Free/Paid; раздел Android TV: Plezy, VidHub (closed), Void (beta), Wholphin, Yamby (closed), Moonfin.
- **Брендинг** — https://jellyfin.org/docs/general/contributing/branding (и /docs/project/branding): «Jellyfin» зарезервировано за проектом и официальными клиентами «Jellyfin for X»; сторонним — **не использовать имя напрямую, избегать паттернов «Jelly[word]» и «[word]fin»**, допустимо «Floostream, a Jellyfin client» в подзаголовке; **логотип (скруглённый треугольник в треугольнике) нельзя** использовать как свой, поворачивать или встраивать; можно взять градиент цветов при уникальной форме. Товарные знаки (имя и логотип) — Jellyfin, Inc. (Канада, США, ЕС, Китай). Ассеты — https://github.com/jellyfin/jellyfin-ux (CC-BY-SA-4.0, 59★, push 2026-10-08): логотипы PNG/SVG, иконки для Android mobile/TV, iOS, Roku, Tizen, web-favicons, плагинные thumbnails.

---

## 2.7 Сводка «что брать откуда»

| Задача | Источник |
|---|---|
| Абстракция плеера/очередь/репортинг сессии | `jellyfin-androidtv/playback/{core,jellyfin}` (PlayerBackend, QueueSupplier, PlaySessionService, TimedEventService) |
| Реализация стороннего движка как `media3.Player` | `findroid/player/local/.../mpv/MPVPlayer.kt` (BasePlayer) — повторить для libVLC |
| DeviceProfile для VLC (direct/transcode/subtitle/codec-условия, HDR-режимы, VC1-костыль, burn-in bitmap-субтитров) | `Swiftfin/Shared/Objects/VideoPlayerType/VideoPlayerType+VLC.swift`, `.../DeviceProfile.swift` |
| DeviceProfile для Android-железа (MediaCodec-зондирование, уровни AVC/HEVC, passthrough-детект, чёрные списки моделей) | `jellyfin-androidtv/app/.../util/profile/*`, `jellyfin-android/.../deviceprofile/DeviceProfileBuilder.kt` (+ `getExternalPlayerProfile()`) |
| DSL для сборки профиля | jellyfin-sdk-kotlin `buildDeviceProfile {}` (1.9.2) |
| Compose for TV: фокус, home-ряды, контролы под пульт | Wholphin (tv-foundation/tv-material, Navigation 3), Komorebi (`FocusTicketManager`), JellyCine (`tv/`) |
| Stub-модуль для тяжёлого нативного движка | Wholphin `wholphin-mpv-stub` |
| Опыт libVLC на Android (ограничения, ABI-сплиты) | JellyPlay `docs/player-engines.md`, Komorebi README, старый `VideoManager.java` (jellyfin-androidtv v0.16.11) — как антипример |
| Управление libVLC из Kotlin (треки, delay, HW-decoder режимы) | lunarr-app/vlc-player (Android-часть), videolan/vlc-android |
| Внешний плеер как fallback (интенты VLC/mpv/MX) | `jellyfin-androidtv/ui/playback/external/*` |
| Офлайн-загрузки | Findroid, JellyPlay (WorkManager + Range-resume), AFinity |
| Политика имени/иконки | jellyfin.org branding + jellyfin-ux |

**Практические предостережения для проекта на libVLC (из найденного):** держать libVLC в отдельном Gradle-модуле за интерфейсом и обязательно иметь ExoPlayer-fallback (все клиенты, вернувшиеся к VLC-вопросу, делали так); строго управлять жизненным циклом `LibVLC`/`MediaPlayer` (главная причина крашей в jellyfin-androidtv); планировать ABI-split сборки (libVLC ≈ +60–80 МБ в universal APK; Fire TV требует armeabi-v7a); для trickplay/медиасегментов/HDR-метаданных рассчитывать на собственную реализацию поверх позиционных событий VLC; для субтитров — VLC рендерит ASS/PGS сам, но внешние bitmap-субтитры придётся прожигать на сервере (как у Swiftfin); учесть отсутствие TLS 1.3 в libvlc 3.x (Swiftfin players.md).

### Ключевые источники
- https://github.com/jellyfin/jellyfin-androidtv/pull/3639 , https://github.com/jellyfin/jellyfin-androidtv/pull/2534 , https://github.com/jellyfin/jellyfin-androidtv/discussions/2546 , https://github.com/jellyfin/jellyfin-androidtv/issues/3937 , https://forum.jellyfin.org/t-solved-bring-back-libvlc , https://github.com/jellyfin/jellyfin-androidtv/releases/tag/v0.20.0-beta.1
- https://github.com/jellyfin/Swiftfin/blob/main/Documentation/players.md , https://github.com/harflabs/SwiftVLC , https://github.com/LePips/MPVUI (не проверено отдельно)
- https://github.com/raulshma/jellyplay/blob/main/docs/player-engines.md
- https://github.com/damontecres/Wholphin/blob/main/DEVELOPMENT.md
- https://repo1.maven.org/maven2/org/videolan/android/libvlc-all/maven-metadata.xml , https://repo1.maven.org/maven2/org/jellyfin/sdk/jellyfin-core/maven-metadata.xml
- https://jellyfin.org/docs/general/contributing/branding , https://jellyfin.org/docs/general/clients/ , https://github.com/awesome-jellyfin/awesome-jellyfin/blob/main/CLIENTS.md

---

# Часть 3. libVLC на Android как движок воспроизведения

Методика: все URL ниже проверены (HTTP 200 через curl/загрузку страницы или через GitLab/Maven API), если не помечено «(не проверено)». code.videolan.org закрыт анти-бот-прокси Anubis для «браузерных» запросов, поэтому содержимое читалось через GitLab API/raw. Исходники libvlcjni (ветки `master` и `libvlcjni-3.x`), VLC Android (`VLCOptions.kt`, `VideoPlayerActivity.kt`, `PlaybackService.kt`, `PlayerController.kt`), VLC 3.0.x (`modules/access/http/access.c`, `audiotrack.c`, `mediacodec.c`, `adaptive.cpp`, `gnutls.c`, `freetype.c`, `libvlc-module.c`) и AAR `libvlc-all-3.7.7` скачаны и просмотрены напрямую.

---

## 3.1 Официальные источники, артефакты, версии

### Репозитории
- [https://code.videolan.org/videolan/vlc-android](https://code.videolan.org/videolan/vlc-android) — официальное приложение VLC for Android / Android TV / ChromeOS (последняя активность 2026-10-10). Модули: `application/` (приложение), `buildsystem/`, `libvlc/` (gradle-модуль, VLC клонируется в `vlc/`), `medialibrary/`. С 2022 г. сам JNI-биндинг вынесен в отдельный репозиторий libvlcjni (см. ниже), а `vlc-android` берёт `libvlc-all` из Maven.
- [https://github.com/videolan/vlc-android](https://github.com/videolan/vlc-android) — GitHub-зеркало приложения (работает, `raw.githubusercontent.com` отдаёт файлы). **Зеркал `videolan/libvlcjni` и `videolan/libvlc-android-samples` на GitHub нет** (404) — только на code.videolan.org.
- [https://code.videolan.org/videolan/libvlcjni](https://code.videolan.org/videolan/libvlcjni) — «JNI wrapper for java interaction for libVLC, mostly for Android» (создан 2022-03-22, активность 2026-09-30). Отсюда публикуется `org.videolan.android:libvlc-all`. Ветки:
  - `master` — API **libVLC 4.0** (`libvlcVersion = '4.0.0-eap29'`, `minSdk = 21`, NDK `28.2.13676358`). Исходники Java лежат в `libvlc/src/main/java/org/videolan/libvlc/`.
  - `libvlcjni-3.x` — API **libVLC 3.x** (`libvlcVersion = '3.7.7'`, `minSdk = 17`). Старая раскладка: `libvlc/src/org/videolan/libvlc/`. Дерево ветки: [https://code.videolan.org/videolan/libvlcjni/-/tree/libvlcjni-3.x](https://code.videolan.org/videolan/libvlcjni/-/tree/libvlcjni-3.x).
  - Лицензия репозитория — LGPLv2 (`libvlc/COPYING.LIB`). README упоминает официальный Discord: `https://discord.gg/3h3K3JF`.
- [https://code.videolan.org/videolan/libvlc-android-samples](https://code.videolan.org/videolan/libvlc-android-samples) — официальные примеры (последняя активность 2025-03-18, ветки `master`, `4.0`, `vulkan`). Состав: `java_sample/` (JavaActivity.java + VLCVideoLayout), `native_sample/` (NDK/C через libvlc.h), `assets/bbb.m4v`, `buildlibvlc.sh`. **Kotlin-примера нет**, README нет. Лицензия примеров — BSD-подобная. В `java_sample/build.gradle`: `minSdkVersion 17`, `compileSdkVersion 33`.
- [https://code.videolan.org/videolan/medialibrary](https://code.videolan.org/videolan/medialibrary) — медиатека VLC (Maven `org.videolan.android:medialibrary-all`), для Jellyfin-клиента не нужна.

### Wiki / документация
- [https://wiki.videolan.org/LibVLC/](https://wiki.videolan.org/LibVLC/) — обзор LibVLC: LGPLv2, список биндингов, ссылка на Doxygen и на libvlc-android-samples; рекомендация использовать только LGPL-плагины, если приложение не GPL.
- [https://wiki.videolan.org/AndroidCompile/](https://wiki.videolan.org/AndroidCompile/) — сборка VLC/libVLC для Android (`buildsystem/compile.sh -l -a <ABI> -r` → AAR в `libvlc/build/outputs/aar/`). **Страница устарела** (NDK r21); актуальные инструкции — README libvlcjni.
- Страницы `LibVLC_Android` на wiki **не существуют** (404). Есть категория [https://wiki.videolan.org/Android/](https://wiki.videolan.org/Android/) и [https://wiki.videolan.org/Android_Player_Intents/](https://wiki.videolan.org/Android_Player_Intents/) (интенты для запуска внешнего VLC — пригодится для fallback «открыть в VLC»).
- [https://wiki.videolan.org/LibVLC_Tutorial/](https://wiki.videolan.org/LibVLC_Tutorial/) — базовый туториал по C-API (понятия libvlc_instance/media/media_player, те же, что в Java-обёртке).
- [https://wiki.videolan.org/VLC_command-line_help/](https://wiki.videolan.org/VLC_command-line_help/) — полный `vlc -H` (для 4.0.0-dev); удобно для поиска имён опций.
- [https://wiki.videolan.org/Documentation:Modules/](https://wiki.videolan.org/Documentation:Modules/) — описание модулей и их опций.
- Doxygen C-API: [https://videolan.videolan.me/vlc-3.0/](https://videolan.videolan.me/vlc-3.0/) (libVLC 3.0, см. [group__libvlc.html](https://videolan.videolan.me/vlc-3.0/group__libvlc.html)) и [https://videolan.videolan.me/vlc/](https://videolan.videolan.me/vlc/) (VLC 4 master). Старые адреса `videolan.org/developers/vlc/doc/doxygen/...` — 404. Java-классы libvlcjni — тонкие обёртки над `libvlc_media_player_*`, поэтому семантика берётся из Doxygen; Javadoc отдельно не публикуется — читать исходники.

### Maven Central: `org.videolan.android:libvlc-all`
- [https://central.sonatype.com/artifact/org.videolan.android/libvlc-all](https://central.sonatype.com/artifact/org.videolan.android/libvlc-all) — страница артефакта; [maven-metadata.xml](https://repo1.maven.org/maven2/org/videolan/android/libvlc-all/maven-metadata.xml) — полный список версий. В группе `org.videolan.android` три артефакта: `libvlc-all`, `medialibrary-all`, `remote-access`. **Отдельных per-ABI артефактов на Maven нет** — только «all» (4 ABI).
- **Последняя стабильная 3.x: `3.7.7`** (AAR опубликован 2026-10-01, 92.6 МБ). История 3.7.x в 2026: 3.7.0 (03), 3.7.1, 3.7.2, 3.7.3/3.7.4 (06), 3.7.5 (07), 3.7.6 (09), 3.7.7 (10). Ветка 3.6.x закончилась на 3.6.5 (2025-10-02).
- **Пре-релизы 4.0: `4.0.0-eap29`** (2026-07-08, 108.9 МБ) — последний. Maven помечает его как `latest`/`release`, но это EAP на нестабильном VLC 4.
- POM: `<name>LibVLC-Android</name>`, лицензия `GNU Lesser General Public License, version 2.1`. Транзитивные зависимости: `androidx.annotation:annotation`, `androidx.legacy:legacy-support-v4:1.0.0` (+ `kotlin-stdlib` у eap29).

```kotlin
// build.gradle.kts (модуль плеера)
dependencies {
    implementation("org.videolan.android:libvlc-all:3.7.7")      // стабильный libVLC 3.x API
    // implementation("org.videolan.android:libvlc-all:4.0.0-eap29") // другой (4.x) API, см. 3.7
}
```

### ABI, размер, minSdk
- Содержимое `libvlc-all-3.7.7.aar` (измерено): `classes.jar`, `R.txt`, assets (lua/hrtfs), `jni/<abi>/{libvlc.so, libvlcjni.so, libc++_shared.so}`. Несжатые размеры нативных библиотек: **arm64-v8a 55.3 МБ, armeabi-v7a 40.4 МБ, x86 50.8 МБ, x86_64 63.5 МБ**. При публикации через AAB Play раздаст один ABI (+~40–55 МБ на устройстве); при универсальном APK — ~210 МБ `.so` до сжатия. Обязательно использовать AAB или `splits { abi { enable = true } }`, а для Android TV — как минимум `armeabi-v7a` + `arm64-v8a` (многие ТВ-боксы 32-битные).
- minSdk: `libvlc-all` 3.x → **17**; 4.0 EAP → **21** (libvlcjni), а приложение VLC для VLC4 подняло minSdk до **23** в феврале 2026 ([коммит «Bump VLC 4 api min to 23»](https://mailman.videolan.org/pipermail/android/2026-February/023564.html)).
- Проверка совместимости CPU перед init: `VLCUtil.hasCompatibleCPU(context)` (так делает `VLCInstance.kt` в VLC Android).

---

## 3.2 Java/Kotlin API (libvlcjni)

Пути исходников (ветка 3.x: `libvlc/src/org/videolan/libvlc/…`; master/4.0: `libvlc/src/main/java/org/videolan/libvlc/…`):
- [MediaPlayer.java (3.x)](https://code.videolan.org/videolan/libvlcjni/-/blob/libvlcjni-3.x/libvlc/src/org/videolan/libvlc/MediaPlayer.java), `Media.java`, `LibVLC.java`, `MediaList.java`, `RendererDiscoverer.java`, `RendererItem.java`, `MediaDiscoverer.java`, `Dialog.java`, `AWindow.java`, `VideoHelper.java`; `interfaces/IVLCVout.java`, `interfaces/IMedia.java`, `interfaces/ILibVLC.java`; `util/VLCVideoLayout.java`, `util/DisplayManager.java`, `util/HWDecoderUtil.java` (3.x), `util/VLCUtil.java`, `util/MediaBrowser.java`, `util/AndroidUtil.java`. Пакет `media/` — обёртки, имитирующие `android.media.MediaPlayer`/`VideoView`; `stubs/` — заглушки для JVM-тестов.

### `LibVLC`
- `LibVLC(Context, List<String> options)` / `LibVLC(Context)`; `static version()`, `majorVersion()` (3 или 4 — удобно для условной логики опций), `setUserAgent(String name, String http)` (задаёт и HTTP User-Agent); `release()`.
- Опции экземпляра задаются как строки `--xxx` (глобально) — то же, что аргументы `vlc`. Опции медиа — `Media.addOption(":xxx")` (с двоеточием). Подтверждённые в исходниках VLC 3.0.x имена: `--network-caching=<ms>`, `--live-caching`, `--file-caching`, `--clock-jitter`, `--clock-synchro`, `--audio-time-stretch`/`--no-audio-time-stretch`, `--vout`, `--aout`, `--deinterlace={-1,0,1}` и `--deinterlace-mode=<yadif,…>`, `--sub-track=<n>`, `--sub-file=<path>`, `--input-slave=<mrl>`, `--sub-autodetect-file`, `--sub-language`/`--audio-language`, `--audio-desync=<ms>`, `--preferred-resolution`, `--input-fast-seek`, `--drop-late-frames`, `--skip-frames`, `--spdif`, `--stereo-mode`, `--audio-filter`, `--video-filter`, `--video-title-show`, `--verbose`/`-vvv`.
- **Внимание**: `--avcodec-hw` относится к модулю `avcodec` (VA-API/DXVA на десктопе). На Android аппаратное декодирование — отдельные модули `mediacodec_ndk`/`mediacodec_jni` (см. 3.4), включаются через `:codec=` на уровне медиа, а не через `--avcodec-hw`.

Что реально передаёт VLC for Android (из [VLCOptions.kt](https://github.com/videolan/vlc-android/blob/master/application/resources/src/main/java/org/videolan/resources/VLCOptions.kt)):
```kotlin
// фрагмент логики VLCOptions.libOptions (упрощённо, значения из настроек приложения)
val options = arrayListOf<String>()
options += if (timeStretching) "--audio-time-stretch" else "--no-audio-time-stretch"
options += "--avcodec-skiploopfilter"; options += "$deblocking"
options += "--avcodec-skip-frame"; options += if (frameSkip) "2" else "0"
options += "--avcodec-skip-idct"; options += if (frameSkip) "2" else "0"
options += "--subsdec-encoding"; options += subtitlesEncoding
options += "--stats"
if (networkCaching > 0) options += "--network-caching=$networkCaching"   // 0..60000 мс
options += "--audio-resampler"; options += "soxr"
options += "--audiotrack-session-id=$audiotrackSessionId"
if (isVLC4()) options += "--sub-text-scale=${1600 / freetypeRelFontsize.toFloat()}"
else          options += "--freetype-rel-fontsize=$freetypeRelFontsize"
if (freetypeBold) options += "--freetype-bold"
options += "--freetype-color=$freetypeColor"; options += "--freetype-opacity=$opacity"
options += "--freetype-background-opacity=0"
options += "--freetype-shadow-opacity=…"; options += "--freetype-outline-thickness=…"
when (opengl) { 1 -> options += "--vout=gles2,none"; 0 -> options += "--vout=android_display,none" }
options += "--keystore"; options += if (isMarshMallowOrLater) "file_crypt,none" else "file_plaintext,none"
options += "--keystore-file"; options += File(dir, "keystore").path
options += "--sout-chromecast-audio-passthrough"; options += "--sout-chromecast-conversion-quality=2"
options += "--sout-keep"
options += "--preferred-resolution=-1"
// на уровне Media (VLCOptions.setMediaOptions):
media.addOption(":no-mediacodec-dr"); media.addOption(":no-omxil-dr")  // режим «decoding acceleration» (без direct rendering)
media.addOption(":no-video"); media.addOption(":start-paused"); media.addOption(":sub-language=none")
```
Инициализация в приложении — `VLCInstance.kt`: `VLCUtil.hasCompatibleCPU(ctx)` → `LibVLC(ctx, VLCOptions.libOptions)`.

### `Media` / `IMedia`
- Конструкторы: `Media(ILibVLC, Uri)`, `Media(ILibVLC, String path)`, `Media(ILibVLC, AssetFileDescriptor)`, `Media(ILibVLC, FileDescriptor)`.
- `addOption(String ":opt")`, `setHWDecoderEnabled(boolean enabled, boolean force)`, `setDefaultMediaPlayerOptions()`, `parse()/parseAsync(flags, timeout)`, `getTracks()`, `getMeta(id)`, `getDuration()`, `subItems()`, `getStats()`, `setEventListener(IMedia.EventListener)`.
- События `IMedia.Event`: `MetaChanged=0`, `SubItemAdded=1`, `DurationChanged=2`, `ParsedChanged=3`, `SubItemTreeAdded=6` (**`Media.Event.Buffering` не существует** — буферизация приходит только через `MediaPlayer.Event.Buffering`).
- Слейвы: `IMedia.Slave(int type, int priority /*0..4*/, String uri)`, типы `IMedia.Slave.Type.Subtitle=0`, `Audio=1`; `media.addSlave(slave)`, `clearSlaves()`, `getSlaves()`.
- HW-декодирование в 3.x (`Media.setHWDecoderEnabled`): по `HWDecoderUtil.getDecoderFromDevice()` добавляет `:codec=mediacodec_ndk,all` (на API<21 — `mediacodec_jni`; при `force` ещё `iomx,`), иначе `:codec=all`; плюс, если не заданы, `:file-caching=1500` и `:network-caching=1500`. В 4.x метод добавляет только `:no-hw-dec` при `enabled=false` (HW включён по умолчанию).

### `MediaPlayer` (3.x; отличия 4.x — ниже)
- Создание: `MediaPlayer(ILibVLC)` / `MediaPlayer(IMedia)`; `setMedia(IMedia)`, `play()`, `pause()`, `stop()`, `isPlaying()`, `isSeekable()`, `getLength()`, `getTime()`/`setTime(long ms, boolean fast)`, `getPosition()`/`setPosition(float, boolean fast)`, `setRate(float)`/`getRate()`, `getVolume()/setVolume(int)`, `release()`.
- Треки (3.x): `getVideoTracks()/setVideoTrack(int id)`, `getAudioTracks()/getAudioTrack()/setAudioTrack(int id)`, `getSpuTracks()/getSpuTrack()/setSpuTrack(int id)` (возвращают `TrackDescription[] {id, name}`; `-1` = выключить), `setVideoTrackEnabled(boolean)` (для фонового аудио), `getAudioDelay()/setAudioDelay(long)`, `getSpuDelay()/setSpuDelay(long)` (задержки в микросекундах), `setEqualizer(MediaPlayer.Equalizer)`.
- Главы/тайтлы: `getTitles(): Title[]`, `getChapters(int title): Chapter[]`, `getTitle()/setTitle(int)`, `getChapter()/setChapter(int)`, `navigate(int)` (меню DVD/BD), `setVideoTitleDisplay(position, timeout)`.
- Видео: `attachViews(VLCVideoLayout, DisplayManager?, boolean subtitles, boolean textureView)`, `detachViews()`, `updateVideoSurfaces()`, `setVideoScale(ScaleType)` (SURFACE_BEST_FIT/FIT_SCREEN/FILL/16_9/4_3/ORIGINAL…), `setScale(float)`, `setAspectRatio(String "16:9")`, `getVLCVout(): IVLCVout`, `updateViewpoint(...)` (360°), `setRenderer(RendererItem)`.
- Аудио: `setAudioOutput("audiotrack"|"opensles"|…)`, `setAudioOutputDevice(String id)`, `setAudioDigitalOutputEnabled(boolean)`, `forceAudioDigitalEncodings(int[] AudioFormat.ENCODING_*)`, `canDoPassthrough()`.
- Слейвы на лету: `addSlave(int type, Uri uri, boolean select)` / `addSlave(type, String path, select)`.
- Запись: `record(String directory, boolean enable)`; телетекст: `setTeletext()`.
- События `MediaPlayer.Event` (константы): `MediaChanged=0x100, Opening=0x102, Buffering=0x103, Playing=0x104, Paused=0x105, Stopped=0x106, EndReached=0x109, EncounteredError=0x10a, TimeChanged=0x10b, PositionChanged=0x10c, SeekableChanged=0x10d, PausableChanged=0x10e, LengthChanged=0x111, Vout=0x112, ESAdded=0x114, ESDeleted=0x115, ESSelected=0x116, RecordChanged=0x11e`. Поля события: `getTimeChanged()`, `getLengthChanged()`, `getPositionChanged()`, `getVoutCount()`, `getEsChangedType()`/`getEsChangedID()`, `getPausable()`, `getSeekable()`, `getBuffering()` (проценты 0–100f). Слушатель: `setEventListener(MediaPlayer.EventListener)` (события доставляются в главный поток через Handler).
- **4.x (master) — изменения API треков**: вместо int-индексов — строковые id: `selectTrack(String id)`, `selectTracks(int type, String ids)`, `unselectTrackType(int type)`, `getSelectedVideoTrack()`, `getAllAudioTracks()/getAllSpuTracks()`, `setSpuTrack(String)`, `setVideoTrack(String)`, `setDisplayFit()`, `setUseOrientationFromBounds()`; `Media.setHWDecoderEnabled` сводится к `:no-hw-dec`.

### Отображение: `VLCVideoLayout`, `IVLCVout`, `DisplayManager`
- `VLCVideoLayout extends FrameLayout` — инфлейтит `R.layout.vlc_video_layout` (SurfaceView/TextureView для видео + отдельная поверхность субтитров). Javadoc: «Use it preferably with MediaPlayer.attachViews()».
- `attachViews(layout, dm, subtitles, textureView)`: `subtitles=true` создаёт отдельную subtitles-surface (нужна для рендера субтитров поверх видео при `android_display`), `textureView=true` — TextureView вместо SurfaceView (TextureView нужен для анимаций/трансформаций/прозрачности, но дороже и без secure/HDR-путей; VLC Android по умолчанию SurfaceView: `mediaPlayer.attachViews(it, displayManager, true, false)`).
- Низкоуровневый `IVLCVout`: `setVideoView(SurfaceView|TextureView)`, `setVideoSurface(Surface, SurfaceHolder)`/`(SurfaceTexture)`, `setSubtitlesView(...)`, `setSubtitlesSurface(...)`, `attachViews(OnNewVideoLayoutListener)`, `detachViews()`, `areViewsAttached()`, `setWindowSize(w, h)` (сообщить libVLC размер окна — важно при ресайзе/повороте/PiP), `addCallback(Callback{onSurfacesCreated/onSurfacesDestroyed})`. `OnNewVideoLayoutListener.onNewVideoLayout(vout, width, height, visibleWidth, visibleHeight, sarNum, sarDen)` даёт реальные размеры кадра для ручного расчёта letterbox. Старый Jellyfin ATV использовал именно `mVlcPlayer.getVLCVout().attachViews(this)` с собственным SurfaceView.
- Поворот/ресайз: при использовании `attachViews(VLCVideoLayout…)` достаточно вызвать `mediaPlayer.updateVideoSurfaces()` в `onConfigurationChanged`/при смене layout (так делает `VideoPlayerActivity.kt`).
- `DisplayManager(Activity, LiveData<RendererItem>?, boolean textureView, boolean cloneMode, boolean benchmark)` — переключение между основным дисплеем, `Presentation` на вторичном дисплее и удалённым рендерером. Для простого клиента можно передавать `null`.
- Picture-in-Picture — обычный Android API; VLC Android берёт размеры выбранной видеодорожки и вызывает `enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(w,h)).build())`, а при изменении размера — `updateVideoSurfaces()`.
- Фоновое аудио: VLC Android держит `MediaPlayer` в `PlaybackService : MediaBrowserServiceCompat` с `MediaSessionCompat` и при уходе видео в фон вызывает `setVideoTrackEnabled(false)` / `detachViews()`, при возврате — наоборот. Для нового приложения: foreground service `mediaPlayback` + media3 `MediaSession` поверх собственного `Player`-адаптера или `MediaSessionCompat`.

### Chromecast через libVLC: `RendererDiscoverer`
- `RendererDiscoverer.list(ILibVLC): Description[]` (имена модулей обнаружения, напр. `microdns_renderer`), `RendererDiscoverer(libvlc, name)`, `start()/stop()`, события `ItemAdded=0x502`/`ItemDeleted=0x503` → `event.getItem(): RendererItem` (`name`, `type`, `iconUrl`, `flags`; нужно `retain()/release()`), затем `mediaPlayer.setRenderer(item)` — libVLC сам транскодирует/стримит на Chromecast (`sout-chromecast-*` опции выше). Это реализация VLC Android («Cast to…»), не Google Cast SDK, т. е. плеер на телефоне продолжает работать как источник; для Jellyfin обычно лучше нативный Cast receiver, но вариант есть.
- `MediaDiscoverer(libvlc, name)` — обнаружение SMB/UPnP, для Jellyfin не нужен.

### Диалоги (TLS/логин)
- `Dialog.setCallbacks(ILibVLC, Dialog.Callbacks)` с `onDisplay(ErrorMessage|LoginDialog|QuestionDialog|ProgressDialog)`, `onCanceled`, `onProgressUpdate`; `LoginDialog.postLogin(user, pass, store)`, `QuestionDialog.postAction(int)`. Через `QuestionDialog` libVLC задаёт вопрос «Insecure site / accept certificate» для недоверенных TLS-сертификатов (см. 3.4).

### Авторитетный пример (official `java_sample/JavaActivity.java`, переведён на Kotlin)
```kotlin
class PlayerActivity : AppCompatActivity() {
    private val useTextureView = false
    private val enableSubtitles = true
    private lateinit var libVLC: LibVLC
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var videoLayout: VLCVideoLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        libVLC = LibVLC(this, arrayListOf("-vvv", "--network-caching=1500"))
        mediaPlayer = MediaPlayer(libVLC)
        videoLayout = findViewById(R.id.video_layout)
    }
    override fun onStart() {
        super.onStart()
        mediaPlayer.attachViews(videoLayout, null, enableSubtitles, useTextureView)
        val media = Media(libVLC, Uri.parse("https://jellyfin.example/Videos/$itemId/stream?static=true&ApiKey=$token"))
        media.setHWDecoderEnabled(true, false)   // 3.x: :codec=mediacodec_ndk,all
        media.addSlave(IMedia.Slave(IMedia.Slave.Type.Subtitle, 4,
            "https://jellyfin.example/Videos/$itemId/$sourceId/Subtitles/$index/0/Stream.srt?ApiKey=$token"))
        mediaPlayer.media = media; media.release()
        mediaPlayer.setEventListener { ev ->
            when (ev.type) {
                MediaPlayer.Event.Buffering -> showBuffer(ev.buffering)      // 0..100f
                MediaPlayer.Event.TimeChanged -> onTime(ev.timeChanged)
                MediaPlayer.Event.LengthChanged -> onLength(ev.lengthChanged)
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESSelected -> refreshTracks()  // getSpuTracks()/getAudioTracks()
                MediaPlayer.Event.EndReached, MediaPlayer.Event.EncounteredError -> finishPlayback()
            }
        }
        mediaPlayer.play()
    }
    override fun onStop() { super.onStop(); mediaPlayer.stop(); mediaPlayer.detachViews() }
    override fun onDestroy() { super.onDestroy(); mediaPlayer.release(); libVLC.release() }
}
```
Compose: официальных примеров `AndroidView { VLCVideoLayout }` не найдено. Паттерн тривиален: `AndroidView(factory = { VLCVideoLayout(it) }, modifier = Modifier.fillMaxSize(), onRelease = { player.detachViews() })` и `attachViews` в `factory`/`LaunchedEffect` — рекомендация, не проверенный официальный код.

### HTTP-заголовки / авторизация Jellyfin
- В модуле HTTP VLC 3.0.x (`modules/access/http/access.c`) есть только: `http-user-agent`, `http-referrer`, `http-forward-cookies` (default true), `http-continuous`; в старом `modules/access/http.c` — `http-reconnect`. **Произвольных заголовков (Authorization, X-Emby-Token) нет**: MR [«http: add support for custom HTTP headers» !7495](https://code.videolan.org/videolan/vlc/-/merge_requests/7495) — закрыт, не смержен; форумные ответы — «Not possible» ([пример](https://forum.videolan.org/viewtopic.php?p=498927)).
- Поэтому для Jellyfin токен передаётся **query-параметром**: `ApiKey=<token>` — константа `ApiClient.QUERY_ACCESS_TOKEN = "ApiKey"` в jellyfin-sdk-kotlin (проверено в исходнике `ApiClient.kt`). Сервер также принимает устаревший `api_key` (не проверено). User-Agent можно задать `LibVLC.setUserAgent(appName, httpUa)` или `:http-user-agent=…`.

---

## 3.3 Субтитры

- Внешние SRT/ASS/VTT: `Media.addSlave(IMedia.Slave(Type.Subtitle, priority, uri))` до `play()` или `MediaPlayer.addSlave(Type.Subtitle, uri, select=true)` во время воспроизведения; URL по HTTP(S) поддерживаются. VLC Android: `PlayerController.addSubtitleTrack(uri, select)`.
- Встроенные дорожки: `getSpuTracks()`/`setSpuTrack(id)` (3.x) / `selectTrack(id)` (4.x); `-1`/`unselectTrackType(Text)` — выключить. Автозагрузка одноимённых файлов отключается `:sub-language=none`.
- Задержка: `setSpuDelay(µs)`/`getSpuDelay()`; аудио — `setAudioDelay(µs)`.
- Стиль текстовых субтитров — опции модуля `freetype`: `--freetype-rel-fontsize` (VLC 3; меньше = крупнее), `--sub-text-scale` (VLC 4; VLC Android переводит как `1600/relfontsize`), `--freetype-bold`, `--freetype-color`, `--freetype-opacity`, `--freetype-background-color/-opacity`, `--freetype-shadow-color/-opacity`, `--freetype-outline-thickness/-color/-opacity`, `--freetype-fontsize`, `--freetype-font`. Кодировка: `--subsdec-encoding`. Это опции экземпляра LibVLC — чтобы менять размер «на лету», нужно пересоздать LibVLC (VLC Android так и делает: `VLCInstance.restart()`).
- ASS/SSA: рендерится libass внутри libVLC с полной поддержкой стилей/позиционирования/karaoke — главное преимущество перед ExoPlayer, у которого SSA/ASS только «базовая» ([форум Jellyfin](https://forum.jellyfin.org/t-solved-subtitle-limit-android-tv?pid=47233)).
- Битмап-субтитры PGS/VobSub/DVB: libVLC декодирует и накладывает сам, независимо от контейнера и способа доставки — в т. ч. в прямом воспроизведении без burn-in на сервере. У media3 PGS/DVB парсеры есть (в контейнере MKV/TS), VobSub внешний не поддерживается ([supported-formats](https://developer.android.com/media/media3/exoplayer/supported-formats)).
- Известные проблемы HLS + субтитры: внешние субтитры не показываются при HLS-воспроизведении — [форум, 2024](https://forum.videolan.org/viewtopic.php?p=546788); WebVTT в HLS ограничено ([форум](https://forum.videolan.org/viewtopic.php?p=521307)); встроенный WebVTT — вторая строка не отображается, 3.6.5 ([vlc#29230](https://code.videolan.org/videolan/vlc/-/issues/29230)); «No embedded subtitles when playing HDR video» — открыт ([vlc-android#2347](https://code.videolan.org/videolan/vlc-android/-/work_items/2347)). Практический вывод: при транскодировании в HLS просить сервер отдавать субтитры отдельным файлом или burn-in, при direct play — слейвы и встроенные треки работают хорошо.

---

## 3.4 Стриминг

### HTTP(S), TLS, самоподписанные сертификаты
- TLS в libVLC Android делает **GnuTLS** (`modules/misc/gnutls.c`), а не Android-стек. Опции: `--gnutls-system-trust` (default true), `--gnutls-dir-trust=<dir>`, `--gnutls-priorities`. Системное хранилище Android читается, но «User»-сертификаты на новых версиях Android не подхватываются — открытый баг [vlc-android#3005](https://code.videolan.org/videolan/vlc-android/-/work_items/3005).
- Отключить проверку опцией нельзя; на недоверенный сертификат libVLC поднимает `Dialog.QuestionDialog` («Insecure site»), который приложение должно показать и ответить `postAction()` ([vlc-android#2673](https://code.videolan.org/videolan/vlc-android/-/work_items/2673), открыт). История: [#665](https://code.videolan.org/videolan/vlc-android/-/work_items/665), [#1642](https://code.videolan.org/videolan/vlc-android/-/work_items/1642), [#2193](https://code.videolan.org/videolan/vlc-android/-/work_items/2193), форум [«HTTP/TLS on libVLC Android»](https://forum.videolan.org/viewtopic.php?p=447307). Для Jellyfin-клиента: отдельно валидировать сертификат в OkHttp (API) и обязательно реализовать `Dialog.Callbacks` для libVLC.
- Переподключение: `--http-reconnect` (старый модуль), `--http-continuous`; куки: `--http-forward-cookies` (включены по умолчанию).

### HLS и seeking
- HLS/DASH — модуль `adaptive`: опции `--adaptive-logic={predictive,nearoptimal,rate,fixedrate,lowest,highest}`, `--adaptive-maxwidth/-maxheight`, `--adaptive-bw`, `--adaptive-livedelay`, `--adaptive-maxbuffer`, `--adaptive-lowlatency`, `--adaptive-use-access`.
- Direct play по статическому HTTP с Range — seek через Range работает штатно (`setTime/setPosition`, `fast=true` = key-frame seek, `--input-fast-seek`). Для HLS от транскодера Jellyfin плейлист «растёт» по мере транскодирования: `SeekableChanged` может прийти `false`, а перемотка внутри сегмента может откатываться к началу сегмента ([vlc#26564](https://code.videolan.org/videolan/vlc/-/issues/26564)); жалобы на Android — [форум 2015](https://forum.videolan.org/viewtopic.php?p=429161), [2019](https://forum.videolan.org/viewtopic.php?p=492115), [LibVLCSharp PR 361](https://github.com/videolan/libvlcsharp/pull/361). Стандартная тактика Jellyfin-клиентов: при seek в транскодируемом потоке перезапрашивать `PlaybackInfo`/новый HLS-URL с `StartTimeTicks`, а не полагаться на внутрипоточный seek.
- Буферизация: `--network-caching=<ms>` (VLC Android: 0–60000, libVLC 3.x при HW-декодировании выставляет 1500), `--live-caching`, `--file-caching`, `--clock-jitter`, `--clock-synchro`. Прогресс — `MediaPlayer.Event.Buffering` + `event.getBuffering()` (0–100f).

### Аппаратное декодирование (MediaCodec), тоннелирование
- Модули `mediacodec_ndk` (NDK AMediaCodec, API≥21) и `mediacodec_jni` (через JNI, старые устройства) — `modules/codec/omxil/mediacodec.c`; оба имеют `set_capability("video decoder", 0)` — «Only enabled via commandline arguments», т. е. включаются через `:codec=mediacodec_ndk,…` (это и делает `Media.setHWDecoderEnabled` в 3.x). Опции: `--mediacodec-dr` (direct rendering в Surface, default true), `--mediacodec-audio` (default false), `--mediacodec-tunneled-playback` (default false). Режимы VLC Android: Disabled → `:codec=all`; «Decoding acceleration» → HW-декодер + `:no-mediacodec-dr :no-omxil-dr` (кадры копируются и выводятся через `gles2`/`android_display`, можно накладывать фильтры/субтитры в самом vout); «Full» → direct rendering (минимальная нагрузка, zero-copy). В 4.x: HW включён по умолчанию, выключается `:no-hw-dec`.
- Vout: `--vout=android_display,none` (прямой вывод в Surface) или `--vout=gles2,none` (OpenGL ES 2: нужен для 360°, фильтров, тонмаппинга); `--android-display-chroma`.
- Аудиовыход: по умолчанию в текущем vlc-android — `aaudio`, альтернативы `audiotrack`, `opensles` (`setAudioOutput()`).

### Passthrough (Dolby/DTS/TrueHD) на Android TV
- README libvlcjni/vlc-android: «Audio passthrough with SPDIF and HDMI, including for Audio HD codecs, like DD+, TrueHD or DTS-HD». Реализация — aout `audiotrack`: проверяет `AudioFormat.ENCODING_AC3/E_AC3/DOLBY_TRUEHD/DTS/DTS_HD`, устройство `encoded`. Javadoc `MediaPlayer.setAudioOutput`: «Starting Android 21, passthrough is enabled for encodings supported by the device/audio system».
- API: `setAudioDigitalOutputEnabled(true)` («Works only with AudioTrack AudioOutput»), `forceAudioDigitalEncodings(intArrayOf(AudioFormat.ENCODING_E_AC3, …))`, `setAudioOutputDevice("hdmi"|"encoded:…")`, `canDoPassthrough()`; VLC Android применяет `setAudioDigitalOutputEnabled(...)` и `setAudioOutput(getAout())` при каждом новом медиа. Старый Jellyfin ATV делал: `setAudioOutput("opensles_android")` + `setAudioOutputDevice("hdmi")` либо `setAudioDigitalOutputEnabled(true)`.
- Известные проблемы: TrueHD/DTS не проходят на ряде устройств (Fire TV Cube — [vlc#27670](https://code.videolan.org/videolan/vlc/-/issues/27670); Sony Android TV — [форум](https://forum.videolan.org/viewtopic.php?p=471323); Homatics TrueHD — [форум](https://forum.videolan.org/viewtopic.php?p=532343)); запрос «passthrough per codec» — [vlc#18112](https://code.videolan.org/videolan/vlc/-/issues/18112); DTS:X как DTS-HD на Shield — [vlc#26978](https://code.videolan.org/videolan/vlc/-/issues/26978). Выбор per-codec через `forceAudioDigitalEncodings` уже позволяет сделать UI вида «E-AC3: passthrough, DTS: decode».

### HDR / Dolby Vision
- Заявлено: «Support for HDR, including tonemapping for SDR streams». Реально через `mediacodec` + direct rendering HDR10 выводится устройством; через `gles2` путь — тонмаппинг в SDR. Баги: [vlc-android#1605](https://code.videolan.org/videolan/vlc-android/-/work_items/1605) (закрыт); «washed-out colors when playing HDR» — [#2999](https://code.videolan.org/videolan/vlc-android/-/work_items/2999) (открыт); «Grey bars on HDR content on Bravia» — [#943](https://code.videolan.org/videolan/vlc-android/-/work_items/943); «wrong color playing 10bit DV on 8bit display» — [#3069](https://code.videolan.org/videolan/vlc-android/-/work_items/3069) (открыт); 4K AV1 freeze на Fire TV Cube — [#3358](https://code.videolan.org/videolan/vlc-android/-/work_items/3358) (2026, открыт). Dolby Vision: отдельного DV-пути (RPU/профили 5/7/8, `video/dolby-vision` MIME) в libVLC нет — DV-файлы декодируются как HEVC и либо падают в HDR10-fallback (профили 7/8.1), либо дают зелёно-фиолетовую картинку (профиль 5). Документация Android: гарантированно HDR только у tunneled-декодеров ([source.android.com/docs/core/display/hdr](https://source.android.com/docs/core/display/hdr)). Для DV-библиотек ExoPlayer с нативным `video/dolby-vision` декодером пока лучше.

### Частота обновления (не задача VLC)
- libVLC не переключает режим дисплея. Делать самим по [Android «Frame rate» guide](https://developer.android.com/media/optimize/performance/frame-rate): `Surface.setFrameRate(fps, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, CHANGE_FRAME_RATE_ALWAYS)` (API 30+) или `WindowManager.LayoutParams.preferredDisplayModeId` по `Display.getSupportedModes()` — последнее рекомендовано именно для Android TV, с проверкой `DisplayManager.getMatchContentFrameRateUserPreference()` и ожиданием `onDisplayChanged` ~2 с перед стартом. FPS дорожки берётся из Jellyfin `MediaStream.RealFrameRate` или `Media.getTracks()` (`VideoTrack.frameRateNum/Den`).

---

## 3.5 Примеры и референсные реализации

- [libvlc-android-samples](https://code.videolan.org/videolan/libvlc-android-samples) — `java_sample/src/main/java/org/videolan/javasample/JavaActivity.java` (81 строка), `native_sample/jni/main.c` — libvlc C API. Kotlin/Compose-примера нет.
- VLC for Android как референс:
  - [VideoPlayerActivity.kt](https://github.com/videolan/vlc-android/blob/master/application/vlc-android/src/org/videolan/vlc/gui/video/VideoPlayerActivity.kt) (~2700 строк) — attach/detach, PiP, DisplayManager, задержки аудио/субтитров, ресайз, Bluetooth-задержка.
  - [PlaybackService.kt](https://github.com/videolan/vlc-android/blob/master/application/vlc-android/src/org/videolan/vlc/PlaybackService.kt) — `MediaBrowserServiceCompat` + `MediaSessionCompat`, `MediaPlayer.EventListener`, рендереры, эквалайзер.
  - `application/vlc-android/src/org/videolan/vlc/media/PlayerController.kt` — тонкая обёртка над `MediaPlayer` (треки, passthrough, слейвы, rate, события) — лучший шаблон «Player-адаптера».
  - [VLCOptions.kt](https://github.com/videolan/vlc-android/blob/master/application/resources/src/main/java/org/videolan/resources/VLCOptions.kt), `VLCInstance.kt` — все опции и инициализация.
- Старый Jellyfin Android TV с libVLC: последняя версия с libVLC — **v0.16.11**, класс [`VideoManager.java`](https://github.com/jellyfin/jellyfin-androidtv/blob/v0.16.11/app/src/main/java/org/jellyfin/androidtv/ui/playback/VideoManager.java) (1093 строки; одновременно ExoPlayer и libVLC). Опции: `--network-caching=<buffer>`, `--audio-time-stretch`, `--avcodec-skiploopfilter 1`, `--avcodec-skip-frame 0`, `--avcodec-skip-idct 0`, `--android-display-chroma RV32`, `--audio-resampler soxr`, `-vv`. Удалено в 0.17 — [PR #3639](https://github.com/jellyfin/jellyfin-androidtv/pull/3639). Жалобы после удаления (MPEG-2 Live TV, ASS, HDR/DV) — [форум Jellyfin](https://forum.jellyfin.org/printthread.php?tid=7841), [t-default-player-option-gone](https://forum.jellyfin.org/t-default-player-option-gone). Важно: сейчас libvlc-all обновляется ежемесячно (3.7.x), так что аргумент «не обновляется 2 года» устарел.
- Другие OSS: [Nova Video Player](https://github.com/nova-video-player/aos-AVP) — Apache-2.0, **не libVLC** (собственный `avos` на ffmpeg); [mpv-android](https://github.com/mpv-android/mpv-android) — MIT, «not a library/module (AAR)»; [LibVLCSharp issue 425](https://code.videolan.org/videolan/LibVLCSharp/-/issues/425) — обсуждение `MediaPlayerElement` для Android.
- Статьи/блоги по libVLC + Kotlin/Compose: качественных англоязычных не найдено; лучшие «документы» — исходники выше.

---

## 3.6 Лицензирование

- libVLC/libvlccore — **LGPL-2.1+** с 21.12.2011 ([пресс-релиз](https://www.videolan.org/press/lgpl-libvlc.html)); приложение VLC остаётся GPLv2+. Общая страница [videolan.org/legal.html](https://www.videolan.org/legal.html): торговые марки VLC нельзя использовать для продвижения не-open-source ПО; патенты — позиция, что во Франции/ЕС ПО не патентуется.
- `libvlcjni` — LGPLv2; POM `libvlc-all` — LGPL-2.1. Приложение VLC for Android — **GPLv2+**, «Android libraries make this, de facto, a GPLv3 application» (README).
- **Ключевой нюанс — contribs внутри `libvlc.so`.** `buildsystem/build-libvlc.sh` (libvlcjni) имеет флаг `--license {g|l|a}`: `g` — «GPLv3 (default)» (contribs с GPL-компонентами + `--enable-live555 --enable-dvdread`), `l` — «LGPLv3 + ad-clauses», `a` — «LGPLv2 + ad-clauses». Какой флаг использует CI при публикации в Maven — проверить не удалось **(не проверено)**; по умолчанию скрипта — GPL-сборка, т. е. **разумно считать бинарный `libvlc-all` де-факто GPLv3-совместимым артефактом, а не чистым LGPL**. Для нашего GPL-приложения это не проблема; проблемой было бы для проприетарного/MPL-клиента.
- Следствия для GPL-клиента: GPL-3.0 совместим с LGPL-2.1+ и GPLv3-contribs; **GPL-2.0-only** несовместим с GPLv3-компонентами (gnutls ≥3, dvdread/live555) → выбирать **GPL-2.0-or-later** или GPL-3.0 (так же поступает VLC). Нужно включить тексты LGPL-2.1/GPL и уведомления контрибов (ffmpeg, libass, dav1d, gnutls и т. д.) в экран «О программе»; ссылку на исходники libvlcjni/vlc.
- Патенты/кодеки: VideoLAN не покупает патентные лицензии; для open-source распространения через Play это общепринятая практика (VLC сам в Play: [download-android.html](https://www.videolan.org/vlc/download-android.html)). Риск (как и у любого ffmpeg-плеера) несёт издатель.
- Имя «VLC» и логотип — торговые марки VideoLAN, в названии/бренде приложения не использовать.

---

## 3.7 Сравнение и известные проблемы; статус libVLC 4

| Критерий | libVLC 3.7.x | media3 / ExoPlayer | libmpv (mpv-android) |
|---|---|---|---|
| Кодеки | всё через ffmpeg + MediaCodec; MPEG-2/VC-1/AC-3/DTS/TrueHD софтом | только MediaCodec (+ ffmpeg-расширение надо собирать самим) | ffmpeg + MediaCodec |
| Субтитры | libass (полный ASS), PGS/VobSub/DVB/CC, внешние слейвы | SRT/VTT/TTML/SSA (базовый стиль), PGS/DVB парсеры, нет VobSub | libass, всё что ffmpeg |
| HDR/DV | HDR10 через mediacodec DR; DV — без RPU, ряд открытых багов | лучший DV/HDR путь (`video/dolby-vision`, tunneling) | HDR есть, DV ограниченно |
| Passthrough | есть (AudioTrack encoded, TrueHD/DTS-HD), устройство-зависимо | есть (`DefaultAudioSink`) | есть |
| HLS seek/субтитры | слабое место (см. 3.3–3.4) | сильная сторона | среднее |
| DRM | нет (не нужно) | Widevine | нет |
| Размер | +40–63 МБ `.so` на ABI | ~5–10 МБ | ~20–30 МБ, нет готового AAR |
| Интеграция | готовый AAR, Java API, активно обновляется | Kotlin-first, Compose-дружелюбен | нужна своя сборка JNI |

- Практический вывод для Jellyfin-клиента: libVLC как **основной движок для direct play** (ASS/PGS, MPEG-2, DTS/TrueHD passthrough, любые контейнеры) и ExoPlayer как альтернатива/fallback для HLS-транскодов и DV — именно та схема, от которой Jellyfin ATV отказался из-за стоимости поддержки двух бэкендов; закладывать абстракцию `Player` с самого начала.
- Трекеры: [vlc-android issues](https://code.videolan.org/videolan/vlc-android/-/issues), `https://code.videolan.org/videolan/libvlcjni/-/issues` (биндинг), `https://code.videolan.org/videolan/vlc/-/issues` (ядро). Релевантные открытые: #3005 (TLS user certs), #2673 (accept cert), #2999/#943/#3069 (HDR/DV), #2347 (субтитры при HDR), #3358 (AV1 4K Fire TV), #2589 (Chromecast options).
- **libVLC 4.0 статус (октябрь 2026):** стабильного релиза VLC 4.0 нет — на [videolan.org/news.html](https://www.videolan.org/news.html) за 2025–2026 только VLC 3.0.23 (2026-01-08), 3.0.24 (2026-09-22), VLC for Android 3.6.0 (2025-01-13) и 3.7.0 (2026-02-25). Android-артефакты 4.x: `libvlc-all:4.0.0-eap29` (2026-07-08); vlc-android ведёт параллельную линию `forceVlc4` с тегами `3.80.0-alpha01…alpha06` (не проверено). Изменения 4.x API для Android: строковые id треков (`selectTrack`), `:no-hw-dec`, `--sub-text-scale` вместо `--freetype-rel-fontsize`, minSdk 21→23, новый clock/aout. Рекомендация: стартовать на 3.7.x, изолировав работу с треками/опциями за адаптером, чтобы переход на 4.0 был локальным.

---

## 3.8 Каналы коммуникации

- Форум: [https://forum.videolan.org/viewforum.php?f=35](https://forum.videolan.org/viewforum.php?f=35) — раздел для VLC for Android (название «VLC for Android and Chrome OS» — не проверено). Общий форум: [forum.videolan.org](https://forum.videolan.org/).
- Списки рассылки: [android@videolan.org — «Development of VLC for Android»](https://mailman.videolan.org/listinfo/android) (туда же идут коммит-уведомления vlc-android/libvlcjni — удобно следить за bump'ами версий), [vlc-devel](https://mailman.videolan.org/listinfo/vlc-devel) (ядро/libVLC).
- IRC: `#videolan` на Libera.Chat, веб-клиент [videolan.org/webirc](https://www.videolan.org/webirc/); страница поддержки [videolan.org/support](https://www.videolan.org/support/). Discord VideoLAN: `https://discord.gg/3h3K3JF`.
- Баг-репорты и MR — только на code.videolan.org; GitHub-зеркало vlc-android PR не принимает.

---

### Краткая сводка для принятия решения
1. Артефакт: `org.videolan.android:libvlc-all:3.7.7` (LGPL-2.1 POM, де-факто GPLv3-сборка контрибов — для GPL-приложения ок), minSdk 17, ~40–63 МБ `.so` на ABI → только AAB/ABI-splits.
2. API стабильно и покрывает всё нужное: треки, слейвы-субтитры, задержки, главы, passthrough (`setAudioDigitalOutputEnabled`/`forceAudioDigitalEncodings`), Chromecast-рендеринг, события буферизации.
3. Ограничения, которые надо закрывать на стороне приложения: нет произвольных HTTP-заголовков (токен в `ApiKey=` query), TLS через GnuTLS (нужен обработчик `Dialog`), HLS-seek/субтитры в HLS слабые (перезапрос транскода с `StartTimeTicks`), Dolby Vision без RPU, refresh-rate switching — своими руками через `Display.Mode`/`setFrameRate`.
4. 4.0 — только EAP; проектировать адаптер с учётом смены API треков.

---

# Часть 4. Android-платформа

_Исходный заголовок: Android-платформа для нативного Kotlin-клиента Jellyfin (phone + Android TV, libVLC) — отчёт по исследованию_

Дата: 2026-10-11. Все URL ниже открывались и проверялись (кроме помеченных «(не проверено)»). Версии библиотек взяты из официальных release-страниц, `maven-metadata.xml` на Maven Central / Google Maven и из актуальных version-catalog'ов Findroid и jellyfin-androidtv (оба обновляются Renovate'ом и на момент проверки сидят на самых свежих версиях).

---

## 0. Главные выводы (TL;DR)

1. **Leanback официально deprecated** («This artifact and its classes are deprecated. Use Compose for TV instead» на странице релизов). Для нового проекта в 2026 — только **Compose for TV** (`androidx.tv:tv-material:1.1.0`, stable с 2026-05-06). TV-специфичные lazy-списки (`TvLazyColumn/Row/Grid`) удалены; используются обычные `LazyColumn/LazyRow` из `androidx.compose.foundation` ≥1.7 + `BringIntoViewSpec` вместо `pivotOffsets`. `ImmersiveList` удалён (есть сэмпл), `Carousel`, `TabRow`, `NavigationDrawer`, `ListItem`, `Card*`, `Button*`, `WideButton` — stable.
2. **libVLC + media3 `MediaSession` реально:** jellyfin-androidtv уже оборачивает свой backend-независимый `PlaybackManager` в `SimpleBasePlayer` (`playback/media3/session/.../MediaSessionPlayer.kt`: `getState()`, `handleSetPlayWhenReady`, `handlePrepare`, `handleStop`, `handleSeek`, `handleSetPlaybackParameters`, `handleSetRepeatMode`, `handleSetDeviceVolume`, `handleRelease`…) и отдаёт его в `MediaSession.Builder(context, player)`. Findroid оборачивает libmpv в `BasePlayer` (`MPVPlayer : BasePlayer(), MPVLib.EventObserver, AudioManager.OnAudioFocusChangeListener`). Это готовые шаблоны для «libVLC → media3 Player → MediaSession/Now Playing/PiP/медиакнопки».
3. **Один код на phone+TV — модель Findroid**: `:app:phone` (Material3 + material3-adaptive) и `:app:tv` (tv-material) поверх общих `:core`, `:data`, `:player:core`, `:player:local`, `:setup`, `:settings`, `:modes:film`; Hilt, Navigation 3, Room 3, Paging 3, WorkManager, Coil 3. jellyfin-androidtv — Koin + гибрид Leanback/Compose + Navigation3-ui.
4. **F-Droid принимает prebuilt `org.videolan.android:libvlc-all` с Maven Central**: в актуальном рецепте VLC 3.7.1 F-Droid больше не собирает libvlc через `compile.sh`, а вырезает модули `:libvlcjni`/`:medialibrary` из `settings.gradle`, т.е. берёт AAR из Maven Central (Maven Central — в allowlist политики включения). Findroid аналогично тянет prebuilt `dev.jdtech.mpv:libmpv` и при этом публикуется в F-Droid с reproducible builds (`Binaries:` + `AllowedAPKSigningKeys`).
5. **Актуальный стек (октябрь 2026):** Kotlin 2.4.21, AGP 9.4.x (Gradle 9.6, JDK 17+), Compose BOM 2026.09.00 (ui/foundation 1.12.1, material3 1.4.0), tv-material 1.1.0, media3 1.11.1, Navigation 3 1.2.0 (Navigation Compose 2.10.2 — «maintenance mode»), Room 3.0.3 (`androidx.room3`) / Room 2.8.5, Paging 3.5.2, WorkManager 2.12.0, DataStore 1.2.1, Lifecycle 2.11.0, Hilt 2.60.1 + androidx.hilt 1.4.0, Koin 4.2.2, Coil 3.6.3, Ktor 3.6.0, OkHttp 5.5.0, kotlinx.coroutines 1.11.0, kotlinx.serialization 1.11.0, KSP 2.3.12, jellyfin-sdk-kotlin 1.9.2, libvlc-all 3.7.7 (stable, 2026-10-01) / 4.0.0-eap29.
6. **Google Play: TV требует target API ≥34, с 1 августа 2026 — 64-bit + 16 KB page size**; F-Droid/IzzyOnDroid — лимит 30 MB на IzzyOnDroid (per-ABI split), reproducible builds желательны; libVLC 3.6.2 имел проблему с 16 KB alignment (баг-репорт), проверять `zipalign -P 16` на текущих 3.7.x.
7. **Google TV «Continue watching»**: legacy Watch Next (TvProvider) продолжает работать на Android TV и на Google TV (с сертификацией), но «все новые интеграции Continue Watching должны идти через Engage SDK» (allowlist/EAP, g.co/tv/vda, лимит 5 сущностей). Для open-source клиента реалистично реализовать TvProvider Watch Next + каналы (как jellyfin-androidtv `LeanbackChannelWorker`), а Engage — опционально.

---

## 1. Официальная документация Android TV

### Качество и манифест
- [TV app quality guidelines](https://developer.android.com/docs/quality-guidelines/tv-app-quality) — чек-лист ревью Play (Tier 3 «TV Ready» — минимум). Ключевые пункты: **TV-ML** (`ACTION_MAIN` + `CATEGORY_LEANBACK_LAUNCHER`), **TV-MT** (`android.hardware.touchscreen` и прочее железо `required=false`), **TV-LB** (баннер 320×180 px xhdpi + иконка ≥160×160), **TV-BN** (баннер содержит имя), **TV-DP** (вся функциональность доступна с D-pad), **TV-DM** (не зависеть от Menu), **TV-DB** (Back ведёт к Home), **TV-LO/TV-OV** (landscape, без обрезки по overscan), **TV-PC** (центр D-pad = play/pause, лево/право = перемотка), **TV-PP** (`KEYCODE_MEDIA_PLAY_PAUSE`), **TV-NP** (Now Playing card только для фонового аудио; видео должно ставиться на паузу при уходе), **TV-VC** (Tier 2: голосовые команды через `MediaSession`), **TV-BU/BY/BA** (Ambient Mode: блокировать только при активном видео), **TV-ME** (лимиты памяти на low-RAM), **TV-G6** (с 1 августа 2026 — 32/64-bit + 16 KB страницы), **TV-PN/TV-EC/TV-ER** (Watch Next / Engage SDK), PiP **TV-IC…TV-IX**.
- [Get started with TV apps (manифест, баннер, эмулятор)](https://developer.android.com/training/tv/start/start) — обязательные элементы манифеста:
  ```xml
  <uses-feature android:name="android.software.leanback" android:required="false"/>  <!-- true только для TV-only -->
  <uses-feature android:name="android.hardware.touchscreen" android:required="false"/>
  <application android:banner="@drawable/banner" ...>   <!-- 320x180 px xhdpi, текст внутри картинки, по баннеру на локаль -->
    <activity ...>
      <intent-filter>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LEANBACK_LAUNCHER"/>
      </intent-filter>
    </activity>
  ```
  Также: `android:colorPrimary` для анимации запуска; кастомный SplashScreen API на TV (Android 12+) не поддерживается. Раздел про запуск на TV-эмуляторе (Device Manager → категория TV).
- [Handle TV hardware](https://developer.android.com/training/tv/get-started/hardware) — рекомендованный способ определить TV: `packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)`. Таблица «неподдерживаемого» на TV железа (touchscreen, faketouch, telephony, camera, nfc, gps, microphone, sensors, screen.portrait) — всё объявлять `required="false"`, плюс `android.hardware.wifi` (есть Ethernet-only ТВ). Разрешения, неявно требующие железо (`RECORD_AUDIO` → microphone, `ACCESS_WIFI_STATE` → wifi) — тоже закрывать `uses-feature required=false`. `<meta-data android:name="android.software.leanback.supports_touch">` для pointer-пультов. Останавливать воспроизведение в `onStop()` (ТВ в «выключенном» режиме продолжает играть звук).
- [Manage TV controllers](https://developer.android.com/training/tv/get-started/controllers) — D-pad обязателен; `android:configChanges="keyboard|keyboardHidden|navigation"` чтобы не пересоздавать Activity при отключении BT-пульта; таблица маппинга `KEYCODE_BUTTON_A/SELECT/ENTER/DPAD_CENTER` → Select, `BUTTON_B/BACK` → Back, `DPAD_RIGHT/BUTTON_R2` → FF, `DPAD_LEFT/BUTTON_L2` → RW; не перехватывать `KEYCODE_MEDIA_*`, если есть `MediaSession` (система сама диспатчит в callback). Back никогда не toggle.
- [TV navigation (D-pad, фокус, Back)](https://developer.android.com/training/tv/start/navigation) — принципы: всегда есть сфокусированный элемент; индикация фокуса одной схемой; `nextFocusUp/Down/Left/Right` только при необходимости; Back не должен спрашивать подтверждение выхода; не рисовать экранную кнопку Back; `OnBackPressedDispatcher` для кастомного поведения; splash не в back stack.
- [Game controller input](https://developer.android.com/develop/ui/views/touch-and-input/game-controllers/controller-input) — `KeyEvent` для кнопок, `MotionEvent` для осей; D-pad геймпада приходит как `AXIS_HAT_X/Y` **или** `KEYCODE_DPAD_*` — обрабатывать оба; dead zone через `MotionRange.getFlat()`; `InputDevice.supportsSource(SOURCE_GAMEPAD/DPAD/JOYSTICK)`.

### Воспроизведение («Build TV playback apps»)
- [Build TV playback apps (оглавление раздела)](https://developer.android.com/training/tv/playback) — подразделы: Compose for TV (setup, layouts, catalog browser, details, scrollable layouts, migrate from Leanback), Ambient mode, Playback controls, Media session, Now Playing card, Audio capabilities, Adjust display settings/frame rate, Memory. TV Input Framework нужен **не** клиенту Jellyfin (только для Live-TV-провайдеров и DRM-превью на домашнем экране).
- [Playback controls on TV](https://developer.android.com/training/tv/playback/controls) — стандарт: центр = play/pause (+показать контролы), короткий Right/Left = перемотка на N сек, удержание = scrub, Up/Down = «peek» информации без паузы.
- [Implement a media session](https://developer.android.com/training/tv/playback/media-session) — framework `MediaSession`: `FLAG_HANDLES_MEDIA_BUTTONS|FLAG_HANDLES_TRANSPORT_CONTROLS`, `setActive(true)` после получения audio focus, `PlaybackState` с доступными `ACTION_*`, `MediaMetadata` (DISPLAY_TITLE/SUBTITLE/ICON_URI + legacy TITLE/ARTIST + маленький bitmap). (Для нового кода — media3 `MediaSession`, см. §5.)
- [Background playback in a Now Playing card](https://developer.android.com/training/tv/playback/now-playing) — карточка появляется при активной сессии + audio focus; обязателен `setSessionActivity(PendingIntent)` на экран с контролами; снимать `setActive(false)` сразу при стопе или через 5–30 мин паузы; **видео не должно использовать Now Playing** (только аудио/музыка или PiP).
- [Ambient mode](https://developer.android.com/training/tv/playback/ambient-mode) — `FLAG_KEEP_SCREEN_ON` только во время активного видео, снимать на паузе; Energy Saver предотвратить нельзя.
- [Optimize memory usage](https://developer.android.com/training/tv/playback/memory) — `ActivityManager.isLowRamDevice()`; на 1 GB-устройствах цель: anon+swap <160 MB, graphics 30–40 MB, максимум 280 MB суммарно; не грузить картинки больше разрешения UI (на 720p UI даунскейлить 1080p-постеры); hardware bitmaps; медиабуферы 40–60 MB (1 GB/1080p) — 100–120 MB (2 GB/4K); освобождать плеер в `onStop`; WorkManager с `setRequiresDeviceIdle(true)` на low-RAM; baseline profiles для холодного старта.
- [Match content frame rate (TV)](https://developer.android.com/training/tv/playback/framerate) и общий гайд [Frame rate API](https://developer.android.com/media/optimize/performance/frame-rate) — см. §5.
- [Audio capabilities (TV)](https://developer.android.com/training/tv/playback/audio-capabilities) — см. §5.
- [PiP on TV / multitasking](https://developer.android.com/training/tv/get-started/multitasking) — см. §5 (важно: на TV PiP для медиа-воспроизведения **не разрешён** по категориям `com.google.android.tv.pip.category`).

### Watch Next / каналы / Google TV
- [Add programs to the Watch Next channel](https://developer.android.com/training/tv/discovery/watch-next-add-programs) — `androidx.tvprovider:tvprovider:1.1.0`, `WatchNextProgram.Builder().setType(TYPE_MOVIE/TYPE_TV_EPISODE).setWatchNextType(WATCH_NEXT_TYPE_CONTINUE/NEXT/NEW/WATCHLIST).setLastEngagementTimeUtcMillis().setLastPlaybackPositionMillis().setDurationMillis().setPosterArtUri().setIntentUri().setInternalProviderId()` → `contentResolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, …)`; обновление через `TvContractCompat.buildWatchNextProgramUri(id)`; обрабатывать `ACTION_WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED` (пользователь удалил — не вставлять снова).
- [Watch Next program attributes](https://developer.android.com/training/tv/discovery/watch-next-programs) — обязательны: Watch Next type, last engagement time; для `CONTINUE` — ещё last playback position и duration; `InternalProviderId` рекомендуется.
- [Video program attributes (постеры)](https://developer.android.com/training/tv/discovery/video-programs) — обязательные поля программ: channelId, title, intentUri, posterArtUri, posterArtAspectRatio; размеры: `ASPECT_RATIO_16_9` 272×153 dp, `ASPECT_RATIO_2_3` 102×153 dp, `ASPECT_RATIO_MOVIE_POSTER` (1:1.441) 106×153 dp.
- [Watch Next guidelines for app developers](https://developer.android.com/training/tv/discovery/guidelines-app-developers) — только фильмы/эпизоды; «начато» = >3 %/2 мин (фильм) или >2 мин (эпизод); «закончено» = титры (или <3 мин до конца); публиковать при выходе из приложения или паузе >5 мин, латентность ≤5 с; не более одного эпизода на сериал; удалять по завершении.
- [Channels on the home screen (preview channels)](https://developer.android.com/training/tv/discovery/recommendations-channel) — Android 8+; `Channel.Builder().setType(TYPE_PREVIEW)`, лого 80×80 dp, `TvContractCompat.requestChannelBrowsable()` для дефолтного канала, `ACTION_REQUEST_CHANNEL_BROWSABLE` для остальных (из foreground Activity), ресивер `android.media.tv.action.INITIALIZE_PROGRAMS`; нельзя WHERE-клаузы в запросах к провайдеру; нужен `WRITE_EPG_DATA`. Пример в проде: jellyfin-androidtv `app/src/main/java/org/jellyfin/androidtv/integration/LeanbackChannelWorker.kt` (CoroutineWorker раз в час, каналы latest_media/my_media/next_up/latest_movies/latest_episodes + Watch Next; проверяет `FEATURE_LEANBACK` и наличие провайдера `TvContractCompat.AUTHORITY`).
- [Display video previews](https://developer.android.com/training/tv/discovery/preview-videos) — `setPreviewVideoUri()` (публичный HTTPS, без DRM) или `TvInputService` для DRM — для Jellyfin не нужно.
- [Recommendations row (Android ≤7.1)](https://developer.android.com/training/tv/discovery/recommendations-row) — только для API ≤25; на API 26+ обязательны каналы. Для нового приложения (minSdk ≥26 на TV) можно игнорировать.
- [Google TV для разработчиков](https://developer.android.com/training/tv/get-started/google-tv) — всё Android TV работает на Google TV без отдельной сборки; Watch Next на Google TV требует сертификации интеграции; определить Google TV: `packageManager.hasSystemFeature("com.google.android.feature.AMATI_EXPERIENCE")`; media actions feed — только для ограниченного круга партнёров.
- [Engage SDK](https://developer.android.com/guide/playcore/engage), [Engage SDK for TV: Continue Watching (client)](https://developer.android.com/guide/playcore/engage/tv/continue-watching/client), [Engage SDK FAQ](https://developer.android.com/guide/playcore/engage/faq) — `AppEngagePublishClient.publishContinuationCluster()` (upsert всего списка, ≤10 элементов, FAQ: лимит 5 на партнёра; постеры 16:9 ≥200 px; `PlatformSpecificUri` для TV и Android; `setSyncAcrossDevices` с явным согласием; `deleteClusters(DELETE_REASON_USER_LOG_OUT)`). FAQ: «Engage будет обратно совместим на всех Android TV с Watch Next API… существующая интеграция продолжит работать», «все новые интеграции Continue Watching должны использовать Engage», доступ — phased/EAP с allowlist (g.co/tv/vda), обязателен скриншот verification app при сабмите.
- Статус TvProvider: `androidx.tvprovider:tvprovider:1.1.0` (май 2025) активно сопровождается — [release notes](https://developer.android.com/jetpack/androidx/releases/tvprovider).

### Дизайн (10-foot UI)
- [TV design (обзор)](https://developer.android.com/design/ui/tv) — Foundations / Style / Layout / Components, Figma-киты ([figma.com/@tv](https://www.figma.com/@tv)).
- [Design for TV (foundations)](https://developer.android.com/design/ui/tv/guides/foundations/design-for-tv) — 10-foot UI (зритель ~3 м, крупный текст, мало чтения), D-pad, общее устройство (приватность профилей).
- [Layouts (safe area, сетка)](https://developer.android.com/design/ui/tv/guides/styles/layouts) — проектировать на канве **960×540 dp** (16:9), ассеты под 1080p; overscan-отступы ≈5 % (48 dp по бокам / 27 dp сверху-снизу, в разделе сетки — 58/28 dp; документ сам противоречив, большинство современных ТВ overscan не имеют); сетка 12 колонок × 52 dp, gutter 20 dp, поля 58 dp; ширины карточек 844/412/268/196 dp для 1–4 в ряд; оставлять зазор под увеличение сфокусированной карточки.
- [Typography](https://developer.android.com/design/ui/tv/guides/styles/typography) — 15 стилей (Display/Headline/Title/Body/Label), Roboto по умолчанию; точные sp на странице только картинкой (не проверено текстом).

### Reference-приложения и эмулятор
- [android/tv-samples](https://github.com/android/tv-samples) — `JetStreamCompose` (полное стриминг-приложение на Compose for TV: TabRow, Carousel, ImmersiveList-паттерн, карточки, Macrobenchmark + baseline profile; catalog: tv-material 1.0.0, Hilt, Navigation Compose, media3-ui-compose, Coil 2), `TvMaterialCatalog` (каталог компонентов tv-material), `ReferenceAppKotlin` (Leanback + ExoPlayer + MediaSessionConnector + каналы/Watch Next + Cast Connect; Leanback-based, но единственный официальный пример полной интеграции Watch Next/каналов), `ClassicsKotlin`, `Leanback`, `LeanbackShowcase`, `AccessibilityDemo`.
- [android/tv-codelabs](https://github.com/android/tv-codelabs) + [Codelab «Introduction to Compose for TV»](https://developer.android.com/codelabs/compose-for-tv-introduction) — каталог (LazyColumn/LazyRow + Carousel + Coil AsyncImage) и экран деталей.
- [android/uamp (Universal Android Music Player)](https://github.com/android/uamp) — **архивирован 9 января 2026, deprecated**; как референс для TV/MediaSession больше не использовать. Актуальная замена по теме Compose + media3 на нескольких форм-факторах — [Jetcaster](https://github.com/android/compose-samples/tree/main/Jetcaster) (модули `mobile`, `tv`, `wear`, `core/data|domain|designsystem`; `tv` использует `androidx.tv:tv-material`, Navigation Compose, Hilt, `media3-exoplayer/session/ui-compose`).
- Эмулятор без устройства: Device Manager → категория TV; доступные системные образы (проверено по индексу SDK): `system-images;android-33|34|36;android-tv;{arm64-v8a,x86[,x86_64]}` и `system-images;android-33|34|36;google-tv;…`, плюс `system-images;android-36;google-tv-ps16k;{arm64-v8a,x86_64}` (Google TV с 16 KB страницами) — [Create and manage virtual devices](https://developer.android.com/tools/devices). Начиная с Android 13 на TV работает Wireless debugging (`adb pair`), для старых — `adb tcpip 5555` + `adb connect ip:5555` — [adb docs](https://developer.android.com/tools/adb).

---

## 2. Compose for TV

### Библиотеки и статус
- [Compose for TV release notes](https://developer.android.com/jetpack/androidx/releases/tv) — `androidx.tv:tv-material:1.1.0` (stable, 2026-05-06; 1.1.0-alpha01 чинила фокус в Carousel; в 1.1.0-beta01 deprecated `NativePaint`/`asFrameworkPaint()` → `Paint.nativePaint`, нужен KGP ≥2.0) и `androidx.tv:tv-foundation:1.0.0` (stable, 2026-05-06). История: `TvLazyColumn/TvLazyRow/TvLazyVerticalGrid/TvLazyHorizontalGrid` deprecated в 1.0.0-alpha11 (июль 2024) и **удалены** в 1.0.0-alpha12 (янв 2025); `ImmersiveList` **удалён** в tv-material 1.0.0-beta01 (есть сэмпл: [ImmersiveListSamples.kt в cs.android.com](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:tv/samples/src/main/java/androidx/tv/samples/ImmersiveListSamples.kt)); в beta01 стабилизированы `MaterialTheme`, `ColorScheme/Typography/Shapes`, `Border`, `Glow`, `Surface`, `Text`, `Icon`, `Button/OutlinedButton/IconButton/OutlinedIconButton/WideButton`, `Card*` (`StandardCardContainer`, `WideCardContainer`), `ListItem/DenseListItem`, `Tab/TabRow`, `NavigationDrawer/ModalNavigationDrawer/NavigationDrawerItem`, `Checkbox/RadioButton/Switch`; `Carousel` — часть функций «dropped for beta из-за экспериментальных API».
  Зависимости:
  ```kotlin
  implementation(platform("androidx.compose:compose-bom:2026.09.00"))
  implementation("androidx.tv:tv-material:1.1.0")      // вместо androidx.compose.material3 на TV
  implementation("androidx.tv:tv-foundation:1.0.0")    // опционально
  ```
- [Compose for TV: setup](https://developer.android.com/training/tv/playback/compose) — использовать `androidx.tv.material3` вместо `androidx.compose.material3` на TV (свой `MaterialTheme`, не смешивать); minSdk 21.
- [Create scrollable layouts for TV](https://developer.android.com/training/tv/playback/compose/lists) — обычные `LazyRow/LazyColumn` (Foundation ≥1.7) уже умеют focus-driven scroll; таблица миграции `TvLazyRow→LazyRow`, `pivotOffsets→BringIntoViewSpec` через `LocalBringIntoViewSpec`; код `PositionFocusedItemInLazyLayout(parentFraction=0.3f)`; для вложенного списка — отключить, подставив пустой `object : BringIntoViewSpec {}`.
- [BringIntoViewSpec introduction (Paramount Tech, 03.2025)](https://paramount.tech/blog/2025/03/03/jetpack-compose-bringintoviewspec-intro.html) — дефолт `LocalBringIntoViewSpec` сам выбирает `PivotBringIntoViewSpec` при `FEATURE_LEANBACK`; ловушка: в конце списка pivot не достигается — добавлять `contentPadding` в конец, равный ширине вьюпорта.
- [Migrate from Leanback to Compose](https://developer.android.com/training/tv/playback/leanback/migrate-to-compose) — экран за экраном, Leanback и Compose сосуществуют, цель — single-activity.
- [Leanback release notes](https://developer.android.com/jetpack/androidx/releases/leanback) — **«This artifact and its classes are deprecated. Use Compose for TV instead»**; `androidx.leanback:leanback:1.2.0` stable (есть 1.3.0-alpha02, июнь 2026 — только багфиксы).

**Leanback vs Compose for TV для нового проекта 2026:** Leanback — deprecated, Fragment/RecyclerView-ориентирован, фиксированные шаблоны (`BrowseSupportFragment`, `DetailsSupportFragment`, `PlaybackSupportFragment`, `GuidedStepFragment`), нет аналога в Compose «из коробки», но зато зрелая фокус-логика. Compose for TV — рекомендация Google, общий код с phone, но фокус-менеджмент надо строить самому (`focusRestorer`, `focusGroup`, `BringIntoViewSpec`), и часть вещей (immersive list, guided step) — руками. Вывод: Compose for TV; Leanback только как источник UX-паттернов. jellyfin-androidtv до сих пор на Leanback 1.2.0 + Compose foundation (гибрид), Findroid TV — чистый Compose (tv-material 1.1.0 + Navigation 3).

### Фокус в Compose (гайды, которые реально существуют)
- [Focus in Compose (обзор + ссылки на 11 подгайдов)](https://developer.android.com/develop/ui/compose/touch-input/focus) — подстраницы: `/focus/focus-target`, `/focus/focus-traversal`, `/focus/focus-in-text-fields`, `/focus/focus-group`, `/focus/focus-restoration`, `/focus/focused-state`, `/focus/react-to-focus`, `/focus/request-focus`, `/focus/move-focus`, `/focus/capture-and-release-focus`, `/focus/testing-focus`.
- [Restore focus](https://developer.android.com/develop/ui/compose/touch-input/focus/focus-restoration) — `LazyColumn/LazyRow/LazyVerticalGrid` уже focus group → достаточно `Modifier.focusRestorer()`; для `Row/Column` нужен `focusGroup().focusRestorer()`; fallback `focusRestorer(firstItemRequester)` (лямбда-вариант deprecated); для `NavigationRail/TabRow` — `focusProperties { onEnter = { selectedRequester.requestFocus() } }`; переход между экранами — `FocusRequester.saveFocusedChild()` перед навигацией и `restoreFocusedChild()` при возврате.
- [Group focus targets](https://developer.android.com/develop/ui/compose/touch-input/focus/focus-group) — `focusGroup()`, `focusProperties { onEnter / onExit }`, `cancelFocusChange()`, `requestedFocusDirection`.
- [Programmatically request focus](https://developer.android.com/develop/ui/compose/touch-input/focus/change-focus-behavior) — `FocusRequester` + `LaunchedEffect(Unit){ requestFocus() }`; **ловушка**: не вызывать `requestFocus()` в теле composable и до attach модификатора (исключение).
- [React to focus](https://developer.android.com/develop/ui/compose/touch-input/focus/react-to-focus) — `onFocusChanged { it.isFocused / it.hasFocus / it.isCaptured }`; ловушка: `isFocused` у контейнера = false, когда сфокусирован ребёнок — для подсветки контейнера использовать `hasFocus`; состояние держать в `remember { mutableStateOf }`.
- [focusRestorer reference](https://developer.android.com/reference/kotlin/androidx/compose/ui/focus/focusRestorer.modifier).
- Обработка клавиш: `Modifier.onPreviewKeyEvent { it.key == Key.DirectionLeft && it.type == KeyEventType.KeyDown }` (capture-фаза) / `onKeyEvent`; Back — `BackHandler` из `androidx.activity.compose` (и `NavigationBackHandler` в Nav3 для predictive back). Ключевые `Key.DirectionUp/Down/Left/Right/DirectionCenter`, `Key.MediaPlayPause`, `Key.Back`.
- Тесты фокуса: [Test focus navigation](https://developer.android.com/develop/ui/compose/touch-input/focus/testing-focus) — см. §8.

---

## 3. Одна кодовая база phone + TV

### Детекция формы устройства
- `PackageManager.FEATURE_LEANBACK` — официальная рекомендация ([Handle TV hardware](https://developer.android.com/training/tv/get-started/hardware)). `UiModeManager.currentModeType == UI_MODE_TYPE_TELEVISION` — старая рекомендация, всё ещё встречается (например в IMA SDK samples); система выставляет TV-режим при `FEATURE_TELEVISION` **или** `FEATURE_LEANBACK`, поэтому сигналы почти совпадают. На практике: для выбора UI-ветки — `FEATURE_LEANBACK`; для «есть ли тач» — `FEATURE_TOUCHSCREEN`; Google TV — `com.google.android.feature.AMATI_EXPERIENCE`; Fire TV — `Build.MANUFACTURER == "Amazon"` + `amazon.hardware.fire_tv` (не проверено).
- [Window size classes](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes) — `currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true).windowSizeClass` из `androidx.compose.material3.adaptive:adaptive:1.3.0` (stable); брейкпоинты Compact <600 / Medium <840 / Expanded <1200 / Large <1600 / XL ≥1600 dp. **Для TV не подходят как признак устройства**: 1080p-ТВ = 960×540 dp (Expanded width, Medium height), т.е. выглядит как планшет в ландшафте; док прямо говорит «size classes are not device types». Размерные классы — для адаптивности внутри phone/tablet-UI, а TV-ветка выбирается по `FEATURE_LEANBACK`.

### Как организуют код существующие клиенты
- **Findroid** ([repo](https://github.com/jarnedemeulemeester/findroid), GPL-3.0) — `settings.gradle.kts`: `:app:phone`, `:app:tv`, `:core`, `:data`, `:player:core`, `:player:local`, `:setup`, `:modes:film`, `:settings`. TV-модуль: отдельный `MainActivity` (`ComponentActivity` + `setContent { FindroidTheme { NavigationRoot(...) } }`), манифест с `android.software.leanback required=true`, `touchscreen required=false`, `android:banner`, `LEANBACK_LAUNCHER`; зависимости TV-модуля — `tv-material`, `navigation3-runtime/ui`, `lifecycle-viewmodel-navigation3`, `savedstate-compose`, `paging-compose`, `hilt-lifecycle-viewmodel-compose`, `coil-compose/network-okhttp/cache-control/svg`, `media3-exoplayer/ui/session`, `media3-ffmpeg-decoder` (org.jellyfin.media3). Phone-модуль — `material3` 1.4.0 + `material3-adaptive-navigation-suite`. Плеер общий (`:player:local` — ExoPlayer и libmpv через `dev.jdtech.mpv:libmpv:1.0.0`, `MPVPlayer : BasePlayer()`), данные общие (`:data`, Room 3), скачивание в `:core` (`Downloader`/`DownloaderImpl`). Т.е. **две app-модули с разным UI поверх общих core/data/player** — самый прямой образец для вашего проекта (замените mpv на libVLC).
- **jellyfin-androidtv** ([repo](https://github.com/jellyfin/jellyfin-androidtv), GPL-2.0) — модули `:app`, `:design`, `:playback:core`, `:playback:jellyfin`, `:playback:media3:exoplayer`, `:playback:media3:session`, `:preference`. Koin 4.2.2 (`koin-android`, `koin-androidx-compose`, `koin-androidx-workmanager`), Leanback 1.2.0 + `compose-foundation` 1.12.1 + `fragment-compose` + `navigation3-ui` 1.1.7, media3 1.11.1 (+ `media3-datasource-okhttp`, `media3-exoplayer-hls`, ffmpeg-decoder, `io.github.peerless2012:ass-media` для ASS-субтитров), Coil 3.6.3, tvprovider 1.1.0, WorkManager, jellyfin-sdk 1.8.12, Kotlin 2.4.20, AGP 9.4.1, minSdk 23, compileSdk 37, targetSdk 36. Архитектурно важно: `playback:core` — собственная абстракция плеера (`PlaybackManager` + backends), а `playback:media3:session` — адаптер в media3 `SimpleBasePlayer` → `MediaSession`.
- **Streamyfin** ([repo](https://github.com/streamyfin/streamyfin), MPL-2.0) — Expo/React Native, MPV на всех платформах, есть TV-таргет (`:tv` скрипты), загрузки через FFmpeg/HLS; как образец архитектуры для нативного Kotlin — не релевантен, но подтверждает тренд «общая логика + отдельная TV-оболочка».
- Google-сэмпл с разделением по форм-факторам: [Jetcaster](https://github.com/android/compose-samples/tree/main/Jetcaster) (`mobile`/`tv`/`wear` + `core/*`).

### Ввод: пульт vs тач vs геймпад
- Общие composable'ы должны работать от `clickable`/`onClick` (D-pad center вызывает `onClick`), а TV-специфика (увеличение карточки по фокусу, `Glow`/`Border` из tv-material, `focusRestorer`) живёт в TV-модуле. Медиаклавиши (`KEYCODE_MEDIA_*`) — через `MediaSession`, не через `onKeyEvent`. Геймпад: см. §1 (D-pad может приходить как `AXIS_HAT_*`). Pointer-пульты/тач на ТВ — `android.software.leanback.supports_touch`.

---

## 4. Рекомендуемый стек (октябрь 2026) — версии проверены

| Компонент | Версия (stable) | Источник |
|---|---|---|
| Kotlin | **2.4.21** (2026-10-08; 2.4.0 — 2026-06-03; есть 2.5.0-Beta1) | [kotlinlang.org/docs/releases](https://kotlinlang.org/docs/releases.html) |
| AGP / Gradle / JDK | **AGP 9.4.0** (Gradle 9.6.0, JDK 17, Build-Tools 36, NDK 28.2); Findroid и jellyfin-androidtv на 9.4.1 | [AGP release notes](https://developer.android.com/build/releases/gradle-plugin) |
| Android Studio | Rabbit 1 (2026.2.1) | [studio/releases](https://developer.android.com/studio/releases) |
| Compose BOM | **2026.09.00** → ui/foundation/runtime 1.12.1, material3 1.4.0 | [BOM mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping) |
| Compose for TV | tv-material **1.1.0**, tv-foundation 1.0.0 | [releases/tv](https://developer.android.com/jetpack/androidx/releases/tv) |
| material3-adaptive | 1.3.0 (phone-ветка) | Google Maven |
| activity-compose | 1.13.0 | Google Maven |
| Lifecycle | **2.11.0** (viewmodel-navigation3, `rememberViewModelStoreOwner`) | [releases/lifecycle](https://developer.android.com/jetpack/androidx/releases/lifecycle) |
| Navigation 3 | **1.2.0** (2026-09-23; `navigation3-runtime` KMP, `navigation3-ui`; 1.3.0-alpha02) | [releases/navigation3](https://developer.android.com/jetpack/androidx/releases/navigation3) |
| Navigation Compose (2.x) | 2.10.2, **«maintenance mode, only critical fixes»** | [releases/navigation](https://developer.android.com/jetpack/androidx/releases/navigation) |
| media3 | **1.11.1** (2026-09-10) | [releases/media3](https://developer.android.com/jetpack/androidx/releases/media3) |
| Room | Room 3 **`androidx.room3:room3-runtime:3.0.3`** (KSP-only, suspend/Flow-first, SQLiteDriver) или Room 2 **2.8.5** | [releases/room3](https://developer.android.com/jetpack/androidx/releases/room3), [releases/room](https://developer.android.com/jetpack/androidx/releases/room) |
| Paging | **3.5.2** (`paging-compose`) | [releases/paging](https://developer.android.com/jetpack/androidx/releases/paging) |
| WorkManager | **2.12.0** (minSdk 24, compileSdk ≥33) | [releases/work](https://developer.android.com/jetpack/androidx/releases/work) |
| DataStore | **1.2.1** (1.3.0-alpha11) | [releases/datastore](https://developer.android.com/jetpack/androidx/releases/datastore) |
| Hilt | Dagger/Hilt **2.60.1** (Kotlin 2.3.21; KSP через `ksp("com.google.dagger:hilt-compiler")`), androidx.hilt **1.4.0** (`hilt-lifecycle-viewmodel-compose`, `hilt-navigation-compose`, `hilt-work`; требует AGP ≥9.2 из-за compileSdk 37) | [dagger releases](https://github.com/google/dagger/releases) |
| Koin | **4.2.2** (`koin-android`, `koin-androidx-compose`, `koin-androidx-workmanager`, `koin-compose-navigation3`) | [koin releases](https://github.com/InsertKoinIO/koin/releases) |
| Coil | **3.6.3** (`io.coil-kt.coil3:coil-compose`, `coil-network-okhttp`, `coil-network-cache-control`, `coil-svg`, `coil-gif`) | [coil releases](https://github.com/coil-kt/coil/releases) |
| Ktor | **3.6.0** (2026-09-17) | [ktor releases](https://ktor.io/docs/releases.html) |
| OkHttp | 5.5.0 | Maven Central |
| kotlinx.coroutines | **1.11.0** | [releases](https://github.com/Kotlin/kotlinx.coroutines/releases) |
| kotlinx.serialization | **1.11.0** (1.12.0-RC) | [releases](https://github.com/Kotlin/kotlinx.serialization/releases) |
| KSP | 2.3.12 | Maven Central |
| jellyfin-sdk-kotlin | **`org.jellyfin.sdk:jellyfin-core:1.9.2`** (2026-10-06, OpenAPI 12.2, минимум сервер 12.0; 1.8.12 — последняя для серверов 10.11) | [GitHub releases](https://github.com/jellyfin/jellyfin-sdk-kotlin/releases) |
| libVLC | **`org.videolan.android:libvlc-all:3.7.7`** (2026-10-01), 4.0.0-eap29 (2026-07-08); `medialibrary-all` 0.13.24 | Maven Central metadata |
| profileinstaller / macrobenchmark | 1.4.1 / 1.5.0 | Google Maven |

Пояснения и ссылки:
- **DI**: Findroid — Hilt (+KSP), jellyfin-androidtv — Koin. Для многомодульного проекта с двумя app-модулями оба работают; Hilt даёт compile-time проверку и `hilt-work`/`hilt-lifecycle-viewmodel-compose`; Koin проще в KMP/мультимодульности и уже имеет `koin-compose-navigation3`. Любой из них — вопрос вкуса; совместимость с Navigation 3 есть у обоих (`lifecycle-viewmodel-navigation3` + `hiltViewModel()`/`koinViewModel()`).
- **Навигация**: для нового Compose-проекта — **Navigation 3** (`NavDisplay`, `entryProvider`, `SceneStrategy`, `NavigationBackHandler`, deep links, Result API). Оба Jellyfin-клиента уже на Nav3.
- **Сеть/SDK**: jellyfin-sdk-kotlin с v1.7 по умолчанию на **OkHttp** (Ktor-пакет `jellyfin-api-ktor` объявлен удаляемым) — [migration v1.7](https://kotlin-sdk.jellyfin.org/migration/v1.7.html) (не проверено текстом, из поисковой выдачи); [Getting started](https://kotlin-sdk.jellyfin.org/guide/getting-started.html): `createJellyfin { clientInfo = ClientInfo(name, version); context = androidContext }`, `jellyfin.createApi(baseUrl, accessToken, deviceInfo, httpClientOptions)`, API через `api.userApi.authenticateUserByName(...)`, WebSocket/discovery через `Flow`; Android ≥4.4, в 1.9 — minSdk 21, compileSdk 37; SDK содержит `buildDeviceProfile {}` DSL (`org.jellyfin.sdk.model.deviceprofile`). Следствие: отдельный Ktor-клиент не нужен — один `OkHttpClient` для SDK, Coil и загрузок. Если всё же Ktor — на Android движок `ktor-client-okhttp` (HTTP/2, общий пул с OkHttp), CIO не поддерживает HTTP/2.
- **Coil 3 с заголовками Jellyfin**: [Coil network docs](https://coil-kt.github.io/coil/network/) — per-request `ImageRequest.Builder().httpHeaders(NetworkHeaders.Builder().set("Authorization","MediaBrowser Token=…").build())`, глобально — `OkHttpNetworkFetcherFactory(callFactory = { okHttpClient.newBuilder().addNetworkInterceptor(...).build() })`; по умолчанию Coil игнорирует `Cache-Control` (всегда пишет в дисковый кэш) — `coil-network-cache-control` для уважения заголовков (Findroid подключает).
- **BlurHash**: Coil 3 не имеет встроенного декодера. Варианты: [woltapp/blurhash](https://github.com/woltapp/blurhash) (референсная Kotlin-реализация в репо, **не публикуется** как Maven-артефакт — копировать файл), [vanniktech/blurhash](https://github.com/vanniktech/blurhash) (`com.vanniktech:blurhash:0.3.0`, KMP, MIT, encode+decode в `Bitmap`; релиз 2024-03), `io.github.brys0:blurhash` (AGSL/SKSL-шейдер, 0.0-4-ALPHA — не для прода), `com.github.skydoves:landscapist-coil3:2.13.2` (обёртка Coil3 + `landscapist-placeholder`, но BlurHash там нет — не проверено). Практика: декодировать BlurHash (из `ImageBlurHashes` в `BaseItemDto`) в маленький `Bitmap` → `placeholder(BitmapDrawable)` или рисовать `ImageBitmap` под `AsyncImage`.
- **Baseline Profiles**: [обзор](https://developer.android.com/topic/performance/baselineprofiles/overview) — плагин `androidx.baselineprofile` + `androidx.benchmark:benchmark-macro-junit4:1.5.0` + `androidx.profileinstaller:profileinstaller:1.4.1`; `./gradlew app:generateBaselineProfile`; ~30 % ускорение исполнения, Startup Profiles +15 %; профиль должен быть <1.5 MB; **для TV-боксов со слабым CPU особенно важно**, но учесть: вне Play (F-Droid/APK) профиль применяется не при установке, а после фонового dexopt (обычно «за ночь»); JetStreamCompose содержит готовый пример (`benchmark` модуль + `baseline-prof.txt`). TV-гайд по памяти тоже рекомендует baseline profiles.
- **R8**: [Shrink code](https://developer.android.com/build/shrink-code) — `isMinifyEnabled = true`, `isShrinkResources = true`, только `proguard-android-optimize.txt` (`proguard-android.txt` удалён в AGP 9.0); full mode по умолчанию с AGP 8; AGP 9.3 — новый `optimization { enable = true }` и keep-rules в `src/<variant>/keepRules/*.keep`; keep-правила для kotlinx.serialization и libVLC JNI (`org.videolan.libvlc.**` — не проверено, брать из README libvlc).
- **Version catalogs**: [Migrate to catalogs](https://developer.android.com/build/migrate-to-catalogs) — `gradle/libs.versions.toml` (`[versions]/[libraries]/[plugins]`, `alias(libs.plugins.…)`); оба Jellyfin-клиента используют каталоги + `TYPESAFE_PROJECT_ACCESSORS`.
- **KMP**: не нужен (Room 3/Nav3/Lifecycle KMP-совместимы, но целей кроме Android нет; libVLC-биндинги — Android-only).
- **Play target API**: с 31.08.2026 новые приложения/обновления — target 36 (телефоны), **Android TV — ≥34**; продление до 01.11.2026 — [target-sdk requirements](https://developer.android.com/google/play/requirements/target-sdk).

---

## 5. Медиа-интеграция на Android (без ExoPlayer как движка)

### media3 MediaSession поверх libVLC
- [The Player interface (media3)](https://developer.android.com/media/media3/session/player) — `MediaSession` требует `Player`; для своего движка — `SimpleBasePlayer(looper)`: обязателен `getState()` (`State.Builder().setAvailableCommands().setPlaybackState().setPlayWhenReady().setPlaylist().setContentPositionMs().setIsLoading()…`), затем `handle*` только для объявленных команд (`handlePrepare`, `handleSetPlayWhenReady`, `handleSeek`, `handleStop`, `handleRelease`, `handleSetMediaItems`, `handleSetVolume`, `handleSetPlaybackParameters`, `handleSetVideoOutput`, `handleSetTrackSelectionParameters`…), каждый возвращает `ListenableFuture`; `invalidateState()` при изменениях; все вызовы — на треде `Looper`. `ForwardingSimpleBasePlayer` — для декорирования существующего `Player`, не для своего движка ([androidx/media#2181](https://github.com/androidx/media/issues/2181): мейнтейнер подтвердил, что «реализация Player поверх другой инфраструктуры — это ровно то, для чего SimpleBasePlayer»; референс-кода Google не дал). Reference: [SimpleBasePlayer](https://developer.android.com/reference/kotlin/androidx/media3/common/SimpleBasePlayer).
- **Рабочие примеры в open source**:
  - jellyfin-androidtv `playback/media3/session/src/main/kotlin/MediaSessionPlayer.kt` — `class MediaSessionPlayer(looper, manager: PlaybackManager) : SimpleBasePlayer(looper)` с `getState()` и `handleSetPlayWhenReady/handlePrepare/handleStop/handleSeek/handleSetPlaybackParameters/handleSetShuffleModeEnabled/handleSetRepeatMode/handleIncrease|Decrease|SetDeviceVolume/handleSetDeviceMuted/handleRelease`; `MediaSessionService.kt` — `MediaSession.Builder(androidContext, player)`. Бэкенд-независимый — идеальный шаблон для libVLC.
  - Findroid `player/local/src/main/java/dev/jdtech/jellyfin/player/local/mpv/MPVPlayer.kt` — `class MPVPlayer(...) : BasePlayer(), MPVLib.EventObserver, AudioManager.OnAudioFocusChangeListener` (полная реализация `Player` поверх libmpv, включая `Timeline`, трек-листы, audio focus через `AudioFocusRequestCompat`/`AudioManagerCompat` из media3).
  - VLC for Android ([PlaybackService.kt](https://github.com/videolan/vlc-android/blob/master/application/vlc-android/src/org/videolan/vlc/PlaybackService.kt)) и [mpv-android](https://github.com/mpv-android/mpv-android) используют **legacy `MediaSessionCompat` + `MediaBrowserServiceCompat` + `androidx.media.session.MediaButtonReceiver`** — рабочий, но устаревший путь (`androidx.media` 1.8.0 помечен superseded media3).
- [Background playback (MediaSessionService)](https://developer.android.com/media/media3/session/background-playback) — `MediaSessionService`/`MediaLibraryService`, `onGetSession()`, `onDestroy()` → `player.release(); session.release()`; манифест:
  ```xml
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"/>
  <service android:name=".PlaybackService" android:foregroundServiceType="mediaPlayback" android:exported="true">
    <intent-filter><action android:name="androidx.media3.session.MediaSessionService"/></intent-filter>
  </service>
  ```
  Уведомление `MediaStyle` создаётся автоматически из `MediaItem.mediaMetadata` (с API 33 System UI берёт данные из сессии, `MediaNotification.Provider` не действует); сервис сам выходит из foreground через 10 мин паузы; `onTaskRemoved` → `pauseAllPlayersAndStopSelf()`; `MediaButtonReceiver` нужен только для playback resumption (тогда обязателен `onPlaybackResumption()`). media3 1.11: строгий threading (геттеры с чужого потока бросают `IllegalStateException`), `onConnectAsync()` вместо `onConnect`, недоверенные контроллеры по умолчанию read-only. Для **видео** на TV фоновый сервис не нужен (TV-NP), но `MediaSession` нужна для медиакнопок/голоса; для **музыки** из Jellyfin — `MediaSessionService` обязателен.
- [MediaController](https://developer.android.com/media/media3/session/control-playback) — UI общается с плеером через `MediaController.Builder(context, sessionToken).buildAsync()`.
- [media3-ui-compose](https://developer.android.com/media/media3/ui/compose) — `PlayerSurface`, `ContentFrame`, state-holders (`PlayPauseButtonState`, `ProgressStateWithTickInterval`, `MuteButtonState`…) работают с любым `Player`; `media3-ui-compose-material3` даёт готовые кнопки. С libVLC поверхность всё равно даётся через `VLCVideoLayout`/`SurfaceView` в `AndroidView` (**только SurfaceView для HDR**, `TextureView` на Android 13+ тонмапит HDR→SDR).
- [Audio focus](https://developer.android.com/media/optimize/audio-focus) — свой плеер обязан сам делать `AudioFocusRequest.Builder(AUDIOFOCUS_GAIN).setAudioAttributes(USAGE_MEDIA/CONTENT_TYPE_MOVIE).setOnAudioFocusChangeListener()`, реагировать на `LOSS/LOSS_TRANSIENT/LOSS_TRANSIENT_CAN_DUCK/GAIN`; Android 12+ система сама делает fade-out; **target 35+: запрос фокуса возможен только из top-app или foreground service**. ExoPlayer делает это сам через `setAudioAttributes(attrs, handleAudioFocus=true)` — для libVLC копировать логику из Findroid `MPVPlayer`.
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types) — `mediaPlayback` (без runtime-пререквизитов; с target 35 нельзя стартовать из `BOOT_COMPLETED`), `dataSync` (загрузки; таймауты, рекомендуют альтернативы — WorkManager/DownloadManager).

### Picture-in-Picture
- [PiP (общий гайд)](https://developer.android.com/develop/ui/views/picture-in-picture) — `android:supportsPictureInPicture="true"`, `configChanges="screenSize|smallestScreenSize|screenLayout|orientation"`, `launchMode="singleTask"`; Android 12+: `PictureInPictureParams.Builder().setAspectRatio().setSourceRectHint().setAutoEnterEnabled(true).setSeamlessResizeEnabled(true)`, вызывать `setPictureInPictureParams` заранее и при смене aspect; `androidx.activity` `trackPipAnimationHintView`; до 12 — `onUserLeaveHint { enterPictureInPictureMode(params) }`; контролы play/pause/next/prev появляются автоматически при активной `MediaSession`, свои — `RemoteAction` (≤ `getMaxNumPictureInPictureActions()`); паузить в `onStop`, не в `onPause`; `onPictureInPictureUiStateChanged` (Android 15) для скрытия оверлеев; проверять `FEATURE_PICTURE_IN_PICTURE` (low-RAM может не иметь).
- [PiP на TV / multitasking](https://developer.android.com/training/tv/get-started/multitasking) — PiP на TV только с Android 14 (API 34); **обязательна meta-data `com.google.android.tv.pip.category`** со значениями `communication|smartHome|health|ticker`, и «usage types outside these categories, **including any playback of media content, aren't allowed in PiP on TV**» → для видеоклиента PiP на TV фактически запрещён; на телефоне — да. Keep-clear API (`setPreferKeepClear`, `setPreferKeepClearRects` для Compose) — чтобы чужое PiP-окно не перекрывало важные элементы вашего fullscreen-UI.

### Частота кадров (24p и т.д.)
- [Frame rate API](https://developer.android.com/media/optimize/performance/frame-rate) — `Surface.setFrameRate(fps, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS|CHANGE_FRAME_RATE_ALWAYS)` (API 30+, третий параметр с API 31; также `SurfaceControl.Transaction.setFrameRate`, NDK `ANativeWindow_setFrameRate`); передавать точную частоту (23.976, 29.97); для фильмов — `CHANGE_FRAME_RATE_ALWAYS` **только** если пользователь включил «Match content frame rate» (`DisplayManager.getMatchContentFrameRateUserPreference() == MATCH_CONTENT_FRAMERATE_ALWAYS`), при non-seamless переключении показать UI и подождать `onDisplayChanged` + ~2 с; сброс `setFrameRate(0f, …)` на паузе/конце; не вызывать часто. Legacy-путь `WindowManager.LayoutParams.preferredDisplayModeId` (+ `Display.getSupportedModes()`, `Display.Mode.getAlternativeRefreshRates()` API 31) — когда нужна смена разрешения или «тяжёлый modeset на Android TV» (док прямо называет этот кейс); `preferredRefreshRate` игнорируется для поверхностей с `setFrameRate`. Важно для libVLC: `setFrameRate` **не действует**, если `Surface` идёт напрямую в MediaCodec, а не в композитор (примечание в reference `Surface`) — значит на TV практичнее `preferredDisplayModeId` по `MediaInfo` потока (как делает jellyfin-androidtv для refresh-rate switching). TV-страница: [Match content frame rate](https://developer.android.com/training/tv/playback/framerate) (там же — Android 16 `MediaQualityManager`/picture profiles «Filmmaker mode»).

### HDR / Dolby Vision
- [HDR video playback (developer guide)](https://developer.android.com/media/grow/hdr-playback) — `display.hdrCapabilities.supportedHdrTypes` (`HDR_TYPE_HDR10/HLG/DOLBY_VISION/HDR10_PLUS`), с Android 13 HLG10 — минимум; без ExoPlayer: `MediaCodecList.findDecoderForFormat(format с KEY_PROFILE = HEVCProfileMain10HDR10 / AV1ProfileMain10 …)`; только `SurfaceView`.
- [AOSP: HDR video playback](https://source.android.com/docs/core/display/hdr) — профили `HEVCProfileMain10HDR10(+)`, `VP9Profile2HDR(10Plus)`, Dolby Vision через MIME `video/dolby-vision` + `DolbyVisionProfileDvheStn/Dtr/St…`; **только tunneled-декодеры гарантируют HDR**; `CodecCapabilities.isFormatSupported` не учитывает требование tunneled; `KEY_HDR_STATIC_INFO`/`KEY_HDR10_PLUS_INFO`. Reference: [Display.HdrCapabilities](https://developer.android.com/reference/android/view/Display.HdrCapabilities), [Display.Mode](https://developer.android.com/reference/android/view/Display.Mode), [MediaCodecInfo.CodecCapabilities](https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities) (`isFeatureSupported(FEATURE_TunneledPlayback/SecurePlayback)`, `profileLevels`, `videoCapabilities.areSizeAndRateSupported(w,h,fps)`, `MediaCodecInfo.isHardwareAccelerated()` API 29). HDR/DV passthrough на уровне ОС: libVLC сам отдаёт поток в MediaCodec (`mediacodec_ndk`) — приложение лишь выбирает, что direct-play'ить, через DeviceProfile.

### Аудио-возможности и passthrough
- [Audio capabilities (TV)](https://developer.android.com/training/tv/playback/audio-capabilities) — API 33+: `AudioManager.getDirectPlaybackSupport(AudioFormat(E_AC3, 5.1, 48 kHz), AudioAttributes(USAGE_MEDIA)) != DIRECT_PLAYBACK_NOT_SUPPORTED`, `getDirectProfilesForAttributes()` (только текущий маршрут, без транскодинга фреймворком); API 29+: `AudioTrack.isDirectPlaybackSupported()` (до API 33 учитывает все выходы, в т.ч. неактивные — ложные срабатывания при BT); API 23/31: `AudioDeviceInfo.getEncodings()/getAudioProfiles()` через `AudioTrack.getRoutedDevice()`; слушать `AudioRouting.OnRoutingChangedListener`/`AudioDeviceCallback`/`ACTION_HDMI_AUDIO_PLUG`; Bluetooth — только PCM. Системная настройка Android TV «Surround sound: Auto/Manual/Never» влияет на результаты (в документе не описана — не проверено).
- **Готовая реализация DeviceProfile из возможностей устройства** (jellyfin-androidtv): `app/src/main/java/org/jellyfin/androidtv/util/profile/deviceProfile.kt` (`buildDeviceProfile {}` из SDK: `maxStreamingBitrate`, DirectPlay/Transcoding/Codec/Subtitle-профили, `VideoRangeType` исключения для DOVI/HDR10/HDR10+ по результатам тестов), `MediaCodecCapabilitiesTest.kt` (`MediaCodecList(REGULAR_CODECS)`; `supportsHevc/Main10/DolbyVision/DolbyVisionEL/HDR10/HDR10Plus`, `supportsAV1*`, `supportsAVCHigh10`, уровни, `getMaxResolution(mime)`, флаг софт-кодеков), `codec/{Av1,Avc,Hevc}CodecCapabilities.kt`, `codec/MediaCodecQuery.kt`, `codec/audioPassthrough.kt` (`isPassthroughAudioAvailable(context, mimetype)` через `AudioFormat` stereo/5.1), `KnownDefects.kt` (обход прошивочных багов). Это лучший открытый образец «MediaCodecList → Jellyfin DeviceProfile»; для libVLC те же проверки MediaCodec остаются валидными (libVLC использует те же аппаратные декодеры), плюс список софт-декодируемого FFmpeg'ом libVLC.

---

## 6. Загрузки / офлайн

- [Data and file storage overview](https://developer.android.com/training/data-storage) — для офлайн-кэша видео: **app-specific external storage** `context.getExternalFilesDirs(null)[i]` / `getExternalFilesDir(DIRECTORY_MOVIES)` — без разрешений (scoped storage с API 29), удаляется при деинсталляции, может быть на съёмной SD (проверять `Environment.getExternalStorageState(dir) == MEDIA_MOUNTED`); shared storage/MediaStore — только если файлы должны быть видны другим приложениям (для GPL-клиента скорее нет). `MANAGE_EXTERNAL_STORAGE` не нужен.
- [DownloadManager](https://developer.android.com/reference/android/app/DownloadManager) — системный сервис: очередь, ретраи, продолжение после смерти процесса, уведомления; `Request.setDestinationUri(file-uri в app-specific dir)` / `setDestinationInExternalFilesDir`, `addRequestHeader("Authorization", "MediaBrowser Token=…")`, `setAllowedOverMetered`, `setNotificationVisibility`; прогресс через `query(Query().setFilterById(id))` + `COLUMN_BYTES_DOWNLOADED_SO_FAR/COLUMN_TOTAL_SIZE_BYTES`; `ACTION_DOWNLOAD_COMPLETE`; ограничения — нет pause/resume API, только заголовки для авторизации, нет контроля над параллелизмом/бэкоффом.
- **Как сделано в Findroid** (`core/src/main/java/dev/jdtech/jellyfin/utils/DownloaderImpl.kt`): интерфейс `Downloader { downloadItem(item, sourceId, storageIndex); cancelDownload; deleteItem; getProgress(downloadId) }`; видео и внешние потоки субтитров — через **`android.app.DownloadManager`** (`Request(source.path.toUri()).setDestinationUri(path)` в `context.getExternalFilesDirs(null)[storageIndex]`, `VISIBILITY_VISIBLE_NOTIFY_COMPLETED` для основного файла и `VISIBILITY_HIDDEN` для сабов), прогресс опросом `DownloadManager.Query`; картинки — через **WorkManager** (`workManager.enqueue(downloadImagesRequest)`); метаданные офлайн-библиотеки — в Room (`data/.../repository/JellyfinRepositoryOfflineImpl.kt` — отдельная реализация репозитория для офлайн-режима). Направление «WorkManager + OkHttp» даёт pause/resume (Range), лимит параллельности и контроль ошибок, но требует собственного foreground-уведомления (`dataSync` тип с таймаутами на Android 15) — компромисс Findroid (DownloadManager для тяжёлых файлов) практичен для первого релиза.
- Шифрование не требуется (ваше решение), но на TV стоит учесть: внешние диски по USB на Android TV доступны как `getExternalFilesDirs()` только если смонтированы как adoptable/portable (не проверено).

---

## 7. Дистрибуция и лицензирование

### Google Play
- GPL-приложения в Play разрешены де-факто: VLC (`org.videolan.vlc`), Jellyfin for Android TV (`org.jellyfin.androidtv`, GPL-2.0, в README есть бейдж Google Play), Findroid (GPL-3.0, Google Play + Amazon Appstore + F-Droid + IzzyOnDroid), Kodi. Отдельной политики Play про GPL нет; конфликт GPLv3 с условиями стора исторически касался Apple App Store, не Play. Страницы Play напрямую (play.google.com) открыть не удалось (таймаут) — (не проверено).
- [Distribute to Android TV](https://developer.android.com/training/tv/publishing/distribute) — соответствие TV quality guidelines; **с 1 августа 2026 — 64-bit + 16 KB**; тот же package name, что и у мобильной версии (единый AAB, отдельный TV-трек по желанию); листинг: ≥1 TV-скриншот, **TV banner** и упоминание «Android TV» в описании; opt-in: Play Console → Setup → Advanced settings → Form factors → Add release type → Android TV; после публикации Google ревьюит по TV-критериям и присылает письмо с замечаниями (типично: D-pad недоступность элементов, отсутствие баннера/LEANBACK_LAUNCHER, тач-зависимость).
- [Graphic assets (Play Console help)](https://support.google.com/googleplay/android-developer/answer/9866151) — **TV banner для листинга: 1280×720 px, JPEG/24-bit PNG без альфы** (не путать с in-app `android:banner` 320×180 xhdpi); TV-скриншоты: JPEG/PNG, 320–3840 px, рекомендовано 1920×1080 16:9; иконка 512×512 PNG; feature graphic 1024×500.
- [16 KB page sizes](https://developer.android.com/guide/practices/page-sizes) — требование Play для target 35+ (с 01.11.2025; с 01.02.2027 без поддержки нельзя выпускать обновления); проверка: `check_elf_alignment.sh app.apk`, `zipalign -v -c -P 16 4 app.apk`, `llvm-objdump -p lib.so | grep LOAD` (align 2**14); AGP ≥8.5.1 + NDK ≥r28; эмулятор `google-tv-ps16k`; `adb shell getconf PAGE_SIZE` → 16384. **libVLC**: баг-репорт [vlc-android#3242](https://code.videolan.org/videolan/vlc-android/-/issues/3242) (страница закрыта анти-бот-защитой, содержимое из поисковой выдачи — не проверено) сообщал о невыровненных `libvlc.so/libvlcjni.so/libc++_shared.so` в 3.6.2; исправление упоминалось в контексте 3.6.5+ — обязательно проверить 3.7.7 скриптом выше.
- Подпись: `apksigner`, v2/v3 схема, один release-ключ для Play/GitHub/F-Droid (reproducible) — ключ нельзя менять, иначе обновления между источниками ломаются.

### F-Droid
- [Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/) — FLOSS-лицензия; Google Play Services/Firebase/Crashlytics/проприетарные SDK **строго запрещены** (нужен `libre`-flavor без них); prebuilt-бинарники допустимы, если свободно лицензированы и из доверенных источников: Debian main (предпочтительно), **Maven Central, Google Maven**, JitPack, OSS Sonatype и др.; Anti-Features обязательно декларировать.
- [Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds/) — если сборка F-Droid побайтно совпадает с вашим APK, публикуется APK **с вашей подписью** (`Binaries:` + `AllowedAPKSigningKeys`); типичные проблемы: timestamps, build paths в native-коде, версии NDK/JDK, `baseline.prof`, PNG crunch, R8-недетерминизм, `apksigner` ≥35.0.0-rc1 (использовать 34), `vcsInfo`.
- **libVLC в F-Droid — фактическое положение дел** (из [metadata/org.videolan.vlc.yml](https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/org.videolan.vlc.yml)): VLC 3.7.1 (versionCode 13070108) собирается F-Droid'ом так: `sed -i -e "/':libvlcjni/d" -e "/':medialibrary/d" ../../settings.gradle` — т.е. **libVLC и medialibrary берутся как prebuilt AAR с Maven Central**, а не компилируются (`compile.sh` использовался до 3.5.x). AntiFeatures нет, сборки per-ABI. Следовательно, `org.videolan.android:libvlc-all` из Maven Central для F-Droid приемлем (VideoLAN — апстрим, лицензия LGPL). Аналогично Findroid ([metadata/dev.jdtech.jellyfin.yml](https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/dev.jdtech.jellyfin.yml)) тянет prebuilt `dev.jdtech.mpv:libmpv` (собран из исходников в [jarnedemeulemeester/libmpv-android](https://github.com/jarnedemeulemeester/libmpv-android), MIT) и публикуется reproducible (`Binaries: …findroid-v%v-libre-arm64-v8a.apk`, flavor `libre`, только arm64 из-за размера). Nova Video Player ([metadata/org.courville.nova.yml](https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/org.courville.nova.yml)) — собирает ffmpeg/dav1d/openssl из исходников внутри своего репо, AntiFeature `NonFreeNet` (TMDB). jellyfin-androidtv в F-Droid ([metadata](https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/org.jellyfin.androidtv.yml)) — reproducible с `AllowedAPKSigningKeys`, prebuilt `org.jellyfin.media3:media3-ffmpeg-decoder` с Maven Central. Обсуждение политики по AAR: [форум F-Droid «Can applications integrate pre-compiled AAR libraries?»](https://forum.f-droid.org/t/policy-clarification-can-applications-integrate-pre-compiled-aar-libraries/33624) — формального запрета нет, предпочтение сборке из исходников, Maven Central в allowlist, желательны provenance/SBOM.
- Практические требования к репо для F-Droid: тегированные релизы, версия в читаемом месте (`UpdateCheckMode: Tags`), fastlane-метаданные (`fastlane/metadata/android/*`), `libre` flavor без Play-зависимостей, детерминированная сборка.

### IzzyOnDroid, GitHub Releases, Obtainium
- [IzzyOnDroid App Inclusion Policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/) — FLOSS, APK из тегированных GitHub/GitLab/Codeberg-релизов (новые приложения только так), подпись release-ключом, без `debuggable/testOnly`, без трекеров/рекламы, **лимит ~30 MB на APK** (держат до 3 версий) — для libVLC-приложения нужны **per-ABI split APK** (fat APK с 4 ABI libVLC в 30 MB не влезет).
- [Obtainium](https://github.com/ImranR98/Obtainium) (GPL-3.0) — обновления напрямую из GitHub Releases/GitLab/Codeberg/F-Droid/IzzyOnDroid; поддерживает deep-link «Add to Obtainium» (см. wiki проекта); Streamyfin публикует так. Для TV: Obtainium на Android TV устанавливается side-load'ом (не проверено).

### Amazon Fire TV
- [Fire TV vs Android TV development](https://developer.amazon.com/docs/fire-tv/differences-from-android-tv-development.html) — Fire TV уважает `LEANBACK_LAUNCHER`, частично поддерживает Leanback (кроме `SearchFragment`); **нет Google Play Services/Firebase**, голосовой поиск — через Alexa; аудиофокус требует `MediaButtonReceiver` в манифесте; Fire TV имеет кнопки RW/FF/Menu.
- [Fire OS overview (версии)](https://developer.amazon.com/docs/fire-tv/fire-os-overview.html) — Fire OS 5 = Android 5.1 (22), 6 = 7.1 (25), 7 = Android 9 (28), 8 = Android 10/11 (29/30; Stick 4K Max 2nd gen, Omni QLED…), 14 = Android 12–14 (31–34; телевизоры 2026), 16 = Android 15/16; новые стики 2026 — **Vega OS** (не Android, свой SDK — ваше приложение туда не попадёт). Следствие: minSdk 25–28 покрывает парк Fire TV; PiP на Fire TV нет (API<34 у большинства).
- Домашний экран Fire TV: нет Google TV-каналов/Watch Next; «Continue Watching» на Fire TV — через Amazon Content Personalization/watch activity (документация в разделе Vega: [intro-to-vega-content-personalization](https://developer.amazon.com/docs/vega/0.24/intro-to-vega-content-personalization.html); для классических Android-приложений Fire TV — отдельный Fire TV Launcher Integration (не проверено)).
- [Appstore details: image assets](https://developer.amazon.com/docs/app-submission/appstore-details.html) — Fire TV: иконка **1280×720 PNG без прозрачности** (safe area 882×448), скриншоты 3–10 шт 1920×1080, фон 1920×1080 (safe area 1214×830); планшеты: 114×114 и 512×512 PNG.
- [Connect to Fire TV through ADB](https://developer.amazon.com/docs/fire-tv/connecting-adb-to-device.html) — Settings → My Fire TV → Developer Options (7 нажатий на «About» при необходимости) → ADB Debugging + Apps from Unknown Sources; `adb connect <ip>:5555`.

### Иконки и баннеры (сводка)
- In-app: `android:banner` 320×180 px (xhdpi), текст в картинке, по баннеру на язык; launcher icon ≥160×160 xhdpi (adaptive icon как обычно).
- Play листинг: TV banner 1280×720; скриншоты TV 1920×1080; иконка 512×512; feature graphic 1024×500.
- Amazon: иконка Fire TV 1280×720 PNG; фон 1920×1080.

---

## 8. Тестирование

- **Эмулятор**: образы `android-tv` и `google-tv` для API 33/34/36 (+ `google-tv-ps16k` API 36 для 16 KB) — создаются через Device Manager (категория TV, профили «Television 1080p/4K»). Для low-RAM-тестов — AVD с 1–1.5 GB RAM и `isLowRamDevice`-поведением (ro.config.low_ram — не проверено).
- **Реальный ТВ**: Android 13+ — Wireless debugging (`adb pair ip:port`), иначе Developer options → USB/Network debugging и `adb connect ip:5555` ([adb](https://developer.android.com/tools/adb)); Fire TV — см. выше. Полезно: `adb shell input keyevent KEYCODE_DPAD_RIGHT`, `adb shell dumpsys media_session`, `adb shell dumpsys SurfaceFlinger | grep -i "refresh"` (не проверено).
- **Compose UI-тесты с D-pad**: [Test focus navigation](https://developer.android.com/develop/ui/compose/touch-input/focus/testing-focus) — `createComposeRule()`, задать стартовый фокус `performClick()`/`requestFocus()`, затем `node.performKeyInput { pressKey(Key.DirectionDown) }` и `target.assertIsFocused()`/`assertIsNotFocused()`; `withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) }`; события внутри одного `performKeyInput` батчатся без рекомпозиции между ними — разделять на несколько вызовов. Зависимости: `androidx.compose.ui:ui-test-junit4`, `debugImplementation ui-test-manifest` ([Compose testing](https://developer.android.com/develop/ui/compose/testing)); `performKeyInput` в актуальном reference без `@ExperimentalTestApi` (раньше была) — [composables.com reference](https://composables.com/docs/androidx.compose.ui/ui-test/functions/performKeyInput). Для end-to-end на TV — Macrobenchmark/UIAutomator (`pressDPadRight()`), как в JetStream `benchmark`.
- **Jellyfin для разработки**: публичный демо-сервер [https://demo.jellyfin.org/stable](https://demo.jellyfin.org/stable) (ссылка «Try the Demo» на jellyfin.org), логин `demo`, пароль пустой ([подтверждение модератора на форуме](https://forum.jellyfin.org/t-jellyfin-demo-credentials)); демо периодически сбрасывается, транскодинг может быть ограничен. Локально — Docker: [Jellyfin in a container](https://jellyfin.org/docs/general/installation/container/):
  ```bash
  docker run -d --name jellyfin -p 8096:8096/tcp -p 7359:7359/udp \
    -v /path/config:/config -v /path/cache:/cache \
    --mount type=bind,source=/path/media,target=/media \
    --restart=unless-stopped jellyfin/jellyfin   # или ghcr.io/jellyfin/jellyfin
  ```
  (7359/udp — автодискавери, которое использует `jellyfin-sdk` `discovery`; HW-транскодинг — проброс `/dev/dri`). Тестовый контент — свободные фильмы Blender: [Big Buck Bunny (480p/720p/1080p, mov/avi/ogg)](https://download.blender.org/peach/bigbuckbunny_movies/) + Sintel/Tears of Steel (не проверено); для HDR/DV/24p/passthrough-кейсов нужны собственные образцы (например, Dolby/Kodi sample-клипы — не проверено). Эмулятор Android TV API 33+ находит сервер в хост-сети через `10.0.2.2:8096`.
- Для TV-ревью перед публикацией — пройти чек-лист TV-DP/TV-OV/TV-PC из §1 на эмуляторе только с клавишами стрелок/Enter/Esc.

---

## Пробелы и непроверенное
- Статус 16 KB-выравнивания в `libvlc-all:3.7.7` — проверять локально (`zipalign -P 16`), первоисточник (issue 3242 на code.videolan.org) закрыт антибот-защитой.
- Содержимое `kotlin-sdk.jellyfin.org/migration/v1.7.html` (OkHttp по умолчанию) — из поисковой выдачи, страница не открывалась напрямую.
- Точные sp-значения TV-типографики (на странице — только изображение).
- Google Play страницы приложений (VLC, Jellyfin ATV) — не открылись по таймауту; факт наличия подтверждён бейджами в README проектов.
- Отдельного Fire TV API «Continue Watching» для классических Android-приложений (вне Vega) — не найдено/не проверено.
- Android Studio Rabbit 1: какой AGP в комплекте — не указано на странице релизов (AGP 9.4.0 — текущий stable по странице AGP; оба Jellyfin-клиента уже на 9.4.1).

---

# Часть 5. Доменный слой воспроизведения

_Исходный заголовок: Jellyfin: доменный слой воспроизведения — DeviceProfile, транскодирование, субтитры/аудио, изображения, UX, экосистема, тестовые медиа

Дата исследования: 2026-10-11. Все ссылки ниже проверены (HTTP 200 или прочитаны), кроме помеченных «(не проверено)». Исходники клиентов/сервера скачаны с raw.githubusercontent.com и прочитаны локально; цитаты идентификаторов — дословные.

## 0. Контекст версий (важно для планирования)

- Актуальный сервер: **Jellyfin 12.2** (релиз 05.10.2026) — [GitHub releases/latest](https://github.com/jellyfin/jellyfin/releases/latest). Публичный демо-сервер тоже на 12.2.0 (`GET https://demo.jellyfin.org/stable/System/Info/Public` → `"Version":"12.2.0"`).
- [Jellyfin 12.0 (07.09.2026)](https://jellyfin.org/posts/jellyfin-release-12.0) — префикс «10.» отброшен (10.12 → 12.0). Критично для клиентов: удалены legacy-префиксы `/emby/` и `/mediabrowser/`; устаревшие методы авторизации отключены по умолчанию; **удалён `GET /QuickConnect/Initiate` (только POST)**; `GetItems` применяет `recursive` при фильтрах; множественные версии эпизодов (resume привязан к версии); плейлисты построчно; настройки субтитров — per-library, добавлена запись VobSub, внешние субтитры могут вшиваться в MKS при транскоде; трикплей подхватывается, а не регенерируется; HLS: вариант `dvh1` для Dolby Vision P5; HLG tone-mapping по BT.2446B; FFmpeg 8.1; добавлена опция DeviceProfile для Android TV-боксов, не умеющих повёрнутое видео (ср. `VideoRotation`/`VideoRotationNotSupported` ниже). Сервер теперь отдаёт версию `12.0.0` — проверьте парсеры версий.
- [Jellyfin 10.11.0 (19.10.2025)](https://jellyfin.org/posts/jellyfin-release-10.11.0/) — EF Core база; **`OnPlaybackStart/Progress/Stopped` помечены deprecated → использовать `ReportPlaybackStart/Progress/Stopped`** (`POST /Sessions/Playing[...]`); рейтинги переехали в `ratingScore`; сервер может отвечать `503` при старте/остановке — уважать `Retry-After`; media segment providers настраиваются per-library; лирика получила word-level cues; FFmpeg 7.1; HDR10+ детект и выборочное удаление DV/HDR10+ метаданных.
- [Jellyfin 10.10.0](https://jellyfin.org/posts/jellyfin-release-10.10.0/) — появились Media Segments (нужен плагин-провайдер), keyframe-only trickplay, software tonemapping HDR10/HLG/DV, DV Profile 10 (предварительно), AC-4.
- [Jellyfin 10.9.0](https://jellyfin.org/posts/jellyfin-release-10.9.0/) — трикплей встроен в сервер («requires explicit client support»), HLS с Opus/FLAC/ALAC, DTS/TrueHD enforcement, нормализация громкости, лирика, DV remux, AV1 encoding, PIN-логин удалён (Quick Connect остался), DLNA вынесен в плагин.
- OpenAPI: [api.jellyfin.org](https://api.jellyfin.org/) (ReDoc), JSON стабильной спеки — [jellyfin-openapi-stable.json](https://api.jellyfin.org/openapi/jellyfin-openapi-stable.json). Kotlin SDK: [jellyfin-sdk-kotlin](https://github.com/jellyfin/jellyfin-sdk-kotlin).

---

## 1. DeviceProfile — глубокий разбор

### 1.1. Модель на сервере (точные поля и enum'ы)

- [MediaBrowser.Model/Dlna/DeviceProfile.cs](https://github.com/jellyfin/jellyfin/blob/master/MediaBrowser.Model/Dlna/DeviceProfile.cs) — поля: `Name`, `Id`, `MaxStreamingBitrate` (default `8000000`), `MaxStaticBitrate` (default `8000000`), `MusicStreamingTranscodingBitrate` (default `128000`), `MaxStaticMusicBitrate` (`8000000`), `DirectPlayProfiles[]`, `TranscodingProfiles[]`, `ContainerProfiles[]`, `CodecProfiles[]`, `SubtitleProfiles[]`. Дефолты низкие — клиент обязан задать свои.
- `DirectPlayProfile`: `Container` (CSV, пустая строка = любой), `AudioCodec`, `VideoCodec`, `Type` (`DlnaProfileType`: Audio/Video/Photo/Subtitle/Lyric).
- `TranscodingProfile`: `Container`, `Type`, `VideoCodec`, `AudioCodec`, `Protocol` (`MediaStreamProtocol`: `http`=0, `hls`=1), `EstimateContentLength`, `EnableMpegtsM2TsMode`, `TranscodeSeekInfo` (`Auto`/`Bytes`), `CopyTimestamps`, `Context` (`EncodingContext`: `Streaming`=0, `Static`=1 — Static используется для скачивания/оффлайна), `EnableSubtitlesInManifest`, `MaxAudioChannels` (строка!), `MinSegments`, `SegmentLength`, `BreakOnNonKeyFrames`, `Conditions[]`, `EnableAudioVbrEncoding` (default true). Порядок кодеков в `VideoCodec`/`AudioCodec` = приоритет.
- `ContainerProfile`: `Type`, `Conditions[]`, `Container`, `SubContainer`.
- `CodecProfile`: `Type` (`CodecType`: Video / VideoAudio / Audio), `Conditions[]`, `ApplyConditions[]` (условия, при которых профиль вообще применяется), `Codec`, `Container`, `SubContainer`.
- `ProfileCondition`: `Condition` (`ProfileConditionType`: `Equals`=0, `NotEquals`=1, `LessThanEqual`=2, `GreaterThanEqual`=3, `EqualsAny`=4), `Property`, `Value`, `IsRequired`.
- `ProfileConditionValue` ([файл](https://github.com/jellyfin/jellyfin/blob/master/MediaBrowser.Model/Dlna/ProfileConditionValue.cs)): `AudioChannels, AudioBitrate, AudioProfile, Width, Height, Has64BitOffsets, PacketLength, VideoBitDepth, VideoBitrate, VideoFramerate, VideoLevel, VideoProfile, VideoTimestamp, IsAnamorphic, RefFrames, NumAudioStreams, NumVideoStreams, IsSecondaryAudio, VideoCodecTag, IsAvc, IsInterlaced, AudioSampleRate, AudioBitDepth, VideoRangeType, NumStreams, VideoRotation`.
- `SubtitleProfile`: `Format`, `Method`, `DidlMode`, `Language`, `Container`.
- [SubtitleDeliveryMethod.cs](https://github.com/jellyfin/jellyfin/blob/master/MediaBrowser.Model/Dlna/SubtitleDeliveryMethod.cs): `Encode = 0` (burn-in при транскоде), `Embed = 1` (остаются в контейнере, клиент сам рендерит), `External = 2` (отдельный HTTP-поток `/Subtitles/.../Stream.{fmt}`), `Hls = 3` (отдельная WebVTT-дорожка в HLS-манифесте, только при `PlayMethod.Transcode`), `Drop = 4`.
- `VideoRangeType` ([файл](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Data/Enums/VideoRangeType.cs)): `Unknown, SDR, HDR10, HLG, DOVI, DOVIWithHDR10, DOVIWithHLG, DOVIWithSDR, DOVIWithEL, DOVIWithHDR10Plus, DOVIWithELHDR10Plus, DOVIInvalid, HDR10Plus`. `DOVI` = «чистый» Profile 5 (IPTPQc2), который без DV-декодера даёт зелёно-фиолетовую картинку; `DOVIWithHDR10` = P8.1, `DOVIWithEL` = P7 и т.п.
- `TranscodeReason` ([файл](https://github.com/jellyfin/jellyfin/blob/master/MediaBrowser.Model/Session/TranscodeReason.cs), flags, сервер отдаёт в `MediaSourceInfo.TranscodeReasons`): `ContainerNotSupported, VideoCodecNotSupported, AudioCodecNotSupported, SubtitleCodecNotSupported, AudioIsExternal, SecondaryAudioNotSupported, StreamCountExceedsLimit, VideoProfileNotSupported, VideoRangeTypeNotSupported, VideoCodecTagNotSupported, VideoLevelNotSupported, VideoResolutionNotSupported, VideoBitDepthNotSupported, VideoFramerateNotSupported, VideoRotationNotSupported, RefFramesNotSupported, AnamorphicVideoNotSupported, InterlacedVideoNotSupported, AudioChannelsNotSupported, AudioProfileNotSupported, AudioSampleRateNotSupported, AudioBitDepthNotSupported, ContainerBitrateExceedsLimit, VideoBitrateNotSupported, AudioBitrateNotSupported, UnknownVideoStreamInfo, UnknownAudioStreamInfo, DirectPlayError`. Swiftfin держит локализованные названия всех причин в [TranscodeReason.swift](https://github.com/jellyfin/Swiftfin/blob/main/Shared/Extensions/JellyfinAPI/TranscodeReason.swift); jellyfin-web показывает их в оверлее статистики ([playerstats.js](https://github.com/jellyfin/jellyfin-web/blob/master/src/components/playerstats/playerstats.js), `session.TranscodingInfo.TranscodeReasons`).

### 1.2. Как сервер решает (StreamBuilder)

- [MediaBrowser.Model/Dlna/StreamBuilder.cs](https://github.com/jellyfin/jellyfin/blob/master/MediaBrowser.Model/Dlna/StreamBuilder.cs) — ключевые факты:
  - Жёсткие белые списки HLS независимо от профиля: `_supportedHlsVideoCodecs = ["h264", "hevc", "vp9", "av1"]`, `_supportedHlsAudioCodecsTs = ["aac", "ac3", "eac3", "mp3"]`, `_supportedHlsAudioCodecsMp4 = ["aac", "ac3", "eac3", "mp3", "alac", "flac", "opus", "dts", "truehd"]`. Итог: контейнер `mp4` (fMP4 HLS) даёт больше passthrough-аудио (DTS/TrueHD/FLAC/Opus), чем `ts`. Кодек вне списка → `VideoCodecNotSupported`/`AudioCodecNotSupported` даже если вы его объявили. Swiftfin из-за этого заводит отдельный TranscodingProfile для VC1, чтобы получить direct stream (комментарий в коде: «Necessary to avoid server-imposed HLS video restrictions in StreamBuilder _supportedHlsVideoCodecs»).
  - Комментарий в коде: «As of 10.10 codebase, SupportsDirectPlay is always true because the MediaSourceInfo initializes this key as true». Решение direct play / direct stream / transcode: если `transcodeReasons == 0` и битрейт не превышает `MaxStreamingBitrate` → DirectPlay; иначе если `SupportsDirectStream` и причины ⊆ `DirectStreamReasons` (= аудио-причины | `ContainerNotSupported` | `VideoCodecTagNotSupported`) → DirectStream (remux/перекодирование только аудио); превышение битрейта → `ContainerBitrateExceedsLimit` → transcode. `UseMostCompatibleTranscodingProfile` (поле MediaSourceInfo, обычно для Live TV) заставляет брать «самый совместимый» профиль.
  - Выбор доставки субтитров (`GetSubtitleProfile`): (1) если можно «embed» (не transcode, либо transcode не по HLS и контейнер поддерживает) — ищется `Embed`-профиль с тем же `Format` и совпадением текст/графика (`MediaStream.IsTextFormat`), затем `Embed` с конвертируемым текстовым форматом (`SupportsSubtitleConversionTo`; **из `ass` конвертировать нельзя**); (2) иначе `External`/`Hls`-профиль без конверсии, потом с конверсией (`Hls` принимается только при `PlayMethod.Transcode`); (3) иначе fallback `Method = Encode` (burn-in). Любой `SubtitleProfile.Method`, кроме `Drop/External/Embed`, добавляет `SubtitleCodecNotSupported` в причины — именно так выбор ASS/PGS с `Encode` принуждает транскод.
  - `IsTextFormat` ([MediaStream.cs](https://github.com/jellyfin/jellyfin/blob/master/MediaBrowser.Model/Entities/MediaStream.cs)): графическими считаются `pgs*`, `dvdsub`, `vobsub`, `dvbsub`, `sup`, `sub` (кроме `microdvd`), всё остальное — текст.

### 1.3. Профили реальных клиентов (точные файлы)

**Jellyfin Android TV (ExoPlayer/Media3)**
- [app/src/main/java/org/jellyfin/androidtv/util/profile/deviceProfile.kt](https://github.com/jellyfin/jellyfin-androidtv/blob/master/app/src/main/java/org/jellyfin/androidtv/util/profile/deviceProfile.kt) — строится через DSL `buildDeviceProfile {}` из Kotlin SDK. Содержимое: `name = "AndroidTV-Default"`; `maxStaticBitrate = maxStreamingBitrate = maxBitrate` (из настроек, Mbit→bit, «0» запрещён); три TranscodingProfile: Video `TS`+`HLS` (`audioCodec` из `aac, ac3, eac3, mp3`), Video `MP4`+`HLS` (`aac, ac3, eac3, mp3, alac, flac, opus, dts`), оба `copyTimestamps = false`, `enableSubtitlesInManifest = true`, `videoCodec = [hevc?, h264]`; Audio `TS`+`HLS` AAC. DirectPlayProfile Video: контейнеры `ASF, HLS, M4V, MKV, MOV, MP4, OGM, OGV, TS, VOB, WEBM, WMV, XVID`, видео `AV1, H264, HEVC, MPEG, MPEG2VIDEO, VC1, VP8, VP9`, аудио — отфильтрованный список (passthrough-кодеки AC3/EAC3/DTS/TRUEHD включаются только если разрешены). CodecProfile'ы: H264 `VIDEO_PROFILE inCollection [high, main, baseline, constrained baseline, (high 10)]`, `VIDEO_LEVEL <= level` с `applyConditions` по профилю, `REF_FRAMES <= 12` при `WIDTH >= 1200` и `<= 4` при `WIDTH >= 1900`; HEVC main/main 10 + level; AV1/VC1 через трюк `VIDEO_PROFILE equals "none"` для полного исключения; `WIDTH/HEIGHT <=` максимального разрешения из MediaCodec; HDR-исключения через `VIDEO_RANGE_TYPE notEquals "A|B|C"` + `applyConditions inCollection` (в коде есть пояснение, что `notEquals` всегда проваливает ConditionProcessor, поэтому через `applyConditions` профиль активируется только для неподдерживаемых диапазонов); `CodecType.VIDEO_AUDIO`: `AUDIO_CHANNELS <= 2` при даунмиксе, иначе `8`. Субтитры: `VTT/WEBVTT` — `Embed+Hls+External` (комментарий: сервер в HLS умеет только WebVTT, остальные текстовые конвертировались бы в VTT — нежелательно); `SRT/SUBRIP/TTML` — `Embed+External`; `DVBSUB/DVDSUB/IDX` — `Embed+Encode`; `PGS/PGSSUB` — `Embed` только если `pgsDirectPlay`, иначе `Encode`; `ASS/SSA` — `Encode` + `Embed/External` только если `assDirectPlay` (через libass-расширение).
- [util/profile/codec/audioPassthrough.kt](https://github.com/jellyfin/jellyfin-androidtv/blob/master/app/src/main/java/org/jellyfin/androidtv/util/profile/codec/audioPassthrough.kt) — проверка `AudioCapabilities.isPassthroughPlaybackSupported` для AC3/EAC3/DTS/TrueHD; режимы `BitstreamAudioMode.AUTO/ENABLE/DISABLE` на каждый формат; `AudioBehavior.DIRECT_STREAM / DOWNMIX_TO_STEREO`.
- [util/profile/KnownDefects.kt](https://github.com/jellyfin/jellyfin-androidtv/blob/master/app/src/main/java/org/jellyfin/androidtv/util/profile/KnownDefects.kt) — список устройств с багами (Fire TV 4K: DoVi+HDR10+; Shield 2019: незаявленный DV Profile 7). Хороший пример «quirks»-таблицы.
- [constant/QualityProfiles.kt](https://github.com/jellyfin/jellyfin-androidtv/blob/master/app/src/main/java/org/jellyfin/androidtv/constant/QualityProfiles.kt) — ступени качества: `200,180,140,120,110,100,90,80,70,60,50,40,30,20,15,10,5,3,2,1 Mbit; 0.72, 0.42` (720/420 kbit).
- История libVLC: исторический профиль [app/src/main/java/org/jellyfin/androidtv/util/profile/LibVlcProfile.kt @ v0.16.11](https://github.com/jellyfin/jellyfin-androidtv/blob/v0.16.11/app/src/main/java/org/jellyfin/androidtv/util/profile/LibVlcProfile.kt) (`name = "AndroidTV-libVLC"`, `maxStreamingBitrate = 20_000_000`; TranscodingProfile `MKV`/H264/aac,mp3 с `copyTimestamps = true` (не HLS!); DirectPlay: почти все контейнеры (`M4V, 3GP, TS, MPEGTS, MOV, XVID, VOB, MKV, WMV, ASF, OGM, OGV, M2V, AVI, MPG, MPEG, MP4, WEBM, WTV`) и аудио (`AAC, MP3, MP2, AC3, EAC3, WMA, WMAV2, DCA, DTS, PCM, PCM_S16LE, PCM_S24LE, OPUS, FLAC, TRUEHD, AAC_LATM`); ContainerProfile AVI `VideoCodecTag NotEquals xvid`; субтитры все `Embed` + `SRT External`). Рядом — [ExoPlayerProfile.kt](https://github.com/jellyfin/jellyfin-androidtv/blob/v0.16.11/app/src/main/java/org/jellyfin/androidtv/util/profile/ExoPlayerProfile.kt) и [ProfileHelper.kt](https://github.com/jellyfin/jellyfin-androidtv/blob/v0.16.11/app/src/main/java/org/jellyfin/androidtv/util/profile/ProfileHelper.kt) той же версии.
- Удаление libVLC: [PR #3639 «Remove LibVLC»](https://github.com/jellyfin/jellyfin-androidtv/pull/3639) (nielsvanvelzen, merged 02.06.2024, milestone v0.17.0). Причины: последний стабильный libvlc-jni 3.5.1 (авг. 2022), непатченный CVE ([issue #3623 «CVE-2023-5217 - update libvlc»](https://github.com/jellyfin/jellyfin-androidtv/issues/3623), F-Droid), смешанный код двух плееров (утечки памяти, производительность), размер артефакта ~107 MB → 24 MB, новая playback-архитектура не планирует LibVLC-бэкенд. Последствия в форуме: [«Live TV no longer works on AndroidTV client without transcoding»](https://forum.jellyfin.org/t-live-tv-no-longer-works-on-androidtv-client-without-transcoding) (MPEG-2 с тюнеров ExoPlayer без софт-декодера не тянет), [«bring back libvlc»](https://forum.jellyfin.org/t-solved-bring-back-libvlc). Это прямой аргумент «за» ваш libVLC-клиент: софт-декод MPEG-2/VC-1/DTS-HD/TrueHD, ASS через libass, два трека субтитров, offset субтитров/аудио.

**Jellyfin Android (mobile, ExoPlayer + web)**
- [app/src/main/java/org/jellyfin/mobile/player/deviceprofile/DeviceProfileBuilder.kt](https://github.com/jellyfin/jellyfin-android/blob/master/app/src/main/java/org/jellyfin/mobile/player/deviceprofile/DeviceProfileBuilder.kt) — константы `MAX_STREAMING_BITRATE = 120000000`, `MAX_STATIC_BITRATE = 100000000`, `MAX_MUSIC_TRANSCODING_BITRATE = 384000`; контейнеры `mp4, fmp4, webm, mkv, mp3, ogg, wav, mpegts, flv, aac, flac, 3gp`, списки кодеков фильтруются по `MediaCodecList`, но `FORCED_AUDIO_CODECS` (PCM, alac, aac, ac3, eac3, dts, mlp, truehd) включаются всегда; TranscodingProfile `ts`/`h264`/`mp1,mp2,mp3,aac,ac3,eac3,dts,mlp,truehd`/`HLS` (+ `mkv` HLS, + audio `mp3` HTTP); CodecProfile `EQUALS_ANY VIDEO_PROFILE` из профилей устройства; субтитры `EXO_EMBEDDED_SUBTITLES = dvbsub, pgssub, srt, subrip, ttml`, `EXO_EXTERNAL_SUBTITLES = srt, subrip, ttml, vtt, webvtt`, `SUBTITLES_SSA = ssa, ass` добавляются при `exoPlayerDirectPlayAss`. **Профиль для внешнего плеера** `getExternalPlayerProfile()`: `DirectPlayProfile(type = VIDEO, container = "")` + AUDIO, пустые transcoding/codec-профили, все `EXTERNAL_PLAYER_SUBTITLES` как Embed+External, `maxStreamingBitrate = maxStaticBitrate = musicStreamingTranscodingBitrate = Int.MAX_VALUE` — фактически «играй всё».
- Интервал отчётов: [utils/Constants.kt](https://github.com/jellyfin/jellyfin-android/blob/master/app/src/main/java/org/jellyfin/mobile/utils/Constants.kt) `PLAYER_TIME_UPDATE_RATE = 10000L`, `CHAPTER_MARKING_UPDATE_DELAY = 1000L`, `SKIP_MEDIA_SEGMENT_UPDATE_DELAY = 1000L`; логика в [player/PlayerViewModel.kt](https://github.com/jellyfin/jellyfin-android/blob/master/app/src/main/java/org/jellyfin/mobile/player/PlayerViewModel.kt).

**Swiftfin (iOS/tvOS; VLCKit, MPV, AVPlayer)**
- Сборка: [Shared/Extensions/JellyfinAPI/DeviceProfile.swift](https://github.com/jellyfin/Swiftfin/blob/main/Shared/Extensions/JellyfinAPI/DeviceProfile.swift) — `DeviceProfile.build(for: videoPlayer, compatibilityMode:, maxBitrate:, maxResolution:)`; режимы `PlaybackCompatibility`: `.auto` (профили плеера), `.mostCompatible` (только `h264+aac` в `mp4`, [PlaybackCompatibility+Video.swift](https://github.com/jellyfin/Swiftfin/blob/main/Shared/Objects/PlaybackCompatibility/PlaybackCompatibility+Video.swift)), `.directPlay` (`forcedDirectPlayProfile = DirectPlayProfile(type: .video)` без ограничений = «всё direct play»), `.custom` (пользовательские профили, [PlaybackDeviceProfile.swift](https://github.com/jellyfin/Swiftfin/blob/main/Shared/Objects/PlaybackDeviceProfile.swift) → `CustomDeviceProfile{type, useAsTranscodingProfile, audio, video, container}`). `forceSubtitleBurnIn` → все `SubtitleFormat.allCases` с `.encode`. `maxBitrate` пишется сразу в `maxStaticBitrate`, `maxStreamingBitrate`, `musicStreamingTranscodingBitrate`.
- VLC-профиль: [Shared/Objects/VideoPlayerType/VideoPlayerType+VLC.swift](https://github.com/jellyfin/Swiftfin/blob/main/Shared/Objects/VideoPlayerType/VideoPlayerType+VLC.swift) — DirectPlay без ограничения контейнера; аудио `aac, ac3, alac, amr_nb, amr_wb, dts, eac3, flac, mp1, mp2, mp3, nellymoser, opus, pcm_alaw, pcm_bluray, pcm_dvd, pcm_mulaw, pcm_s16be, pcm_s16le, pcm_s24be, pcm_s24le, pcm_u8, speex, vorbis, wavpack, wmalossless, wmapro, wmav1, wmav2`; видео `av1 (если HW), dirac, dv, ffv1, flv1, h261, h263, h264, hevc, mjpeg, mpeg1video, mpeg2video, mpeg4, msmpeg4v1/v2/v3, prores, theora, vc1, vp8, vp9, wmv1, wmv2, wmv3`; TranscodingProfile `HLS`, container `mp4`, `isBreakOnNonKeyFrames: true`, `maxAudioChannels: "8"`, `minSegments: 2`, аудио `aac, ac3, alac, dts, eac3, flac, mp1, mp2, mp3, opus, vorbis`, видео `av1?, h263, h264, hevc, mjpeg, mpeg1video, mpeg2video, mpeg4, vp9`; отдельный профиль для `vc1`. Субтитры: `Embed` — `ass, cc_dec, dvbsub, dvdsub, libzvbi_teletextdec, mov_text, mpl2, pgssub, pjs, realtext, sami, ssa, subrip, subviewer, subviewer1, text, ttml, vplayer, vtt, xsub`; `External` — текстовые (`subrip` первым: «Unmatched text subtitles (ex: VTT) are converted to the first option (subrip)»); `Encode` — `dvbsub, dvdsub, pgssub, vtt, xsub`. CodecProfile'ы: `isAnamorphic != true`, `isInterlaced != true`, `videoRangeType equalsAny` (SDR/DOVIWithSDR всегда; HDR-типы при `hdrEnabled`), H264 `videoLevel <= 80`, HEVC `<= 175`.
- AVPlayer-профиль: [VideoPlayerType+AVPlayer.swift](https://github.com/jellyfin/Swiftfin/blob/main/Shared/Objects/VideoPlayerType/VideoPlayerType+AVPlayer.swift) — контейнеры `mp4/m4v`, `mov`, `3gp/3g2`, `avi(mjpeg)`; HEVC требует `videoCodecTag equalsAny hvc1, dvh1` (isRequired) и `videoFramerate <= 60`; субтитры: `Embed` только `cc_dec, ttml`, `Encode` графические, `Hls` для `vtt`; `enableSubtitlesInManifest: true`. Общие условия — [VideoPlayerType+Shared.swift](https://github.com/jellyfin/Swiftfin/blob/main/Shared/Objects/VideoPlayerType/VideoPlayerType+Shared.swift). Форматы — [SubtitleFormat.swift](https://github.com/jellyfin/Swiftfin/blob/main/Shared/Objects/MediaComponents/SubtitleFormat.swift) (`isText`, расширения: pgssub→`sup`, dvdsub→`sub`, mov_text→`tx3g`...).
- Битрейты: [PlaybackBitrate.swift](https://github.com/jellyfin/Swiftfin/blob/main/Shared/Objects/PlaybackBitrate/PlaybackBitrate.swift) (`auto=0, max=360 Mbps, 120, 80, 60, 40, 20, 15, 10, 8, 6, 4, 3, 1.5, 0.72, 0.42`, отдельный список для аудио), [PlaybackBitrateTestSize.swift](https://github.com/jellyfin/Swiftfin/blob/main/Shared/Objects/PlaybackBitrate/PlaybackBitrateTestSize.swift) (размер теста `1, 2.5, 5, 7.5, 10 MB`).

**Findroid (mpv, Android)** — [data/src/main/java/dev/jdtech/jellyfin/repository/JellyfinRepositoryImpl.kt](https://github.com/jarnedemeulemeester/findroid/blob/main/data/src/main/java/dev/jdtech/jellyfin/repository/JellyfinRepositoryImpl.kt): `DeviceProfile(name = "Direct play all", maxStaticBitrate = 1_000_000_000, maxStreamingBitrate = 1_000_000_000, codecProfiles = emptyList(), containerProfiles = emptyList(), directPlayProfiles = emptyList(), transcodingProfiles = emptyList(), subtitleProfiles = [srt EXTERNAL, ass EXTERNAL])` — т.е. mpv играет всё, сервер транскод не предлагает (пустые transcodingProfiles). Stream URL — `videosApi.getVideoStreamUrl(itemId, static = true, mediaSourceId)`; отчёты всегда `PlayMethod.DIRECT_PLAY`; локальные правила «просмотрено»: `<10%` → сброс, `>90%` → played. В дереве репо есть модуль `app/tv` (TV-UI в разработке).

**Streamyfin (React Native; mpv + ExoPlayer на TV)** — каталог [utils/profiles/](https://github.com/streamyfin/streamyfin/tree/develop/utils/profiles): [native.ts](https://github.com/streamyfin/streamyfin/blob/develop/utils/profiles/native.ts) (режимы аудио `auto/stereo/5.1/passthrough` → `MaxAudioChannels` `"6"/"2"/"6"/"8"`; mpv-аудио `aac,mp3,flac,opus,vorbis,ac3,eac3,dts,truehd,wma`; ExoPlayer-профиль с `org.jellyfin.media3:media3-ffmpeg-decoder`; гейт `NotEquals VideoRangeType DOVI` блокирует только P5, если нет DV-декодера; AV1 только при HW), [subtitles.ts](https://github.com/streamyfin/streamyfin/blob/develop/utils/profiles/subtitles.ts) (графические `dvdsub, idx, pgs, pgssub, teletext, vobsub, xsub` → `Encode`; текстовые → `External`; **намеренно не объявляют `webvtt` для External**, иначе сервер перегоняет .vtt через свой VttWriter с нестандартным `Region:` и теряет реплики — их issue #1892), [getStreamUrl.ts](https://github.com/streamyfin/streamyfin/blob/develop/utils/jellyfin/media/getStreamUrl.ts) (если есть `TranscodingUrl` — используют его, при `subtitleStreamIndex === -1` заменяют `SubtitleMethod=Encode` → `SubtitleMethod=Hls`; иначе `/Videos/{itemId}/stream?static=true&container=...&mediaSourceId=...&playSessionId=...`; для `BaseItemKind.Program` делают PlaybackInfo по `ChannelId` c `autoOpenLiveStream: true`; для удалённых `IsRemote && Protocol == "Http"` играют `Path` напрямую).

**jellyfin-web (браузер; эталон)** — [src/scripts/browserDeviceProfile.js](https://github.com/jellyfin/jellyfin-web/blob/master/src/scripts/browserDeviceProfile.js): `MaxStreamingBitrate = getMaxBitrate()` (120000000), `MaxStaticBitrate: 100000000`, `MusicStreamingTranscodingBitrate: Math.min(bitrateSetting, 384000)`; HLS `Container: enableFmp4Hls ? 'mp4' : 'ts'`, `MinSegments: '2'` на iOS/macOS, `BreakOnNonKeyFrames`; аудио HTTP `aac, mp3, opus, wav`, `Context: 'Static'` для оффлайна; ContainerProfile Tizen `NumStreams LessThanEqual 32`; `AudioProfile NotEquals HE-AAC`; субтитры: `vtt External`, `ass/ssa External` (рендер JASSUB/libass-wasm), `pgssub External` + `vobsub External Container: 'mks'` при `subtitlerenderpgs` (клиентский рендер через `libbitsub`), настройка `subtitleburnin` = `all` / `allcomplexformats` / `onlyimageformats`. Логика выбора рендерера — [src/plugins/htmlVideoPlayer/plugin.js](https://github.com/jellyfin/jellyfin-web/blob/master/src/plugins/htmlVideoPlayer/plugin.js) (`NATIVE_UNSUPPORTED_SUBTITLE_CODECS = ['ssa', 'ass', 'pgssub', 'dvdsub', 'vobsub']`, `DeliveryMethod === 'Encode'` → индекс `-1`, `alwaysBurnInSubtitleWhenTranscoding`).

**jellyfin-mpv-shim** — профиль в [jellyfin_mpv_shim/utils.py](https://github.com/jellyfin/jellyfin-mpv-shim/blob/master/jellyfin_mpv_shim/utils.py): `"DirectPlayProfiles": [{"Type": "Video"}, {"Type": "Audio"}, {"Type": "Photo"}]` (всё), субтитры `srt/ass/sub/ssa/smi` External+Embed, `pgssub/dvdsub/dvbsub/pgs` только Embed (комментарий: «Jellyfin currently refuses to serve these subtitle types as external» — учтите, что в 12.2 графические субтитры стали отдаваться как raw-файлы с range-запросами); опциональные CodecProfile-переключатели: `transcode_hi10p` → `VideoBitDepth LessThanEqual 8`, `transcode_dolby_vision` → `VideoRangeType NotEquals DOVI`, `transcode_hdr` → `VideoRangeType Equals SDR`, `transcode_hevc`; `always_transcode` → `DirectPlayProfiles = []`. (`video_profile.py` там — это шейдерные профили mpv, не DeviceProfile.)

**jellyfin-kodi** — [jellyfin_kodi/helper/playutils.py](https://github.com/jellyfin/jellyfin-kodi/blob/master/jellyfin_kodi/helper/playutils.py) `get_device_profile()`: `"MusicStreamingTranscodingBitrate": 1280000`, `"TimelineOffsetSeconds": 5`, TranscodingProfile Video `"Container": "m3u8"` + `MaxAudioChannels` из настроек, DirectPlay `{"Type":"Video","VideoCodec":...}`, `{"Type":"Audio"}`, `{"Type":"Photo"}`; субтитры `srt/ass/sub/ssa/smi/pgssub/dvdsub/pgs` External+Embed; `transcodeHi10P` → `VideoBitDepth <= 8`; `transcode_h265_rext` → `VideoProfile EqualsAny main|main 10`; для `TvChannel` вставляется профиль `ts`/`h264`/`mp3,aac`/`hls`/`MaxAudioChannels "2"`/`MinSegments "1"`/`BreakOnNonKeyFrames`.

**Kotlin SDK DSL** (рекомендую использовать): [jellyfin-model/.../deviceprofile/DeviceProfileBuilder.kt](https://github.com/jellyfin/jellyfin-sdk-kotlin/blob/master/jellyfin-model/src/commonMain/kotlin/org/jellyfin/sdk/model/deviceprofile/DeviceProfileBuilder.kt), [DeviceProfileBuilderDsl.kt](https://github.com/jellyfin/jellyfin-sdk-kotlin/blob/master/jellyfin-model/src/commonMain/kotlin/org/jellyfin/sdk/model/deviceprofile/DeviceProfileBuilderDsl.kt) — `buildDeviceProfile { transcodingProfile {...}; directPlayProfile {...}; codecProfile { conditions {...}; applyConditions {...} }; subtitleProfile(format, method) }` с инфиксами `equals / notEquals / inCollection / lowerThanOrEquals / greaterThanOrEquals`.

### 1.4. «VLC играет всё» vs ExoPlayer — как выглядит профиль и подводные камни

Профиль libVLC-клиента (по образцу Swiftfin-VLC, Findroid, mpv-shim, старого `LibVlcProfile`):
- DirectPlay: `DirectPlayProfile(type=VIDEO, container=null/"" , videoCodec=null, audioCodec=null)` + такой же AUDIO — либо явные длинные списки (как Swiftfin), чтобы всё же исключить то, что устройство реально не тянет (AV1/HEVC 10-bit софтом на слабом SoC, 4K60 без HW).
- Ограничения через CodecProfile, а не через отсутствие кодека: `WIDTH/HEIGHT`, `VIDEO_BITRATE`, `VIDEO_RANGE_TYPE` (DV P5 без HW-декодера → транскод/тонмап; HDR на SDR-панели — решать по `Display.getHdrCapabilities()`), `VIDEO_BIT_DEPTH` (Hi10P H.264 только софтом), `IS_INTERLACED`, `VideoRotation` (новая опция 12.0).
- TranscodingProfile всё равно нужен: Video `HLS` + `mp4` (fMP4) с `h264,hevc,(av1)` и аудио `aac,ac3,eac3,mp3,alac,flac,opus,dts,truehd` — иначе при превышении `MaxStreamingBitrate`/пользовательского лимита сервер не сможет ничего предложить (`NoCompatibleStream`). `ts` даёт меньше аудио-кодеков (см. белые списки). Ставьте `BreakOnNonKeyFrames=true`, `MinSegments=2`, `MaxAudioChannels` по режиму (2/6/8), `EnableSubtitlesInManifest=true`, если хотите HLS-субтитры.
- Битрейт: `MaxStreamingBitrate` = выбранное пользователем качество (Auto → результат `/Playback/BitrateTest`; LAN → очень большое число); `MaxStaticBitrate` — для `static=true`/скачивания; `MusicStreamingTranscodingBitrate` — для `/Audio/{id}/universal` (web ограничивает 384 kbps). Сервер дополнительно режет по `UserPolicy.RemoteClientBitrateLimit` и `ServerConfiguration.RemoteClientBitrateLimit` для внешних адресов.
- Субтитры с libVLC: текст (`srt, subrip, ass, ssa, vtt, ttml, mov_text, microdvd, sami...`) — `Embed` + `External` (ASS внешним файлом VLC отрисует через libass, если доступны шрифты/attachments; при External сервер отдаёт исходный `.ass`, конверсия из ASS невозможна); графические (`pgssub, dvdsub, dvbsub, xsub`) — `Embed` (VLC декодирует сам) и `Encode` как fallback для транскода. При транскоде через HLS «embed» невозможен (`CanConsiderEmbedSubtitle`), поэтому для выбранной PGS/ASS при транскоде сервер выберет `Encode` (burn-in) — это ожидаемо и так делают все клиенты; для текстовых при транскоде лучше `Hls`/`External`.
- Аудио: при passthrough (`AudioCapabilities`/`AudioManager` сообщает поддержку AC3/EAC3/DTS/TrueHD через HDMI) — объявлять кодеки и `AUDIO_CHANNELS <= 8`; при даунмиксе — `<= 2` и исключать `truehd/dts/mlp` из direct play, чтобы сервер перегнал в AAC/EAC3 (так делает Android TV). TrueHD/Atmos и DTS-HD MA libVLC декодирует софтом в PCM (как mpv/ExoPlayer+ffmpeg), но биттстрим через Android AudioTrack для TrueHD практически нет — для Atmos нужен passthrough TrueHD/EAC3-JOC.
- `SupportsDirectStream=false` (например, некоторые Live TV/плагинные источники) — тогда только DirectPlay или полный транскод. `IsInfiniteStream`, `RequiresOpening/OpenToken/LiveStreamId`, `RequiresClosing` — Live TV.
- Удалите `SubtitleCodecNotSupported`-ловушку: если объявить формат только с `Encode`, выбор такой дорожки всегда вызовет транскод видео.
- Streamyfin-грабли с `webvtt`-алиасом (см. выше) и Swiftfin-комментарий о конверсии «в первый External-формат» — порядок SubtitleProfile'ов важен.
- `MaxAudioChannels` — строка; `Container` в DirectPlayProfile сравнивается с `MediaSourceInfo.Container` (у MKV — `mkv`, у TS — `ts`/`mpegts`), пустое = любое.

---

## 2. Поток воспроизведения (как у реальных клиентов)

1. **PlaybackInfo** — `POST /Items/{itemId}/PlaybackInfo` ([MediaInfoController.cs](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/MediaInfoController.cs)), query-параметры помечены `ParameterObsolete` — всё передаётся телом `PlaybackInfoDto`: `UserId, MaxStreamingBitrate, StartTimeTicks, AudioStreamIndex, SubtitleStreamIndex, MaxAudioChannels, MediaSourceId, LiveStreamId, DeviceProfile, EnableDirectPlay, EnableDirectStream, EnableTranscoding, AllowVideoStreamCopy, AllowAudioStreamCopy, AutoOpenLiveStream, AlwaysBurnInSubtitleWhenTranscoding`. Ответ `PlaybackInfoResponse { MediaSources[], PlaySessionId, ErrorCode }`, `PlaybackErrorCode`: `NotAllowed`, `NoCompatibleStream`, `RateLimitExceeded` (это не API rate limit, а лимит `UserPolicy.MaxActiveSessions`/битрейт).
2. **MediaSourceInfo** ([файл](https://github.com/jellyfin/jellyfin/blob/master/MediaBrowser.Model/Dto/MediaSourceInfo.cs)) — ключевые поля: `Id, Protocol (File/Http/...), Container, Size, Bitrate, RunTimeTicks, SupportsDirectPlay, SupportsDirectStream, SupportsTranscoding, IsInfiniteStream, IsRemote, Path, TranscodingUrl, TranscodingSubProtocol (http/hls), TranscodingContainer, TranscodeReasons, DefaultAudioStreamIndex, DefaultSubtitleStreamIndex, DefaultAudioIndexSource, MediaStreams[], MediaAttachments[], RequiredHttpHeaders, RequiresOpening, OpenToken, LiveStreamId, RequiresClosing, BufferMs, HasSegments, UseMostCompatibleTranscodingProfile, FallbackMaxStreamingBitrate, VideoType, IsoType, Video3DFormat`.
3. **Выбор URL** (так делают web/Streamyfin/Android):
   - есть `TranscodingUrl` → `baseUrl + TranscodingUrl` (это уже HLS `/Videos/{id}/master.m3u8?...` с `PlaySessionId`, `DeviceId`, `api_key`, `MediaSourceId`, `VideoCodec`, `AudioCodec`, `SubtitleMethod`, `TranscodeReasons=...`); при direct stream сервер тоже отдаёт `TranscodingUrl` (remux в HLS, `PlayMethod.DirectStream`);
   - иначе direct play: `GET /Videos/{itemId}/stream?static=true&mediaSourceId=...&playSessionId=...&deviceId=...&api_key=...` или `/Videos/{itemId}/stream.{container}` ([VideosController.cs](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/VideosController.cs)); у Kotlin SDK — `videosApi.getVideoStreamUrl(itemId, static = true, mediaSourceId = ...)`;
   - HLS-эндпоинты ([DynamicHlsController.cs](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/DynamicHlsController.cs)): `/Videos/{itemId}/master.m3u8`, `/main.m3u8`, `/live.m3u8`, сегменты `/Videos/{itemId}/hls1/{playlistId}/{segmentId}.{container}`; параметр `subtitleMethod` (`SubtitleDeliveryMethod`, по умолчанию `External`). Legacy `/Videos/{itemId}/hls/{playlistId}/stream.m3u8` в [HlsSegmentController.cs](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/HlsSegmentController.cs). В 12.0 HLS-контроллеры скрыты из OpenAPI-спеки (эндпоинты работают).
   - аудио: `GET /Audio/{itemId}/universal` ([UniversalAudioController.cs](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/UniversalAudioController.cs)) — сервер сам решает direct/transcode по `Container`, `AudioCodec`, `MaxStreamingBitrate`, `TranscodingContainer`, `TranscodingProtocol`; `/Audio/{itemId}/master.m3u8`.
4. **Live TV**: `POST /LiveStreams/Open` (`openToken, playSessionId, maxStreamingBitrate, startTimeTicks, audioStreamIndex, subtitleStreamIndex, maxAudioChannels, itemId, enableDirectPlay, enableDirectStream, alwaysBurnInSubtitleWhenTranscoding` + тело с DeviceProfile) и `POST /LiveStreams/Close`; проще — `AutoOpenLiveStream=true` в PlaybackInfo.
5. **Отчёты** ([PlaystateController.cs](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/PlaystateController.cs)): `POST /Sessions/Playing` (`PlaybackStartInfo`), `POST /Sessions/Playing/Progress` (`PlaybackProgressInfo`: `CanSeek, Item, ItemId, SessionId, MediaSourceId, AudioStreamIndex, SubtitleStreamIndex, IsPaused, IsMuted, PositionTicks, PlaybackStartTimeTicks, VolumeLevel, Brightness, AspectRatio, PlayMethod, LiveStreamId, PlaySessionId, RepeatMode, PlaybackOrder, NowPlayingQueue[], PlaylistItemId`), `POST /Sessions/Playing/Stopped` (`PlaybackStopInfo`: `... PositionTicks, LiveStreamId, PlaySessionId, Failed, NextMediaType, PlaylistItemId, NowPlayingQueue`), `POST /Sessions/Playing/Ping?playSessionId=` (держит транскод живым). Старые `POST/DELETE /PlayingItems/{itemId}` и `/Users/{userId}/PlayingItems/...` — deprecated (10.11). `PlayMethod`: `Transcode=0, DirectStream=1, DirectPlay=2`. Обязательно передавать `PlaySessionId` — по нему сервер связывает транскод и сессию.
   - Интервалы у клиентов: jellyfin-web — `setInterval(..., 10000)` ([playbackmanager.js](https://github.com/jellyfin/jellyfin-web/blob/master/src/components/playback/playbackmanager.js)); jellyfin-android — 10 с; Android TV legacy `PlaybackController.java`: `PROGRESS_REPORTING_INTERVAL = 3 s`, `PROGRESS_REPORTING_PAUSE_INTERVAL = 15 s` ([файл](https://github.com/jellyfin/jellyfin-androidtv/blob/master/app/src/main/java/org/jellyfin/androidtv/ui/playback/PlaybackController.java)); новая архитектура ATV — событийная [playback/jellyfin/.../playsession/PlaySessionService.kt](https://github.com/jellyfin/jellyfin-androidtv/blob/master/playback/jellyfin/src/main/kotlin/playsession/PlaySessionService.kt) (start на PLAYING, progress на PAUSED, stop на STOPPED/ERROR, `playMethod` из `MediaConversionMethod`, очередь `peekNext(15)` в `nowPlayingQueue`); Swiftfin — [MediaProgressObserver.swift](https://github.com/jellyfin/Swiftfin/blob/main/Shared/Objects/MediaPlayerManager/MediaProgressObserver.swift) с `PokeIntervalTimer` (точный интервал не извлечён — не проверено). Также после каждой паузы/сика/смены дорожки клиенты шлют progress немедленно.
6. **Остановка транскода**: `DELETE /Videos/ActiveEncodings?deviceId=...&playSessionId=...` (оба `Required`). Web вызывает `apiClient.stopActiveEncodings(playSessionId)` перед сменой потока и при стопе.
7. **Bitrate test**: `GET /Playback/BitrateTest?size=` (`Range(1, 100_000_000)`, default `102400` байт). Логика web — [src/utils/bitrateTest.ts](https://github.com/jellyfin/jellyfin-web/blob/master/src/utils/bitrateTest.ts): таймаут 5 с, кэш результата 1 ч, `LAN_BITRATE = 140000000`, учёт `navigator.connection.downlinkMax * 0.7`; замер от `HEADERS_RECEIVED` до `onload`. Swiftfin тестирует 1–10 MB. «Auto» = результат теста, «Max»/ручное значение → `MaxStreamingBitrate`; смена качества на лету = новый PlaybackInfo с `StartTimeTicks` + stop старого транскода (`changeStreamToUrl` в playbackmanager).
8. **Next Up / автоплей**: `GET /Shows/NextUp` ([TvShowsController.cs](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/TvShowsController.cs)) с `userId, startIndex, limit, fields, seriesId, parentId, enableImages, imageTypeLimit, enableImageTypes, enableUserData, nextUpDateCutoff, enableTotalRecordCount, enableResumable (default true), enableRewatching (default false)`. Web на главной: `limit 24/15`, `fields: PrimaryImageAspectRatio, DateCreated, Path, MediaSourceCount`, `enableResumable: false`, `nextUpDateCutoff` из настройки ([sections/nextUp.ts](https://github.com/jellyfin/jellyfin-web/blob/master/src/components/homesections/sections/nextUp.ts)); после эпизода web вызывает `getNextUpEpisodes({ SeriesId, UserId })` и автоплеит, если `user.Configuration.EnableNextEpisodeAutoPlay` (или тип не видео). Android TV: `NextUpBehavior = EXTENDED / MINIMAL / DISABLED`, «Still watching?» `StillWatchingBehavior = SHORT / DEFAULT / LONG / VERY_LONG / DISABLED` ([файлы](https://github.com/jellyfin/jellyfin-androidtv/tree/master/app/src/main/java/org/jellyfin/androidtv/preference/constant)).
9. **Resume / «просмотрено»**: пороги на сервере — [ServerConfiguration.cs](https://github.com/jellyfin/jellyfin/blob/master/MediaBrowser.Model/Configuration/ServerConfiguration.cs): `MinResumePct = 5`, `MaxResumePct = 90`, `MinResumeDurationSeconds = 300`, `MinAudiobookResume = 5`, `MaxAudiobookResume = 5` — сервер сам при `Stopped` обнуляет позицию (<5 % или короче 5 мин) или помечает Played (>90 %); клиенту достаточно честно слать `PositionTicks`. `UserData.PlaybackPositionTicks > 0` → показывать диалог «Продолжить с …/Сначала» (Android TV ещё вычитает `resumeSubtractDuration`). Ручные отметки: `POST /UserPlayedItems/{itemId}?datePlayed=`, `DELETE /UserPlayedItems/{itemId}`; `GET /UserItems/Resume` (`mediaTypes, includeItemTypes, excludeActiveSessions, limit...`), `POST /UserItems/{itemId}/UserData`; избранное `POST/DELETE /UserFavoriteItems/{itemId}`.
10. **Media Segments (10.10+)**: `GET /MediaSegments/{itemId}?includeSegmentTypes=Intro,Outro,...` ([MediaSegmentsController.cs](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/MediaSegmentsController.cs)), `MediaSegmentDto { Id, ItemId, Type, StartTicks, EndTicks }`, `MediaSegmentType`: `Unknown=0, Commercial=1, Preview=2, Recap=3, Outro=4, Intro=5`. Сигнал наличия — `MediaSourceInfo.HasSegments`. Документация: [Media segments](https://jellyfin.org/docs/general/server/metadata/media-segments) (нужен плагин-провайдер, задача `Media segment scan`; официальный [jellyfin-plugin-chapter-segments](https://github.com/jellyfin/jellyfin-plugin-chapter-segments) делает сегменты из названий глав). Реализации: web — [mediaSegmentManager.ts](https://github.com/jellyfin/jellyfin-web/blob/master/src/apps/legacy/features/playback/utils/mediaSegmentManager.ts) + [mediaSegmentAction.ts](https://github.com/jellyfin/jellyfin-web/blob/master/src/apps/legacy/features/playback/constants/mediaSegmentAction.ts) (`None / AskToSkip / Skip`; запрашивает только типы с действием; Skip игнорируется для сегментов <1 с и если пользователь сам отмотал назад внутрь сегмента; AskToSkip — <3 с; без `EndTicks` → `nextTrack()`), кнопка [skipsegment.ts](https://github.com/jellyfin/jellyfin-web/blob/master/src/components/playback/skipsegment.ts); Android TV — [MediaSegmentRepository.kt](https://github.com/jellyfin/jellyfin-androidtv/blob/master/app/src/main/java/org/jellyfin/androidtv/ui/playback/segment/MediaSegmentRepository.kt) (`MediaSegmentAction.NOTHING / SKIP / ASK_TO_SKIP`, `SkipMinDuration = 1s`, `AskToSkipMinDuration = 3s`, `AskToSkipAutoHideDuration = 8s`, поддерживаемые типы INTRO, OUTRO, PREVIEW, RECAP, COMMERCIAL, настройки хранятся строкой `Intro=SKIP,Outro=ASK_TO_SKIP`); Findroid — [FindroidSegment.kt](https://github.com/jarnedemeulemeester/findroid/blob/main/data/src/main/java/dev/jdtech/jellyfin/models/FindroidSegment.kt) + `PlayerViewModel` (кнопка исчезает за 100 мс до конца, автоскип с режимом «всегда/в PiP», кэш сегментов для оффлайна); Streamyfin — [utils/segments.ts](https://github.com/streamyfin/streamyfin/blob/develop/utils/segments.ts), [hooks/useMediaSegments.ts](https://github.com/streamyfin/streamyfin/blob/develop/hooks/useMediaSegments.ts) (приоритет перекрытий `Commercial > Recap > Intro > Preview > Outro`, задержка первого автоскипа 1,5 с из-за транскода, «титры до конца −5 с» → countdown следующего эпизода).
11. **Intro Skipper (legacy API)**: оригинальная документация — [ConfusedPolarBear/intro-skipper docs/api.md](https://github.com/ConfusedPolarBear/intro-skipper/blob/master/docs/api.md): `GET /Episode/{ItemId}/IntroTimestamps` (= `/v1`), ответ `{ EpisodeId, Valid, IntroStart, IntroEnd, ShowSkipPromptAt, HideSkipPromptAt }` (секунды), `404` если интро нет. Форки добавили `GET /Episode/{id}/Timestamps` с полями `Introduction / Credits / Recap / Preview / Commercial { Start, End, Valid }` (так его читает Streamyfin как fallback). Актуальный плагин — [intro-skipper/intro-skipper](https://github.com/intro-skipper/intro-skipper): требует **Jellyfin 12.0+ и jellyfin-ffmpeg 7.1.3-1+**, манифест `https://intro-skipper.org/manifest.json`, «As of Jellyfin 10.10, Intro Skipper does NOT modify the UI», публикует сегменты в нативный MediaSegments (wiki: «Jellyfin 12 clients use the native MediaSegments API», плагинная БД — источник истины, таблица MediaSegments — зеркало). Вывод: новому клиенту достаточно Media Segments API; legacy `/Episode/.../IntroTimestamps` — только для старых серверов 10.8/10.9.
12. **Trickplay**: `GET /Videos/{itemId}/Trickplay/{width}/{index}.jpg?mediaSourceId=` и `GET /Videos/{itemId}/Trickplay/{width}/tiles.m3u8` ([TrickplayController.cs](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/TrickplayController.cs)); метаданные — `BaseItemDto.Trickplay: Dictionary<mediaSourceId, Dictionary<width, TrickplayInfoDto>>` (нужен `ItemFields.Trickplay`), `TrickplayInfoDto { Width, Height, TileWidth, TileHeight, ThumbnailCount, Interval (мс), Bandwidth }`. Дефолты сервера ([TrickplayOptions.cs](https://github.com/jellyfin/jellyfin/blob/master/MediaBrowser.Model/Configuration/TrickplayOptions.cs)): `Interval = 10000`, `WidthResolutions = [320]`, `TileWidth = TileHeight = 10`, `Qscale = 4`, `JpegQuality = 90`, `EnableKeyFrameOnlyExtraction`, `EnableHwAcceleration/EnableHwEncoding`. Расчёт листа/тайла: `tileIndex = floor(positionMs / Interval)`, лист = `tileIndex / (TileWidth*TileHeight)`, в листе — `x = (i % TileWidth) * Width`, `y = (i / TileWidth) * Height` (см. [Streamyfin utils/trickplay.ts](https://github.com/streamyfin/streamyfin/blob/develop/utils/trickplay.ts): ключ `Trickplay[mediaSourceId]`, для альтернативных версий считать по `ThumbnailCount`). Findroid кэширует тайлы в `filesDir/trickplay/{itemId}`.
13. **Главы**: `BaseItemDto.Chapters: ChapterInfo[] { StartPositionTicks, Name, ImagePath, ImageDateModified, ImageTag }` (нужен `ItemFields.Chapters`); картинка — `GET /Items/{itemId}/Images/Chapter/{imageIndex}?tag=`.
14. **Capabilities**: `POST /Sessions/Capabilities` (query) или `POST /Sessions/Capabilities/Full` с `ClientCapabilitiesDto { PlayableMediaTypes[], SupportedCommands[], SupportsMediaControl, SupportsPersistentIdentifier, DeviceProfile, AppStoreUrl, IconUrl }` — чтобы сессия управлялась другими клиентами (remote control, SyncPlay); Findroid делает `postCapabilities` при старте.

---

## 3. Субтитры и аудио

### Субтитры
- Форматы, которые сервер понимает: текстовые `srt/subrip, ass, ssa, vtt/webvtt, ttml, mov_text (tx3g), microdvd, sami/smi, mpl2, pjs, realtext, subviewer, text, vplayer, scc, cc_dec (EIA-608/708)`; графические `pgssub/pgs (sup), dvdsub/vobsub (idx+sub), dvbsub, xsub, teletext (libzvbi_teletextdec)`. Официальная таблица: [Codec Support](https://jellyfin.org/docs/general/clients/codec-support/) — ASS/SSA и PGS только в MKV (не в MP4/TS), SRT/WebVTT/VobSub/MP4TT/PGSSUB/EIA-608/708 перечислены. Отдельной страницы «subtitles» в документации нет (проверено по дереву репо jellyfin.org).
- Поля `MediaStream` ([MediaStream.cs](https://github.com/jellyfin/jellyfin/blob/master/MediaBrowser.Model/Entities/MediaStream.cs)): `Index, Type (Audio/Video/Subtitle/EmbeddedImage/Data/Lyric), Codec, CodecTag, Language, Title, DisplayTitle, IsDefault, IsForced, IsHearingImpaired, IsExternal, IsExternalUrl, IsTextSubtitleStream, SupportsExternalStream, DeliveryMethod, DeliveryUrl, Path, Channels, ChannelLayout, Profile, Level, BitDepth, RefFrames, IsInterlaced, IsAVC, VideoRangeType, DvProfile, DvLevel, LocalizedDefault/Forced/External/HearingImpaired`. После PlaybackInfo сервер проставляет `DeliveryMethod` и `DeliveryUrl` каждой субтитровой дорожке — используйте их, а не собирайте URL сами (mpv-shim советует для названий брать `DisplayTitle`).
- Внешний поток ([SubtitleController.cs](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/SubtitleController.cs)): `GET /Videos/{itemId}/{mediaSourceId}/Subtitles/{index}/Stream.{format}` и `GET /Videos/{itemId}/{mediaSourceId}/Subtitles/{index}/{startPositionTicks}/Stream.{format}` (query: `endPositionTicks, copyTimestamps=false, addVttTimeMap=false`; `format=js` → json); HLS-плейлист субтитров `GET /Videos/{itemId}/{mediaSourceId}/Subtitles/{index}/subtitles.m3u8?segmentLength=`; графические (pgs/vobsub) отдаются как сырой файл (12.2: с поддержкой range-запросов). Загрузка пользователем: `POST /Videos/{itemId}/Subtitles` (`UploadSubtitleDto`), удаление `DELETE /Videos/{itemId}/Subtitles/{index}`. Шрифты для ASS: `GET /FallbackFont/Fonts`, `GET /FallbackFont/Fonts/{name}` (сервер: `EncodingOptions.EnableFallbackFont`, `FallbackFontPath`); вложенные шрифты MKV — `MediaSourceInfo.MediaAttachments` + `GET /Videos/{videoId}/{mediaSourceId}/Attachments/{index}` (не проверялось в этом исследовании, но используется web).
- Поиск/скачивание (нужен плагин [jellyfin-plugin-opensubtitles](https://github.com/jellyfin/jellyfin-plugin-opensubtitles), документация [Open Subtitles](https://jellyfin.org/docs/general/server/plugins/open-subtitles/) — требуется аккаунт OpenSubtitles.com в настройках плагина): `GET /Items/{itemId}/RemoteSearch/Subtitles/{language}?isPerfectMatch=` → `RemoteSubtitleInfo[]`, `POST /Items/{itemId}/RemoteSearch/Subtitles/{subtitleId}` (скачать на сервер), `GET /Providers/Subtitles/Subtitles/{subtitleId}`. Разрешение пользователя: `EnableSubtitleManagement` (`PermissionKind`). Библиотечные опции (`LibraryOptions`): `SubtitleFetcherOrder, SubtitleDownloadLanguages, SkipSubtitlesIfEmbeddedSubtitlesPresent, SkipSubtitlesIfAudioTrackMatches, RequirePerfectSubtitleMatch, SaveSubtitlesWithMedia, AllowEmbeddedSubtitles`.
- Предпочтения пользователя ([UserConfiguration.cs](https://github.com/jellyfin/jellyfin/blob/master/MediaBrowser.Model/Configuration/UserConfiguration.cs)): `AudioLanguagePreference`, `PlayDefaultAudioTrack`, `SubtitleLanguagePreference`, `SubtitleMode` (`SubtitlePlaybackMode`: `Default=0, Always=1, OnlyForced=2, None=3, Smart=4`), `RememberAudioSelections`, `RememberSubtitleSelections`, `EnableNextEpisodeAutoPlay`, `DisplayMissingEpisodes`, `HidePlayedInLatest`, `LatestItemsExcludes`, `MyMediaExcludes`, `OrderedViews`, `GroupedFolders`, `CastReceiverId`. Сервер уже применяет их, выставляя `DefaultAudioStreamIndex`/`DefaultSubtitleStreamIndex` в MediaSourceInfo (`Smart` = субтитры на предпочитаемом языке, только если аудио не на нём). `PlaybackInfoDto.AlwaysBurnInSubtitleWhenTranscoding` — соответствующая пользовательская настройка web.
- Рендер на клиенте: web — ASS через `@jellyfin/libass-wasm` (SubtitlesOctopus, `libassMemoryLimit: 40`), PGS/VobSub через `libbitsub.PgsRenderer` (стриминг, range-запросы, `prefetchWindow`), настройка `subtitlerenderpgs`. Для libVLC всё это внутри плеера; внешнее ASS подключайте через `Media.addSlave(Type.Subtitle, url)`.

### Аудио
- Passthrough vs транскод: ATV — объявляет AC3/EAC3/DTS/TrueHD только при подтверждённом passthrough (`AudioCapabilities.getCapabilities(...).isPassthroughPlaybackSupported`), иначе сервер перегонит в AAC/AC3 внутри HLS; при `DOWNMIX_TO_STEREO` direct play оставляют только `AAC, MP2, MP3` и `AUDIO_CHANNELS <= 2`. Streamyfin: TrueHD/DTS-HD декодирует mpv/ffmpeg в PCM, режим аудио меняет только `MaxAudioChannels`. HLS `ts` не пропустит DTS/TrueHD/FLAC — только `mp4`. Сервер-сайд даунмикс: [Stereo Downmix](https://jellyfin.org/docs/general/post-install/transcoding/downmix/) — алгоритмы `None, Dave750, NightmodeDialogue, RFC7845, AC-4` (`EncodingOptions.DownMixStereoAlgorithm`), `DownMixAudioBoost` (0.5–3, default 2).
- Нормализация громкости (10.9+): `BaseItemDto.NormalizationGain` и `AlbumNormalizationGain` (float, dB); рассчитывается задачей [AudioNormalizationTask.cs](https://github.com/jellyfin/jellyfin/blob/master/Emby.Server.Implementations/ScheduledTasks/Tasks/AudioNormalizationTask.cs) через `ffmpeg -af ebur128=framelog=verbose` (парсит `I: ... LUFS`) для библиотек с `LibraryOptions.EnableLUFSScan`; Android TV применяет gain с целевой громкостью **-23 LUFS** ([normalizationGainElement.kt](https://github.com/jellyfin/jellyfin-androidtv/blob/master/playback/core/src/main/kotlin/mediastream/normalizationGainElement.kt)); Finamp тоже применяет; по словам автора Symfonium, album gain полноценно появится позже ([обсуждение](https://support.symfonium.app/t/jellyfin-replay-normalization-gain/14084)). В libVLC — `MediaPlayer.setVolume` не годится для dB; используйте аудиофильтр `gain` или `--gain`/`audio-filter=gain` при создании LibVLC (не проверено на практике).
- Live TV & DVR ([LiveTvController.cs](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/LiveTvController.cs), документация [Live Broadcast](https://jellyfin.org/docs/general/server/live-tv/) — тюнеры HDHomeRun и M3U, гиды Schedules Direct/XMLTV): `GET /LiveTv/Info`, `/LiveTv/Channels`, `/LiveTv/Channels/{channelId}`, `/LiveTv/Programs` (+`POST /LiveTv/Programs` для сложных фильтров), `/LiveTv/Programs/Recommended`, `/LiveTv/GuideInfo`, `/LiveTv/Recordings`, `/LiveTv/Recordings/{recordingId}`, `/LiveTv/Recordings/Folders`, `/LiveTv/Timers`, `/LiveTv/Timers/Defaults?programId=`, `POST /LiveTv/Timers`, `/LiveTv/SeriesTimers`, `GET /LiveTv/LiveRecordings/{recordingId}/stream`, `GET /LiveTv/LiveStreamFiles/{streamId}/stream.{container}`. Воспроизведение канала — обычный PlaybackInfo по `LiveTvChannel` c `AutoOpenLiveStream`; тип элемента `Program` → играть `ChannelId`. Разрешения `EnableLiveTvAccess`, `EnableLiveTvManagement`.
- Музыка: лирика `GET /Audio/{itemId}/Lyrics` → `LyricDto { Metadata, Lyrics: LyricLine[] (Text, Start ticks, с 10.11 — word cues) }`, `POST /Audio/{itemId}/Lyrics`, `DELETE`, `GET /Audio/{itemId}/RemoteSearch/Lyrics`, `POST /Audio/{itemId}/RemoteSearch/Lyrics/{lyricId}`, `GET /Providers/Lyrics/{lyricId}`; флаг `BaseItemDto.HasLyrics`; файлы `.lrc/.elrc/.txt` рядом с треком ([Music docs](https://jellyfin.org/docs/general/server/media/music/)). Instant Mix ([InstantMixController.cs](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/InstantMixController.cs)): `GET /Items/{itemId}/InstantMix`, `/Songs/{itemId}/InstantMix`, `/Albums/{itemId}/InstantMix`, `/Artists/{itemId}/InstantMix`, `/MusicGenres/{name}/InstantMix`, `/Playlists/{itemId}/InstantMix`. Gapless: серверного API нет — это задача плеера (предзагрузка следующего `Media` в libVLC через `MediaList`/второй `MediaPlayer`); при транскоде через `/Audio/{id}/universal` gapless не гарантирован (не проверено).

---

## 4. Изображения и метаданные

- `ImageType` ([ImageType.cs](https://github.com/jellyfin/jellyfin/blob/master/MediaBrowser.Model/Entities/ImageType.cs)): `Primary=0, Art=1, Backdrop=2, Banner=3, Logo=4, Thumb=5, Disc=6, Box=7, Screenshot=8, Menu=9, Chapter=10, BoxRear=11, Profile=12`.
- Эндпоинты ([ImageController.cs](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/ImageController.cs)): `GET /Items/{itemId}/Images/{imageType}` и `/{imageIndex}` с query `maxWidth, maxHeight, width, height, quality, fillWidth, fillHeight, tag, format (ImageFormat: Bmp/Gif/Jpg/Png/Webp/Svg), percentPlayed, unplayedCount, blur, backgroundColor, foregroundLayer, imageIndex`; `GET /Items/{itemId}/Images` (список `ImageInfo`); `GET /Persons/{name}/Images/{imageType}`, `/Artists/{name}/Images/...`, `/Genres/{name}/...`, `/Studios/{name}/...`, `/MusicGenres/{name}/...`; аватар `GET /UserImage?userId=`/`/Users/{userId}/Images/Primary`; `GET /Branding/Splashscreen`. Семантика: `maxWidth/maxHeight` — вписать с сохранением пропорций; `width/height` — точный размер; `fillWidth/fillHeight` — заполнение с кропом (web использует `fillWidth/fillHeight` для постеров, `maxWidth` для бэкдропов); `quality` — JPEG/WebP качество; `tag` — кэш-бастинг (из `ImageTags[type]`/`BackdropImageTags[i]`); `blur` — размытие на сервере; `percentPlayed`/`unplayedCount` — наложение индикаторов сервером (web предпочитает рисовать сам).
- Поля `BaseItemDto` ([BaseItemDto.cs](https://github.com/jellyfin/jellyfin/blob/master/MediaBrowser.Model/Dto/BaseItemDto.cs)): `ImageTags: Dictionary<ImageType,string>`, `BackdropImageTags[]`, `ScreenshotImageTags[]`, `ImageBlurHashes: Dictionary<ImageType, Dictionary<tag, blurhash>>`, `PrimaryImageAspectRatio`, `ParentBackdropItemId` + `ParentBackdropImageTags[]`, `ParentLogoItemId/ParentLogoImageTag`, `ParentThumbItemId/ParentThumbImageTag`, `ParentArtItemId/ParentArtImageTag`, `ParentPrimaryImageItemId/ParentPrimaryImageTag`, `SeriesPrimaryImageTag`, `SeriesThumbImageTag`, `AlbumPrimaryImageTag`, `ChannelPrimaryImageTag`, `SeriesId/SeriesName/SeasonId/SeasonName`, `ParentId`, `ParentIndexNumber/IndexNumber`, `RunTimeTicks`, `CumulativeRunTimeTicks`, `PartCount`, `LocalTrailerCount`, `HasSubtitles`, `HasLyrics`, `Status`, `AirsAfter/BeforeSeasonNumber`, `PremiereDate`, `Chapters`, `Trickplay`, `MediaSources`, `MediaStreams`, `CollectionType`, `NormalizationGain`, `UserData`. Правило для эпизода: `Primary` — кадр эпизода, бэкдроп/лого обычно у сериала → брать через `ParentBackdropItemId` + `ParentBackdropImageTags`, постер сезона/сериала — `SeriesPrimaryImageTag` (itemId = `SeriesId`). Сериал без собственного `Thumb` → `ParentThumbItemId`.
- `UserItemDataDto`: `Rating, PlayedPercentage, UnplayedItemCount, PlaybackPositionTicks, PlayCount, IsFavorite, Likes, LastPlayedDate, Played, Key, ItemId`.
- `ItemFields` ([ItemFields.cs](https://github.com/jellyfin/jellyfin/blob/master/MediaBrowser.Model/Querying/ItemFields.cs)): `AirTime, CanDelete, CanDownload, ChannelInfo, Chapters, Trickplay, ChildCount, CumulativeRunTimeTicks, CustomRating, DateCreated, DateLastMediaAdded, DisplayPreferencesId, Etag, ExternalUrls, Genres, ItemCounts, MediaSourceCount, MediaSources, OriginalTitle, Overview, ParentId, Path, People, PlayAccess, ProductionLocations, ProviderIds, PrimaryImageAspectRatio, RecursiveItemCount, Settings, SeriesStudio, SortName, SpecialEpisodeNumbers, Studios, Taglines, Tags, RemoteTrailers, MediaStreams, SeasonUserData, DateLastRefreshed, DateLastSaved, RefreshState, ChannelImage, EnableMediaSourceDisplay, Width, Height, ExtraIds, LocalTrailerCount, IsHD, SpecialFeatureCount`. Практика web: для лент — `PrimaryImageAspectRatio` (+ `DateCreated, Path, MediaSourceCount`) с `imageTypeLimit: 1` и `enableImageTypes`; для деталей — `Overview, Genres, People, Studios, Chapters, MediaSources, MediaStreams, Trickplay, Tags, ExternalUrls, ProviderIds, RemoteTrailers, Taglines, DateCreated, CanDownload, CanDelete, CustomRating`.
- `BaseItemKind` ([файл](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Data/Enums/BaseItemKind.cs)): `AggregateFolder, Audio, AudioBook, BasePluginFolder, Book, BoxSet, Channel, ChannelFolderItem, CollectionFolder, Episode, Folder, Genre, ManualPlaylistsFolder, Movie, LiveTvChannel, LiveTvProgram, MusicAlbum, MusicArtist, MusicGenre, MusicVideo, Person, Photo, PhotoAlbum, Playlist, PlaylistsFolder, Program, Recording, Season, Series, Studio, Trailer, TvChannel, TvProgram, UserRootFolder, UserView, Video, Year`.
- `CollectionType` ([файл](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Data/Enums/CollectionType.cs), сериализуется в lowercase): `unknown, movies, tvshows, music, musicvideos, trailers, homevideos, boxsets, books, photos, livetv, playlists, folders` + внутренние виды `tvshowseries, tvgenres, tvlatest, tvnextup, tvresume, tvfavoriteseries, tvfavoriteepisodes, movielatest, movieresume, moviemovies, moviecollection, moviefavorites, moviegenres...`. Библиотеки — `GET /UserViews?userId=` (элементы `CollectionFolder` с `CollectionType`), `GET /Items/Latest?parentId=&limit=&includeItemTypes=&isPlayed=&groupItems=true`, `GET /Items?parentId=&recursive=&includeItemTypes=&sortBy=&fields=...`, `GET /Shows/{seriesId}/Seasons`, `GET /Shows/{seriesId}/Episodes?seasonId=`, `GET /Shows/Upcoming`, `GET /Items/{itemId}/Intros`, `/LocalTrailers`, `/SpecialFeatures`. Персоны — тип `Person`, картинка `Persons/{name}/Images/Primary?tag=` (в `People[]` есть `PrimaryImageTag`, `Id`, `Role`, `Type`).

---

## 5. UX-референсы

- [jellyfin/jellyfin-ux](https://github.com/jellyfin/jellyfin-ux) — брендинг/логотипы/иконки (CC-BY-SA-4.0), папки `branding/`, `logos/`, `plugins/`; Figma не публикуется. Правила — [Branding guidelines](https://jellyfin.org/docs/general/contributing/branding): имя «Jellyfin» и шаблоны «Jelly[word]»/«[word]fin» не использовать; разрешено «X, a Jellyfin client»; логотип (треугольник в треугольнике) без разрешения не брать, градиент — можно. Дизайн-обсуждения — Matrix `#jellyfin-ui-ux`.
- Структура главной в официальном web — [src/components/homesections/homesections.js](https://github.com/jellyfin/jellyfin-web/blob/master/src/components/homesections/homesections.js) и [constants/homeSectionType.ts](https://github.com/jellyfin/jellyfin-web/blob/master/src/constants/homeSectionType.ts): `SmallLibraryTiles` («My Media»), `LibraryButtons`, `Resume` («Continue Watching», `GET /UserItems/Resume` с `mediaTypes: [Video]`, limit 12/5, `fields: PrimaryImageAspectRatio`), `ResumeAudio`, `ResumeBook`, `NextUp`, `LatestMedia` (по одной секции на библиотеку, `/Items/Latest?parentId=`; limit 16, tvshows 5, music 9/30, иначе 8; учитывает `LatestItemsExcludes`/`HidePlayedInLatest`), `LiveTv`, `ActiveRecordings`. Порядок секций — из DisplayPreferences пользователя (`GET /DisplayPreferences/usersettings?client=emby`, ключи `homesection0..6`).
- Android TV: [Design for TV](https://developer.android.com/design/ui/tv) (Foundations/Style/Layout/Components; компоненты Buttons, Cards, Featured carousel, Immersive list, Lists, Navigation drawer, Tabs; ссылка на Android TV Figma kit — сам Figma-файл отдаёт 403 анонимно, не проверено), [Compose for TV](https://developer.android.com/training/tv/playback/compose) (`androidx.tv:tv-material:1.0.0`, свой `MaterialTheme`; не смешивать с mobile Material3; сэмплы [android/tv-samples](https://github.com/android/tv-samples) — JetStreamCompose, TvMaterialCatalog), релизы [androidx.tv](https://developer.android.com/jetpack/androidx/releases/tv), [TV app quality](https://developer.android.com/docs/quality-guidelines/tv-app-quality) (TV-DP d-pad, TV-PC/TV-PP play-pause, TV-OV overscan, TV-LB баннер 320×180, TV-ML `LEANBACK_LAUNCHER`, TV-NP media session, TV-BU ambient mode, TV-AX captions/high contrast, TV-AO выбор аудиоканалов, TV-FR frame-rate matching), [TV accessibility](https://developer.android.com/training/tv/accessibility) (`fontScale`, `isAudioDescriptionRequested`, `getKeyCodeForKeyLocation`, TalkBack, системные стили субтитров через `CaptioningManager`).
- Сравнения клиентов: независимых обзоров мало; материалы [jellywatch.app](https://jellywatch.app/blog/best-jellyfin-client-android-tv-2026) принадлежат конкурирующему клиенту — предвзяты (не проверено по сути). Факты: Findroid и Streamyfin — mobile-first (у Streamyfin есть TV-режим на ExoPlayer, у Findroid — модуль `app/tv`); официальный ATV — Leanback → Compose-миграция; из форума видно спрос на функции, потерянные с libVLC (двойные субтитры, offset аудио/субтитров, MPEG-2 Live TV без транскода).

---

## 6. Сообщество и экосистема

- Контакты — [jellyfin.org/contact](https://jellyfin.org/contact): Matrix Space `#jellyfinorg:matrix.org` (комнаты `#jellyfin`, `#jellyfin-announce`, `#jellyfin-troubleshooting`, dev: `#jellyfin-dev`, `#jellyfin-dev-client`, `#jellyfin-dev-android`, `#jellyfin-dev-ios`, `#jellyfin-dev-roku`, `#jellyfin-dev-python`, `#jellyfin-documentation`, `#jellyfin-ui-ux`, `#jellyfin-hwa-tool`), Discord `https://discord.gg/zHBxVSXdBV` (мост в Matrix), Mastodon `@jellyfin@mastodon.online`.
- Форум — [forum.jellyfin.org](https://forum.jellyfin.org/): [Client Development](https://forum.jellyfin.org/f-client-development) (подфорумы Android, Android TV, Roku...), [Server Development](https://forum.jellyfin.org/f-server-development), [Plugin Development](https://forum.jellyfin.org/f-plugin-development), [Feature Requests](https://forum.jellyfin.org/f-feature-requests), Project Announcements. Старый трекер идей features.jellyfin.org (Fider) отвечает, но контент не читался (не проверено); актуальные предложения — форум и [GitHub Discussions](https://github.com/jellyfin/jellyfin/discussions) (категории Announcements, Feature Proposals, General, Polls, Q&A, Show and tell). r/jellyfin существует, но недоступен для проверки из этой среды (не проверено).
- Листинг стороннего клиента — [Clients docs](https://jellyfin.org/docs/general/clients/): PR в [src/data/clients.ts](https://github.com/jellyfin/jellyfin.org/blob/master/src/data/clients.ts) (`Client { id, name, description, smallDescription?, clientType: Official|OfficialBeta|ThirdParty, deviceTypes: Desktop|Mobile|TV, platforms, primaryLinks, secondaryLinks?, recommended? }`). Требования: Jellyfin как «first-class citizen» с настраиваемым URL; соблюдение [Community Standards](https://jellyfin.org/docs/general/community-standards/) и брендинга; FOSS-лицензия из списка FSF; ≥1 года активной разработки или заметный интерес; хотя бы одна уникальная функция в публичном релизе. Сейчас на [downloads/clients/all](https://jellyfin.org/downloads/clients/all) из сторонних Android — Findroid, Finamp, Streamyfin; **сторонних Android TV-клиентов в списке нет** — ниша для вашего проекта.
- Ограничений API для сторонних клиентов нет: клиенты объявляют себя через заголовок `Authorization: MediaBrowser Client="...", Device="...", DeviceId="...", Version="...", Token="..."`; rate limiting на уровне API сервер не вводит (есть только `UserPolicy.MaxActiveSessions`, `LoginAttemptsBeforeLockout`, битрейт-лимиты). Политика: [Contributing / development](https://jellyfin.org/docs/general/contributing/development) и `docs/general/contributing/llm-policies.md` в репо jellyfin.org (политика по LLM-коду для PR в официальные репо).
- Плагины, важные клиенту: [Intro Skipper](https://github.com/intro-skipper/intro-skipper) (сегменты), [Chapter Segments Provider](https://github.com/jellyfin/jellyfin-plugin-chapter-segments), [OpenSubtitles](https://github.com/jellyfin/jellyfin-plugin-opensubtitles), [Playback Reporting](https://github.com/jellyfin/jellyfin-plugin-playbackreporting) (статистика, клиентского API не заявляет; ваши отчёты Progress/Stopped — его источник данных), Trickplay — встроен с 10.9 (плагин больше не нужен).
- Серверные настройки, влияющие на клиент: `EncodingOptions` ([файл](https://github.com/jellyfin/jellyfin/blob/master/MediaBrowser.Model/Configuration/EncodingOptions.cs)) — `HardwareAccelerationType`, `EnableHardwareEncoding`, `AllowHevcEncoding`, `AllowAv1Encoding` (без них транскод всегда в H.264 — объявляйте h264 в TranscodingProfile первым/обязательно), `EnableTonemapping`/`EnableVppTonemapping`/`EnableVideoToolboxTonemapping`, `EnableThrottling`/`ThrottleDelaySeconds`, `EnableSegmentDeletion`/`SegmentKeepSeconds` (нельзя далеко сикать назад в уже удалённые сегменты — делайте новый PlaybackInfo со `StartTimeTicks`), `EnableSubtitleExtraction`, `EnableFallbackFont`, `DownMixStereoAlgorithm`, `HlsAudioSeekStrategy`, `EnableAudioVbr`. Документация: [Transcoding](https://jellyfin.org/docs/general/post-install/transcoding/) («Direct Play / Remux / Direct Stream / Transcode», тонмаппинг HDR→SDR, разрешение нельзя задать вручную), [Hardware Acceleration](https://jellyfin.org/docs/general/post-install/transcoding/hardware-acceleration/).
- Права пользователя (`UserPolicy` / `PermissionKind`): `EnableMediaPlayback`, `EnableAudioPlaybackTranscoding`, `EnableVideoPlaybackTranscoding`, `EnablePlaybackRemuxing` («Allow video playback that requires conversion without re-encoding»), `ForceRemoteSourceTranscoding`, `EnableRemoteAccess`, `RemoteClientBitrateLimit` («Internet streaming bitrate limit (Mbps)»), `EnableContentDownloading`, `EnableSyncTranscoding`, `EnableMediaConversion`, `EnableLiveTvAccess/Management`, `EnableAllFolders/EnabledFolders`, `EnableAllDevices/EnabledDevices`, `EnableAllChannels`, `EnableSharedDeviceControl`, `EnableRemoteControlOfOtherUsers`, `EnablePublicSharing`, `EnableCollectionManagement`, `EnableSubtitleManagement`, `EnableLyricManagement`, `SyncPlayAccess`, `MaxActiveSessions`, `IsHidden`, `IsDisabled`, родительский контроль `MaxParentalRating`, `MaxParentalSubRating`, `BlockUnratedItems[]`, `BlockedTags[]`, `AllowedTags[]`, `AccessSchedules[]`. Документация: [Adding & Managing Users](https://jellyfin.org/docs/general/server/users/adding-managing-users). При отказе сервер вернёт `PlaybackErrorCode.NotAllowed` или 403 — показывайте понятное сообщение.
- Quick Connect ([docs](https://jellyfin.org/docs/general/server/quick-connect), [QuickConnectController.cs](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/QuickConnectController.cs)): `GET /QuickConnect/Enabled`, `POST /QuickConnect/Initiate` → `{ Secret, Code(6 символов), Authenticated, DateAdded }`, опрос `GET /QuickConnect/Connect?secret=`, затем `POST /Users/AuthenticateWithQuickConnect` с `Secret`; авторизация с другого устройства — `POST /QuickConnect/Authorize?code=&userId=`. Включается в Dashboard → General («Enable Quick Connect on this server», по умолчанию включено). `GET /QuickConnect/Initiate` удалён в 12.0.
- Плагины/клиенты для сравнения архитектуры: [jellyfin-media-player (Jellyfin Desktop)](https://github.com/jellyfin/jellyfin-media-player) — Qt WebEngine + libmpv (passthrough, HW-декод, `mpv.conf`), [Finamp](https://github.com/jmshrv/finamp) (музыка, применяет NormalizationGain).

---

## 7. Тестовые медиа

- [repo.jellyfin.org/test-videos](http://repo.jellyfin.org/test-videos/) — **официальный Jellyfin test suite** (CC BY-SA, автор gnattu): `SDR/{AVC, AV1, HEVC 8bit, HEVC 10bit}`, `HDR/HDR10/{AV1, HEVC}`, `HDR/Dolby Vision/` (P5, P8.1, P8.4); имена вида `Test Jellyfin 1080p AV1 10bit 3M.mp4`, `Test Jellyfin 4K DV P8.1.mp4`; 1080p/4K/8K, 3–150 Mbps, с `.md5/.sha1/.sha256`. HLG нет, аудио не описано.
- [repo.jellyfin.org/archive/jellyfish/](https://repo.jellyfin.org/archive/jellyfish/) — зеркало классических Jellyfish bitrate-тестов (помечено DEPRECATED): 56 MKV `jellyfish-{3..400}-mbps-{hd|4k-uhd}-{h264|hevc|hevc-10bit}.mkv`; оригинальный jell.yfish.us не работает.
- Blender open movies (CC-BY): Big Buck Bunny — [download.blender.org/peach/bigbuckbunny_movies/](https://download.blender.org/peach/bigbuckbunny_movies/) (MP4 320×180, M4V 640×360, MOV H.264 480/720/1080p, AVI stereo/surround 480/720/1080p, OGG Theora 480/720/1080p — удобно для MPEG-4 ASP/AC3 5.1/Theora+Vorbis); Sintel — [download.blender.org/durian/movies/](https://download.blender.org/durian/movies/) (`Sintel.2010.1080p.mkv`, 720p/4K MKV, `sintel_4k.mov`, Blu-ray ISO, FLAC m+e); Tears of Steel — [download.blender.org/demo/movies/ToS/](https://download.blender.org/demo/movies/ToS/) (4K MOV, 1080p MOV/WebM, 720p MOV, `tears_of_steel_surround.webm`, `Surround-TOS_DVDSURROUND-Dolby 5.1.ac3`, `subtitles/`), сайт [mango.blender.org/download](https://mango.blender.org/download/).
- [ietf-wg-cellar/matroska-test-files](https://github.com/ietf-wg-cellar/matroska-test-files) — `test_files/test1..8.mkv`: MPEG-4 Part 2 + MP3; нестандартный TimecodeScale/AR; header stripping (test3 — проверка демуксера); «live»-запись без Duration и Cues; **test5 — несколько аудио (AAC + HE-AAC commentary) и 7 субтитровых дорожек** (EN/FR/DE/HU/ES/IT/JA) — идеален для UI выбора дорожек; test6 без Cues (сик по кластерам); test7 с мусорными элементами; test8 с пропуском аудио.
- [test-videos.co.uk](https://test-videos.co.uk/) — отвечает 200, набор H.264/H.265/VP9/AV1 в MP4/MKV/WebM разных битрейтов (содержимое страницы не проверено).
- Kodi wiki [Samples](https://kodi.wiki/view/Samples) / [Test files](https://kodi.wiki/view/Test_files) — крупнейший каталог (Dolby Vision P5/P7/P8/P10, HDR10+, LG/Samsung демо, TrueHD/Atmos/DTS:X passthrough-клипы, PGS/VobSub, 3D, interlaced, VC-1), но страницы отдают 403 автоматическим клиентам — открывать в браузере (не проверено). Аналогично [4kmedia.org](https://4kmedia.org/) (HDR10/HLG/DV демо-клипы, 403 для нас — не проверено) и Dolby Developer «Dolby Vision streams» (DNS не разрешился из среды — не проверено).
- [demo.jellyfin.org/stable/web/](https://demo.jellyfin.org/stable/web/) — публичный демо-сервер (сейчас 12.2.0), логин `demo` без пароля (подтверждено модератором на форуме: [thread](https://forum.jellyfin.org/t-jellyfin-demo-credentials)); подходит для smoke-тестов API и UI, но транскод там ограничен.

---

## Краткие выводы для libVLC-клиента

1. Профиль: широкие DirectPlay-списки (по образцу Swiftfin-VLC) + CodecProfile-ограничения по разрешению/битрейту/`VideoRangeType`/`VideoBitDepth`, обязательный HLS-`mp4` TranscodingProfile с `h264,hevc` и расширенным аудио; `MaxStreamingBitrate` из настройки/`BitrateTest`.
2. Субтитры: текстовые `Embed+External`, графические `Embed` (+`Encode` fallback); ASS внешним файлом только если доверяете libass и шрифтам; никогда не объявлять формат только с `Encode`.
3. Аудио: решать passthrough через `AudioManager`/`AudioCapabilities`, при даунмиксе ограничивать `AUDIO_CHANNELS<=2`; применять `NormalizationGain` (цель −23 LUFS).
4. Сегменты/трикплей/главы — через нативные API 10.10+/12; legacy Intro Skipper — только как fallback.
5. Отчёты: `POST /Sessions/Playing*` каждые ~10 с (3 с, как у ATV, тоже приемлемо) с `PlaySessionId`, `PlayMethod`, индексами дорожек; `DELETE /Videos/ActiveEncodings` при стопе/смене качества; `Ping` при долгой паузе транскода.
6. Ниша: сторонних Android TV-клиентов в официальном списке нет; требования к листингу — год разработки/интерес + уникальная функция (двойные субтитры, offset, MPEG-2/VC-1 софт-декод, DTS-HD/TrueHD — всё то, что потеряли с удалением libVLC из официального ATV).
---

# Часть 6. Инструментарий разработки

_Исходный заголовок: Инструментарий разработки: Kotlin Android-клиент Jellyfin (phone + Android TV, libVLC) на Linux

Дата проверки: 2026-10-11. Все URL ниже открывались и проверялись (кроме помеченных «(не проверено)»). Версии — текущие стабильные на дату проверки.

---

## 0. Сводка актуальных версий (проверено)

| Компонент | Версия | Источник |
|---|---|---|
| Android Studio (stable) | **Rabbit 1 \| 2026.2.1** (build 2026.2.1.8), вышла 1 октября 2026; в canary — Rabbit 2 | https://developer.android.com/studio/releases , https://androidstudio.googleblog.com/ |
| AGP (stable) | **9.4.0** (сентябрь 2026): Gradle ≥ 9.6.0, JDK 17 (min и default), Build Tools 36.0.0, NDK 28.2.13676358, max API 37 | https://developer.android.com/build/releases/gradle-plugin |
| Gradle | **9.8.1** (7 октября 2026); ветка 8.x — 8.14.6 | https://gradle.org/releases/ |
| Kotlin | **2.4.21** (8 октября 2026); 2.4.0 — июнь 2026 | https://kotlinlang.org/docs/releases.html |
| KSP | **2.3.12** (9 сентября 2026); требует AGP ≥ 8.12.0 | https://github.com/google/ksp/releases |
| Hilt / Dagger | **2.60.1** | https://github.com/google/dagger/releases , https://dagger.dev/hilt/gradle-setup.html |
| Room | **2.8.5** (сентябрь 2026), Gradle-плагин `androidx.room` | https://developer.android.com/jetpack/androidx/releases/room |
| Compose for TV | `androidx.tv:tv-material:1.1.0`, `androidx.tv:tv-foundation:1.0.0` (stable, май 2026); Compose BOM в примерах Google — `2026.09.00` | https://developer.android.com/jetpack/androidx/releases/tv , https://developer.android.com/training/tv/playback/compose |
| Android Emulator | **37.2.12** (1 октября 2026) | https://developer.android.com/studio/releases/emulator |
| cmdline-tools (Linux) | build 15859902 (short 22.0): `https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip` | https://developer.android.com/studio/install |
| Android CLI (`android`) | 1.0.16500706 (октябрь 2026); первый релиз 0.7 — апрель 2026 | https://developer.android.com/tools/agents/android-cli/release-notes |
| scrcpy | **v5.0.1** | https://github.com/Genymobile/scrcpy |
| LeakCanary | 2.14 | https://square.github.io/leakcanary/getting_started/ |
| Chucker | 4.2.0 | https://github.com/ChuckerTeam/chucker |

Таблица совместимости Android Studio ↔ AGP (с официальной страницы релизов): Rabbit 1 (2026.2.1) и Quail 4 (2026.1.4) поддерживают AGP 7.1–9.4; Quail 2/3 — до 9.3; Quail 1 / Panda 4 — до 9.2; Panda 1 — до 9.0. Минимальные версии для API 37: Panda 3 Patch 1 + AGP 9.1.1; для API 36: Meerkat Patch 1 + AGP 8.9.1.

Матрица AGP 9.x ↔ Gradle ↔ KGP (из release notes каждой версии):

| AGP | Gradle min | JDK | KGP min | Релиз |
|---|---|---|---|---|
| 9.0.x | 9.1.0 | 17 | 2.2.10 (встроенный Kotlin) | январь 2026 |
| 9.1.x | 9.3.1 | 17 | 2.2.10 | апрель 2026 |
| 9.2.x | 9.4.1 | 17 | 2.2.10 | апрель 2026 |
| 9.3.x | 9.5.0 | 17 | не указан | июль 2026 |
| 9.4.0 | 9.6.0 | 17 | не указан | сентябрь 2026 |

Важно про AGP 9: **built-in Kotlin** включён по умолчанию — плагин `org.jetbrains.kotlin.android` больше не применяется (его надо удалить), `kotlin-kapt` несовместим (переходить на KSP или временно `com.android.legacy-kapt`), `android.kotlinOptions {}` мигрирует в `kotlin { compilerOptions {} }`, новый DSL (`android.newDsl=true`), `variantFilter` → `androidComponents.beforeVariants`. AGP 10 (середина 2026+) уберёт опции отката. Подробно:
- [AGP 9.0 release notes](https://developer.android.com/build/releases/agp-9-0-0-release-notes) — список поведенческих изменений, таблица совместимости.
- [Migrate to built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin) — что удалить, как задать compilerOptions, опт-аут `android.builtInKotlin=false` + `android.newDsl=false`.
- [Compose compiler Gradle plugin](https://kotlinlang.org/docs/compose-compiler-migration-guide.html) — с Kotlin 2.0 компилятор Compose живёт в репозитории Kotlin, плагин `org.jetbrains.kotlin.plugin.compose` версионируется вместе с Kotlin (в каталоге: `version.ref = "kotlin"`); при AGP ≥ 9.0 плагин `org.jetbrains.kotlin.android` не нужен; `composeOptions.kotlinCompilerExtensionVersion` больше не используется.

---

## 1. Build toolchain

### 1.1 Android Studio на Linux
- [Install Android Studio](https://developer.android.com/studio/install) — официальный способ: tar.gz (`android-studio-rabbit1-linux.tar.gz`), распаковать в `/usr/local/` (для пользователя) или `/opt/` (для всех), запуск `android-studio/bin/studio`; далее `Tools > Create Desktop Entry`. Для 64-битной Ubuntu документация всё ещё просит 32-битные библиотеки: `sudo apt-get install libc6:i386 libncurses5:i386 libstdc++6:i386 lib32z1 libbz2-1.0:i386`.
- Системные требования Linux (та же страница): любой 64-битный дистрибутив с GNOME/KDE/Unity, **glibc ≥ 2.31**; RAM: 8 ГБ только Studio / **16 ГБ Studio + эмулятор**, рекомендовано 32 ГБ; диск 8/16 ГБ, рекомендован SSD 32+ ГБ; CPU с виртуализацией (Intel VT-x / AMD-V, включена в BIOS), микроархитектура после 2017; GPU для эмулятора — 4 ГБ VRAM (рекомендовано 8 ГБ); экран 1280×800 (рек. 1920×1080). ARM-Linux не поддерживается. Intel N/U-серии «не рекомендуются».
- [JetBrains Toolbox App](https://www.jetbrains.com/toolbox-app/) — официально умеет ставить и обновлять «Google's Android Studio releases and Canary builds». Для пользователя JetBrains IDE это самый удобный путь (параллельно Stable и Canary).
- Snap (`sudo snap install android-studio --classic`, пакет Snapcrafters) и Flathub (`flatpak install flathub com.google.AndroidStudio`, неофициальный community-пакет) существуют, но preview-сборок в них нет и они отстают от tarball — (ссылки на страницы snapcraft/flathub не проверялись, команды — из сторонних гайдов).
- Релизы/каналы: [Android Studio blog](https://androidstudio.googleblog.com/) — хронология: Rabbit 1 stable 01.10.2026, Rabbit 2 Canary 4 08.10.2026, Quail 4 Patch 1 18.09.2026. Фичи Rabbit 2 Canary (Compose Preview Screenshot Testing через test suites, ресайз окна Running Devices, BYOA-агенты, Journeys, Parallel sync с Gradle 9.4) — [Preview release notes](https://developer.android.com/studio/preview/features).

### 1.2 KVM / ускорение эмулятора на Linux
- [Configure hardware acceleration](https://developer.android.com/studio/run/emulator-acceleration) — проверка: `egrep -c '(vmx|svm)' /proc/cpuinfo` (>0), `sudo apt-get install cpu-checker && sudo kvm-ok`; установка `sudo apt-get install qemu-kvm libvirt-daemon-system libvirt-clients bridge-utils`; проверка эмулятором `emulator -accel-check` (ожидается `KVM (version 12) is installed and usable`). Эмулятор с KVM нельзя запускать внутри другой ВМ (VirtualBox/VMware/Docker). GPU-режимы: `-gpu auto|host|software|lavapipe|swiftshader|swangle`; `swiftshader_indirect` объявлен deprecated в эмуляторе 36.4.9, `mesa` — deprecated; на Linux `-gpu software` теперь выбирает Lavapipe. Пользователя нужно добавить в группу `kvm` (в доке — «specific user permissions», конкретика в инструкции Ubuntu KVM).

### 1.3 Только командная строка
- [sdkmanager](https://developer.android.com/tools/sdkmanager) — расположение `android_sdk/cmdline-tools/latest/bin/sdkmanager` (после распаковки zip нужно вручную создать подпапку `latest` и перенести туда `bin`, `lib`, `NOTICE.txt`, `source.properties`). Команды: `sdkmanager --list`, `sdkmanager "platform-tools" "platforms;android-36"`, `sdkmanager "build-tools;36.0.0"`, `sdkmanager --licenses`, опции `--sdk_root=`, `--channel=0..3`, `--update`, `--uninstall`. **Внимание:** страница теперь помечает `sdkmanager` как deprecated в пользу Android CLI (`android sdk install|list|update|remove`).
- [avdmanager](https://developer.android.com/tools/avdmanager) — `avdmanager create avd -n name -k "system-images;android-25;google_apis;x86" [-c size] [-f] [-p path]`, `avdmanager list device|target|avd`, `delete avd -n`. Тоже помечен deprecated (→ `android emulator create|remove`). Флаг `-d/--device` для выбора hardware-профиля на онлайн-странице не описан, но присутствует в `avdmanager create avd --help` (не проверено по онлайн-доке).
- [Emulator command line](https://developer.android.com/studio/run/emulator-commandline) — `emulator -avd NAME` / `emulator @NAME`, `-list-avds`, `-no-snapshot` (полностью отключить Quick Boot), `-no-snapshot-load`, `-no-snapshot-save`, `-gpu MODE`, `-no-window`, `-memory 1536..8192`, `-no-audio`/`-noaudio`, `-no-boot-anim`, `-wipe-data`, `-netdelay`, `-accel auto|on|off`, `emulator -help-all`. `-skin`/`-skindir` помечены deprecated. Страница тоже отмечает, что `emulator` deprecated в пользу `android emulator create|start|stop|list`.
- **Android CLI** (новый официальный инструмент Google, бинарник `android`):
  - [Обзор/скачивание](https://developer.android.com/tools/agents) и [Overview](https://developer.android.com/tools/agents/android-cli/overview) — установка со страницы загрузки (в release notes упоминаются `apt-get`, `winget`, `homebrew`), `android update`, `android init`; группы команд `android sdk`, `android emulator`, `android device remote`, `android screen capture`, `android layout`, `android studio render-compose-preview` и др.
  - [android sdk install](https://developer.android.com/tools/agents/android-cli/commands/sdk_install) — синтаксис `android sdk install [--beta] [--canary] [--force] <package>[@<version>]`, пути через слеш: `android sdk install emulator platforms/android-34`; список — `android sdk list --all`.
  - [android emulator create](https://developer.android.com/tools/agents/android-cli/commands/emulator_create) — `android emulator create --list-profiles`, `android emulator create <profile>` (дефолт `medium_phone`). Выбор образа/TV-профиля на странице не описан — проверяйте `--list-profiles`.
  - [android emulator start](https://developer.android.com/tools/agents/android-cli/commands/emulator_start) — `android emulator start [--cold] [--headless] <device>`; команда блокируется до окончания загрузки.
  - [Release notes](https://developer.android.com/tools/agents/android-cli/release-notes) — 1.0.16500706 (октябрь 2026); `android emulator` на Windows отключён; TV-профили в заметках не упоминаются.
- Сборка из терминала: `./gradlew assembleDebug`, `./gradlew installDebug`, `./gradlew :app:assembleRelease`, `./gradlew connectedCheck` (инструментальные тесты на подключённом устройстве/эмуляторе). Это стандартные задачи AGP (примеры `./gradlew assembleDebug` / `connectedCheck` встречаются в README jellyfin-androidtv CI и android-emulator-runner).

### 1.4 JDK
- [Java versions in Android builds](https://developer.android.com/build/jdks) — три разных JDK: JBR (запускает IDE; рекомендуется не трогать, не задавать `STUDIO_JDK`), **Gradle JDK** (из терминала берётся `JAVA_HOME`/`java` в PATH; в Studio — `Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK`, рекомендован макрос `GRADLE_LOCAL_JAVA_HOME`; с Panda 1 новые проекты используют Gradle Daemon JVM criteria), **toolchain JDK** (компиляция). Рекомендуется всегда задавать toolchain: `java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }`; `kotlinOptions.jvmTarget` нужен только при Kotlin < 2.2.
- Требование AGP 9.x: **JDK 17** (минимум и default). Для терминальной сборки на Ubuntu достаточно Temurin 17 или 21 (Gradle 9.8 работает на 17+); Android Studio bundled JBR на середину 2026 — 21 (из обсуждений на platform.jetbrains.com, официально версия JBR в release notes не указана — не проверено). Практичный выбор: Temurin 21 как `JAVA_HOME` + toolchain 17 в проекте.

### 1.5 Gradle: каталоги версий, кэш конфигурации, производительность
- [Version catalogs](https://docs.gradle.org/current/userguide/version_catalogs.html) — `gradle/libs.versions.toml`, секции `[versions]`, `[libraries]`, `[bundles]`, `[plugins]`; доступ `libs.xxx`, `alias(libs.plugins.xxx)`.
- [Configuration cache](https://docs.gradle.org/current/userguide/configuration_cache.html) — `org.gradle.configuration-cache=true`; предпочтительный режим с Gradle 9.0, станет default в Gradle 10; IDE sync его не использует.
- [Optimize your build speed](https://developer.android.com/build/optimize-your-build) — пример `org.gradle.jvmargs=-Xmx6g -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8 -XX:+UseParallelGC -XX:MaxMetaspaceSize=1g`; `org.gradle.configuration-cache=true`; kapt → KSP («kapt significantly slower»); non-transitive R (default с AGP 8.0); `resourceConfigurations("en","xxhdpi")` для dev-флейвора; статичные версии зависимостей; `android.enableJetifier=false`. `org.gradle.caching=true` и `org.gradle.parallel=true` — стандартные свойства Gradle (на этой странице не упомянуты).

### 1.6 KSP для Hilt/Room
- [Hilt Gradle setup](https://dagger.dev/hilt/gradle-setup.html) — плагины `com.google.devtools.ksp` и `com.google.dagger.hilt.android` (2.60.1), зависимости `implementation("com.google.dagger:hilt-android:2.60.1")`, `ksp("com.google.dagger:hilt-compiler:2.60.1")`, для тестов `kspAndroidTest`/`kspTest`.
- [Room releases](https://developer.android.com/jetpack/androidx/releases/room) — `ksp("androidx.room:room-compiler:2.8.5")`, плагин `id("androidx.room")` + `room { schemaDirectory("$projectDir/schemas") }`.
- Совместимость KSP 2.3.12 с Kotlin 2.4.21 на странице релиза явно не заявлена (не проверено) — KSP2 с 2025 года декаплирован от версии компилятора, но при обновлении Kotlin стоит сверяться с release notes KSP.

### 1.7 Варианты сборки phone vs TV, подпись, R8
- [Configure build variants](https://developer.android.com/build/build-variants) — `flavorDimensions += "..."`, `productFlavors { create("tv") { dimension = ... } }`, source sets `src/<flavor>/`, отключение вариантов через `androidComponents { beforeVariants { } }`. **Рекомендация для Jellyfin-клиента:** один `applicationId` и один APK/AAB с `LEANBACK_LAUNCHER` + обычным `LAUNCHER` (так делают и Google в [Get started with TV apps](https://developer.android.com/training/tv/start/start): `uses-feature android.software.leanback required=false`, `android.hardware.touchscreen required=false`, баннер `android:banner` 320×180 px xhdpi с текстом внутри, отдельная TV-activity или одна activity с обеими категориями). Флейворы имеют смысл только если нужны разные `applicationId`/магазины (например, отдельная сборка для Amazon Appstore без GMS).
- [Sign your app](https://developer.android.com/studio/publish/app-signing) — `keystore.properties` вне VCS, `signingConfigs { create("release") { ... } }`, `keytool -export -rfc -keystore ... -alias ... -file upload_certificate.pem`, Play App Signing обязателен для новых приложений, CLI — `apksigner`.
- [Shrink/optimize (R8)](https://developer.android.com/build/shrink-code) — AGP < 9.3: `isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), ...)` (`proguard-android.txt` с AGP 9.1 не поддерживается); AGP ≥ 9.3: `release { optimization { enable = true } }` и keep-правила в `src/<variant>/keepRules/*.keep`; задача `./gradlew :app:analyzeReleaseR8Config` (R8 Configuration Analyzer). Для libVLC потребуются consumer-правила библиотеки (libvlc-android их поставляет — не проверено).

### 1.8 adb и TV-устройства
- [Android Debug Bridge](https://developer.android.com/tools/adb) — wireless debugging: Android 11+ для телефонов, **для TV и Wear OS — Android 13+ (API 33)**; `adb pair ipaddr:port` (код из «Pair using pairing code»), затем автоподключение по mDNS; диагностика `adb server-status`, `adb mdns track-services --proto-text`; adb 37.0.0 + Android 17 добавляют «adb Wi-Fi 2.0». Legacy-способ (работает и на старых TV): по USB `adb tcpip 5555`, отключить кабель, `adb connect <ip>:5555`, `adb devices`.
- [Get started with TV apps — физическое устройство](https://developer.android.com/training/tv/start/start) — включение Developer options на Android TV: `Settings > Device > About > Build` (нажать несколько раз до «You are now a developer!»), затем `Settings > Preferences > Developer options > Debugging > USB debugging`. На Google TV (Chromecast with Google TV / Google TV Streamer) путь `Settings > System > About > Android TV OS build` ×7 — по сторонним гайдам (allaboutchromecast.com, aftvnews.com), официальной страницы Google не найдено (не проверено).
- [Fire TV: Connect to Fire TV through ADB](https://developer.amazon.com/docs/fire-tv/connecting-adb-to-device.html) — `Settings > My Fire TV > Developer Options > ADB Debugging` (+ Apps from Unknown Sources); на части прошивок Developer Options скрыты: `Settings > My Fire TV > About > Your TV` ×7; IP брать из `About > Network`; `adb connect <ip>:5555` (порт 5555–5585); подтвердить «Always allow from this computer».
- Отправка клавиш (keycodes подтверждены по [keycodes.h в AOSP](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/include/android/keycodes.h)): DPAD_UP=19, DOWN=20, LEFT=21, RIGHT=22, DPAD_CENTER=23, BACK=4, HOME=3, MENU=82, MEDIA_PLAY_PAUSE=85, MEDIA_REWIND=89, MEDIA_FAST_FORWARD=90, SEARCH=84, TV=170, CAPTIONS=175, SETTINGS=176, TV_INPUT=178, ASSIST=219.

```bash
adb connect <ip-тв>:5555
adb -s <ip-тв>:5555 shell input keyevent KEYCODE_DPAD_CENTER
adb shell input keyevent KEYCODE_DPAD_RIGHT
adb shell input keyevent KEYCODE_MEDIA_PLAY_PAUSE
adb shell input keyevent KEYCODE_BACK
adb shell input keyevent 85        # то же, что KEYCODE_MEDIA_PLAY_PAUSE
adb shell input text "hello"
adb shell am start -n org.example.app/.MainActivity
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
(Команда `adb shell input keyevent` — стандартный системный бинарник `input`; на странице ADB-доки она не описана, но её поведение общеизвестно.)

---

## 2. Android TV эмуляторы

### 2.1 Hardware-профили и системные образы (проверено по официальным репозиториям SDK)
- Device definitions для TV (файл [tv.xml в sdklib](https://android.googlesource.com/platform/tools/base/+/refs/heads/mirror-goog-studio-main/sdklib/src/main/resources/com/android/sdklib/devices/tv.xml)): **`tv_4k` «Television (4K)» 3840×2160 xxxhdpi**, **`tv_1080p` «Television (1080p)» 1920×1080 xhdpi**, **`tv_720p` «Television (720p)» 1280×720 tvdpi**; у всех nav = `dpad`, tag `android-tv`.
- [Create and manage virtual devices](https://developer.android.com/studio/run/managing-avds) — в Device Manager Device Type = Android TV / Google TV; «Navigation Style: D-pad» — события от внешнего пульта идентичны D-pad-событиям; для Android TV аппаратная клавиатура в конфиге AVD отключена, камеры нет. Образы с Play Store (release-key) не дают `adb root`; для root берите AOSP-образы.
- Репозиторий образов **Google TV** ([sys-img2-1.xml](https://dl.google.com/android/repository/sys-img/google-tv/sys-img2-1.xml), канал stable):
  - API **36**: `system-images;android-36;google-tv;x86_64`, `...;google-tv;arm64-v8a`, `...;google-tv;x86`, плюс 16 KB page-size варианты `system-images;android-36;google-tv-ps16k;x86_64` и `...;google-tv-ps16k;arm64-v8a`.
  - API 34: `system-images;android-34;google-tv;x86` и `...;arm64-v8a` (**x86_64 нет**).
  - API 33: `...;android-33;google-tv;x86`, `arm64-v8a`. API 31: `x86`, `arm64-v8a`. API 30: `x86`.
- Репозиторий образов **Android TV (AOSP)** ([sys-img2-1.xml](https://dl.google.com/android/repository/sys-img/android-tv/sys-img2-1.xml)): API 36 — `system-images;android-36;android-tv;x86_64`, `arm64-v8a`, `x86`; API 34/33/31 — только `x86` и `arm64-v8a`; API 21–30 — `x86` (и armeabi-v7a для 21/23).
- **Выводы:** TV-образов для API **35 не существует**; единственные x86_64 TV-образы — API 36 (Android 16). Для API 34 и ниже на x86_64-хосте используйте 32-битные `x86`-образы (работают под KVM). arm64-образы на x86-хосте работать не будут. Google TV образы требуют принятия лицензии `android-googletv-license`.

### 2.2 Управление TV-эмулятором
- [Extended controls](https://developer.android.com/studio/run/emulator-extended-controls) — раздел **Directional Pad** появляется, если hardware-профиль включает D-pad (у TV-профилей — да). Полный список горячих клавиш — внутри эмулятора: **F1** (Help pane) в standalone-режиме; в embedded-режиме — `Settings > Keymap > Running Devices`. Чтобы шорткаты работали, в General должно быть «Send keyboard shortcuts: Emulator controls».
- [Run apps on the Android Emulator](https://developer.android.com/studio/run/emulator) — Ctrl+M симулирует Menu; требования эмулятора 16 ГБ RAM / 16 ГБ диска.
- Практическая раскладка (официальной таблицы для TV в доке нет — по опыту, не проверено): стрелки = DPAD_*, Enter = DPAD_CENTER, Esc = BACK, Home = HOME, Ctrl+M = MENU; Play/Pause и медиаклавиши с клавиатуры не маппятся — используйте `adb shell input keyevent KEYCODE_MEDIA_PLAY_PAUSE` или панель D-pad в Extended controls.
- Посылать клавиши в эмулятор: `adb -e shell input keyevent KEYCODE_DPAD_DOWN` (`-e` выбирает единственный эмулятор).

### 2.3 Производительность, ограничения
- KVM + `-gpu host` (или `auto`) — лучшая производительность на Linux ([acceleration docs](https://developer.android.com/studio/run/emulator-acceleration)); `-no-snapshot` для чистых стартов; `-memory 4096`; TV-профиль 4K тяжёлый — для разработки UI используйте `tv_1080p`.
- Release notes эмулятора ([ссылка](https://developer.android.com/studio/releases/emulator)): 36.4.10 — фикс краша TV-эмулятора при hover; 36.4.9 — Lavapipe как software-рендер по умолчанию, `-gpu software`; 36.5.10 — новый сетевой стек (AVD видят друг друга — удобно для теста discovery Jellyfin по UDP 7359); 37.1.11 — Vulkan-расширения для API 37. Про аппаратное декодирование видео упоминаний нет; единственное упоминание — опция CUDA-декода VPX (`ANDROID_EMU_MEDIA_DECODER_CUDA_VPX=1`).
- Ограничения для видеоплеера (общие знания, не проверено по официальной доке): в эмуляторе нет аппаратных кодеков SoC — MediaCodec предоставляет только программные `c2.android.*`/`OMX.google.*` декодеры, поэтому libVLC с `--codec=mediacodec_ndk` получит software-путь, HEVC 10-bit/4K будет тормозить; HDR (HDR10/DV) не воспроизводится как HDR; audio passthrough (AC3/DTS/TrueHD bitstream) и HDMI CEC проверить нельзя; тональность Dolby Vision профилей — только на железе. Тестируйте транскодинг-решения Jellyfin на реальном устройстве.

### 2.4 Headless / CI
- `emulator -avd tv36 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader` (на новых версиях `-gpu software`), затем `adb wait-for-device` и polling `getprop sys.boot_completed`.
- [ReactiveCircus/android-emulator-runner](https://github.com/ReactiveCircus/android-emulator-runner) — `target` официально принимает **`android-tv` и `google-tv`**, `arch: x86_64|x86|arm64-v8a`, `profile` (например `tv_1080p` — имя из `avdmanager list device`), `api-level: 36`, `emulator-options` (default `-no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim`), `script: ./gradlew connectedCheck`. Перед шагом на Linux включить KVM udev-правилом (см. раздел 6).
- [GitHub changelog: hardware-accelerated Android virtualization](https://github.blog/changelog/2024-04-02-github-actions-hardware-accelerated-android-virtualization-now-available/) — KVM доступен на 2-vCPU Linux-раннерах GitHub; udev-правило `KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"`.

### 2.5 Альтернативные эмуляторы
- **Genymotion** — [Desktop docs](https://docs.genymotion.com/desktop/) и [список шаблонов](https://rozz.genymotion.com/pages/which-devices-templates-are-available-with-genymotion-deskto.html) — только телефоны/планшеты (и Automotive-туториалы); **TV-шаблонов нет**; SaaS-образы до Android 16.
- **Waydroid** — [docs.waydro.id](https://docs.waydro.id/) — контейнер с LineageOS (Android 13), **официальных TV-образов нет**; существуют community-сборки: [supechicken/waydroid-androidtv-builds](https://github.com/supechicken/waydroid-androidtv-builds) (Android TV образы с VA-API/GApps/Widevine L3 — не проверено на работоспособность). Для разработки под D-pad малопригоден, но может подойти для проверки libVLC с VA-API.
- **Android-x86 / Bliss OS** — TV/leanback-сборок для PC в официальных каналах не найдено; Android-x86 «dormant», Bliss OS — desktop-ориентированные ISO (не проверено).
- **Cuttlefish** — [Get started](https://source.android.com/docs/setup/create/cuttlefish-use); в [AndroidProducts.mk](https://android.googlesource.com/device/google/cuttlefish/+/refs/heads/main/AndroidProducts.mk) есть TV-таргеты **`aosp_cf_x86_64_tv`** и `aosp_cf_x86_tv` (lunch `aosp_cf_x86_64_tv-trunk_staging-userdebug`). Готовые сборки — на ci.android.com (нужно выбрать этот таргет); запуск `launch_cvd`, просмотр через WebRTC на localhost:8443, adb работает. Это AOSP без GMS — полезно для CI/проверки чистого Android TV, но без Google TV launcher.
- **Firebase Test Lab** ([каталог устройств](https://firebase.google.com/docs/test-lab/android/available-testing-devices)) — виртуальные `GoogleTvEmulator/30` и `AmatiTvEmulator/29` помечены deprecated (удаление планировалось 2025-05-31), замена `GoogleTv.arm/31` — «available soon». Список: `gcloud firebase test android models list`.
- **Fire TV** — [How Fire TV differs from Android TV](https://developer.amazon.com/public/solutions/devices/fire-tv/docs/amazon-fire-tv-differences-from-android-tv-development): «**Fire TV doesn't support emulators for testing**» — только физическое устройство по ADB; нет Google Play services (Firebase с GMS не работает), Leanback частично (без SearchFragment), пульт имеет те же D-pad/Select/Back/Play-Pause + Rewind/FF/Menu. Vega OS (новая платформа Fire TV на базе не-Android) имеет свой Vega Virtual Device — к Android-клиенту не относится.

### 2.6 Дешёвое тестовое железо и управление с телефона/ПК
- Google TV Streamer (4K) — [store.google.com](https://store.google.com/us/product/google_tv_streamer?hl=en-US) (страница есть; спецификации не распарсились — не проверено); актуальные прошивки: [Chromecast & Google TV Streamer firmware versions](https://support.google.com/chromecast/answer/7124014?hl=en) (Streamer — build UTTK.260317.003, Chromecast HD — UTTC.250917.004). Это эталон Google TV UX; Chromecast with Google TV (HD/4K) — ещё дешевле на вторичном рынке.
- NVIDIA SHIELD TV / SHIELD TV Pro — [nvidia.com/shield](https://www.nvidia.com/en-us/shield/) (Pro — $299); лучший декодер/passthrough для проверки AC3/DTS/TrueHD и Dolby Vision — классическое устройство для тестов медиаплееров.
- Xiaomi TV Stick 4K / Mi Box S — mi.com отдал 403 для автоматического запроса (ссылка не проверена); дешёвый Android TV-девайс с распространённым Amlogic SoC.
- Fire TV Stick (4K/4K Max) — самый дешёвый способ проверить Fire OS; adb по сети (см. 1.8).
- Телефон как пульт: [Use your phone as the remote for your Chromecast or Google TV Streamer](https://support.google.com/chromecast/answer/11221499?hl=en) — встроено в Google TV app (Android ≥ 4.27.8.93) и Google Home; D-pad/свайп, клавиатура, громкость.
- Протокол пульта из кода/скриптов: [tronikos/androidtvremote2](https://github.com/tronikos/androidtvremote2) — Python-библиотека «Android TV Remote protocol v2» (тот же протокол, что в Google TV app), без ADB, pairing по коду, отправка keycodes; PyPI 0.2.1, есть в Ubuntu как `python-androidtvremote2`. Удобно для автоматизации сценариев навигации на реальной приставке.
- scrcpy — см. раздел 3; для TV-приставок по `adb connect` это самый удобный «монитор + пульт» на Linux-десктопе.

---

## 3. Телефонные эмуляторы и реальные устройства
- Device Manager / AVD ([managing-avds](https://developer.android.com/studio/run/managing-avds)) — профили Pixel (`android emulator create --list-profiles` / `avdmanager list device`), вкладка Recommended для образов; foldable/tablet имеет смысл только для адаптивного UI телефона (Pixel Fold / Pixel Tablet профили есть в Paparazzi и AVD).
- [Run apps on a hardware device](https://developer.android.com/studio/run/device) — пара по Wi-Fi через QR/код (`Pair Devices Using Wi-Fi`), device mirroring в окне Running Devices, настройки `Settings > Tools > Device Mirroring` (включая проброс звука «Redirect audio from local devices»).
- **scrcpy**:
  - [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) — v5.0.1; Linux/Win/mac; `--keyboard=uhid` (`-K`) — физическая клавиатура, `--otg`, аудио (Android 11+), запись `--record=file.mp4`.
  - [Linux install](https://github.com/Genymobile/scrcpy/blob/master/doc/linux.md) — пакет в Ubuntu/Debian и snap помечены **obsolete**; рекомендуется статический релиз с GitHub или `install_release.sh` (apt-зависимости ffmpeg, SDL3, libusb, adb, meson/ninja).
  - [Connection (TCP/IP)](https://github.com/Genymobile/scrcpy/blob/master/doc/connection.md) — `scrcpy --tcpip` (автоматически переводит USB-устройство в TCP/IP), `scrcpy --tcpip=<ip-тв>` (порт 5555 по умолчанию), `-s ip:port`, `-e` (единственное TCP/IP-устройство), `-d` (USB).
  - [Shortcuts](https://github.com/Genymobile/scrcpy/blob/master/doc/shortcuts.md) — MOD (левый Alt/Super): MOD+h Home, MOD+b Back, MOD+s App switch, MOD+m Menu, MOD+↑/↓ громкость, MOD+p Power, MOD+f fullscreen, MOD+o выключить экран (продолжая зеркалить), MOD+Shift+v вставить текст. Обычные стрелки и Enter прокидываются на устройство как клавиши — на Android TV это DPAD_*/DPAD_CENTER, поэтому scrcpy работает как полноценный пульт (маппинг стрелок по опыту; в shortcuts.md не задокументирован).

```bash
adb connect <ip-тв>:5555
scrcpy -e --max-size 1280 --no-audio      # зеркало TV-приставки с управлением стрелками
scrcpy --tcpip=<ip-тв> --record=tv-session.mp4
```

---

## 4. Смотреть UI без полного эмулятора

### 4.1 Compose @Preview
- [Preview your UI with composable previews](https://developer.android.com/develop/ui/compose/tooling/previews) — параметры `device` (`"id:pixel_4"` или `spec:width=...,height=...,dpi=...`), `uiMode` (константы `Configuration.UI_*`), `showSystemUi`, `fontScale`, `locale`, `widthDp/heightDp`; multipreview-шаблоны `@PreviewScreenSizes`, `@PreviewFontScales`, `@PreviewLightDark`, `@PreviewDynamicColors` (ui-tooling-preview ≥ 1.6.0-alpha01) и собственные аннотации; **Interactive mode** (кнопка на превью — клики/ввод/анимации в песочнице); **Run Preview** разворачивает превью как Activity на устройстве; `LocalInspectionMode`; `@PreviewParameter`.
- [Devices reference](https://developer.android.com/reference/kotlin/androidx/compose/ui/tooling/preview/Devices) — объект с предопределёнными устройствами (DEFAULT, DESKTOP, FOLDABLE, AUTOMOTIVE_1024p, Nexus/Pixel… — страница рендерится динамически, полный список и точные строки `TV_720p`/`TV_1080p`/`TV_4K` через fetch подтвердить не удалось — **(не проверено)**). Гарантированно работающая форма — ID из tv.xml: `@Preview(device = "id:tv_1080p")`, `"id:tv_720p"`, `"id:tv_4k"` (формат `id:` подтверждён докой, ID — в tv.xml). Рекомендуемая TV-мультипревью:

```kotlin
@Preview(name = "TV 1080p", device = "id:tv_1080p", showSystemUi = true,
         uiMode = Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "TV 720p", device = "id:tv_720p", showSystemUi = true,
         uiMode = Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_YES)
annotation class TvPreviews
```
- Для Compose for TV ([Use Jetpack Compose on Android TV](https://developer.android.com/training/tv/playback/compose)) — `androidx.tv:tv-material` используется **вместо** `androidx.compose.material3`; смешивать нельзя (разные `MaterialTheme`); сэмплы `android/tv-samples` (JetStream, TV Material Catalog), codelab «Introduction to Compose for TV».
- Live Edit — на странице превью описано как «live updates to your composable preview as you make your edits»; Live Edit также включается в Layout Inspector.
- Android CLI: `android studio render-compose-preview` — рендер превью из терминала (команда есть в списке Android CLI; синтаксис не проверен).

### 4.2 Layout Inspector и Running Devices
- [Layout Inspector](https://developer.android.com/studio/debug/layout-inspector) — embedded в Running Devices (кнопка Toggle Layout Inspector), счётчики рекомпозиций, Deep Inspect, снапшоты, overlay-картинка, Live Edit; поддерживаются эмуляторы и физические устройства через mirroring; CLI — `android layout`.
- Running Devices / device mirroring ([Run apps on a hardware device](https://developer.android.com/studio/run/device)) — зеркало любого устройства с USB/wireless debugging прямо в IDE; TV-устройства отдельно не упомянуты, но mirroring работает через adb-стрим, поэтому Android TV по `adb connect` зеркалится так же (по опыту, не проверено официально).
- [Android Device Streaming](https://developer.android.com/studio/run/android-device-streaming) — удалённые реальные Pixel 9/9a/Pro/Pro XL/9 Pro Fold и партнёрские лаборатории (Samsung, Xiaomi, OPPO, OnePlus, vivo, Transsion); бесплатные минуты на Spark-плане; **TV-устройств нет**; CLI `android device remote`.

### 4.3 Screenshot-тесты для TV-лейаутов
- [Compose Preview Screenshot Testing](https://developer.android.com/studio/preview/compose-screenshot-testing) — плагин `com.android.compose.screenshot` 0.0.1-alpha16, `android.experimental.enableScreenshotTest=true`, source set `screenshotTest`, аннотация `@PreviewTest`, задачи `./gradlew updateDebugScreenshotTest` / `validateDebugScreenshotTest`, отчёт `build/reports/screenshotTest/preview/<variant>/index.html`. Стандалон-плагин **deprecated с AGP 9.5.0-alpha03**.
- [Screenshot testing with AGP test suites](https://developer.android.com/studio/preview/compose-screenshot-testing-with-testsuites) — новый путь: AGP ≥ 9.5.0-alpha03, Rabbit 1 Canary 4+, `android.experimental.testSuiteSupport=true`, `testOptions { screenshotTests.create("screenshotTest") { engineVersion = "0.0.1-alpha16"; targetVariants.add("debug") } }`, задачи `update{Suite}{Target}{Variant}TestSuite` / `test{Suite}...TestSuite`. Поскольку движок рендерит `@Preview` с параметром `device`, TV-превью (`id:tv_1080p`) становятся регрессионными скриншотами (прямо в доке TV не упомянут).
- [Roborazzi](https://github.com/takahirom/roborazzi) — JVM/Robolectric (`@GraphicsMode(NATIVE)`), плагин `io.github.takahirom.roborazzi`, задачи `recordRoborazziDebug` / `compareRoborazziDebug` / `verifyRoborazziDebug`, автогенерация тестов из `@Preview` (`generateComposePreviewRobolectricTests`), конфигурация устройства через `@Config(qualifiers = ...)`. TV-квалификатор: Robolectric разбирает строку по правилам Android-ресурсов ([Robolectric device configuration](https://robolectric.org/device-configuration/)), а UI-mode `television` — валидный квалификатор ресурсов с API 13 ([Providing resources](https://developer.android.com/guide/topics/resources/providing-resources)), поэтому ожидается рабочей строка `"w960dp-h540dp-land-xhdpi-television"` (1920×1080 px при xhdpi) — поддержка `television` именно в Robolectric не проверена.
- [Paparazzi](https://github.com/cashapp/paparazzi) — 2.0.0-alpha05.1, без эмулятора (layoutlib), `recordPaparazziDebug` / `verifyPaparazziDebug`; в [DeviceConfig.kt](https://raw.githubusercontent.com/cashapp/paparazzi/master/paparazzi/src/main/java/app/cash/paparazzi/DeviceConfig.kt) **нет TV-пресетов** — нужно создать `DeviceConfig(screenWidth = 1920, screenHeight = 1080, density = Density.XHIGH, uiMode = UiMode.TELEVISION, orientation = LANDSCAPE)` (поля `uiMode`/`orientation` — не проверены по исходнику, но пресеты показывают настраиваемые width/height/density).

### 4.4 UI-тесты с D-pad
- [Test focus navigation (Compose)](https://developer.android.com/develop/ui/compose/touch-input/focus/testing-focus) — `node.performKeyInput { pressKey(Key.DirectionDown) }`, `assertIsFocused()` / `assertIsNotFocused()`, `withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) }`; тестовая среда по умолчанию в touch mode — для TV переключайте input mode (см. ссылку на default input mode в доке). Поддерживаются `Key.DirectionUp/Down/Left/Right`, `Key.DirectionCenter`.
- [Compose testing APIs](https://developer.android.com/develop/ui/compose/testing/apis) — `performKeyPress(keyEvent)`, matchers `hasTestTag`, `assertIsDisplayed`.
- Espresso для View-based экранов: `ViewActions.pressKey(KeyEvent.KEYCODE_DPAD_RIGHT)` (стандартный API Espresso; страница не проверялась).

---

## 5. Прочие инструменты разработки

### 5.1 IntelliJ IDEA vs Android Studio
- [IntelliJ IDEA Help: Android](https://www.jetbrains.com/help/idea/android.html) — Android-поддержка **не входит в комплект** IDEA (unbundled с 2023.3); ставятся плагины **Android** и **Android Design Tools** из Marketplace (Community-редакция тоже подходит).
- [Compose previews (Kotlin Multiplatform docs)](https://kotlinlang.org/docs/multiplatform/compose-previews.html) — превью `androidx.compose.ui.tooling.preview.Preview` рендерятся и в Android Studio, и в IntelliJ IDEA (с плагином Kotlin Multiplatform); **но AGP 9.0 поддерживается в стабильной Android Studio и «пока не поддерживается» в IntelliJ IDEA** (обещали Q1 2026; на дату проверки community-отчёты в Slack kotlinlang — IDEA 2026.1 всё ещё не работает с AGP 9.x превью). Практика: для проекта на AGP 9.4 **нужна Android Studio** — Compose Preview, Layout Inspector, Running Devices, Device Manager/AVD, Logcat, Image Asset wizard и App Inspection там доступны полноценно; IDEA пригодна для редактирования/рефакторинга, а сборка/запуск — через Gradle/CLI. Ставьте Android Studio через JetBrains Toolbox рядом с IDEA — настройки/кеймапы переносятся.

### 5.2 Инспекция трафика Jellyfin API
- jellyfin-sdk-kotlin: [репозиторий](https://github.com/jellyfin/jellyfin-sdk-kotlin), [документация](https://kotlin-sdk.jellyfin.org/), [Getting started](https://kotlin-sdk.jellyfin.org/guide/getting-started) — координаты `org.jellyfin.sdk:jellyfin-core`, `createJellyfin { clientInfo = ClientInfo(...); context = androidContext }`. В `jellyfin-api/build.gradle.kts` на JVM объявлена **только зависимость OkHttp** ([файл](https://github.com/jellyfin/jellyfin-sdk-kotlin/blob/master/jellyfin-api/build.gradle.kts)) — Ktor-клиента там нет. Значит, подходят OkHttp-инструменты (Chucker, `HttpLoggingInterceptor`), если SDK даёт передать свой `OkHttpClient`/interceptor — способ передачи не проверен в доке.
- [Chucker](https://github.com/ChuckerTeam/chucker) — 4.2.0, `debugImplementation("com.github.chuckerteam.chucker:library:4.2.0")` + `releaseImplementation(...:library-no-op:4.2.0)`, `OkHttpClient.Builder().addInterceptor(ChuckerInterceptor(context))`; **интеграции с Ktor нет**. На TV UI Chucker (уведомления + Activity с тач-UI) неудобен — лучше `adb logcat` + HttpLoggingInterceptor или прокси.
- [Ktor client Logging](https://ktor.io/docs/client-logging.html) — на случай собственного Ktor-слоя: `io.ktor:ktor-client-logging`, `install(Logging) { level = LogLevel.HEADERS; sanitizeHeader { it == "Authorization" } }`, на Android — `org.slf4j:slf4j-android` (Ktor 3.6.0).
- [mitmproxy: install system-trusted CA on Android](https://docs.mitmproxy.org/stable/howto/install-system-trusted-ca-android/) — приложения с targetSdk ≥ 24 игнорируют пользовательские CA; на эмуляторе: `emulator -avd NAME -writable-system`, `adb root`, (API ≥ 29) `adb shell avbctl disable-verification && adb reboot && adb root`, `adb remount`, `openssl x509 -inform PEM -subject_hash_old -in mitmproxy-ca-cert.cer | head -1` → `<hash>.0` → `adb push <hash>.0 /system/etc/security/cacerts && adb shell chmod 644 ...`; для Play-образов — Magisk-модуль. Проще для своего приложения: [Network security config](https://developer.android.com/privacy-and-security/security-config) с `<debug-overrides><trust-anchors><certificates src="user"/></trust-anchors></debug-overrides>` — доверять пользовательским CA только в debuggable-сборках, затем на устройстве/эмуляторе поставить CA через mitm.it ([mitmproxy certificates](https://docs.mitmproxy.org/stable/concepts/certificates/)).
- HTTP Toolkit — https://httptoolkit.com/android/ (сайт отдал 403 для автоматического запроса — ссылка не проверена): ADB-настройка, автоматическая инъекция системного сертификата в эмулятор/root-устройство.
- [LeakCanary](https://square.github.io/leakcanary/getting_started/) — `debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")`; важно для плеера (утечки Activity/Surface при выходе из playback).

### 5.3 Jellyfin-сервер для разработки и медиаинструменты
- [Jellyfin in a container](https://jellyfin.org/docs/general/installation/container/) — образ `jellyfin/jellyfin` (также `ghcr.io/jellyfin/jellyfin`), порты `8096/tcp` (HTTP/web), `7359/udp` (auto-discovery), volumes `/config`, `/cache`, `/media` (можно read-only), опционально `/dev/dri` для HW-транскодинга, `JELLYFIN_PublishedServerUrl`. Пример compose:

```yaml
services:
  jellyfin:
    image: jellyfin/jellyfin
    container_name: jellyfin
    ports:
      - 8096:8096/tcp
      - 7359:7359/udp
    volumes:
      - ./config:/config
      - ./cache:/cache
      - type: bind
        source: /path/to/media
        target: /media
        read_only: true
    devices:
      - /dev/dri:/dev/dri      # опционально, VA-API/QSV транскодинг
    restart: unless-stopped
```
- Демо-сервер: ссылка «Try the Demo» на [jellyfin.org](https://jellyfin.org/) ведёт на **https://demo.jellyfin.org/stable** (страница открывается); логин по сообщениям модераторов форума — пользователь `demo` без пароля (2023; не проверено).
- [Movies naming](https://jellyfin.org/docs/general/server/media/movies/) — `Movie Name (2030) [imdbid-tt0000000]/Movie Name (2030) [imdbid-tt0000000].mkv` для тестовой библиотеки.
- [Troubleshooting](https://jellyfin.org/docs/general/administration/troubleshooting/) и [Configuration](https://jellyfin.org/docs/general/administration/configuration/) — логи в `<Data Directory>/log` (Linux: `~/.local/share/jellyfin/log`, в Docker — внутри `/config`), debug-уровень через `logging.json` (`Serilog.MinimumLevel.Default = "Debug"`), при проблемах транскодинга «first check the ffmpeg logs» (файлы FFmpeg.Transcode-*.log в той же папке — имя не проверено по доке). Причина транскодирования: в Dashboard у активной сессии (иконка «i») показываются Direct Play / Direct Stream / Transcode и причина; в логе ищите `TranscodeReason`/`TranscodeReasons` (ContainerNotSupported, AudioCodecNotSupported, VideoCodecNotSupported, SubtitleCodecNotSupported и т.п.) — по форуму, официальной страницы с перечнем не найдено. Для клиента ключ — корректный `DeviceProfile` в `PlaybackInfo`, тогда сервер пишет причины в ответе `PlaybackInfoResponse.MediaSources[].TranscodingReasons` (поле из OpenAPI Jellyfin — не проверено в этом исследовании).
- [ffprobe](https://ffmpeg.org/ffprobe.html) — `ffprobe -show_streams -show_format -output_format json INPUT` для проверки кодеков/профилей/HDR-метаданных тестовых файлов.
- [MediaInfo](https://mediaarea.net/en/MediaInfo) — пакеты для Ubuntu/Debian/Fedora/Arch, Flatpak/Snap; удобно для Dolby Vision профилей и аудиодорожек.
- VLC desktop — https://www.videolan.org/vlc/ (не проверено в этом исследовании) — эталон для сравнения поведения libVLC (те же модули демукса/декодирования).

---

## 6. CI/CD и релизы
- [android-actions/setup-android](https://github.com/android-actions/setup-android) — `@v4`, inputs `packages` (default `platform-tools`), `cmdline-tools-version` (default 15859902), `accept-android-sdk-licenses`; на Ubuntu-раннерах GitHub SDK уже предустановлен, экшен докачивает только недостающее.
- [gradle/actions/setup-gradle](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md) — `@v6`, кеширование Gradle User Home по умолчанию (запись только с default-ветки, `cache-read-only`), `dependency-graph`, build scans. Не комбинировать с `actions/setup-java` `cache: gradle`.
- [actions/setup-java](https://github.com/actions/setup-java) — `@v6`, `distribution: temurin`, `java-version: 17`.
- [ReactiveCircus/android-emulator-runner](https://github.com/ReactiveCircus/android-emulator-runner) — TV-таргеты поддерживаются (`target: google-tv` / `android-tv`). Пример:

```yaml
jobs:
  tv-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v6
      - uses: actions/setup-java@v6
        with: { distribution: temurin, java-version: 17 }
      - uses: gradle/actions/setup-gradle@v6
      - name: Enable KVM
        run: |
          echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
          sudo udevadm control --reload-rules
          sudo udevadm trigger --name-match=kvm
      - uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 36
          target: google-tv
          arch: x86_64
          profile: tv_1080p
          script: ./gradlew connectedDebugAndroidTest
```
- Живой пример у Jellyfin: [jellyfin-androidtv/.github/workflows](https://github.com/jellyfin/jellyfin-androidtv/tree/master/.github/workflows) — `app-build.yaml` (ubuntu-26.04, `actions/setup-java@v6` temurin с версией из `.tools-versions`, `gradle/actions/setup-gradle@v6`, `./gradlew assembleDebug`, upload APK), отдельные `app-lint`, `app-test`, `app-publish`, `gradlew-validate`.
- Подпись в CI: keystore в base64 в GitHub Secret → `echo "$KEYSTORE_B64" | base64 -d > release.jks`, пароли через env в `keystore.properties`/`signingConfigs` (схема из [app-signing](https://developer.android.com/studio/publish/app-signing)).
- [fastlane supply](https://docs.fastlane.tools/actions/supply/) — `fastlane supply init`, `fastlane supply --aab app.aab --track internal`, service-account JSON (`validate_play_store_json_key`), структура `fastlane/metadata/android/<locale>/{title.txt,short_description.txt,full_description.txt,changelogs/<versionCode>.txt,images/{icon.png,featureGraphic.png,tvBanner.png,phoneScreenshots/,tvScreenshots/}}`.
- [F-Droid: descriptions, graphics, screenshots](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/) — F-Droid читает ту же fastlane-структуру (`fastlane/metadata/android/` или `metadata/`), `changelogs/<versionCode>.txt` (имя — ровно versionCode), `images/tvScreenshots/`, `images/tvBanner.png`; альтернатива Triple-T (`src/main/play/`). [Inclusion How-To](https://f-droid.org/docs/Inclusion_How-To/) — RFP → `fdroiddata` merge request с `metadata/<appid>.yml`, автообновление по тегам, reproducible builds рекомендованы.
- [Obtainium](https://github.com/ImranR98/Obtainium) — обновления прямо из GitHub Releases/F-Droid/IzzyOnDroid, deep-link/бейдж для README (детали в wiki); про Android TV на странице не сказано (не проверено). GitHub Releases с APK (универсальный + per-ABI) — базовый канал для сайдлоада на TV.

---

## 7. Дизайн-инструменты
- [Design for TV](https://developer.android.com/design/ui/tv) — официальные гайды (buttons, cards, featured carousel, immersive list, lists, navigation drawer, tabs; стиль: цвет, типографика, motion, theming) и ссылка на Figma-сообщество **https://www.figma.com/@tv** (Figma отдаёт 403 для автоматического запроса — содержимое профиля не проверено).
- [Compose for TV](https://developer.android.com/training/tv/playback/compose) и [androidx.tv releases](https://developer.android.com/jetpack/androidx/releases/tv) — реализация тех же компонентов в коде (`tv-material 1.1.0`).
- [jellyfin-ux](https://github.com/jellyfin/jellyfin-ux) — логотипы (SVG/PNG), `branding/` с ассетами для Android mobile/Android TV (включая store-листинг и баннеры), лицензия CC-BY-SA-4.0. [Branding guide](https://jellyfin.org/docs/general/contributing/branding/) — **сторонним клиентам нельзя называться «Jellyfin …»** и использовать логотип без разрешения; допускается «Jellyfin client» в описании и использование фирменного градиента на собственном уникальном логотипе; не поощряются имена вида «Jelly*»/«*fin».
- [Material Theme Builder](https://material-foundation.github.io/material-theme-builder/) — страница существует (генерация M3 цветовой схемы; экспорт в Compose/Kotlin — не проверено).
- [Image Asset Studio](https://developer.android.com/studio/write/create-app-icons) — `res > New > Image Asset`: adaptive/legacy launcher icons, notification icons, Play Store 512×512; **генератора TV-баннера нет** — баннер 320×180 px (xhdpi, с текстом внутри, локализованный) рисуется вручную и кладётся в `res/drawable-xhdpi/banner.png`, подключается `android:banner` ([TV app start](https://developer.android.com/training/tv/start/start)).

---

## Рецепт: Android TV эмулятор на Linux из командной строки (все пакеты и флаги проверены по официальным источникам)

```bash
# 0. Предпосылки: KVM
egrep -c '(vmx|svm)' /proc/cpuinfo              # > 0
sudo apt-get install -y qemu-kvm libvirt-daemon-system libvirt-clients bridge-utils cpu-checker
sudo kvm-ok                                     # "KVM acceleration can be used"
sudo usermod -aG kvm "$USER"                    # перелогиниться

# 1. JDK + command-line tools
sudo apt-get install -y temurin-17-jdk || sudo apt-get install -y openjdk-17-jdk   # любой JDK 17+
export ANDROID_HOME="$HOME/Android/Sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools"
cd /tmp && curl -LO https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip
unzip -q commandlinetools-linux-15859902_latest.zip -d /tmp/cmdtools
mv /tmp/cmdtools/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

# 2. Пакеты SDK (Google TV, Android 16 / API 36, x86_64)
yes | sdkmanager --licenses
sdkmanager --install "platform-tools" "emulator" "platforms;android-36" "build-tools;36.0.0" \
  "system-images;android-36;google-tv;x86_64"
# альтернативы: "system-images;android-36;android-tv;x86_64" (AOSP TV),
#               "system-images;android-34;google-tv;x86" (API 34 есть только в 32-bit x86 / arm64)

# 3. AVD с TV-профилем 1080p
echo no | avdmanager create avd -n tv36 -k "system-images;android-36;google-tv;x86_64" -d tv_1080p
#   (-d/--device см. `avdmanager create avd --help`; профили: `avdmanager list device` -> tv_720p / tv_1080p / tv_4k)

# 4. Запуск
emulator -accel-check
emulator -avd tv36 -gpu host -memory 4096 -no-snapshot -no-boot-anim &
# headless/CI: emulator -avd tv36 -no-window -no-audio -no-boot-anim -no-snapshot -gpu software &
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done

# 5. Установка и управление "пультом"
./gradlew installDebug
adb -e shell am start -n org.example.jellyfinclient/.MainActivity
adb -e shell input keyevent KEYCODE_DPAD_DOWN
adb -e shell input keyevent KEYCODE_DPAD_RIGHT
adb -e shell input keyevent KEYCODE_DPAD_CENTER
adb -e shell input keyevent KEYCODE_MEDIA_PLAY_PAUSE
adb -e shell input keyevent KEYCODE_BACK
adb -e shell input keyevent KEYCODE_HOME
adb -e exec-out screencap -p > tv.png          # скриншот
scrcpy -e                                        # зеркало с клавиатурой как D-pad (стрелки/Enter/Esc)

# Эквивалент через новый Android CLI (если установлен):
#   android sdk install emulator platforms/android-36
#   android emulator create --list-profiles      # найти TV-профиль
#   android emulator start --headless <avd>
```

Примечания к рецепту: имена пакетов `system-images;android-36;google-tv;x86_64`, `...;android-tv;x86_64`, `platforms;android-36`, `build-tools;36.0.0`, `emulator`, `platform-tools` — из официальных манифестов/доков; ID профилей `tv_720p/tv_1080p/tv_4k` — из sdklib `tv.xml`; флаги `emulator` — из страницы command-line; `-accel-check` и установка KVM — из страницы acceleration. Единственное непроверенное по онлайн-документации — флаг `-d` у `avdmanager create avd` (присутствует в `--help` самого инструмента). Клавиатурная раскладка эмулятора (стрелки/Enter/Esc) — по опыту; официальный список открывается по F1 в окне эмулятора.

---

## Ключевые выводы для проекта
1. Стек на октябрь 2026: Android Studio Rabbit 1 (2026.2.1) + AGP 9.4.0 + Gradle 9.6–9.8 + Kotlin 2.4.21 + KSP 2.3.12 + Hilt 2.60.1 + Room 2.8.5 + Compose BOM 2026.09.00 + tv-material 1.1.0; JDK 17 как toolchain. При AGP 9 убрать `org.jetbrains.kotlin.android` и kapt.
2. TV-образы: x86_64 только для API 36 (Google TV / Android TV); API 35 TV-образов нет; API 34/33/31 — 32-bit x86 или arm64. Эмулятор не покрывает HW-декодирование/HDR/passthrough — нужен хотя бы один реальный девайс (Google TV Streamer или Chromecast with Google TV как эталон UX, SHIELD для аудио/видео-форматов, Fire TV Stick — Fire OS без эмулятора).
3. Для пользователя JetBrains: IDEA с AGP 9.x сейчас не рендерит Compose-превью — ставьте Android Studio через JetBrains Toolbox; для «посмотреть UI» без эмулятора — `@Preview(device = "id:tv_1080p", uiMode = UI_MODE_TYPE_TELEVISION)` + Interactive mode + screenshot-тесты (Compose Preview Screenshot Testing / Roborazzi).
4. Пульт в разработке: клавиатура эмулятора, `adb shell input keyevent`, scrcpy по `adb connect` к приставке, Google TV app на телефоне, androidtvremote2 для скриптов.
5. jellyfin-sdk-kotlin на JVM/Android использует OkHttp → Chucker/HttpLoggingInterceptor применимы; для прокси — `debug-overrides` в network security config + mitmproxy.
6. CI: `reactivecircus/android-emulator-runner` поддерживает `target: google-tv`/`android-tv` на ubuntu-раннерах с KVM; метаданные для Play/F-Droid в единой структуре `fastlane/metadata/android/` (есть `tvScreenshots`, `tvBanner`); помните об ограничениях брендинга Jellyfin на имя и логотип.
---

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
