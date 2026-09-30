package com.ali.assistant.core

import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

object PersianReminderParser {
    data class Parsed(val text: String, val remindAt: Long?)

    fun parse(raw: String, now: ZonedDateTime = ZonedDateTime.now()): Parsed {
        var s = normalizeDigits(raw.trim().replace('‌', ' '))
            .replace(Regex("^(بیوک[،, ]*)"), "")
            .replace(Regex("^(لطفا\\s*)?(یادم بنداز|یادم بینداز|یادآوری کن)( که)?\\s*"), "")
            .trim()
        s = normalizeNumberWords(s)

        val lower = s.lowercase()
        var at: ZonedDateTime? = null

        val relative = Regex("(\\d{1,3})\\s*(دقیقه|ساعت)\\s*(دیگه|دیگر|بعد)").find(lower)
        if (lower.contains("نیم ساعت دیگه") || lower.contains("نیم ساعت بعد")) {
            at = now.plusMinutes(30)
        } else if (relative != null) {
            val amount = relative.groupValues[1].toLongOrNull() ?: 0L
            at = if (relative.groupValues[2] == "ساعت") now.plusHours(amount) else now.plusMinutes(amount)
        }

        val clock = Regex("ساعت\\s*(\\d{1,2})(?:\\s*[:و]\\s*(\\d{1,2}))?").find(lower)
            ?: Regex("(?<!\\d)(\\d{1,2}):(\\d{1,2})(?!\\d)").find(lower)
        val h = clock?.groupValues?.getOrNull(1)?.toIntOrNull()
        var m = clock?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
        if (clock != null && lower.substring(clock.range.last.coerceAtMost(lower.lastIndex)).take(12).contains("نیم")) m = 30

        if (at == null) {
            val dayOffset = when {
                lower.contains("پس فردا") -> 2L
                lower.contains("فردا") -> 1L
                else -> 0L
            }
            val dayPartHour = when {
                lower.contains("صبح") -> 9
                lower.contains("ظهر") -> 13
                lower.contains("بعد از ظهر") -> 16
                lower.contains("عصر") -> 18
                lower.contains("شب") || lower.contains("امشب") -> 21
                else -> null
            }
            val explicitDay = dayOffset > 0 || lower.contains("امروز") || lower.contains("امشب")
            if (h != null || dayPartHour != null || explicitDay) {
                val targetHour = (h ?: dayPartHour ?: 9).coerceIn(0, 23)
                val targetMinute = m.coerceIn(0, 59)
                var candidate = now.plusDays(dayOffset).withHour(targetHour).withMinute(targetMinute).withSecond(0).withNano(0)
                if (dayOffset == 0L && candidate.isBefore(now)) candidate = candidate.plusDays(1)
                at = candidate
            }
        }

        if (at == null) {
            val weekdays = mapOf(
                "شنبه" to DayOfWeek.SATURDAY,
                "یکشنبه" to DayOfWeek.SUNDAY,
                "دوشنبه" to DayOfWeek.MONDAY,
                "سه شنبه" to DayOfWeek.TUESDAY,
                "چهارشنبه" to DayOfWeek.WEDNESDAY,
                "پنجشنبه" to DayOfWeek.THURSDAY,
                "جمعه" to DayOfWeek.FRIDAY
            )
            val found = weekdays.entries.firstOrNull { lower.contains(it.key) }
            if (found != null) {
                val targetHour = (h ?: when {
                    lower.contains("صبح") -> 9
                    lower.contains("ظهر") -> 13
                    lower.contains("عصر") -> 18
                    lower.contains("شب") -> 21
                    else -> 9
                }).coerceIn(0, 23)
                var candidate = now.with(TemporalAdjusters.next(found.value)).withHour(targetHour).withMinute(m.coerceIn(0,59)).withSecond(0).withNano(0)
                at = candidate
            }
        }

        val cleaned = cleanReminderText(s)
        return Parsed(cleaned.ifBlank { s }, at?.toInstant()?.toEpochMilli())
    }

    private fun cleanReminderText(s: String): String = s
        .replace(Regex("(نیم ساعت|\\d{1,3}\\s*(?:دقیقه|ساعت))\\s*(?:دیگه|دیگر|بعد)"), "")
        .replace(Regex("\\b(?:امروز|فردا|پس فردا|امشب|صبح|ظهر|بعد از ظهر|عصر|شب)\\b"), "")
        .replace(Regex("\\b(?:شنبه|یکشنبه|دوشنبه|سه شنبه|چهارشنبه|پنجشنبه|جمعه)\\b"), "")
        .replace(Regex("ساعت\\s*\\d{1,2}(?:\\s*[:و]\\s*\\d{1,2})?(?:\\s*و?\\s*نیم)?"), "")
        .replace(Regex("(?<!\\d)\\d{1,2}:\\d{1,2}(?!\\d)"), "")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '،', ',', '-', '؛')

    private fun normalizeDigits(s: String): String {
        val fa = "۰۱۲۳۴۵۶۷۸۹"
        val ar = "٠١٢٣٤٥٦٧٨٩"
        return buildString(s.length) {
            for (c in s) {
                val fi = fa.indexOf(c)
                val ai = ar.indexOf(c)
                append(when {
                    fi >= 0 -> ('0'.code + fi).toChar()
                    ai >= 0 -> ('0'.code + ai).toChar()
                    else -> c
                })
            }
        }
    }

    private fun normalizeNumberWords(s: String): String {
        var out = s
        val words = linkedMapOf(
            "بیست و سه" to "23", "بیست و دو" to "22", "بیست و یک" to "21",
            "بیست" to "20", "نوزده" to "19", "هجده" to "18", "هفده" to "17", "شانزده" to "16", "پانزده" to "15", "چهارده" to "14", "سیزده" to "13", "دوازده" to "12", "یازده" to "11", "ده" to "10",
            "نه" to "9", "هشت" to "8", "هفت" to "7", "شش" to "6", "پنج" to "5", "چهار" to "4", "سه" to "3", "دو" to "2", "یک" to "1"
        )
        for ((word, number) in words) out = out.replace(Regex("(?<![\\p{L}])${Regex.escape(word)}(?![\\p{L}])"), number)
        return out
    }
}
