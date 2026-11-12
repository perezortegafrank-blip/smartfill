/**
 * ==========================================================================
 *  SmartFill - Monitoreo inteligente de dispensadores de agua (garrafon 20L)
 *  ESP32 + HC-SR04 + LED/Rele bomba (GPIO25) + Buzzer de alerta (GPIO26)
 *  Firebase Realtime Database - publicacion cada 5 s + listener bomba_estado
 * ==========================================================================
 *  Credenciales: src/../credentials.h (NO versionado). Por defecto usa la
 *  red virtual de Wokwi; con credenciales reales envia a Firebase sin
 *  modificar la logica. Si Wi-Fi/Firebase fallan (p. ej. Wokwi sin salida
 *  a internet), el sistema continua en modo offline: sensor HC-SR04,
 *  LED bomba y buzzer siguen funcionando por consola Serial.
 */

#include <WiFi.h>
#include <Firebase_ESP_Client.h>
#include "addons/TokenHelper.h"
#include "credentials.h"

// ================== 1. CREDENCIALES (desde credentials.h) ==================
#ifndef WIFI_SSID
  #define WIFI_SSID "Wokwi-GUEST"
#endif
#ifndef WIFI_PASSWORD
  #define WIFI_PASSWORD ""
#endif
#ifndef DATABASE_URL
  #define DATABASE_URL FIREBASE_HOST
#endif
#ifndef FIREBASE_API_KEY
  #define FIREBASE_API_KEY "TU_WEB_API_KEY_AQUI"
#endif

// ================== 2. HARDWARE ==================
#define PIN_TRIG        5     // HC-SR04 Trigger   GPIO 5  (D5)
#define PIN_ECHO        18    // HC-SR04 Echo      GPIO 18 (D18, con divisor 1k/2k)
#define PIN_BOMBA       25    // LED / Rele bomba de agua   (GPIO 25, D25)
#define PIN_BUZZER      26    // Buzzer de alarma local     (GPIO 26, D26)

// ================== 3. CALIBRACION Y LOGICA ==================
#define DIST_LLENO_CM   5.0f    // Distancia sensor->agua con garrafon LLENO
#define DIST_VACIO_CM   30.0f   // Distancia sensor->fondo con garrafon VACIO
#define UMBRAL_CAMBIO   20      // <20%   requiere cambio de bidon
#define UMBRAL_CRITICO  15      // <15%   buzzer local (critico)
#define NIVEL_LLENO     60      // >=60%  "Lleno"
#define INTERVALO_MS    5000UL  // Publicar en Firebase cada 5 s
#define NODO_DISPENSER  "dispensers/DISP-001"

// ================== 4. OBJETOS FIREBASE ==================
FirebaseData fbdoPublish;
FirebaseData fbdoStream;
FirebaseAuth auth;
FirebaseConfig config;

// ================== 5. ESTADO ==================
bool bombaEncendida = false;
bool buzzerOn = false;
int nivelActual = 100;
float distanciaActual = 0.0f;
unsigned long ultimaPublicacion = 0;
unsigned long tBuzzer = 0;

// -- Estado de conectividad (modo offline no bloqueante) --
bool wifiOk = false;
bool firebaseReady = false;
unsigned long tReintentoWifi = 0;
unsigned long tReintentoFirebase = 0;
#define WIFI_TIMEOUT_MS      10000UL  // Timeout corto de conexion Wi-Fi
#define FB_SERVER_TIMEOUT_MS 10000UL  // Timeout de signUp/begin (evita bloqueo infinito)
#define REINTENTO_WIFI_MS    10000UL  // Reintento de Wi-Fi cada 10 s
#define REINTENTO_FIREBASE_MS 30000UL // Reintento de Firebase cada 30 s
bool credencialesOk = false;

// ================== VALIDACION DE CREDENCIALES ==================
// Detecta placeholders de credentials.h ("TU_...", "...AQUI", "placeholder",
// vacio) para no pasarlos a la libreria Firebase (provoca panic/assert).
bool credencialesPlaceholder(const char* valor) {
  if (valor == nullptr || strlen(valor) == 0) return true;
  String s = valor;
  s.toUpperCase();
  return s.indexOf("TU_") >= 0 || s.indexOf("AQUI") >= 0 ||
         s.indexOf("PLACEHOLDER") >= 0;
}

// ----------- Prototipos -----------
float medirDistanciaCm();
float medirDistanciaFiltrada();
int calcularNivel(float dist);
const char* estadoBidon(int nivel);
void publicarNivel(float dist, int nivel, bool requiere, const char* estado);
void aplicarBomba(bool estado);
bool conectarWiFi();
bool inicializarFirebase();

