package com.example.ecotechapp.data.repository

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.ecotechapp.data.model.*
import com.example.ecotechapp.data.remote.FirebaseSyncService
import com.example.ecotechapp.data.remote.MySqlSyncService
import com.example.ecotechapp.util.NotificationHelper
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.util.UUID

class WaterRepository private constructor(private val context: Context) {

    private val firebaseSync = FirebaseSyncService(context)
    val mysqlSync = MySqlSyncService(context)
    private val notificationHelper = NotificationHelper(context)

    /** Dispensador monitoreado por el prototipo ESP32 en Wokwi */
    private val esp32DispenserId = "DISP-001"

    /** RTDB en tiempo real (requiere google-services.json + persistencia offline activada) */
    private val firebaseDb: FirebaseDatabase by lazy { FirebaseDatabase.getInstance() }
    private var realtimeSyncStarted = false

    private val _dispensers = MutableLiveData<List<WaterDispenser>>(emptyList())
    val dispensers: LiveData<List<WaterDispenser>> get() = _dispensers

    private val _alerts = MutableLiveData<List<WaterAlert>>(emptyList())
    val alerts: LiveData<List<WaterAlert>> get() = _alerts

    private val _refillLogs = MutableLiveData<List<RefillLog>>(emptyList())
    val refillLogs: LiveData<List<RefillLog>> get() = _refillLogs

    private val _syncStatusMessage = MutableLiveData<String>("Sistema listo - Conectado a Firebase & MySQL")
    val syncStatusMessage: LiveData<String> get() = _syncStatusMessage

    private val handler = Handler(Looper.getMainLooper())
    private var isSimulationRunning = false

    private val simulationRunnable = object : Runnable {
        override fun run() {
            if (isSimulationRunning) {
                simulateSensorReading()
                handler.postDelayed(this, 8000) // Simular consumo cada 8 segundos
            }
        }
    }

    init {
        loadInitialData()
    }

    companion object {
        @Volatile
        private var INSTANCE: WaterRepository? = null

        fun getInstance(context: Context): WaterRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = WaterRepository(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    private fun loadInitialData() {
        val initialList = mutableListOf(
            WaterDispenser(
                id = "DISP-001",
                name = "Dispensador Principal",
                location = "Piso 1 - Recepción Central",
                currentLevelPercent = 85,
                sensorId = "ESP32-W001"
            ),
            WaterDispenser(
                id = "DISP-002",
                name = "Dispensador Cafetería",
                location = "Piso 2 - Sala de Descanso",
                currentLevelPercent = 25, // Advertencia: nivel bajo
                sensorId = "ESP32-W002"
            ),
            WaterDispenser(
                id = "DISP-003",
                name = "Dispensador Operaciones",
                location = "Planta Baja - Área Industrial",
                currentLevelPercent = 8,  // Crítico: Garrafón vacío!
                sensorId = "ESP32-W003"
            ),
            WaterDispenser(
                id = "DISP-004",
                name = "Dispensador Gerencia",
                location = "Piso 3 - Salas VIP",
                currentLevelPercent = 95,
                sensorId = "ESP32-W004"
            )
        )

        initialList.forEach { it.updateStatusFromLevel() }
        _dispensers.value = initialList

        // Generar alertas iniciales para dispensadores que requieren cambio de bidón
        val currentAlerts = mutableListOf<WaterAlert>()
        initialList.forEach { dispenser ->
            checkAndGenerateAlert(dispenser, currentAlerts)
        }
        _alerts.value = currentAlerts
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SINCRONIZACIÓN EN TIEMPO REAL CON EL ESP32 (Firebase RTDB)
    //  Escucha dispensers/DISP-001 → nivel_porcentaje, distancia_cm,
    //  requiere_cambio, estado_bidon, bomba_estado
    // ════════════════════════════════════════════════════════════════════════
    fun startRealtimeSync() {
        if (realtimeSyncStarted) return
        realtimeSyncStarted = true

        val ref = firebaseDb.getReference("dispensers").child(esp32DispenserId)
        ref.keepSynced(true) // Copia local persistente para modo offline

        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val nivel = snapshot.child("nivel_porcentaje").value
                    ?.toString()?.toIntOrNull()
                    ?: snapshot.child("levelPercent").value
                        ?.toString()?.toIntOrNull()
                    ?: return // Sin datos del ESP32 todavía

                val distancia = snapshot.child("distancia_cm").value
                    ?.toString()?.toDoubleOrNull() ?: 0.0
                val estado = snapshot.child("estado_bidon").value as? String
                val requiere = snapshot.child("requiere_cambio").value as? Boolean
                val bomba = snapshot.child("bomba_estado").value as? Boolean

                applyRealtimeReading(nivel, distancia, estado, requiere, bomba)
            }

            override fun onCancelled(error: DatabaseError) {
                _syncStatusMessage.postValue("⚠️ Listener Firebase cancelado: ${error.message}")
            }
        })

        _syncStatusMessage.value = "📡 Sincronización en tiempo real ESP32 activa ($esp32DispenserId)"
    }

