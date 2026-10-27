# Разработка

## Сборка

Нужны JDK 21 и Android SDK (compileSdk 37). Gradle — через обёртку `./gradlew` (9.8.1).

| Что | Команда |
|---|---|
| ТВ, debug | `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` |
| ТВ, release | `./gradlew :app:assembleRelease` — R8 без обфускации, пока подписан debug-ключом; ABI `armeabi-v7a` + `arm64-v8a` |
| ПК, запуск | `./gradlew :desktop:run` или `tools/linux/desktop.sh` (без Gradle вокруг процесса) |
| ПК, пакет | `./gradlew :desktop:packageDeb` → `desktop/build/deb/sleepyowl_<версия>_amd64.deb` (зависит от `libmpv2`) |
| Тесты | `./gradlew test` (в т.ч. рендер-тесты экранов ПК и `ui:shared` без дисплея) |

Производительность интерфейса на ТВ мерить только на release-сборке (см. [ui-performance.md](ui-performance.md)).
Baseline profile лежит в `app/src/release/generated/baselineProfiles/` и снимается заново на ТВ (`gen_profile`, ниже).

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
