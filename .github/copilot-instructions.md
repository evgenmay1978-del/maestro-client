# Инструкции для coding-агента — Maestro Client

## Что это
Standalone Android VPN-клиент, пакет com.maestrovpn.client. Функциональный аналог Happ 4.11.2
по протоколам и подпискам, интерфейс — MaestroVPN. Это НЕ обновление MaestroVPN 1.0.212:
чужой пакет и чужой код не трогать.

## Стек (не менять без явного разрешения в issue)
AGP 9.0.1, Kotlin 2.3.10, KSP 2.3.14, Gradle 9.3.1, Compose BOM 2026.02.00,
compileSdk 36, minSdk 24, targetSdk 35, Java 17, NDK 28.0.13004108 (ndk-build, без CMake).
Остальные версии — в app/build.gradle.kts, менять нельзя.
ABI: только arm64-v8a и armeabi-v7a.

## Жёсткие запреты
1. Ядро одно — Xray-core (app/src/main/jniLibs/*/libmaestro_subxhttp.so). sing-box/libbox
   не возвращать ни в каком виде; чужой JSON парсер отвергает с внятной причиной.
2. Никаких сторонних сервисов: Firebase, Sentry, Crashlytics, аналитика, рекламные SDK,
   GitHub/F-Droid-апдейтеры.
3. Единственные внешние ссылки в UI — @MaestroSecureVPN_bot и @wapmixx.
4. Новые зависимости — только с явного разрешения в issue. WorkManager удалён в срезе 7,
   не возвращать.
5. Никаких чужих backend: ни tunnel-fetch, ни панельных API.
6. Не менять applicationId, versionCode, versionName и ABI-фильтры.
7. geoip.dat/geosite.dat в assets должны оставаться несжатыми — проверь настройку
   noCompress для "dat" в app/build.gradle.kts и не убирай её, иначе Xray не откроет базы.
8. Не «чинить» падающий тест удалением или ослаблением: падает тест — ищи баг в коде.
9. Секреты, токены и пароли прокси в репозиторий не коммитить (прокси — только
   в ~/.gradle/gradle.properties вне проекта).

## Куда класть код
- core/engine/ — VpnEngine (интерфейс), XrayEngine (валидация, запуск, статистика), XrayNative.
- core/subscription/ — SubscriptionManager (validate-before-save + атомарная запись),
  SubscriptionParser, SubscriptionHeaders, XrayConfigBuilder (единственное место, где
  собирается Xray JSON), ManualServerStore, ExpiryNotifier.
- core/routing/ — RoutingProfile, RoutingProfileStore (filesDir JSON, атомарная запись,
  привязка к подписке), RoutingRulesBuilder (rules + dns), GeoDataFiles.
- bg/ — VPNService/ProxyService/TileService/BootReceiver, XrayNotification, PackagePolicy.
- ui/screens/, ui/navigation/Routes.kt — Compose-экраны; заглушки лежат в Routes.stubs.
- constant/SettingsKey.kt — только ключи, которые реально читаются/пишутся.

## Правила кода
- Бизнес-логика — чистый Kotlin без Android-зависимостей в core/..., покрыта JVM-тестами.
  Android-часть — тонкая обёртка.
- Запись файлов атомарно: *.tmp → rename (как в RoutingProfileStore/SubscriptionManager).
- Конфиг Xray валидируется до сохранения и до старта (xray run -test).
- Строки UI — в app/src/main/res/values/strings.xml, не хардкодить в Compose.
- Комментарии и тексты ошибок — по-русски, в стиле существующего кода. Ошибка объясняет
  причину, а не «что-то пошло не так».
- Не плодить абстракции на будущее: один движок, один билдер конфига.

## Проверка перед PR (обязательно)
./gradlew testDebugUnitTest assembleDebug --console=plain
Новые JVM-тесты — в app/src/test/java/..., на том фреймворке, который уже подключён
в app/build.gradle.kts: happy path, границы, отказ.
Собранные конфиги прогонять xray run -test локально. В CI Xray-бинаря нет — в описании PR
честно укажи что прогонялось, а что нет.

## Коммиты и PR
- Один PR = одна issue. Ветка feat/<номер>-<slug>, PR — draft, в описании Closes #N.
- Сообщение коммита: что сделано и почему, со ссылкой на issue. Без «fix», «update», «wip».
- В описании PR: изменения, что проверено, что осталось за рамками.
- Требование непонятно — вопрос в issue, а не выдумывание поведения.
