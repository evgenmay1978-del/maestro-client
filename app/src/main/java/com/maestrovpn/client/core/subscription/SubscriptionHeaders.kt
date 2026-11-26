package com.maestrovpn.client.core.subscription

/**
 * Заголовки ответа подписки (спецификация §7).
 *
 * Читаются из HTTP-ответа при скачивании:
 * - `profile-title` — имя подписки (base64 в нашем источнике, plain в Akonit);
 * - `profile-update-interval` — тик автообновления в часах;
 * - `profile-web-page-url` — страница подписки;
 * - `subscription-userinfo` — трафик и срок (upload/download/total/expire);
 * - `support-url` — поддержка;
 * - `announce` — объявление (base64 или plain).
 *
 * Формат значений — по образцам (подтверждено живыми ответами wapmixx и akonit):
 * - `profile-title: base64:TWFlc3Ryb1ZQTg==` ← base64:
 * - `announce: base64:8J+SsyA…` ← base64:
 * - `profile-web-page-url: https://t.me/maestrovpn` ← plain URL
 * - `subscription-userinfo: upload=0; download=0; total=0; expire=4394044800` ← kv
 *
 * Пустые/невалидные значения не должны ломать импорт: все поля nullable.
 */
data class SubscriptionHeaders(
    val title: String? = null,
    val updateIntervalHours: Int? = null,
    val webPageUrl: String? = null,
    val upload: Long? = null,
    val download: Long? = null,
    val total: Long? = null,
    val expire: Long? = null,
    val supportUrl: String? = null,
    val announce: String? = null,
) {
    /** Дней до истечения, если срок указан и ещё не наступил; иначе null. */
    fun daysUntilExpire(nowMillis: Long): Int? {
        val exp = expire ?: return null
        if (exp <= 0) return null
        val delta = exp * 1000 - nowMillis
        if (delta <= 0) return 0
        return (delta / 86_400_000L).toInt()
    }

    companion object {
        /**
         * Разбор карты заголовков ответа. Регистр имён не важен (HTTP).
         * Неизвестные и битые значения пропускаются, а не роняют импорт.
         */
        fun parse(headerLines: Map<String, List<String>>?): SubscriptionHeaders {
            if (headerLines.isNullOrEmpty()) return SubscriptionHeaders()
            fun last(name: String): String? =
                headerLines.entries
                    .firstOrNull { it.key.equals(name, ignoreCase = true) }
                    ?.value
                    ?.lastOrNull()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }

            val userinfo = parseUserinfo(last("subscription-userinfo"))
            val expire = userinfo?.get("expire")?.toLongOrNull()
                ?.takeIf { it > 0 }

            return SubscriptionHeaders(
                title = decodeValue(last("profile-title")),
                updateIntervalHours = last("profile-update-interval")
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 },
                webPageUrl = last("profile-web-page-url"),
                upload = userinfo?.get("upload")?.toLongOrNull(),
                download = userinfo?.get("download")?.toLongOrNull(),
                total = userinfo?.get("total")?.toLongOrNull()
                    ?.takeIf { it > 0 },
                expire = expire,
                supportUrl = last("support-url"),
                announce = decodeValue(last("announce")),
            )
        }

        /**
         * Значение заголовка: либо `base64:…`, либо обычный текст.
         * Пустая/невалидная base64-строка → null.
         */
        fun decodeValue(raw: String?): String? {
            val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val body = when {
                value.startsWith("base64:") -> value.removePrefix("base64:").trim()
                else -> return value
            }
            val bytes = SubscriptionParser.decodeBase64Loose(body) ?: return null
            val text = String(bytes, Charsets.UTF_8).trim()
            return text.takeIf { it.isNotEmpty() }
        }

        /** `upload=0; download=0; total=0; expire=…` → карта; порядок пар не важен. */
        fun parseUserinfo(raw: String?): Map<String, String>? {
            val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val result = HashMap<String, String>()
            for (part in value.split(';')) {
                val kv = part.trim().split('=', limit = 2)
                if (kv.size != 2) continue
                val key = kv[0].trim().lowercase()
                val v = kv[1].trim()
                if (key.isNotEmpty() && v.isNotEmpty()) result[key] = v
            }
            return result.takeIf { it.isNotEmpty() }
        }
    }
}
