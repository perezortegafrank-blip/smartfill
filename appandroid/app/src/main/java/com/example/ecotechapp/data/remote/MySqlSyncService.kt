package com.example.ecotechapp.data.remote

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.ecotechapp.data.model.RefillLog
import com.example.ecotechapp.data.model.WaterAlert
import com.example.ecotechapp.data.model.WaterDispenser
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class MySqlSyncService(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mysql_config", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    var serverUrl: String
        get() = prefs.getString("server_url", "http://10.0.2.2/water_app_api") ?: "http://10.0.2.2/water_app_api"
        set(value) {
            prefs.edit().putString("server_url", value.trimEnd('/')).apply()
        }

    fun syncDispenserStatus(dispenser: WaterDispenser, onComplete: (Boolean, String) -> Unit) {
        val payload = mapOf(
            "action" to "sync_dispenser",
            "dispenser_id" to dispenser.id,
            "name" to dispenser.name,
            "location" to dispenser.location,
            "level_percent" to dispenser.currentLevelPercent,
            "status" to dispenser.status.name,
            "sensor_id" to dispenser.sensorId
        )

        postJson("$serverUrl/api.php", payload, onComplete)
    }

    fun syncAlert(alert: WaterAlert, onComplete: (Boolean, String) -> Unit) {
        val payload = mapOf(
            "action" to "create_alert",
            "alert_id" to alert.id,
            "dispenser_id" to alert.dispenserId,
            "dispenser_name" to alert.dispenserName,
            "location" to alert.location,
            "level_percent" to alert.levelPercent,
            "severity" to alert.severity.name,
            "timestamp" to alert.timestamp
        )

        postJson("$serverUrl/api.php", payload, onComplete)
    }

    fun syncRefillLog(log: RefillLog, onComplete: (Boolean, String) -> Unit) {
        val payload = mapOf(
            "action" to "log_refill",
            "log_id" to log.id,
            "dispenser_id" to log.dispenserId,
            "dispenser_name" to log.dispenserName,
            "location" to log.location,
            "technician_name" to log.technicianName,
            "notes" to log.notes,
            "timestamp" to log.timestamp
        )

        postJson("$serverUrl/api.php", payload, onComplete)
    }

    private fun postJson(url: String, data: Map<String, Any>, onComplete: (Boolean, String) -> Unit) {
        Thread {
            try {
                val json = gson.toJson(data)
                val body = json.toRequestBody(jsonMediaType)
                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val respBody = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        onComplete(true, "Conectado y sincronizado con MySQL ($response.code)")
                    } else {
                        onComplete(false, "Error MySQL Server HTTP ${response.code}: $respBody")
                    }
                }
            } catch (e: IOException) {
                Log.w("MySqlSyncService", "MySQL Host inaccesible: ${e.message}")
                onComplete(false, "Servidor MySQL no alcanzado (${e.localizedMessage}). Sincronización pendiente.")
            } catch (e: Exception) {
                Log.e("MySqlSyncService", "Error inesperado en sync MySQL", e)
                onComplete(false, "Error: ${e.localizedMessage}")
            }
        }.start()
    }
}
