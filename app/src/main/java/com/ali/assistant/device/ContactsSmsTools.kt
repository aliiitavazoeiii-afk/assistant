package com.ali.assistant.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

class ContactsSmsTools(private val context: Context) {
    fun findPhone(name: String): Pair<String, String> {
        requirePermission(Manifest.permission.READ_CONTACTS)
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        ).use { c ->
            if (c != null && c.moveToFirst()) return c.getString(0) to c.getString(1)
        }
        throw IllegalArgumentException("Contact not found: $name")
    }

    fun callContact(name: String): JSONObject {
        requirePermission(Manifest.permission.CALL_PHONE)
        val (display, phone) = findPhone(name)
        context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(phone)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return JSONObject().put("ok", true).put("contact", display).put("phone", phone)
    }

    @Suppress("DEPRECATION")
    fun sendSms(nameOrNumber: String, message: String): JSONObject {
        requirePermission(Manifest.permission.SEND_SMS)
        val phone = if (nameOrNumber.any { it.isLetter() }) findPhone(nameOrNumber).second else nameOrNumber
        SmsManager.getDefault().sendTextMessage(phone, null, message, null, null)
        return JSONObject().put("ok", true).put("to", phone).put("message", message)
    }

    fun readSms(startIso: String?, endIso: String?, query: String?, limit: Int): JSONArray {
        requirePermission(Manifest.permission.READ_SMS)
        val start = startIso?.takeIf { it.isNotBlank() }?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
        val end = endIso?.takeIf { it.isNotBlank() }?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (start != null) { where += "${Telephony.Sms.DATE} >= ?"; args += start.toString() }
        if (end != null) { where += "${Telephony.Sms.DATE} <= ?"; args += end.toString() }
        if (!query.isNullOrBlank()) { where += "${Telephony.Sms.BODY} LIKE ?"; args += "%$query%" }
        val out = JSONArray()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            if (where.isEmpty()) null else where.joinToString(" AND "),
            if (args.isEmpty()) null else args.toTypedArray(),
            "${Telephony.Sms.DATE} DESC",
        ).use { c ->
            var n = 0
            while (c != null && c.moveToNext() && n < limit.coerceIn(1, 100)) {
                out.put(JSONObject()
                    .put("address", c.getString(0))
                    .put("body", c.getString(1))
                    .put("date", c.getLong(2))
                    .put("type", c.getInt(3)))
                n++
            }
        }
        return out
    }

    private fun requirePermission(permission: String) {
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Permission not granted: $permission")
        }
    }
}
