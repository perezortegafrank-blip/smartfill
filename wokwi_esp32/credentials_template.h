/**
 * ==========================================================================
 *  SmartFill - Plantilla de credenciales locales (ISO 27400)
 * ==========================================================================
 *  USO (opcional, para Arduino IDE o alternativo a build_flags):
 *    1. Copia este archivo como  credentials.h  (NO se sube a git).
 *    2. Escribe tus credenciales reales.
 *    3. En sketch.ino anade al inicio:  #include "credentials.h"
 *       (los guards #ifndef haran que estos valores prevalezcan).
 *
 *  Alternativa recomendada (PlatformIO): usar los build_flags de
 *  platformio.ini directamente - no requiere este archivo.
 */

#define WIFI_SSID       "TU_WIFI_AQUI"
#define WIFI_PASSWORD   "TU_PASSWORD_AQUI"
#define FIREBASE_URL    "https://TU-PROYECTO-default-rtdb.firebaseio.com"
#define FIREBASE_API_KEY "TU_WEB_API_KEY_AQUI"
