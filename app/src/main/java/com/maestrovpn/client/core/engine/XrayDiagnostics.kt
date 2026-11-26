package com.maestrovpn.client.core.engine

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Разбор сбоя ядра: полный текст ошибки и полный конфиг на диск.
 *
 * Зачем: 26.11.2026 экран показывал обрезанный хвост («…netlin»), и причину
 * пришлось выяснять по строкам внутри бинаря. Теперь при отказе старта
 * пишется files/xray-failure.log — полный текст ошибки, конфиг (секреты
 * замаскированы) и хвост журнала ядра. Файл переживает закрытие приложения:
 * его можно забрать с устройства, не воспроизводя сбой заново.
 *
 * Маскируются только значения секретных ключей (id/uuid/пароли/ключи
 * Reality), структура JSON сохраняется — конфиг остаётся читаемым.
 */
object XrayDiagnostics {

    /** Имя файла отчёта в filesDir приложения. */
    const val FILE_NAME = "xray-failure.log"

    /** Сколько хвоста журнала ядра кладём в отчёт. */
    private const val LOG_TAIL_CHARS = 8_000

    /** Ключи, значения которых нельзя писать на диск в открытом виде. */
    private val SECRET_KEYS = setOf("id", "uuid", "password", "pass", "key", "seed")

    /** Окончания имён секретных полей. */
    private val SECRET_SUFFIXES = listOf(
        "password", "uuid", "publickey", "privatekey", "shortid", "secret",
    )

    private val STRING_FIELD = Regex(
        "\"([A-Za-z0-9_.-]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"",
    )

    /**
     * Секретное ли поле по его имени. Имена сравниваем в «плоском» виде:
     * private_key, privateKey и PRIVATE-KEY — одно и то же поле.
     */
    fun isSecretKey(key: String): Boolean {
        val lower = key.lowercase(Locale.ROOT)
        val compact = lower.filter { it.isLetterOrDigit() }
        return lower in SECRET_KEYS || compact in SECRET_KEYS ||
            SECRET_SUFFIXES.any { compact.endsWith(it) }
    }

    /** Заменить значения секретных ключей на ***, остальное оставить как есть. */
    fun maskSecrets(json: String): String = STRING_FIELD.replace(json) { match ->
        val key = match.groupValues[1]
        if (isSecretKey(key)) "\"" + key + "\":\"***\"" else match.value
    }

    /** Хвост файла: не больше maxChars символов и maxLines строк. */
    fun tail(file: File?, maxChars: Int = LOG_TAIL_CHARS, maxLines: Int = 200): String {
        if (file == null || !file.exists()) return ""
        return try {
            val text = file.readBytes().toString(Charsets.UTF_8)
            val cut = if (text.length > maxChars) text.takeLast(maxChars) else text
            val lines = cut.lineSequence().filter { it.isNotBlank() }.toList()
            lines.takeLast(maxLines).joinToString("\n")
        } catch (_: Exception) {
            ""
        }
    }

    /** Текст отчёта о сбое: этап, полная ошибка, конфиг, журнал. */
    fun report(
        stage: String,
        error: String,
        configJson: String?,
        logTail: String,
        now: Date = Date(),
    ): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)
        return buildString {
            appendLine("=== Maestro Client: сбой ядра Xray ===")
            appendLine("время: " + stamp)
            appendLine("этап: " + stage)
            appendLine()
            appendLine("--- ошибка (полный текст) ---")
            appendLine(error)
            appendLine()
            appendLine("--- конфиг ядра (секреты замаскированы) ---")
            appendLine(configJson?.let { maskSecrets(it) } ?: "(конфиг недоступен)")
            appendLine()
            appendLine("--- журнал ядра (хвост) ---")
            appendLine(logTail.ifBlank { "(пусто)" })
        }
    }

    /**
     * Записать отчёт в dir (filesDir приложения). Возвращает файл или null.
     * Ошибка записи не должна превращать отказ ядра в отказ приложения.
     */
    fun writeFailure(
        dir: File?,
        stage: String,
        error: String,
        configJson: String?,
        logTail: String,
    ): File? {
        if (dir == null) return null
        return try {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, FILE_NAME)
            file.writeText(report(stage, error, configJson, logTail))
            file
        } catch (_: Exception) {
            null
        }
    }
}
