package com.gathin.tvhome;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The launcher's settings: the values in res/values/config.xml, with an optional overlay read from
 * {@code config.json} in the app's external files directory
 * ({@code /sdcard/Android/data/com.gathin.tvhome/files/}). Every key in the JSON is optional and an
 * unknown or badly typed key is ignored, so a theme can change the dock and the look without a rebuild:
 * {@code tools/theme.sh themes/<name>} pushes a theme folder there and restarts Home.
 *
 * Read once in {@link MainActivity#onCreate}; nothing here touches the network.
 */
final class Config {
    private static final String TAG = "TvHome.Config";
    static final String FILE_NAME = "config.json";
    static final String WALLPAPER_NAME = "wallpaper.jpg";

    final List<String> dockApps;
    final List<String> hiddenApps;
    final String liveTvPackage;
    final String liveTvAction;
    final String sourcePanelService;
    final String sourcePanelAction;
    final String inputAction;
    final int columns;
    final int rowsBelowDock;
    final boolean shamsiDate;
    final boolean moduleCards;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): stiff spring, short fades, no replay. */
    final boolean reducedMotion;
    final String vpnApp;
    final boolean showHomeSettingsShortcut;
    /** Background colour behind everything, #RRGGBB; black unless a theme says otherwise. */
    final int backgroundColor;
    /** Full-screen picture behind the launcher, or null for the plain colour. */
    final File wallpaper;
    /** Whether the overlay file was present and parsed. */
    final boolean overlayApplied;
    /** Top-bar pills: the Inputs dropdown and the network name. */
    final boolean showInputsPill;
    final boolean showNetworkPill;
    /** Watchlist row (Watchlist.java): IMDb user ("ur…", empty = no row), country, services in order of preference. */
    final String watchlistImdbUser;
    final String watchlistCountry;
    final List<String> watchlistServices;
    /** Language codes ("hi") that each get a chip of new, well-liked titles; the first is open on Home. */
    final List<String> watchlistLanguages;
    /** This year's popular and best-rated titles as two chips. */
    final boolean watchlistThisYear;
    /** A Podcasts chip: YouTube's popular podcasts for the country, in the first watchlist language if set. */
    final boolean watchlistPodcasts;
    /** YouTube channel IDs for the Podcasts chip instead of YouTube's popular ones; each plays its newest episode. */
    final List<String> watchlistPodcastChannels;
    /** Two chips of what is trending this week: in the country, on the apps you have, and worldwide. */
    final boolean watchlistTrending;
    /** World cinema: the best-rated films that are not in English or the household's languages. */
    final boolean watchlistWorldCinema;
    /**
     * A first chip made for one person: named for them (say, "Mum"), with the first language's picks (no horror),
     * plus watchlistPersonalMix recipes (korean_drama: new K-dramas rated 7.5 and up) and four of
     * watchlistPersonalTitles (IMDb IDs: films chosen for them) at a time. Empty: the first chip is the language.
     */
    final String watchlistPersonalName;
    /** A Letterboxd username whose public watchlist joins the IMDb one in the same rows. */
    final String watchlistLetterboxdUser;
    /** The screensaver as a gallery of public-domain paintings (Gallery), instead of the drifting clock. */
    final boolean screensaverGallery;
    /** IMDb genres no row ever shows ("Horror"), from searches, trending, directors and the watchlist alike. */
    final List<String> watchlistExcludeGenres;
    /** Soft notes as focus moves and things open (Sounds), at this share of the system-sound volume. */
    final boolean navigationSounds;
    final int navigationSoundVolume;
    /** "android": Android TV's own sounds from the system folder; "wood": a small knock bundled here. */
    final String navigationSoundSet;
    /** IMDb name IDs (nm...) for a Directors chip: their photos, and OK on one opens their best films. */
    final List<String> watchlistDirectors;
    final List<String> watchlistPersonalMix;
    final List<String> watchlistPersonalTitles;
    /** Temperature and sky beside the date, and on the clock screensaver. */
    final boolean weather;
    /** A town or city for the weather; empty means wherever the internet connection says the TV is. */
    final String weatherPlace;

    private Config(List<String> dockApps, List<String> hiddenApps, String liveTvPackage, String liveTvAction,
                   String sourcePanelService, String sourcePanelAction, String inputAction,
                   int columns, int rowsBelowDock,
                   boolean shamsiDate, boolean moduleCards, boolean reducedMotion, String vpnApp,
                   boolean showHomeSettingsShortcut, int backgroundColor,
                   File wallpaper, boolean overlayApplied,
                   boolean showInputsPill, boolean showNetworkPill,
                   String watchlistImdbUser, String watchlistCountry, List<String> watchlistServices,
                   List<String> watchlistLanguages, boolean watchlistThisYear, boolean watchlistPodcasts,
                   List<String> watchlistPodcastChannels, boolean watchlistTrending, boolean watchlistWorldCinema,
                   boolean weather, String weatherPlace, String watchlistPersonalName,
                   List<String> watchlistPersonalMix, List<String> watchlistPersonalTitles, List<String> watchlistDirectors,
                   boolean navigationSounds, int navigationSoundVolume, String navigationSoundSet,
                   List<String> watchlistExcludeGenres, boolean screensaverGallery, String watchlistLetterboxdUser) {
        this.dockApps = Collections.unmodifiableList(dockApps);
        this.hiddenApps = Collections.unmodifiableList(hiddenApps);
        this.liveTvPackage = liveTvPackage;
        this.liveTvAction = liveTvAction;
        this.sourcePanelService = sourcePanelService;
        this.sourcePanelAction = sourcePanelAction;
        this.inputAction = inputAction;
        this.columns = columns;
        this.rowsBelowDock = rowsBelowDock;
        this.shamsiDate = shamsiDate;
        this.moduleCards = moduleCards;
        this.reducedMotion = reducedMotion;
        this.vpnApp = vpnApp;
        this.showHomeSettingsShortcut = showHomeSettingsShortcut;
        this.backgroundColor = backgroundColor;
        this.wallpaper = wallpaper;
        this.overlayApplied = overlayApplied;
        this.showInputsPill = showInputsPill;
        this.showNetworkPill = showNetworkPill;
        this.watchlistImdbUser = watchlistImdbUser;
        this.watchlistCountry = watchlistCountry;
        this.watchlistServices = Collections.unmodifiableList(watchlistServices);
        this.watchlistLanguages = Collections.unmodifiableList(watchlistLanguages);
        this.watchlistThisYear = watchlistThisYear;
        this.watchlistPodcasts = watchlistPodcasts;
        this.watchlistPodcastChannels = Collections.unmodifiableList(watchlistPodcastChannels);
        this.watchlistTrending = watchlistTrending;
        this.watchlistWorldCinema = watchlistWorldCinema;
        this.weather = weather;
        this.weatherPlace = weatherPlace;
        this.watchlistPersonalName = watchlistPersonalName;
        this.watchlistPersonalMix = Collections.unmodifiableList(watchlistPersonalMix);
        this.watchlistPersonalTitles = Collections.unmodifiableList(watchlistPersonalTitles);
        this.watchlistDirectors = Collections.unmodifiableList(watchlistDirectors);
        this.navigationSounds = navigationSounds;
        this.navigationSoundVolume = navigationSoundVolume;
        this.navigationSoundSet = navigationSoundSet;
        this.watchlistExcludeGenres = Collections.unmodifiableList(watchlistExcludeGenres);
        this.screensaverGallery = screensaverGallery;
        this.watchlistLetterboxdUser = watchlistLetterboxdUser;
    }

    /** The folder the theme tools push into; null when external storage is unavailable. */
    static File dir(Context ctx) {
        return ctx.getExternalFilesDir(null);
    }

    /** The app's Android/media folder: the tools push config.json here where adb can't reach Android/data (Fire OS). */
    @SuppressWarnings("deprecation")
    static File mediaDir(Context ctx) {
        File[] dirs = ctx.getExternalMediaDirs();
        return dirs != null && dirs.length > 0 ? dirs[0] : null;
    }

    /** config.json: in the app's Android/data folder, or its Android/media folder (Fire OS); null if neither. */
    static File overlayFile(Context ctx) {
        File dir = dir(ctx);
        File file = dir != null ? new File(dir, FILE_NAME) : null;
        if (file != null && file.isFile()) return file;
        File media = mediaDir(ctx);
        return media != null && new File(media, FILE_NAME).isFile() ? new File(media, FILE_NAME) : file;
    }

    /** The whole of config.json, for the parts other classes read themselves (Profiles); null if there is none. */
    static JSONObject overlay(Context ctx) {
        return read(overlayFile(ctx));
    }

    static Config load(Context ctx) {
        Resources res = ctx.getResources();
        List<String> dock = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.dock_apps)));
        List<String> hidden = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.hidden_apps)));
        String liveTv = res.getString(R.string.live_tv_package).trim();
        String liveTvAction = res.getString(R.string.live_tv_action).trim();
        String panelService = res.getString(R.string.source_panel_service).trim();
        String panelAction = res.getString(R.string.source_panel_action).trim();
        String inputAction = res.getString(R.string.input_action).trim();
        int columns = res.getInteger(R.integer.columns);
        int rowsBelow = res.getInteger(R.integer.rows_below_dock);
        boolean shamsi = res.getBoolean(R.bool.shamsi_date);
        boolean moduleCards = res.getBoolean(R.bool.module_cards);
        boolean reducedMotion = res.getBoolean(R.bool.reduced_motion);
        String vpn = res.getString(R.string.vpn_app).trim();
        boolean homeSettings = res.getBoolean(R.bool.show_home_settings_shortcut);
        int background = 0;   // 0: Backdrop.base(), the plum room; a background_color in config.json is used as is
        boolean applied = false;
        boolean inputsPill = res.getBoolean(R.bool.show_inputs_pill);
        boolean networkPill = res.getBoolean(R.bool.show_network_pill);
        String imdbUser = res.getString(R.string.watchlist_imdb_user).trim();
        String country = res.getString(R.string.watchlist_country).trim();
        List<String> services = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.watchlist_services)));
        List<String> languages = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.watchlist_languages)));
        boolean thisYear = res.getBoolean(R.bool.watchlist_this_year);
        boolean podcasts = res.getBoolean(R.bool.watchlist_podcasts);
        List<String> podcastChannels = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.watchlist_podcast_channels)));
        boolean trending = res.getBoolean(R.bool.watchlist_trending);
        boolean worldCinema = res.getBoolean(R.bool.watchlist_world_cinema);
        boolean weather = res.getBoolean(R.bool.weather);
        String weatherPlace = res.getString(R.string.weather_place).trim();
        String personalName = "";
        List<String> personalMix = new ArrayList<>();
        List<String> personalTitles = new ArrayList<>();
        List<String> directors = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.watchlist_directors)));
        boolean sounds = res.getBoolean(R.bool.navigation_sounds);
        int soundVolume = res.getInteger(R.integer.navigation_sound_volume);
        String soundSet = res.getString(R.string.navigation_sound_set);
        List<String> excludeGenres = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.watchlist_exclude_genres)));
        boolean gallery = res.getBoolean(R.bool.screensaver_gallery);
        String letterboxd = res.getString(R.string.watchlist_letterboxd_user).trim();

        File file = overlayFile(ctx);
        File dir = file != null ? file.getParentFile() : dir(ctx);
        JSONObject json = read(file);
        if (json != null) {
            applied = true;
            dock = stringList(json, "dock_apps", dock);
            hidden = stringList(json, "hidden_apps", hidden);
            liveTv = string(json, "live_tv_package", liveTv);
            liveTvAction = string(json, "live_tv_action", liveTvAction);
            panelService = string(json, "source_panel_service", panelService);
            panelAction = string(json, "source_panel_action", panelAction);
            inputAction = string(json, "input_action", inputAction);
            columns = integer(json, "columns", columns, 1, 8);
            rowsBelow = integer(json, "rows_below_dock", rowsBelow, 0, 3);
            shamsi = bool(json, "shamsi_date", shamsi);
            moduleCards = bool(json, "module_cards", moduleCards);
            reducedMotion = bool(json, "reduced_motion", reducedMotion);
            vpn = string(json, "vpn_app", vpn);
            homeSettings = bool(json, "show_home_settings_shortcut", homeSettings);
            background = color(json, "background_color", background);
            inputsPill = bool(json, "show_inputs_pill", inputsPill);
            networkPill = bool(json, "show_network_pill", networkPill);
            imdbUser = string(json, "watchlist_imdb_user", imdbUser);
            country = string(json, "watchlist_country", country);
            services = stringList(json, "watchlist_services", services);
            languages = stringList(json, "watchlist_languages", languages);
            thisYear = bool(json, "watchlist_this_year", thisYear);
            podcasts = bool(json, "watchlist_podcasts", podcasts);
            podcastChannels = stringList(json, "watchlist_podcast_channels", podcastChannels);
            trending = bool(json, "watchlist_trending", trending);
            worldCinema = bool(json, "watchlist_world_cinema", worldCinema);
            weather = bool(json, "weather", weather);
            weatherPlace = string(json, "weather_place", weatherPlace);
            personalName = string(json, "watchlist_personal_name", personalName);
            personalMix = stringList(json, "watchlist_personal_mix", personalMix);
            personalTitles = stringList(json, "watchlist_personal_titles", personalTitles);
            directors = stringList(json, "watchlist_directors", directors);
            sounds = bool(json, "navigation_sounds", sounds);
            soundVolume = integer(json, "navigation_sound_volume", soundVolume, 0, 100);
            soundSet = string(json, "navigation_sound_set", soundSet);
            excludeGenres = stringList(json, "watchlist_exclude_genres", excludeGenres);
            gallery = bool(json, "screensaver_gallery", gallery);
            letterboxd = string(json, "watchlist_letterboxd_user", letterboxd);
        }
        File wallpaper = dir != null ? new File(dir, WALLPAPER_NAME) : null;
        if (wallpaper != null && !(wallpaper.isFile() && wallpaper.length() > 0)) wallpaper = null;
        // A theme can also switch the wallpaper off while the file is still there.
        if (json != null && json.has("wallpaper") && !bool(json, "wallpaper", true)) wallpaper = null;

        return new Config(dock, hidden, liveTv, liveTvAction, panelService, panelAction, inputAction,
                Math.max(1, columns), Math.max(0, rowsBelow), shamsi, moduleCards, reducedMotion,
                vpn, homeSettings, background, wallpaper, applied,
                inputsPill, networkPill, imdbUser, country, services, languages, thisYear, podcasts,
                podcastChannels, trending, worldCinema, weather, weatherPlace, personalName, personalMix, personalTitles, directors,
                sounds, soundVolume, soundSet, excludeGenres, gallery, letterboxd);
    }

    /** Reads and parses the overlay; any problem means "no overlay", never a crash on the home screen. */
    private static JSONObject read(File file) {
        if (file == null || !file.isFile()) return null;
        if (file.length() > 64 * 1024) {
            Log.w(TAG, "ignoring " + file + ": larger than 64 KB");
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            String text = new String(buf, 0, n, StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) text = text.substring(1);   // Windows editors add a BOM
            return new JSONObject(text);
        } catch (Exception e) {
            Log.w(TAG, "ignoring " + file + ": " + e.getMessage());
            return null;
        }
    }

    private static String string(JSONObject o, String key, String fallback) {
        Object v = o.opt(key);
        return v instanceof String ? ((String) v).trim() : fallback;
    }

    private static boolean bool(JSONObject o, String key, boolean fallback) {
        Object v = o.opt(key);
        return v instanceof Boolean ? (Boolean) v : fallback;
    }

    private static int integer(JSONObject o, String key, int fallback, int min, int max) {
        Object v = o.opt(key);
        if (!(v instanceof Number)) return fallback;
        int i = ((Number) v).intValue();
        return i < min || i > max ? fallback : i;
    }

    private static int color(JSONObject o, String key, int fallback) {
        Object v = o.opt(key);
        if (!(v instanceof String)) return fallback;
        try {
            int c = Color.parseColor(((String) v).trim());
            return c | 0xFF000000;   // always opaque: the window behind is black anyway
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static List<String> stringList(JSONObject o, String key, List<String> fallback) {
        Object v = o.opt(key);
        if (!(v instanceof JSONArray)) return fallback;
        JSONArray a = (JSONArray) v;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            Object item = a.opt(i);
            if (item instanceof String && !((String) item).trim().isEmpty()) out.add(((String) item).trim());
        }
        return out;
    }
}
