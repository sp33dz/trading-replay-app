package com.tradingview.replay;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "TradingReplay";
    private WebView webView;
    private boolean isFullscreen = false;
    private WindowInsetsControllerCompat insetsController;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        insetsController = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());

        setupWebView();
        webView.loadUrl("file:///android_asset/replay.html");
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        // ใช้ Chrome User-Agent เต็ม — Google Drive จะไม่ redirect ไป login page
        settings.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.6099.144 Mobile Safari/537.36"
        );

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        webView.setScrollbarFadingEnabled(true);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        // เปิด cookies รวมถึง third-party (lh3.googleusercontent.com)
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient());
    }

    // ────────────────────────────────────────────────────────
    //  HTTP helper — ทำ request จาก Java (ไม่มี CORS / file:// ปัญหา)
    //  รองรับ redirect สูงสุด 8 ครั้ง และส่ง Referer + Accept headers
    // ────────────────────────────────────────────────────────
    private byte[] httpGetBytes(String urlStr) throws Exception {
        String currentUrl = urlStr;
        int maxRedirects = 8;

        for (int i = 0; i < maxRedirects; i++) {
            Log.d(TAG, "HTTP GET attempt " + i + ": " + currentUrl);
            URL url = new URL(currentUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.6099.144 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "image/webp,image/apng,image/*,*/*;q=0.8");
            conn.setRequestProperty("Referer", "https://drive.google.com/");
            conn.setRequestProperty("Origin", "https://drive.google.com");

            String cookies = CookieManager.getInstance().getCookie(currentUrl);
            if (cookies != null && !cookies.isEmpty()) {
                conn.setRequestProperty("Cookie", cookies);
                Log.d(TAG, "Sending cookies len=" + cookies.length());
            }

            int status = conn.getResponseCode();
            String contentType = conn.getContentType();
            Log.d(TAG, "HTTP status=" + status + " contentType=" + contentType);

            if (status == HttpURLConnection.HTTP_MOVED_PERM ||
                status == HttpURLConnection.HTTP_MOVED_TEMP ||
                status == HttpURLConnection.HTTP_SEE_OTHER ||
                status == 307 || status == 308) {

                String location = conn.getHeaderField("Location");
                Log.d(TAG, "Redirect → " + location);
                conn.disconnect();
                if (location == null || location.isEmpty()) break;
                if (location.startsWith("/")) {
                    URL base = new URL(currentUrl);
                    location = base.getProtocol() + "://" + base.getHost() + location;
                }
                currentUrl = location;
                continue;
            }

            if (status != HttpURLConnection.HTTP_OK) {
                // อ่าน error body เพื่อ debug
                InputStream es = conn.getErrorStream();
                String errBody = "";
                if (es != null) {
                    byte[] buf = new byte[512];
                    int n = es.read(buf);
                    errBody = n > 0 ? new String(buf, 0, n) : "";
                    es.close();
                }
                conn.disconnect();
                throw new Exception("HTTP " + status + " body=" + errBody.replace("\n"," ").substring(0, Math.min(errBody.length(),200)));
            }

            InputStream is = conn.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(chunk)) != -1) {
                buffer.write(chunk, 0, bytesRead);
            }
            is.close();
            conn.disconnect();
            byte[] result = buffer.toByteArray();
            Log.d(TAG, "Downloaded " + result.length + " bytes, contentType=" + contentType);
            return result;
        }
        throw new Exception("Too many redirects for: " + urlStr);
    }

    // ────────────────────────────────────────────────────────
    //  Android Bridge
    // ────────────────────────────────────────────────────────
    private class AndroidBridge {

        @JavascriptInterface
        public String getNotes() {
            SharedPreferences prefs = getSharedPreferences("TradingReplay", MODE_PRIVATE);
            return prefs.getString("notes", "{}");
        }

        @JavascriptInterface
        public void saveNotes(String notesJson) {
            SharedPreferences prefs = getSharedPreferences("TradingReplay", MODE_PRIVATE);
            prefs.edit().putString("notes", notesJson).apply();
        }

        // ─── fetchImage: JS เรียก → Java โหลดรูป → callback กลับ JS เป็น base64 ───
        @JavascriptInterface
        public void fetchImage(String urlStr, String callbackId) {
            Log.d(TAG, "fetchImage called: cbId=" + callbackId + " url=" + urlStr);
            executor.submit(() -> {
                try {
                    byte[] imageBytes = httpGetBytes(urlStr);
                    if (imageBytes.length < 100) {
                        throw new Exception("Response too small (" + imageBytes.length + " bytes), likely not an image");
                    }
                    boolean isImage = isImageBytes(imageBytes);
                    Log.d(TAG, "isImage=" + isImage + " bytes=" + imageBytes.length
                        + " magic=" + String.format("%02X %02X %02X %02X",
                            imageBytes[0]&0xFF, imageBytes[1]&0xFF,
                            imageBytes[2]&0xFF, imageBytes[3]&0xFF));
                    if (!isImage) {
                        // แสดง 200 chars แรกของ response เพื่อ debug
                        String preview = new String(imageBytes, 0, Math.min(200, imageBytes.length));
                        throw new Exception("Not an image. Response preview: " + preview.replace("\n"," "));
                    }

                    String b64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP);
                    String mimeType = detectMime(imageBytes);
                    String dataUri = "data:" + mimeType + ";base64," + b64;
                    Log.d(TAG, "fetchImage success: mime=" + mimeType + " b64len=" + b64.length());

                    String escaped = dataUri.replace("\\", "\\\\").replace("'", "\\'");
                    mainHandler.post(() -> webView.evaluateJavascript(
                        "window._imgCallback('" + callbackId + "', '" + escaped + "', null);",
                        null
                    ));

                } catch (Exception e) {
                    Log.e(TAG, "fetchImage error: " + e.getMessage(), e);
                    String errMsg = e.getMessage() != null
                        ? e.getMessage().replace("'", "\\'").replace("\n", " ")
                        : "fetch error";
                    // ตัดให้สั้นลงเพื่อไม่ให้ JS string ยาวเกินไป
                    if (errMsg.length() > 300) errMsg = errMsg.substring(0, 300);
                    final String finalErr = errMsg;
                    mainHandler.post(() -> webView.evaluateJavascript(
                        "window._imgCallback('" + callbackId + "', null, '" + finalErr + "');",
                        null
                    ));
                }
            });
        }

        @JavascriptInterface
        public void copyToClipboard(String text) {
            mainHandler.post(() -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Debug Log", text);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(MainActivity.this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
            });
        }

        private boolean isImageBytes(byte[] bytes) {
            if (bytes.length < 4) return false;
            // JPEG: FF D8 FF
            if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) return true;
            // PNG:  89 50 4E 47
            if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return true;
            // GIF:  47 49 46
            if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return true;
            // WEBP: 52 49 46 46 ... 57 45 42 50
            if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') return true;
            return false;
        }

        private String detectMime(byte[] bytes) {
            if (bytes.length < 4) return "image/jpeg";
            if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P') return "image/png";
            if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return "image/gif";
            if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') return "image/webp";
            return "image/jpeg";
        }

        @JavascriptInterface
        public void toggleFullscreen() {
            runOnUiThread(() -> {
                isFullscreen = !isFullscreen;
                if (isFullscreen) {
                    insetsController.hide(WindowInsetsCompat.Type.systemBars());
                    insetsController.setSystemBarsBehavior(
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    insetsController.show(WindowInsetsCompat.Type.systemBars());
                }
            });
        }

        @JavascriptInterface
        public void onFrameChange(String frameJson) {}

        @JavascriptInterface
        public void showToast(String msg) {
            runOnUiThread(() ->
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show()
            );
        }

        @JavascriptInterface
        public void keepScreenOn(boolean on) {
            runOnUiThread(() -> {
                if (on) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            });
        }
    }

    @Override
    public void onBackPressed() {
        webView.evaluateJavascript(
            "(function(){ return window.onBackPressed ? window.onBackPressed() : false; })()",
            result -> {
                if (!"true".equals(result)) super.onBackPressed();
            }
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        webView.stopLoading();
        webView.destroy();
    }
}
