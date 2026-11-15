package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A home screen of channels, for someone who knows what he likes and doesn't want to go looking for it.
 *
 * The film he's on fills the top of the screen: its name, year, who's in it and the app it plays in, all on one
 * left edge. Under it the channels are rows: the one he's on in full, the next one peeking in below. Up and Down
 * move between rows, Left and Right along one, OK watches. A row of directors or stars opens that person's films
 * just under it, and Back closes them. Rows that run in order (the Bond films, a series in Stremio) start at the
 * next one he hasn't seen: after he has been away for most of a film, the row moves on.
 */
public class ChannelsActivity extends Activity {

    static final String EXTRA_WELCOME = "welcome";
    static final String EXTRA_FRESH = "fresh";
    private static final int TITLES = 0, PEOPLE = 1, FILMS_OF = 2, APPS = 3;
    private static final int ROW_H = 166, CELL_W = 176, CELL_H = 99, FACE = 99, FACE_W = 124, GAP = 16;
    private static final List<String> DEFAULT_APPS = Arrays.asList("com.netflix.ninja", "com.amazon.firebat",
            "com.amazon.amazonvideo.livingroom", "com.amazon.firetv.youtube", "com.google.android.youtube.tv",
            "in.startv.hotstar", "com.stremio.one", "com.apple.atve.amazon.appletv", "com.apple.atve.androidtv.appletv",
            "com.onemainstream.sonyliv.android", "com.sonyliv", "com.zee5.amazon", "com.graymatrix.did");

    /** One row on screen: a channel of titles, a row of people, one person's films, or the apps. */
    private static final class Row {
        int kind;
        String name;
        ChannelData.Channel channel;
        ChannelData.Person person;
        final List<ChannelData.Item> items = new ArrayList<>();
        final List<ChannelData.Person> people = new ArrayList<>();
        final List<String> apps = new ArrayList<>();
        int focus, start;
        LinearLayout view, strip;
        TextView label;

