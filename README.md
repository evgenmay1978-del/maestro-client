# Maestro Client — отдельный standalone VPN-клиент

Полный клон Happ по поведению, интерфейс — как у MaestroVPN.
**Это НЕ обновление MaestroVPN 1.0.212** — отдельный пакет `com.maestrovpn.client`,
существующее приложение не трогается.

Единственные внешние ссылки в приложении — бот `@MaestroSecureVPN_bot`
и личный Telegram `@wapmixx`. Никаких `happ.su`, `t.me/happ_*`, Firebase/Sentry.

## Срез 1 — каркас + первая сборка

Что внутри:
- Gradle-проект (AGP 9.0.1, Kotlin 2.3.10, Compose BOM 2026.02.00,
  compileSdk 36, minSdk 24, targetSdk 35) — тот же стек, что у MaestroVPN 1.0.212.
- Главный экран: монета (заглушка среза 1 — точное поведение переносится
  из `MonogramMedallion.kt` следующим срезом, см. отчёт среза 1 §1.1), кнопка питания,
  статус, время сессии, список подписок, кнопки Telegram.
- Навигация по всем 32 экранам Happ из карты (`happ-4.11.2-map.md`):
  главный — настоящий, настройки/About — настоящие списки, остальные — заглушки.
- Подписки: загрузка по URL + validate-before-save + атомарная запись
  (`core/subscription/SubscriptionManager.kt`).
- VPN-служба (`bg/ClientVpnService.kt`), QS-плитка, BootReceiver, deeplink `maestro://`.
- Движок: интерфейс `VpnEngine` + `StubEngine`. Настоящий sing-box (`libbox.aar`)
  подключается, только если собран (см. ниже); без него сборка работает на заглушке.
- Xray-раннер: `libmaestro_subxhttp.so` (arm64-v8a + armeabi-v7a) уже в `jniLibs/`,
  подключение раннера — срез 2 (см. `maestro-client-core-survey.md`).

## Сборка

```sh
cd ~/workspace/maestro-client
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Требуется Android SDK: `~/tools/android-sdk` (cmdline-tools + platform-tools +
`platforms;android-36` + `build-tools;36.0.0`). Прокси для загрузок Gradle задаётся
в `~/.gradle/gradle.properties` (вне проекта), SDK ставился напрямую через curl,
т.к. `sdkmanager` не проходит egress-прокси (нет proxy-auth в plain Java).

## Сборка libbox.aar (sing-box 1.14.4)

В репозитории Maestro AAR нет — он собирается в CI (`.github/workflows/libbox.yml`):
Go 1.25.11 + JDK 17 + NDK 28.0.13004108 + `make lib_install` + `make lib_android`.
Готовый `libbox.aar` кладётся в `app/libs/` — тогда сборка включает
`src/libbox/java` с настоящим `SingBoxEngine` (реализация — срез 2).

## Что НЕ вошло в срез 1 (следующие срезы по таблице соответствия)

- Настоящий движок sing-box + Xray-раннер (ждёт `libbox.aar`).
- Парсер подписок (share-ссылки, base64, Xray JSON), редактор сервера, маршрутизация.
- Tunnel-fetch (отложен: нужен только при поднятом туннеле и заблокированной панели).
- Полный медальон (вращение, объёмная M, гнездо, spin-down), виджет, шорткаты.
- QR-сканер, Send-to-TV (QR/буфер обмена, без чужого backend).

## Документы

- Карта Happ: `~/workspace/maestro-study/happ-4.11.2-map.md`
- Таблица соответствия: `~/workspace/maestro-study/happ-maestro-mapping.md`
- Обзор ядра Maestro: `~/workspace/maestro-study/maestro-client-core-survey.md`
- Отчёт среза 1: `~/workspace/maestro-study/maestro-client-slice1.md`
