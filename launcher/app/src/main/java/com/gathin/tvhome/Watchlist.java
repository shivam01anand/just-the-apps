package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The watchlist row: titles from an IMDb watchlist, each saved with the one place to open it. That is the
 * title's page in a streaming app the owner has (from JustWatch's offers for the country), or Stremio.
 *
 * Nothing runs at boot and nothing is preloaded. When Home is shown and the saved list is older than
 * {@link #REFRESH_MS}, one background thread fetches the list, looks up the titles it has not seen (and a
 * few stale ones), saves title, poster and link, and hands the new list to the UI. A click only fires the
 * saved link. IMDb's and JustWatch's feeds are unofficial; if either fails, the last saved list stays.
 */
final class Watchlist {
    private static final String TAG = "TvHome.Watchlist";
    private static final long REFRESH_MS = 12 * 60 * 60 * 1000L;
    /** After a failed refresh (TV offline, a feed down), try again on the next Home after this long. */
    private static final long RETRY_MS = 30 * 60 * 1000L;
    /** Streaming catalogues change; a title's place is looked up again after this long. */
    private static final long RECHECK_MS = 3 * 24 * 60 * 60 * 1000L;
    /** Re-checks per refresh, so a refresh after the first one stays a handful of requests. */
    private static final int MAX_RECHECKS = 25;
    /** Bumped when the saved list gains fields; an older list is refreshed on the next Home.
     *  2: genres, rating. 3: runtime, countries, plot, directors, votes. 4: 300 px posters, collections.
     *  5: podcasts. 6: podcasts from chosen channels, trending, world cinema. 7: episodes, directors. */
    private static final int DATA_VERSION = 7;
    /** A YouTube episode, shown as a 16:9 card with its channel; and a director, whose tile opens their films. */
    static final String PODCAST = "podcast";
    static final String DIRECTOR = "director";
    /** Titles per collection (Hindi, this year, best of this year). */
    private static final int COLLECTION_SIZE = 30;
    /** Titles kept in Continue. */
    private static final int HISTORY_SIZE = 15;

    static final String STREMIO = "Stremio";

    static final class Item {
        String imdb, title, type, posterUrl, service, pkg, intentUri;
        int year;
        long checked;
        /** IMDb's rating out of 10 and its number of votes; 0 when it has none. */
        double rating;
        int votes;
        int runtimeMin;
        String plot;
        /** A podcast's channel, and when the episode it plays came out; null and 0 for films and shows. */
        String subtitle;
        long published;
        /** A director's best films, best rated first; empty for everything else. */
        List<Item> works = new ArrayList<>();
        List<String> genres = new ArrayList<>();
        List<String> countries = new ArrayList<>();
        List<String> directors = new ArrayList<>();
    }

    /** A chip on the groups row and the posters it opens. */
    static final class Group {
        final String label;
        final List<Item> items;

        Group(String label, List<Item> items) {
            this.label = label;
            this.items = items;
        }
    }

    /** A list from beyond the watchlist: new great titles in a language, or this year's popular and best. */
    static final class Collection {
        final String label;
        /** Language collections go first on the row; the first chip is the one open when Home appears. */
        final boolean lead;
        final List<Item> items;
        /** IMDb's best in a genre, filling that genre's chip after the watchlist's own titles; not a chip itself. */
        final boolean genreFill;

        Collection(String label, boolean lead, List<Item> items) {
            this(label, lead, items, false);
        }

        Collection(String label, boolean lead, List<Item> items, boolean genreFill) {
            this.label = label;
            this.lead = lead;
            this.items = items;
            this.genreFill = genreFill;
        }
    }

    /** Everything the row is built from: the watchlist, the collections, and what was opened from Home. */
    static final class Data {
        final List<Item> watchlist;
        final List<Collection> collections;
        final List<Item> history;

        Data(List<Item> watchlist, List<Collection> collections, List<Item> history) {
            this.watchlist = watchlist;
            this.collections = collections;
            this.history = history;
        }
    }

    /**
     * The groups row, for someone who thinks in moods and genres, not apps: language collections first (the
     * first chip is open when Home appears), Continue (what you opened from Home, newest first), Recently added
     * to the watchlist, this year's popular and best, then the watchlist's genres, best rated first. A genre that
     * covers most of the watchlist (Drama, on many lists) narrows nothing, so it is left out, and so is any genre
     * with fewer than three titles.
     */
    static List<Group> groups(Data d) {
        List<Group> out = new ArrayList<>();
        for (Collection c : d.collections) if (c.lead && !c.items.isEmpty()) out.add(new Group(c.label, c.items));
        if (!d.history.isEmpty()) out.add(new Group("Continue", d.history));
        if (!d.watchlist.isEmpty()) out.add(new Group("Recently added", d.watchlist));
        Map<String, Collection> fills = new HashMap<>();
        for (Collection c : d.collections) {
            if (c.genreFill) fills.put(c.label, c);
            else if (!c.lead && !c.items.isEmpty()) out.add(new Group(c.label, c.items));
        }
        Map<String, List<Item>> byGenre = new HashMap<>();
        for (Item it : d.watchlist) {
            for (String g : it.genres) {
                List<Item> l = byGenre.get(g);
                if (l == null) byGenre.put(g, l = new ArrayList<>());
                l.add(it);
            }
        }
        List<Map.Entry<String, List<Item>>> genres = new ArrayList<>(byGenre.entrySet());
        Collections.sort(genres, (a, b) -> b.getValue().size() != a.getValue().size()
                ? b.getValue().size() - a.getValue().size() : a.getKey().compareTo(b.getKey()));
        // Inside a genre, best rated first: picking a genre means "show me the good ones".
        for (Map.Entry<String, List<Item>> e : genres) {
            int n = e.getValue().size();
            if (n < 3 || n * 4 > d.watchlist.size() * 3) continue;
            List<Item> byRating = new ArrayList<>(e.getValue());
            Collections.sort(byRating, (a, b) -> Double.compare(b.rating, a.rating));
            // A genre the watchlist has only a few of is topped up with IMDb's best in it, never left at three.
            Collection fill = fills.get(e.getKey());
            java.util.Set<String> have = new java.util.HashSet<>();
            for (Item it : byRating) have.add(it.imdb);
            for (int i = 0; fill != null && i < fill.items.size() && byRating.size() < GENRE_SIZE; i++) {
                if (have.add(fill.items.get(i).imdb)) byRating.add(fill.items.get(i));
            }
            out.add(new Group(e.getKey(), byRating));
        }
        return out;
    }

    /** A genre chip holds at least this many: the watchlist's titles in it, then IMDb's best. */
    private static final int GENRE_SIZE = 24;

