package com.maestrovpn.client.whitelist

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Срез 2: из WhiteListConfig Maestro переносится ТОЛЬКО stripLegacy.
 *
 * Выпилены (CDN-ветка Maestro): ordinaryTag, inject, cdnRoute, edgeDirectRoute,
 * selectOrdinary, payload — все они обслуживали выбор CDN-подключения
 * (WhiteListSelection/Session) и в standalone-клиенте не существуют.
 */
internal object WhiteListConfig {
    /**
     * Выпил протоколов, которых больше нет в ядре и приложении (задача 17.11.2026: AWG/AnyTLS/
     * Naive выпилены из ядра v1.14.4, wdtt/olcrtc — из приложения). Панель ещё эмитит их теги,
     * поэтому перед передачей в ядро конфиг чистится здесь:
     *   • outbounds типов naive/anytls и с тегами olcrtc / olc rtc / vk-turn / wdtt;
     *   • endpoints типов awg и с тегами vk-turn / wdtt;
     *   • ссылки на удалённые теги в списках "outbounds" оставшихся selector/urltest;
     *   • "default" у "select" (первый оставшийся → "auto" → "direct");
     *   • route-правила со ссылкой на удалённый тег; route "final" при выпиле починяется.
     * Конфиг без выпиленного возвращается без изменений. Пустую selector/urltest-группу
     * дополняем "direct", если такой outbound есть, иначе оставляем как есть.
     */
    fun stripLegacy(base: String): String {
        val root = runCatching { Json.parseToJsonElement(base) as? JsonObject }.getOrNull() ?: return base
        val outbounds = (root["outbounds"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val endpoints = (root["endpoints"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.content
        val legacyTags = setOf("olcrtc", "olc rtc", "vk-turn", "wdtt")
        fun removed(o: JsonObject): Boolean =
            o.text("type") in setOf("naive", "anytls", "awg") || o.text("tag") in legacyTags
        val removedTags = (outbounds + endpoints).filter(::removed).mapNotNull { it.text("tag") }.toSet()
        if (removedTags.isEmpty()) return base

        val keptOutbounds = outbounds.filterNot(::removed)
        val cleaned = keptOutbounds.map { outbound ->
            var result = outbound
            val members = (outbound["outbounds"] as? JsonArray) ?: return@map result
            val kept = JsonArray(members.filterNot { (it as? JsonPrimitive)?.content in removedTags })
            result = JsonObject(outbound + ("outbounds" to kept))
            if ((outbound["type"] as? JsonPrimitive)?.content in setOf("selector", "urltest") && kept.isEmpty()) {
                val direct = keptOutbounds.firstOrNull { it.text("tag") == "direct" }
                if (direct != null) result = JsonObject(result + ("outbounds" to JsonArray(listOf(JsonPrimitive("direct")))))
            }
            if ((outbound["tag"] as? JsonPrimitive)?.content == "select") {
                val default = outbound.text("default")
                if (default == null || default in removedTags) {
                    val after = (result["outbounds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
                    val fallback = after.firstOrNull()
                        ?: "auto".takeIf { tag -> keptOutbounds.any { it.text("tag") == tag } }
                        ?: "direct".takeIf { tag -> keptOutbounds.any { it.text("tag") == tag } }
                    if (fallback != null) result = JsonObject(result + ("default" to JsonPrimitive(fallback)))
                }
            }
            result
        }

        val routes = (root["route"] as? JsonObject)?.let { route ->
            var cleanedRoute = route
            val rules = route["rules"] as? JsonArray
            if (rules != null) {
                val keptRules = JsonArray(rules.mapNotNull { it as? JsonObject }.filterNot { it.text("outbound") in removedTags })
                cleanedRoute = JsonObject(cleanedRoute + ("rules" to keptRules))
            }
            val final = route.text("final")
            if (final != null && final in removedTags) {
                val fallback = "select".takeIf { tag -> keptOutbounds.any { it.text("tag") == tag } }
                    ?: "direct".takeIf { tag -> keptOutbounds.any { it.text("tag") == tag } }
                if (fallback != null) cleanedRoute = JsonObject(cleanedRoute + ("final" to JsonPrimitive(fallback)))
            }
            cleanedRoute
        }

        var result = JsonObject(root + ("outbounds" to JsonArray(cleaned)))
        if (endpoints.isNotEmpty()) result = JsonObject(result + ("endpoints" to JsonArray(endpoints.filterNot(::removed))))
        if (routes != null) result = JsonObject(result + ("route" to routes))
        return result.toString()
    }
}
