package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.io.File;

/**
 * The home screen's backdrop: the focused title's poster, shrunk to a few dozen pixels and blurred, so that
 * stretched over the screen it is only its colours, the way a cinema screen lights the room. Every bitmap here
 * is tiny (the largest is 64 × 36); the GPU does the stretching, so a change costs one small texture upload.
 */
final class Backdrop {
    private static final int W = 27, H = 40;   // 2:3, like the posters

    private Backdrop() {
    }

    /** The poster's colours, blurred; null when there is no poster file yet. Off the main thread. */
    static Bitmap blurred(File poster) {
        if (poster == null || !poster.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 4;   // 300 × 444 decodes as 75 × 111: a few KB, a millisecond or two
        Bitmap src;
        try {
            src = BitmapFactory.decodeFile(poster.getPath(), o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
        if (src == null) return null;
        Bitmap out = blurred(src);
        src.recycle();
        return out;
    }

    /** A bitmap's colours, blurred, as above; the source is left as it is. */
    static Bitmap blurred(Bitmap src) {
        Bitmap small = Bitmap.createScaledBitmap(src, W, H, true);
        int[] px = new int[W * H];
        small.getPixels(px, 0, W, 0, 0, W, H);
        for (int pass = 0; pass < 3; pass++) px = boxBlur(px, W, H, 2);
        // A little more colour: under the scrim a poster's own tones turn muddy.
        float[] hsv = new float[3];
        for (int i = 0; i < px.length; i++) {
            Color.colorToHSV(px[i], hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.3f);
            px[i] = Color.HSVToColor(hsv);
        }
        Bitmap out = Bitmap.createBitmap(px, W, H, Bitmap.Config.ARGB_8888);
        if (small != src) small.recycle();
        return out;
    }

    /** One horizontal and one vertical pass of a box blur, edges clamped. */
    private static int[] boxBlur(int[] in, int w, int h, int r) {
        int[] tmp = new int[in.length];
        int[] out = new int[in.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) tmp[y * w + x] = average(in, w, h, x, y, r, true);
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) out[y * w + x] = average(tmp, w, h, x, y, r, false);
        }
        return out;
    }

    private static int average(int[] px, int w, int h, int x, int y, int r, boolean horizontal) {
        int rs = 0, gs = 0, bs = 0, n = 0;
        for (int d = -r; d <= r; d++) {
            int xx = horizontal ? Math.max(0, Math.min(w - 1, x + d)) : x;
            int yy = horizontal ? y : Math.max(0, Math.min(h - 1, y + d));
            int c = px[yy * w + xx];
            rs += (c >> 16) & 0xFF;
            gs += (c >> 8) & 0xFF;
            bs += c & 0xFF;
            n++;
        }
        return 0xFF000000 | ((rs / n) << 16) | ((gs / n) << 8) | (bs / n);
    }

    /**
     * The room with nothing playing: deep plum rather than black, a little wine-coloured light from the top
     * left and a little amber from the bottom right, like the walls of an old cinema before the film.
     */
    static Bitmap base() {
        int w = 64, h = 36;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, 0xFF1E1520, 0xFF0C090E, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new RadialGradient(w * 0.18f, h * 0.05f, w * 0.75f, 0x803D1F33, 0x003D1F33, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new RadialGradient(w * 0.92f, h * 1.05f, w * 0.6f, 0x4D4A3018, 0x004A3018, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        return b;
    }

    /** Darker at the top (the clock) and the bottom (the apps), lighter around the posters. */
    static Bitmap scrim() {
        int h = 128;
        Bitmap b = Bitmap.createBitmap(1, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0xA6000000, 0x73000000, 0x59000000, 0x99000000, 0xD9000000},
                new float[]{0f, 0.16f, 0.36f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 1, h, p);
        return b;
    }
}
