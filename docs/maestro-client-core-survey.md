# Maestro Client — обзор ядра Xray (libmaestro_subxhttp.so)

Источник: app/src/main/jniLibs/arm64-v8a/libmaestro_subxhttp.so (33 685 798 Б) и его строки,
свёртывание через apktool-распаковку Happ 4.11.2 (libgojni.so + libhev-socks5-tunnel.so),
а также нативный спавнер app/src/main/cpp/xray_spawn.c и конфиг-билдер
app/src/main/java/com/maestrovpn/client/core/subscription/XrayConfigBuilder.kt.

Дата анализа: 26–27.11.2026. Это тех-обзор, не продакшн-описание.

## 1. Идентификация бинаря

| Параметр | Значение | Источник |
|---|---|---|
| Xray version | 26.11.9 Custom (go1.27.3 android/arm64) | `./xray version`, строки бинаря |
| ABI | arm64-v8a, armeabi-v7a (оба в jniLibs) | файловые |
| Build type | Custom (не upstream-релиз) | строки бинаря |
| Build date | не извлекается | — |

## 2. Поддержка TUN (критично)

| Факт | Подтверждение | Заметка |
|---|---|---|
| Читает fd из переменной | Только **xray.tun.fd** (не XRAY_TUN_FD) | строки бинаря: «xray.tun.fd» — 1, «XRAY_TUN_FD» — 0; дифференциальный прогон на 5 вариантах env |
| Путь к чтению | Xray-core proxy/tun/tun_android.go → platform.NewEnvFlag(platform.TunFdKey) | fetch Go-источника v26.11.9 |
| Без fd / без env | «read Android Tun Fd 0» → создаёт tun само → падает в netlink | strace + strings «fail to get system interface information» |
| Схема TunConfig (v26.11.9) | name, desc, mtu, gateway, dns, userLevel, autoSystemRoutingTable, autoOutboundsInterface, autoSystemDnsToGateway, autoSystemWfpBlockLeak | infra/conf/tun.go из исходников v26.11.9 |
| Поля отсутствуют в схеме | address, strictRoute, interfaceName | — |
| Netlink при пустом name | GetAvailableTunName → net.Interfaces() → запрещён в untrusted_app | точное соответствие тексту ошибки с устройства |
| Решение | settings.name непустой + только mtu в settings | локально проверено: с name netlink вызовов 0 |

## 3. DNS / FakeDNS / Sniffing

| Возможность | В бинаре / конфиге | Заметка |
|---|---|---|
| dns.servers | поддерживается (DoH URL, IP) | RoutingRulesBuilder.dns() |
| queryStrategy | "UseIPv4" работает | — |
| fakedns (верхний уровень) | поддерживается, ipPool | «fakedns» в settings |
| fakedns вложенный в dns | **не поддерживается** (схема не принимает) | тестовый конфиг с fakedns внутри dns — валидация проходит, но в runtime не работает |
| sniffing.destOverride | http, tls, quic | tun-inbound имеет sniffing |
| DNS outbound | нет встроенного — нужен outbound protocol=dns | XrayConfigBuilder создаёт его |

## 4. Mux

| Параметр | Поддержка | Заметка |
|---|---|---|
| mux.enabled / mux.concurrency | поддерживается | MULTIPLEXABLE_PROTOCOLS: vless/vmess/trojan/shadowsocks/socks/http |
| mux.xudpConcurrency | поддерживается | XUDP отдельно |
| mux.xudpBehavior | "reject" / "prefer" / "xudpOnly" | advanced.muxXudpBehavior |
| Vision flow | mux пропускается для VLESS с flow=xtls-rprx-vision | hasVisionFlow() |

## 5. Фрагментация и шумы

| Параметр | В бинаре / конфиге | Заметка |
|---|---|---|
| fragment.enabled + interval/length/maxSplit | в XrayConfigBuilder нет — поля не перенесены | issue #3 |
| noises (packet/delay/rand/range/type) | в XrayConfigBuilder нет — поля не перенесены | issue #3 |

