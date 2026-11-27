# Карта поведения эталонного клиента 4.11.2 — база для Maestro Client

Источник разбора — APK-файл, предоставленный владельцем (69 352 837 Б, sha256 c52aa9f9…, zip OK).
Декодирование: apktool 2.12.1 d -s (официальный релиз, java 21). Все пункты подтверждены манифестом,
строками res/values/strings.xml (1032 записи) или строками dex — не догадки.

Документ нейтрален: без указания наименования продукта и без внешних ссылок (требование владельца).

## 1. Состав и идентификация

| Факт | Значение | Где проверено |
|---|---|---|
| package | com.maestrovpn.client (наш) | AndroidManifest.xml |
| версия | 4.11.2 | строки classes.dex |
| Application | класс инициализации движка | манифест |
| процесс движка | :XRayDaemon (VPN, tile, widget, switch) | манифест |
| фоновый процесс | :bg_process (WorkManager) | манифест |
| ядро | Xray-core (Go, libgojni.so 37.7 МБ arm64 / 36.6 МБ armeabi-v7a) | lib/ |
| туннель | hev-socks5-tunnel (SOCKS5-туннель под tun inbound) | lib/ |
| сторонние SDK | Firebase/Play Core/datatransport, Sentry NDK | манифест |
| ориентация | screenOrientation=locked у всех activity | манифест |

Отличие от эталона: Firebase и Sentry у Maestro Client отсутствуют и не нужны (зафиксировано в README репо).

## 2. Сервисы, приёмники, экраны

