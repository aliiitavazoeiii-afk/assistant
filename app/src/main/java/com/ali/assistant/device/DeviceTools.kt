package com.ali.assistant.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import androidx.core.content.ContextCompat
import org.json.JSONObject

class DeviceTools(private val context: Context) {
    fun openMaps(destination: String): JSONObject {
        val gmm = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}")).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(gmm) }.getOrElse { context.startActivity(fallback) }
        return JSONObject().put("ok", true).put("destination", destination)
    }

    fun openApp(name: String): JSONObject {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = pm.queryIntentActivities(launcherIntent, 0)
        val match = candidates.firstOrNull {
            it.loadLabel(pm).toString().contains(name, ignoreCase = true) ||
                it.activityInfo.packageName.contains(name, ignoreCase = true)
        } ?: throw IllegalArgumentException("Installed app not found: $name")
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: throw IllegalStateException("No launch intent for ${match.activityInfo.packageName}")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return JSONObject()
            .put("ok", true)
            .put("label", match.loadLabel(pm).toString())
            .put("package", match.activityInfo.packageName)
    }

    fun currentLocation(): JSONObject {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) throw SecurityException("Location permission not granted")
        val lm = context.getSystemService(LocationManager::class.java)
        val providers = lm.getProviders(true)
        val location = providers.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
            ?: throw IllegalStateException("No cached location available; open Maps/location once and retry")
        return JSONObject()
            .put("latitude", location.latitude)
            .put("longitude", location.longitude)
            .put("accuracyMeters", location.accuracy)
            .put("timestamp", location.time)
    }
}
