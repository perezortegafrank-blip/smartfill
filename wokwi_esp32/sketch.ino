/**
 * ══════════════════════════════════════════════════════════════════════════
 *  SmartFill — Monitoreo inteligente de dispensadores de agua (garrafón 20L)
 *  ESP32 + HC-SR04 + LED/Relé bomba (GPIO25) + Buzzer de alerta (GPIO26)
 *  Firebase Realtime Database — publicación cada 5 s + listener bomba_estado
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  Nodos publicados (dispensers/DISP-001):
 *    nivel_porcentaje : Int   0-100
 *    distancia_cm     : Float (distancia HC-SR04 hasta el nivel del agua)
 *    requiere_cambio  : Boolean (true si nivel < 20%)
 *    estado_bidon     : String ("Lleno" | "Nivel Bajo" | "Vacío - Reemplazar")
 *    bomba_estado     : Boolean (control remoto desde la app Android)
 *    levelPercent     : Int   (llave legacy de la app, mantenida en sync)
 *
 *  Lógica de estados (alineada con la app Android):
 *    nivel >= 60%      → "Lleno"
 *    20% <= nivel < 60% → "Nivel Bajo"
 *    nivel < 20%       → "Vacío - Reemplazar" (requiere_cambio = true)
 *    nivel < 15%       → buzzer local (alarma crítica)
 */

#include <WiFi.h>
#include <Firebase_ESP_Client.h>
#include "addons/TokenHelper.h"

// ══════════════════ 1. CREDENCIALES (placeholders ISO 27400) ══════════════════
// ⚠️ NUNCA quemar credenciales reales aquí.
// - PlatformIO: se inyectan vía build_flags en platformio.ini (sobrescriben estos defaults).
// - Arduino IDE / Wokwi: se usan los valores por defecto de abajo (red virtual Wokwi).
#ifndef WIFI_SSID
  #define WIFI_SSID "Wokwi-GUEST"   // Red virtual de Wokwi. Local: tu SSID real
#endif
#ifndef WIFI_PASSWORD
  #define WIFI_PASSWORD ""          // Wokwi no requiere password
#endif
#ifndef FIREBASE_URL
  #define FIREBASE_URL "https://TU-PROYECTO-default-rtdb.firebaseio.com"
#endif
#ifndef FIREBASE_API_KEY
  #define FIREBASE_API_KEY "TU_WEB_API_KEY_AQUI"
#endif

// ══════════════════ 2. HARDWARE ══════════════════
#define PIN_TRIG        5     // HC-SR04 Trigger  → GPIO 5  (D5)
#define PIN_ECHO        18    // HC-SR04 Echo     → GPIO 18 (D18, con divisor 1k/2k)
#define PIN_BOMBA       25    // LED / Relé bomba de agua   (GPIO 25, D25)
#define PIN_BUZZER      26    // Buzzer de alarma local     (GPIO 26, D26)

// ══════════════════ 3. CALIBRACIÓN Y LÓGICA ══════════════════
#define DIST_LLENO_CM   5.0f    // Distancia sensor→agua con garrafón LLENO
#define DIST_VACIO_CM   30.0f   // Distancia sensor→fondo con garrafón VACÍO
#define UMBRAL_CAMBIO   20      // <20%  → requiere cambio de bidón
#define UMBRAL_CRITICO  15      // <15%  → buzzer local (crítico)
#define NIVEL_LLENO     60      // >=60% → "Lleno"
#define INTERVALO_MS    5000UL  // Publicar en Firebase cada 5 s
#define NODO_DISPENSER  "dispensers/DISP-001"

// ══════════════════ 4. OBJETOS FIREBASE ══════════════════
FirebaseData fbdoPublish;
FirebaseData fbdoStream;
FirebaseAuth auth;
FirebaseConfig config;

// ══════════════════ 5. ESTADO ══════════════════
bool bombaEncendida = false;
bool buzzerOn = false;
int nivelActual = 100;
float distanciaActual = 0.0f;
unsigned long ultimaPublicacion = 0;
unsigned long tBuzzer = 0;

// ─────────── Prototipos (necesarios para .cpp de PlatformIO) ───────────
float medirDistanciaCm();
float medirDistanciaFiltrada();
int calcularNivel(float dist);
const char* estadoBidon(int nivel);
void publicarNivel(float dist, int nivel, bool requiere, const char* estado);
void aplicarBomba(bool estado);
void conectarWiFi();
void inicializarFirebase();

