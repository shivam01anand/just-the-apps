package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The titles on one person's channels (config.json, "profiles" → "channels"): each title's details from IMDb, a
 * landscape still and the app it plays in from JustWatch, and the episodes of each series. Looked up on the TV in
 * the background when the line-ups change and once a week after that (what streams where changes), and kept in
 * the app's own folder, so the channels open instantly and still work offline.
 */
final class ChannelData {

    private static final String TAG = "TvHome.Channels";
    private static final int DATA_VERSION = 3;
    private static final long REFRESH_MS = 7L * 24 * 60 * 60 * 1000;
    private static final long RETRY_MS = 30L * 60 * 1000;
    private static final String JW_URL = "https://apis.justwatch.com/graphql";
    private static final String STREMIO_PKG = "com.stremio.one";
    private static final AtomicBoolean running = new AtomicBoolean(false);

    static final class Episode {
        String imdb, title;
        int season, number, mins;
    }

    static final class Item {
        String imdb, title, plot, still, service, pkg, intentUri, poster;
        int year, endYear, mins, votes;
        float rating;
        boolean series;
        final List<String> cast = new ArrayList<>();
        final List<Episode> episodes = new ArrayList<>();

        boolean inStremio() {
            return service == null || ChannelData.STREMIO.equals(service);
        }
    }

    /** A director or star: their photo and their best-rated films. */
    static final class Person {
        String id, name, photo, role;
        final List<Item> films = new ArrayList<>();
    }

    static final class Channel {
        String name;
        boolean numbered;
        final List<Item> items = new ArrayList<>();
        /** A row of people instead of titles: OK on one opens their films. */
        final List<Person> people = new ArrayList<>();
    }

    static final String STREMIO = "Stremio";

    interface Listener {
        void onChannels(List<Channel> channels);
    }

    private ChannelData() {
    }

    static File dir(Context c, String profile) {
        File base = Config.dir(c);
        if (base == null) base = c.getFilesDir();
        File d = new File(new File(base, "profiles"), profile);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static File still(Context c, String profile, String imdb) {
        return new File(new File(dir(c, profile), "stills"), imdb + ".jpg");
    }

    // ---- saved line-ups -----------------------------------------------------------------------

    static List<Channel> load(Context c, String profile) {
        File f = new File(dir(c, profile), "channels.json");
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return parse(new JSONArray(new String(buf, 0, n, StandardCharsets.UTF_8)));
        } catch (IOException | JSONException e) {
            Log.w(TAG, "saved channels unreadable", e);
            return null;
        }
    }

