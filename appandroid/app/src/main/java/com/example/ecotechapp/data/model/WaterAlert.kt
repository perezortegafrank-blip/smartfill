package com.example.ecotechapp.data.model

enum class AlertSeverity {
    WARNING,  // Requiere cambio de bidón, nivel 15-19% (tarjeta amarilla)
    CRITICAL  // Crítico: nivel < 15% (tarjeta roja, buzzer activo en el ESP32)
}

data class WaterAlert(
    val id: String,
    val dispenserId: String,
    val dispenserName: String,
    val location: String,
    val levelPercent: Int,
    val severity: AlertSeverity,
    val timestamp: Long = System.currentTimeMillis(),
    var isResolved: Boolean = false,
    var resolvedBy: String? = null,
    var resolvedTimestamp: Long? = null
)
