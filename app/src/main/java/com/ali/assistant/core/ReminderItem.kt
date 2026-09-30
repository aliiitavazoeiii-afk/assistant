package com.ali.assistant.core

data class ReminderItem(
    val id: Long,
    val text: String,
    val createdAt: Long,
    val remindAt: Long?,
    val done: Boolean
)