// ================== SENSOR ULTRASONICO HC-SR04 ==================
float medirDistanciaCm() {
  digitalWrite(PIN_TRIG, LOW);
  delayMicroseconds(2);
  digitalWrite(PIN_TRIG, HIGH);
  delayMicroseconds(10);        // Pulso de disparo de 10 us
  digitalWrite(PIN_TRIG, LOW);
  // Timeout 30 ms ~ 5 m de alcance maximo
  unsigned long duracion = pulseIn(PIN_ECHO, HIGH, 30000UL);
  if (duracion == 0) return -1.0f;                 // Lectura invalida
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

// ================== CONVERSION DISTANCIA -> PORCENTAJE ==================
// Regla de tres lineal: DIST_LLENO_CM -> 100%, DIST_VACIO_CM -> 0%
int calcularNivel(float dist) {
  if (dist <= 0) return -1;
  dist = constrain(dist, DIST_LLENO_CM, DIST_VACIO_CM);
  float nivel = ((DIST_VACIO_CM - dist) / (DIST_VACIO_CM - DIST_LLENO_CM)) * 100.0f;
  return (int)nivel;
}

// ================== ESTADO DEL BIDON (coherente con la app) ==================
const char* estadoBidon(int nivel) {
  if (nivel < UMBRAL_CAMBIO)  return "Vacio - Reemplazar";
  if (nivel < NIVEL_LLENO)    return "Nivel Bajo";
  return "Lleno";
}

// ================== PUBLICACION EN FIREBASE RTDB ==================
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
    Serial.println("Firebase RTDB actualizado");
  } else {
    Serial.println("Firebase update: " + fbdoPublish.errorReason());
  }
}

// ================== CONTROL REMOTO DE LA BOMBA ==================
void aplicarBomba(bool estado) {
  bombaEncendida = estado;
  digitalWrite(PIN_BOMBA, estado ? HIGH : LOW);
  Serial.printf("Bomba/valvula: %s (orden desde app Android)\n", estado ? "ENCENDIDA" : "APAGADA");
}

// ================== CONEXION Wi-Fi (timeout corto, no bloqueante) ==================
bool conectarWiFi() {
  Serial.printf("Conectando a Wi-Fi '%s'", WIFI_SSID);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  unsigned long t0 = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - t0 < WIFI_TIMEOUT_MS) {
    delay(300);
    Serial.print(".");
  }
  if (WiFi.status() == WL_CONNECTED) {
    wifiOk = true;
    Serial.printf("\nWi-Fi OK - IP: %s\n", WiFi.localIP().toString().c_str());
    return true;
  }
  wifiOk = false;
  Serial.println("\nWi-Fi no conectado (se reintentara sin bloquear el loop)");
  return false;
}

// ================== FIREBASE: AUTH ANONIMA + LISTENER ==================
// Auth anonima (habilitar "Anonymous" en Firebase Console -> Authentication)
// Cumple con las reglas "auth != null" de firebase_rules.json
// Devuelve false si falla; el loop reintentara cada REINTENTO_FIREBASE_MS.
bool inicializarFirebase() {
  firebaseReady = false;

  // Placeholder detectado -> NO tocar la libreria Firebase (panic/assert)
  if (credencialesPlaceholder(FIREBASE_API_KEY) || credencialesPlaceholder(DATABASE_URL)) {
    credencialesOk = false;
    static bool advertido = false;
    if (!advertido) {
      advertido = true;
      Serial.println("credentials.h contiene placeholders - Firebase deshabilitado (modo offline local)");
    }
    return false;
  }
  credencialesOk = true;

  config.api_key = FIREBASE_API_KEY;
  config.database_url = DATABASE_URL;
  config.token_status_callback = tokenStatusCallback;  // addon TokenHelper.h
  // Timeout de red para signUp/begin: evita quedarse bloqueado sin internet
  config.timeout.serverResponse = FB_SERVER_TIMEOUT_MS;

  if (!Firebase.signUp(&config, &auth, "", "")) {
    Serial.printf("signUp fallo: %s (continua en modo offline; revisa Anonymous en Firebase Console)\n",
                  config.signer.signupError.message.c_str());
    return false;
  }
  Serial.println("Autenticacion anonima OK");

  // Evitar desbordamiento de memoria durante la negociacion SSL/TLS
  // (crash "assert failed" en Wokwi): sin validacion de certificado y
  // con buffer BSSL reducido, ANTES de Firebase.begin().
  config.cert.data = nullptr;                  // desactiva validacion de certificado
  fbdoPublish.setBSSLBufferSize(4096, 1024);   // RX 4 KB / TX 1 KB
  fbdoStream.setBSSLBufferSize(4096, 1024);    // RX 4 KB / TX 1 KB

  Firebase.begin(&config, &auth);
  Firebase.reconnectWiFi(true);
  fbdoPublish.setResponseSize(2048);

  // Listener bidireccional: la app Android escribe bomba_estado
  if (!Firebase.RTDB.beginStream(&fbdoStream, NODO_DISPENSER "/bomba_estado")) {
    Serial.println("stream bomba_estado: " + fbdoStream.errorReason());
    return false;
  }
  Serial.println("Escuchando bomba_estado en tiempo real...");

  // Estado inicial de la bomba (por si la app ya la encendio)
  if (Firebase.RTDB.getBool(&fbdoPublish, NODO_DISPENSER "/bomba_estado")) {
    if (fbdoPublish.dataType() == "boolean") {
      aplicarBomba(fbdoPublish.boolData());
    }
  }
  firebaseReady = true;
  return true;
}