// ══════════════════ SENSOR ULTRASÓNICO HC-SR04 ══════════════════
float medirDistanciaCm() {
  digitalWrite(PIN_TRIG, LOW);
  delayMicroseconds(2);
  digitalWrite(PIN_TRIG, HIGH);
  delayMicroseconds(10);        // Pulso de disparo de 10 µs
  digitalWrite(PIN_TRIG, LOW);
  // Timeout 30 ms ≈ 5 m de alcance máximo
  unsigned long duracion = pulseIn(PIN_ECHO, HIGH, 30000UL);
  if (duracion == 0) return -1.0f;                 // Lectura inválida
  return (duracion * 0.0343f) / 2.0f;              // Velocidad del sonido /2
}

// Mediana de 3 lecturas para filtrar ruido/rebotes
float medirDistanciaFiltrada() {
  float m[3];
  int validas = 0;
  for (int i = 0; i < 3; i++) {
    m[i] = medirDistanciaCm();
    if (m[i] > 0) validas++;
    delay(50);
  }
  if (validas == 0) return -1.0f;
  // Ordenar los 3 valores y devolver el central (mediana)
  for (int i = 0; i < 2; i++) {
    for (int j = i + 1; j < 3; j++) {
      if (m[j] < m[i]) { float t = m[i]; m[i] = m[j]; m[j] = t; }
    }
  }
  return m[1];
}

// ══════════════════ CONVERSIÓN DISTANCIA → PORCENTAJE ══════════════════
// Regla de tres lineal: DIST_LLENO_CM → 100%, DIST_VACIO_CM → 0%
int calcularNivel(float dist) {
  if (dist <= 0) return -1;
  dist = constrain(dist, DIST_LLENO_CM, DIST_VACIO_CM);
  float nivel = ((DIST_VACIO_CM - dist) / (DIST_VACIO_CM - DIST_LLENO_CM)) * 100.0f;
  return (int)nivel;
}

// ══════════════════ ESTADO DEL BIDÓN (coherente con la app) ══════════════════
const char* estadoBidon(int nivel) {
  if (nivel < UMBRAL_CAMBIO)  return "Vacío - Reemplazar";
  if (nivel < NIVEL_LLENO)    return "Nivel Bajo";
  return "Lleno";
}

// ══════════════════ PUBLICACIÓN EN FIREBASE RTDB ══════════════════
// Usa updateAsync (PATCH) para no sobrescribir llaves escritas por otros clientes
void publicarNivel(float dist, int nivel, bool requiere, const char* estado) {
  FirebaseJson json;
  json.set("nivel_porcentaje", nivel);      // Int 0-100
  json.set("levelPercent", nivel);          // llave legacy de la app Android
  json.set("distancia_cm", dist);           // Float
  json.set("requiere_cambio", requiere);    // Boolean: nivel < 20%
  json.set("estado_bidon", estado);         // String
  json.set("sensor_id", "ESP32-W001");
  json.set("timestamp_ms", (int)(millis() % 1000000));

  if (Firebase.RTDB.updateNodeAsync(&fbdoPublish, NODO_DISPENSER, &json)) {
    Serial.println("✅ Firebase RTDB actualizado");
  } else {
    Serial.println("❌ Firebase update: " + fbdoPublish.errorReason());
  }
}

// ══════════════════ CONTROL REMOTO DE LA BOMBA ══════════════════
void aplicarBomba(bool estado) {
  bombaEncendida = estado;
  digitalWrite(PIN_BOMBA, estado ? HIGH : LOW);
  Serial.printf("🚰 Bomba/válvula: %s (orden desde app Android)\n", estado ? "ENCENDIDA" : "APAGADA");
}

// ══════════════════ CONEXIÓN Wi-Fi ══════════════════
void conectarWiFi() {
  Serial.printf("📶 Conectando a Wi-Fi '%s'", WIFI_SSID);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  unsigned long t0 = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - t0 < 20000) {
    delay(300);
    Serial.print(".");
  }
  if (WiFi.status() == WL_CONNECTED) {
    Serial.printf("\n✅ Wi-Fi OK — IP: %s\n", WiFi.localIP().toString().c_str());
  } else {
    Serial.println("\n⚠️ Wi-Fi no conectado (continúa el intento en background)");
  }
}

