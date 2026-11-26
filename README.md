# Maestro Client — отдельный standalone VPN-клиент

Функциональный аналог клиента 4.11.2 по протоколам, подпискам и функциям; интерфейс — как у MaestroVPN.
**Это НЕ обновление MaestroVPN 1.0.212** — отдельный пакет `com.maestrovpn.client`,
существующее приложение не трогается.

Единственные внешние ссылки в приложении — бот `@MaestroSecureVPN_bot`
и личный Telegram `@wapmixx`. Никаких сторонних сервисов (Firebase/Sentry нет).

## Срез 1 — каркас + первая сборка

Что внутри:
- Gradle-проект (AGP 9.0.1, Kotlin 2.3.10, Compose BOM 2026.02.00,
  compileSdk 36, minSdk 24, targetSdk 35) — тот же стек, что у MaestroVPN 1.0.212.
- Главный экран: монета (заглушка среза 1 — точное поведение переносится
  из `MonogramMedallion.kt` следующим срезом, см. отчёт среза 1 §1.1), кнопка питания,
  статус, время сессии, список подписок, кнопки Telegram.
- Навигация по всем 32 экранам клиента-прототипа:
  главный — настоящий, настройки/About — настоящие списки, остальные — заглушки.
- Подписки: загрузка по URL + validate-before-save + атомарная запись
  (`core/subscription/SubscriptionManager.kt`).
- VPN-служба (`bg/ClientVpnService.kt`), QS-плитка, BootReceiver, deeplink `maestro://`.
- Движок: Xray-core (`XrayEngine`, срез 7, issue #8) — единственное ядро.
  Бинарь `libmaestro_subxhttp.so` (arm64-v8a + armeabi-v7a) в `jniLibs/`;
  запуск — нативный fork/exec (`XrayNative`, см. `src/main/cpp/xray_spawn.c`),
  TUN — через Xray tun inbound, режим «только прокси» — SOCKS :10808 + HTTP :10809.
  sing-box/libbox удалён полностью (XHTTP он не поддерживает ни в одной версии).

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

## Нативный код (срез 7)

sing-box/libbox удалён полностью. NDK 28.0.13004108 нужен только для
JNI-лаунчера `xrayspawn` (`src/main/cpp/xray_spawn.c`, ndk-build, без CMake):
нативный fork/exec — единственный способ передать дочернему Xray tun fd
(ProcessBuilder закрывает все fd >= 3 при exec).

## Что НЕ вошло в срез 1 (следующие срезы по таблице соответствия)

- Маршрутизация (issue #2), продвинутые настройки (issue #3), пинг и статистика
  (issue #4), свойства подписки/autoswitch (issue #5), виджет/шорткаты/Send-to-TV
  (issue #6). Движок Xray, парсер подписок, редактор сервера и медальон — готовы
  (срезы 2, 3, 5, 7).
- Tunnel-fetch (отложен: нужен только при поднятом туннеле и заблокированной панели).
- Полный медальон (вращение, объёмная M, гнездо, spin-down), виджет, шорткаты.
- QR-сканер, Send-to-TV (QR/буфер обмена, без чужого backend).

## Документы

- Обзор ядра Maestro: `~/workspace/maestro-study/maestro-client-core-survey.md`
- Отчёт среза 1: `~/workspace/maestro-study/maestro-client-slice1.md`
