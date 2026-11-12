package com.example.ecotechapp.data.model

enum class DispenserStatus {
    OK,           // Nivel adecuado (>= 60%)
    LOW_WATER,    // Advertencia: Nivel bajo (20-59%)
    EMPTY,        // Crítico: requiere cambio de bidón (< 20%)
    MAINTENANCE   // En mantenimiento
}

/**
 * Modelo de dispensador. Los campos distanciaCm, estadoBidon, requiereCambio
 * y bombaEstado se sincronizan 1:1 con los nodos que publica el ESP32 en
 * Firebase Realtime Database (dispensers/DISP-001).
 */
data class WaterDispenser(
    val id: String,
    val name: String,
    val location: String,
    var currentLevelPercent: Int, // 0 to 100
    val capacityLiters: Double = 20.0, // Garrafón estándar de 20 Litros
    var distanciaCm: Double = 0.0,      // 📡 distancia_cm (HC-SR04, escrita por ESP32)
    var estadoBidon: String = "Lleno",  // estado_bidon ("Lleno" | "Nivel Bajo" | "Vacío - Reemplazar")
    var requiereCambio: Boolean = false, // requiere_cambio (true si nivel < 20%)
    var bombaEstado: Boolean = false,    // bomba_estado (la app ESCRIBE, el ESP32 LEE)
    var isRealtime: Boolean = false,     // true cuando la lectura viene del ESP32 real
    var status: DispenserStatus = DispenserStatus.OK,
    var lastRefillTimestamp: Long = System.currentTimeMillis(),
    var assignedTechnician: String = "Técnico de Turno",
    val sensorId: String = "ESP32-WATER-${id.takeLast(4)}"
) {
    fun updateStatusFromLevel() {
        status = when {
            currentLevelPercent < THRESHOLD_CAMBIO -> DispenserStatus.EMPTY
            currentLevelPercent < THRESHOLD_LLENO -> DispenserStatus.LOW_WATER
            else -> DispenserStatus.OK
        }
        // Estado textual EXACTO al publicado por el firmware ESP32 (estado_bidon)
        estadoBidon = when {
            currentLevelPercent < THRESHOLD_CAMBIO -> "Vacío - Reemplazar"
            currentLevelPercent < THRESHOLD_LLENO -> "Nivel Bajo"
            else -> "Lleno"
        }
        requiereCambio = currentLevelPercent < THRESHOLD_CAMBIO
    }

    /** Nivel crítico (< 15%): el buzzer del ESP32 está sonando */
    val isCritico: Boolean
        get() = currentLevelPercent < THRESHOLD_CRITICO

    val litersRemaining: Double
        get() = (capacityLiters * currentLevelPercent) / 100.0

    companion object {
        /** < 20% → requiere cambio de bidón (umbral firmware) */
        const val THRESHOLD_CAMBIO = 20
        /** < 15% → crítico, buzzer local en el ESP32 */
        const val THRESHOLD_CRITICO = 15
        /** >= 60% → garrafón lleno */
        const val THRESHOLD_LLENO = 60
    }
}