    /** Aplica una lectura real del sensor HC-SR04 recibida desde Firebase */
    private fun applyRealtimeReading(
        nivel: Int,
        distanciaCm: Double,
        estadoBidon: String?,
        requiereCambio: Boolean?,
        bombaEstado: Boolean?
    ) {
        val currentList = _dispensers.value?.toMutableList() ?: return
        val index = currentList.indexOfFirst { it.id == esp32DispenserId }
        if (index == -1) return

        val dispenser = currentList[index]
        val previousStatus = dispenser.status

        dispenser.isRealtime = true
        dispenser.currentLevelPercent = nivel.coerceIn(0, 100)
        if (distanciaCm > 0) dispenser.distanciaCm = distanciaCm
        if (estadoBidon != null && estadoBidon.isNotBlank()) dispenser.estadoBidon = estadoBidon
        if (requiereCambio != null) dispenser.requiereCambio = requiereCambio
        if (bombaEstado != null) dispenser.bombaEstado = bombaEstado
        dispenser.updateStatusFromLevel()

        currentList[index] = dispenser
        _dispensers.value = currentList

        _syncStatusMessage.postValue(
            "📡 ESP32 en vivo: ${dispenser.estadoBidon} (${dispenser.currentLevelPercent}%) · ${dispenser.distanciaCm} cm"
        )

        // Alerta roja/amarilla cuando se requiere cambio de bidón (sin duplicados)
        val currentAlerts = _alerts.value?.toMutableList() ?: mutableListOf()
        if (dispenser.requiereCambio && previousStatus != dispenser.status) {
            checkAndGenerateAlert(dispenser, currentAlerts)
            _alerts.value = currentAlerts
        }

        // Auto-resolver alertas cuando el garrafón fue reemplazado (nivel recuperado)
        if (!dispenser.requiereCambio) {
            var changed = false
            currentAlerts.forEach { alert ->
                if (alert.dispenserId == dispenser.id && !alert.isResolved) {
                    alert.isResolved = true
                    alert.resolvedBy = "ESP32 (nivel restaurado)"
                    alert.resolvedTimestamp = System.currentTimeMillis()
                    changed = true
                }
            }
            if (changed) _alerts.value = currentAlerts
        }
    }

    /** Control remoto de la bomba: la app escribe bomba_estado y el ESP32 reacciona */
    fun setBombaEstado(dispenserId: String, estado: Boolean) {
        val currentList = _dispensers.value?.toMutableList() ?: return
        val index = currentList.indexOfFirst { it.id == dispenserId }
        if (index != -1) {
            currentList[index].bombaEstado = estado
            _dispensers.value = currentList
        }

        firebaseDb.getReference("dispensers").child(dispenserId).child("bomba_estado")
            .setValue(estado)
            .addOnSuccessListener {
                _syncStatusMessage.postValue("🚰 Bomba ${if (estado) "ENCENDIDA" else "APAGADA"} → orden enviada al ESP32")
            }
            .addOnFailureListener {
                _syncStatusMessage.postValue("❌ Error al escribir bomba_estado: ${it.message}")
            }
    }

    fun startSensorSimulation() {
        if (!isSimulationRunning) {
            isSimulationRunning = true
            handler.post(simulationRunnable)
            _syncStatusMessage.value = "Simulación de Sensores ESP32 ACTIVADA (Lecturas cada 8s)"
        }
    }

    fun stopSensorSimulation() {
        isSimulationRunning = false
        handler.removeCallbacks(simulationRunnable)
        _syncStatusMessage.value = "Simulación de Sensores PAUSADA"
    }

    fun isSimulating(): Boolean = isSimulationRunning

    fun updateDispenserLevel(dispenserId: String, newLevel: Int) {
        val currentList = _dispensers.value?.toMutableList() ?: return
        val index = currentList.indexOfFirst { it.id == dispenserId }
        if (index != -1) {
            val dispenser = currentList[index]
            val previousStatus = dispenser.status
            dispenser.currentLevelPercent = newLevel.coerceIn(0, 100)
            dispenser.updateStatusFromLevel()

            currentList[index] = dispenser
            _dispensers.value = currentList

            // Sincronización en segundo plano con Firebase y MySQL
            syncDispenserData(dispenser)

            // Verificar si la nueva lectura requiere lanzar una alerta
            val currentAlertsList = _alerts.value?.toMutableList() ?: mutableListOf()
            if (dispenser.requiereCambio && previousStatus != dispenser.status) {
                checkAndGenerateAlert(dispenser, currentAlertsList)
                _alerts.value = currentAlertsList
            }
        }
    }

