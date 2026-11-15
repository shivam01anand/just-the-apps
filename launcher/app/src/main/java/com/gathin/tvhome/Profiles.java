package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * "Who's watching?": the people listed under "profiles" in config.json, which of them is watching, and when to ask
 * again. With fewer than two people listed nothing changes: the home screen is the one everyone has always had.
 *
 * Each person has a "home": "watchlist" is this launcher's own home screen (MainActivity, as configured by the rest
 * of config.json); "channels" is a set of TV channels built from lists of films and series (ChannelsActivity).
 *
 * The TV asks again after it has slept or restarted, with whoever watched last already chosen.
 */
final class Profiles {

    static final String HOME_WATCHLIST = "watchlist";
    static final String HOME_CHANNELS = "channels";
    private static final String PREFS = "profiles";

    static final class Person {
        final String id, name, tagline, home, cardStill;
        final int color;
        final JSONObject json;

        Person(JSONObject o) {
            json = o;
            id = o.optString("id", o.optString("name", "")).trim();
            name = o.optString("name", id).trim();
            tagline = o.optString("tagline", "").trim();
            home = o.optString("home", HOME_WATCHLIST).trim();
            cardStill = o.optString("card_still", "").trim();
            int c;
            try {
                c = Color.parseColor(o.optString("color", "#3A2C1E"));
            } catch (IllegalArgumentException e) {
                c = 0xFF3A2C1E;
            }
            color = c;
        }

        boolean channels() {
            return HOME_CHANNELS.equals(home);
        }
    }

    private Profiles() {
    }

    static List<Person> all(Context c) {
        List<Person> out = new ArrayList<>();
        JSONObject cfg = Config.overlay(c);
        JSONArray a = cfg != null ? cfg.optJSONArray("profiles") : null;
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Person p = new Person(o);
            if (!p.id.isEmpty() && !p.name.isEmpty()) out.add(p);
        }
        return out;
    }

    static boolean enabled(Context c) {
        return all(c).size() > 1;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Whoever is watching now: the last one chosen, or the first in the list. Null without profiles. */
    static Person active(Context c) {
        List<Person> people = all(c);
        if (people.isEmpty()) return null;
        String id = prefs(c).getString("active", "");
        for (Person p : people) if (p.id.equals(id)) return p;
        return people.get(0);
    }

    static int activeIndex(Context c, List<Person> people) {
        String id = prefs(c).getString("active", "");
        for (int i = 0; i < people.size(); i++) if (people.get(i).id.equals(id)) return i;
        return 0;
    }

    static void choose(Context c, Person p) {
        prefs(c).edit().putString("active", p.id).putLong("picked", System.currentTimeMillis()).apply();
    }

    /** The TV went to sleep: whoever picks up the remote next is asked who's watching. */
    static void slept(Context c) {
        prefs(c).edit().putLong("slept", System.currentTimeMillis()).apply();
    }

    /** Ask when nobody has been chosen yet, or the TV has slept or restarted since. */
    static boolean shouldAsk(Context c) {
        if (!enabled(c)) return false;
        SharedPreferences p = prefs(c);
        long picked = p.getLong("picked", 0);
        long boot = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        return picked == 0 || p.getLong("slept", 0) > picked || boot > picked;
    }

    /**
     * Called by the home screen as it opens. Sends the remote to "Who's watching?", or to the active person's
     * channels, and returns true; returns false when the home screen should simply show itself.
     */
    static boolean route(Activity a, boolean fresh) {
        if (!enabled(a)) return false;
        if (shouldAsk(a)) {
            a.startActivity(new Intent(a, ProfilesActivity.class));
            return true;
        }
        Person p = active(a);
        if (p != null && p.channels()) {
            a.startActivity(new Intent(a, ChannelsActivity.class).putExtra(ChannelsActivity.EXTRA_FRESH, fresh));
            return true;
        }
        return false;
    }

    /** Opens the chosen person's home. */
    static void open(Activity from, Person p, boolean welcome) {
        Intent i = p.channels()
                ? new Intent(from, ChannelsActivity.class).putExtra(ChannelsActivity.EXTRA_WELCOME, welcome)
                .putExtra(ChannelsActivity.EXTRA_FRESH, true)
                : new Intent(from, MainActivity.class).addCategory(Intent.CATEGORY_HOME);
        from.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
