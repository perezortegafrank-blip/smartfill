# 🔧 SmartFill — Guía de Integración (ESP32 + Android + Firebase)

Sistema inteligente de monitoreo para dispensadores de agua: detecta cuando un
bidón (garrafón de 20 L) está por quedar vacío, envía la alerta en tiempo real a
**Firebase Realtime Database** y permite al operario cambiar el bidón antes del
agotamiento. El prototipo se simula en **Wokwi** y se controla desde la app
**Android (Kotlin)**.

---

## 1. Llaves JSON (contrato ESP32 ⇄ Android)

Nodo raíz: `dispensers/DISP-001`

| Llave | Tipo | Escritor | Descripción |
|---|---|---|---|
| `nivel_porcentaje` | Int (0-100) | ESP32 | Porcentaje de agua restante en el bidón |
| `distancia_cm` | Float | ESP32 | Distancia HC-SR04 hasta el nivel del líquido |
| `requiere_cambio` | Boolean | ESP32 | `true` si nivel < 20% → cambio de bidón |
| `estado_bidon` | String | ESP32 | `"Lleno"` (≥60%) · `"Nivel Bajo"` (20-59%) · `"Vacío - Reemplazar"` (<20%) |
| `bomba_estado` | Boolean | **App Android** | Control remoto: enciende/apaga la salida de agua (LED GPIO25) |
| `levelPercent` | Int | ESP32 | Llave legacy de la app (se mantiene sincronizada) |
| `sensor_id` | String | ESP32 | Identificador del dispositivo (`ESP32-W001`) |
| `id`, `name`, `location`, `status`, `lastRefill` | — | App (legacy) | Metadatos de la app existente |

Nodos auxiliares: `alerts/{ALT-XXXX}` (alertas rojas/amarillas) e `history/{LOG-XXXX}` (cambios de garrafón).

**Umbrales unificados (una sola fuente de verdad):**
- **≥ 60%** → 🟢 `"Lleno"` — sin alerta
- **20–59%** → 🟠 `"Nivel Bajo"` — sin cambio aún
- **< 20%** → 🔴 `"Vacío - Reemplazar"` + `requiere_cambio=true` (alerta en app)
- **< 15%** → 🚨 Crítico: alerta roja + **buzzer local** en el ESP32

---

## 2. Credenciales locales (ISO 27400 — nada real en el código fuente)

### ESP32 / PlatformIO (`wokwi_esp32/platformio.ini`)
Reemplaza los placeholders de `build_flags`:
```ini
build_flags =
    -D WIFI_SSID='"TU_WIFI_REAL"'
    -D WIFI_PASSWORD='"TU_PASSWORD_REAL"'
    -D FIREBASE_URL='"https://TU-PROYECTO-default-rtdb.firebaseio.com"'
    -D FIREBASE_API_KEY='"TU_WEB_API_KEY"'
```
> El Web API Key se obtiene en: **Firebase Console → ⚙️ Configuración del proyecto → General → API Key (Web)**.
> Compilar con: `pio run -e esp32doit-devkit-v1` · Monitorear con `pio device monitor`.

Alternativa Arduino IDE: copia `credentials_template.h` → `credentials.h`, pon tus datos y añade `#include "credentials.h"` al inicio de `sketch.ino`. `credentials.h` está gitignored.

### Wokwi
En el simulador usa `WIFI_SSID = "Wokwi-GUEST"` (default del código). Pega tu `FIREBASE_URL` y `FIREBASE_API_KEY` reales en los `#define` de `sketch.ino` (solo en tu copia local) o en `libraries.txt` + diagrama abriendo `sketch.ino` en wokwi.com.

### Android
1. **Firebase Console → Configuración del proyecto → añade app Android** con paquete `com.example.ecotechapp`.
2. Descarga el `google-services.json` real y **reemplaza** `appandroid/app/google-services.json` (el actual es una plantilla placeholder para que compile).
3. **Realtime Database** → crea la BD y despliega `firebase_rules.json` (raíz del repo) en la pestaña *Rules*.
4. **Authentication → Sign-in method → habilita "Anonymous"** (el ESP32 usa auth anónima; así se cumplen las reglas `auth != null`).

