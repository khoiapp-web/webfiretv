package com.angga.maxihop;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Size;
import android.webkit.JavascriptInterface;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * Reads the USB webcam through Android's own camera system (the same one Zoom uses)
 * and hands JPEG frames to the web page, because the Fire TV web engine can't see USB webcams.
 * Exposed to JavaScript as window.FireCam.
 */
public class CameraBridge {

    public interface Logger { void log(String line); }

    private final Context ctx;
    private final Logger logger;
    private HandlerThread thread;
    private Handler bg;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private volatile String latest = null;
    private volatile long seq = 0;
    private int width, height;
    private boolean firstFrameLogged;

    public CameraBridge(Context ctx, Logger logger) {
        this.ctx = ctx.getApplicationContext();
        this.logger = logger;
    }

    private CameraManager manager() {
        return (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
    }

    private static String facingName(Integer f) {
        if (f == null) return "?";
        switch (f) {
            case CameraCharacteristics.LENS_FACING_FRONT: return "front";
            case CameraCharacteristics.LENS_FACING_BACK: return "back";
            case 2: return "external";
            default: return String.valueOf(f);
        }
    }

    /** Lists the cameras Android can see (for the diagnostics panel). */
    @JavascriptInterface
    public String describe() {
        try {
            String[] ids = manager().getCameraIdList();
            if (ids.length == 0) return "Android camera list: none";
            StringBuilder sb = new StringBuilder("Android cameras: ");
            for (String id : ids) {
                CameraCharacteristics c = manager().getCameraCharacteristics(id);
                Integer level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                sb.append('#').append(id).append(' ').append(facingName(c.get(CameraCharacteristics.LENS_FACING)))
                        .append(" level=").append(level).append("; ");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "Android camera list error: " + t;
        }
    }

    private String pickCamera() throws Exception {
        String[] ids = manager().getCameraIdList();
        for (String id : ids) {
            Integer f = manager().getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (f != null && f == 2) return id; // LENS_FACING_EXTERNAL
        }
        return ids.length > 0 ? ids[0] : null;
    }

    @JavascriptInterface
    public boolean available() {
        try { return pickCamera() != null; } catch (Throwable t) { return false; }
    }

    /** Opens the webcam. Returns "WIDTHxHEIGHT" or "error: ...". Frames follow shortly after. */
    @SuppressLint("MissingPermission")
    @JavascriptInterface
    public synchronized String start(int wantW, int wantH) {
        try {
            if (device != null) return width + "x" + height;
            String id = pickCamera();
            if (id == null) return "error: Android sees no camera";
            CameraCharacteristics c = manager().getCameraCharacteristics(id);
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size best = null;
            if (map != null) {
                for (Size s : map.getOutputSizes(ImageFormat.YUV_420_888)) {
                    if (best == null || score(s, wantW, wantH) < score(best, wantW, wantH)) best = s;
                }
            }
            if (best == null) best = new Size(640, 480);
            width = best.getWidth();
            height = best.getHeight();

            thread = new HandlerThread("firecam");
            thread.start();
            bg = new Handler(thread.getLooper());
            reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(this::onImage, bg);
            firstFrameLogged = false;
            logger.log("Opening camera #" + id + " at " + width + "x" + height);

            manager().openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    device = d;
                    try {
                        d.createCaptureSession(Collections.singletonList(reader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override public void onConfigured(CameraCaptureSession s) {
                                        session = s;
                                        try {
                                            CaptureRequest.Builder b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                            b.addTarget(reader.getSurface());
                                            s.setRepeatingRequest(b.build(), null, bg);
                                        } catch (Throwable t) { logger.log("Camera request failed: " + t); }
                                    }
                                    @Override public void onConfigureFailed(CameraCaptureSession s) {
                                        logger.log("Camera session failed");
                                    }
                                }, bg);
                    } catch (Throwable t) { logger.log("Camera session error: " + t); }
                }
                @Override public void onDisconnected(CameraDevice d) { logger.log("Camera disconnected"); d.close(); device = null; }
                @Override public void onError(CameraDevice d, int error) { logger.log("Camera error " + error + " (busy? close Zoom)"); d.close(); device = null; }
            }, bg);
            return width + "x" + height;
        } catch (Throwable t) {
            return "error: " + t.getMessage();
        }
    }

    private static int score(Size s, int w, int h) {
        return Math.abs(s.getWidth() - w) + Math.abs(s.getHeight() - h);
    }

    private void onImage(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            byte[] nv21 = toNV21(img);
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            new YuvImage(nv21, ImageFormat.NV21, img.getWidth(), img.getHeight(), null)
                    .compressToJpeg(new Rect(0, 0, img.getWidth(), img.getHeight()), 70, out);
            latest = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            seq++;
            if (!firstFrameLogged) { firstFrameLogged = true; logger.log("Camera frames flowing ✓"); }
        } catch (Throwable t) {
            logger.log("Frame error: " + t);
        } finally {
            if (img != null) img.close();
        }
    }

    private static byte[] toNV21(Image image) {
        int w = image.getWidth(), h = image.getHeight();
        byte[] out = new byte[w * h * 3 / 2];
        Image.Plane[] p = image.getPlanes();
        ByteBuffer y = p[0].getBuffer();
        int yRow = p[0].getRowStride(), yPix = p[0].getPixelStride();
        int pos = 0;
        for (int r = 0; r < h; r++) {
            int base = r * yRow;
            if (yPix == 1) {
                y.position(base);
                y.get(out, pos, w);
                pos += w;
            } else {
                for (int c = 0; c < w; c++) out[pos++] = y.get(base + c * yPix);
            }
        }
        ByteBuffer u = p[1].getBuffer(), v = p[2].getBuffer();
        int uvRow = p[1].getRowStride(), uvPix = p[1].getPixelStride();
        for (int r = 0; r < h / 2; r++) {
            for (int c = 0; c < w / 2; c++) {
                int i = r * uvRow + c * uvPix;
                out[pos++] = v.get(i);
                out[pos++] = u.get(i);
            }
        }
        return out;
    }

    /** Returns "" if no frame newer than lastSeq, else "seq,base64jpeg". */
    @JavascriptInterface
    public String frame(long lastSeq) {
        String f = latest;
        long s = seq;
        if (f == null || s == lastSeq) return "";
        return s + "," + f;
    }

    @JavascriptInterface
    public synchronized void stop() {
        try { if (session != null) session.close(); } catch (Throwable ignored) { }
        try { if (device != null) device.close(); } catch (Throwable ignored) { }
        try { if (reader != null) reader.close(); } catch (Throwable ignored) { }
        if (thread != null) thread.quitSafely();
        session = null; device = null; reader = null; thread = null; latest = null;
    }
}
