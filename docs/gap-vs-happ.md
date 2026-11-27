# Maestro Client vs Happ 4.11.2 — таблица расхождений тракта

**Назначение:** полная сверка сетевого тракта (TUN, DNS, routing, жизненный цикл) с эталоном
docs/happ-4.11.2-map.md. Каждая строка — одно расхождение с якорем параграфа карты
и файлом нашего кода. Это главный артефакт Шага 2 задания — чинить только после этой таблицы.

---

## A. TUN: адреса, маршруты, MTU, DNS-серверы, per-app policy, metered/bypass

| № | Параграф карты | Как в Happ | Как у нас | Файл/строка | Статус |
|---|---|---|---|---|---|
| A1 | §1 (process :XRayDaemon), §2 VpnService | tun создаётся в отдельном процессе :XRayDaemon, fd передаётся в hev-socks5-tunnel (tun2socks) | tun inbound внутри Xray, fd через xray.tun.fd, fork/exec в том же процессе | app/src/main/cpp/xray_spawn.c, core/engine/XrayEngine.kt | АРХИТЕКТУРНОЕ РАСХОЖДЕНИЕ (не чиним) |
| A2 | §4 MTU 1500 (mtu_default) | MTU 1500 по умолчанию | MTU 1280 (измеренный урок Maestro) | core/subscription/XrayConfigBuilder.kt:27, core/settings/AdvancedConfig.kt | ИНТЕНЦИОНАЛЬНОЕ УЛУЧШЕНИЕ |
| A3 | §1 (screenOrientation=locked) | Все activity locked | MainActivity portrait, остальные locked | AndroidManifest.xml | ЧАСТИЧНО |
| A4 | §2 VpnService (meters, bypass) | setMetered(false) + allowBypass() | setMetered(false) только API>=Q; allowBypass() если Settings.allowBypass | bg/VPNService.kt:161-163 | ЧАСТИЧНО — нет setMetered на API<Q |
| A5 | §2 (per-app policy) | addAllowedApplication / addDisallowedApplication | то же через PackagePolicy.decide | bg/VPNService.kt:165-185, bg/PackagePolicy.kt | СООТВЕТСТВУЕТ |
| A6 | §4 (IPv6) | addRoute("::", 0) + addAddress(IPv6) | ТОЛЬКО addAddress(IPv4) + addRoute("0.0.0.0",0) | bg/VPNService.kt:164-165 | **РАСХОЖДЕНИЕ** — IPv6 уходит мимо VPN |
| A7 | §4 (DNS servers) | builder.addDnsServer для каждого DNS | builder.addDnsServer для DNS_SERVERS (1.1.1.1, 8.8.8.8) | bg/VPNService.kt:165-168 | ЧАСТИЧНО — нет разделения remote/domestic |

---

## B. DNS: перехват порта 53, куда уходят запросы, DoH/UDP, FakeDNS, queryStrategy, AAAA