## 6. Routing / Balancer / Policy

| Возможность | Статус | Заметка |
|---|---|---|
| routing.rules (field) | block → proxy → direct (порядок Happ) | RoutingRulesBuilder |
| routing.domainStrategy | IPIfNonMatch / AsIs / IPOnDemand | profile.domainStrategy |
| routing.balancers (auto) | есть, strategy leastPing | только при auto (не выбран сервер) |
| routing.final | **не используется** (catch-all rule вместо него) | catchAllRule на outboundTag сервера |
| policy.levels + system | statsInbound/Outbound включены | XrayConfigBuilder |
| api inbound (dokodemo-door) | порт 10085, services: Handler/Logger/Stats | для Stats API |

## 7. Жизненный цикл процесса (нативный спавнер)

| Факт | Детали |
|---|---|
| Запуск | fork/exec (xray_spawn.c) — единственный способ передать fd |
| fd передача | dup() в ребёнке → F_DUPFD(3) → снимает CLOEXEC → xray.tun.fd=<n> |
| CLOEXEC | исходный fd от VpnService имеет CLOEXEC; dup() копия — без |
| PR_SET_PDEATHSIG | SIGTERM — умирает с потоком-спавнером (app process killer) |
| Журнал ребёнка | /proc/self/fd + значение env перед execv (log_child_evidence) |
| stdout/stderr | редирект в файл лога (app/filesDir/xray.log) |
| Остановка | SIGTERM → grace 1.5с → SIGKILL (TunnelTeardown.kt) |
| Готовность | nativeIsAlive каждые 200 мс в течение 5 с (READY_TIMEOUT_MS) |

## 8. Расхождения с эталоном Happ 4.11.2 (технические)

| Аспект | Happ 4.11.2 | Maestro Client | Заметка |
|---|---|---|---|
| Ядро | Xray-core в libgojni.so (JNI, в процессе) | libmaestro_subxhttp.so (fork/exec) | архитектурно разные |
| Туннель | libhev-socks5-tunnel (tun2socks) → Xray socks | Xray tun inbound (свой fd) | мы делаем TUN внутри ядра, Happ — внешний |
| fd env | (не проверено — нет JNI-обёртки в APK) | xray.tun.fd | мы исправили на xray.tun.fd |
| DNS-перехват | через hev-socks5-tunnel | tun-inbound + DNS outbound + catch-all | схема другая |
| FakeDNS | встроен в бинарь | Xray fakedns (верхний уровень) | мы включили в sniffing |
| Mux handling QUIC | отдельный тумблер | advanced.muxXudpBehavior | — |

## 9. Открытые вопросы / риски

1. **IPv6 в туннеле**: VPNService строит только addAddress(IPv4) + addRoute("0.0.0.0",0). AAAA-запросы и IPv6-трафик уходят мимо VPN. Нужен addRoute("::", 0) и настройка DNS для IPv6.
2. **Metered/bypass**: VpnService не ставит setMetered(false) на API < Q; per-app bypass не полностью покрыт.
3. **DoH по IP-литералам**: текущий XrayConfigBuilder кладёт IP в dns.servers как есть — работает, но схема ожидает домен для DoH. Нужно явное разделение: DoH → URL, UDP → IP.
4. **XrayValidationConfig**: подменяет tun на socks для run -test — правильно, но в проде tun должен работать с fd. Закрыто fix/tun-start-fd.
5. **TunnelTeardown**: SIGTERM → grace → SIGKILL — закрыто fix/tunnel-stop-and-dns, но на устройстве не проверяли.

## 10. Что НЕ переносим из Happ (намеренно)

- Firebase / Sentry NDK / Play Core / WorkManager
- Чужие backend (tunnel-fetch, panel API)
- Схемы файлов и deeplink-имена чужих продуктов
- Любые упоминания наименования Happ в UI/коде
- Автообновление через backend (у нашего флота свой OTA-канал)
