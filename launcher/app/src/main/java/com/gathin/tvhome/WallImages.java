package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The pictures on the walls. A picture is downloaded the first time its tile comes near the screen, kept on the TV,
 * and held in memory at the size it is shown: small for a tile, large for a double tile or the one lifted off the
 * wall. The newest request goes first, so the tiles on screen fill in before the ones already passed.
 */
final class WallImages {

    /** Wider than this on the screen and the picture is decoded large. */
    static final int LARGE = 260;
    private static final long RETRY_MS = 60_000;

    private final File dir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> failed = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(3, 3, 0, TimeUnit.MILLISECONDS,
            new LinkedBlockingDeque<Runnable>() {
                @Override
                public boolean offer(Runnable r) {
                    return offerFirst(r);
                }
            });
    private Runnable onLoaded = () -> {
    };

    WallImages(File dir) {
        this.dir = dir;
        //noinspection ResultOfMethodCallIgnored
        new File(dir, "posters").mkdirs();
        int budget = (int) Math.min(64 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 4);
        cache = new LruCache<String, Bitmap>(budget) {
            @Override
            protected int sizeOf(String key, Bitmap b) {
                return b.getByteCount() / 1024;
            }
        };
    }

    void onLoaded(Runnable r) {
        onLoaded = r;
    }

    /** The picture if it is in memory, else the other size as a stand-in (or null) while this one loads. */
    Bitmap get(WallData.Tile t, boolean large) {
        String key = t.id + (large ? "@L" : "@S");
        Bitmap b = cache.get(key);
        if (b != null) return b;
        load(t, large, key, null);
        return cache.get(t.id + (large ? "@S" : "@L"));
    }

    /** Puts the picture into an image view when it is ready, if the view still wants this one. */
    void into(ImageView view, WallData.Tile t) {
        String key = t.id + "@S";
        view.setTag(key);
        Bitmap b = cache.get(key);
        view.setImageBitmap(b);
        if (b == null) load(t, false, key, view);
    }

    private void load(WallData.Tile t, boolean large, String key, ImageView view) {
        if (t.img == null) return;
        Long f = failed.get(t.id);
        if (f != null && System.currentTimeMillis() - f < RETRY_MS) return;
        if (view == null && !pending.add(key)) return;
        pool.execute(() -> {
            Bitmap b = cache.get(key);
            try {
                if (b == null) {
                    File file = WallData.picture(dir, t);
                    if (!file.isFile()) download(t, file);
                    b = decode(file, t.video() ? (large ? 640 : 320) : (large ? 400 : 200), large);
                    if (b != null) cache.put(key, b);
                    else failed.put(t.id, System.currentTimeMillis());
                }
            } catch (OutOfMemoryError e) {
                cache.evictAll();
            } finally {
                pending.remove(key);
            }
            final Bitmap got = b;
            if (got != null) {
                main.post(() -> {
                    if (view == null) onLoaded.run();
                    else if (key.equals(view.getTag())) view.setImageBitmap(got);
                });
            }
        });
    }

    private static Bitmap decode(File f, int width, boolean large) {
        if (!f.isFile()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();   // half a file: fetch it again next time
            return null;
        }
        int sample = 1;
        while (o.outWidth / (sample * 2) >= width) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = large ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** IMDb's image server scales on request: 400 px wide is sharp for a double tile and light to keep. */
    private static void download(WallData.Tile t, File to) {
        if (t.video()) {
            if (!fetch(t.img, to)) fetch(t.img.replace("/hq720.jpg", "/mqdefault.jpg"), to);
        } else {
            fetch(t.img.replace("._V1_", "._V1_QL75_UX400_"), to);
        }
    }

    static boolean fetch(String url, File to) {
        File tmp = new File(to.getPath() + ".tmp");
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(20_000);
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            } finally {
                conn.disconnect();
            }
            return tmp.renameTo(to);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
    }

    void release() {
        pool.shutdownNow();
        main.removeCallbacksAndMessages(null);
        cache.evictAll();
    }
}
