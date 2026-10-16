package com.nebulasyst.printflow;

import android.os.Bundle;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fondo oscuro detrás de las barras del sistema (la web de PrintFlow es
        // oscura; con el fondo blanco por defecto las barras quedaban blancas).
        getWindow().getDecorView().setBackgroundColor(0xFF0B0D14);

        WebView webView = getBridge().getWebView();
        WebFeatures features = new WebFeatures(this, webView);
        features.install();

        // Si el WebView no soporta scripts al inicio del documento, se inyecta
        // en cada carga de página.
        if (!features.usesDocumentStartScript()) {
            getBridge()
                .addWebViewListener(
                    new WebViewListener() {
                        @Override
                        public void onPageStarted(WebView view) {
                            features.injectNow();
                        }

                        @Override
                        public void onPageLoaded(WebView view) {
                            features.injectNow();
                        }
                    }
                );
        }
    }
}
