package com.ali.assistant.biyok

import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

enum class VoiceCommandKind { ADD, SHOW_LIST }

data class ParsedVoiceCommand(
    val kind: VoiceCommandKind,
    val text: String,
    val dueAtMillis: Long? = null,
    val rawText: String,
)

object PersianReminderParser {
    private val numberWords = mapOf(
        "صفر" to 0, "یک" to 1, "يه" to 1, "یه" to 1, "دو" to 2, "سه" to 3,
        "چهار" to 4, "پنج" to 5, "شش" to 6, "هفت" to 7, "هشت" to 8,
        "نه" to 9, "ده" to 10, "یازده" to 11, "دوازده" to 12,
        "سیزده" to 13, "چهارده" to 14, "پانزده" to 15, "شانزده" to 16,
        "هفده" to 17, "هجده" to 18, "نوزده" to 19, "بیست" to 20,
        "سی" to 30, "چهل" to 40, "پنجاه" to 50, "شصت" to 60,
    )

    private val dayNames = listOf(
        "شنبه" to DayOfWeek.SATURDAY,
        "یکشنبه" to DayOfWeek.SUNDAY,
        "دوشنبه" to DayOfWeek.MONDAY,
        "سه شنبه" to DayOfWeek.TUESDAY,
        "چهارشنبه" to DayOfWeek.WEDNESDAY,
        "پنجشنبه" to DayOfWeek.THURSDAY,
        "جمعه" to DayOfWeek.FRIDAY,
    )

    fun parse(raw: String, now: ZonedDateTime = ZonedDateTime.now()): ParsedVoiceCommand {
        val normalized = normalize(raw)
        val hasReminderPrefix = REMINDER_PREFIXES.any { normalized.startsWith(it) || normalized.contains(" $it") }
        if (!hasReminderPrefix && isListRequest(normalized)) {
            return ParsedVoiceCommand(VoiceCommandKind.SHOW_LIST, "", null, raw)
        }

        val due = extractDue(normalized, now)
        val cleanText = cleanReminderText(normalized, due.matchedFragments)
            .ifBlank { normalized.ifBlank { raw.trim() } }

        return ParsedVoiceCommand(
            kind = VoiceCommandKind.ADD,
            text = cleanText,
            dueAtMillis = due.whenAt?.toInstant()?.toEpochMilli(),
            rawText = raw.trim(),
        )
    }

    private fun isListRequest(text: String): Boolean {
        val patterns = listOf(
            "لیست کار", "کارهام", "کارام", "کارهایی که", "یادآوری هام", "یادآوریهام",
            "یادآوری ها", "چی باید انجام", "چه کارهایی", "کارهای من", "یادآوری های من",
        )
        return patterns.any { text.contains(it) }
    }

    private data class DueExtraction(
        val whenAt: ZonedDateTime?,
        val matchedFragments: List<String>,
    )

