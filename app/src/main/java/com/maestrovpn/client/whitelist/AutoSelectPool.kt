package com.maestrovpn.client.whitelist

/**
 * Срез 2: no-op passthrough с тем же API, что у Maestro (Result + exclude).
 *
 * У Maestro «Авто» выбирает только среди сторонних серверов: собственные серверы
 * (S1/S3, wapmixx.ru) вырезаются из каждой urltest-группы. У standalone-клиента
 * «своих» серверов нет — все серверы в конфиге принадлежат подписке пользователя,
 * поэтому исключать нечего: конфиг возвращается как есть. OwnServers намеренно
 * НЕ копируется (его IP и хосты Maestro — не часть этого приложения).
 */
internal object AutoSelectPool {
    data class Result(
        val config: String,
        /** urltest tag → our servers taken out of it. */
        val removed: Map<String, List<String>>,
        /** urltest groups kept as they were because they hold nothing but our servers. */
        val ownOnly: List<String>,
    )

    fun exclude(config: String): Result = Result(config, emptyMap(), emptyList())
}