// ================== SETUP (nunca se bloquea si falla la nube) ==================
void setup() {
  Serial.begin(115200);
  pinMode(PIN_TRIG, OUTPUT);
  pinMode(PIN_ECHO, INPUT);
  pinMode(PIN_BOMBA, OUTPUT);
  pinMode(PIN_BUZZER, OUTPUT);
  digitalWrite(PIN_BOMBA, LOW);
  digitalWrite(PIN_BUZZER, LOW);

  // Validar credenciales ANTES de tocar la libreria Firebase
  credencialesOk = !(credencialesPlaceholder(FIREBASE_API_KEY) ||
                     credencialesPlaceholder(DATABASE_URL));

  conectarWiFi();
  if (!credencialesOk) {
    Serial.println("credentials.h contiene placeholders - Firebase deshabilitado (modo offline local)");
  } else if (wifiOk) {
    if (!inicializarFirebase()) {
      Serial.println("Firebase no disponible - modo offline (sensor/bomba/buzzer siguen activos)");
    }
  } else {
    Serial.println("Sin Wi-Fi - modo offline (sensor/bomba/buzzer siguen activos)");
  }
}

// ================== LOOP (no bloqueante, funciona con o sin Firebase) ==================
void loop() {
  unsigned long now = millis();

  // -- Reconexion Wi-Fi no bloqueante (si nunca conecto o se cayo) --
  if (WiFi.status() != WL_CONNECTED) {
    wifiOk = false;
    firebaseReady = false;
    if (now - tReintentoWifi >= REINTENTO_WIFI_MS) {
      tReintentoWifi = now;
      Serial.println("Reintentando Wi-Fi...");
      WiFi.disconnect();
      conectarWiFi();
      if (wifiOk) tReintentoFirebase = 0;  // permite reintento inmediato de Firebase
    }
  }

  // -- (Re)inicializacion de Firebase no bloqueante (solo con credenciales reales) --
  if (wifiOk && credencialesOk && !firebaseReady && now - tReintentoFirebase >= REINTENTO_FIREBASE_MS) {
    tReintentoFirebase = now;
    if (inicializarFirebase()) {
      Serial.println("Firebase reconectado - publicando de nuevo");
    }
  }

  // -- Listener en tiempo real: bomba_estado desde la app Android --
  if (firebaseReady) {
    if (Firebase.RTDB.readStream(&fbdoStream)) {
      if (fbdoStream.streamTimeout()) {
        Serial.println("stream timeout, reconectando...");
        Firebase.RTDB.beginStream(&fbdoStream, NODO_DISPENSER "/bomba_estado");
      }
      if (fbdoStream.streamAvailable()) {
        if (fbdoStream.dataType() == "boolean") {
          aplicarBomba(fbdoStream.boolData());
        }
      }
    } else {
      Serial.println("readStream: " + fbdoStream.errorReason());
      // Error persistente -> reintentar reinicializacion en el proximo ciclo
      firebaseReady = false;
      tReintentoFirebase = now;
    }
  }

  // -- Publicacion periodica del nivel de agua (siempre se mide y loguea;
  //     solo el envio a Firebase depende de firebaseReady) --
  if (now - ultimaPublicacion >= INTERVALO_MS) {
    ultimaPublicacion = now;
    float dist = medirDistanciaFiltrada();
    if (dist > 0) {
      int nivel = calcularNivel(dist);
      if (nivel >= 0) {
        nivelActual = nivel;
        distanciaActual = dist;
        bool requiere = nivel < UMBRAL_CAMBIO;
        if (firebaseReady) {
          publicarNivel(dist, nivel, requiere, estadoBidon(nivel));
        }
        Serial.printf("%.1f cm -> %d%% (%s)%s\n",
                      dist, nivel, estadoBidon(nivel),
                      requiere ? "  CAMBIO DE BIDON" : "");
      }
    } else {
      Serial.println("Lectura HC-SR04 invalida");
    }
  }

  // -- Buzzer local: suena si nivel < 15% (critico) --
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

  delay(100);  // Alimenta el watchdog del ESP32 y evita bloqueos de CPU
}
