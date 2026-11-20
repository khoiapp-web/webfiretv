package com.angga.maxihop;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.webkit.RenderProcessGoneDetail;
import android.graphics.Typeface;
import android.view.Gravity;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen MaxiHop player for Fire TV.
 * - Opens START_URL in a WebView and lets the page use the USB webcam (and mic).
 * - D-pad moves an on-screen pointer, OK/Select clicks, Back goes back, Menu reloads.
 */
public class MainActivity extends Activity {

    private static final String START_URL = "https://maxihop.com/";
    /** Only pages on these hosts get camera/mic access. */
    private static final String[] TRUSTED_HOSTS = {"maxihop.com"};

    private static final int REQ_MEDIA = 42;
    private static final long CURSOR_HIDE_MS = 4000;

    private WebView webView;
    private CursorView cursor;
    private TextView logView;
    private CameraBridge camBridge;
    private String camShim = "";
    private static final StringBuilder log = new StringBuilder();
    private SharedPreferences prefs;
    private boolean safeMode;
    /** Safe mode: run body tracking on the main thread without the graphics chip (less memory, no GPU crashes). */
    private static final String SAFE_JS = "(function(){if(window.__fireSafe)return;window.__fireSafe=true;"
            + "window.Worker=function(){throw new Error('workers off (safe mode)');};"
            + "if(window.OffscreenCanvas){var g=OffscreenCanvas.prototype.getContext;"
            + "OffscreenCanvas.prototype.getContext=function(t,o){if(/webgl/.test(t))return null;return g.call(this,t,o);};}"
            + "console.log('[diag] Safe mode active');})();";
    private PermissionRequest pendingRequest;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideCursor = () -> cursor.setVisible(false);

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("maxihop", MODE_PRIVATE);
        safeMode = prefs.getBoolean("safeMode", false);
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((th, ex) -> {
            java.io.StringWriter sw = new java.io.StringWriter();
            ex.printStackTrace(new java.io.PrintWriter(sw));
            String st = sw.toString();
            prefs.edit().putString("lastCrash", st.length() > 600 ? st.substring(0, 600) : st).commit();
            if (prev != null) prev.uncaughtException(th, ex);
        });
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        webView = new WebView(this);
        cursor = new CursorView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(cursor, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        logView = new TextView(this);
        logView.setTextColor(Color.WHITE);
        logView.setBackgroundColor(Color.argb(200, 0, 0, 0));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(12);
        logView.setPadding(16, 8, 16, 8);
        logView.setGravity(Gravity.BOTTOM);
        logView.setVisibility(android.view.View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        root.addView(logView, lp);
        setContentView(root);
        logLine("App 1.3 | Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") | " + webViewVersion());
        logLine("Play/Pause = camera test, Rewind = show/hide panel, Fast-forward = safe mode on/off");
        logLine("Safe mode: " + (safeMode ? "ON" : "off"));
        String lastCrash = prefs.getString("lastCrash", null);
        if (lastCrash != null) {
            logLine("LAST CRASH: " + lastCrash);
            prefs.edit().remove("lastCrash").apply();
        }

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);

        // Native webcam bridge: the Fire TV web engine can't see USB webcams, so the app reads it itself.
        camBridge = new CameraBridge(this, this::logLine);
        webView.addJavascriptInterface(camBridge, "FireCam");
        camShim = readAsset("firecam-shim.js");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false; // keep every link inside the app, never hand off to Silk
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (isTrusted(Uri.parse(url)) && !camShim.isEmpty()) {
                    if (safeMode) view.evaluateJavascript(SAFE_JS, null);
                    view.evaluateJavascript(camShim, null);
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // The web engine died (graphics crash or out of memory). Keep the app alive,
                // switch to safe mode and start over instead of closing.
                logLine("WEB ENGINE " + (detail.didCrash() ? "CRASHED" : "was killed (low memory)")
                        + " -> switching safe mode ON and reloading");
                camBridge.stop();
                prefs.edit().putBoolean("safeMode", true).apply();
                handler.post(MainActivity.this::recreate);
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    Toast.makeText(MainActivity.this,
                            "Can't load MaxiHop. Check Wi-Fi, then press Menu (≡) to retry.",
                            Toast.LENGTH_LONG).show();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> handleWebPermission(request));
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                String msg = m.message();
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR
                        || m.messageLevel() == ConsoleMessage.MessageLevel.WARNING
                        || msg.startsWith("[diag]")) {
                    logLine(m.messageLevel().name().charAt(0) + " " + msg);
                }
                return true;
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (request == pendingRequest) pendingRequest = null;
            }
        });

        // Ask for camera + mic up front so the first game starts without a prompt mid-play.
        if (Build.VERSION.SDK_INT >= 23 && !hasMediaPermissions()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO}, REQ_MEDIA);
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(START_URL);
        }
        bumpCursor();
    }

    // ---------- Camera / mic permission bridge ----------

    private boolean isTrusted(Uri origin) {
        if (origin == null || origin.getHost() == null) return false;
        String host = origin.getHost().toLowerCase();
        for (String h : TRUSTED_HOSTS) {
            if (host.equals(h) || host.endsWith("." + h)) return true;
        }
        return false;
    }

    private boolean hasPermission(String p) {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasMediaPermissions() {
        return hasPermission(Manifest.permission.CAMERA) && hasPermission(Manifest.permission.RECORD_AUDIO);
    }

    private void handleWebPermission(PermissionRequest request) {
        logLine("Page asked for: " + java.util.Arrays.toString(request.getResources()) + " from " + request.getOrigin());
        if (!isTrusted(request.getOrigin())) {
            logLine("Denied (not a trusted site)");
            request.deny();
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && !hasPermission(Manifest.permission.CAMERA)) {
                missing.add(Manifest.permission.CAMERA);
            } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
                missing.add(Manifest.permission.RECORD_AUDIO);
            }
        }
        if (missing.isEmpty() || Build.VERSION.SDK_INT < 23) {
            grantAllowed(request);
        } else {
            pendingRequest = request;
            requestPermissions(missing.toArray(new String[0]), REQ_MEDIA);
        }
    }

    private void grantAllowed(PermissionRequest request) {
        List<String> ok = new ArrayList<>();
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && hasPermission(Manifest.permission.CAMERA)) {
                ok.add(r);
            } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && hasPermission(Manifest.permission.RECORD_AUDIO)) {
                ok.add(r);
            }
        }
        if (ok.isEmpty()) {
            request.deny();
            logLine("Denied: app has no camera permission");
            Toast.makeText(this, "Camera permission is off. Turn it on in Settings > Applications > MaxiHop.",
                    Toast.LENGTH_LONG).show();
        } else {
            request.grant(ok.toArray(new String[0]));
            logLine("Granted: " + ok);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MEDIA && pendingRequest != null) {
            PermissionRequest r = pendingRequest;
            pendingRequest = null;
            grantAllowed(r);
        }
    }

    // ---------- Remote control: pointer, click, back, reload ----------

    private void bumpCursor() {
        cursor.setVisible(true);
        handler.removeCallbacks(hideCursor);
        handler.postDelayed(hideCursor, CURSOR_HIDE_MS);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int dx = 0, dy = 0;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:    dy = -1; break;
            case KeyEvent.KEYCODE_DPAD_DOWN:  dy = 1;  break;
            case KeyEvent.KEYCODE_DPAD_LEFT:  dx = -1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dx = 1;  break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down && e.getRepeatCount() == 0) {
                    if (cursor.isShown() && cursor.isPointerVisible()) {
                        clickAt(cursor.getPointerX(), cursor.getPointerY());
                    }
                    bumpCursor();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (down && e.getRepeatCount() == 0) runCameraTest();
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down && e.getRepeatCount() == 0) {
                    logView.setVisibility(logView.getVisibility() == android.view.View.VISIBLE
                            ? android.view.View.GONE : android.view.View.VISIBLE);
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down && e.getRepeatCount() == 0) {
                    safeMode = !safeMode;
                    prefs.edit().putBoolean("safeMode", safeMode).apply();
                    logLine("Safe mode " + (safeMode ? "ON" : "off") + " - reloading");
                    camBridge.stop();
                    webView.reload();
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (down && e.getRepeatCount() == 0) {
                    webView.reload();
                    Toast.makeText(this, "Reloading…", Toast.LENGTH_SHORT).show();
                }
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
        if (down) {
            // Speeds up the longer the button is held.
            int step = Math.min(12 + e.getRepeatCount() * 6, 70);
            boolean hitEdge = cursor.moveBy(dx * step, dy * step);
            if (hitEdge) {
                webView.evaluateJavascript(
                        "window.scrollBy(" + (dx * 150) + "," + (dy * 150) + ")", null);
            }
            bumpCursor();
        }
        return true;
    }

    private void clickAt(float x, float y) {
        long t = SystemClock.uptimeMillis();
        MotionEvent downEv = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent upEv = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0);
        downEv.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        upEv.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        webView.dispatchTouchEvent(downEv);
        webView.dispatchTouchEvent(upEv);
        downEv.recycle();
        upEv.recycle();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ---------- Diagnostics ----------

    private String webViewVersion() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                PackageInfo p = WebView.getCurrentWebViewPackage();
                if (p != null) return p.packageName + " " + p.versionName;
            }
        } catch (Throwable ignored) { }
        return "WebView (version unknown)";
    }

    private void logLine(String line) {
        runOnUiThread(() -> {
            log.append(line).append('\n');
            String[] lines = log.toString().split("\n");
            if (lines.length > 14) {
                log.setLength(0);
                for (int i = lines.length - 14; i < lines.length; i++) log.append(lines[i]).append('\n');
            }
            logView.setText(log.toString().trim());
            logView.setVisibility(android.view.View.VISIBLE);
        });
    }

    /** Tries the camera directly, outside MaxiHop, and reports each step on screen. */
    private void runCameraTest() {
        logLine("--- Camera test ---");
        logLine(camBridge.describe());
        String js = "(async()=>{const L=m=>console.log('[diag] '+m);"
                + "try{L('UA '+navigator.userAgent.replace(/.*Chrome\\//,'Chrome/').slice(0,40));"
                + "L('secure='+window.isSecureContext+' mediaDevices='+!!navigator.mediaDevices);"
                + "const d=await navigator.mediaDevices.enumerateDevices();"
                + "L('devices: '+d.map(x=>x.kind+(x.label?'('+x.label.slice(0,20)+')':'')).join(', '));"
                + "const t=new Promise((_,r)=>setTimeout(()=>r(new Error('TIMEOUT after 10s')),10000));"
                + "const s=await Promise.race([navigator.mediaDevices.getUserMedia({video:true,audio:false}),t]);"
                + "const v=s.getVideoTracks()[0];const st=v.getSettings();"
                + "L('CAMERA OK: '+v.label+' '+st.width+'x'+st.height+'@'+st.frameRate);"
                + "s.getTracks().forEach(x=>x.stop());"
                + "const s2=await Promise.race([navigator.mediaDevices.getUserMedia({video:{width:{ideal:1280,max:1920},height:{ideal:720,max:1080},frameRate:{ideal:30},facingMode:'user'}}),new Promise((_,r)=>setTimeout(()=>r(new Error('TIMEOUT after 10s')),10000))]);"
                + "L('MaxiHop settings OK');s2.getTracks().forEach(x=>x.stop());"
                + "L('webgl2='+!!document.createElement('canvas').getContext('webgl2')+' wasm='+(typeof WebAssembly));"
                + "}catch(e){L('FAILED: '+e.name+' - '+e.message);}})()";
        webView.evaluateJavascript(js, null);
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            logLine("Could not load " + name + ": " + e);
            return "";
        }
    }

    // ---------- Lifecycle ----------

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onPause() {
        // Releases the webcam when you leave the app.
        webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        camBridge.stop();
        ((android.view.ViewGroup) webView.getParent()).removeView(webView);
        webView.destroy();
        super.onDestroy();
    }
}