| Компонент эталона | Наш аналог | Статус у нас |
|---|---|---|
| VpnService (BIND_VPN_SERVICE, specialUse, отдельный процесс) | ClientVpnService/VPNService | есть |
| ProxyOnlyService (specialUse=proxy) | ProxyService | есть |
| DownloadFilesService (загрузка geo/данных) | — | НЕТ |
| QSTileService (BIND_QUICK_SETTINGS_TILE) | TileService | есть |
| WidgetProvider (appwidget + два своих action) | — | НЕТ (issue #6) |
| BootUpReceiver (BOOT_COMPLETED, MY_PACKAGE_REPLACED) | BootReceiver | есть |
| SubscriptionUpdaterReceiver (cancel notification) | ExpiryNotifier | частично (issue #5) |
| shortcuts (android.app.shortcuts meta-data) | — | НЕТ (issue #6) |
| DeeplinkInterceptorActivity (silent: connect_without_ui/open/toggle/close) | DeepLink | частично |
| MainActivity (LAUNCHER + TV-лаунчер + VIEW/EDIT своей схемы) | MainActivity | частично (TV-лаунчер нет) |

Экраны эталона (activity-перечень манифеста, 32 позиции):
ServerActivity, ServerCustomConfigActivity, SettingsActivity, LanguageActivitySettings,
UISettingsActivity, VpnSettingsActivity, ExcludedRoutesActivity, InboundAuthActivity,
SubscriptionSettingsActivity, PingSettingsActivity, StatisticsSettingsActivity,
LogsSettingsActivity, LogsViewSettingsActivity, ResetSettingsActivity, FaqSettingsActivity,
WebViewActivity, UrlSchemesSettingsActivity, ReportActivity, PerAppProxyActivity, ScannerActivity,
ScScannerActivity, ScSwitchActivity, RoutingSettingsActivity, RoutingSettingsEditActivity,
RoutingSettingsEditSearchActivity, RoutingRulesActivity, RoutingProfileActivity, SubRoutingActivity,
SubscriptionPropertiesActivity, AboutSettingsActivity.

Наши Routes.kt покрывают те же 32 маршрута (сверено по именам). Реальные (не заглушки) сейчас: главный,
настройки-список, About, редактор сервера, routing-экраны после issue #2, deeplink.

## 3. Подписки (strings + dex)

| Поведение эталона | Факт из APK | Наш статус |
|---|---|---|
| User-Agent настраивается | sub_properties_ua_title | НЕТ |
| Таймаут запроса 5–15 с | sub_properties_request_timeout_min/max | НЕТ (жёсткий 10 с) |
| Автообновление + интервал 1–730 ч | sub_properties_auto_update_interval_description (1–730) | частично (есть поле, нет экрана) |
| Уведомления об обновлении | sub_properties_auto_update_show_notifications | НЕТ |
| Provider ID | sub_properties_provider_id | НЕТ |
| Отправка HWID + Android version | sub_properties_sending_data_hwid_description | НЕТ (и не надо без нужды) |
| Connect on open / Update on open / Ping on open | sub_properties_general_connect_on_open и др. | НЕТ |
| Connect to / фильтр / сортировка серверов | sub_properties_general_connect_to, server_sort_title | НЕТ |
| Autoswitch onlymobile (Mobile в имени сервера) + timeout | sub_properties_autoswitch_onlymobile* + notification_autoswitch_channel | НЕТ (issue #5) |
| Напоминание об истечении (дни/часы/минуты/истекла) | sub_info_expire_days/hours/minutes/expired, worker_expire_title | частично (ExpiryNotifier) |
| Обновление подписки, expire | info_bar_expires | expire есть |
| Заголовки подписки (title, announce, userinfo) | SubscriptionHeaders | есть |

Важно: у эталона настройки переехали ВНУТРЬ подписки (sub_properties_migration_info_title
«доступ через ••• рядом с подпиской на главном»). У нас подписки тоже per-subscription, но экрана свойств нет (issue #5).

## 4. Продвинутые настройки (issue #3)

| Параметр эталона | Значение по умолчанию из APK | Примечание |
|---|---|---|
| SOCKS-порт | 10808 (port_socks_default) | как у нас |
| Local DNS порт | 10853 (port_local_dns_default) | у нас 10808/10809 |
| Remote DNS | 1.1.1.1 (remote_dns_default) | — |
| Domestic DNS | 8.8.8.8 (domestic_dns_default) | у нас аналог не настроен |
| Domain DNS | dns-query URL (domain_dns_default) | DoH |
| Local DNS включён / Fake DNS / DNS from JSON | title_pref_local_dns_enabled, title_pref_fake_dns_enabled, title_pref_dns_from_json | три тумблера |
| Mux | выкл, диапазон -1..1024 (settings_mux_range_info) + handling QUIC (settings_mux_handling) | TCP + XUDP раздельно |
| MTU | 1500 (mtu_default) | — |
| Фрагментация | enabled + length 50-100, interval 10-20, max split 100-200, packets | 4 поля |
| Шумы (noises) | enabled + packet, delay 10-16, rand, rand range, type | 5 полей |
| Sniffing | title_pref_sniffing_enabled | — |
| Per-app proxy | Off / On / Bypass + пресеты google/spy | 3 режима + пресеты |
| Исключённые маршруты | IPv4/IPv6, import/export, добавить приватные | excluded_routes_* |
| URL-тест | адрес проверки связи по умолчанию, настраиваемый пользователем | title_pref_delay_test_url |
| Inbound-авторизация | Auto / Manual / From JSON / Disable, отдельно HTTP и SOCKS, user+password | inbound_authorization_* |
| Языки | Auto/EN/RU/ZH/(и др. 20+ values-*) | LanguageActivitySettings |

Поведенческая деталь: настройки туннеля применяются ПОСЛЕ перезапуска туннеля (warning_settings_after_tunnel_restart) — у нас стоит такое же предупреждение в UI.

## 5. Маршрутизация (issue #2 — уже сделан у нас)

| Поведение эталона | Факт | Наш статус |
|---|---|---|
| Профили правил, привязка к подписке | routing_settings_title, routing_sub_select_title | сделано (issue #2) |
| Порядок действий block/proxy/direct | routing_activity_order_routing | сделано |
| Global Proxy (выкл = всё мимо прокси кроме правил) | routing_activity_global_proxy_description | проверить |
| Geo-файлы geosite/geoip, скачивание с прогрессом | routing_downloading_geo_files_progress | сделано (geo в assets) |
| Импорт/экспорт профиля | routing_profile_successfully_add/update, export_failure | частично |
 | Запрет правки geo внутри JSON-подписки | routing_profile_json_restrict_geo_edit | проверить |
 | Показ тегов в geoip/geosite | routing_activity_show_tags_in_geo_* | НЕТ |
 | LAN-адреса добавлением | routing_settings_set_lan_addresses | НЕТ |

## 6. Пинг/статистика (issue #4)

| Эталон | Факт | Наш статус |
|---|---|---|
| Тест соединения с текстом ошибки | connection_test_available/error/error_tls_handshake/error_eof/error_status_code | НЕТ |
 | Проверка текущего соединения (mobile) | connection_test_pending_mobile | НЕТ |
 | Статистика: upload/download, direct usage, proxy usage, bandwidth, server, connect/start time | statistics_* | НЕТ |

## 7. Deeplink, QR, Send-to-TV, FAQ

| Эталон | Факт | Наш статус |
|---|---|---|
| своя scheme + хосты add/routing/send-to-tv/crypt1-5/toggle/connect/disconnect/open/close | манифест intent-filter | у нас maestro:// add/connect/disconnect/toggle |
 | Без UI (silent) — *_without_ui хосты | DeeplinkInterceptorActivity autoVerify | проверить |
| Файлы: content:// *.json + octet-stream | intent-filter | у нас .json есть, своего бинарного формата не заводим |
 | QR-scanner + отдельный Sc-сканер | ScannerActivity, ScScannerActivity | НЕТ (issue #6) |
 | Send-to-TV по локальной сети с проверкой «один Wi-Fi» | send_to_tv_failure_wrong_wifi/no_wifi/timeout | НЕТ (issue #6) |
 | Fallback через backend API при отсутствии интернета | send_to_tv_failure_api | НЕ НУЖЕН (у TVs нашего флота свой канал) |
 | URL Schemes — настраиваемые схемы | url_schemes_* | НЕТ |
 | FAQ + WebView | FaqSettingsActivity, WebViewActivity | НЕТ (issue #6) |
 | Отчёт о проблеме | ReportActivity | маршрут есть, экрана нет |

## 8. Итоговый зазор: что внедряем (приоритет)

Уже есть (сверено с issues и коммитами): Xray-ядро, парсер подписок, редактор сервера, deeplink 4 команды,
routing-профили, geo-данные, медальон, QS-плитка, BootReceiver, вход в настройки/About.

Дефицит по приоритету (весь список — открытые issues репо, ничего нового не выдумывал):

1. issue #3 — продвинутые настройки: inbound-порты (10808/10853/10809), три DNS (remote 1.1.1.1 / domestic 8.8.8.8 /
   domain DoH), тумблеры Local DNS / Fake DNS / DNS from JSON, Mux (TCP+XUDP+QUIC handling), фрагментация (4 поля),
   шумы (5 полей), sniffing, URL-тест с настраиваемым адресом.
2. issue #4 — пинг и статистика: тест конфига с точными текстами ошибок, экран статистики (up/down/direct/proxy/bandwidth).
3. issue #5 — свойства подписки: UA, таймаут 5–15 с, автообновление с интервалом и уведомлением, autoswitch onlymobile,
   connect/update/ping on open, сортировка серверов.
4. issue #6 — виджет, шорткаты, Scanner/QR, FAQ, inbound-авторизация (Auto/Manual/JSON/Disable).
5. Мелкое: per-app proxy 3 режима + пресеты; исключённые маршруты с импортом/экспортом и private-пресетом;
   TV-лаунчер (LEANBACK_LAUNCHER) — у Maestro Client категории LAUNCHER нет, для ТВ это блокер.

Не переносим намеренно: Firebase, Sentry NDK, Play Core, backend-fallback для отправки на ТВ (свой канал есть),
свою scheme и свои форматы (не чужие), а также любые упоминания наименования эталона в UI и коде.
