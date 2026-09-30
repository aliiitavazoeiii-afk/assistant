package com.ali.assistant.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ReminderStore(context: Context) : SQLiteOpenHelper(context, "biyok.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE reminders(id INTEGER PRIMARY KEY AUTOINCREMENT,text TEXT NOT NULL,created_at INTEGER NOT NULL,remind_at INTEGER,done INTEGER NOT NULL DEFAULT 0)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun add(text: String, remindAt: Long?): Long {
        val v = ContentValues().apply {
            put("text", text)
            put("created_at", System.currentTimeMillis())
            if (remindAt == null) putNull("remind_at") else put("remind_at", remindAt)
            put("done", 0)
        }
        return writableDatabase.insertOrThrow("reminders", null, v)
    }

    fun listOpen(): List<ReminderItem> = readableDatabase.rawQuery(
        "SELECT id,text,created_at,remind_at,done FROM reminders WHERE done=0 ORDER BY COALESCE(remind_at, 9223372036854775807), created_at DESC", null
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(ReminderItem(c.getLong(0), c.getString(1), c.getLong(2), if (c.isNull(3)) null else c.getLong(3), c.getInt(4) != 0))
        }
    }

    fun complete(id: Long) {
        writableDatabase.execSQL("UPDATE reminders SET done=1 WHERE id=?", arrayOf(id))
    }

    fun delete(id: Long) {
        writableDatabase.delete("reminders", "id=?", arrayOf(id.toString()))
    }
}
