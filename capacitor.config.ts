import type { CapacitorConfig } from '@capacitor/cli';

// A diferencia de nebula-mobile-client, aquí NO hay server.url: la app arranca en
// su pantalla local de servidores (www/) y desde ahí navega a la web del servidor
// FilaOps que elija el usuario, que puede estar en cualquier dominio. Por eso
// allowNavigation es "*": cualquier servidor (y su login previo, p. ej.
// Cloudflare Access) se abre dentro de la app en vez de en el navegador.
//
// En las páginas remotas el puente nativo de Capacitor no está completo; no hace
// falta: allí manda la web del propio servidor. El botón "atrás" de Android
// vuelve por el historial hasta la pantalla de servidores.
const config: CapacitorConfig = {
  appId: 'com.nebulasyst.printflow',
  appName: 'PrintFlow',
  webDir: 'www',
  server: {
    androidScheme: 'https',
    // Permite servidores en la red local por http (p. ej. http://192.168.1.50)
    cleartext: true,
    allowNavigation: ['*'],
  },
  android: {
    // Marcador que busca PrintFlow (fork de FilaOps, frontend/src/nebula) para mostrar
    // el botón "Cambiar servidor" en Ajustes.
    appendUserAgent: 'PrintFlowApp',
  },
  plugins: {
    // Iconos claros en las barras del sistema (la app y la web son oscuras).
    SystemBars: { style: 'DARK' },
    // No se sustituye fetch global: las webs remotas usan sus cookies normales.
    // La pantalla de servidores llama a CapacitorHttp directamente.
    CapacitorHttp: { enabled: false },
  },
};

export default config;
