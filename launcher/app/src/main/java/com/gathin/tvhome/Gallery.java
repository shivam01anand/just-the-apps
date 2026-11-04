package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The screensaver's paintings (res/raw/gallery.json): public-domain masterpieces, each one image from Wikimedia
 * Commons at a standard thumbnail width (1920 landscape, 1280 portrait), chosen without nudes or devotional
 * scenes. They show in a shuffled order that only repeats once all have been seen. At most three are kept on
 * the TV at a time: the one before, the one showing and the next, which is always downloaded ahead.
 */
final class Gallery {
    private static final String TAG = "TvHome.Gallery";
    /** Wikimedia asks every client to say who it is. */
    private static final String UA = "hisense-just-the-apps/1.0 (https://github.com/shivam01anand/hisense-just-the-apps)";
    private static final AtomicBoolean preparing = new AtomicBoolean(false);
    private static List<Art> all;

    static final class Art {
        final String title, artist, url;
        final int year;

        Art(String title, String artist, int year, String url) {
            this.title = title;
            this.artist = artist;
            this.year = year;
            this.url = url;
        }
    }

    private Gallery() {
    }

    static synchronized List<Art> all(Context c) {
        if (all != null) return all;
        List<Art> out = new ArrayList<>();
        try (InputStream in = c.getResources().openRawResource(R.raw.gallery)) {
            JSONArray a = new JSONArray(new String(readAll(in), "UTF-8"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Art(o.getString("title"), o.getString("artist"), o.optInt("year"), o.getString("url")));
            }
        } catch (Exception e) {
            Log.w(TAG, "gallery list unreadable", e);
        }
        all = out;
        return all;
    }

    static boolean enabled(Context c) {
        return Config.load(c).screensaverGallery && !all(c).isEmpty();
    }

    /** The next painting if it is on the TV already (the order moves on), or null to show the clock instead. */
    static Art take(Context c) {
        List<Art> arts = all(c);
        if (arts.isEmpty()) return null;
        SharedPreferences p = prefs(c);
        List<Integer> order = order(p, arts.size());
        int pos = p.getInt("pos", 0) % order.size();
        Art a = arts.get(order.get(pos));
        if (!file(c, a).isFile()) return null;
        if (pos + 1 >= order.size()) {
            p.edit().remove("order").putInt("pos", 0).apply();   // all seen: a new order next time
        } else {
            p.edit().putInt("pos", pos + 1).apply();
        }
        return a;
    }

    static File file(Context c, Art a) {
        return new File(dir(c), Integer.toHexString(a.url.hashCode()) + ".jpg");
    }

    /**
     * On a background thread: downloads the next painting if it is missing, and deletes all but the last shown,
     * the next and the one after. Cheap to call often; it does nothing when the next one is there.
     */
    static void prepare(Context context) {
        if (!enabled(context) || !preparing.compareAndSet(false, true)) return;
        final Context c = context.getApplicationContext();
        new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                List<Art> arts = all(c);
                SharedPreferences p = prefs(c);
                List<Integer> order = order(p, arts.size());
                int pos = p.getInt("pos", 0) % order.size();
                List<File> keep = new ArrayList<>();
                for (int k = -1; k <= 1; k++) {
                    Art a = arts.get(order.get(((pos + k) % order.size() + order.size()) % order.size()));
                    File f = file(c, a);
                    keep.add(f);
                    if (k >= 0 && !f.isFile()) download(a.url, f);
                }
                File[] files = dir(c).listFiles();
                for (int i = 0; files != null && i < files.length; i++) {
                    //noinspection ResultOfMethodCallIgnored
                    if (!keep.contains(files[i])) files[i].delete();
                }
            } catch (Exception e) {
                Log.w(TAG, "could not fetch the next painting", e);
            } finally {
                preparing.set(false);
            }
        }, "gallery").start();
    }

    private static void download(String url, File f) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("User-Agent", UA);
        try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        } finally {
            conn.disconnect();
        }
        if (!tmp.renameTo(f)) throw new java.io.IOException("rename failed");
    }

    /** A shuffled order of every painting, kept until all have shown. */
    private static List<Integer> order(SharedPreferences p, int n) {
        String saved = p.getString("order", "");
        List<Integer> out = new ArrayList<>();
        for (String s : saved.split(",")) {
            try {
                int i = Integer.parseInt(s);
                if (i >= 0 && i < n) out.add(i);
            } catch (NumberFormatException ignored) {
            }
        }
        if (out.size() == n) return out;
        out.clear();
        for (int i = 0; i < n; i++) out.add(i);
        Collections.shuffle(out);
        StringBuilder sb = new StringBuilder();
        for (int i : out) sb.append(sb.length() > 0 ? "," : "").append(i);
        p.edit().putString("order", sb.toString()).putInt("pos", 0).apply();
        return out;
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "gallery");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("gallery", Context.MODE_PRIVATE);
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
