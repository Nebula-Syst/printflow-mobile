package com.nebulasyst.printflow;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * Lo que la web de un servidor FilaOps/PrintFlow necesita y un WebView no trae:
 * guardar/abrir ficheros, imprimir y abrir enlaces externos. La parte web está
 * en res/raw/printflow_shim.js, que llama a estos métodos vía window.PrintFlowNative.
 */
class WebFeatures {

    private static final String JS_NAME = "PrintFlowNative";
    private static final int MAX_BYTES = 100 * 1024 * 1024;

    private final Activity activity;
    private final WebView webView;
    private final String shim;
    private boolean documentStart;

    WebFeatures(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.shim = readRaw(activity, R.raw.printflow_shim);
    }

    void install() {
        webView.addJavascriptInterface(new Api(), JS_NAME);

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, shim, Collections.singleton("*"));
            documentStart = true;
        }

        // Descargas "normales" (navegar a una URL que responde como adjunto)
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
            new Thread(() -> downloadUrl(url, userAgent, name, mimeType)).start();
        });
    }

    boolean usesDocumentStartScript() {
        return documentStart;
    }

    void injectNow() {
        webView.evaluateJavascript(shim, null);
    }

    // ------------------------------------------------------------------ JS → nativo

    private class Api {

        @JavascriptInterface
        public void saveFile(String name, String mimeType, String base64, boolean open) {
            byte[] data;
            try {
                data = Base64.decode(base64, Base64.DEFAULT);
            } catch (IllegalArgumentException e) {
                toast("Fichero dañado");
                return;
            }
            saveAndOpen(name, mimeType, data, open);
        }

        @JavascriptInterface
        public void openExternal(String url) {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("mailto") && !scheme.equals("tel")) {
                return;
            }
            activity.runOnUiThread(() -> {
                try {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException e) {
                    toast("No hay ninguna app para abrir este enlace");
                }
            });
        }

        @JavascriptInterface
        public void print(String title) {
            activity.runOnUiThread(() -> {
                PrintManager pm = (PrintManager) activity.getSystemService(Context.PRINT_SERVICE);
                String job = (title == null || title.isEmpty()) ? "PrintFlow" : title;
                pm.print(job, webView.createPrintDocumentAdapter(job), new PrintAttributes.Builder().build());
            });
        }

        @JavascriptInterface
        public void toast(String message) {
            WebFeatures.this.toast(message);
        }
    }

    // ------------------------------------------------------------------ ficheros

    private void downloadUrl(String url, String userAgent, String name, String mimeType) {
        toast("Descargando " + name + "…");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) conn.setRequestProperty("Cookie", cookies);
            if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            int code = conn.getResponseCode();
            if (code >= 400) {
                toast("No se ha podido descargar (HTTP " + code + ")");
                return;
            }
            try (InputStream in = conn.getInputStream()) {
                byte[] data = readAll(in);
                saveAndOpen(name, mimeType != null ? mimeType : conn.getContentType(), data, true);
            }
        } catch (IOException e) {
            toast("No se ha podido descargar: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void saveAndOpen(String rawName, String rawMime, byte[] data, boolean open) {
        String mime = cleanMime(rawMime);
        String name = cleanName(rawName, mime);
        Uri uri;
        try {
            uri = save(name, mime, data);
        } catch (IOException e) {
            toast("No se ha podido guardar el fichero: " + e.getMessage());
            return;
        }
        if (!open) {
            toast("Guardado en Descargas: " + name);
            return;
        }
        Intent view = new Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.runOnUiThread(() -> {
            try {
                activity.startActivity(view);
                toast("Guardado en Descargas: " + name);
            } catch (ActivityNotFoundException e) {
                toast("Guardado en Descargas: " + name + " (no hay app para abrirlo)");
            }
        });
    }

    /** Android 10+: carpeta Descargas pública (sin permisos). Antes: carpeta de la app. */
    private Uri save(String name, String mime, byte[] data) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver cr = activity.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, mime);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("MediaStore");
            try (OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) throw new IOException("MediaStore");
                out.write(data);
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            cr.update(uri, values, null, null);
            return uri;
        }
        File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = new File(activity.getCacheDir(), "downloads");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("No se puede crear " + dir);
        File file = uniqueFile(dir, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
        return FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", file);
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; f.exists(); i++) f = new File(dir, base + " (" + i + ")" + ext);
        return f;
    }

    private static String cleanMime(String mime) {
        if (mime == null || mime.trim().isEmpty()) return "application/octet-stream";
        int semi = mime.indexOf(';');
        return (semi >= 0 ? mime.substring(0, semi) : mime).trim().toLowerCase();
    }

    private static String cleanName(String name, String mime) {
        String n = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (n.isEmpty()) n = "printflow-" + System.currentTimeMillis();
        if (n.length() > 120) n = n.substring(n.length() - 120);
        if (!n.contains(".")) {
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (ext != null) n = n + "." + ext;
        }
        return n;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
            if (buf.size() > MAX_BYTES) throw new IOException("fichero demasiado grande");
        }
        return buf.toByteArray();
    }

    private static String readRaw(Context ctx, int id) {
        try (InputStream in = ctx.getResources().openRawResource(id)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private void toast(String message) {
        activity.runOnUiThread(() -> Toast.makeText(activity, message, Toast.LENGTH_SHORT).show());
    }
}
