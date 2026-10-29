# Разработка

## Сборка

Нужны JDK 21 и Android SDK (compileSdk 37). Gradle — через обёртку `./gradlew` (9.8.1).

| Что | Команда |
|---|---|
| ТВ, debug | `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` |
| ТВ, release | `./gradlew :app:assembleRelease` — R8 без обфускации, ключ — ниже; ABI `armeabi-v7a` + `arm64-v8a` |
| ПК, запуск | `./gradlew :desktop:run` или `tools/linux/desktop.sh` (без Gradle вокруг процесса) |
| ПК, пакет | `./gradlew :desktop:packageDeb` → `desktop/build/deb/sleepyowl_<версия>_amd64.deb` (зависит от `libmpv2`) |
| Тесты | `./gradlew test` (в т.ч. рендер-тесты экранов ПК и `ui:shared` без дисплея) |

Производительность интерфейса на ТВ мерить только на release-сборке (см. [ui-performance.md](ui-performance.md)).
Baseline profile лежит в `app/src/release/generated/baselineProfiles/` и снимается заново на ТВ (`gen_profile`, ниже).

Версия обоих приложений — `sleepyowl.versionName` в `gradle.properties` (или `-Psleepyowl.versionName=…`);
`versionCode` APK следует за ней (`1.2.3` → `1002003`). Release-APK подписывается ключом из `ANDROID_KEYSTORE`
(+ `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, по умолчанию `sleepyowl`), без него — debug-ключом машины.

## CI: релизы в Gitea и GitHub

`.gitea/workflows/release.yaml` (Gitea) и `.github/workflows/release.yml` (GitHub) — одно и то же: на пуш в `master` APK и .deb собираются в Docker и выкладываются на страницу
Releases репозитория как тег `v<версия>` (APK `sleepyowl-<версия>.apk`, `.deb` как его назвал `packageDeb`).

- **Версия.** Каждый релиз — patch на единицу выше прошлого тега (`v0.1.1` → `0.1.2`). Чтобы начать новую minor или
  major, поднять `sleepyowl.versionName` в `gradle.properties` выше последнего релиза — следующий релиз возьмёт её.
  Первый релиз — версия из `gradle.properties`. Пуш, который не менял приложения (только `docs/`, `tools/`,
  `.gitea/`, `.github/`, `baselineprofile/`, тесты, `*.md`), релиза не делает.
- **Сборка.** `tools/ci/Dockerfile` — образ с JDK 21 и Android SDK; `tools/ci/release.Dockerfile` собирает в нём
  `:app:assembleRelease :desktop:packageDeb` (`tools/ci/release.sh`) целиком внутри BuildKit: исходники — контекст
  сборки, ключ — BuildKit-секреты (в слои не попадает), кэш Gradle — cache mount, который живёт между сборками,
  результат — `--output`. Так раннеру не нужны bind mount'ы. На GitHub раннер — целая одноразовая VM со своим Docker:
  тот же образ и тот же `release.sh` запускаются через `docker run` с bind mount'ами, а образ и домашняя папка Gradle
  (зависимости, wrapper, build cache) между запусками живут в кэше Actions.
- **Раннер.** В Gitea нужен Gitea Actions runner с меткой `ubuntu-latest`, видящий этот репозиторий (на уровне
  репозитория, организации `StenFreddSoft` или всего сервера) и с доступом к Docker. На GitHub — его стандартный
  `ubuntu-latest`, ничего настраивать не нужно. Gitea `.github/` не читает, пока есть `.gitea/workflows/`.
- **Секреты** (Gitea: *Settings → Actions → Secrets*; GitHub: *Settings → Secrets and variables → Actions*):
  - `ANDROID_KEYSTORE_BASE64` — `base64 -w0 <keystore>`;
  - `ANDROID_KEYSTORE_PASSWORD` — его пароль (у PKCS12 он же пароль ключа);
  - `ANDROID_KEY_ALIAS` — alias ключа, если не `sleepyowl`;
  - `RELEASE_TOKEN` — только в Gitea и только если автоматический токен Actions не может создавать релизы: токен с
    `write:repository`. На GitHub хватает автоматического (workflow сам просит `contents: write`).

  Без ключа джоба падает: APK на ключе, придуманном в контейнере, не встанет поверх ни одной установленной версии.
  Ключ должен быть один и тот же навсегда — APK на другом ключе ставится только после удаления приложения (с его
  настройками и входом). Свой ключ: `keytool -genkeypair -keystore sleepyowl.jks -alias sleepyowl -keyalg RSA
  -keysize 4096 -validity 36500`, хранить вне репозитория и с резервной копией.
- **Переменные** (необязательные, рядом с секретами): `RELEASES_KEEP` (10) — сколько релизов остаётся на странице
  (старые удаляются вместе с файлами, теги остаются); только в Gitea — `BUILD_CACHE_KEEP` (`10GB`), потолок кэша
  сборки Docker на раннере. На GitHub кэш Actions сам держится в 10 ГБ на репозиторий.

Локально то же самое (команды — в шапке `tools/ci/release.Dockerfile`):

```sh
docker build -t sleepyowl-ci tools/ci
docker build --no-cache -f tools/ci/release.Dockerfile --secret id=keystore,src=<keystore> \
  --secret id=keystore_password,src=<файл с паролем> --secret id=key_alias,src=<файл с alias> -o out .
```

## Личные настройки инструментов: `local/`

Папка `local/` целиком в `.gitignore`: там всё, что относится к конкретной машине, серверу и аккаунтам —
настройки инструментов, рабочие заметки, тестовые данные, резервные копии. В git она не попадает.

Инструменты в `tools/` берут адреса и идентификаторы из `local/dev.env`. Шаблон с описанием каждой переменной —
[`tools/dev.env.example`](../tools/dev.env.example):

```sh
mkdir -p local && cp tools/dev.env.example local/dev.env   # и заполнить
```

Формат — строки `KEY=value` без кавычек и подстановок; переменная, уже заданная в окружении, важнее файла.
Shell-скрипты подключают файл через `tools/devenv.sh`, Python-скрипты — через `tools/devenv.py`. Если нужной
переменной нет, скрипт останавливается и говорит, какую задать.

| Переменная | Для чего |
|---|---|
| `JAVA_HOME`, `ADB` | JDK 21 и adb, если не из окружения/`PATH` |
| `TV_ADB` | адрес ТВ для adb (`<ip-тв>:5555`) |
| `JF_URL` | сервер Jellyfin по адресу в LAN |
| `JF_USER` | тестовый аккаунт на сервере: тестовые прогоны меняют его данные просмотра |
| `PROFILE_SERIES` | id сериала для прохода по странице сериала при съёмке baseline profile |
| `K2_EPISODES` | три подряд идущие серии одного сезона для `tools/linux/k2.py` |
| `L0_FLOAT_ITEM`, `L0_CASES` | файл и список файлов для проверок mpv в `tools/linux/l0/` |

Debug-тур (`app/src/debug/assets/tour.json`, список файлов для обхода всех форматов из отладочного экрана) тоже
не в git: он собирается со своего сервера.

## Инструменты

### `tools/tv/` — тестовый ТВ по adb

`source tools/tv/env.sh` даёт функции:

- `install_debug`, `install_release` (release сразу компилируется по baseline profile);
- `play <itemId> [exo|vlc]` — открыть карточку и играть (debug deep-link `--es item`, `--es engine`, `--el start <мс>`);
- `k KEY…` — нажатия пульта, `shot NAME` — скриншот в `$OUT`;
- `plog` — журнал решений сессии воспроизведения, `vproxy` — журнал локального прокси VLC, `aout` — что реально идёт на аудиовыход;
- `tv_token` — токен текущего аккаунта (только debug-сборка, через `run-as`), `sess` — что играет на ТВ по данным сервера;
- `ui_bench [right|down] [N]` — замер плавности (перцентили кадра и GPU, разбивка по фазам через `framestats.py`);
- `gen_profile` — снять baseline profile на ТВ (~8 мин, на ТВ должен быть выполнен вход).

`tools/tv/run-case.sh <name> <itemId> [exo|vlc]` — прогон одного файла: скриншот и журнал.

### `tools/tour/build_tour.py`

Собирает debug-тур: минимальный набор файлов медиатеки, покрывающий все форматные признаки сервера
(контейнер, кодеки, HDR, частоты, чересстрочность, субтитры) и важные для выбора движка пары.

### `tools/logo/make_assets.py`

Все растровые логотипы (баннер ТВ, адаптивная иконка, сова и надпись в интерфейсе, иконки ПК 16…512) генерируются
из `design/logo/sleepyowl.png`. PNG не править руками — перезапускать скрипт.

### `tools/linux/` — версия для ПК

- `desktop.sh` — запуск приложения без Gradle (`DESKTOP_NOBUILD=1` — без сборки, `SLEEPYOWL_SCALE` — масштаб).
- `stand.sh` — тестовый стенд: свой виртуальный дисплей Xvfb `:87` с оконным менеджером Muffin и своей сессией
  D-Bus, звук в `ao=null`. Окна, мышь, MPRIS и хранитель экрана проверяются, не трогая рабочий стол.
  Файлы приложения стенда — отдельно от обычных (`$STAND`).
- `xinput.py` — ввод на дисплей стенда; `fake_screensaver.py` — заглушка `org.freedesktop.ScreenSaver` на шине стенда.
- `k2.py` — сквозная проверка плеера и интеграции с системой на стенде (PASS/FAIL по пунктам, скриншоты).
- `watchdata.py snapshot|diff|restore` — снимок данных просмотра тестового аккаунта до прогона и возврат после.
- `l0/` — проверка, как сам mpv играет файлы медиатеки (версии 0.37 и 0.41, `vo=gpu`/`gpu-next`), и поведение плавающего окна mpv.

Подробности о стенде и его ограничениях — в [desktop.md](desktop.md).
