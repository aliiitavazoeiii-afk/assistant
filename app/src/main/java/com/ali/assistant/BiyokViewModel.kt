package com.ali.assistant

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.ali.assistant.core.PersianReminderParser
import com.ali.assistant.core.ReminderItem
import com.ali.assistant.core.ReminderStore
import com.ali.assistant.reminders.ReminderScheduler

class BiyokViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ReminderStore(app)
    var items by mutableStateOf<List<ReminderItem>>(emptyList()); private set
    var status by mutableStateOf("آماده"); private set
    init { refresh() }
    fun refresh() { items = store.listOpen() }
    fun add(raw: String) {
        val p = PersianReminderParser.parse(raw)
        val id = store.add(p.text, p.remindAt)
        ReminderScheduler.schedule(getApplication(), id, p.text, p.remindAt)
        status = if (p.remindAt == null) "ذخیره شد" else "یادآوری تنظیم شد"
        refresh()
    }
    fun done(id: Long) { store.complete(id); refresh() }
    fun delete(id: Long) { store.delete(id); refresh() }
    fun setStatus(v: String) { status = v }
}
