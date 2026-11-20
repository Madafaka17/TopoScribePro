package com.toposcribe.web;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Contenedor de TopoScribePro: muestra la app web empaquetada en el APK (assets) dentro de un
 * WebView, en un origen https local (appassets.androidplatform.net) para que el almacenamiento
 * del navegador (IndexedDB) funcione igual que en Chrome. Agrega lo que el WebView no trae por sí
 * solo: elegir archivos o tomar fotos, y guardar en Descargas los PDF, DXF, Excel y respaldos.
 */
public class MainActivity extends Activity {

    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";
    private static final int REQ_FILES = 1;
    private static final int REQ_CAMERA = 2;

    private static final Map<String, String> MIME = new HashMap<>();
    static {
        MIME.put("html", "text/html");
        MIME.put("js", "text/javascript");
        MIME.put("mjs", "text/javascript");
        MIME.put("css", "text/css");
        MIME.put("json", "application/json");
        MIME.put("webmanifest", "application/manifest+json");
        MIME.put("svg", "image/svg+xml");
        MIME.put("png", "image/png");
        MIME.put("jpg", "image/jpeg");
        MIME.put("wasm", "application/wasm");
        MIME.put("ttf", "font/ttf");
        MIME.put("txt", "text/plain");
    }

    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private ValueCallback<Uri[]> pendingFiles;
    private Uri pendingCameraUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        // Sirve los archivos de la app con el tipo MIME correcto (los módulos .mjs lo exigen).
        final AssetManager assets = getAssets();
        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", path -> {
                    try {
                        String clean = path.isEmpty() ? "index.html" : path;
                        InputStream in = assets.open(clean);
                        int dot = clean.lastIndexOf('.');
                        String ext = dot >= 0 ? clean.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
                        String mime = MIME.containsKey(ext) ? MIME.get(ext) : "application/octet-stream";
                        boolean text = mime.startsWith("text/") || mime.contains("json") || mime.contains("svg");
                        return new WebResourceResponse(mime, text ? "utf-8" : null, in);
                    } catch (IOException e) {
                        return null;
                    }
                })
                .build();

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);

        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if ("appassets.androidplatform.net".equals(url.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (ActivityNotFoundException ignored) {
                    // sin navegador externo: se ignora el enlace
                }
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pendingFiles != null) pendingFiles.onReceiveValue(null);
                pendingFiles = callback;
                boolean images = true;
                for (String t : params.getAcceptTypes()) {
                    if (t != null && !t.isEmpty() && !t.startsWith("image/")) images = false;
                }
                if (images && params.isCaptureEnabled() && openCamera()) return true;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(images && hasAccept(params) ? "image/*" : "*/*");
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(intent, "Elegir archivo"), REQ_FILES);
                } catch (ActivityNotFoundException e) {
                    finishFiles(null);
                    toast("No hay una aplicación para elegir archivos.");
                }
                return true;
            }
        });

        if (savedInstanceState != null) webView.restoreState(savedInstanceState);
        else webView.loadUrl(START_URL);
    }

    private static boolean hasAccept(WebChromeClient.FileChooserParams params) {
        for (String t : params.getAcceptTypes()) if (t != null && !t.isEmpty()) return true;
        return false;
    }

    private boolean openCamera() {
        try {
            File dir = new File(getCacheDir(), "camara");
            if (!dir.exists() && !dir.mkdirs()) return false;
            File photo = new File(dir, "foto_" + System.currentTimeMillis() + ".jpg");
            pendingCameraUri = FileProvider.getUriForFile(this, "com.toposcribe.web.fileprovider", photo);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, REQ_CAMERA);
            return true;
        } catch (Exception e) {
            pendingCameraUri = null;
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAMERA) {
            finishFiles(resultCode == RESULT_OK && pendingCameraUri != null ? new Uri[]{pendingCameraUri} : null);
            pendingCameraUri = null;
        } else if (requestCode == REQ_FILES) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    result = new Uri[n];
                    for (int i = 0; i < n; i++) result[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            finishFiles(result);
        }
    }

    private void finishFiles(Uri[] result) {
        if (pendingFiles != null) pendingFiles.onReceiveValue(result);
        pendingFiles = null;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    /** Funciones disponibles para la app web como window.AndroidBridge. */
    private class Bridge {
        @JavascriptInterface
        public boolean isAndroid() {
            return true;
        }

        /** Guarda un archivo generado por la app en Descargas/TopoScribePro y, si es PDF, lo abre. */
        @JavascriptInterface
        public String saveFile(String name, String mime, String base64) {
            String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            String type = mime == null || mime.isEmpty() ? "application/octet-stream" : mime;
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                Uri uri;
                String where;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                    v.put(MediaStore.Downloads.MIME_TYPE, type);
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TopoScribePro");
                    uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) throw new IOException("No se pudo crear el archivo en Descargas.");
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out == null) throw new IOException("No se pudo escribir el archivo.");
                        out.write(bytes);
                    }
                    where = "Descargas/TopoScribePro/" + safe;
                } else {
                    File dir = new File(getExternalFilesDir(null), "Download");
                    if (!dir.exists() && !dir.mkdirs()) throw new IOException("No se pudo crear la carpeta.");
                    File f = new File(dir, safe);
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(bytes);
                    }
                    uri = FileProvider.getUriForFile(MainActivity.this, "com.toposcribe.web.fileprovider", f);
                    where = f.getAbsolutePath();
                }
                toast("Guardado en " + where);
                if (type.equals("application/pdf")) {
                    final Uri open = uri;
                    runOnUiThread(() -> {
                        Intent view = new Intent(Intent.ACTION_VIEW);
                        view.setDataAndType(open, "application/pdf");
                        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        try {
                            startActivity(view);
                        } catch (ActivityNotFoundException ignored) {
                            // sin visor de PDF: el archivo queda en Descargas
                        }
                    });
                }
                return where;
            } catch (Exception e) {
                toast("No se pudo guardar " + safe + ": " + e.getMessage());
                return "";
            }
        }
    }
}
