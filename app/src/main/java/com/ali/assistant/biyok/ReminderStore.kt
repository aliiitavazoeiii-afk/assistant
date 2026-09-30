package com.ali.assistant.biyok

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ReminderItem(
    val id: Long,
    val text: String,
    val sourceText: String,
    val createdAt: Long,
    val dueAt: Long?,
    val completed: Boolean,
    val createdByVoice: Boolean,
)

class ReminderStore private constructor(context: Context) {
    private val db = Db(context.applicationContext)
    private val _items = MutableStateFlow<List<ReminderItem>>(emptyList())
    val items: StateFlow<List<ReminderItem>> = _items

    init { refresh() }

    @Synchronized
    fun add(text: String, sourceText: String = text, dueAt: Long? = null, byVoice: Boolean = true): ReminderItem {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("text", text.trim())
            put("source_text", sourceText.trim())
            put("created_at", now)
            if (dueAt == null) putNull("due_at") else put("due_at", dueAt)
            put("completed", 0)
            put("created_by_voice", if (byVoice) 1 else 0)
        }
        val id = db.writableDatabase.insertOrThrow("reminders", null, values)
        val item = get(id) ?: error("Reminder insert succeeded but row was not found")
        refresh()
        return item
    }

    @Synchronized
    fun get(id: Long): ReminderItem? = db.readableDatabase.query(
        "reminders", COLUMNS, "id=?", arrayOf(id.toString()), null, null, null, "1"
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toItem() else null }

    @Synchronized
    fun setCompleted(id: Long, completed: Boolean) {
        db.writableDatabase.update(
            "reminders",
            ContentValues().apply { put("completed", if (completed) 1 else 0) },
            "id=?",
            arrayOf(id.toString())
        )
        refresh()
    }

    @Synchronized
    fun delete(id: Long) {
        db.writableDatabase.delete("reminders", "id=?", arrayOf(id.toString()))
        refresh()
    }

    @Synchronized
    fun pendingScheduled(): List<ReminderItem> = query(
        "completed=0 AND due_at IS NOT NULL",
        null,
        "due_at ASC"
    )

    @Synchronized
    fun openCount(): Int = db.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM reminders WHERE completed=0", null
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    @Synchronized
    fun refresh() {
        _items.value = query(
            null,
            null,
            "completed ASC, CASE WHEN due_at IS NULL THEN 1 ELSE 0 END ASC, due_at ASC, created_at DESC"
        )
    }

    private fun query(selection: String?, args: Array<String>?, order: String): List<ReminderItem> =
        db.readableDatabase.query("reminders", COLUMNS, selection, args, null, null, order)
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.toItem())
                }
            }

    private fun android.database.Cursor.toItem(): ReminderItem = ReminderItem(
        id = getLong(getColumnIndexOrThrow("id")),
        text = getString(getColumnIndexOrThrow("text")),
        sourceText = getString(getColumnIndexOrThrow("source_text")) ?: "",
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        dueAt = getColumnIndexOrThrow("due_at").let { index -> if (isNull(index)) null else getLong(index) },
        completed = getInt(getColumnIndexOrThrow("completed")) != 0,
        createdByVoice = getInt(getColumnIndexOrThrow("created_by_voice")) != 0,
    )

    private class Db(context: Context) : SQLiteOpenHelper(context, "biyok.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE reminders (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    text TEXT NOT NULL,
                    source_text TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL,
                    due_at INTEGER,
                    completed INTEGER NOT NULL DEFAULT 0,
                    created_by_voice INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_reminders_due ON reminders(completed, due_at)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        private val COLUMNS = arrayOf("id", "text", "source_text", "created_at", "due_at", "completed", "created_by_voice")
        @Volatile private var instance: ReminderStore? = null

        fun get(context: Context): ReminderStore = instance ?: synchronized(this) {
            instance ?: ReminderStore(context).also { instance = it }
        }
    }
}