    private static List<Channel> parse(JSONArray a) throws JSONException {
        List<Channel> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Channel ch = new Channel();
            ch.name = o.getString("name");
            ch.numbered = o.optBoolean("numbered");
            JSONArray people = o.optJSONArray("people");
            for (int k = 0; people != null && k < people.length(); k++) {
                JSONObject p = people.getJSONObject(k);
                Person person = new Person();
                person.id = p.getString("id");
                person.name = p.optString("name");
                person.photo = p.optString("photo", null);
                person.role = p.optString("role");
                JSONArray films = p.optJSONArray("films");
                for (int j = 0; films != null && j < films.length(); j++) person.films.add(item(films.getJSONObject(j)));
                if (!person.films.isEmpty()) ch.people.add(person);
            }
            JSONArray items = o.optJSONArray("items");
            for (int k = 0; items != null && k < items.length(); k++) ch.items.add(item(items.getJSONObject(k)));
            if (!ch.items.isEmpty() || !ch.people.isEmpty()) out.add(ch);
        }
        return out;
    }

    private static Item item(JSONObject t) throws JSONException {
        Item it = new Item();
        it.imdb = t.getString("imdb");
        it.title = t.optString("title", it.imdb);
        it.plot = t.optString("plot", null);
        it.still = t.optString("still", null);
        it.service = t.optString("service", null);
        it.pkg = t.optString("pkg", null);
        it.intentUri = t.optString("intent", null);
        it.year = t.optInt("year");
        it.endYear = t.optInt("endYear");
        it.mins = t.optInt("mins");
        it.votes = t.optInt("votes");
        it.rating = (float) t.optDouble("rating", 0);
        it.series = t.optBoolean("series");
        JSONArray cast = t.optJSONArray("cast");
        for (int j = 0; cast != null && j < cast.length(); j++) it.cast.add(cast.getString(j));
        JSONArray eps = t.optJSONArray("episodes");
        for (int j = 0; eps != null && j < eps.length(); j++) {
            JSONObject e = eps.getJSONObject(j);
            Episode ep = new Episode();
            ep.imdb = e.optString("imdb");
            ep.title = e.optString("title");
            ep.season = e.optInt("s");
            ep.number = e.optInt("e");
            ep.mins = e.optInt("mins");
            it.episodes.add(ep);
        }
        return it;
    }

    private static JSONArray toJson(List<Channel> channels) throws JSONException {
        JSONArray a = new JSONArray();
        for (Channel ch : channels) {
            JSONArray items = new JSONArray();
            for (Item it : ch.items) items.put(itemJson(it));
            JSONArray people = new JSONArray();
            for (Person p : ch.people) {
                JSONArray films = new JSONArray();
                for (Item it : p.films) films.put(itemJson(it));
                people.put(new JSONObject().put("id", p.id).put("name", p.name).put("photo", p.photo).put("role", p.role).put("films", films));
            }
            a.put(new JSONObject().put("name", ch.name).put("numbered", ch.numbered).put("items", items).put("people", people));
        }
        return a;
    }

    private static JSONObject itemJson(Item it) throws JSONException {
        JSONObject t = new JSONObject().put("imdb", it.imdb).put("title", it.title).put("plot", it.plot)
                .put("still", it.still).put("service", it.service).put("pkg", it.pkg).put("intent", it.intentUri)
                .put("year", it.year).put("endYear", it.endYear).put("mins", it.mins).put("series", it.series)
                .put("cast", new JSONArray(it.cast)).put("rating", it.rating).put("votes", it.votes);
        if (!it.episodes.isEmpty()) {
            JSONArray eps = new JSONArray();
            for (Episode e : it.episodes) {
                eps.put(new JSONObject().put("imdb", e.imdb).put("title", e.title).put("s", e.season)
                        .put("e", e.number).put("mins", e.mins));
            }
            t.put("episodes", eps);
        }
        return t;
    }

    // ---- opening a title ------------------------------------------------------------------------

    /** The intent that plays an item: its app's link, or Stremio's page for the film or for this episode. */
    static Intent intentFor(Item it, Episode ep) {
        Intent i = null;
        if (!it.inStremio() && it.intentUri != null) {
            try {
                i = Intent.parseUri(it.intentUri, Intent.URI_INTENT_SCHEME);
            } catch (java.net.URISyntaxException ignored) {
            }
        }
        if (i == null) {
            String uri = it.series
                    ? "stremio:///detail/series/" + it.imdb + (ep != null ? "/" + it.imdb + ":" + ep.season + ":" + ep.number : "")
                    : "stremio:///detail/movie/" + it.imdb + "/" + it.imdb;
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(STREMIO_PKG);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.getData() != null) i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);   // Stremio ignores a link while open
        return i;
    }

    // ---- looking titles up ----------------------------------------------------------------------

    private static String signature(JSONArray channels) {
        return DATA_VERSION + ":" + (channels != null ? channels.toString().hashCode() : 0);
    }

    /** Refreshes in the background when the line-ups changed or a week has passed; the listener runs on the main thread. */
    static void refreshIfStale(Context context, Profiles.Person person, Listener listener) {
        JSONArray wanted = person.json.optJSONArray("channels");
        if (wanted == null || wanted.length() == 0) return;
        SharedPreferences prefs = context.getSharedPreferences("channels_" + person.id, Context.MODE_PRIVATE);
        String want = signature(wanted);
        long now = System.currentTimeMillis();
        boolean outdated = !want.equals(prefs.getString("built", ""));
        if (!outdated && now - prefs.getLong("ok", 0) < REFRESH_MS) return;
        if (want.equals(prefs.getString("triedFor", "")) && now - prefs.getLong("tried", 0) < RETRY_MS) return;
        if (!running.compareAndSet(false, true)) return;
        prefs.edit().putLong("tried", now).putString("triedFor", want).apply();
        final Context c = context.getApplicationContext();
        new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                List<Channel> built = build(c, person.id, wanted);
                File f = new File(dir(c, person.id), "channels.json");
                File tmp = new File(f.getPath() + ".tmp");
                try (OutputStream out = new FileOutputStream(tmp)) {
                    out.write(toJson(built).toString().getBytes(StandardCharsets.UTF_8));
                }
                if (!tmp.renameTo(f)) throw new IOException("rename failed");
                prefs.edit().putLong("ok", System.currentTimeMillis()).putString("built", want).apply();
                new Handler(Looper.getMainLooper()).post(() -> listener.onChannels(built));
            } catch (Exception | OutOfMemoryError e) {
                Log.w(TAG, "refresh failed, keeping the saved channels", e);
            } finally {
                running.set(false);
            }
        }, "channels").start();
    }

    private static final String TITLE_FIELDS = "id titleText { text } releaseYear { year endYear } runtime { seconds }"
            + " ratingsSummary { aggregateRating voteCount }"
            + " plot { plotText { plainText } } titleType { id } primaryImage { url }"
            + " cast: credits(first: 3, filter: {categories: [\"actor\", \"actress\"]}) { edges { node { name { nameText { text } } } } }";

    private static List<Channel> build(Context c, String profile, JSONArray wanted) throws IOException, JSONException {
        // Every title once, in the order the channels list them; then each person's best films.
        Map<String, Item> items = new LinkedHashMap<>();
        for (int i = 0; i < wanted.length(); i++) {
            JSONArray ids = wanted.getJSONObject(i).optJSONArray("titles");
            for (int k = 0; ids != null && k < ids.length(); k++) add(items, ids.optString(k).trim());
        }
        Map<String, Person> people = new LinkedHashMap<>();
        for (int i = 0; i < wanted.length(); i++) {
            JSONObject o = wanted.getJSONObject(i);
            JSONArray pids = o.optJSONArray("people");
            for (int k = 0; pids != null && k < pids.length(); k++) {
                String id = pids.optString(k).trim();
                if (!id.startsWith("nm") || people.containsKey(id)) continue;
                Person p = new Person();
                p.id = id;
                p.role = o.optString("role", "actor");
                people.put(id, p);
            }
        }
        people(people, items);
        List<String> ids = new ArrayList<>(items.keySet());
        for (int i = 0; i < ids.size(); i += 20) {
            StringBuilder q = new StringBuilder("query {");
            for (int k = i; k < Math.min(ids.size(), i + 20); k++) {
                q.append(" t").append(k).append(": title(id: \"").append(ids.get(k)).append("\") { ").append(TITLE_FIELDS).append(" }");
            }
            JSONObject data = Watchlist.imdb(q.append(" }").toString()).optJSONObject("data");
            for (int k = i; data != null && k < Math.min(ids.size(), i + 20); k++) {
                JSONObject t = data.optJSONObject("t" + k);
                if (t != null) details(items.get(ids.get(k)), t);
            }
        }
        for (Item it : items.values()) {
            if (it.series) episodes(it);
        }

        Config cfg = Config.load(c);
        List<Watchlist.Service> services = Watchlist.installedServices(c, cfg.watchlistServices);
        File stills = new File(dir(c, profile), "stills");
        //noinspection ResultOfMethodCallIgnored
        stills.mkdirs();
        for (Item it : items.values()) {
            String backdrop = null;
            try {
                backdrop = place(it, services, cfg.watchlistCountry);
            } catch (IOException | JSONException e) {
                Log.w(TAG, "JustWatch failed for " + it.title, e);
            }
            File f = new File(stills, it.imdb + ".jpg");
            if (!f.isFile() && backdrop != null) download(backdrop, f);
            // No landscape still (some old Doordarshan shows): IMDb's poster, cropped to the screen, beats black.
            if (!f.isFile() && it.poster != null) download(it.poster.replace("._V1_", "._V1_QL80_UX1440_"), f);
            if (f.isFile()) it.still = f.getName();
        }
        for (Person p : people.values()) {
            File f = new File(stills, p.id + ".jpg");
            if (!f.isFile() && p.photo != null) download(p.photo.replace("._V1_", "._V1_QL80_UX400_"), f);
            p.photo = f.isFile() ? f.getName() : null;
        }

        List<Channel> out = new ArrayList<>();
        for (int i = 0; i < wanted.length(); i++) {
            JSONObject o = wanted.getJSONObject(i);
            Channel ch = new Channel();
            ch.name = o.optString("name", "Channel " + (i + 1));
            ch.numbered = o.optBoolean("numbered");
            JSONArray tids = o.optJSONArray("titles");
            for (int k = 0; tids != null && k < tids.length(); k++) {
                Item it = items.get(tids.optString(k).trim());
                if (it != null && it.title != null) ch.items.add(it);
            }
            // "sort": "rating" puts the best-rated first, as IMDb's voters see it today.
            if ("rating".equals(o.optString("sort"))) ch.items.sort((a, b) -> Float.compare(b.rating, a.rating));
            JSONArray pids = o.optJSONArray("people");
            for (int k = 0; pids != null && k < pids.length(); k++) {
                Person p = people.get(pids.optString(k).trim());
                if (p == null || p.name == null) continue;
                List<Item> films = new ArrayList<>();
                for (Item f : p.films) {
                    Item full = items.get(f.imdb);
                    if (full != null && full.title != null) films.add(full);
                }
                p.films.clear();
                p.films.addAll(films);
                if (!p.films.isEmpty()) ch.people.add(p);
            }
            if (!ch.items.isEmpty() || !ch.people.isEmpty()) out.add(ch);
        }
        return out;
    }

    private static void add(Map<String, Item> items, String id) {
        if (!id.startsWith("tt") || items.containsKey(id)) return;
        Item it = new Item();
        it.imdb = id;
        items.put(id, it);
    }

    /** Films a person has at least this many IMDb ratings for count; the best-rated twelve make their row. */
    private static final int PERSON_MIN_VOTES = 1500, PERSON_FILMS = 12;

    /** Each person's name, photo and best-rated feature films (as director or actor), added to the titles to look up. */
    private static void people(Map<String, Person> people, Map<String, Item> items) {
        List<Person> all = new ArrayList<>(people.values());
        for (int i = 0; i < all.size(); i += 4) {
            StringBuilder q = new StringBuilder("query {");
            for (int k = i; k < Math.min(all.size(), i + 4); k++) {
                Person p = all.get(k);
                String cats = "director".equals(p.role) ? "[\"director\"]" : "[\"actor\", \"actress\"]";
                q.append(" p").append(k).append(": name(id: \"").append(p.id).append("\") { nameText { text } primaryImage { url }")
                        .append(" credits(first: 250, filter: {categories: ").append(cats).append("}) { edges { node { title {")
                        .append(" id titleType { id } ratingsSummary { aggregateRating voteCount } } } } } }");
            }
            try {
                JSONObject data = Watchlist.imdb(q.append(" }").toString()).optJSONObject("data");
                for (int k = i; data != null && k < Math.min(all.size(), i + 4); k++) {
                    JSONObject n = data.optJSONObject("p" + k);
                    if (n == null) continue;
                    Person p = all.get(k);
                    JSONObject nt = n.optJSONObject("nameText");
                    p.name = nt != null ? nt.optString("text") : null;
                    JSONObject img = n.optJSONObject("primaryImage");
                    p.photo = img != null ? img.optString("url", null) : null;
                    JSONArray edges = n.getJSONObject("credits").getJSONArray("edges");
                    List<JSONObject> films = new ArrayList<>();
                    for (int e = 0; e < edges.length(); e++) {
                        JSONObject t = edges.getJSONObject(e).getJSONObject("node").optJSONObject("title");
                        JSONObject type = t != null ? t.optJSONObject("titleType") : null;
                        JSONObject rs = t != null ? t.optJSONObject("ratingsSummary") : null;
                        if (type == null || rs == null || !"movie".equals(type.optString("id"))) continue;
                        if (rs.optInt("voteCount") < PERSON_MIN_VOTES) continue;
                        films.add(t);
                    }
                    films.sort((a, b) -> Double.compare(b.optJSONObject("ratingsSummary").optDouble("aggregateRating", 0),
                            a.optJSONObject("ratingsSummary").optDouble("aggregateRating", 0)));
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    for (JSONObject t : films) {
                        String id = t.optString("id");
                        if (!seen.add(id)) continue;
                        add(items, id);
                        Item stub = new Item();
                        stub.imdb = id;
                        p.films.add(stub);
                        if (p.films.size() >= PERSON_FILMS) break;
                    }
                }
            } catch (IOException | JSONException e) {
                Log.w(TAG, "people failed", e);
            }
        }
    }

    private static void details(Item it, JSONObject t) {
        JSONObject text = t.optJSONObject("titleText");
        it.title = text != null ? text.optString("text", it.imdb) : it.imdb;
        JSONObject year = t.optJSONObject("releaseYear");
        if (year != null) {
            it.year = year.optInt("year");
            it.endYear = year.optInt("endYear");
        }
        JSONObject rt = t.optJSONObject("runtime");
        if (rt != null) it.mins = rt.optInt("seconds") / 60;
        JSONObject rs = t.optJSONObject("ratingsSummary");
        if (rs != null) {
            it.rating = (float) rs.optDouble("aggregateRating", 0);
            it.votes = rs.optInt("voteCount");
        }
        JSONObject plot = t.optJSONObject("plot");
        JSONObject pt = plot != null ? plot.optJSONObject("plotText") : null;
        if (pt != null) it.plot = pt.optString("plainText", null);
        JSONObject image = t.optJSONObject("primaryImage");
        if (image != null) it.poster = image.optString("url", null);
        JSONObject type = t.optJSONObject("titleType");
        String tid = type != null ? type.optString("id") : "";
        it.series = tid.equals("tvSeries") || tid.equals("tvMiniSeries");
        JSONObject cast = t.optJSONObject("cast");
        JSONArray edges = cast != null ? cast.optJSONArray("edges") : null;
        for (int i = 0; edges != null && i < edges.length(); i++) {
            JSONObject n = edges.optJSONObject(i).optJSONObject("node");
            JSONObject name = n != null ? n.optJSONObject("name") : null;
            JSONObject nt = name != null ? name.optJSONObject("nameText") : null;
            if (nt != null) it.cast.add(nt.optString("text"));
        }
    }

    private static void episodes(Item it) {
        String q = "query { title(id: \"" + it.imdb + "\") { episodes { episodes(first: 250) { edges { node { id titleText { text }"
                + " runtime { seconds } series { displayableEpisodeNumber { episodeNumber { text } displayableSeason { text } } } } } } } } }";
        try {
            JSONObject title = Watchlist.imdb(q).getJSONObject("data").getJSONObject("title");
            JSONArray edges = title.getJSONObject("episodes").getJSONObject("episodes").getJSONArray("edges");
            for (int i = 0; i < edges.length(); i++) {
                JSONObject n = edges.getJSONObject(i).getJSONObject("node");
                JSONObject num = n.getJSONObject("series").getJSONObject("displayableEpisodeNumber");
                Episode e = new Episode();
                e.imdb = n.optString("id");
                e.title = n.getJSONObject("titleText").optString("text");
                e.season = parseInt(num.getJSONObject("displayableSeason").optString("text"));
                e.number = parseInt(num.getJSONObject("episodeNumber").optString("text"));
                JSONObject rt = n.optJSONObject("runtime");
                e.mins = rt != null ? rt.optInt("seconds") / 60 : 0;
                if (e.season > 0 && e.number > 0) it.episodes.add(e);
            }
            it.episodes.sort((a, b) -> a.season != b.season ? a.season - b.season : a.number - b.number);
        } catch (IOException | JSONException e) {
            Log.w(TAG, "episodes failed for " + it.title, e);
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return 0;
        }
    }

    private static final String JW_QUERY = "query S($country: Country!, $q: String!) {"
            + " popularTitles(country: $country, first: 6, filter: {searchQuery: $q}) { edges { node {"
            + " content(country: $country, language: \"en\") { externalIds { imdbId } backdrops { backdropUrl } }"
            + " offers(country: $country, platform: WEB) { monetizationType standardWebURL"
            + " deeplinkURL(platform: ANDROID_TV) package { technicalName } } } } } }";

    /** Sets where the item plays (the first of the person's apps that has it, else Stremio); returns its still's URL. */
    private static String place(Item it, List<Watchlist.Service> services, String country) throws IOException, JSONException {
        it.service = STREMIO;
        it.pkg = null;
        it.intentUri = null;
        JSONObject vars = new JSONObject().put("country", country).put("q", it.title);
        JSONObject d = Watchlist.post(JW_URL, new JSONObject().put("query", JW_QUERY).put("variables", vars));
        JSONArray edges = d.getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges");
        for (int i = 0; i < edges.length(); i++) {
            JSONObject n = edges.getJSONObject(i).getJSONObject("node");
            JSONObject content = n.getJSONObject("content");
            JSONObject ext = content.optJSONObject("externalIds");
            if (ext == null || !it.imdb.equals(ext.optString("imdbId"))) continue;
            JSONArray offers = n.optJSONArray("offers");
            outer:
            for (Watchlist.Service s : services) {
                for (int k = 0; offers != null && k < offers.length(); k++) {
                    JSONObject o = offers.getJSONObject(k);
                    String money = o.optString("monetizationType");
                    if (!money.equals("FLATRATE") && !money.equals("FREE") && !money.equals("ADS")) continue;
                    if (!s.tech.equals(o.getJSONObject("package").optString("technicalName"))) continue;
                    Intent intent = Watchlist.offerIntent(s, o.isNull("standardWebURL") ? null : o.optString("standardWebURL"),
                            o.isNull("deeplinkURL") ? null : o.optString("deeplinkURL"));
                    if (intent == null) continue;
                    it.service = s.label;
                    it.pkg = s.pkg;
                    it.intentUri = intent.toUri(Intent.URI_INTENT_SCHEME);
                    break outer;
                }
            }
            JSONArray backs = content.optJSONArray("backdrops");
            JSONObject first = backs != null && backs.length() > 0 ? backs.getJSONObject(0) : null;
            String url = first != null ? first.optString("backdropUrl", null) : null;
            return url == null ? null
                    : "https://images.justwatch.com" + url.replace("{profile}", "s1440").replace("{format}", "jpg");
        }
        return null;
    }

    private static void download(String url, File to) {
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
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(to);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }
}
