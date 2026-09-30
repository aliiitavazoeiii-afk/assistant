package com.ali.assistant.biyok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PersianReminderParserTest {
    private val zone = ZoneId.of("Asia/Tehran")
    private val now = ZonedDateTime.of(2026, 9, 30, 18, 0, 0, 0, zone)

    @Test
    fun inboxReminderWithoutTime() {
        val parsed = PersianReminderParser.parse("یادم بنداز شیر بخرم", now)
        assertEquals(VoiceCommandKind.ADD, parsed.kind)
        assertEquals("شیر بخرم", parsed.text)
        assertNull(parsed.dueAtMillis)
    }

    @Test
    fun tomorrowAtThreeUsesAfternoonHeuristic() {
        val parsed = PersianReminderParser.parse("یادم بنداز فردا ساعت سه به علی زنگ بزنم", now)
        val expected = ZonedDateTime.of(2026, 10, 1, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("به علی زنگ بزنم", parsed.text)
        assertEquals(expected, parsed.dueAtMillis)
    }

    @Test
    fun relativeMinutes() {
        val parsed = PersianReminderParser.parse("یادم بنداز بیست دقیقه دیگه چای رو بردارم", now)
        val expected = now.plusMinutes(20).withSecond(0).withNano(0).toInstant().toEpochMilli()
        assertEquals("چای رو بردارم", parsed.text)
        assertEquals(expected, parsed.dueAtMillis)
    }

    @Test
    fun explicitNightTime() {
        val parsed = PersianReminderParser.parse("یادم بنداز امشب ساعت ۱۰ گزارش رو چک کنم", now)
        val expected = ZonedDateTime.of(2026, 9, 30, 22, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("گزارش رو چک کنم", parsed.text)
        assertEquals(expected, parsed.dueAtMillis)
    }

    @Test
    fun tomorrowMorningDefaultsToNine() {
        val parsed = PersianReminderParser.parse("یادم بنداز فردا صبح سفارش رو پیگیری کنم", now)
        val expected = ZonedDateTime.of(2026, 10, 1, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("سفارش رو پیگیری کنم", parsed.text)
        assertEquals(expected, parsed.dueAtMillis)
    }

    @Test
    fun persianDigitsWork() {
        val parsed = PersianReminderParser.parse("یادم بنداز فردا ساعت ۹:۳۰ جلسه رو شروع کنم", now)
        val expected = ZonedDateTime.of(2026, 10, 1, 9, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("جلسه رو شروع کنم", parsed.text)
        assertEquals(expected, parsed.dueAtMillis)
    }

    @Test
    fun listCommandDoesNotCreateReminder() {
        val parsed = PersianReminderParser.parse("کارهایی که گفتم انجام بدم رو نشون بده", now)
        assertEquals(VoiceCommandKind.SHOW_LIST, parsed.kind)
    }
}
