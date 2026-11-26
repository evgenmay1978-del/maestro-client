package com.maestrovpn.client.core.subscription

/**
 * Напоминание об истечении подписки (issue #5): предупреждение за 3 дня,
 * по одному разу в сутки на каждый уровень.
 *
 * Чистая логика (без Android) — проверяется JVM-тестом.
 */
object ExpiryNotifier {

    /** Порог предупреждения — за сколько дней начинать уведомлять. */
    const val WARNING_DAYS = 3

    /** Какое уведомление сейчас нужно показать. */
    enum class Level {
        /** Никакого: до порога далеко. */
        NONE,

        /** Истекает скоро: меньше WARNING_DAYS дней. */
        WARNING,

        /** Уже истекла. */
        EXPIRED,
    }

    fun level(expireMillis: Long?, nowMillis: Long): Level {
        if (expireMillis == null || expireMillis <= 0) return Level.NONE
        // Истекла — только когда срок действительно прошёл.
        // Равный срок ещё жив: он на границе окна WARNING.
        if (expireMillis < nowMillis) return Level.EXPIRED
        val dayMillis = 86_400_000L
        // Остаток дней с округлением ВВЕРХ: «истекает сегодня» = 1 день,
        // иначе остаток менее суток дал бы 0 и молчал.
        val remaining = expireMillis - nowMillis
        val daysLeft = (remaining + dayMillis - 1) / dayMillis
        return if (daysLeft <= WARNING_DAYS) Level.WARNING else Level.NONE
    }

    /**
     * Ключ дня, за который уведомление уже показано.
     * Нужен, чтобы не показывать одно и то же напоминание каждый запуск:
     * в сутки — один раз на каждый уровень (WARNING и EXPIRED раздельно).
     */
    fun dayKey(subscriptionId: String, level: Level, nowMillis: Long): String {
        val day = nowMillis / 86_400_000L
        return "expiry_${subscriptionId}_${level.name}_$day"
    }

    /** Текст уведомления для уровня. */
    fun message(name: String, level: Level, daysLeft: Int): String = when (level) {
        Level.WARNING -> "Подписка «$name» истекает через $daysLeft дн."
        Level.EXPIRED -> "Подписка «$name» истекла"
        Level.NONE -> ""
    }
}
