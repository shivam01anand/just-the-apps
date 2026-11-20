package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One person's walls (config.json, "profiles" → "walls"): each wall is a list of posters to hang. A wall comes from
 * the IMDb watchlist, an IMDb search (the best rated of a genre, of this year, of series), the Best Picture winners,
 * a list of titles, directors and their films, the newest videos of chosen YouTube channels, or the best rated
 * films of every country on a map.
 *
 * Built on the TV in the background, a wall at a time, and kept in the person's folder, so the walls open at once
 * and still work offline. Where a title plays is only looked up when the viewer stops on it: asking JustWatch about
 * a thousand titles in one go gets a home's address refused for hours.
 */
final class WallData {

    private static final String TAG = "TvHome.Walls";
    private static final int DATA_VERSION = 2;
    static final String POSTERS = "posters", VIDEO = "video", PEOPLE = "people", MAP = "map";
    private static final long HOUR = 60L * 60 * 1000, DAY = 24 * HOUR;
    private static final long RETRY_MS = 30L * 60 * 1000, PLACE_MS = 7 * DAY;
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final ExecutorService placer = Executors.newSingleThreadExecutor();

    /** A poster, a video's still or a director's face, and where it hangs. */
    static final class Tile {
        String id, title, img, type = "movie", plot, country, by, group;
        /** What a film lover looks for: its title where it was made, its language, "Black and White", its frame's
         *  shape ("2.39:1"), its place in IMDb's top 250 and its Oscars ("Won 3 Oscars"). Null or 0 when unknown. */
        String original, language, color, ratio, award;
        int rank;
        int year, mins, votes, count;
        long published, views;
        float rating;
        /** Hung at double size: the best rated of its wall. */
        boolean big;
        final List<String> genres = new ArrayList<>();
        /** Its directors' IMDb IDs. */
        final List<String> people = new ArrayList<>();
        /** A director's films. */
        final List<Tile> sub = new ArrayList<>();
        /** Where it plays and a frame from it (JustWatch's address for one; "" when it has none), once looked up. */
        String service, pkg, intentUri, still;
        long checked;
        /** Its column and row on the wall, and how many spaces wide and tall it is. */
        int c, r, w = 1;

        boolean video() {
            return "video".equals(type);
        }

        boolean person() {
            return "person".equals(type);
        }

        boolean film() {
            return "movie".equals(type) || "tvMovie".equals(type);
        }

        boolean placed() {
            return service != null && System.currentTimeMillis() - checked < PLACE_MS;
        }
    }

    static final class Country {
        String code;
        final List<Tile> films = new ArrayList<>();
    }

    static final class Wall {
        String name, kind = POSTERS, what = "", key = "";
        long at;
        final List<Tile> tiles = new ArrayList<>();
        final List<Country> countries = new ArrayList<>();

        boolean empty() {
            return MAP.equals(kind) ? countries.isEmpty() : tiles.isEmpty();
        }

        int size() {
            return MAP.equals(kind) ? countries.size() : tiles.size();
        }
    }

    interface Listener {
        void onWalls(List<Wall> walls);
    }

    private WallData() {
    }

    static File dir(Context c, String profile) {
        return ChannelData.dir(c, profile);
    }

    /** A tile's picture, kept on the TV once downloaded. */
    static File picture(File dir, Tile t) {
        return new File(new File(dir, "posters"), t.id + ".jpg");
    }

    // ---- hanging ------------------------------------------------------------------------------

    /** Which tile is in each space of a wall: [column][row]. */
    static final class Hang {
        Tile[][] cells = new Tile[0][];
        int rows;

        Tile at(int c, int r) {
            return c >= 0 && c < cells.length && r >= 0 && r < rows ? cells[c][r] : null;
        }
    }

    /**
     * Hangs the tiles in columns, left to right, in the order given. The big ones take four spaces and are spread
     * along the wall. With sizes off every tile takes one space.
     */
    static Hang hang(List<Tile> tiles, int rows, boolean sizes) {
        List<Tile> bigs = new ArrayList<>(), smalls = new ArrayList<>();
        for (Tile t : tiles) (sizes && t.big ? bigs : smalls).add(t);
        int total = smalls.size() + bigs.size() * 4;
        int gap = bigs.isEmpty() ? Integer.MAX_VALUE : Math.max(4, total / bigs.size() - 4);
        List<Tile[]> cols = new ArrayList<>();
        int bi = 0, si = 0, since = bigs.isEmpty() ? 0 : gap / 2, c = 0;
        while (si < smalls.size() || bi < bigs.size()) {
            while (cols.size() < c + 2) cols.add(new Tile[rows]);
            for (int r = 0; r < rows; r++) {
                if (cols.get(c)[r] != null) continue;
                boolean room = r + 1 < rows && cols.get(c)[r + 1] == null && cols.get(c + 1)[r] == null && cols.get(c + 1)[r + 1] == null;
                int want = gap == Integer.MAX_VALUE ? gap : gap + (bi * 5) % 4 - 1;
                if (bi < bigs.size() && room && (since >= want || si >= smalls.size())) {
                    Tile t = bigs.get(bi++);
                    put(cols, t, c, r, 2);
                    since = 0;
                } else if (si < smalls.size()) {
                    put(cols, smalls.get(si++), c, r, 1);
                    since++;
                } else if (bi < bigs.size()) {
                    put(cols, bigs.get(bi++), c, r, 1);   // no room left for a big one: it hangs small
                }
            }
            c++;
        }
        while (!cols.isEmpty() && empty(cols.get(cols.size() - 1))) cols.remove(cols.size() - 1);
        Hang h = new Hang();
        h.rows = rows;
        h.cells = cols.toArray(new Tile[0][]);
        return h;
    }

