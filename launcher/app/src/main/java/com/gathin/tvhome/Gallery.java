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
        final String title, artist, url, when;
        final int year;
        /** One of the household's own paintings (config.json "screensaver_own"), hung between the masterpieces. */
        final boolean own;

        Art(String title, String artist, int year, String url) {
            this(title, artist, year, url, year > 0 ? String.valueOf(year) : "", false);
        }

        Art(String title, String artist, int year, String url, String when, boolean own) {
            this.title = title;
            this.artist = artist;
            this.year = year;
            this.url = url;
            this.when = when;
            this.own = own;
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
        // A painter's own work, from config.json: title, artist, when ("c. 2016–2018"), url. Newest first. Whether it
        // hangs is the mode (masters, mix or own), set from the screensaver with Left/Right or by tools/own-paintings.sh.
        JSONObject cfg = Config.overlay(c);
        JSONArray own = cfg != null ? cfg.optJSONArray("screensaver_own") : null;
        for (int i = 0; own != null && i < own.length(); i++) {
            JSONObject o = own.optJSONObject(i);
            if (o == null || o.optString("url").isEmpty()) continue;
            out.add(new Art(o.optString("title"), o.optString("artist"), 0, o.optString("url"), o.optString("when"), true));
        }
        all = out;
        return all;
    }

    // ---- what hangs: the masters, a mix, or only the household's own ---------------------------

    static final String MASTERS = "masters", MIX = "mix", OWN = "own";
    private static final String[] MODES = {MASTERS, MIX, OWN};

    static boolean hasOwn(Context c) {
        for (Art a : all(c)) if (a.own) return true;
        return false;
    }

    /** The painter's name on their own works, for the mode label; "" without any. */
    static String ownArtist(Context c) {
        for (Art a : all(c)) if (a.own) return a.artist;
        return "";
    }

    /** The mode chosen last, else config.json's "screensaver_own_on" (true: mix), else the masters alone. */
    static String mode(Context c) {
        if (!hasOwn(c)) return MASTERS;
        String m = prefs(c).getString("mode", null);
        if (m != null && java.util.Arrays.asList(MODES).contains(m)) return m;
        JSONObject cfg = Config.overlay(c);
        return cfg != null && cfg.optBoolean("screensaver_own_on", false) ? MIX : MASTERS;
    }

    static void setMode(Context c, String m) {
        if (!java.util.Arrays.asList(MODES).contains(m)) return;
        prefs(c).edit().putString("mode", m).remove("order").putInt("pos", 0).apply();
    }

    /** Left (-1) or Right (+1) from the current mode, stopping at the ends: masters, mix, own. */
    static String step(Context c, int by) {
        int i = java.util.Arrays.asList(MODES).indexOf(mode(c));
        String next = MODES[Math.max(0, Math.min(MODES.length - 1, i + by))];
        setMode(c, next);
        return next;
    }

    static boolean enabled(Context c) {
        return Config.load(c).screensaverGallery && !all(c).isEmpty();
    }

    /** The next painting if it is on the TV already (the order moves on), or null to show the clock instead. */
    static Art take(Context c) {
        List<Art> arts = all(c);
        if (arts.isEmpty()) return null;
        SharedPreferences p = prefs(c);
        List<Integer> order = order(c, p, arts);
        int pos = p.getInt("pos", 0) % order.size();
        Art a = arts.get(order.get(pos));
        if (!file(c, a).isFile()) {
            // Not downloaded yet (just after a change of mode): hang the next one in this order that is, so the
            // gallery still opens; the order catches up as the next ones arrive.
            for (int k = 1; k < order.size(); k++) {
                Art b = arts.get(order.get((pos + k) % order.size()));
                if (file(c, b).isFile()) return b;
            }
            return null;
        }
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
                List<Integer> order = order(c, p, arts);
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

    /**
     * The order paintings hang in, kept until all have shown. Masters: every masterpiece, shuffled. Own: the
     * household's own, newest first. Mix: one of theirs after every two masterpieces, newest first, starting again
     * from the newest when they run out.
     */
    private static List<Integer> order(Context c, SharedPreferences p, List<Art> arts) {
        int n = arts.size();
        String mode = mode(c);
        List<Integer> masters = new ArrayList<>(), mine = new ArrayList<>();
        for (int i = 0; i < n; i++) (arts.get(i).own ? mine : masters).add(i);
        if (MASTERS.equals(mode)) mine.clear();
        if (OWN.equals(mode) && !mine.isEmpty()) masters.clear();
        String made = n + ":" + mine.size() + ":" + mode;
        String saved = p.getString("order", "");
        List<Integer> out = new ArrayList<>();
        for (String s : saved.split(",")) {
            try {
                int i = Integer.parseInt(s);
                if (i >= 0 && i < n) out.add(i);
            } catch (NumberFormatException ignored) {
            }
        }
        if (made.equals(p.getString("orderFor", "")) && !out.isEmpty()) return out;
        out.clear();
        Collections.shuffle(masters);
        if (masters.isEmpty()) {   // only their own: newest first, as listed
            out.addAll(mine);
            mine.clear();
        }
        for (int i = 0, k = 0; i < masters.size(); i++) {
            out.add(masters.get(i));
            if (!mine.isEmpty() && i % 2 == 1 && !arts.get(masters.get(i)).own) out.add(mine.get(k++ % mine.size()));
        }
        p.edit().putString("orderFor", made).apply();
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
