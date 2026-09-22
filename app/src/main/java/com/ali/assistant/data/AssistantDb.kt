package com.ali.assistant.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AssistantDb(context: Context) : SQLiteOpenHelper(context, "assistant.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE notes(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                category TEXT NOT NULL,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE schedules(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                trigger_at INTEGER NOT NULL,
                label TEXT NOT NULL,
                song_query TEXT,
                strict INTEGER NOT NULL DEFAULT 0,
                challenge TEXT NOT NULL DEFAULT 'NONE'
            )""".trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun saveNote(category: String, title: String, content: String): Long {
        val values = ContentValues().apply {
            put("category", category)
            put("title", title)
            put("content", content)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("notes", null, values)
    }

    fun listNotes(category: String?, query: String?, limit: Int): List<Map<String, Any>> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (!category.isNullOrBlank()) {
            where += "category = ?"
            args += category
        }
        if (!query.isNullOrBlank()) {
            where += "(title LIKE ? OR content LIKE ?)"
            args += "%$query%"
            args += "%$query%"
        }
        val result = mutableListOf<Map<String, Any>>()
        readableDatabase.query(
            "notes",
            arrayOf("id", "category", "title", "content", "created_at"),
            if (where.isEmpty()) null else where.joinToString(" AND "),
            if (args.isEmpty()) null else args.toTypedArray(),
            null,
            null,
            "created_at DESC",
            limit.coerceIn(1, 100).toString(),
        ).use { c ->
            while (c.moveToNext()) {
                result += mapOf(
                    "id" to c.getLong(0),
                    "category" to c.getString(1),
                    "title" to c.getString(2),
                    "content" to c.getString(3),
                    "createdAt" to c.getLong(4),
                )
            }
        }
        return result
    }

    fun saveSchedule(kind: String, triggerAt: Long, label: String, songQuery: String?, strict: Boolean, challenge: String): Long {
        val values = ContentValues().apply {
            put("kind", kind)
            put("trigger_at", triggerAt)
            put("label", label)
            put("song_query", songQuery)
            put("strict", if (strict) 1 else 0)
            put("challenge", challenge)
        }
        return writableDatabase.insertOrThrow("schedules", null, values)
    }

    fun futureSchedules(now: Long = System.currentTimeMillis()): List<ScheduleRecord> {
        val out = mutableListOf<ScheduleRecord>()
        readableDatabase.query(
            "schedules",
            arrayOf("id", "kind", "trigger_at", "label", "song_query", "strict", "challenge"),
            "trigger_at > ?",
            arrayOf(now.toString()),
            null,
            null,
            "trigger_at ASC",
        ).use { c ->
            while (c.moveToNext()) {
                out += ScheduleRecord(
                    id = c.getLong(0),
                    kind = c.getString(1),
                    triggerAt = c.getLong(2),
                    label = c.getString(3),
                    songQuery = c.getString(4),
                    strict = c.getInt(5) == 1,
                    challenge = c.getString(6),
                )
            }
        }
        return out
    }
}

data class ScheduleRecord(
    val id: Long,
    val kind: String,
    val triggerAt: Long,
    val label: String,
    val songQuery: String?,
    val strict: Boolean,
    val challenge: String,
)
