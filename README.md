<p align="center"><img src="www/printflow-corto.png" alt="PrintFlow" height="44"></p>

# PrintFlow — app móvil

App Android (Capacitor 8) para usar **cualquier servidor PrintFlow o FilaOps** desde el
móvil, esté alojado donde esté (cada empresa con su dominio).

> **PrintFlow es un fork modificado de [FilaOps](https://github.com/BLB3DPrinting/filaops)**
> (BLB3D Printing, Business Source License 1.1). El servidor de PrintFlow está en
> [Nebula-Syst/filaops](https://github.com/Nebula-Syst/filaops).

**Descargar:** [última versión (APK)](https://github.com/Nebula-Syst/printflow-mobile/releases/latest)

## Cómo funciona

- La app arranca en una **pantalla local de servidores** (`www/`): añades la dirección
  de tu ERP (`gestion.tuempresa.com`, `http://192.168.1.50:13003`…) y la comprueba.
- Al elegir un servidor, la app carga **la web de ese servidor**. Por eso cada uno
  muestra sus propias funciones: un servidor PrintFlow
  ([`Nebula-Syst/filaops`](https://github.com/Nebula-Syst/filaops)) tendrá español,
  usuarios ilimitados, etc.; un FilaOps estándar funciona igual pero sin esos extras.
- Se recuerda el último servidor y se abre solo al arrancar. Para cambiar de servidor:
  botón **atrás** de Android, o **Ajustes → Cambiar servidor** (solo en servidores PrintFlow).
- Cada servidor mantiene su propia sesión (cookies por dominio): puedes tener varios
  con la sesión iniciada.

### Qué detecta al añadir un servidor

| Resultado | Qué significa |
| --- | --- |
| **PrintFlow** | Servidor PrintFlow (sirve `/nebula.json` con `edition: printflow`) |
| **FilaOps estándar** | Responde `/api/v1/setup/status`, sin `/nebula.json` |
| **Con acceso protegido** | Hay un login previo (p. ej. Cloudflare Access); se pide al abrir |
| Sin verificar | Se guardó igualmente aunque no respondía como FilaOps |

Las comprobaciones usan el HTTP nativo (`CapacitorHttp`) para no depender del CORS del
servidor. `CapacitorHttp` **no** reemplaza el `fetch` global: las webs remotas usan
sus cookies de siempre.

### Descargas, ventanas nuevas e impresión

La web del servidor está pensada para un navegador; un WebView de Android no descarga
ficheros, no abre pestañas ni imprime. `res/raw/printflow_shim.js` (inyectado en cada
página por `WebFeatures.java`) lo resuelve para cualquier servidor, sin tocar la web:

| En la web | En la app |
| --- | --- |
| Descargar (`<a download>`, `blob:`, adjuntos) | Se guarda en **Descargas** y se abre con el visor (PDF, CSV…) |
| `window.open` / `target="_blank"` del propio servidor | Si es un fichero, igual que arriba; si es una página, se abre en la misma vista |
| Enlaces a otras webs | Navegador del sistema (la app sigue en el ERP) |
| `window.print()` | Diálogo de impresión de Android |

Para probarlo en el emulador: `adb forward tcp:9333 localabstract:webview_devtools_remote_<pid>`
y Chrome DevTools (`chrome://inspect`) sobre el WebView (APK debug).

## Integración con el servidor PrintFlow

- User-Agent con `PrintFlowApp` → el servidor muestra en Ajustes la tarjeta "App móvil"
  con **Cambiar servidor** (enlaza a `https://localhost/?select=1`, el origen local).
- `/nebula.json` en el servidor → `{ "edition": "nebula", "features": [...] }`.

## Desarrollo

```bash
npm install
npm test                      # tests de la pantalla de servidores (Node, sin red)
npx cap sync android
cd android
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME=~/Android/Sdk
./gradlew assembleDebug       # android/app/build/outputs/apk/debug/app-debug.apk
```

Probar la pantalla de servidores en el navegador: `python3 -m http.server -d www`
(las comprobaciones fallarán por CORS; en el móvil van por HTTP nativo).

Iconos/splash: `resources/` (icon, icon-foreground/background para el icono adaptativo
y splash) salen del kit de marca de PrintFlow (`branding/` en Nebula-Syst/filaops).

```bash
npx capacitor-assets generate --android --iconBackgroundColor '#0b0d17' --splashBackgroundColor '#0b0d17'
```

## CI/CD

`.github/workflows/build-apk.yml`: cada push a `main` pasa los tests, compila el APK
(debug, sin firma de Play Store) y lo publica como GitHub Release `1.0.<run>`.

## Pendiente

- iOS: se puede añadir con `npx cap add ios`, pero compilar requiere macOS/Xcode.
- Firma de release / Play Store.
