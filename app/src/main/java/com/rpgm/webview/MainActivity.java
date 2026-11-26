package com.rpgm.webview;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

public class MainActivity extends Activity implements VirtualPad.Listener {

    // ================= Konfigurasi =================
    /** Host palsu: semua request ke host ini dilayani dari assets/www. */
    private static final String HOST = "appassets.androidplatform.net";
    private static final String ASSET_ROOT = "www";
    /** Tambah "?test" di belakang untuk mode test (F9 debug menu aktif). */
    private static final String START_URL = "https://" + HOST + "/index.html";
    /** Lama splash tampil (ms), lalu fade-out. */
    private static final long SPLASH_MS = 1700;
    /** true = bisa di-inspect lewat chrome://inspect (USB). */
    private static final boolean DEBUG_WEBVIEW = false;
    private static final int MAX_LOG = 40;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> errorLog = new ArrayDeque<>();
    private FrameLayout rootView;
    private SharedPreferences prefs;
    private boolean tyrano;
    private boolean nodeShim;
    private WebView webView;
    private VirtualPad pad;
    private ImageView splash;
    private boolean splashGone;
    private int mouseButtons;
    private long mouseDownTime;

    // Indeks nama file huruf kecil -> nama asli (untuk game Windows yang beda huruf besar/kecil)
    private Map<String, String> lowerIndex;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("pad", MODE_PRIVATE);
        tyrano = "tyrano".equalsIgnoreCase(getString(R.string.pad_profile).trim());
        nodeShim = !"false".equalsIgnoreCase(getString(R.string.node_shim).trim());
        if (tyrano && prefs.getBoolean("portrait", false)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        }