---

## 3. Circuito Wokwi (`wokwi_esp32/diagram.json`)

| Componente | Pin ESP32 | Pin componente | Nota |
|---|---|---|---|
| HC-SR04 **TRIG** | GPIO 5 (D5) | TRIG | Ubicado en la parte superior del bidón |
| HC-SR04 **ECHO** | GPIO 18 (D18) | ECHO | Divisor 1 kΩ/2 kΩ para proteger la entrada de 3.3 V |
| HC-SR04 VCC/GND | 3V3 / GND | VCC / GND | |
| **LED bomba** (azul) | GPIO 25 (D25) | Ánodo (C→GND) | Simula bomba/válvula controlada por `bomba_estado` |
| **Buzzer** | GPIO 26 (D26) | señal (otro pin→GND) | Sonido local cuando nivel < 15% |

**Calibración firmware** (`sketch.ino` / `src/main.cpp`): `DIST_LLENO_CM = 5.0` (100%) y `DIST_VACIO_CM = 30.0` (0%). En Wokwi, arrastra el objeto del sensor HC-SR04 para cambiar la distancia.

### Flujo de datos
```
ESP32 (HC-SR04) ──cada 5 s──▶ dispensers/DISP-001/{nivel_porcentaje, distancia_cm,
                                              requiere_cambio, estado_bidon, levelPercent}
                                     │
App Android ◀── ValueEventListener ───┤   (persistencia offline activa)
App Android ── Switch "Bomba" ──▶ dispensers/DISP-001/bomba_estado
                                     │
ESP32 ◀──────── stream()/listener ───┘  → LED GPIO25 ON/OFF + buzzer local
```

---

## 4. App Android — cambios implementados

| Archivo | Cambio |
|---|---|
| `app/build.gradle.kts` | Plugin `google-services` aplicado |
| `app/google-services.json` | Plantilla → **reemplazar por el real** |
| `MainActivity.kt` | `setPersistenceEnabled(true)` (alertas al recuperar Wi-Fi/4G) + `startRealtimeSync()` |
| `WaterRepository.kt` | `ValueEventListener` en `dispensers/DISP-001`, `keepSynced`, alertas por `requiereCambio`, `setBombaEstado()` (escribe `bomba_estado`), simulador desactivado para el dispensador con datos reales |
| `WaterDispenser.kt` | Umbrales `CAMBIO=20 / CRITICO=15 / LLENO=60`, campos `distanciaCm`, `estadoBidon`, `requiereCambio`, `bombaEstado` |
| `DashboardFragment` | Banner global rojo/amarillo + contador de garrafones que requieren cambio |
| `DispenserDetailFragment` + layout | Tarjeta de alerta **roja** (crítico <15%) / **amarilla** (15–19%), datos de distancia del sensor, `SwitchMaterial` de bomba → escribe `bomba_estado` |

---

## 5. Cómo probar el prototipo completo

1. **Firebase Console**: crea proyecto → Realtime Database (modo bloqueado) → pega `firebase_rules.json` → habilita *Anonymous* en Authentication.
2. **Wokwi**: abre `wokwi_esp32/sketch.ino` (o sube la carpeta), coloca tus `FIREBASE_URL`/`FIREBASE_API_KEY` y presiona ▶️. En el Serial verás `📏 12.5 cm → 70% (Lleno)`.
3. **Android Studio**: reemplaza `google-services.json` → compila e instala. En el Dashboard aparecerá DISP-001 en verde (`📡 ESP32 en vivo` en la barra de estado).
4. **Simula el consumo**: en Wokwi aleja el HC-SR04 (o cambia la distancia) → el nivel baja → cuando cruza 20% la app muestra la tarjeta de alerta y el contador rojo del Dashboard; bajo 15% suena el buzzer.
5. **Control remoto**: abre DISP-001 en la app → activa el switch "Bomba" → el LED azul del GPIO25 enciende en Wokwi.

### Sin credenciales todavía
La app funciona igualmente: `FirebaseSyncService` detecta la ausencia de conexión y queda en *modo local/simulado*; el switch de simulación del Dashboard permite probar las alertas sin hardware.
