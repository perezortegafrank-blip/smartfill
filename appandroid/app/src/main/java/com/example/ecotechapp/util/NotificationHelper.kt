package com.example.ecotechapp.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.ecotechapp.MainActivity
import com.example.ecotechapp.R
import com.example.ecotechapp.data.model.WaterAlert
import com.example.ecotechapp.data.model.AlertSeverity

class NotificationHelper(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_ID = "water_dispenser_alerts"
        const val CHANNEL_NAME = "Alertas de Dispensadores de Agua"
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones urgentes cuando un garrafón de agua necesita reemplazo"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun sendAlertNotification(alert: WaterAlert) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            alert.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (alert.severity == AlertSeverity.CRITICAL) {
            "🚨 ¡GARRAFÓN VACÍO! - ${alert.dispenserName}"
        } else {
            "⚠️ Nivel Bajo de Agua - ${alert.dispenserName}"
        }

        val message = "Ubicación: ${alert.location}. Nivel actual: ${alert.levelPercent}%. Se requiere cambio manual de garrafón."

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        notificationManager.notify(alert.id.hashCode(), builder.build())
    }
}
