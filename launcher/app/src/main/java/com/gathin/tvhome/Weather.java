package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "18°  Clear" beside the date and on the clock screensaver, from Open-Meteo (free, no key). The place is the
 * configured town, or where the internet connection says the TV is (ipwho.is), looked up once a day. The
 * reading is fetched at most every half hour, on a background thread, only while Home or the clock is showing.
 */
final class Weather {
    private static final String TAG = "TvHome.Weather";
    private static final long FRESH_MS = 30 * 60 * 1000L;
    private static final long PLACE_MS = 24 * 60 * 60 * 1000L;
    private static final AtomicBoolean running = new AtomicBoolean(false);

    private Weather() {
    }

    /** The last reading, or null when weather is off or nothing has been fetched yet. No network. */
    static String saved(Context c) {
        if (!Config.load(c).weather) return null;
        return prefs(c).getString("line", null);
    }

    /** Fetches a new reading when the saved one is over half an hour old; onChange runs on the main thread. */
    static void refreshIfStale(Context context, Runnable onChange) {
        Config cfg = Config.load(context);
        if (!cfg.weather) return;
        SharedPreferences p = prefs(context);
        long now = System.currentTimeMillis();
        if (now - p.getLong("tried", 0) < FRESH_MS) return;
        if (!running.compareAndSet(false, true)) return;
        p.edit().putLong("tried", now).apply();
        new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                double[] at = place(p, cfg.weatherPlace);
                JSONObject cur = new JSONObject(Watchlist.get("https://api.open-meteo.com/v1/forecast?latitude=" + at[0]
                        + "&longitude=" + at[1] + "&current=temperature_2m,weather_code&timezone=auto")).getJSONObject("current");
                String line = (Math.round(cur.getDouble("temperature_2m")) + "°  " + describe(cur.getInt("weather_code"))).trim();
                p.edit().putString("line", line).apply();
                new Handler(Looper.getMainLooper()).post(onChange);
            } catch (Exception e) {
                Log.w(TAG, "weather failed, keeping the last reading", e);
            } finally {
                running.set(false);
            }
        }, "weather").start();
    }

    /** Latitude and longitude of the configured town, or of the connection; kept a day. */
    private static double[] place(SharedPreferences p, String town) throws Exception {
        long now = System.currentTimeMillis();
        if (town.equals(p.getString("placeFor", null)) && now - p.getLong("placeAt", 0) < PLACE_MS) {
            return new double[]{Double.longBitsToDouble(p.getLong("lat", 0)), Double.longBitsToDouble(p.getLong("lon", 0))};
        }
        double lat, lon;
        if (!town.isEmpty()) {
            JSONArray r = new JSONObject(Watchlist.get("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                    + Uri.encode(town))).getJSONArray("results");
            lat = r.getJSONObject(0).getDouble("latitude");
            lon = r.getJSONObject(0).getDouble("longitude");
        } else {
            JSONObject o = new JSONObject(Watchlist.get("https://ipwho.is/"));
            lat = o.getDouble("latitude");
            lon = o.getDouble("longitude");
        }
        p.edit().putString("placeFor", town).putLong("placeAt", now)
                .putLong("lat", Double.doubleToLongBits(lat)).putLong("lon", Double.doubleToLongBits(lon)).apply();
        return new double[]{lat, lon};
    }

    /** WMO weather codes, in a word or two. */
    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorms";
        return "";
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("weather", Context.MODE_PRIVATE);
    }
}