| № | Параграф карты | Как в Happ | Как у нас | Файл/строка | Статус |
|---|---|---|---|---|---|
| B1 | §4 (dns-секция) | domain_dns_default (DoH URL), remote_dns_default 1.1.1.1, domestic_dns_default 8.8.8.8, port_local_dns 10853 | remoteDnsDomain (DoH), remoteDnsIp, domesticDnsDomain, domesticDnsIp, socksPort 10808, httpPort 10809 | core/settings/AdvancedConfig.kt, core/routing/RoutingRulesBuilder.kt:90-120 | ЧАСТИЧНО — нет local DNS 10853, нет Fake DNS/DNS from JSON тумблеров |
| B2 | §4 (DNS перехват) | через hev-socks5-tunnel | routing rule: inboundTag=tun-in, port=53 -> outboundTag=dns (протокол dns) | core/routing/RoutingRulesBuilder.kt:73-80, core/subscription/XrayConfigBuilder.kt:224-225 | СООТВЕТСТВУЕТ (схема другая, эффект тот же) |
| B3 | §4 (FakeDNS) | title_pref_fake_dns_enabled (тумблер) + fakedns в sniffing | profile.fakeDns, верхнеуровневая секция fakeDns + fakedns в sniffing.destOverride | core/subscription/XrayConfigBuilder.kt:106-110, 284-292 | ЧАСТИЧНО — нет тумблера в UI (issue #3) |
| B4 | §4 (DNS from JSON) | title_pref_dns_from_json | НЕТ | — | **РАСХОЖДЕНИЕ** |
| B5 | §4 (Local DNS enabled) | title_pref_local_dns_enabled | НЕТ (нет local DNS на порту 10853) | — | **РАСХОЖДЕНИЕ** |
| B6 | §4 (queryStrategy) | автоматически | advanced.queryStrategy (UseIPv4/UseIP/AsIs) | core/settings/AdvancedConfig.kt, RoutingRulesBuilder.kt:118 | СООТВЕТСТВУЕТ |
| B7 | §4 (DoH fallback) | домен в dns.servers | DoH: если есть IP — https://IP/path, иначе домен как есть | RoutingRulesBuilder.kt:161-173 | ЧАСТИЧНО — IP в DoH работает, но bootstrap DNS для домена может дать цикл |
| B8 | §4 (AAAA обработка) | через fakeDns ipPool 198.18.0.0/15 | fakeDns ipPool 198.18.0.0/16 | core/subscription/XrayConfigBuilder.kt:53 | ЧАСТИЧНО — пул другой (16 вместо 15), но работает |

---

## C. Routing: порядок правил, замыкание маршрута, geosite/geoip, приватные диапазоны

| № | Параграф карты | Как в Happ | Как у нас | Файл/строка | Статус |
|---|---|---|---|---|---|
| C1 | §5 (routing_activity_order_routing) | block → proxy → direct | block → proxy (если !globalProxy) → direct | core/routing/RoutingRulesBuilder.kt:47-57 | СООТВЕТСТВУЕТ |
| C2 | §5 (global proxy) | globalProxy=true → всё через прокси, правила proxy игнорируются | то же: при globalProxy proxy-правила не добавляются | RoutingRulesBuilder.kt:52-54 | СООТВЕТСТВУЕТ |
| C3 | §5 (catch-all) | routing.final (если есть) или последнее правило | catchAllRule последним в rules: outboundTag сервера или balancerTag auto | XrayConfigBuilder.kt:244-246, RoutingRulesBuilder.kt:63-67 | СООТВЕТСТВУЕТ (routing.final игнорируется ядром) |
| C4 | §5 (private CIDRs) | приватные диапазоны всегда direct | PRIVATE_CIDRS включает IPv4 и IPv6 (::1/128, fc00::/7, fe80::/10) | XrayConfigBuilder.kt:57-65 | СООТВЕТСТВУЕТ |
| C5 | §5 (geoip/geosite теги) | routing_activity_show_tags_in_geo_* | НЕТ — нет UI для показа тегов | — | **РАСХОЖДЕНИЕ** (issue #2) |
| C6 | §5 (LAN addresses) | routing_settings_set_lan_addresses | НЕТ | — | **РАСХОЖДЕНИЕ** (issue #2) |
| C7 | §5 (import/export) | routing_profile_successfully_add/update, export_failure | Частично — есть SAF-импорт, но UI не полностью | core/routing/ | ЧАСТИЧНО (issue #2) |
| C8 | §5 (geo в JSON подписки) | routing_profile_json_restrict_geo_edit | НЕ ПРОВЕРЯЛИ | core/routing/RoutingProfileStore.kt | **НЕПРОВЕРЕНО** |

---

## D. Выбор сервера: авто-выбор, whitelist-узлы (WL), балансировка

| № | Параграф карты | Как в Happ | Как у нас | Файл/строка | Статус |
|---|---|---|---|---|---|
| D1 | §3 (sub_properties_general_connect_to) | Connect to / фильтр / сортировка серверов | НЕТ — выбран только один сервер, сортировки нет | ui/screens/DashboardScreen.kt, ServerRow | **РАСХОЖДЕНИЕ** (issue #5) |
| D2 | §3 (autoswitch onlymobile) | sub_properties_autoswitch_onlymobile* + timeout | НЕТ | — | **РАСХОЖДЕНИЕ** (issue #5) |
| D3 | §4 (balancer auto) | — | balancer "auto" с leastPing, включает ВСЕ узлы подписки (в т.ч. WL) | XrayConfigBuilder.kt:250-261 | **РАСХОЖДЕНИЕ** — эталон может исключать WL из авто, у нас включаем все |
| D4 | §4 (server sort) | server_sort_title | НЕТ | — | **РАСХОЖДЕНИЕ** (issue #5) |

---

## E. Жизненный цикл: старт, готовность, остановка, повторный старт, revoke

| № | Параграф карты | Как в Happ | Как у нас | Файл/строка | Статус |
|---|---|---|---|---|---|
| E1 | §2 (VpnService start) | процесс :XRayDaemon, establis() → fd → hev-socks5-tunnel | establish() → fd → xray.tun.fd → fork/exec Xray | bg/VPNService.kt:120-180, core/engine/XrayNative.kt | АРХИТЕКТУРНОЕ РАСХОЖДЕНИЕ |
| E2 | §2 (готовность) | — | READY_TIMEOUT_MS 5с, nativeIsAlive каждые 200мс | bg/VPNService.kt:190-200 | СООТВЕТСТВУЕТ |
| E3 | §2 (остановка) | — | SIGTERM → grace 1.5с → SIGKILL, lock-файл, EngineManager STOPPING + watchdog 1.5с | core/engine/TunnelTeardown.kt, core/engine/TunnelProcessRegistry.kt, core/engine/EngineManager.kt | СООТВЕТСТВУЕТ (issue #14 закрыт) |
| E4 | §2 (revoke) | onRevoke() → stopTunnel() | onRevoke() → stopTunnel() | bg/VPNService.kt:380-382 | СООТВЕТСТВУЕТ |
| E5 | §2 (повторный старт) | — | EngineManager scope = SupervisorJob, watchdog 25с, start вне UI-потока | core/engine/EngineManager.kt:70-120 | СООТВЕТСТВУЕТ |
| E6 | §2 (UI при старте) | кнопка работает во всех состояниях | кнопка монеты в STARTING/STOPPING → stopService() | ui/screens/DashboardScreen.kt | ЧАСТИЧНО — есть «Отключение…» строка |

---

## F. Дополнительные фичи эталона (issues #3, #4, #5, #6)

| № | Параграф карты | Как в Happ | Как у нас | Файл/строка | Статус |
|---|---|---|---|---|---|
| F1 | §4 (Mux QUIC handling) | settings_mux_handling | advanced.muxXudpBehavior (reject/prefer/xudpOnly) | core/settings/AdvancedConfig.kt | ЧАСТИЧНО — раздельно TCP/XUDP |
| F2 | §4 (Fragmentation 4 поля) | length/interval/maxSplit/packets | НЕТ в XrayConfigBuilder | — | **РАСХОЖДЕНИЕ** (issue #3) |
| F3 | §4 (Noises 5 полей) | packet/delay/rand/range/type | НЕТ в XrayConfigBuilder | — | **РАСХОЖДЕНИЕ** (issue #3) |
| F4 | §4 (Sniffing) | title_pref_sniffing_enabled | sniffing.enabled=true + destOverride http/tls/quic (+fakedns) | XrayConfigBuilder.kt:284-292 | ЧАСТИЧНО — нет тумблера в UI |
| F5 | §4 (URL test) | title_pref_delay_test_url | PROBE_URL жёсткий gstatic/generate_204 | XrayConfigBuilder.kt:40 | **РАСХОЖДЕНИЕ** (issue #3) |
| F6 | §6 (Ping test) | connection_test_available/error_tls_handshake/... | НЕТ экрана PING_SETTINGS | — | **РАСХОЖДЕНИЕ** (issue #4) |
| F7 | §6 (Statistics) | statistics_* (up/down/direct/proxy/bandwidth) | НЕТ экрана STATISTICS | — | **РАСХОЖДЕНИЕ** (issue #4) |
| F8 | §3 (Subscription props) | UA, timeout 5-15s, auto-update, autoswitch | НЕТ экрана SUBSCRIPTION_PROPERTIES | — | **РАСХОЖДЕНИЕ** (issue #5) |
| F9 | §7 (Widget) | appwidget + 2 actions | НЕТ | — | **РАСХОЖДЕНИЕ** (issue #6) |
| F10 | §7 (Shortcuts) | android.app.shortcuts meta-data | НЕТ | — | **РАСХОЖДЕНИЕ** (issue #6) |
| F11 | §7 (QR Scanner) | ScannerActivity, ScScannerActivity | QR_SCAN маршрут есть (Transfer/Scanner), но CameraX UI | ui/screens/QrScannerScreen.kt | ЧАСТИЧНО |
| F12 | §7 (FAQ) | FaqSettingsActivity, WebViewActivity | НЕТ | — | **РАСХОЖДЕНИЕ** (issue #6) |
| F13 | §7 (Inbound auth) | Auto/Manual/JSON/Disable + user/pass | НЕТ | — | **РАСХОЖДЕНИЕ** (issue #6) |
| F14 | §7 (Per-app proxy presets) | google/spy пресеты | 3 режима + пресеты в PackagePolicy | bg/PackagePolicy.kt | ЧАСТИЧНО — нет UI |

---

## G. Известные баги, уже закрытые (для контекста)

| № | Проблема | Коммит/PR | Статус |
|---|---|---|---|
| G1 | xray.tun.fd вместо XRAY_TUN_FD | 9182495 / PR #13 | ЗАКРЫТО |
| G2 | tun inbound без name → netlink | 9182495 / PR #13 | ЗАКРЫТО |
| G3 | stopTunnel не убивал ядро | 1ea6ead / PR #15 | ЗАКРЫТО |
| G4 | catch-all на несуществующий proxy | 6d5f401 / PR #15 | ЗАКРЫТО |
| G5 | нет DNS intercept / dns outbound | 6d5f401 / PR #15 | ЗАКРЫТО |
| G6 | fakeDns вложен в dns + не в sniffing | 6d5f401 / PR #15 | ЗАКРЫТО |

---

## Сводка: что требует правки (не фич, а тракт)

**Критичные (трафик не пойдёт / утечки):**
1. A6 — IPv6 маршрут отсутствует (addRoute("::",0) + IPv6 DNS)
2. A4 — setMetered(false) на всех API уровнях
3. B5 — Local DNS 10853 (или документировать, что не делаем)
4. B4 — DNS from JSON (или документировать)
5. D3 — балансировщик включает WL-узлы (нужно исключать)

**Важные (функциональность):**
6. C5 — показ тегов в geoip/geosite
7. C6 — LAN адреса в профиле
8. D1/D2/D4 — connect_to, autoswitch, сортировка серверов (issue #5)
9. F2/F3 — fragmentation / noises (issue #3)
10. F5 — настраиваемый URL-тест (issue #3)
11. F6/F7 — ping/statistics экраны (issue #4)
12. F8 — subscription properties (issue #5)
13. F9-F13 — widget, shortcuts, QR, FAQ, inbound auth (issue #6)

**Архитектурные (не чиним, но документируем):**
- A1 — процесс движка (fork/exec vs JNI)
- A2 — MTU 1280 vs 1500 (намеренно)
- E1 — схема передачи fd

---

## Приоритет следующего PR (пакет тракта)

Сгруппировать в один PR: A4, A6, B1 (добавить local DNS порт), B5 (документировать), C5/C6 (issue #2), D3 (исключить WL из балансировщика).

Второй PR: жизненный цикл E3-E5 (уже есть, но проверить на устройстве).
