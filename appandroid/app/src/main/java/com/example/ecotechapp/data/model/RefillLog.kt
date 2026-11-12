package com.example.ecotechapp.data.model

data class RefillLog(
    val id: String,
    val dispenserId: String,
    val dispenserName: String,
    val location: String,
    val technicianName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val notes: String = "Vidón de agua de 20L reemplazado exitosamente",
    var syncedToFirebase: Boolean = true,
    var syncedToMySql: Boolean = false
)