// ══════════════════ FIREBASE: AUTH ANÓNIMA + LISTENER ══════════════════
// Auth anónima (habilitar "Anonymous" en Firebase Console → Authentication)
// Cumple con las reglas "auth != null" de firebase_rules.json
void inicializarFirebase() {
  config.api_key = FIREBASE_API_KEY;
  config.database_url = FIREBASE_URL;
  config.token_status_callback = tokenStatusCallback;  // addon TokenHelper.h

  if (Firebase.signUp(&config, &auth, "", "")) {
    Serial.println("✅ Autenticación anónima OK");
  } else {
    Serial.printf("⚠️ signUp falló: %s (revisa Anonymous en Firebase Console)\n",
                  config.signer.signupError.message.c_str());
  }
  Firebase.begin(&config, &auth);
  Firebase.reconnectWiFi(true);
  fbdoPublish.setResponseSize(2048);

  // Listener bidireccional: la app Android escribe bomba_estado
  if (!Firebase.RTDB.beginStream(&fbdoStream, NODO_DISPENSER "/bomba_estado")) {
    Serial.println("❌ stream bomba_estado: " + fbdoStream.errorReason());
  } else {
    Serial.println("👂 Escuchando bomba_estado en tiempo real...");
  }

  // Estado inicial de la bomba (por si la app ya la encendió)
  if (Firebase.RTDB.getBool(&fbdoPublish, NODO_DISPENSER "/bomba_estado")) {
    if (fbdoPublish.dataType() == "boolean") {
      aplicarBomba(fbdoPublish.boolData());
    }
  }
}

// ══════════════════ SETUP ══════════════════
void setup() {
  Serial.begin(115200);
  pinMode(PIN_TRIG, OUTPUT);
  pinMode(PIN_ECHO, INPUT);
  pinMode(PIN_BOMBA, OUTPUT);
  pinMode(PIN_BUZZER, OUTPUT);
  digitalWrite(PIN_BOMBA, LOW);
  digitalWrite(PIN_BUZZER, LOW);

  conectarWiFi();
  inicializarFirebase();
}

// ══════════════════ LOOP (no bloqueante) ══════════════════
void loop() {
  unsigned long now = millis();

  // ── Listener en tiempo real: bomba_estado desde la app Android ──
  if (Firebase.RTDB.readStream(&fbdoStream)) {
    if (fbdoStream.streamTimeout()) {
      Serial.println("⏳ stream timeout, reconectando...");
      Firebase.RTDB.beginStream(&fbdoStream, NODO_DISPENSER "/bomba_estado");
    }
    if (fbdoStream.streamAvailable()) {
      if (fbdoStream.dataType() == "boolean") {
        aplicarBomba(fbdoStream.boolData());
      }
    }
  } else {
    Serial.println("⚠️ readStream: " + fbdoStream.errorReason());
    Firebase.RTDB.beginStream(&fbdoStream, NODO_DISPENSER "/bomba_estado");
    delay(1000);
  }

  // ── Publicación periódica del nivel de agua ──
  if (now - ultimaPublicacion >= INTERVALO_MS) {
    ultimaPublicacion = now;
    float dist = medirDistanciaFiltrada();
    if (dist > 0) {
      int nivel = calcularNivel(dist);
      if (nivel >= 0) {
        nivelActual = nivel;
        distanciaActual = dist;
        bool requiere = nivel < UMBRAL_CAMBIO;
        publicarNivel(dist, nivel, requiere, estadoBidon(nivel));
        Serial.printf("📏 %.1f cm → %d%% (%s)%s\n",
                      dist, nivel, estadoBidon(nivel),
                      requiere ? "  ⚠️ CAMBIO DE BIDÓN" : "");
      }
    } else {
      Serial.println("⚠️ Lectura HC-SR04 inválida");
    }
  }

  // ── Buzzer local: suena si nivel < 15% (crítico) ──
  if (nivelActual < UMBRAL_CRITICO) {
    if (now - tBuzzer >= 400) {
      tBuzzer = now;
      buzzerOn = !buzzerOn;
      digitalWrite(PIN_BUZZER, buzzerOn ? HIGH : LOW);
    }
  } else if (buzzerOn) {
    buzzerOn = false;
    digitalWrite(PIN_BUZZER, LOW);
  }
}