    private fun simulateSensorReading() {
        val currentList = _dispensers.value?.toMutableList() ?: return
        if (currentList.isEmpty()) return

        // Solo simular dispensadores SIN datos reales del ESP32
        val candidates = currentList.filter { !it.isRealtime && it.currentLevelPercent > 0 }
        if (candidates.isEmpty()) return

        val dispenser = candidates.random()
        val drop = (3..12).random()
        val newLevel = (dispenser.currentLevelPercent - drop).coerceAtLeast(0)
        updateDispenserLevel(dispenser.id, newLevel)
    }

    private fun checkAndGenerateAlert(dispenser: WaterDispenser, alertList: MutableList<WaterAlert>) {
        val existingAlert = alertList.find { it.dispenserId == dispenser.id && !it.isResolved }

        // Se requiere cambio de bidón (< 20%)
        if (dispenser.requiereCambio) {
            // Rojo crítico si < 15% (buzzer activo en el ESP32), amarillo si 15-19%
            val severity = if (dispenser.isCritico) AlertSeverity.CRITICAL else AlertSeverity.WARNING

            if (existingAlert == null) {
                val newAlert = WaterAlert(
                    id = "ALT-" + UUID.randomUUID().toString().take(6).uppercase(),
                    dispenserId = dispenser.id,
                    dispenserName = dispenser.name,
                    location = dispenser.location,
                    levelPercent = dispenser.currentLevelPercent,
                    severity = severity
                )
                alertList.add(0, newAlert)

                // Disparar Notificación Push del Sistema
                notificationHelper.sendAlertNotification(newAlert)

                // Sincronizar Alerta con Firebase y MySQL
                firebaseSync.syncAlertToFirebase(newAlert) { _, _ -> }
                mysqlSync.syncAlert(newAlert) { _, _ -> }
            }
        }
    }

    fun refillDispenser(dispenserId: String, technicianName: String = "Técnico de Turno", notes: String = "Garrafón 20L Reemplazado"): Boolean {
        val currentList = _dispensers.value?.toMutableList() ?: return false
        val index = currentList.indexOfFirst { it.id == dispenserId }
        if (index == -1) return false

        val dispenser = currentList[index]
        dispenser.currentLevelPercent = 100
        dispenser.lastRefillTimestamp = System.currentTimeMillis()
        dispenser.updateStatusFromLevel()
        currentList[index] = dispenser
        _dispensers.value = currentList

        // Marcar alertas previas como resueltas
        val currentAlerts = _alerts.value?.toMutableList() ?: mutableListOf()
        currentAlerts.forEach { alert ->
            if (alert.dispenserId == dispenserId && !alert.isResolved) {
                alert.isResolved = true
                alert.resolvedBy = technicianName
                alert.resolvedTimestamp = System.currentTimeMillis()
            }
        }
        _alerts.value = currentAlerts

        // Crear registro en el Historial de Recargas
        val refillLog = RefillLog(
            id = "LOG-" + UUID.randomUUID().toString().take(6).uppercase(),
            dispenserId = dispenser.id,
            dispenserName = dispenser.name,
            location = dispenser.location,
            technicianName = technicianName,
            notes = notes
        )

        val currentLogs = _refillLogs.value?.toMutableList() ?: mutableListOf()
        currentLogs.add(0, refillLog)
        _refillLogs.value = currentLogs

        // Sincronizar recarga con Firebase & MySQL
        syncDispenserData(dispenser)
        mysqlSync.syncRefillLog(refillLog) { success, msg ->
            if (success) {
                refillLog.syncedToMySql = true
            }
            _syncStatusMessage.postValue("Recarga registrada. MySQL Status: $msg")
        }

        return true
    }

    private fun syncDispenserData(dispenser: WaterDispenser) {
        // El ESP32 es la fuente de verdad para dispensadores reales:
        // no reescribir sus nodos desde la app (evita bucles de sincronización)
        if (!dispenser.isRealtime) {
            firebaseSync.syncDispenserToFirebase(dispenser) { _, _ -> }
        }
        mysqlSync.syncDispenserStatus(dispenser) { success, msg ->
            _syncStatusMessage.postValue(
                if (success) "Sincronizado con MySQL exitosamente"
                else "Sync MySQL: $msg"
            )
        }
    }

    fun getDispenserById(id: String): WaterDispenser? {
        return _dispensers.value?.find { it.id == id }
    }
}
