package com.ali.assistant.core

import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class PersianReminderParserTest {
    private val zone = ZoneId.of("Asia/Tehran")
    private val now = ZonedDateTime.of(2026, 9, 30, 19, 0, 0, 0, zone)

    @Test fun parsesTomorrowPersianWordHour() {
        val p = PersianReminderParser.parse("یادم بنداز فردا ساعت ده پارچه سفارش بدم", now)
        assertEquals("پارچه سفارش بدم", p.text)
        val at = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(p.remindAt!!), zone)
        assertEquals(2026, at.year); assertEquals(10, at.monthValue); assertEquals(1, at.dayOfMonth)
        assertEquals(10, at.hour); assertEquals(0, at.minute)
    }

    @Test fun parsesRelativePersianDigits() {
        val p = PersianReminderParser.parse("۳۰ دقیقه دیگه به رضا زنگ بزنم", now)
        assertEquals("به رضا زنگ بزنم", p.text)
        val at = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(p.remindAt!!), zone)
        assertEquals(19, at.hour); assertEquals(30, at.minute)
    }

    @Test fun parsesHalfHour() {
        val p = PersianReminderParser.parse("نیم ساعت دیگه آب دستگاه رو چک کنم", now)
        assertEquals("آب دستگاه رو چک کنم", p.text)
        assertEquals(now.plusMinutes(30).toInstant().toEpochMilli(), p.remindAt)
    }

    @Test fun parsesSeparatedWeekdayName() {
        val p = PersianReminderParser.parse("سه شنبه عصر سفارش نخ رو چک کنم", now)
        assertEquals("سفارش نخ رو چک کنم", p.text)
        val at = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(p.remindAt!!), zone)
        assertEquals(DayOfWeek.TUESDAY, at.dayOfWeek)
        assertEquals(18, at.hour)
    }

    @Test fun noTimeStaysInListWithoutAlarm() {
        val p = PersianReminderParser.parse("یادم بنداز نمونه پارچه جدید رو ببینم", now)
        assertEquals("نمونه پارچه جدید رو ببینم", p.text)
        assertNull(p.remindAt)
    }
}