    private static void put(List<Tile[]> cols, Tile t, int c, int r, int w) {
        t.c = c;
        t.r = r;
        t.w = w;
        for (int x = 0; x < w; x++) for (int y = 0; y < w; y++) cols.get(c + x)[r + y] = t;
    }

    private static boolean empty(Tile[] col) {
        for (Tile t : col) if (t != null) return false;
        return true;
    }

    // ---- saved walls --------------------------------------------------------------------------

    static List<Wall> load(Context c, String profile) {
        File f = new File(dir(c, profile), "walls.json");
        List<Wall> out = new ArrayList<>();
        if (!f.isFile()) return out;
        try {
            JSONArray a = new JSONArray(read(f));
            Map<String, JSONObject> places = places(c, profile);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Wall w = new Wall();
                w.name = o.getString("name");
                w.kind = o.optString("kind", POSTERS);
                w.what = o.optString("what");
                w.key = o.optString("key");
                w.at = o.optLong("at");
                JSONArray tiles = o.optJSONArray("tiles");
                for (int k = 0; tiles != null && k < tiles.length(); k++) w.tiles.add(tile(tiles.getJSONObject(k), places));
                JSONArray cs = o.optJSONArray("countries");
                for (int k = 0; cs != null && k < cs.length(); k++) {
                    JSONObject co = cs.getJSONObject(k);
                    Country country = new Country();
                    country.code = co.getString("code");
                    JSONArray films = co.getJSONArray("films");
                    for (int j = 0; j < films.length(); j++) country.films.add(tile(films.getJSONObject(j), places));
                    w.countries.add(country);
                }
                out.add(w);
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "saved walls unreadable", e);
            out.clear();
        }
        return out;
    }

    private static String read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        }
    }

    private static void write(File f, String text) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IOException("rename failed");
    }

    private static Tile tile(JSONObject o, Map<String, JSONObject> places) throws JSONException {
        Tile t = new Tile();
        t.id = o.getString("id");
        t.title = o.optString("title", t.id);
        t.img = o.optString("img", null);
        t.type = o.optString("type", "movie");
        t.plot = o.optString("plot", null);
        t.country = o.optString("country", null);
        t.by = o.optString("by", null);
        t.group = o.optString("group", null);
        t.original = o.optString("original", null);
        t.language = o.optString("language", null);
        t.color = o.optString("color", null);
        t.ratio = o.optString("ratio", null);
        t.award = o.optString("award", null);
        t.rank = o.optInt("rank");
        t.year = o.optInt("year");
        t.mins = o.optInt("mins");
        t.votes = o.optInt("votes");
        t.count = o.optInt("count");
        t.published = o.optLong("published");
        t.views = o.optLong("views");
        t.rating = (float) o.optDouble("rating", 0);
        t.big = o.optBoolean("big");
        strings(o.optJSONArray("genres"), t.genres);
        strings(o.optJSONArray("people"), t.people);
        JSONArray sub = o.optJSONArray("sub");
        for (int i = 0; sub != null && i < sub.length(); i++) t.sub.add(tile(sub.getJSONObject(i), places));
        JSONObject p = places.get(t.id);
        if (p != null) {
            t.service = p.optString("service", null);
            t.pkg = p.optString("pkg", null);
            t.intentUri = p.optString("intent", null);
            t.still = p.optString("still", null);
            t.checked = p.optLong("at");
        }
        return t;
    }

    private static void strings(JSONArray a, List<String> into) {
        for (int i = 0; a != null && i < a.length(); i++) into.add(a.optString(i));
    }

    private static JSONObject json(Tile t) throws JSONException {
        JSONObject o = new JSONObject().put("id", t.id).put("title", t.title).put("img", t.img).put("type", t.type)
                .put("plot", t.plot).put("country", t.country).put("by", t.by).put("group", t.group)
                .put("original", t.original).put("language", t.language).put("color", t.color).put("ratio", t.ratio)
                .put("award", t.award).put("rank", t.rank)
                .put("year", t.year).put("mins", t.mins).put("votes", t.votes).put("count", t.count)
                .put("published", t.published).put("views", t.views).put("rating", t.rating).put("big", t.big)
                .put("genres", new JSONArray(t.genres)).put("people", new JSONArray(t.people));
        if (!t.sub.isEmpty()) {
            JSONArray sub = new JSONArray();
            for (Tile s : t.sub) sub.put(json(s));
            o.put("sub", sub);
        }
        return o;
    }

    private static void save(Context c, String profile, List<Wall> walls) throws IOException, JSONException {
        JSONArray a = new JSONArray();
        for (Wall w : walls) {
            JSONArray tiles = new JSONArray();
            for (Tile t : w.tiles) tiles.put(json(t));
            JSONArray cs = new JSONArray();
            for (Country country : w.countries) {
                JSONArray films = new JSONArray();
                for (Tile t : country.films) films.put(json(t));
                cs.put(new JSONObject().put("code", country.code).put("films", films));
            }
            a.put(new JSONObject().put("name", w.name).put("kind", w.kind).put("what", w.what).put("key", w.key)
                    .put("at", w.at).put("tiles", tiles).put("countries", cs));
        }
        write(new File(dir(c, profile), "walls.json"), a.toString());
    }

    // ---- where a title plays ------------------------------------------------------------------

    private static Map<String, JSONObject> places(Context c, String profile) {
        Map<String, JSONObject> out = new HashMap<>();
        File f = new File(dir(c, profile), "places.json");
        if (!f.isFile()) return out;
        try {
            JSONObject o = new JSONObject(read(f));
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String id = it.next();
                out.put(id, o.getJSONObject(id));
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "saved places unreadable", e);
        }
        return out;
    }

    /** A frame from the title, kept on the TV once downloaded. */
    static File still(Context c, String profile, Tile t) {
        return ChannelData.still(c, profile, t.id);
    }

    private static final String JW_QUERY = "query S($country: Country!, $q: String!) {"
            + " popularTitles(country: $country, first: 6, filter: {searchQuery: $q}) { edges { node {"
            + " content(country: $country, language: \"en\") { externalIds { imdbId } backdrops { backdropUrl } }"
            + " offers(country: $country, platform: WEB) { monetizationType standardWebURL"
            + " deeplinkURL(platform: ANDROID_TV) package { technicalName } } } } } }";

    /**
     * Looks up which of the person's apps has the title (else Stremio) and fetches a frame from it. Runs done on
     * the main thread once it knows where it plays, and again when the frame has arrived. One title at a time, and
     * only for titles the viewer stops on. A failed lookup leaves the tile as it was.
     */
    static void place(Context context, String profile, Tile t, Runnable done) {
        if (t.video() || t.person()) return;
        final Context c = context.getApplicationContext();
        placer.execute(() -> {
            Handler main = new Handler(Looper.getMainLooper());
            try {
                if (!t.placed() || t.still == null) {
                    Config cfg = Config.load(c);
                    lookUp(t, Watchlist.installedServices(c, cfg.watchlistServices), cfg.watchlistCountry);
                    t.checked = System.currentTimeMillis();
                    Map<String, JSONObject> all = places(c, profile);
                    all.put(t.id, new JSONObject().put("service", t.service).put("pkg", t.pkg).put("intent", t.intentUri)
                            .put("still", t.still).put("at", t.checked));
                    JSONObject o = new JSONObject();
                    for (Map.Entry<String, JSONObject> e : all.entrySet()) o.put(e.getKey(), e.getValue());
                    write(new File(dir(c, profile), "places.json"), o.toString());
                    main.post(done);
                }
                File f = still(c, profile, t);
                if (!f.isFile() && !t.still.isEmpty()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.getParentFile().mkdirs();
                    if (WallImages.fetch(t.still, f)) main.post(done);
                }
            } catch (IOException | JSONException e) {
                Log.w(TAG, "where " + t.title + " plays: " + e.getMessage());
            }
        });
    }

    private static void lookUp(Tile t, List<Watchlist.Service> services, String country) throws IOException, JSONException {
        JSONObject vars = new JSONObject().put("country", country).put("q", t.title);
        JSONObject d = Watchlist.post("https://apis.justwatch.com/graphql", new JSONObject().put("query", JW_QUERY).put("variables", vars));
        JSONArray edges = d.getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges");
        t.service = ChannelData.STREMIO;
        t.pkg = null;
        t.intentUri = null;
        t.still = "";
        for (int i = 0; i < edges.length(); i++) {
            JSONObject n = edges.getJSONObject(i).getJSONObject("node");
            JSONObject content = n.getJSONObject("content");
            JSONObject ext = content.optJSONObject("externalIds");
            if (ext == null || !t.id.equals(ext.optString("imdbId"))) continue;
            JSONArray offers = n.optJSONArray("offers");
            outer:
            for (Watchlist.Service s : services) {
                for (int k = 0; offers != null && k < offers.length(); k++) {
                    JSONObject o = offers.getJSONObject(k);
                    String money = o.optString("monetizationType");
                    if (!money.equals("FLATRATE") && !money.equals("FREE") && !money.equals("ADS")) continue;
                    if (!s.tech.equals(o.getJSONObject("package").optString("technicalName"))) continue;
                    android.content.Intent intent = Watchlist.offerIntent(s, o.isNull("standardWebURL") ? null : o.optString("standardWebURL"),
                            o.isNull("deeplinkURL") ? null : o.optString("deeplinkURL"));
                    if (intent == null) continue;
                    t.service = s.label;
                    t.pkg = s.pkg;
                    t.intentUri = intent.toUri(android.content.Intent.URI_INTENT_SCHEME);
                    break outer;
                }
            }
            JSONArray backs = content.optJSONArray("backdrops");
            JSONObject first = backs != null && backs.length() > 0 ? backs.getJSONObject(0) : null;
            String url = first != null ? first.optString("backdropUrl", null) : null;
            if (url != null) t.still = "https://images.justwatch.com" + url.replace("{profile}", "s1920").replace("{format}", "jpg");
            return;
        }
    }

    // ---- building -----------------------------------------------------------------------------

    private static long life(String from) {
        switch (from) {
            case "youtube":
                return 3 * HOUR;
            case "watchlist":
                return 12 * HOUR;
            case "year":
                return DAY;
            default:
                return 7 * DAY;
        }
    }

    /**
     * Rebuilds, in the background, the walls that are missing, changed in config.json or old. The listener gets
     * the whole list on the main thread after each wall, so the first one shows as soon as it is ready.
     */
    static void refreshIfStale(Context context, Profiles.Person person, Listener listener) {
        JSONArray wanted = person.json.optJSONArray("walls");
        if (wanted == null || wanted.length() == 0) return;
        if (!running.compareAndSet(false, true)) return;
        final Context c = context.getApplicationContext();
        new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                Config cfg = Config.load(c);
                SharedPreferences prefs = c.getSharedPreferences("walls_" + person.id, Context.MODE_PRIVATE);
                String settings = cfg.watchlistImdbUser + cfg.watchlistExcludeGenres + cfg.watchlistDirectors + cfg.watchlistCountry;
                Map<String, Wall> saved = new HashMap<>();
                for (Wall w : load(c, person.id)) saved.put(w.key, w);
                Wall[] walls = new Wall[wanted.length()];
                String[] keys = new String[wanted.length()];
                for (int i = 0; i < walls.length; i++) {
                    keys[i] = DATA_VERSION + ":" + (wanted.getJSONObject(i).toString() + settings).hashCode();
                    walls[i] = saved.get(keys[i]);
                }
                long now = System.currentTimeMillis();
                for (int i = 0; i < walls.length; i++) {
                    JSONObject spec = wanted.getJSONObject(i);
                    String from = spec.optString("from", "search");
                    if (walls[i] != null && now - walls[i].at < life(from)) continue;
                    if (now - prefs.getLong("tried_" + keys[i], 0) < RETRY_MS) continue;
                    prefs.edit().putLong("tried_" + keys[i], now).apply();
                    try {
                        Wall w = build(c, cfg, spec, from, walls);
                        if (w.empty()) throw new IOException("nothing came back");
                        // "{year}" in a wall's name is this year: the wall of this year's films renames itself.
                        w.name = spec.optString("name", "Wall " + (i + 1))
                                .replace("{year}", String.valueOf(Calendar.getInstance().get(Calendar.YEAR)));
                        w.key = keys[i];
                        w.at = System.currentTimeMillis();
                        walls[i] = w;
                        final List<Wall> list = list(walls);
                        save(c, person.id, list);
                        new Handler(Looper.getMainLooper()).post(() -> listener.onWalls(list));
                    } catch (Exception | OutOfMemoryError e) {
                        Log.w(TAG, "wall " + spec.optString("name") + " failed, keeping the saved one", e);
                    }
                }
            } catch (JSONException e) {
                Log.w(TAG, "walls unreadable in config.json", e);
            } finally {
                running.set(false);
            }
        }, "walls").start();
    }

    private static List<Wall> list(Wall[] walls) {
        List<Wall> out = new ArrayList<>();
        for (Wall w : walls) if (w != null) out.add(w);
        return out;
    }

    private static final String F = "id titleText { text } originalTitleText { text } releaseYear { year } titleType { id } primaryImage { url }"
            + " ratingsSummary { aggregateRating voteCount topRanking { rank } } titleGenres { genres { genre { text } } }"
            + " prestigiousAwardSummary { wins nominations award { text } } spokenLanguages { spokenLanguages { text } }"
            + " technicalSpecifications { aspectRatios { items { aspectRatio } } colorations { items { text } } }"
            + " runtime { seconds } countriesOfOrigin { countries { text } } plot { plotText { plainText } }"
            + " directors: credits(first: 2, filter: {categories: [\"director\"]}) { edges { node { name { id } } } }";

    private static Wall build(Context c, Config cfg, JSONObject spec, String from, Wall[] soFar) throws IOException, JSONException {
        Wall w = new Wall();
        w.what = "films";
        int year = Calendar.getInstance().get(Calendar.YEAR);
        switch (from) {
            case "watchlist":
                w.tiles.addAll(watchlist(cfg.watchlistImdbUser));
                sizes(w.tiles);
                break;
            case "youtube":
                w.kind = VIDEO;
                w.what = "new videos";
                w.tiles.addAll(youtube(spec));
                break;
            case "world":
                w.kind = MAP;
                w.what = "countries";
                w.countries.addAll(world(c, cfg));
                break;
            case "directors":
                w.kind = PEOPLE;
                w.what = "directors";
                w.tiles.addAll(directors(spec, cfg, soFar));
                break;
            case "best_picture":
                w.what = "Best Picture winners";
                w.tiles.addAll(search("titleTypeConstraint: {anyTitleTypeIds: [\"movie\"]}, awardConstraint: {allEventNominations:"
                        + " [{eventId: \"ev0000003\", searchAwardCategoryId: \"bestPicture\", winnerFilter: WINNER_ONLY}]}", "RELEASE_DATE", 110));
                sizes(w.tiles);
                break;
            case "titles":
                List<String> ids = new ArrayList<>();
                strings(spec.optJSONArray("titles"), ids);
                double floor = spec.optDouble("min_rating", 0);
                for (Tile t : titles(ids)) if (t.rating >= floor) w.tiles.add(t);
                w.tiles.sort((a, b) -> Float.compare(b.rating, a.rating));
                w.what = "films and series";
                sizes(w.tiles);
                break;
            case "year":
                w.tiles.addAll(search(types(spec) + ", " + genres(spec, cfg) + ", releaseDateConstraint: {releaseDateRange: {start: \""
                        + year + "-01-01\", end: \"" + year + "-12-31\"}}, " + rated(spec, 6.8, 20000), "USER_RATING", spec.optInt("count", 90)));
                sizes(w.tiles);
                break;
            default:
                w.tiles.addAll(search(types(spec) + ", " + genres(spec, cfg) + ", " + rated(spec, 7.5, 50000), "USER_RATING", spec.optInt("count", 100)));
                boolean films = false, series = false;
                for (Tile t : w.tiles) {
                    films |= t.film();
                    series |= !t.film();
                }
                w.what = films && series ? "films and series" : series ? "series" : "films";
                sizes(w.tiles);
        }
        if (spec.has("what")) w.what = spec.optString("what");
        return w;
    }

    /** The best rated ninth of a wall hangs at double size. */
    private static void sizes(List<Tile> tiles) {
        List<Tile> best = new ArrayList<>(tiles);
        best.sort((a, b) -> a.rating != b.rating ? Float.compare(b.rating, a.rating) : b.votes - a.votes);
        int n = Math.max(4, Math.round(tiles.size() / 9f));
        for (int i = 0; i < best.size(); i++) best.get(i).big = i < n;
    }

    private static String types(JSONObject spec) {
        JSONArray a = spec.optJSONArray("types");
        StringBuilder s = new StringBuilder("titleTypeConstraint: {anyTitleTypeIds: [");
        if (a == null || a.length() == 0) s.append("\"movie\"");
        for (int i = 0; a != null && i < a.length(); i++) s.append(i > 0 ? ", " : "").append('"').append(a.optString(i)).append('"');
        return s.append("]}").toString();
    }

    /** The wall's genre, without the genres nobody in the house wants (config.json, watchlist_exclude_genres). */
    private static String genres(JSONObject spec, Config cfg) {
        String genre = spec.optString("genre", "");
        List<String> out = new ArrayList<>(cfg.watchlistExcludeGenres);
        strings(spec.optJSONArray("exclude_genres"), out);
        StringBuilder s = new StringBuilder("genreConstraint: {");
        if (!genre.isEmpty()) s.append("allGenreIds: [\"").append(genre).append("\"], ");
        s.append("excludeGenreIds: [");
        boolean first = true;
        for (String g : new java.util.LinkedHashSet<>(out)) {
            if (g.equals(genre) || g.isEmpty()) continue;
            s.append(first ? "" : ", ").append('"').append(g).append('"');
            first = false;
        }
        return s.append("]}").toString();
    }

    private static String rated(JSONObject spec, double rating, int votes) {
        return "userRatingsConstraint: {aggregateRatingRange: {min: " + spec.optDouble("min_rating", rating)
                + "}, ratingsCountRange: {min: " + spec.optInt("min_votes", votes) + "}}";
    }

    private static List<Tile> search(String constraints, String sort, int first) throws IOException, JSONException {
        String q = "query { advancedTitleSearch(first: " + first + ", constraints: { " + constraints + " }, sort: {sortBy: " + sort
                + ", sortOrder: DESC}) { edges { node { title { " + F + " } } } } }";
        return edges(Watchlist.imdb(q).getJSONObject("data").getJSONObject("advancedTitleSearch"));
    }

    private static List<Tile> edges(JSONObject found) throws JSONException {
        List<Tile> out = new ArrayList<>();
        JSONArray edges = found.getJSONArray("edges");
        for (int i = 0; i < edges.length(); i++) {
            Tile t = title(edges.getJSONObject(i).getJSONObject("node").optJSONObject("title"));
            if (t != null) out.add(t);
        }
        return out;
    }

    /** A title with a poster; null without one, since a wall of posters has no place for it. */
    private static Tile title(JSONObject o) {
        if (o == null || !o.has("id")) return null;
        JSONObject image = o.optJSONObject("primaryImage");
        String img = image != null ? image.optString("url", null) : null;
        JSONObject text = o.optJSONObject("titleText");
        if (img == null || text == null) return null;
        Tile t = new Tile();
        t.id = o.optString("id");
        t.title = text.optString("text", t.id);
        t.img = img;
        JSONObject year = o.optJSONObject("releaseYear");
        t.year = year != null ? year.optInt("year") : 0;
        JSONObject type = o.optJSONObject("titleType");
        t.type = type != null ? type.optString("id", "movie") : "movie";
        JSONObject rs = o.optJSONObject("ratingsSummary");
        if (rs != null) {
            double r = rs.optDouble("aggregateRating", 0);
            t.rating = Double.isNaN(r) ? 0 : (float) r;
            t.votes = rs.optInt("voteCount");
            JSONObject top = rs.optJSONObject("topRanking");
            t.rank = top != null ? top.optInt("rank") : 0;
        }
        JSONObject ot = o.optJSONObject("originalTitleText");
        String original = ot != null ? ot.optString("text", null) : null;
        if (original != null && !original.equalsIgnoreCase(t.title)) t.original = original;
        JSONObject pa = o.optJSONObject("prestigiousAwardSummary");
        JSONObject award = pa != null ? pa.optJSONObject("award") : null;
        if (award != null && !award.optString("text").isEmpty()) {
            int wins = pa.optInt("wins"), noms = pa.optInt("nominations"), n = wins > 0 ? wins : noms;
            if (n > 0) t.award = (wins > 0 ? "Won " : "Nominated for ") + n + " " + award.optString("text") + (n > 1 ? "s" : "");
        }
        JSONObject sl = o.optJSONObject("spokenLanguages");
        JSONArray ls = sl != null ? sl.optJSONArray("spokenLanguages") : null;
        if (ls != null && ls.length() > 0) t.language = ls.optJSONObject(0).optString("text", null);
        JSONObject ts = o.optJSONObject("technicalSpecifications");
        JSONObject ar = ts != null ? ts.optJSONObject("aspectRatios") : null, cl = ts != null ? ts.optJSONObject("colorations") : null;
        JSONArray ars = ar != null ? ar.optJSONArray("items") : null, cls = cl != null ? cl.optJSONArray("items") : null;
        if (ars != null && ars.length() > 0) t.ratio = ars.optJSONObject(0).optString("aspectRatio", "").replace(" ", "");
        if (t.ratio != null && t.ratio.isEmpty()) t.ratio = null;
        if (cls != null && cls.length() > 0) t.color = cls.optJSONObject(0).optString("text", null);
        JSONObject tg = o.optJSONObject("titleGenres");
        JSONArray gs = tg != null ? tg.optJSONArray("genres") : null;
        for (int i = 0; gs != null && i < gs.length() && t.genres.size() < 3; i++) {
            JSONObject g = gs.optJSONObject(i).optJSONObject("genre");
            if (g != null) t.genres.add(g.optString("text"));
        }
        JSONObject rt = o.optJSONObject("runtime");
        t.mins = rt != null ? rt.optInt("seconds") / 60 : 0;
        JSONObject co = o.optJSONObject("countriesOfOrigin");
        JSONArray cs = co != null ? co.optJSONArray("countries") : null;
        if (cs != null && cs.length() > 0) t.country = cs.optJSONObject(0).optString("text", null);
        JSONObject plot = o.optJSONObject("plot");
        JSONObject pt = plot != null ? plot.optJSONObject("plotText") : null;
        t.plot = pt != null ? pt.optString("plainText", null) : null;
        JSONObject ds = o.optJSONObject("directors");
        JSONArray de = ds != null ? ds.optJSONArray("edges") : null;
        for (int i = 0; de != null && i < de.length(); i++) {
            JSONObject name = de.optJSONObject(i).optJSONObject("node").optJSONObject("name");
            if (name != null) t.people.add(name.optString("id"));
        }
        return t;
    }

    /** The IMDb watchlist, newest additions first. */
    private static List<Tile> watchlist(String user) throws IOException, JSONException {
        List<Tile> out = new ArrayList<>();
        if (user == null || user.isEmpty()) return out;
        String after = null;
        do {
            String q = "query { predefinedList(classType: WATCH_LIST, userId: \"" + user + "\") { items(first: 250, "
                    + (after != null ? "after: \"" + after + "\", " : "") + "sort: {by: CREATED_DATE, order: DESC}) {"
                    + " pageInfo { hasNextPage endCursor } edges { node { listItem { ... on Title { " + F + " } } } } } } }";
            JSONObject items = Watchlist.imdb(q).getJSONObject("data").getJSONObject("predefinedList").getJSONObject("items");
            JSONArray edges = items.getJSONArray("edges");
            for (int i = 0; i < edges.length(); i++) {
                Tile t = title(edges.getJSONObject(i).getJSONObject("node").optJSONObject("listItem"));
                if (t != null) out.add(t);
            }
            JSONObject page = items.optJSONObject("pageInfo");
            after = page != null && page.optBoolean("hasNextPage") ? page.optString("endCursor", null) : null;
        } while (after != null && out.size() < 1500);
        return out;
    }

    private static List<Tile> titles(List<String> ids) throws IOException, JSONException {
        List<Tile> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += 40) {
            StringBuilder q = new StringBuilder("query { titles(ids: [");
            for (int k = i; k < Math.min(ids.size(), i + 40); k++) q.append(k > i ? ", " : "").append('"').append(ids.get(k).trim()).append('"');
            JSONArray a = Watchlist.imdb(q.append("]) { ").append(F).append(" } }").toString()).getJSONObject("data").getJSONArray("titles");
            for (int k = 0; k < a.length(); k++) {
                Tile t = title(a.optJSONObject(k));
                if (t != null) out.add(t);
            }
        }
        return out;
    }

    /**
     * Directors as faces: the ones listed ("people", else watchlist_directors) and whoever directed two or more
     * films on the watchlist wall. Each opens their sixteen best rated films. The seven with the most films on the
     * watchlist hang at double size.
     */
    private static List<Tile> directors(JSONObject spec, Config cfg, Wall[] soFar) {
        List<String> ids = new ArrayList<>();
        strings(spec.optJSONArray("people"), ids);
        if (ids.isEmpty()) ids.addAll(cfg.watchlistDirectors);
        Map<String, Integer> onWall = new LinkedHashMap<>();
        for (Wall w : soFar) {
            if (w == null || !POSTERS.equals(w.kind) || !w.what.equals("films") || onWall.size() > 0) continue;
            for (Tile t : w.tiles) for (String p : t.people) onWall.merge(p, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> often = new ArrayList<>(onWall.entrySet());
        often.sort((a, b) -> b.getValue() - a.getValue());
        for (Map.Entry<String, Integer> e : often) {
            if (e.getValue() < 2 || ids.size() >= 60) break;
            if (!ids.contains(e.getKey())) ids.add(e.getKey());
        }
        List<Tile> out = new ArrayList<>();
        for (String id : ids) {
            if (!id.startsWith("nm")) continue;
            try {
                String q = "query { name(id: \"" + id + "\") { nameText { text } primaryImage { url } credits(first: 250, filter:"
                        + " {categories: [\"director\"]}) { edges { node { title { " + F + " } } } } } }";
                JSONObject n = Watchlist.imdb(q).getJSONObject("data").optJSONObject("name");
                JSONObject image = n != null ? n.optJSONObject("primaryImage") : null;
                if (image == null) continue;
                Tile p = new Tile();
                p.id = id;
                p.type = "person";
                p.title = n.getJSONObject("nameText").optString("text");
                p.img = image.optString("url", null);
                p.count = onWall.containsKey(id) ? onWall.get(id) : 0;
                Set<String> seen = new HashSet<>();
                for (Tile t : edges(n.getJSONObject("credits"))) {
                    if (t.film() && t.votes >= 5000 && seen.add(t.id)) p.sub.add(t);
                }
                p.sub.sort((a, b) -> Float.compare(b.rating, a.rating));
                while (p.sub.size() > 16) p.sub.remove(p.sub.size() - 1);
                if (p.sub.size() < 4 || p.img == null) continue;
                p.rating = p.sub.get(0).rating;
                out.add(p);
            } catch (IOException | JSONException e) {
                Log.w(TAG, "director " + id + " failed", e);
            }
        }
        List<Tile> most = new ArrayList<>(out);
        most.sort((a, b) -> b.count - a.count);
        for (int i = 0; i < most.size(); i++) most.get(i).big = i < 7 && most.get(i).count > 0;
        return out;
    }

    // ---- YouTube ------------------------------------------------------------------------------

    /**
     * The four newest videos of each channel, newest first. "channels" is a list of channel IDs (UC…), or groups of
     * them: {"Photography": ["UC…"]}. A channel's "UULF" playlist is its full-length videos, without Shorts.
     */
    private static List<Tile> youtube(JSONObject spec) {
        List<String[]> channels = new ArrayList<>();
        JSONObject groups = spec.optJSONObject("channels");
        JSONArray plain = spec.optJSONArray("channels");
        for (int i = 0; plain != null && i < plain.length(); i++) channels.add(new String[]{null, plain.optString(i).trim()});
        if (groups != null) {
            for (Iterator<String> it = groups.keys(); it.hasNext(); ) {
                String g = it.next();
                JSONArray ids = groups.optJSONArray(g);
                for (int i = 0; ids != null && i < ids.length(); i++) channels.add(new String[]{g, ids.optString(i).trim()});
            }
        }
        int each = spec.optInt("each", 4);
        List<Tile> out = new ArrayList<>();
        for (String[] ch : channels) {
            if (!ch[1].startsWith("UC")) continue;
            try {
                String xml;
                try {
                    xml = Watchlist.get("https://www.youtube.com/feeds/videos.xml?playlist_id=UULF" + ch[1].substring(2));
                } catch (IOException e) {
                    xml = Watchlist.get("https://www.youtube.com/feeds/videos.xml?channel_id=" + ch[1]);
                }
                List<Tile> videos = feed(xml);
                for (int i = 0; i < Math.min(each, videos.size()); i++) {
                    videos.get(i).group = ch[0];
                    out.add(videos.get(i));
                }
            } catch (IOException | org.xmlpull.v1.XmlPullParserException e) {
                Log.w(TAG, "channel " + ch[1] + " failed: " + e.getMessage());
            }
        }
        out.sort((a, b) -> Long.compare(b.published, a.published));
        List<Tile> most = new ArrayList<>(out);
        most.sort((a, b) -> Long.compare(b.views, a.views));
        int n = Math.max(4, out.size() / 8);
        for (int i = 0; i < most.size(); i++) most.get(i).big = i < n;
        return out;
    }

    private static List<Tile> feed(String xml) throws IOException, org.xmlpull.v1.XmlPullParserException {
        org.xmlpull.v1.XmlPullParser p = android.util.Xml.newPullParser();
        p.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new java.io.StringReader(xml));
        java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.ROOT);
        List<Tile> out = new ArrayList<>();
        Tile t = null;
        String link = "";
        for (int ev = p.getEventType(); ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == org.xmlpull.v1.XmlPullParser.END_TAG && "entry".equals(p.getName())) {
                if (t != null && t.id != null && t.published > 0 && !link.contains("/shorts/")) out.add(t);
                t = null;
            }
            if (ev != org.xmlpull.v1.XmlPullParser.START_TAG || (t == null && !"entry".equals(p.getName()))) continue;
            switch (p.getName()) {
                case "entry":
                    t = new Tile();
                    t.type = "video";
                    link = "";
                    break;
                case "yt:videoId":
                    String video = p.nextText();
                    t.id = "yt_" + video;
                    t.img = "https://i.ytimg.com/vi/" + video + "/hq720.jpg";
                    break;
                case "title":
                    if (t.title == null) t.title = p.nextText();
                    break;
                case "name":
                    if (t.by == null) t.by = p.nextText();
                    break;
                case "link":
                    link = String.valueOf(p.getAttributeValue(null, "href"));
                    break;
                case "published":
                    try {
                        t.published = iso.parse(p.nextText()).getTime();
                    } catch (java.text.ParseException ignored) {
                    }
                    break;
                case "media:statistics":
                    try {
                        t.views = Long.parseLong(String.valueOf(p.getAttributeValue(null, "views")));
                    } catch (NumberFormatException ignored) {
                    }
                    break;
                default:
            }
        }
        return out;
    }

    // ---- the world ----------------------------------------------------------------------------

    private static JSONObject world;

    /** The map: every country's outline, where its name sits, and its name (assets/world.json). */
    static synchronized JSONObject world(Context c) {
        if (world != null) return world;
        try (InputStream in = c.getAssets().open("world.json")) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
            world = new JSONObject(new String(buf.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            Log.w(TAG, "map unreadable", e);
            world = new JSONObject();
        }
        return world;
    }

    /** Countries that are gone, counted with the one that holds their films today. */
    private static final String[][] FORMER = {{"SUHH", "RU"}, {"CSHH", "CZ"}, {"XWG", "DE"}, {"DDDE", "DE"}, {"YUCS", "RS"}};
    /** A film needs this many IMDb ratings. India needs far more: fans vote new releases up by the thousand. */
    private static final int WORLD_VOTES = 10_000, WORLD_VOTES_INDIA = 60_000, WORLD_FILMS = 24;

    /** Each country's best rated films. A country with fewer than a dozen gets a second look with a lower bar. */
    private static List<Country> world(Context c, Config cfg) throws IOException, JSONException {
        JSONObject cent = world(c).optJSONObject("cent");
        if (cent == null) throw new IOException("no map");
        List<String[]> ask = new ArrayList<>();   // {code asked for, code on the map}
        for (Iterator<String> it = cent.keys(); it.hasNext(); ) {
            String code = it.next();
            ask.add(new String[]{code, code});
        }
        for (String[] f : FORMER) if (cent.has(f[1])) ask.add(f);
        JSONObject none = new JSONObject();
        String rest = "titleTypeConstraint: {anyTitleTypeIds: [\"movie\"]}, " + genres(none, cfg) + ", ";
        Map<String, List<Tile>> films = new LinkedHashMap<>();
        for (int i = 0; i < ask.size(); i += 3) {
            StringBuilder q = new StringBuilder("query {");
            for (int k = i; k < Math.min(ask.size(), i + 3); k++) {
                String code = ask.get(k)[0];
                int votes = code.equals("IN") ? WORLD_VOTES_INDIA : WORLD_VOTES;
                q.append(" c").append(k).append(": advancedTitleSearch(first: 40, constraints: { ").append(rest)
                        .append("originCountryConstraint: {anyPrimaryCountries: [\"").append(code).append("\"]}, ")
                        .append("userRatingsConstraint: {aggregateRatingRange: {min: ").append(code.equals("IN") ? 7.5 : 7)
                        .append("}, ratingsCountRange: {min: ").append(votes).append("}} }, sort: {sortBy: USER_RATING, sortOrder: DESC})")
                        .append(" { edges { node { title { ").append(F).append(" } } } }");
            }
            try {
                JSONObject data = Watchlist.imdb(q.append(" }").toString()).getJSONObject("data");
                for (int k = i; k < Math.min(ask.size(), i + 3); k++) {
                    JSONObject found = data.optJSONObject("c" + k);
                    if (found == null) continue;
                    List<Tile> into = films.get(ask.get(k)[1]);
                    if (into == null) films.put(ask.get(k)[1], into = new ArrayList<>());
                    into.addAll(edges(found));
                }
            } catch (IOException | JSONException e) {
                Log.w(TAG, "world: a batch of countries failed", e);
            }
        }
        List<Country> out = new ArrayList<>();
        for (Map.Entry<String, List<Tile>> e : films.entrySet()) {
            List<Tile> got = e.getValue();
            if (got.size() < 12 && !e.getKey().equals("IN")) {
                try {
                    got.addAll(search(rest + "originCountryConstraint: {anyPrimaryCountries: [\"" + e.getKey() + "\"]}, "
                            + "userRatingsConstraint: {aggregateRatingRange: {min: 6.8}, ratingsCountRange: {min: 2500}}", "USER_RATING", 24));
                } catch (IOException | JSONException ignored) {
                }
            }
            got.sort((a, b) -> a.rating != b.rating ? Float.compare(b.rating, a.rating) : b.votes - a.votes);
            Country country = new Country();
            country.code = e.getKey();
            Set<String> seen = new HashSet<>();
            for (Tile t : got) {
                if (seen.add(t.id) && country.films.size() < WORLD_FILMS) country.films.add(t);
            }
            if (country.films.size() >= 3) out.add(country);
        }
        return out;
    }

    /** A copy of the tiles in a new order. */
    static List<Tile> shuffled(List<Tile> tiles) {
        List<Tile> out = new ArrayList<>(tiles);
        Collections.shuffle(out);
        return out;
    }
}
