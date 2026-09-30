package com.ali.assistant.core

import java.time.*

object PersianReminderParser {
    data class Parsed(val text: String, val remindAt: Long?)

    fun parse(raw: String, now: ZonedDateTime = ZonedDateTime.now()): Parsed {
        var s = raw.trim().replace(Regex("^(بیوک[،, ]*)"), "")
        s = s.replace(Regex("^(یادم بنداز|یادم بینداز|یادآوری کن)( که)?\\s*"), "").trim()
        var at: ZonedDateTime? = null
        val lower = s
        val hm = Regex("(?:ساعت\\s*)?(\\d{1,2})(?::(\\d{1,2}))?").find(lower)
        val h = hm?.groupValues?.getOrNull(1)?.toIntOrNull()
        val m = hm?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
        when {
            lower.contains("فردا") && h != null -> at = now.plusDays(1).withHour(h.coerceIn(0,23)).withMinute(m.coerceIn(0,59)).withSecond(0).withNano(0)
            lower.contains("امشب") -> at = now.withHour(if (h != null) h.coerceIn(0,23) else 21).withMinute(m).withSecond(0).withNano(0).let { if (it.isBefore(now)) it.plusDays(1) else it }
            lower.contains("امروز") && h != null -> at = now.withHour(h.coerceIn(0,23)).withMinute(m.coerceIn(0,59)).withSecond(0).withNano(0).let { if (it.isBefore(now)) it.plusDays(1) else it }
            h != null && (lower.contains("ساعت") || lower.matches(Regex(".*\\d{1,2}:\\d{1,2}.*"))) -> at = now.withHour(h.coerceIn(0,23)).withMinute(m.coerceIn(0,59)).withSecond(0).withNano(0).let { if (it.isBefore(now)) it.plusDays(1) else it }
        }
        val cleaned = s
            .replace(Regex("\\b(امروز|فردا|امشب)\\b"), "")
            .replace(Regex("ساعت\\s*\\d{1,2}(?::\\d{1,2})?"), "")
            .replace(Regex("\\s+"), " ").trim(' ', '،', ',')
        return Parsed(cleaned.ifBlank { s }, at?.toInstant()?.toEpochMilli())
    }
}