        rootView = new FrameLayout(this);
        rootView.setBackgroundColor(Color.BLACK);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        rootView.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        pad = new VirtualPad(this, prefs, this, tyrano);
        rootView.addView(pad, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        splash = new ImageView(this);
        splash.setBackgroundColor(Color.BLACK);
        splash.setScaleType(ImageView.ScaleType.FIT_CENTER);
        splash.setImageResource(R.drawable.splash);
        splash.setClickable(true); // telan sentuhan selama splash tampil
        rootView.addView(splash, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(rootView);
        applyImmersive();
        setupWebView();
        webView.loadUrl(START_URL);
        ui.postDelayed(this::hideSplash, SPLASH_MS);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        if (DEBUG_WEBVIEW) WebView.setWebContentsDebuggingEnabled(true);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setTextZoom(100);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setLongClickable(false);
        webView.setOnLongClickListener(v -> true);
        webView.setHapticFeedbackEnabled(false);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);

        if (nodeShim) webView.addJavascriptInterface(new FsBridge(), "AndroidFS");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    errorLog.addLast(m.message() + "  (" + m.sourceId() + ":" + m.lineNumber() + ")");
                    while (errorLog.size() > MAX_LOG) errorLog.removeFirst();
                }
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                if (HOST.equals(r.getUrl().getHost())) return serveAsset(r);
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "WebView crash, memuat ulang...", Toast.LENGTH_LONG).show();
                    recreate();
                });
                return true;
            }
        });
    }

    // ================= Asset server =================

    private WebResourceResponse serveAsset(WebResourceRequest req) {
        String path = req.getUrl().getPath();
        if (path == null || path.isEmpty() || path.equals("/")) path = "/index.html";
        if (path.contains("..")) return status(403, "Forbidden");
        String assetPath = ASSET_ROOT + path;
        if (nodeShim) {
            if (path.equals("/__nodeshim.js")) return serveShim();
            if (path.equals("/index.html")) return serveHtmlWithShim(assetPath);
        }

        InputStream in = openAsset(assetPath);
        if (in == null) return status(404, "Not Found");

        String mime = mimeOf(path);
        String enc = isText(mime) ? "utf-8" : null;
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Accept-Ranges", "bytes");

        String range = req.getRequestHeaders().get("Range");
        if (range != null && range.startsWith("bytes=")) {
            try {
                long total = in.available();
                if (total > 0) {
                    String spec = range.substring(6).split(",")[0].trim();
                    int dash = spec.indexOf('-');
                    String a = spec.substring(0, dash);
                    String b = spec.substring(dash + 1);
                    long start, end;
                    if (a.isEmpty()) {
                        start = Math.max(0, total - Long.parseLong(b));
                        end = total - 1;
                    } else {
                        start = Long.parseLong(a);
                        end = b.isEmpty() ? total - 1 : Math.min(Long.parseLong(b), total - 1);
                    }
                    if (start > end || start >= total) {
                        closeQuietly(in);
                        Map<String, String> h2 = new HashMap<>(h);
                        h2.put("Content-Range", "bytes */" + total);
                        return new WebResourceResponse("text/plain", "utf-8", 416, "Range Not Satisfiable",
                                h2, new ByteArrayInputStream(new byte[0]));
                    }
                    long remaining = start;
                    while (remaining > 0) {
                        long n = in.skip(remaining);
                        if (n <= 0) break;
                        remaining -= n;
                    }
                    if (remaining == 0) {
                        long len = end - start + 1;
                        h.put("Content-Range", "bytes " + start + "-" + end + "/" + total);
                        h.put("Content-Length", String.valueOf(len));
                        return new WebResourceResponse(mime, enc, 206, "Partial Content", h,
                                new BoundedStream(in, len));
                    }
                    // skip gagal: buka ulang dan kirim utuh
                    closeQuietly(in);
                    in = openAsset(assetPath);
                    if (in == null) return status(404, "Not Found");
                }
            } catch (Exception ignored) {
                // format Range aneh -> kirim utuh
                closeQuietly(in);
                in = openAsset(assetPath);
                if (in == null) return status(404, "Not Found");
            }
        }
        return new WebResourceResponse(mime, enc, 200, "OK", h, in);
    }


    // ================= Node.js / NW.js shim =================

    private Map<String, String> plainHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", "no-cache");
        return h;
    }

    private WebResourceResponse serveShim() {
        try {
            return new WebResourceResponse("text/javascript", "UTF-8", 200, "OK",
                    plainHeaders(), getAssets().open("nodeshim.js"));
        } catch (IOException e) {
            return status(404, "Not Found");
        }
    }

    private WebResourceResponse serveHtmlWithShim(String assetPath) {
        InputStream in = openAsset(assetPath);
        if (in == null) return status(404, "Not Found");
        try {
            String html = new String(readAll(in), StandardCharsets.UTF_8);
            String tag = "<script src=\"/__nodeshim.js\"></script>";
            Matcher m = Pattern.compile("<head(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE).matcher(html);
            if (m.find()) {
                html = html.substring(0, m.end()) + tag + html.substring(m.end());
            } else {
                html = tag + html;
            }
            return new WebResourceResponse("text/html", "UTF-8", 200, "OK", plainHeaders(),
                    new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            return status(500, "Error");
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    /** Jembatan fs untuk nodeshim.js: baca aset game, tulis ke penyimpanan app (files/nodefs). */
    private final class FsBridge {
        private File root() {
            File r = new File(getFilesDir(), "nodefs");
            r.mkdirs();
            return r;
        }

        private String clean(String p) {
            if (p == null || !p.startsWith("/") || p.contains("..")) return null;
            while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
            return p;
        }

        private File ov(String p) {
            try {
                File r = root();
                File f = new File(r, p);
                String rc = r.getCanonicalPath();
                String fc = f.getCanonicalPath();
                if (fc.equals(rc) || fc.startsWith(rc + File.separator)) return f;
            } catch (IOException ignored) {
            }
            return null;
        }

        /** Path aset nyata (tanpa peduli besar-kecil huruf) bila berupa file, selain itu null. */
        private String assetFile(String p) {
            String ap = p.equals("/") ? ASSET_ROOT : ASSET_ROOT + p;
            try {
                getAssets().open(ap).close();
                return ap;
            } catch (IOException ignored) {
            }
            String real = index().get(ap.toLowerCase(Locale.ROOT));
            if (real != null) {
                try {
                    getAssets().open(real).close();
                    return real;
                } catch (IOException ignored) {
                }
            }
            return null;
        }

        private String[] assetList(String p) {
            String ap = p.equals("/") ? ASSET_ROOT : ASSET_ROOT + p;
            try {
                String[] r = getAssets().list(ap);
                if (r != null && r.length > 0) return r;
            } catch (IOException ignored) {
            }
            String pre = ap.toLowerCase(Locale.ROOT) + "/";
            TreeSet<String> out = new TreeSet<>();
            for (String real : index().values()) {
                if (real.toLowerCase(Locale.ROOT).startsWith(pre)) {
                    String rest = real.substring(pre.length());
                    int i = rest.indexOf('/');
                    out.add(i < 0 ? rest : rest.substring(0, i));
                }
            }
            return out.toArray(new String[0]);
        }

        private byte[] readBytes(String p) {
            try {
                File f = ov(p);
                if (f != null && f.isFile()) {
                    try (FileInputStream in = new FileInputStream(f)) {
                        return readAll(in);
                    }
                }
                String a = assetFile(p);
                if (a != null) {
                    try (InputStream in = getAssets().open(a)) {
                        return readAll(in);
                    }
                }
            } catch (IOException ignored) {
            }
            return null;
        }

        private boolean write(String p, byte[] data, boolean append) {
            try {
                File f = ov(p);
                if (f == null || f.isDirectory()) return false;
                File par = f.getParentFile();
                if (par != null) par.mkdirs();
                if (append && !f.exists()) {
                    byte[] old = readBytes(p);
                    if (old != null) {
                        try (FileOutputStream o = new FileOutputStream(f, false)) {
                            o.write(old);
                        }
                    }
                }
                try (FileOutputStream o = new FileOutputStream(f, append)) {
                    o.write(data);
                }
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        @JavascriptInterface
        public String stat(String p) {
            p = clean(p);
            if (p == null) return "";
            File f = ov(p);
            if (f != null && f.exists()) {
                return String.format(Locale.ROOT, "{\"t\":\"%s\",\"s\":%d,\"m\":%d}",
                        f.isDirectory() ? "d" : "f", f.length(), f.lastModified());
            }
            String a = assetFile(p);
            if (a != null) {
                long size = 0;
                try (InputStream in = getAssets().open(a)) {
                    size = in.available();
                } catch (IOException ignored) {
                }
                return "{\"t\":\"f\",\"s\":" + size + ",\"m\":0}";
            }
            if (p.equals("/") || assetList(p).length > 0) return "{\"t\":\"d\",\"s\":0,\"m\":0}";
            return "";
        }

        @JavascriptInterface
        public String list(String p) {
            p = clean(p);
            JSONArray arr = new JSONArray();
            if (p == null) return arr.toString();
            TreeSet<String> names = new TreeSet<>();
            File f = ov(p);
            if (f != null && f.isDirectory()) {
                String[] l = f.list();
                if (l != null) for (String n : l) names.add(n);
            }
            for (String n : assetList(p)) names.add(n);
            for (String n : names) arr.put(n);
            return arr.toString();
        }

        @JavascriptInterface
        public String readText(String p) {
            p = clean(p);
            byte[] b = p == null ? null : readBytes(p);
            return b == null ? null : new String(b, StandardCharsets.UTF_8);
        }

        @JavascriptInterface
        public String readB64(String p) {
            p = clean(p);
            byte[] b = p == null ? null : readBytes(p);
            return b == null ? null : Base64.encodeToString(b, Base64.NO_WRAP);
        }

        @JavascriptInterface
        public boolean writeText(String p, String text, boolean append) {
            p = clean(p);
            return p != null && text != null && write(p, text.getBytes(StandardCharsets.UTF_8), append);
        }

        @JavascriptInterface
        public boolean writeB64(String p, String b64, boolean append) {
            p = clean(p);
            if (p == null || b64 == null) return false;
            try {
                return write(p, Base64.decode(b64, Base64.DEFAULT), append);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean mkdir(String p) {
            p = clean(p);
            File f = p == null ? null : ov(p);
            return f != null && (f.isDirectory() || f.mkdirs());
        }

        private boolean delete(File f) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) delete(k);
            return f.delete();
        }

        @JavascriptInterface
        public boolean remove(String p) {
            p = clean(p);
            File f = p == null || p.equals("/") ? null : ov(p);
            return f != null && f.exists() && delete(f);
        }

        @JavascriptInterface
        public boolean rename(String a, String b) {
            a = clean(a);
            b = clean(b);
            if (a == null || b == null) return false;
            File fa = ov(a);
            File fb = ov(b);
            if (fa == null || fb == null) return false;
            File par = fb.getParentFile();
            if (par != null) par.mkdirs();
            if (fa.exists()) return fa.renameTo(fb);
            byte[] data = readBytes(a);
            return data != null && write(b, data, false);
        }

        @JavascriptInterface
        public void quit() {
            runOnUiThread(() -> finishAndRemoveTask());
        }

        @JavascriptInterface
        public void openUrl(String url) {
            if (url == null) return;
            final Uri u = Uri.parse(url);
            String sc = u.getScheme();
            if (!"http".equalsIgnoreCase(sc) && !"https".equalsIgnoreCase(sc)) return;
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (RuntimeException ignored) {
                }
            });
        }
    }

    private InputStream openAsset(String assetPath) {
        AssetManager am = getAssets();
        try {
            return am.open(assetPath);
        } catch (IOException ignored) {
        }
        String real = index().get(assetPath.toLowerCase(Locale.ROOT));
        if (real != null) {
            try {
                return am.open(real);
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    private synchronized Map<String, String> index() {
        if (lowerIndex == null) {
            Map<String, String> m = new HashMap<>();
            walk(ASSET_ROOT, m);
            lowerIndex = m;
        }
        return lowerIndex;
    }

    private void walk(String dir, Map<String, String> out) {
        try {
            String[] items = getAssets().list(dir);
            if (items == null) return;
            for (String it : items) {
                String p = dir + "/" + it;
                String[] sub = getAssets().list(p);
                if (sub != null && sub.length > 0) walk(p, out);
                else out.put(p.toLowerCase(Locale.ROOT), p);
            }
        } catch (IOException ignored) {
        }
    }

    private static WebResourceResponse status(int code, String reason) {
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        return new WebResourceResponse("text/plain", "utf-8", code, reason, h,
                new ByteArrayInputStream(new byte[0]));
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
        }
    }

    private static boolean isText(String mime) {
        return mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json")
                || mime.contains("xml");
    }

    private static String mimeOf(String path) {
        int dot = path.lastIndexOf('.');
        String ext = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
        switch (ext) {
            case "html":
            case "htm":
                return "text/html";
            case "js":
            case "mjs":
                return "text/javascript";
            case "json":
                return "application/json";
            case "css":
                return "text/css";
            case "txt":
                return "text/plain";
            case "xml":
                return "application/xml";
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "gif":
                return "image/gif";
            case "webp":
                return "image/webp";
            case "svg":
                return "image/svg+xml";
            case "ico":
                return "image/x-icon";
            case "ogg":
            case "oga":
                return "audio/ogg";
            case "m4a":
                return "audio/mp4";
            case "mp3":
                return "audio/mpeg";
            case "wav":
                return "audio/wav";
            case "webm":
                return "video/webm";
            case "mp4":
                return "video/mp4";
            case "ttf":
                return "font/ttf";
            case "otf":
                return "font/otf";
            case "woff":
                return "font/woff";
            case "woff2":
                return "font/woff2";
            case "wasm":
                return "application/wasm";
            default:
                return "application/octet-stream"; // termasuk .rpgmvp/.rpgmvo/.rpgmvm
        }
    }

    /** Batasi stream untuk respons 206. */
    private static final class BoundedStream extends FilterInputStream {
        private long left;

        BoundedStream(InputStream in, long limit) {
            super(in);
            left = limit;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int r = super.read();
            if (r >= 0) left--;
            return r;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = super.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(super.available(), left);
        }
    }

    // ================= Splash =================

    private void hideSplash() {
        if (splashGone) return;
        splashGone = true;
        splash.animate().alpha(0f).setDuration(300)
                .withEndAction(() -> splash.setVisibility(View.GONE)).start();
    }

    // ================= Input =================

    @Override
    public void onKey(int keyCode, boolean down) {
        sendKey(keyCode, down);
    }

    @Override
    public void onOpenMenu() {
        showMenu();
    }

    /** Event mouse sungguhan (tool type MOUSE) dari kursor virtual. */
    @Override
    public void onMouse(float x, float y, int buttons) {
        long now = SystemClock.uptimeMillis();
        int old = mouseButtons;
        mouseButtons = buttons;
        int action;
        boolean touch = true;
        if (old == 0 && buttons != 0) {
            action = MotionEvent.ACTION_DOWN;
            mouseDownTime = now;
        } else if (old != 0 && buttons == 0) {
            action = MotionEvent.ACTION_UP;
        } else if (buttons != 0) {
            action = MotionEvent.ACTION_MOVE;
        } else {
            action = MotionEvent.ACTION_HOVER_MOVE;
            touch = false;
        }
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = x;
        pc.y = y;
        pc.pressure = buttons != 0 ? 1f : 0f;
        pc.size = 1f;
        MotionEvent ev = MotionEvent.obtain(touch ? mouseDownTime : now, now, action, 1,
                new MotionEvent.PointerProperties[]{pp}, new MotionEvent.PointerCoords[]{pc},
                0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        if (touch) webView.dispatchTouchEvent(ev);
        else webView.dispatchGenericMotionEvent(ev);
        ev.recycle();
    }

    @Override
    public void onRotate() {
        boolean portrait = !prefs.getBoolean("portrait", false);
        prefs.edit().putBoolean("portrait", portrait).apply();
        setRequestedOrientation(portrait ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    /** Roda mouse sungguhan (ACTION_SCROLL, AXIS_VSCROLL) di posisi kursor. */
    @Override
    public void onScroll(float x, float y, int dir) {
        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = x;
        pc.y = y;
        pc.setAxisValue(MotionEvent.AXIS_VSCROLL, dir);
        MotionEvent ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1,
                new MotionEvent.PointerProperties[]{pp}, new MotionEvent.PointerCoords[]{pc},
                0, mouseButtons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        webView.dispatchGenericMotionEvent(ev);
        ev.recycle();
    }

    private void sendKey(int code, boolean down) {
        long t = SystemClock.uptimeMillis();
        KeyEvent e = new KeyEvent(t, t, down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        webView.dispatchKeyEvent(e);
    }

    private void tapKey(int code) {
        sendKey(code, true);
        ui.postDelayed(() -> sendKey(code, false), 60);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // Tombol Back = Esc (menu / batal). Keluar lewat menu (tahan ikon mata).
        tapKey(KeyEvent.KEYCODE_ESCAPE);
    }

    // ================= Menu pengaturan =================

    private void showMenu() {
        String[] items = {
                "Ukuran tombol: " + pad.sizeLabel(),
                "Transparansi tombol: " + pad.opacityLabel(),
                "Getar saat ditekan: " + (pad.isHaptic() ? "Ya" : "Tidak"),
                "Muat ulang game",
                "Lihat log error JS",
                "Keluar"
        };
        new AlertDialog.Builder(this)
                .setTitle("Pengaturan")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            pad.cycleSize();
                            showMenu();
                            break;
                        case 1:
                            pad.cycleOpacity();
                            showMenu();
                            break;
                        case 2:
                            pad.toggleHaptic();
                            showMenu();
                            break;
                        case 3:
                            webView.reload();
                            break;
                        case 4:
                            showLog();
                            break;
                        case 5:
                            finishAndRemoveTask();
                            break;
                        default:
                            break;
                    }
                })
                .show();
    }

    private void showLog() {
        StringBuilder sb = new StringBuilder();
        for (String s : errorLog) sb.append("• ").append(s).append("\n\n");
        if (sb.length() == 0) sb.append("Tidak ada error JavaScript.");
        new AlertDialog.Builder(this)
                .setTitle("Log error JS")
                .setMessage(sb.toString())
                .setPositiveButton("Tutup", null)
                .show();
    }

    // ================= Cheat: saklar & variabel dari System.json =================

    private static final String CHEAT_READ_JS =
            "(function(){try{var S=window.$gameSwitches,V=window.$gameVariables;"
                    + "if(!S||!V)return null;return JSON.stringify({s:S._data,v:V._data});}catch(e){return null;}})()";

    private static final class CheatItem {
        final int id;
        final String label;

        CheatItem(int id, String name) {
            this.id = id;
            this.label = "#" + id + "  " + (name.isEmpty() ? "(tanpa nama)" : name);
        }
    }

    private final List<CheatItem> cheatSwitches = new ArrayList<>();
    private final List<CheatItem> cheatVars = new ArrayList<>();
    private boolean[] swOn = new boolean[0];
    private String[] varVal = new String[0];
    private boolean cheatNamesLoaded;
    private int cheatTab; // 0 = saklar, 1 = variabel

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onCheat() {
        if (!loadCheatNames()) {
            Toast.makeText(this, "System.json tidak ditemukan / tidak terbaca (bukan game RPG Maker?)",
                    Toast.LENGTH_LONG).show();
            return;
        }
        webView.evaluateJavascript(CHEAT_READ_JS, value -> {
            if (!parseCheatValues(value)) {
                Toast.makeText(MainActivity.this,
                        "Game belum dimulai. Mulai New Game atau muat save dulu.", Toast.LENGTH_LONG).show();
                return;
            }
            showCheat();
        });
    }

    private boolean loadCheatNames() {
        if (cheatNamesLoaded) return true;
        InputStream in = openAsset(ASSET_ROOT + "/data/System.json");
        if (in == null) return false;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            String txt = bos.toString("UTF-8");
            if (txt.startsWith("﻿")) txt = txt.substring(1);
            JSONObject root = new JSONObject(txt);
            fillCheat(cheatSwitches, root.optJSONArray("switches"));
            fillCheat(cheatVars, root.optJSONArray("variables"));
            cheatNamesLoaded = true;
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            closeQuietly(in);
        }
    }

    private static void fillCheat(List<CheatItem> out, JSONArray arr) {
        out.clear();
        if (arr == null) return;
        for (int i = 1; i < arr.length(); i++) {
            out.add(new CheatItem(i, arr.isNull(i) ? "" : arr.optString(i, "")));
        }
    }

    private boolean parseCheatValues(String value) {
        if (value == null || value.equals("null")) return false;
        try {
            Object o = new JSONTokener(value).nextValue();
            if (!(o instanceof String)) return false;
            JSONObject j = new JSONObject((String) o);
            JSONArray s = j.optJSONArray("s");
            JSONArray v = j.optJSONArray("v");
            int sn = cheatSwitches.isEmpty() ? 0 : cheatSwitches.get(cheatSwitches.size() - 1).id;
            int vn = cheatVars.isEmpty() ? 0 : cheatVars.get(cheatVars.size() - 1).id;
            swOn = new boolean[sn + 1];
            varVal = new String[vn + 1];
            for (int i = 1; i <= sn; i++) {
                swOn[i] = s != null && i < s.length() && !s.isNull(i) && s.optBoolean(i, false);
            }
            for (int i = 1; i <= vn; i++) {
                varVal[i] = (v == null || i >= v.length() || v.isNull(i)) ? "0" : String.valueOf(v.opt(i));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void cheatSetSwitch(int id, boolean on) {
        webView.evaluateJavascript("(function(){try{$gameSwitches.setValue(" + id + "," + on
                + ");$gameMap.requestRefresh();}catch(e){}})()", null);
    }

    private void cheatSetVar(int id, String num) {
        webView.evaluateJavascript("(function(){try{$gameVariables.setValue(" + id + "," + num
                + ");$gameMap.requestRefresh();}catch(e){}})()", null);
    }

    private static double parseNum(String s) {
        try {
            double d = Double.parseDouble(s.trim());
            return (Double.isNaN(d) || Double.isInfinite(d)) ? 0 : d;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String fmtNum(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        return String.valueOf(d);
    }

    private void showCheat() {
        final List<CheatItem> shown = new ArrayList<>();
        final BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return shown.size();
            }

            @Override
            public Object getItem(int position) {
                return shown.get(position);
            }

            @Override
            public long getItemId(int position) {
                return shown.get(position).id;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                final CheatItem it = shown.get(position);
                if (cheatTab == 0) {
                    CheckBox cb = (convertView instanceof CheckBox) ? (CheckBox) convertView
                            : new CheckBox(MainActivity.this);
                    cb.setOnCheckedChangeListener(null);
                    cb.setText(it.label);
                    cb.setTextColor(Color.WHITE);
                    cb.setPadding(dp(8), dp(10), dp(8), dp(10));
                    cb.setChecked(swOn[it.id]);
                    cb.setOnCheckedChangeListener((btn, on) -> {
                        swOn[it.id] = on;
                        cheatSetSwitch(it.id, on);
                    });
                    return cb;
                }
                TextView tv = (convertView != null && convertView.getClass() == TextView.class)
                        ? (TextView) convertView : new TextView(MainActivity.this);
                tv.setText(it.label + "   =   " + varVal[it.id]);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(16);
                tv.setPadding(dp(12), dp(12), dp(12), dp(12));
                return tv;
            }
        };

        final EditText search = new EditText(this);
        search.setHint("Cari nama atau nomor...");
        search.setHintTextColor(Color.GRAY);
        search.setTextColor(Color.WHITE);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);

        final Runnable refilter = () -> {
            String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            shown.clear();
            for (CheatItem it : (cheatTab == 0 ? cheatSwitches : cheatVars)) {
                if (q.isEmpty() || it.label.toLowerCase(Locale.ROOT).contains(q)) shown.add(it);
            }
            adapter.notifyDataSetChanged();
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                refilter.run();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        final Button tabS = new Button(this);
        final Button tabV = new Button(this);
        tabS.setText("Saklar (" + cheatSwitches.size() + ")");
        tabV.setText("Variabel (" + cheatVars.size() + ")");
        final ListView list = new ListView(this);
        list.setAdapter(adapter);
        final Runnable paintTabs = () -> {
            tabS.setAlpha(cheatTab == 0 ? 1f : 0.45f);
            tabV.setAlpha(cheatTab == 1 ? 1f : 0.45f);
        };
        tabS.setOnClickListener(v -> {
            cheatTab = 0;
            paintTabs.run();
            refilter.run();
            list.setSelection(0);
        });
        tabV.setOnClickListener(v -> {
            cheatTab = 1;
            paintTabs.run();
            refilter.run();
            list.setSelection(0);
        });
        list.setOnItemClickListener((parent, view, pos, id) -> {
            if (cheatTab == 1 && pos < shown.size()) editCheatVar(shown.get(pos), adapter);
        });

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.addView(tabS, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(tabV, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), 0);
        box.addView(tabs);
        box.addView(search);
        int listH = (int) (getResources().getDisplayMetrics().heightPixels * 0.45f);
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listH));

        paintTabs.run();
        refilter.run();

        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Cheat")
                .setView(box)
                .setPositiveButton("Tutup", null)
                .setNeutralButton("Segarkan", (d, w) -> onCheat())
                .create();
        dlg.setOnDismissListener(d -> webView.requestFocus());
        dlg.show();
    }

    private void editCheatVar(final CheatItem it, final BaseAdapter adapter) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        et.setText(varVal[it.id]);
        et.setSelectAllOnFocus(true);
        et.setTextColor(Color.WHITE);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int[] deltas = {-100, -10, -1, 1, 10, 100};
        for (int dv : deltas) {
            final int delta = dv;
            Button b = new Button(this);
            b.setText(delta > 0 ? "+" + delta : String.valueOf(delta));
            b.setAllCaps(false);
            b.setOnClickListener(v -> {
                et.setText(fmtNum(parseNum(et.getText().toString()) + delta));
                et.setSelection(et.length());
            });
            row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        box.addView(et);
        box.addView(row);

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(it.label)
                .setView(box)
                .setPositiveButton("Set", (dlg, w) -> {
                    String s = fmtNum(parseNum(et.getText().toString()));
                    varVal[it.id] = s;
                    cheatSetVar(it.id, s);
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    // ================= Lifecycle =================

    @SuppressWarnings("deprecation")
    private void applyImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        webView.resumeTimers();
        webView.requestFocus();
    }

    @Override
    protected void onPause() {
        pad.releaseAll();
        webView.onPause();
        webView.pauseTimers();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        splashGone = true;
        ui.removeCallbacksAndMessages(null);
        rootView.removeAllViews();
        webView.destroy();
        super.onDestroy();
    }
}