        int size() {
            return kind == PEOPLE ? people.size() : kind == APPS ? apps.size() : items.size();
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> thumbs = new LruCache<>(48);

    private Profiles.Person person;
    private List<ChannelData.Channel> channels = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private SharedPreferences state;
    private int at;   // the row he's on
    private boolean reducedMotion;
    private float density;

    private ImageView front, back;
    private ObjectAnimator drift;
    private String shownStill;
    private TextView kicker, title, meta, plot, where, note, hello, date;
    private LinearLayout rowsColumn;
    private View act;

    private final Runnable hideNote = () -> note.setVisibility(View.GONE);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        person = Profiles.active(this);
        if (person == null || !person.channels()) {
            finish();
            return;
        }
        setContentView(R.layout.activity_channels);
        density = getResources().getDisplayMetrics().density;
        reducedMotion = Config.load(this).reducedMotion;
        state = getSharedPreferences("channels_state_" + person.id, MODE_PRIVATE);
        front = findViewById(R.id.stillA);
        back = findViewById(R.id.stillB);
        kicker = findViewById(R.id.kicker);
        title = findViewById(R.id.title);
        meta = findViewById(R.id.meta);
        plot = findViewById(R.id.plot);
        where = findViewById(R.id.where);
        note = findViewById(R.id.note);
        hello = findViewById(R.id.hello);
        date = findViewById(R.id.date);
        act = findViewById(R.id.act);
        rowsColumn = findViewById(R.id.rows);

        int night = getColor(R.color.night);
        findViewById(R.id.scrimLeft).setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{alpha(night, 0.9f), alpha(night, 0.45f), alpha(night, 0f)}));
        findViewById(R.id.scrimBottom).setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{alpha(night, 1f), alpha(night, 0.92f), alpha(night, 0f)}));
        findViewById(R.id.scrimTop).setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{alpha(night, 0.55f), alpha(night, 0f)}));
        for (int id : new int[]{R.id.time, R.id.date, R.id.kicker, R.id.title, R.id.meta}) {
            ((TextView) findViewById(id)).setShadowLayer(dp(6), 0, dp(1), 0xB3000000);
        }

        List<ChannelData.Channel> saved = ChannelData.load(this, person.id);
        if (saved != null) channels = saved;
        buildRows(state.getInt("row", 0));
        if (getIntent().getBooleanExtra(EXTRA_WELCOME, false)) welcome();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (person == null) return;
        Profiles.Person now = Profiles.active(this);
        if (now == null || !now.id.equals(person.id) || !now.channels()) {   // someone else is watching now
            finish();
            if (now != null) Profiles.open(this, now, false);
            return;
        }
        if (intent.getBooleanExtra(EXTRA_WELCOME, false)) welcome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (person == null) return;
        date.setText(new SimpleDateFormat("EEEE d MMMM", Locale.ENGLISH).format(new Date()));
        cameBack();
        ChannelData.refreshIfStale(this, person, fresh -> {
            channels = fresh;
            buildRows(at);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        decoder.shutdownNow();
        handler.removeCallbacksAndMessages(null);
    }

    // ---- rows ---------------------------------------------------------------------------------

    private void buildRows(int wantRow) {
        rows.clear();
        for (ChannelData.Channel c : channels) {
            Row r = new Row();
            r.channel = c;
            r.name = c.name;
            if (!c.people.isEmpty()) {
                r.kind = PEOPLE;
                r.people.addAll(c.people);
            } else {
                r.kind = TITLES;
                r.items.addAll(c.items);
                r.focus = r.start = cursor(c);
            }
            rows.add(r);
        }
        if (!channels.isEmpty()) {
            Row apps = new Row();
            apps.kind = APPS;
            apps.name = getString(R.string.channels_apps);
            apps.apps.addAll(installedApps());
            if (!apps.apps.isEmpty()) rows.add(apps);
        }
        at = Math.max(0, Math.min(rows.size() - 1, wantRow));
        rowsColumn.removeAllViews();
        for (Row r : rows) rowsColumn.addView(rowView(r));
        layoutRows(false);
    }

    private LinearLayout rowView(Row r) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setClipChildren(false);
        v.setPadding(dp(56), 0, 0, 0);
        r.label = label(r.name, 15, R.font.mukta_semibold, getColor(R.color.ivory));
        v.addView(r.label);
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setClipChildren(false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(8);
        v.addView(strip, sp);
        r.view = v;
        r.strip = strip;
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ROW_H)));
        return v;
    }

    /** Slides the column so row `at` sits under the film, fades the rest, and fills the nearby rows' cells. */
    private void layoutRows(boolean animate) {
        if (rows.isEmpty()) {
            drawEmpty();
            return;
        }
        float y = -dp(ROW_H) * at;
        if (animate && !reducedMotion) rowsColumn.animate().translationY(y).setDuration(320).start();
        else rowsColumn.setTranslationY(y);
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            float a = i < at ? 0f : i == at ? 1f : i == at + 1 ? 0.5f : 0.22f;
            if (animate && !reducedMotion) r.view.animate().alpha(a).setDuration(320).start();
            else r.view.setAlpha(a);
            r.label.setTextColor(i == at ? getColor(R.color.ivory) : getColor(R.color.ivory_dim));
            if (i >= at - 1 && i <= at + 2) fill(r, i == at);
            else r.strip.removeAllViews();
        }
        state.edit().putInt("row", at).apply();
        drawHero();
    }

    private int visible(Row r) {
        int cell = (r.kind == PEOPLE ? FACE_W : CELL_W) + GAP;
        return Math.max(1, (int) ((getResources().getDisplayMetrics().widthPixels / density - 56) / cell));
    }

    /** Builds the row's cells around its focus; only the few that fit the screen. */
    private void fill(Row r, boolean focused) {
        int n = r.size(), vis = visible(r);
        if (r.focus < r.start) r.start = r.focus;
        if (r.focus > r.start + vis - 2) r.start = Math.max(0, r.focus - vis + 2);
        r.start = Math.max(0, Math.min(r.start, Math.max(0, n - vis + 1)));
        r.strip.removeAllViews();
        for (int i = r.start; i < Math.min(n, r.start + vis + 1); i++) r.strip.addView(cell(r, i, focused && i == r.focus));
    }

    private View cell(Row r, int i, boolean lit) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        boolean face = r.kind == PEOPLE;
        int w = dp(face ? FACE_W : CELL_W);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(GAP));
        cell.setGravity(face ? Gravity.CENTER_HORIZONTAL : Gravity.START);

        FrameLayout pic = new FrameLayout(this);
        int pw = dp(face ? FACE : CELL_W), ph = dp(face ? FACE : CELL_H);
        pic.setClipToOutline(true);
        final float radius = face ? pw / 2f : dp(6);
        pic.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        pic.setBackgroundColor(0xFF2A221A);
        ImageView img = new ImageView(this);
        img.setScaleType(r.kind == APPS ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        pic.addView(img, new FrameLayout.LayoutParams(pw, ph));
        String text;
        if (r.kind == APPS) {
            String pkg = r.apps.get(i);
            img.setImageDrawable(banner(pkg));
            text = appLabel(pkg);
        } else if (face) {
            ChannelData.Person p = r.people.get(i);
            loadThumb(p.photo, img, 2);
            text = p.name;
        } else {
            ChannelData.Item it = r.items.get(i);
            loadThumb(it.still, img, 4);
            text = it.series || it.year == 0 ? it.title : it.title + "  " + it.year;
            if (r.kind == TITLES && r.channel.numbered && i < cursor(r.channel)) {
                pic.addView(badge(getString(R.string.channels_seen)), badgeParams());
            }
        }
        if (lit) {
            View ring = new View(this);
            GradientDrawable stroke = new GradientDrawable();
            stroke.setStroke(dp(2.5f), getColor(R.color.brass));
            stroke.setCornerRadius(radius);
            ring.setBackground(stroke);
            pic.addView(ring, new FrameLayout.LayoutParams(pw, ph));
        }
        cell.addView(pic, new LinearLayout.LayoutParams(pw, ph));
        TextView t = label(text, 12, R.font.mukta_medium, getColor(R.color.ivory));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(face ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(6);
        cell.addView(t, tp);
        cell.setAlpha(lit ? 1f : 0.8f);
        if (lit) {   // grows from its top-left corner, so it stays on the row's left edge and clear of the row below
            cell.setPivotX(0);
            cell.setPivotY(0);
            cell.setScaleX(1.04f);
            cell.setScaleY(1.04f);
        }
        cell.setLayoutParams(lp);
        return cell;
    }

    private TextView badge(String text) {
        TextView seen = label(text, 9, R.font.mukta_semibold, getColor(R.color.ivory_dim));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xC70C0907);
        bg.setCornerRadius(dp(10));
        seen.setBackground(bg);
        seen.setPadding(dp(6), dp(2), dp(6), dp(2));
        return seen;
    }

    private FrameLayout.LayoutParams badgeParams() {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        p.setMargins(dp(6), dp(6), 0, 0);
        return p;
    }

    // ---- the film at the top ------------------------------------------------------------------

    private void drawHero() {
        Row r = rows.get(at);
        act.setVisibility(View.VISIBLE);
        if (r.kind == APPS) {
            String pkg = r.apps.isEmpty() ? null : r.apps.get(r.focus);
            showStill(null);
            setKicker(null);
            showTitle(pkg != null ? appLabel(pkg) : "");
            meta.setText("");
            plot.setText("");
            where.setText(R.string.channels_open_app);
            return;
        }
        if (r.kind == PEOPLE) {
            ChannelData.Person p = r.people.get(r.focus);
            showStill(firstStill(p.films));
            setKicker(getString("director".equals(p.role) ? R.string.channels_director : R.string.channels_star));
            showTitle(p.name);
            List<String> names = new ArrayList<>();
            for (int i = 0; i < Math.min(4, p.films.size()); i++) names.add(p.films.get(i).title);
            meta.setText(getString(R.string.channels_best_rated, TextUtils.join(", ", names)));
            plot.setText("");
            where.setText(R.string.channels_see_films);
            return;
        }
        ChannelData.Item it = r.items.get(r.focus);
        showStill(it.still);
        showTitle(it.title);
        List<String> bits = new ArrayList<>();
        if (byEpisode(it)) {
            ChannelData.Episode ep = it.episodes.get(episode(it));
            setKicker(getString(R.string.channels_episode_kicker, ep.season, ep.number));
            bits.add(ep.title);
            if (ep.mins > 0) bits.add(getString(R.string.channels_minutes, ep.mins));
        } else {
            if (r.kind == TITLES && r.channel.numbered) {
                int cur = cursor(r.channel);
                setKicker(r.focus < cur ? getString(R.string.channels_seen_it) : r.focus == cur ? getString(R.string.channels_up_next) : null);
            } else {
                setKicker(it.series ? seasons(it) : null);
            }
            if (it.year > 0) bits.add(it.series && it.endYear > it.year ? it.year + "–" + it.endYear : String.valueOf(it.year));
            if (!it.series && it.mins > 0) bits.add(it.mins >= 60 ? getString(R.string.channels_hours, it.mins / 60, it.mins % 60)
                    : getString(R.string.channels_minutes, it.mins));
        }
        if (!it.cast.isEmpty()) bits.add(TextUtils.join(", ", it.cast));
        meta.setText(TextUtils.join("  ·  ", bits));
        plot.setText(it.plot != null ? it.plot : "");
        where.setText(it.inStremio() ? getString(R.string.channels_watch_in, ChannelData.STREMIO)
                : getString(R.string.channels_watch_on, it.service));
    }

    private void drawEmpty() {
        showStill(null);
        setKicker(null);
        showTitle(getString(R.string.channels_getting_ready));
        meta.setText(R.string.channels_getting_ready_note);
        plot.setText("");
        act.setVisibility(View.GONE);
    }

    private void setKicker(String text) {
        kicker.setText(text != null ? text : "");
        kicker.setVisibility(text != null ? View.VISIBLE : View.GONE);
    }

    private void showTitle(String t) {
        title.setText(t);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.length() <= 13 ? 50 : t.length() <= 22 ? 42 : 34);
    }

    private String seasons(ChannelData.Item it) {
        int n = 0;
        for (ChannelData.Episode e : it.episodes) n = Math.max(n, e.season);
        return n > 1 ? getString(R.string.channels_seasons, n) : getString(R.string.channels_series);
    }

    private static String firstStill(List<ChannelData.Item> films) {
        for (ChannelData.Item f : films) if (f.still != null) return f.still;
        return null;
    }

    // ---- the remote ---------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (e.getAction() != KeyEvent.ACTION_DOWN) {
            return k != KeyEvent.KEYCODE_HOME && k != KeyEvent.KEYCODE_VOLUME_UP && k != KeyEvent.KEYCODE_VOLUME_DOWN
                    && k != KeyEvent.KEYCODE_VOLUME_MUTE || super.dispatchKeyEvent(e);
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                moveRow(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                moveRow(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                moveAlong(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                moveAlong(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (e.getRepeatCount() == 0) choose();
                return true;
            case KeyEvent.KEYCODE_BACK:
                back();
                return true;   // this is home: Back goes nowhere further
            case KeyEvent.KEYCODE_MENU:
                if (e.getRepeatCount() == 0) startActivity(new Intent(this, ProfilesActivity.class));
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void moveRow(int by) {
        if (rows.isEmpty()) return;
        int next = Math.max(0, Math.min(rows.size() - 1, at + by));
        if (next == at) return;
        at = next;
        layoutRows(true);
    }

    private void moveAlong(int by) {
        if (rows.isEmpty()) return;
        Row r = rows.get(at);
        int next = Math.max(0, Math.min(r.size() - 1, r.focus + by));
        if (next == r.focus) return;
        r.focus = next;
        fill(r, true);
        drawHero();
    }

    private void back() {
        if (rows.isEmpty()) return;
        Row r = rows.get(at);
        if (r.kind == FILMS_OF) {   // close the person's films, back to their face
            rows.remove(at);
            rowsColumn.removeView(r.view);
            at--;
            layoutRows(true);
            return;
        }
        int home = r.kind == TITLES ? cursor(r.channel) : 0;
        if (r.focus != home) {
            r.focus = home;
            fill(r, true);
            drawHero();
        }
    }

    private void choose() {
        if (rows.isEmpty()) return;
        Row r = rows.get(at);
        if (r.kind == APPS) {
            Intent i = r.apps.isEmpty() ? null : Watchlist.launchIntent(getPackageManager(), r.apps.get(r.focus));
            if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if (r.kind == PEOPLE) {
            openPerson(r.people.get(r.focus));
            return;
        }
        watch(r, r.items.get(r.focus));
    }

    /** Opens a person's films as a row just under theirs, and moves down to it. */
    private void openPerson(ChannelData.Person p) {
        if (at + 1 < rows.size() && rows.get(at + 1).kind == FILMS_OF) {
            Row old = rows.remove(at + 1);
            rowsColumn.removeView(old.view);
        }
        Row films = new Row();
        films.kind = FILMS_OF;
        films.person = p;
        films.name = getString("director".equals(p.role) ? R.string.channels_films_by : R.string.channels_films_with, p.name);
        films.items.addAll(p.films);
        rows.add(at + 1, films);
        rowsColumn.addView(rowView(films), at + 1);
        at++;
        layoutRows(true);
    }

    // ---- watching, and coming back ------------------------------------------------------------

    private int cursor(ChannelData.Channel c) {
        int i = state.getInt("cur_" + c.name, 0);
        return i >= 0 && i < c.items.size() ? i : 0;
    }

    private int episode(ChannelData.Item it) {
        int i = state.getInt("ep_" + it.imdb, 0);
        return i >= 0 && i < it.episodes.size() ? i : 0;
    }

    /** A series he follows in Stremio is played episode by episode; in his apps, the app keeps his place. */
    private static boolean byEpisode(ChannelData.Item it) {
        return it.series && it.inStremio() && !it.episodes.isEmpty();
    }

    private void watch(Row r, ChannelData.Item it) {
        ChannelData.Episode ep = byEpisode(it) ? it.episodes.get(episode(it)) : null;
        Intent i = ChannelData.intentFor(it, ep);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Intent fallback = i.getPackage() != null ? Watchlist.launchIntent(getPackageManager(), i.getPackage()) : null;
            if (fallback == null) {
                say(getString(R.string.channels_cannot_open, it.inStremio() ? ChannelData.STREMIO : it.service));
                return;
            }
            startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        String channel = r.kind == TITLES ? r.channel.name : "";
        state.edit().putString("p_ch", channel).putInt("p_idx", r.focus).putInt("p_ep", ep != null ? episode(it) : -1)
                .putLong("p_at", System.currentTimeMillis()).putInt("p_mins", ep != null ? ep.mins : it.mins).apply();
    }

    /**
     * Back from a film: if he was away for most of its length, its row moves on (a series stays on the series, at
     * its next episode). Back after a few minutes, nothing changes.
     */
    private void cameBack() {
        long started = state.getLong("p_at", 0);
        if (started == 0) return;
        String name = state.getString("p_ch", "");
        int idx = state.getInt("p_idx", -1), epIdx = state.getInt("p_ep", -1), mins = state.getInt("p_mins", 0);
        state.edit().remove("p_at").apply();
        long needed = (mins > 0 ? Math.max(15, (long) (mins * 0.6)) : 40) * 60_000L;
        if (name.isEmpty() || System.currentTimeMillis() - started < needed) return;
        for (Row r : rows) {
            if (r.kind != TITLES || !r.channel.name.equals(name) || idx < 0 || idx >= r.items.size()) continue;
            ChannelData.Item it = r.items.get(idx);
            SharedPreferences.Editor ed = state.edit();
            String upNext = null;
            int nextIdx;
            if (it.series) {
                nextIdx = idx;
                if (epIdx >= 0 && epIdx + 1 < it.episodes.size()) {
                    ed.putInt("ep_" + it.imdb, epIdx + 1);
                    ChannelData.Episode n = it.episodes.get(epIdx + 1);
                    upNext = getString(R.string.channels_next_episode, n.season, n.number, n.title);
                } else if (epIdx >= 0) {
                    ed.putInt("ep_" + it.imdb, 0);
                    nextIdx = (idx + 1) % r.items.size();
                    upNext = getString(R.string.channels_next_on, r.items.get(nextIdx).title);
                }
            } else {
                nextIdx = (idx + 1) % r.items.size();
                if (r.channel.numbered) upNext = getString(R.string.channels_next_on, r.items.get(nextIdx).title);
            }
            ed.putInt("cur_" + r.channel.name, nextIdx).apply();
            r.focus = r.start = nextIdx;
            layoutRows(false);
            if (upNext != null) say(upNext);
            return;
        }
    }

    // ---- small things -------------------------------------------------------------------------

    private void welcome() {
        View w = findViewById(R.id.welcome);
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int greet = h < 12 ? R.string.channels_morning : h < 17 ? R.string.channels_afternoon : R.string.channels_evening;
        hello.setText(getString(greet, person.name));
        w.setAlpha(1f);
        w.setVisibility(View.VISIBLE);
        hello.setAlpha(0f);
        hello.setTranslationY(dp(10));
        hello.animate().alpha(1f).translationY(0).setDuration(reducedMotion ? 0 : 700).start();
        handler.postDelayed(() -> w.animate().alpha(0f).setDuration(reducedMotion ? 0 : 600)
                .withEndAction(() -> w.setVisibility(View.GONE)).start(), 1500);
    }

    private void say(String text) {
        note.setText(text);
        note.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideNote);
        handler.postDelayed(hideNote, 3600);
    }

    private List<String> installedApps() {
        List<String> wanted = new ArrayList<>();
        org.json.JSONArray a = person.json.optJSONArray("apps");
        for (int i = 0; a != null && i < a.length(); i++) wanted.add(a.optString(i));
        if (wanted.isEmpty()) wanted.addAll(DEFAULT_APPS);
        List<String> out = new ArrayList<>();
        PackageManager pm = getPackageManager();
        for (String pkg : wanted) if (Watchlist.launchIntent(pm, pkg) != null && !out.contains(pkg)) out.add(pkg);
        return out;
    }

    private Drawable banner(String pkg) {
        try {
            Drawable d = getPackageManager().getApplicationBanner(pkg);
            return d != null ? d : getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private String appLabel(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private void showStill(String file) {
        if (file == null) {
            shownStill = null;
            front.animate().alpha(0f).setDuration(400).start();
            back.animate().alpha(0f).setDuration(400).start();
            return;
        }
        if (file.equals(shownStill)) return;
        shownStill = file;
        File f = new File(new File(ChannelData.dir(this, person.id), "stills"), file);
        decoder.execute(() -> {
            Bitmap b = decode(f, 1);
            handler.post(() -> {
                if (!file.equals(shownStill) || isDestroyed()) return;
                ImageView in = back, out = front;
                in.setImageBitmap(b);
                in.setScaleX(1f);
                in.setScaleY(1f);
                in.animate().alpha(1f).setDuration(reducedMotion ? 0 : 600).start();
                out.animate().alpha(0f).setDuration(reducedMotion ? 0 : 600).start();
                front = in;
                back = out;
                if (drift != null) drift.cancel();
                if (!reducedMotion) {
                    drift = ObjectAnimator.ofPropertyValuesHolder(in, PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.05f),
                            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.05f));
                    drift.setDuration(24_000);
                    drift.setRepeatMode(ValueAnimator.REVERSE);
                    drift.setRepeatCount(ValueAnimator.INFINITE);
                    drift.start();
                }
            });
        });
    }

    private void loadThumb(String file, ImageView into, int sample) {
        if (file == null) return;
        Bitmap cached = thumbs.get(file);
        if (cached != null) {
            into.setImageBitmap(cached);
            return;
        }
        into.setTag(file);
        File f = new File(new File(ChannelData.dir(this, person.id), "stills"), file);
        decoder.execute(() -> {
            Bitmap b = decode(f, sample);
            if (b == null) return;
            handler.post(() -> {
                thumbs.put(file, b);
                if (file.equals(into.getTag())) into.setImageBitmap(b);
            });
        });
    }

    private static Bitmap decode(File f, int sample) {
        if (!f.isFile()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        try {
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private TextView label(String text, float sp, int font, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(getResources().getFont(font));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private static int alpha(int color, float a) {
        return (Math.round(a * 255) << 24) | (color & 0x00FFFFFF);
    }
}