    /** The genres that become chips: in three or more watchlist titles, but not in most of them (Drama). */
    private static List<String> genreChips(List<Item> watchlist) {
        Map<String, Integer> count = new HashMap<>();
        for (Item it : watchlist) for (String g : it.genres) count.put(g, count.containsKey(g) ? count.get(g) + 1 : 1);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : count.entrySet()) {
            if (e.getValue() >= 3 && e.getValue() * 4 <= watchlist.size() * 3) out.add(e.getKey());
        }
        return out;
    }

    /** Titles per row remembered as "in front": the next reshuffle puts the others first. */
    private static final int FRONT = 6;

    /**
     * Each row's order on screen. On a reshuffle (Home pressed) every row but Continue is shuffled, with the
     * titles that were not in front last time put first, so Home never opens on the same posters twice running.
     * Otherwise (back from an app, or new data) each row keeps the order it had, new titles at the end.
     * Continue keeps newest first: that is what it is for.
     */
    static List<Group> arrange(Context c, List<Group> groups, Map<String, List<String>> order, boolean reshuffle) {
        SharedPreferences fronts = c.getSharedPreferences("shuffle", Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = fronts.edit();
        List<Group> out = new ArrayList<>();
        for (Group g : groups) {
            List<Item> items = new ArrayList<>(g.items);
            List<String> before = order.get(g.label);
            if (!g.label.equals("Continue") && (reshuffle || before == null)) {
                java.util.Set<String> front = new java.util.HashSet<>(Arrays.asList(fronts.getString(g.label, "").split(",")));
                List<Item> fresh = new ArrayList<>(), shown = new ArrayList<>();
                for (Item it : items) (front.contains(it.imdb) ? shown : fresh).add(it);
                Collections.shuffle(fresh);
                Collections.shuffle(shown);
                items = fresh;
                items.addAll(shown);
                StringBuilder now = new StringBuilder();
                for (int i = 0; i < Math.min(FRONT, items.size()); i++) now.append(i > 0 ? "," : "").append(items.get(i).imdb);
                edit.putString(g.label, now.toString());
            } else if (before != null && !g.label.equals("Continue")) {
                final List<String> was = before;
                Collections.sort(items, (a, b) -> Integer.compare(rank(was, a.imdb), rank(was, b.imdb)));
            }
            List<String> ids = new ArrayList<>();
            for (Item it : items) ids.add(it.imdb);
            order.put(g.label, ids);
            out.add(new Group(g.label, items));
        }
        edit.apply();
        return out;
    }

    private static int rank(List<String> order, String id) {
        int i = order.indexOf(id);
        return i >= 0 ? i : Integer.MAX_VALUE;
    }

    /**
     * A streaming service the owner has: JustWatch's technical name, the label shown, the app's package, and
     * the package manager, to check the app takes a link before it's used.
     */
    static final class Service {
        final String tech, label, pkg;
        final PackageManager pm;

        Service(String tech, String label, String pkg, PackageManager pm) {
            this.tech = tech;
            this.label = label;
            this.pkg = pkg;
            this.pm = pm;
        }
    }

    /** YouTube's app: Android TV's, else Fire TV's. Podcast episodes open in whichever is installed. */
    private static final String[] YOUTUBE_PACKAGES = {"com.google.android.youtube.tv", "com.amazon.firetv.youtube"};
    private static volatile String youtubePkg = YOUTUBE_PACKAGES[0];

    /** The first of these packages that is installed and can be opened, or null. */
    private static String firstInstalled(PackageManager pm, String... pkgs) {
        for (String pkg : pkgs) {
            String p = pkg.trim();
            if (!p.isEmpty() && (pm.getLeanbackLaunchIntentForPackage(p) != null || pm.getLaunchIntentForPackage(p) != null)) return p;
        }
        return null;
    }

    interface Listener {
        void onWatchlist(Data data);
    }

    private static final AtomicBoolean running = new AtomicBoolean(false);

    private Watchlist() {
    }

    /** The row shows when there is an IMDb user or any collection (config.xml, overlaid by config.json). */
    static boolean enabled(Context c) {
        Config cfg = Config.load(c);
        return !cfg.watchlistImdbUser.isEmpty() || !cfg.watchlistLetterboxdUser.isEmpty() || !cfg.watchlistLanguages.isEmpty() || cfg.watchlistThisYear
                || cfg.watchlistPodcasts || cfg.watchlistTrending || cfg.watchlistWorldCinema || !cfg.watchlistDirectors.isEmpty();
    }

    // ---- saved list ------------------------------------------------------------------------------

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "watchlist");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static File poster(Context c, String imdb) {
        return new File(new File(dir(c), "posters300"), imdb + ".jpg");
    }

    /** The saved watchlist, collections and history; empty lists when there are none yet. */
    static Data load(Context c) {
        List<Collection> cols = new ArrayList<>();
        try {
            JSONArray a = readJson(new File(dir(c), "collections.json"));
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                cols.add(new Collection(o.getString("label"), o.optBoolean("lead"), items(o.optJSONArray("items")),
                        o.optBoolean("genre")));
            }
        } catch (Exception e) {
            Log.w(TAG, "saved collections unreadable", e);
            cols.clear();
        }
        return new Data(loadItems(c, "items.json"), cols, loadItems(c, "history.json"));
    }

    private static List<Item> loadItems(Context c, String name) {
        try {
            return items(readJson(new File(dir(c), name)));
        } catch (Exception e) {
            Log.w(TAG, name + " unreadable, starting over", e);
            return new ArrayList<>();
        }
    }

    private static JSONArray readJson(File f) throws IOException, JSONException {
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            return new JSONArray(new String(readAll(in), StandardCharsets.UTF_8));
        }
    }

    private static List<Item> items(JSONArray a) throws JSONException {
        List<Item> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) out.add(fromJson(a.getJSONObject(i)));
        return out;
    }

    private static Item fromJson(JSONObject o) throws JSONException {
        Item it = new Item();
        it.imdb = o.getString("imdb");
        it.title = o.optString("title");
        it.type = o.optString("type");
        it.year = o.optInt("year");
        it.posterUrl = o.optString("poster", null);
        it.service = o.optString("service", STREMIO);
        it.pkg = o.optString("pkg", null);
        it.intentUri = o.optString("intent", null);
        it.checked = o.optLong("checked");
        it.rating = o.optDouble("rating", 0);
        it.votes = o.optInt("votes");
        it.runtimeMin = o.optInt("runtime");
        it.plot = o.optString("plot", null);
        it.subtitle = o.optString("subtitle", null);
        it.published = o.optLong("published");
        JSONArray works = o.optJSONArray("works");
        if (works != null) it.works = items(works);
        strings(o.optJSONArray("genres"), it.genres);
        strings(o.optJSONArray("countries"), it.countries);
        strings(o.optJSONArray("directors"), it.directors);
        return it;
    }

    private static JSONObject toJson(Item it) throws JSONException {
        return new JSONObject()
                .put("imdb", it.imdb).put("title", it.title).put("type", it.type).put("year", it.year)
                .put("poster", it.posterUrl).put("service", it.service).put("pkg", it.pkg)
                .put("intent", it.intentUri).put("checked", it.checked)
                .put("rating", it.rating).put("votes", it.votes).put("runtime", it.runtimeMin).put("plot", it.plot)
                .put("subtitle", it.subtitle).put("published", it.published)
                .put("works", it.works.isEmpty() ? null : toJson(it.works))
                .put("genres", new JSONArray(it.genres)).put("countries", new JSONArray(it.countries))
                .put("directors", new JSONArray(it.directors));
    }

    private static JSONArray toJson(List<Item> items) throws JSONException {
        JSONArray a = new JSONArray();
        for (Item it : items) a.put(toJson(it));
        return a;
    }

    private static void strings(JSONArray a, List<String> into) {
        for (int k = 0; a != null && k < a.length(); k++) {
            String v = a.optString(k);
            if (!v.isEmpty()) into.add(v);
        }
    }

    private static void write(Context c, String name, JSONArray a) throws IOException {
        File tmp = new File(dir(c), name + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(a.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(new File(dir(c), name))) throw new IOException("rename failed");
    }

    /**
     * Puts a title at the front of Continue. A small file on the TV and no network: the app it opens in keeps
     * the position, so finding it again is all Continue has to do.
     */
    static void remember(Context c, Item item) {
        try {
            List<Item> h = loadItems(c, "history.json");
            for (int i = h.size() - 1; i >= 0; i--) if (h.get(i).imdb.equals(item.imdb)) h.remove(i);
            h.add(0, item);
            while (h.size() > HISTORY_SIZE) h.remove(h.size() - 1);
            write(c, "history.json", toJson(h));
        } catch (Exception e) {
            Log.w(TAG, "could not save Continue", e);
        }
    }

    // ---- refresh ---------------------------------------------------------------------------------

    /** Refreshes on a background thread when the saved list is stale; the listener runs on the main thread. */
    static void refreshIfStale(Context context, Listener listener) {
        if (!enabled(context)) return;
        SharedPreferences prefs = context.getSharedPreferences("watchlist", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        // What the saved lists are built for: this code's data version and the watchlist settings. A change to
        // either (an update, a chip switched on in config.json) refreshes on the next Home, not hours later.
        Config cfg = Config.load(context);
        String want = DATA_VERSION + "|" + cfg.watchlistImdbUser + "|" + cfg.watchlistCountry + "|"
                + cfg.watchlistLanguages + "|" + cfg.watchlistThisYear + "|" + cfg.watchlistPodcasts + "|"
                + cfg.watchlistPodcastChannels + "|" + cfg.watchlistTrending + "|" + cfg.watchlistWorldCinema + "|"
                + cfg.watchlistPersonalName + "|" + cfg.watchlistPersonalMix + "|" + cfg.watchlistPersonalTitles + "|"
                + cfg.watchlistDirectors + "|" + cfg.watchlistExcludeGenres + "|" + cfg.watchlistLetterboxdUser;
        boolean outdated = !want.equals(prefs.getString("built", ""));
        // A failed try waits RETRY_MS, but only for the same settings: a try by the version before an update,
        // minutes earlier, held the podcasts back for half an hour after one.
        if (want.equals(prefs.getString("triedFor", "")) && now - prefs.getLong("tried", 0) < RETRY_MS) return;
        if (!outdated && now - prefs.getLong("ok", 0) < REFRESH_MS) return;
        if (!running.compareAndSet(false, true)) return;
        prefs.edit().putLong("tried", now).putString("triedFor", want).apply();
        final Context c = context.getApplicationContext();
        new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                Data data = refresh(c, prefs);
                prefs.edit().putLong("ok", System.currentTimeMillis()).putString("built", want).apply();
                new Handler(Looper.getMainLooper()).post(() -> listener.onWatchlist(data));
            } catch (Exception | OutOfMemoryError e) {
                Log.w(TAG, "refresh failed, keeping the saved list", e);
            } finally {
                running.set(false);
            }
        }, "watchlist").start();
    }

    private static Data refresh(Context c, SharedPreferences prefs) throws IOException, JSONException {
        Data before = load(c);
        Map<String, Item> saved = new HashMap<>();
        for (Collection col : before.collections) {
            for (Item it : col.items) {
                saved.put(it.imdb, it);
                for (Item w : it.works) saved.put(w.imdb, w);
            }
        }
        for (Item it : before.watchlist) saved.put(it.imdb, it);
        Config cfg = Config.load(c);
        List<Service> services = installedServices(c, cfg.watchlistServices);
        String yt = firstInstalled(c.getPackageManager(), YOUTUBE_PACKAGES);
        if (yt != null) youtubePkg = yt;
        // A service added (an app installed, or the list changed) can hold any title: look every one up again.
        StringBuilder sig = new StringBuilder();
        for (Service sv : services) sig.append(sv.tech).append(',');
        boolean servicesChanged = !sig.toString().equals(prefs.getString("services", ""));
        // The 200 px posters from before version 4 are too soft at the new size.
        deleteTree(new File(dir(c), "posters"));

        Placer placer = new Placer(c, saved, services, cfg.watchlistCountry, servicesChanged);
        skipGenres = new java.util.HashSet<>(cfg.watchlistExcludeGenres);
        List<Item> watchlist = cfg.watchlistImdbUser.isEmpty() ? new ArrayList<>() : fetchImdbWatchlist(cfg.watchlistImdbUser);
        if (!cfg.watchlistLetterboxdUser.isEmpty()) {
            // After the IMDb list, the Letterboxd films it doesn't already have. A Letterboxd hiccup keeps the rest.
            java.util.Set<String> have = new java.util.HashSet<>();
            for (Item it : watchlist) have.add(it.imdb);
            try {
                for (Item it : fetchLetterboxdWatchlist(c, cfg.watchlistLetterboxdUser)) if (have.add(it.imdb)) watchlist.add(it);
            } catch (IOException | JSONException | RuntimeException e) {
                Log.w(TAG, "letterboxd watchlist failed", e);
            }
        }
        watchlist.removeIf(Watchlist::unwanted);
        for (Item it : watchlist) placer.place(it);

        List<Collection> cols = new ArrayList<>();
        int year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        String types = "titleTypeConstraint: {anyTitleTypeIds: [\"movie\", \"tvSeries\", \"tvMiniSeries\"]}";
        for (String lang : cfg.watchlistLanguages) {
            // New and well liked: from the start of last year, 7+ with enough votes to mean it, most popular first.
            // Only titles streaming on an installed app: this chip is for whoever just wants to press OK and
            // watch, so a film still in cinemas has no place in it. Twice as many are fetched to fill it.
            boolean personal = !cfg.watchlistPersonalName.isEmpty() && lang.equals(cfg.watchlistLanguages.get(0));
            String label = personal ? cfg.watchlistPersonalName : new java.util.Locale(lang).getDisplayLanguage(java.util.Locale.ENGLISH);
            Collection col = collection(placer, label, true, COLLECTION_SIZE * 2, true, "releaseDateConstraint: {releaseDateRange: {start: \"" + (year - 1)
                    + "-01-01\", end: \"" + year + "-12-31\"}}, " + types
                    + ", userRatingsConstraint: {aggregateRatingRange: {min: 7}, ratingsCountRange: {min: 1000}}"
                    + ", languageConstraint: {anyPrimaryLanguages: [\"" + lang + "\"]}"
                    + (personal ? ", genreConstraint: {excludeGenreIds: [\"Horror\"]}" : ""), "POPULARITY", "ASC");
            cols.add(personal ? personalMix(placer, col, cfg, year) : col);
        }
        if (cfg.watchlistPodcasts) {
            // A lead chip, so it sits after the language chips and before Continue.
            List<Item> pods = new ArrayList<>();
            try {
                pods = !cfg.watchlistPodcastChannels.isEmpty() ? fetchChannelShows(c, cfg.watchlistPodcastChannels, saved)
                        : fetchPodcasts(cfg.watchlistCountry, cfg.watchlistLanguages.isEmpty() ? "" : cfg.watchlistLanguages.get(0));
                for (Item it : pods) fetchPoster(c, it);   // a show's cover rarely changes; a saved one is kept
            } catch (IOException | JSONException | RuntimeException e) {
                Log.w(TAG, "podcasts failed", e);   // never the whole refresh: the other chips still update
            }
            cols.add(new Collection("Podcasts", true, pods));
        }
        if (cfg.watchlistTrending) {
            String here = new java.util.Locale("", cfg.watchlistCountry).getDisplayCountry(java.util.Locale.ENGLISH);
            cols.add(trendingHere(placer, "Trending in " + here, cfg.watchlistCountry));
            // IMDb's most looked-up titles released in the last year, with enough votes to have been seen.
            java.util.Calendar since = java.util.Calendar.getInstance();
            since.add(java.util.Calendar.YEAR, -1);
            cols.add(collection(placer, "Trending worldwide", false, COLLECTION_SIZE, false,
                    "releaseDateConstraint: {releaseDateRange: {start: \"" + new java.text.SimpleDateFormat("yyyy-MM-dd",
                            java.util.Locale.ROOT).format(since.getTime()) + "\"}}, " + types
                            + ", userRatingsConstraint: {aggregateRatingRange: {min: 6}, ratingsCountRange: {min: 2000}}",
                    "POPULARITY", "ASC"));
        }
        if (cfg.watchlistWorldCinema) {
            // Not in English or the household's own languages (nor, in India, any Indian one), 8 and up from at
            // least 50,000 people: the films the rest of the world agrees on, best first.
            StringBuilder skip = new StringBuilder("\"en\", \"zxx\"");
            List<String> own = new ArrayList<>(cfg.watchlistLanguages);
            if (cfg.watchlistCountry.equals("IN")) own.addAll(Arrays.asList("hi", "ta", "te", "ml", "kn", "bn", "mr", "pa", "gu", "ur"));
            for (String l : own) skip.append(", \"").append(l).append('"');
            cols.add(collection(placer, "World cinema", false, COLLECTION_SIZE, false,
                    "titleTypeConstraint: {anyTitleTypeIds: [\"movie\"]}, userRatingsConstraint: {aggregateRatingRange: "
                            + "{min: 8}, ratingsCountRange: {min: 50000}}, languageConstraint: {excludePrimaryLanguages: ["
                            + skip + "]}", "USER_RATING", "DESC"));
        }
        if (!cfg.watchlistDirectors.isEmpty()) cols.add(directors(placer, cfg.watchlistDirectors));
        for (String genre : genreChips(watchlist)) {
            // The best in the genre by people's ratings, 7.3 and up from 25,000 or more. Posters load when the
            // row first shows them, and where each streams is looked up when it is opened.
            List<Item> best = new ArrayList<>();
            try {
                // Films and mini-series: as a genre's best, long-running serials (Ramayan under Musical) crowd them out.
                for (Item it : fetchSearch(withoutGenres("genreConstraint: {allGenreIds: [\"" + genre + "\"]}, "
                        + "titleTypeConstraint: {anyTitleTypeIds: [\"movie\", \"tvMiniSeries\"]}, userRatingsConstraint: "
                        + "{aggregateRatingRange: {min: 7.3}, ratingsCountRange: {min: 25000}}"),
                        "USER_RATING", "DESC", GENRE_SIZE + 10)) {
                    if (unwanted(it)) continue;
                    Item known = saved.get(it.imdb);
                    if (known != null && known.checked > 0 && placer.now - known.checked < RECHECK_MS) Placer.copyPlace(known, it);
                    else it.service = null;
                    best.add(it);
                }
            } catch (IOException | JSONException e) {
                Log.w(TAG, "genre " + genre + " failed", e);
            }
            cols.add(new Collection(genre, false, best, true));
        }
        if (cfg.watchlistThisYear) {
            String thisYear = "releaseDateConstraint: {releaseDateRange: {start: \"" + year + "-01-01\", end: \"" + year
                    + "-12-31\"}}, " + types;
            // This year's most popular, minus the ones people didn't like; and the best of the year with real votes.
            cols.add(collection(placer, String.valueOf(year), false, COLLECTION_SIZE, false, thisYear
                    + ", userRatingsConstraint: {aggregateRatingRange: {min: 6.5}, ratingsCountRange: {min: 5000}}", "POPULARITY", "ASC"));
            cols.add(collection(placer, "Best of " + year, false, COLLECTION_SIZE, false, thisYear
                    + ", userRatingsConstraint: {ratingsCountRange: {min: 20000}}", "USER_RATING", "DESC"));
        }

        // A source having a bad moment (a feed down, a timeout) leaves its chip as it was, not empty until the
        // next refresh twelve hours later.
        for (int i = 0; i < cols.size(); i++) {
            if (!cols.get(i).items.isEmpty()) continue;
            for (Collection old : before.collections) {
                if (old.label.equals(cols.get(i).label) && old.genreFill == cols.get(i).genreFill && !old.items.isEmpty()) cols.set(i, old);
            }
        }
        write(c, "items.json", toJson(watchlist));
        JSONArray a = new JSONArray();
        for (Collection col : cols) {
            a.put(new JSONObject().put("label", col.label).put("lead", col.lead).put("genre", col.genreFill)
                    .put("items", toJson(col.items)));
        }
        write(c, "collections.json", a);
        prefs.edit().putString("services", sig.toString()).apply();
        List<Item> history = loadItems(c, "history.json");
        dropOldThumbnails(c, cols, history);
        return new Data(watchlist, cols, history);
    }

    /**
     * One collection; a failed search leaves it empty rather than failing the whole refresh. With streamingOnly,
     * titles that would fall back to Stremio are left out, and the first COLLECTION_SIZE that stream are kept.
     */
    private static Collection collection(Placer placer, String label, boolean lead, int fetch, boolean streamingOnly,
                                         String constraints, String sortBy, String order) {
        List<Item> items = new ArrayList<>();
        try {
            for (Item it : fetchSearch(withoutGenres(constraints), sortBy, order, fetch)) {
                if (items.size() >= COLLECTION_SIZE) break;
                if (unwanted(it)) continue;
                if (streamingOnly) placer.placeOnly(it);
                else placer.place(it);
                if (streamingOnly && STREMIO.equals(it.service)) continue;
                if (streamingOnly) fetchPoster(placer.c, it);
                items.add(it);
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "collection " + label + " failed", e);
        }
        return new Collection(label, lead, items);
    }

    /** Genres the household never wants to see (watchlist_exclude_genres), for the refresh under way. */
    private static java.util.Set<String> skipGenres = new java.util.HashSet<>();

    private static boolean unwanted(Item it) {
        for (String g : it.genres) if (skipGenres.contains(g)) return true;
        return false;
    }

    /** The search with the unwanted genres left out by IMDb itself, so a row still fills to its size. */
    private static String withoutGenres(String constraints) {
        if (skipGenres.isEmpty()) return constraints;
        StringBuilder ids = new StringBuilder();
        for (String g : skipGenres) ids.append(ids.length() > 0 ? ", " : "").append('"').append(g).append('"');
        // One genreConstraint per search: add to its exclusions, or give it some, or add one.
        int i = constraints.indexOf("genreConstraint: {");
        if (i < 0) return constraints + ", genreConstraint: {excludeGenreIds: [" + ids + "]}";
        String ex = "excludeGenreIds: [";
        int j = constraints.indexOf(ex, i);
        if (j >= 0) return constraints.substring(0, j + ex.length()) + ids + ", " + constraints.substring(j + ex.length());
        int k = i + "genreConstraint: {".length();
        return constraints.substring(0, k) + ex + ids + "], " + constraints.substring(k);
    }

    /** In the personal chip, the language's picks are capped to leave room for the rest of the mix. */
    private static final int PERSONAL_LANGUAGE = 20, PERSONAL_RECIPE = 8, PERSONAL_TITLES = 4;

    /**
     * The personal chip: the first language's picks, then each recipe in watchlistPersonalMix, then four of
     * watchlistPersonalTitles chosen afresh at each refresh. Home shuffles them together.
     */
    private static Collection personalMix(Placer placer, Collection language, Config cfg, int year) {
        List<Item> items = new ArrayList<>(language.items.subList(0, Math.min(PERSONAL_LANGUAGE, language.items.size())));
        for (String recipe : cfg.watchlistPersonalMix) {
            if (recipe.equals("korean_drama")) {
                // New K-dramas people loved: series from the start of last year, 7.5 and up, no horror, on the apps here.
                Collection k = collection(placer, recipe, false, COLLECTION_SIZE, true, "titleTypeConstraint: {anyTitleTypeIds: "
                        + "[\"tvSeries\", \"tvMiniSeries\"]}, languageConstraint: {anyPrimaryLanguages: [\"ko\"]}, "
                        + "releaseDateConstraint: {releaseDateRange: {start: \"" + (year - 1) + "-01-01\"}}, userRatingsConstraint: "
                        + "{aggregateRatingRange: {min: 7.5}, ratingsCountRange: {min: 1000}}, genreConstraint: {excludeGenreIds: "
                        + "[\"Horror\"]}", "POPULARITY", "ASC");
                items.addAll(k.items.subList(0, Math.min(PERSONAL_RECIPE, k.items.size())));
            }
        }
        if (!cfg.watchlistPersonalTitles.isEmpty()) {
            List<String> ids = new ArrayList<>(cfg.watchlistPersonalTitles);
            Collections.shuffle(ids);
            try {
                for (Item it : imdbTitles(ids.subList(0, Math.min(PERSONAL_TITLES, ids.size())))) {
                    if (unwanted(it)) continue;
                    placer.place(it);   // where it streams, else Stremio: these are worth the extra step
                    items.add(it);
                }
            } catch (IOException | JSONException e) {
                Log.w(TAG, "personal titles failed", e);
            }
        }
        List<Item> unique = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Item it : items) if (seen.add(it.imdb)) unique.add(it);
        return new Collection(language.label, true, unique);
    }

    /** Where each title opens, shared by the watchlist and the collections so no title is looked up twice. */
    private static final class Placer {
        final Context c;
        final Map<String, Item> saved;
        final Map<String, Item> done = new HashMap<>();
        final List<Service> services;
        final String country;
        final boolean servicesChanged;
        final long now = System.currentTimeMillis();
        int rechecks;

        Placer(Context c, Map<String, Item> saved, List<Service> services, String country, boolean servicesChanged) {
            this.c = c;
            this.saved = saved;
            this.services = services;
            this.country = country;
            this.servicesChanged = servicesChanged;
        }

        void place(Item it) {
            placeOnly(it);
            fetchPoster(c, it);
        }

        /** Works out where the title opens without downloading its poster (it may yet be left out). */
        void placeOnly(Item it) {
            Item known = done.get(it.imdb);
            if (known != null) {
                copyPlace(known, it);
                return;
            }
            Item old = saved.get(it.imdb);
            boolean stale = old == null || servicesChanged || now - old.checked > RECHECK_MS;
            if (old != null && (!stale || (!servicesChanged && rechecks >= MAX_RECHECKS))) {
                copyPlace(old, it);
            } else {
                if (old != null) rechecks++;
                try {
                    findPlace(it, services, country);
                    it.checked = now;
                } catch (IOException | JSONException e) {
                    // JustWatch hiccup: keep what we knew, try this title again next time.
                    if (old != null) copyPlace(old, it);
                    else it.service = STREMIO;
                    it.checked = old != null ? old.checked : 0;
                }
            }
            done.put(it.imdb, it);
        }

        static void copyPlace(Item from, Item to) {
            to.service = from.service;
            to.pkg = from.pkg;
            to.intentUri = from.intentUri;
            to.checked = from.checked;
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /**
     * The configured services, in order of preference, whose app is installed and enabled. A line may name
     * several packages for one service, comma separated (Android TV's app, then Fire TV's): the first installed
     * one is used.
     */
    static List<Service> installedServices(Context c, List<String> lines) {
        List<Service> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        for (String line : lines) {
            String[] p = line.split("\\|");
            if (p.length != 3) continue;
            String pkg = firstInstalled(pm, p[2].split(","));
            if (pkg == null) continue;
            out.add(new Service(p[0], p[1], pkg, pm));
        }
        return out;
    }

    /** The fields the row, the info line and the details card use, for one IMDb title. */
    private static final String TITLE_FIELDS = "id titleText { text } originalTitleText { text } releaseYear { year } titleType { id } primaryImage { url }"
            + " ratingsSummary { aggregateRating voteCount } titleGenres { genres { genre { text } } }"
            + " runtime { seconds } countriesOfOrigin { countries { text } } plot { plotText { plainText } }"
            + " directors: credits(first: 2, filter: {categories: [\"director\"]}) { edges { node { name { nameText { text } } } } }";

    private static List<Item> fetchImdbWatchlist(String user) throws IOException, JSONException {
        String q = "query { predefinedList(classType: WATCH_LIST, userId: \"" + user + "\") {"
                + " items(first: 250, sort: {by: CREATED_DATE, order: DESC}) { edges { node { listItem {"
                + " ... on Title { " + TITLE_FIELDS + " } } } } } } }";
        JSONObject d = imdb(q);
        JSONArray edges = d.getJSONObject("data").getJSONObject("predefinedList").getJSONObject("items").getJSONArray("edges");
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < edges.length(); i++) {
            Item it = parseTitle(edges.getJSONObject(i).getJSONObject("node").optJSONObject("listItem"));
            if (it != null) out.add(it);   // a list entry that is not a title (person, image) is skipped
        }
        return out;
    }

    /** Letterboxd watchlist pages read per refresh; at 28 films a page, about the 250 the IMDb list allows. */
    private static final int LETTERBOXD_PAGES = 9;
    private static final java.util.regex.Pattern LETTERBOXD_FILM = java.util.regex.Pattern.compile(
            "data-item-slug=\"([^\"]+)\"[^>]*?data-item-name=\"([^\"]+)\"|data-item-name=\"([^\"]+)\"[^>]*?data-item-slug=\"([^\"]+)\"");

    /**
     * A public Letterboxd watchlist, in its own order. Letterboxd has no open API, so this reads the watchlist's
     * pages (the films' names and years are on them) and finds each film on IMDb by name and year, once: the
     * match is saved, so later refreshes only look up films added since.
     */
    private static List<Item> fetchLetterboxdWatchlist(Context c, String user) throws IOException, JSONException {
        File cacheFile = new File(dir(c), "letterboxd.json");
        JSONObject cache = new JSONObject();
        try (InputStream in = cacheFile.exists() ? new FileInputStream(cacheFile) : null) {
            if (in != null) cache = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // an unreadable cache just means looking the films up again
        }
        List<String> ids = new ArrayList<>();
        java.util.Set<String> slugs = new java.util.HashSet<>();
        for (int page = 1; page <= LETTERBOXD_PAGES; page++) {
            String html = get("https://letterboxd.com/" + Uri.encode(user) + "/watchlist/page/" + page + "/");
            java.util.regex.Matcher m = LETTERBOXD_FILM.matcher(html);
            int found = 0;
            while (m.find()) {
                String slug = m.group(1) != null ? m.group(1) : m.group(4);
                String name = android.text.Html.fromHtml(m.group(1) != null ? m.group(2) : m.group(3),
                        android.text.Html.FROM_HTML_MODE_LEGACY).toString();
                if (!slugs.add(slug)) continue;   // each film appears more than once in the page's markup
                found++;
                String id = cache.optString(slug, null);
                if (id == null) {
                    id = imdbIdFor(name);
                    cache.put(slug, id);
                }
                if (!id.isEmpty()) ids.add(id);
            }
            if (found == 0) break;   // past the last page
        }
        try (OutputStream out = new FileOutputStream(cacheFile)) {
            out.write(cache.toString().getBytes(StandardCharsets.UTF_8));
        }
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += 100) out.addAll(imdbTitles(ids.subList(i, Math.min(ids.size(), i + 100))));
        return out;
    }

    private static final java.util.regex.Pattern NAME_YEAR = java.util.regex.Pattern.compile("^(.*) \\((\\d{4})\\)$");

    /** "Black Bag (2025)" to its IMDb ID through IMDb's own search suggestions, matching the year; "" if none. */
    private static String imdbIdFor(String nameAndYear) {
        java.util.regex.Matcher m = NAME_YEAR.matcher(nameAndYear);
        boolean dated = m.matches();
        String title = dated ? m.group(1) : nameAndYear;
        int year = dated ? Integer.parseInt(m.group(2)) : 0;
        try {
            String q = title.toLowerCase(java.util.Locale.ROOT);
            if (q.length() > 60) q = q.substring(0, 60);
            JSONArray d = new JSONObject(get("https://v3.sg.media-imdb.com/suggestion/x/" + Uri.encode(q) + ".json")).optJSONArray("d");
            for (int i = 0; d != null && i < d.length(); i++) {
                JSONObject x = d.getJSONObject(i);
                if (!x.optString("id").startsWith("tt")) continue;
                if (year == 0 || Math.abs(x.optInt("y") - year) <= 1) return x.optString("id");
            }
        } catch (IOException | JSONException e) {
            return "";
        }
        return "";
    }

    /** IMDb's advanced search, as on imdb.com/search/title. */
    private static List<Item> fetchSearch(String constraints, String sortBy, String order, int first) throws IOException, JSONException {
        String q = "query { advancedTitleSearch(first: " + first + ", constraints: { " + constraints + " },"
                + " sort: {sortBy: " + sortBy + ", sortOrder: " + order + "}) { edges { node { title { " + TITLE_FIELDS
                + " } } } } }";
        JSONArray edges = imdb(q).getJSONObject("data").getJSONObject("advancedTitleSearch").getJSONArray("edges");
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < edges.length(); i++) {
            Item it = parseTitle(edges.getJSONObject(i).getJSONObject("node").optJSONObject("title"));
            if (it != null) out.add(it);
        }
        return out;
    }

    /**
     * What is trending this week in the country on the apps the owner has (JustWatch, which counts what its
     * visitors there look at), each looked up on IMDb. Below 6.5 or 2,000 votes is left out: on its own the
     * list carries soft-porn web series nobody in the house is after.
     */
    private static Collection trendingHere(Placer placer, String label, String country) {
        List<Item> items = new ArrayList<>();
        try {
            JSONArray pk = new JSONArray();
            JSONObject packages = post(JW_URL, new JSONObject().put("query",
                    "query { packages(country: " + country + ", platform: WEB) { shortName technicalName } }"));
            JSONArray all = packages.getJSONObject("data").getJSONArray("packages");
            for (int i = 0; i < all.length(); i++) {
                JSONObject p = all.getJSONObject(i);
                for (Service sv : placer.services) if (sv.tech.equals(p.optString("technicalName"))) pk.put(p.optString("shortName"));
            }
            if (pk.length() == 0) return new Collection(label, false, items);
            JSONObject d = post(JW_URL, new JSONObject()
                    .put("query", "query T($country: Country!, $pk: [String!]) { popularTitles(country: $country, first: 80,"
                            + " sortBy: TRENDING, filter: {objectTypes: [MOVIE, SHOW], packages: $pk}) { edges { node {"
                            + " content(country: $country, language: \"en\") { externalIds { imdbId } } } } } }")
                    .put("variables", new JSONObject().put("country", country).put("pk", pk)));
            JSONArray edges = d.getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges");
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < edges.length(); i++) {
                JSONObject ext = edges.getJSONObject(i).getJSONObject("node").getJSONObject("content").optJSONObject("externalIds");
                String id = ext != null && !ext.isNull("imdbId") ? ext.optString("imdbId") : "";
                if (id.startsWith("tt") && !ids.contains(id)) ids.add(id);
            }
            for (Item it : imdbTitles(ids)) {
                if (items.size() >= COLLECTION_SIZE) break;
                if (it.rating < 6.5 || it.votes < 2000 || unwanted(it)) continue;
                placer.placeOnly(it);
                if (STREMIO.equals(it.service)) continue;   // trending here means on an app here
                fetchPoster(placer.c, it);
                items.add(it);
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "collection " + label + " failed", e);
        }
        return new Collection(label, false, items);
    }

    /** Films per director, best rated first, from those enough people have rated to mean it. */
    private static final int DIRECTOR_WORKS = 12;

    /**
     * One tile per director (their photo and name); OK on it opens their best films. All the directors come in
     * one IMDb request, then their picks in another. Posters are fetched now; where each film streams is looked
     * up when it is opened (placeThenOpen), as a refresh would otherwise ask JustWatch about 200 films.
     */
    private static Collection directors(Placer placer, List<String> ids) {
        List<Item> out = new ArrayList<>();
        try {
            StringBuilder q = new StringBuilder("query {");
            for (int i = 0; i < ids.size(); i++) {
                q.append(" d").append(i).append(": name(id: \"").append(ids.get(i)).append("\") { id nameText { text }")
                        .append(" primaryImage { url } credits(first: 100, filter: {categories: [\"director\"]}) { edges {")
                        .append(" node { title { id titleType { id } ratingsSummary { aggregateRating voteCount } } } } } }");
            }
            JSONObject data = imdb(q.append(" }").toString()).getJSONObject("data");
            List<List<String>> picks = new ArrayList<>();
            List<String> all = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                JSONObject n = data.optJSONObject("d" + i);
                List<String> mine = new ArrayList<>();
                picks.add(mine);
                if (n == null) continue;
                List<JSONObject> films = new ArrayList<>();
                JSONArray edges = n.getJSONObject("credits").getJSONArray("edges");
                int most = 0;
                for (int k = 0; k < edges.length(); k++) {
                    JSONObject r = edges.getJSONObject(k).getJSONObject("node").getJSONObject("title").optJSONObject("ratingsSummary");
                    if (r != null) most = Math.max(most, r.optInt("voteCount"));
                }
                for (int k = 0; k < edges.length(); k++) {
                    JSONObject t = edges.getJSONObject(k).getJSONObject("node").getJSONObject("title");
                    JSONObject tt = t.optJSONObject("titleType");
                    String type = tt != null ? tt.optString("id") : "";
                    JSONObject r = t.optJSONObject("ratingsSummary");
                    // Their films: not a series they directed an episode of, nor a TV cut of a film.
                    if (r == null || !(type.equals("movie") || type.equals("tvMovie"))) continue;
                    // Re-cuts and compilations ("Extended Version") have a sliver of the votes of the film itself.
                    int votes = r.optInt("voteCount");
                    if (votes < 500 || votes * 20 < most || r.optDouble("aggregateRating", 0) < 6.5) continue;
                    films.add(t);
                }
                Collections.sort(films, (a, b) -> Double.compare(b.optJSONObject("ratingsSummary").optDouble("aggregateRating", 0),
                        a.optJSONObject("ratingsSummary").optDouble("aggregateRating", 0)));
                for (int k = 0; k < Math.min(DIRECTOR_WORKS, films.size()); k++) {
                    String id = films.get(k).getString("id");
                    mine.add(id);
                    if (!all.contains(id)) all.add(id);
                }
            }
            Map<String, Item> titles = new HashMap<>();
            for (int i = 0; i < all.size(); i += 100) {
                for (Item it : imdbTitles(all.subList(i, Math.min(all.size(), i + 100)))) titles.put(it.imdb, it);
            }
            for (int i = 0; i < ids.size(); i++) {
                JSONObject n = data.optJSONObject("d" + i);
                if (n == null || picks.get(i).isEmpty()) continue;
                Item d = new Item();
                d.imdb = n.getString("id");
                d.title = n.getJSONObject("nameText").getString("text");
                d.type = DIRECTOR;
                JSONObject img = n.optJSONObject("primaryImage");
                d.posterUrl = img != null ? img.getString("url").replaceFirst("\\._V1_.*\\.jpg$", "._V1_UX300_.jpg") : null;
                List<String> names = new ArrayList<>();
                for (String id : picks.get(i)) {
                    Item w = titles.get(id);
                    if (w == null || unwanted(w)) continue;
                    Item known = placer.saved.get(id);
                    if (known != null && known.checked > 0 && placer.now - known.checked < RECHECK_MS) Placer.copyPlace(known, w);
                    else w.service = null;   // not looked up yet: placeThenOpen does it on the way to opening
                    fetchPoster(placer.c, w);
                    d.works.add(w);
                    if (names.size() < 3) names.add(w.title + (w.year > 0 ? " (" + w.year + ")" : ""));
                }
                d.subtitle = d.works.size() + " films";
                d.plot = "Best known for " + TextUtils.join(", ", names);
                d.checked = placer.now;
                fetchPoster(placer.c, d);
                out.add(d);
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "directors failed", e);
        }
        return new Collection("Directors", false, out);
    }

    /** Opens a title once it knows where it streams, looking that up first if nobody has yet. */
    static void placeThenOpen(Context c, Item it) {
        if (it.service != null) {
            open(c, it);
            return;
        }
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                Config cfg = Config.load(app);
                findPlace(it, installedServices(app, cfg.watchlistServices), cfg.watchlistCountry);
                it.checked = System.currentTimeMillis();
            } catch (IOException | JSONException e) {
                it.service = STREMIO;
            }
            new Handler(Looper.getMainLooper()).post(() -> open(c, it));
        }, "place").start();
    }

    /** Episode thumbnails no row or Continue shows any more; each new episode brings its own. */
    private static void dropOldThumbnails(Context c, List<Collection> cols, List<Item> history) {
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Collection col : cols) for (Item it : col.items) keep.add(it.imdb + ".jpg");
        for (Item it : history) keep.add(it.imdb + ".jpg");
        File[] files = poster(c, "x").getParentFile().listFiles();
        for (int i = 0; files != null && i < files.length; i++) {
            String name = files[i].getName();
            //noinspection ResultOfMethodCallIgnored
            if (name.startsWith("yt_") && !keep.contains(name)) files[i].delete();
        }
    }

    /** IMDb titles by ID, in the order asked for. */
    private static List<Item> imdbTitles(List<String> ids) throws IOException, JSONException {
        List<Item> out = new ArrayList<>();
        if (ids.isEmpty()) return out;
        JSONArray q = new JSONArray(ids);
        JSONArray ts = imdb("query { titles(ids: " + q + ") { " + TITLE_FIELDS + " } }").getJSONObject("data").getJSONArray("titles");
        for (int i = 0; i < ts.length(); i++) {
            Item it = parseTitle(ts.optJSONObject(i));
            if (it != null) out.add(it);
        }
        return out;
    }

    static JSONObject imdb(String query) throws IOException, JSONException {
        return post("https://caching.graphql.imdb.com/", new JSONObject().put("query", query),
                "x-imdb-client-name", "imdb-web-next-localized");
    }

    private static Item parseTitle(JSONObject t) throws JSONException {
        if (t == null || !t.has("id")) return null;
        Item it = new Item();
        it.imdb = t.getString("id");
        it.title = t.getJSONObject("titleText").getString("text");
        JSONObject year = t.optJSONObject("releaseYear");
        it.year = year != null ? year.optInt("year") : 0;
        JSONObject type = t.optJSONObject("titleType");
        it.type = type != null ? type.optString("id") : "movie";
        JSONObject img = t.optJSONObject("primaryImage");
        // IMDb's image server scales on request: 300 px wide (about 30 KB) is sharp at the poster's size on a
        // 1080p screen, instead of a 2000 px original.
        it.posterUrl = img != null ? img.getString("url").replaceFirst("\\._V1_.*\\.jpg$", "._V1_UX300_.jpg") : null;
        JSONObject ratings = t.optJSONObject("ratingsSummary");
        it.rating = ratings != null ? ratings.optDouble("aggregateRating", 0) : 0;
        if (Double.isNaN(it.rating)) it.rating = 0;
        it.votes = ratings != null ? ratings.optInt("voteCount") : 0;
        JSONObject runtime = t.optJSONObject("runtime");
        it.runtimeMin = runtime != null ? runtime.optInt("seconds") / 60 : 0;
        JSONObject plot = t.optJSONObject("plot");
        JSONObject plotText = plot != null ? plot.optJSONObject("plotText") : null;
        it.plot = plotText != null && !plotText.isNull("plainText") ? plotText.optString("plainText") : null;
        JSONObject coo = t.optJSONObject("countriesOfOrigin");
        JSONArray cs = coo != null ? coo.optJSONArray("countries") : null;
        for (int k = 0; cs != null && k < cs.length(); k++) {
            String v = cs.getJSONObject(k).optString("text");
            if (!v.isEmpty()) it.countries.add(v);
        }
        // Indian films by the name the household knows them by (Pyaasa, not "Thirsty"), when it is in Latin letters.
        JSONObject original = t.optJSONObject("originalTitleText");
        String orig = original != null ? original.optString("text") : "";
        if (it.countries.contains("India") && !orig.isEmpty() && !orig.matches(".*[\\u0370-\\u1FFF\\u2E80-\\uFFFF].*")) it.title = orig;
        JSONObject dirs = t.optJSONObject("directors");
        JSONArray ds = dirs != null ? dirs.optJSONArray("edges") : null;
        for (int k = 0; ds != null && k < ds.length(); k++) {
            JSONObject name = ds.getJSONObject(k).getJSONObject("node").optJSONObject("name");
            JSONObject nt = name != null ? name.optJSONObject("nameText") : null;
            if (nt != null && !nt.optString("text").isEmpty()) it.directors.add(nt.optString("text"));
        }
        JSONObject tg = t.optJSONObject("titleGenres");
        JSONArray gs = tg != null ? tg.optJSONArray("genres") : null;
        for (int k = 0; gs != null && k < gs.length(); k++) {
            JSONObject g = gs.getJSONObject(k).optJSONObject("genre");
            if (g != null && !g.optString("text").isEmpty()) it.genres.add(g.optString("text"));
        }
        return it;
    }

    // ---- podcasts ----------------------------------------------------------------------------------

    /** A show with no new episode for this long has stopped; it is left out however popular it once was. */
    private static final long PODCAST_MAX_AGE_MS = 60L * 24 * 60 * 60 * 1000;
    /** Scripts of other Indian languages, Urdu and Arabic, for a household that watches in Hindi or English. */
    private static final java.util.regex.Pattern OTHER_SCRIPT = java.util.regex.Pattern.compile(
            "[\u0600-\u06FF\u0980-\u09FF\u0A00-\u0A7F\u0A80-\u0AFF\u0B00-\u0B7F\u0B80-\u0BFF\u0C00-\u0C7F\u0C80-\u0CFF\u0D00-\u0D7F]");
    private static final java.util.regex.Pattern OTHER_LANGUAGE = java.util.regex.Pattern.compile(
            "\\b(tamil|telugu|bangla|bengali|kannada|malayalam|marathi|quran)\\b", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern SHORTS = java.util.regex.Pattern.compile(
            "\\bshorts?\\b|शॉर्ट्स", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Episodes about politics, religion or astrology, which this household does not want on its home screen. */
    private static final java.util.regex.Pattern AVOID_TOPICS = java.util.regex.Pattern.compile(
            "\\b(politic\\w*|elections?|modi|rahul gandhi|bjp|congress|parliament|ministers?|geopolitic\\w*|war|nuclear"
                    + "|pakistan|god|gods|bhagwan|bhakti|krishna|shiva?|mahadev|hanuman|ram mandir|mandir|temples?|puja|pujo"
                    + "|durga|navratri|quran|allah|jesus|church|spiritual\\w*|premanand|maharaj|sadhguru|baba|guruji"
                    + "|astrolog\\w*|jyotish\\w*|kundli|horoscopes?|rashifal|zodiac|tarot|numerolog\\w*|vastu|black magic"
                    + "|tantra|ghosts?|paranormal|bhoot|aghori|occult|horror)\\b"
                    + "|राजनीति|चुनाव|मोदी|भगवान|मंदिर|पूजा|ज्योतिष|राशि|कुंडली|भूत",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    /** A chosen show keeps its place for this long without a new episode (a season break), then drops out. */
    private static final long CHANNEL_MAX_AGE_MS = 120L * 24 * 60 * 60 * 1000;

    /**
     * The household's own shows: for each YouTube channel, its newest full-length video (the channel's
     * "UULF" list leaves out Shorts and live streams) that is not about politics, religion or astrology,
     * newest first. The tile is the channel's picture, fetched once and then kept.
     */
    private static List<Item> fetchChannelShows(Context c, List<String> channels, Map<String, Item> saved) {
        List<Item> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (String channel : channels) {
            if (!channel.startsWith("UC") || channel.length() != 24) continue;
            try {
                Episode pick = null;
                for (Episode e : episodes("UULF" + channel.substring(2))) {
                    if (AVOID_TOPICS.matcher(e.title != null ? e.title : "").find()) continue;
                    if (pick == null || e.published > pick.published) pick = e;
                }
                if (pick == null || now - pick.published > CHANNEL_MAX_AGE_MS) continue;
                out.add(episodeItem(pick, pick.author));
            } catch (IOException | org.xmlpull.v1.XmlPullParserException e) {
                Log.w(TAG, "channel feed failed: " + channel, e);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.published, a.published));
        return out;
    }

    /**
     * One episode as a card: its own thumbnail (YouTube's 480 × 360, cropped to 16:9 on the card), its title,
     * the show or channel under it, and the opening of its description for the details card.
     */
    private static Item episodeItem(Episode e, String show) {
        Item it = new Item();
        it.imdb = "yt_" + e.video;
        it.title = e.title;
        it.subtitle = show;
        it.type = PODCAST;
        it.posterUrl = "https://i.ytimg.com/vi/" + e.video + "/hqdefault.jpg";
        it.published = e.published;
        it.plot = summary(e.description);
        it.service = "YouTube";
        it.pkg = youtubePkg;
        it.intentUri = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + e.video))
                .setPackage(it.pkg).toUri(Intent.URI_INTENT_SCHEME);
        it.checked = System.currentTimeMillis();
        return it;
    }

    /** The first real sentence or two of a description: not a link, a hashtag line or a "subscribe" plea. */
    private static String summary(String description) {
        if (description == null) return null;
        for (String line : description.split("\n")) {
            String l = line.trim();
            if (l.length() < 40 || l.contains("http") || l.startsWith("#") || l.toLowerCase(java.util.Locale.ROOT).contains("subscribe")) continue;
            if (l.length() <= 220) return l;
            int cut = l.lastIndexOf(' ', 217);
            return l.substring(0, cut > 120 ? cut : 217) + "…";
        }
        return null;
    }

    /**
     * YouTube's "Popular podcasts" for the country, from its public Podcasts page (YouTube has no feed for
     * that shelf). Each show plays its newest episode, found in the show's own feed: the shelf's video is the
     * top of the show's playlist, which for some shows is years old. Shows with nothing new in 60 days are left
     * out. With Hindi as the household language, so are shows mostly in another language: half or more of their
     * recent episodes titled or described in another script, or named for one (Tamil, Bangla, Quran...).
     */
    private static List<Item> fetchPodcasts(String country, String lang) throws IOException, JSONException {
        String html = get("https://www.youtube.com/podcasts?gl=" + country + "&hl=en");
        int start = html.indexOf("var ytInitialData = ");
        int end = start < 0 ? -1 : html.indexOf("};</script>", start);
        if (start < 0 || end < 0) throw new IOException("no ytInitialData on the Podcasts page");
        JSONObject shelf = findShelf(new JSONObject(html.substring(start + "var ytInitialData = ".length(), end + 1)),
                "Popular podcasts");
        html = null;   // 2 MB of page; the TV has little memory to spare
        List<Item> out = new ArrayList<>();
        JSONArray contents = shelf != null ? shelf.optJSONArray("contents") : null;
        long now = System.currentTimeMillis();
        for (int i = 0; contents != null && i < contents.length() && out.size() < COLLECTION_SIZE; i++) {
            JSONObject lv = contents.getJSONObject(i).optJSONObject("richItemRenderer");
            lv = lv != null ? lv.optJSONObject("content") : null;
            lv = lv != null ? lv.optJSONObject("lockupViewModel") : null;
            JSONObject meta = lv != null ? lv.optJSONObject("metadata") : null;
            meta = meta != null ? meta.optJSONObject("lockupMetadataViewModel") : null;
            if (meta == null || lv.optString("contentId").isEmpty()) continue;
            String playlist = lv.optString("contentId");
            String title = meta.getJSONObject("title").optString("content");
            String channel = findString(meta.optJSONObject("metadata"), "content");
            String show = title + " " + (channel != null ? channel : "");
            if (SHORTS.matcher(show).find() || AVOID_TOPICS.matcher(show).find()) continue;
            if (lang.equals("hi") && (OTHER_SCRIPT.matcher(show).find() || OTHER_LANGUAGE.matcher(show).find())) continue;
            List<Episode> episodes;
            try {
                episodes = episodes(playlist);
            } catch (IOException | org.xmlpull.v1.XmlPullParserException e) {
                Log.w(TAG, "podcast feed failed: " + title, e);
                continue;
            }
            Episode newest = null;
            int otherLanguage = 0;
            for (Episode e : episodes) {
                boolean avoid = AVOID_TOPICS.matcher(e.title != null ? e.title : "").find();
                if (!avoid && (newest == null || e.published > newest.published)) newest = e;
                if (OTHER_SCRIPT.matcher(e.text).find() || OTHER_LANGUAGE.matcher(e.text).find()) otherLanguage++;
            }
            if (newest == null || now - newest.published > PODCAST_MAX_AGE_MS) continue;
            if (lang.equals("hi") && otherLanguage * 2 >= episodes.size()) continue;
            Item it = episodeItem(newest, title);
            // In its playlist, so the show carries on to the next episode when this one ends.
            it.intentUri = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/watch?v=" + newest.video + "&list=" + playlist))
                    .setPackage(it.pkg).toUri(Intent.URI_INTENT_SCHEME);
            out.add(it);
        }
        return out;
    }

    private static final class Episode {
        String video, title, author, description, text = "";
        long published;
    }

    /** A playlist's feed: its first 15 videos in playlist order, each with its date, title and description. */
    private static List<Episode> episodes(String playlist) throws IOException, org.xmlpull.v1.XmlPullParserException {
        String xml = get("https://www.youtube.com/feeds/videos.xml?playlist_id=" + Uri.encode(playlist));
        org.xmlpull.v1.XmlPullParser p = android.util.Xml.newPullParser();
        // Names as written ("yt:videoId", "media:description"): Android's parser otherwise drops the prefixes.
        p.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new java.io.StringReader(xml));
        java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.ROOT);
        List<Episode> out = new ArrayList<>();
        Episode e = null;
        for (int ev = p.getEventType(); ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == org.xmlpull.v1.XmlPullParser.END_TAG && "entry".equals(p.getName())) {
                if (e != null && e.video != null && e.published > 0) out.add(e);
                e = null;
            }
            if (ev != org.xmlpull.v1.XmlPullParser.START_TAG) continue;
            String tag = p.getName();
            if ("entry".equals(tag)) {
                e = new Episode();
            } else if (e != null && "yt:videoId".equals(tag)) {
                e.video = p.nextText();
            } else if (e != null && "title".equals(tag) && e.title == null) {
                e.title = p.nextText();
                e.text += e.title + " ";
            } else if (e != null && "name".equals(tag) && e.author == null) {
                e.author = p.nextText();   // <author><name>, the channel
            } else if (e != null && "media:description".equals(tag)) {
                e.description = p.nextText();
                e.text += e.description;
            } else if (e != null && "published".equals(tag)) {
                try {
                    e.published = iso.parse(p.nextText()).getTime();
                } catch (java.text.ParseException ignored) {
                }
            }
        }
        return out;
    }

    private static JSONObject findShelf(Object node, String title) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject shelf = o.optJSONObject("richShelfRenderer");
            if (shelf != null) {
                JSONObject t = shelf.optJSONObject("title");
                String text = t == null ? "" : t.has("simpleText") ? t.optString("simpleText") : findString(t, "text");
                if (title.equals(text)) return shelf;
            }
            for (java.util.Iterator<String> k = o.keys(); k.hasNext(); ) {
                JSONObject r = findShelf(o.opt(k.next()), title);
                if (r != null) return r;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = findShelf(a.opt(i), title);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** The first string value under this key anywhere below node, depth first. */
    private static String findString(Object node, String key) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            Object v = o.opt(key);
            if (v instanceof String) return (String) v;
            for (java.util.Iterator<String> k = o.keys(); k.hasNext(); ) {
                String r = findString(o.opt(k.next()), key);
                if (r != null) return r;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                String r = findString(a.opt(i), key);
                if (r != null) return r;
            }
        }
        return null;
    }

    static String get(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(20_000);
        // A desktop browser's headers: the page carries its data inline only for them.
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36");
        conn.setRequestProperty("Accept-Language", "en-IN,en;q=0.9");
        try (InputStream in = conn.getInputStream()) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    private static final String JW_URL = "https://apis.justwatch.com/graphql";
    private static final String JW_QUERY = "query S($country: Country!, $q: String!) {"
            + " popularTitles(country: $country, first: 5, filter: {searchQuery: $q}) { edges { node {"
            + " content(country: $country, language: \"en\") { externalIds { imdbId } }"
            + " offers(country: $country, platform: WEB) { monetizationType standardWebURL"
            + " deeplinkURL(platform: ANDROID_TV) package { technicalName } } } } } }";

    /** Sets the item's service and link: the first service in preference order that streams it, else Stremio. */
    static void findPlace(Item it, List<Service> services, String country) throws IOException, JSONException {
        it.service = STREMIO;
        it.pkg = null;
        it.intentUri = null;
        if (services.isEmpty()) return;
        JSONObject vars = new JSONObject().put("country", country).put("q", it.title);
        JSONObject d = post(JW_URL, new JSONObject().put("query", JW_QUERY).put("variables", vars));
        JSONArray edges = d.getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges");
        for (int i = 0; i < edges.length(); i++) {
            JSONObject n = edges.getJSONObject(i).getJSONObject("node");
            JSONObject ids = n.getJSONObject("content").optJSONObject("externalIds");
            if (ids == null || !it.imdb.equals(ids.optString("imdbId"))) continue;
            JSONArray offers = n.optJSONArray("offers");
            if (offers == null) return;
            for (Service s : services) {
                for (int k = 0; k < offers.length(); k++) {
                    JSONObject o = offers.getJSONObject(k);
                    String money = o.optString("monetizationType");
                    // Subscription or free only: rent and buy are not "what can I watch".
                    if (!money.equals("FLATRATE") && !money.equals("FREE") && !money.equals("ADS")) continue;
                    if (!s.tech.equals(o.getJSONObject("package").optString("technicalName"))) continue;
                    // optString turns an explicit JSON null into the text "null", so check isNull first.
                    Intent intent = offerIntent(s, o.isNull("standardWebURL") ? null : o.optString("standardWebURL"),
                            o.isNull("deeplinkURL") ? null : o.optString("deeplinkURL"));
                    if (intent == null) continue;
                    it.service = s.label;
                    it.pkg = s.pkg;
                    it.intentUri = intent.toUri(Intent.URI_INTENT_SCHEME);
                    return;
                }
            }
            return;
        }
    }

    /**
     * The link that opens the title. JustWatch's Android TV links for Netflix and Prime Video start playback, so
     * those use the web URL of the title page, which both apps open in place. SonyLIV ignores its own sony://
     * link and plays the title from its web URL. Hotstar's hotstar://content link opens its title page.
     * An app that doesn't take the deep link (Fire TV's Zee5 takes only zee5.com links) gets the web URL.
     * Whatever the link says, it may only open the service's app.
     */
    static Intent offerIntent(Service s, String web, String deeplink) {
        if (web != null && (s.tech.equals("netflix") || s.tech.equals("amazonprimevideo") || s.tech.equals("sonyliv"))) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(web)).setPackage(s.pkg);
            if (s.tech.equals("netflix")) i.putExtra("source", "30");   // Netflix's launch source for TV home screens
            return i;
        }
        if (deeplink != null) {
            try {
                Intent i = Intent.parseUri(deeplink, Intent.URI_INTENT_SCHEME);
                if (i.getAction() == null) i.setAction(Intent.ACTION_VIEW);
                i.setComponent(null);
                i.setSelector(null);
                i.setFlags(0);
                i.setPackage(s.pkg);
                if (web == null || s.pm.resolveActivity(i, 0) != null) return i;
            } catch (URISyntaxException ignored) {
            }
        }
        return web != null ? new Intent(Intent.ACTION_VIEW, Uri.parse(web)).setPackage(s.pkg) : null;
    }

    static void fetchPoster(Context c, Item it) {
        if (it.posterUrl == null) return;
        File f = poster(c, it.imdb);
        if (f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(it.posterUrl).openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(15_000);
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                out.write(readAll(in));
            } finally {
                conn.disconnect();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(f);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();   // no poster this time; the tile shows the title instead
        }
    }

    // ---- opening ---------------------------------------------------------------------------------

    /** Opens the item where it streams, or in Stremio; falls back to the app itself if its link is refused. */
    static void open(Context c, Item it) {
        PackageManager pm = c.getPackageManager();
        Intent i = null;
        if (!STREMIO.equals(it.service) && it.intentUri != null) {
            try {
                i = Intent.parseUri(it.intentUri, Intent.URI_INTENT_SCHEME);
            } catch (URISyntaxException ignored) {
            }
        }
        if (i == null) i = stremioIntent(c, it);
        if (i == null) {
            Toast.makeText(c, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // A title link must start the app's screen afresh: Stremio 1.10 ignores a link while it is already
        // open in the background and just comes to the front. Plain app launches keep where you left off.
        if (i.getData() != null) i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try {
            c.startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Intent app = i.getPackage() != null ? launchIntent(pm, i.getPackage()) : null;
            if (app == null) {
                Toast.makeText(c, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
            c.startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    /**
     * Stremio's title link, stremio:///detail/{type}/{id}/{videoId}, works in its current apps. Stremio 1.2
     * (still common on Android TV 9 sets) reads every stremio:// link as an add-on to install and shows an
     * error, so there the app just opens and a toast names the title to search for.
     */
    private static Intent stremioIntent(Context c, Item it) {
        PackageManager pm = c.getPackageManager();
        for (String pkg : new String[]{"com.stremio.one", "com.stremio.app"}) {
            Intent app = launchIntent(pm, pkg);
            if (app == null) continue;
            if (legacyStremio(pm, pkg)) {
                Toast.makeText(c, c.getString(R.string.watchlist_find_in_stremio, it.title), Toast.LENGTH_LONG).show();
                return app;
            }
            boolean series = !(it.type.equals("movie") || it.type.equals("tvMovie") || it.type.equals("short") || it.type.equals("video"));
            String uri = series
                    ? "stremio:///detail/series/" + it.imdb
                    : "stremio:///detail/movie/" + it.imdb + "/" + it.imdb;
            return new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg);
        }
        return null;
    }

    private static boolean legacyStremio(PackageManager pm, String pkg) {
        try {
            PackageInfo info = pm.getPackageInfo(pkg, 0);
            String v = info.versionName != null ? info.versionName : "";
            return v.matches("^1\\.[0-5](\\..*)?$");
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    // ---- http ------------------------------------------------------------------------------------

    static JSONObject post(String url, JSONObject body, String... headers) throws IOException, JSONException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(20_000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("content-type", "application/json");
        for (int i = 0; i + 1 < headers.length; i += 2) conn.setRequestProperty(headers[i], headers[i + 1]);
        try {
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (conn.getResponseCode() != 200) throw new IOException(url + " answered " + conn.getResponseCode());
            try (InputStream in = conn.getInputStream()) {
                JSONObject d = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
                if (d.optJSONObject("data") == null) throw new IOException(url + " returned no data");
                return d;
            }
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[16 * 1024];
        for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }
}