    private fun extractDue(text: String, now: ZonedDateTime): DueExtraction {
        val matched = mutableListOf<String>()

        // Relative phrases such as "20 دقیقه دیگه" or "دو ساعت بعد".
        val relative = Regex("([\\p{L}0-9]+)\\s*(دقیقه|ساعت|روز)\\s*(دیگه|دیگر|بعد)")
            .find(text)
        if (relative != null) {
            val amount = parseNumber(relative.groupValues[1])
            if (amount != null && amount > 0) {
                matched += relative.value
                val due = when (relative.groupValues[2]) {
                    "دقیقه" -> now.plusMinutes(amount.toLong())
                    "ساعت" -> now.plusHours(amount.toLong())
                    else -> now.plusDays(amount.toLong())
                }
                return DueExtraction(due.withSecond(0).withNano(0), matched)
            }
        }

        var explicitDate = false
        var date = now.toLocalDate()

        when {
            text.contains("پس فردا") || text.contains("پسفردا") -> {
                explicitDate = true
                date = date.plusDays(2)
                matched += if (text.contains("پس فردا")) "پس فردا" else "پسفردا"
            }
            text.contains("فردا") -> {
                explicitDate = true
                date = date.plusDays(1)
                matched += "فردا"
            }
            text.contains("امروز") -> {
                explicitDate = true
                matched += "امروز"
            }
            else -> {
                for ((name, day) in dayNames) {
                    if (text.contains(name)) {
                        explicitDate = true
                        var candidate = date.with(TemporalAdjusters.nextOrSame(day))
                        if (candidate == date && now.hour >= 22) candidate = candidate.plusWeeks(1)
                        date = candidate
                        matched += name
                        break
                    }
                }
            }
        }

        val dayPart = when {
            text.contains("بعد از ظهر") || text.contains("بعدازظهر") -> DayPart.AFTERNOON
            text.contains("عصر") -> DayPart.AFTERNOON
            text.contains("امشب") || text.contains("شب") -> DayPart.NIGHT
            text.contains("ظهر") -> DayPart.NOON
            text.contains("صبح") -> DayPart.MORNING
            else -> DayPart.NONE
        }
        when (dayPart) {
            DayPart.MORNING -> matched += "صبح"
            DayPart.NOON -> matched += "ظهر"
            DayPart.AFTERNOON -> {
                if (text.contains("بعد از ظهر")) matched += "بعد از ظهر"
                else if (text.contains("بعدازظهر")) matched += "بعدازظهر"
                else matched += "عصر"
            }
            DayPart.NIGHT -> matched += if (text.contains("امشب")) "امشب" else "شب"
            DayPart.NONE -> Unit
        }
        if (text.contains("امشب")) explicitDate = true

        val timeRegex = Regex(
            "ساعت\\s+([\\p{L}0-9]+)(?:\\s*[:٫.]\\s*([\\p{L}0-9]+))?(?:\\s+و\\s+(نیم|ربع|[\\p{L}0-9]+))?"
        )
        val timeMatch = timeRegex.find(text)

        var explicitTime = false
        var hour: Int? = null
        var minute = 0
        if (timeMatch != null) {
            val parsedHour = parseNumber(timeMatch.groupValues[1])
            if (parsedHour != null) {
                explicitTime = true
                hour = parsedHour
                val colonMinute = timeMatch.groupValues[2].takeIf { it.isNotBlank() }?.let(::parseNumber)
                val andMinute = timeMatch.groupValues[3].takeIf { it.isNotBlank() }?.let {
                    when (it) {
                        "نیم" -> 30
                        "ربع" -> 15
                        else -> parseNumber(it)
                    }
                }
                minute = (colonMinute ?: andMinute ?: 0).coerceIn(0, 59)
                matched += timeMatch.value
            }
        }

        if (!explicitTime && dayPart != DayPart.NONE && explicitDate) {
            explicitTime = true
            hour = when (dayPart) {
                DayPart.MORNING -> 9
                DayPart.NOON -> 12
                DayPart.AFTERNOON -> 17
                DayPart.NIGHT -> 20
                DayPart.NONE -> 9
            }
        }

        if (!explicitDate && !explicitTime) return DueExtraction(null, matched)

        if (!explicitTime) hour = 9
        var resolvedHour = resolveHour(hour ?: 9, dayPart)
        if (resolvedHour !in 0..23) resolvedHour = resolvedHour.coerceIn(0, 23)

        var due = date.atTime(resolvedHour, minute).atZone(now.zone)
        if (!explicitDate && !due.isAfter(now)) due = due.plusDays(1)
        if (explicitDate && due.isBefore(now.minusMinutes(1)) && date == now.toLocalDate()) due = due.plusDays(1)

        return DueExtraction(due.withSecond(0).withNano(0), matched)
    }

    private fun resolveHour(hour: Int, part: DayPart): Int {
        if (hour >= 13) return hour
        return when (part) {
            DayPart.MORNING -> if (hour == 12) 0 else hour
            DayPart.NOON -> if (hour < 11) hour + 12 else hour
            DayPart.AFTERNOON, DayPart.NIGHT -> if (hour < 12) hour + 12 else hour
            DayPart.NONE -> if (hour in 1..6) hour + 12 else hour
        }
    }

    private fun cleanReminderText(text: String, matchedFragments: List<String>): String {
        var result = text
        REMINDER_PREFIXES.sortedByDescending { it.length }.forEach { prefix ->
            result = result.replace(Regex("(^|\\s)${Regex.escape(prefix)}(?=\\s|$)"), " ")
        }
        matchedFragments.distinct().sortedByDescending { it.length }.forEach { fragment ->
            result = result.replace(fragment, " ")
        }
        result = result
            .replace(Regex("^\\s*(که|تا)\\s+"), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '،', ',', '.', '؛')
        return result
    }

    private fun parseNumber(token: String): Int? {
        val normalized = token.trim()
        normalized.toIntOrNull()?.let { return it }
        return numberWords[normalized]
    }

    fun normalize(value: String): String {
        val digits = buildString(value.length) {
            value.forEach { ch ->
                append(
                    when (ch) {
                        '۰', '٠' -> '0'; '۱', '١' -> '1'; '۲', '٢' -> '2'; '۳', '٣' -> '3';
                        '۴', '٤' -> '4'; '۵', '٥' -> '5'; '۶', '٦' -> '6'; '۷', '٧' -> '7';
                        '۸', '٨' -> '8'; '۹', '٩' -> '9'; 'ي', 'ى' -> 'ی'; 'ك' -> 'ک'; '\u200c' -> ' ';
                        else -> ch
                    }
                )
            }
        }
        return digits.lowercase()
            .replace("سه‌شنبه", "سه شنبه")
            .replace("پنج‌شنبه", "پنجشنبه")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private enum class DayPart { NONE, MORNING, NOON, AFTERNOON, NIGHT }

    private val REMINDER_PREFIXES = listOf(
        "یادم بنداز که", "یادم بنداز", "یادم بندازش", "یادآوری کن که", "یادآوری کن",
        "یادآوری بذار", "ثبت کن که", "ثبت کن", "یادت باشه", "باید",
    )
}
