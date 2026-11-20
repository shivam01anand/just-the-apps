package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A home screen of walls, for someone who wants the best of everything in front of them and nothing else.
 *
 * A wall of posters fills the TV. Stay on a poster for three seconds and the wall dims for a frame from the film,
 * wide across the screen like a lobby card, with what a film lover wants under it: its title (and the one it was
 * made under), genre, country, year, length, language, black and white or not, the shape of its frame, its IMDb
 * rating and place in the top 250, its Oscars, a line of story and where it plays. OK watches it. Up from the top row
 * shows the other walls along the top: Left and Right change wall, Down goes back in. Play shuffles the wall.
 * A wall of directors opens each one's films; the world wall is a map, and OK on a country opens its films.
 *
 * The look is a wall of film posters at night. The posters bring every colour, the type is a poster's own billing
 * block, and the only accent is IMDb's yellow, kept for ratings.
 */
public class WallsActivity extends Activity implements WallView.Host {

    static final String EXTRA_FRESH = "fresh";
    private static final int WALL = 0, BAR = 1, MAP = 2, SUB = 3;
    private static final int SCREEN = 0xFF070A0B, INK = 0xFFF2F0EA, DIM = 0xFFAEB4B2, FAINT = 0xFF737B7C, GOLD = 0xFFF5C518,
            LINE = 0x33F2F0EA;
    private static final String[] YOUTUBE = {"com.google.android.youtube.tv", "com.amazon.firetv.youtube"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private Sounds sounds;
    private Profiles.Person person;
    private List<WallData.Wall> walls = new ArrayList<>();
    private int at, mode = WALL, parentMode = WALL;
    private boolean onShuffle, reducedMotion;
    private float s = 1;
    private Typeface billing, plain, semi;

    private WallImages images;
    private WallView wall;
    private WorldMapView map;
    private FrameLayout root, bar;
    private HorizontalScrollView strip;
    private LinearLayout card, panel, tabs, flash, subhead, titles, score;
    private TextView note, waiting, where, story, lobbyWhere;
    private FrameLayout lobby;
    private ImageView scene, sheet;
    /** The film the lobby card is showing, and the frame of it that is up. */
    private WallData.Tile lobbyTile;
    private String sceneFor;
    private Typeface serif;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    /** The wall a director's or a country's films were opened from, to hang again on Back. */
    private List<WallData.Tile> parentTiles;
    private String parentKind;
    private int parentC, parentR;

    private final Runnable hideFlash = () -> flash.animate().alpha(0f).setDuration(reducedMotion ? 0 : 500).start();
    private final Runnable hideNote = () -> note.animate().alpha(0f).setDuration(reducedMotion ? 0 : 200).start();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        person = Profiles.active(this);
        if (person == null || !person.walls()) {
            finish();
            return;
        }
        s = getResources().getDisplayMetrics().widthPixels / WallView.W;
        reducedMotion = Config.load(this).reducedMotion;
        sounds = new Sounds(this);
        billing = face("'wdth' 62, 'wght' 800");
        plain = face("'wdth' 100, 'wght' 400");
        semi = face("'wdth' 100, 'wght' 600");
        serif = Typeface.create(Typeface.SERIF, Typeface.ITALIC);
        images = new WallImages(WallData.dir(this, person.id));

        root = new FrameLayout(this);
        root.setBackgroundColor(SCREEN);
        wall = new WallView(this);
        wall.setUp(images, this, reducedMotion);
        wall.setFaces(billing, semi, plain);
        images.onLoaded(wall::invalidate);
        root.addView(wall, fill());
        map = new WorldMapView(this);
        map.setVisibility(View.GONE);
        root.addView(map, fill());

        panel = column();
        panel.setPadding(px(52), px(44), px(52), px(44));
        panel.setBackgroundColor(0xF0090D0E);
        panel.setVisibility(View.GONE);
        root.addView(panel, new FrameLayout.LayoutParams(px(760), FrameLayout.LayoutParams.MATCH_PARENT));

        subhead = line();
        subhead.setVisibility(View.GONE);
        root.addView(subhead, spot(60, 30));

        card = column();
        card.setPadding(px(34), px(30), px(34), px(30));
        card.setVisibility(View.INVISIBLE);
        root.addView(card, new FrameLayout.LayoutParams(px(540), px(450)));

        buildLobby();

        flash = line();
        flash.setAlpha(0f);
        root.addView(flash, spot(56, 44));

        bar = new FrameLayout(this);
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xF5050809, 0xE6050809, 0x00050809}));
        tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        // The names can run wider than the screen: they slide so the one the viewer is on stays in view.
        strip = new HorizontalScrollView(this);
        strip.setHorizontalScrollBarEnabled(false);
        strip.setFocusable(false);
        strip.setOverScrollMode(View.OVER_SCROLL_NEVER);
        strip.addView(tabs);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        sp.setMargins(px(56), px(44), px(56), 0);
        bar.addView(strip, sp);
        bar.setTranslationY(-px(210));
        root.addView(bar, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, px(210)));

        note = text("", 26, semi, 0xFF0B0F10);
        note.setBackgroundColor(INK);
        note.setPadding(px(26), px(14), px(26), px(14));
        note.setAlpha(0f);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        np.bottomMargin = px(60);
        root.addView(note, np);

        waiting = text(getString(R.string.walls_hanging), 30, plain, FAINT);
        waiting.setGravity(Gravity.CENTER);
        root.addView(waiting, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);

        walls = WallData.load(this, person.id);
        if (!walls.isEmpty()) {
            waiting.setVisibility(View.GONE);
            buildTabs();
            show(0);
            enter(true);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (person == null) return;
        Profiles.Person now = Profiles.active(this);
        if (now == null || !now.id.equals(person.id) || !now.walls()) {   // someone else is watching now
            finish();
            if (now != null) Profiles.open(this, now, false);
            return;
        }
        if (intent.getBooleanExtra(EXTRA_FRESH, false) && !walls.isEmpty() && (at != 0 || mode != WALL)) {   // Home: the first wall
            if (mode == SUB) leaveSub();
            show(0);
            enter(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (person == null) return;
        sounds.refresh(this);
        WallData.refreshIfStale(this, person, this::onWalls);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        decoder.shutdownNow();
        if (images != null) images.release();
        if (sounds != null) sounds.release();
    }

    /** A wall was built or rebuilt. The one on screen stays as it is until the viewer next comes to it. */
    private void onWalls(List<WallData.Wall> fresh) {
        if (isFinishing() || fresh.isEmpty()) return;
        boolean first = walls.isEmpty();
        String showing = first || at >= walls.size() ? null : walls.get(at).name;
        walls = fresh;
        buildTabs();
        if (first) {
            waiting.setVisibility(View.GONE);
            show(0);
            enter(true);
            return;
        }
        at = 0;
        for (int i = 0; i < walls.size(); i++) if (walls.get(i).name.equals(showing)) at = i;
        markTabs();
    }

    // ---- walls --------------------------------------------------------------------------------

    private void show(int i) {
        at = i;
        onShuffle = false;
        WallData.Wall w = walls.get(i);
        card.setVisibility(View.INVISIBLE);
        subhead.setVisibility(View.GONE);
        boolean world = WallData.MAP.equals(w.kind);
        wall.setVisibility(world ? View.GONE : View.VISIBLE);
        map.setVisibility(world ? View.VISIBLE : View.GONE);
        panel.setVisibility(world ? View.VISIBLE : View.GONE);
        if (world) {
            wall.rest(false);
            Set<String> codes = new HashSet<>();
            for (WallData.Country c : w.countries) codes.add(c.code);
            map.countries(codes);
            fillPanel();
        } else {
            wall.hang(w.tiles, w.kind, 3, 1);
            wall.dim(mode == BAR);
        }
        markTabs();
    }

    /** Into the wall, from the bar or as the screen opens. */
    private void enter(boolean announce) {
        WallData.Wall w = walls.get(at);
        mode = WallData.MAP.equals(w.kind) ? MAP : WALL;
        onShuffle = false;
        markTabs();
        bar.animate().translationY(-px(210)).setDuration(reducedMotion ? 0 : 260).start();
        wall.dim(false);
        if (mode == WALL) {
            if (announce) announce(w.name, w.size() + " " + w.what);
            wall.rest(true);
        }
    }

    private void openBar() {
        mode = BAR;
        wall.rest(false);
        wall.dim(true);
        flash.animate().cancel();
        flash.setAlpha(0f);
        bar.animate().translationY(0).setDuration(reducedMotion ? 0 : 260).start();
        markTabs();
    }

    private void buildTabs() {
        tabs.removeAllViews();
        tabs.addView(tab(getString(R.string.walls_shuffle)));
        View rule = new View(this);
        rule.setBackgroundColor(LINE);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(Math.max(1, px(1)), px(40));
        rp.rightMargin = px(38);
        tabs.addView(rule, rp);
        for (WallData.Wall w : walls) tabs.addView(tab(w.name));
        markTabs();
    }

    private View tab(String name) {
        TextView t = caps(name, 34, FAINT);
        t.setPadding(0, 0, 0, px(17));
        t.setBackground(new Underline());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = px(38);
        t.setLayoutParams(lp);
        return t;
    }

    /** The line under the wall the viewer is on. */
    private final class Underline extends Drawable {
        private final Paint paint = new Paint();
        boolean on;

        @Override
        public void draw(Canvas canvas) {
            if (!on) return;
            paint.setColor(INK);
            canvas.drawRect(getBounds().left, getBounds().bottom - px(5), getBounds().right, getBounds().bottom, paint);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Lights the wall the viewer is on (or Shuffle) and slides the names so it is on screen. */
    private void markTabs() {
        View lit = null;
        for (int i = 0; i < tabs.getChildCount(); i++) {
            View v = tabs.getChildAt(i);
            if (!(v instanceof TextView)) continue;
            boolean on = onShuffle ? i == 0 : i == at + 2;
            ((TextView) v).setTextColor(on ? INK : FAINT);
            ((Underline) v.getBackground()).on = on;
            v.invalidate();
            if (on) lit = v;
        }
        final View target = lit;
        strip.post(() -> {
            if (target == null) return;
            int to = Math.max(0, target.getRight() + px(120) - strip.getWidth());
            if (reducedMotion) strip.scrollTo(to, 0);
            else strip.smoothScrollTo(to, 0);
        });
    }

    /** Shuffle rehangs the wall in a new order. On the map it goes to a country. */
    private void shuffle() {
        if (walls.isEmpty()) return;
        sounds.select();
        if (mode != SUB && WallData.MAP.equals(walls.get(at).kind)) {
            List<WallData.Country> cs = walls.get(at).countries;
            if (cs.size() < 2) return;
            String code;
            do {
                code = cs.get(random.nextInt(cs.size())).code;
            } while (code.equals(map.focused()));
            map.focus(code);
            fillPanel();
            return;
        }
        boolean sub = mode == SUB;
        wall.hang(WallData.shuffled(wall.tiles()), sub ? "sub" : walls.get(at).kind, sub ? 0 : 3, sub ? 0 : 1);
        wall.dim(mode == BAR);
        if (mode != BAR) wall.rest(true);
    }

    private void enterSub(String name, List<WallData.Tile> films) {
        if (films.isEmpty()) return;
        parentMode = mode;
        if (mode == WALL) {
            parentTiles = wall.tiles();
            parentKind = walls.get(at).kind;
            WallData.Tile f = wall.focused();
            parentC = f.c;
            parentR = f.r;
        }
        mode = SUB;
        map.setVisibility(View.GONE);
        panel.setVisibility(View.GONE);
        wall.setVisibility(View.VISIBLE);
        subhead.removeAllViews();
        subhead.addView(caps(name, 54, INK));
        subhead.addView(after(text(getString(R.string.walls_best_films, films.size()), 24, plain, DIM), 18));
        subhead.setVisibility(View.VISIBLE);
        wall.hang(films, "sub", 0, 0);
        wall.rest(true);
    }

    private void leaveSub() {
        subhead.setVisibility(View.GONE);
        card.setVisibility(View.INVISIBLE);
        mode = parentMode;
        if (mode == MAP) {
            wall.rest(false);
            wall.setVisibility(View.GONE);
            map.setVisibility(View.VISIBLE);
            panel.setVisibility(View.VISIBLE);
        } else {
            wall.hang(parentTiles, parentKind, parentC, parentR);
            wall.rest(true);
        }
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
                move(k, 0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                move(k, 0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                move(k, -1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                move(k, 1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (e.getRepeatCount() == 0) ok();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (e.getRepeatCount() == 0) shuffle();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (e.getRepeatCount() == 0) back();
                return true;   // this is home: Back goes nowhere further
            case KeyEvent.KEYCODE_MENU:
                if (e.getRepeatCount() == 0) startActivity(new Intent(this, ProfilesActivity.class));
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void move(int key, int dx, int dy) {
        if (walls.isEmpty()) return;
        if (mode == BAR) {
            if (dy > 0) {
                sounds.move(key);
                enter(true);
            } else if (dx != 0 && onShuffle) {
                if (dx > 0) {
                    onShuffle = false;
                    sounds.move(key);
                    markTabs();
                } else sounds.edge();
            } else if (dx != 0 && at + dx < 0) {
                onShuffle = true;
                sounds.move(key);
                markTabs();
            } else if (dx != 0 && at + dx < walls.size()) {
                sounds.move(key);
                show(at + dx);
            } else sounds.edge();
            return;
        }
        if (mode == MAP) {
            if (map.move(dx, dy)) {
                sounds.move(key);
                fillPanel();
            } else if (dy < 0) {
                sounds.move(key);
                openBar();
            } else sounds.edge();
            return;
        }
        int result = wall.move(dx, dy);
        if (result == WallView.MOVED) sounds.move(key);
        else if (result == WallView.TOP && mode == WALL) {
            sounds.move(key);
            openBar();
        } else sounds.edge();
    }

    private void ok() {
        if (walls.isEmpty()) return;
        sounds.select();
        if (mode == BAR) {
            if (onShuffle) shuffle();
            else enter(true);
            return;
        }
        if (mode == MAP) {
            WallData.Country c = country();
            if (c != null) enterSub(map.name(c.code), c.films);
            return;
        }
        WallData.Tile t = wall.focused();
        if (t == null) return;
        if (t.person()) enterSub(t.title, t.sub);
        else watch(t);
    }

    private void back() {
        sounds.back();
        if (mode == SUB) leaveSub();
        else if (mode == BAR || walls.isEmpty()) startActivity(new Intent(this, ProfilesActivity.class));   // "Who's watching?"
        else openBar();
    }

    private void watch(WallData.Tile t) {
        if (t.video()) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + t.id.substring(3)));
            for (String pkg : YOUTUBE) if (Watchlist.launchIntent(getPackageManager(), pkg) != null) i.setPackage(pkg);
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException e) {
                say(getString(R.string.walls_cannot_open, "YouTube"));
            }
            return;
        }
        // Where it plays is looked up now if nobody has yet; a lookup that fails opens it in Stremio.
        Watchlist.Item it = new Watchlist.Item();
        it.imdb = t.id;
        it.title = t.title;
        it.type = t.type;
        if (t.placed()) {
            it.service = t.service;
            it.pkg = t.pkg;
            it.intentUri = t.intentUri;
        }
        Watchlist.placeThenOpen(this, it);
    }

    // ---- the card beside a lifted tile ----------------------------------------------------------

    @Override
    public void onSettle(WallData.Tile t) {
        if (mode != WALL && mode != SUB) return;
        WallData.place(this, person.id, t, () -> {
            if (lobbyTile != t) return;
            lobbyWhere.setText(whereText(t));
            showScene(t);
        });
    }

    @Override
    public boolean onRest(WallData.Tile t) {
        if (mode != WALL && mode != SUB) return true;
        if (!t.video() && !t.person()) {
            showLobby(t);
            return false;
        }
        RectF l = wall.liftRect;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        lp.width = px(wall.cardW);
        lp.height = px(l.height());
        card.setLayoutParams(lp);
        card.setX(px(wall.cardRight ? l.right : l.left - wall.cardW));
        card.setY(px(l.top));
        card.setBackgroundColor(wall.tone(t));
        card.removeAllViews();
        where = null;
        if (t.video()) videoCard(t);
        else personCard(t);
        card.animate().cancel();
        card.setVisibility(View.VISIBLE);
        if (reducedMotion) {
            card.setAlpha(1f);
        } else {
            float home = card.getX(), from = home + px(wall.cardRight ? -40 : 40);
            card.setAlpha(0f);
            card.setX(from);
            card.animate().alpha(1f).x(home).setDuration(280).setStartDelay(50).start();
        }
        return true;
    }

    @Override
    public void onLeave() {
        card.animate().cancel();
        card.setVisibility(View.INVISIBLE);
        lobby.animate().cancel();
        lobby.setVisibility(View.INVISIBLE);
        lobbyTile = null;
    }

    // ---- the lobby card for a film --------------------------------------------------------------

    private void buildLobby() {
        lobby = new FrameLayout(this);
        lobby.setClipChildren(false);
        lobby.setVisibility(View.INVISIBLE);
        scene = new ImageView(this);
        scene.setScaleType(ImageView.ScaleType.CENTER_CROP);
        scene.setCropToPadding(true);   // the lobby lets its children draw past their edges; a picture must not
        scene.setBackgroundColor(0xFF0E1315);
        lobby.addView(scene, box(340, 84, 1240, 519));   // 2.39:1, the shape of a scope frame
        sheet = new ImageView(this);
        sheet.setScaleType(ImageView.ScaleType.CENTER_CROP);
        sheet.setCropToPadding(true);
        sheet.setBackgroundColor(0xFF12181A);
        sheet.setElevation(px(28));
        lobby.addView(sheet, box(380, 483, 220, 330));
        titles = column();
        titles.setGravity(Gravity.BOTTOM);
        titles.setClipChildren(false);
        lobby.addView(titles, box(640, 615, 940, 237));
        View rule = new View(this);
        rule.setBackgroundColor(LINE);
        lobby.addView(rule, box(380, 866, 1200, 1));
        story = text("", 24, serif, 0xFFD9D6CE);
        story.setMaxLines(3);
        story.setEllipsize(TextUtils.TruncateAt.END);
        story.setLineSpacing(0, 1.16f);
        FrameLayout.LayoutParams sp = box(380, 886, 720, 0);
        sp.height = FrameLayout.LayoutParams.WRAP_CONTENT;
        lobby.addView(story, sp);
        score = line();
        score.setGravity(Gravity.CENTER_VERTICAL);
        lobby.addView(score, spot(1160, 880));
        lobbyWhere = text("", 23, semi, INK);
        lobby.addView(lobbyWhere, spot(380, 1006));
        TextView ok = text(getString(R.string.walls_ok_watch), 23, plain, DIM);
        FrameLayout.LayoutParams op = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END);
        op.topMargin = px(1006);
        op.rightMargin = px(340);
        lobby.addView(ok, op);
        root.addView(lobby, fill());
    }

    private void showLobby(WallData.Tile t) {
        lobbyTile = t;
        titles.removeAllViews();
        if (!t.genres.isEmpty()) {
            TextView kicker = text(TextUtils.join("  ·  ", t.genres).toUpperCase(Locale.ROOT), 21, semi, DIM);
            kicker.setLetterSpacing(0.1f);
            titles.addView(kicker);
        }
        int n = t.title.length();
        TextView title = caps(t.title, n > 34 ? 48 : n > 18 ? 62 : 80, INK);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(below(title, 10));
        if (t.original != null) titles.addView(below(text(t.original, 28, serif, DIM), 6));
        List<String> facts = new ArrayList<>();
        if (t.country != null) facts.add(t.country);
        if (t.year > 0) facts.add(String.valueOf(t.year));
        if (!t.film()) facts.add(getString(R.string.walls_series));
        else if (t.mins >= 60) facts.add(getString(R.string.channels_hours, t.mins / 60, t.mins % 60));
        else if (t.mins > 0) facts.add(getString(R.string.channels_minutes, t.mins));
        if (t.language != null) facts.add(t.language);
        if (t.color != null && !t.color.equalsIgnoreCase("Color")) facts.add(t.color);
        if (t.ratio != null) facts.add(t.ratio);
        titles.addView(below(text(TextUtils.join("  ·  ", facts), 23, plain, DIM), 12));

        story.setText(t.plot != null ? t.plot : "");
        score.removeAllViews();
        if (t.rating > 0) score.addView(caps(String.format(Locale.US, "%.1f", t.rating), 92, GOLD));
        LinearLayout honours = column();
        honours.addView(text(getString(R.string.walls_imdb), 20, plain, DIM));
        if (t.rank > 0 && t.rank <= 250) honours.addView(below(text(getString(R.string.walls_top_rank, t.rank), 21, semi, INK), 4));
        if (t.award != null) honours.addView(below(text(t.award, 21, plain, DIM), 4));
        score.addView(after(honours, 18));
        lobbyWhere.setText(whereText(t));

        images.into(sheet, t);
        scene.setImageDrawable(null);
        sceneFor = null;
        showScene(t);
        lobby.animate().cancel();
        lobby.setVisibility(View.VISIBLE);
        if (reducedMotion) {
            lobby.setAlpha(1f);
        } else {
            lobby.setAlpha(0f);
            lobby.animate().alpha(1f).setDuration(420).start();
        }
    }

    /** A frame from the film across the top. Until one has arrived, the poster's own colours hold its place. */
    private void showScene(WallData.Tile t) {
        final java.io.File f = WallData.still(this, person.id, t);
        if (!f.isFile()) {
            if (sceneFor == null) {
                scene.setImageBitmap(images.get(t, false));
                scene.setColorFilter(0xD9070A0B, android.graphics.PorterDuff.Mode.SRC_ATOP);
            }
            return;
        }
        if (f.getPath().equals(sceneFor)) return;
        sceneFor = f.getPath();
        decoder.execute(() -> {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
            final android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
            handler.post(() -> {
                if (lobbyTile != t || b == null) return;
                scene.clearColorFilter();
                scene.setImageBitmap(b);
                if (!reducedMotion) {
                    scene.setAlpha(0f);
                    scene.animate().alpha(1f).setDuration(500).start();
                }
            });
        });
    }

    private String whereText(WallData.Tile t) {
        if (!t.placed()) return "";
        return getString(ChannelData.STREMIO.equals(t.service) ? R.string.walls_in : R.string.walls_on, t.service);
    }

    private void videoCard(WallData.Tile t) {
        TextView title = caps(t.title, 36, INK);
        title.setMaxLines(3);
        title.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(title);
        if (t.by != null) card.addView(below(text(t.by, 23, plain, INK), 12));
        List<String> meta = new ArrayList<>();
        if (t.group != null) meta.add(t.group);
        meta.add(DateUtils.getRelativeTimeSpanString(t.published, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString());
        if (t.views > 0) meta.add(getString(R.string.walls_views, count(t.views)));
        card.addView(below(text(TextUtils.join(" · ", meta), 23, plain, DIM), 6));
        foot(getString(R.string.walls_on, "YouTube"), getString(R.string.walls_ok_watch));
    }

    private void personCard(WallData.Tile t) {
        card.addView(caps(t.title, 46, INK));
        String best = getString(R.string.walls_best_rated, t.sub.get(0).title);
        card.addView(below(text(t.count > 0 ? getString(R.string.walls_on_wall, t.count) + " · " + best : best, 23, plain, DIM), 10));
        LinearLayout strip = line();
        for (int i = 0; i < Math.min(6, t.sub.size()); i++) {
            WallData.Tile f = t.sub.get(i);
            LinearLayout one = column();
            one.addView(poster(f, 88, 132));
            one.addView(below(caps(String.format(Locale.US, "%.1f", f.rating), 20, GOLD), 4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(88), LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = px(10);
            strip.addView(one, lp);
        }
        card.addView(below(strip, 16));
        foot(getString(R.string.walls_films, t.sub.size()), getString(R.string.walls_ok_films));
    }

    /** The card's last line, held at the bottom: where it plays, and what OK does. */
    private void foot(String left, String right) {
        View gap = new View(this);
        card.addView(gap, new LinearLayout.LayoutParams(1, 0, 1f));
        View rule = new View(this);
        rule.setBackgroundColor(LINE);
        card.addView(rule, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, px(1))));
        LinearLayout row = line();
        where = text(left, 23, semi, INK);
        row.addView(where, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(text(right, 23, plain, DIM));
        card.addView(below(row, 12));
    }

    // ---- the world ----------------------------------------------------------------------------

    private WallData.Country country() {
        if (at >= walls.size()) return null;
        for (WallData.Country c : walls.get(at).countries) if (c.code.equals(map.focused())) return c;
        return null;
    }

    /** The chosen country's best rated films in up to three rows by genre, on the side away from the country. */
    private void fillPanel() {
        WallData.Country c = country();
        panel.removeAllViews();
        if (c == null) return;
        ((FrameLayout.LayoutParams) panel.getLayoutParams()).gravity = map.focusOnLeft() ? Gravity.END : Gravity.START;
        panel.requestLayout();
        String name = map.name(c.code);
        panel.addView(caps(name, name.length() > 14 ? 54 : 76, INK));
        panel.addView(below(text(getString(R.string.walls_country_note, c.films.size()), 23, plain, DIM), 12));
        Map<String, Integer> often = new LinkedHashMap<>();
        for (WallData.Tile t : c.films) for (String g : t.genres) often.merge(g, 1, Integer::sum);
        List<String> order = new ArrayList<>(often.keySet());
        order.remove("Drama");   // nearly every film is one: it goes last, for what the others didn't take
        order.sort((a, b) -> often.get(b) - often.get(a));
        if (often.containsKey("Drama")) order.add("Drama");
        Set<WallData.Tile> used = new HashSet<>();
        int rows = 0;
        for (String g : order) {
            if (rows == 3) break;
            List<WallData.Tile> its = new ArrayList<>();
            for (WallData.Tile t : c.films) if (its.size() < 4 && !used.contains(t) && t.genres.contains(g)) its.add(t);
            if (its.size() < 2) continue;
            used.addAll(its);
            rows++;
            TextView label = text(g.toUpperCase(Locale.ROOT), 22, semi, DIM);
            label.setLetterSpacing(0.08f);
            panel.addView(below(label, 22));
            LinearLayout row = line();
            for (WallData.Tile t : its) {
                LinearLayout one = column();
                one.addView(poster(t, 150, 225));
                SpannableStringBuilder cap = new SpannableStringBuilder(String.format(Locale.US, "%.1f", t.rating));
                cap.setSpan(new ForegroundColorSpan(GOLD), 0, cap.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                cap.append("  ").append(t.title);
                TextView caption = text("", 19, semi, INK);
                caption.setText(cap);
                caption.setMaxLines(2);
                caption.setEllipsize(TextUtils.TruncateAt.END);
                one.addView(below(caption, 8));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(150), LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = px(16);
                row.addView(one, lp);
            }
            panel.addView(below(row, 12));
        }
    }

    // ---- small things -------------------------------------------------------------------------

    private void announce(String name, String count) {
        flash.removeAllViews();
        TextView n = caps(name, 34, INK), c = text(count, 24, plain, DIM);
        n.setShadowLayer(px(9), 0, px(2), 0xE6000000);
        c.setShadowLayer(px(6), 0, px(1), 0xE6000000);
        flash.addView(n);
        flash.addView(after(c, 16));
        flash.animate().cancel();
        flash.setAlpha(1f);
        handler.removeCallbacks(hideFlash);
        handler.postDelayed(hideFlash, 2200);
    }

    private void say(String words) {
        note.setText(words);
        note.animate().cancel();
        note.setAlpha(1f);
        handler.removeCallbacks(hideNote);
        handler.postDelayed(hideNote, 2600);
    }

    private static String count(long n) {
        if (n >= 10_000_000) return n / 1_000_000 + "M";
        if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1_000_000f).replace(".0M", "M");
        if (n >= 1_000) return Math.round(n / 1_000f) + "K";
        return String.valueOf(n);
    }

    private Typeface face(String variation) {
        try {
            return new Typeface.Builder(getAssets(), "archivo.ttf").setFontVariationSettings(variation).build();
        } catch (RuntimeException e) {
            return Typeface.DEFAULT;
        }
    }

    private int px(float v) {
        return Math.round(v * s);
    }

    private FrameLayout.LayoutParams fill() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private FrameLayout.LayoutParams box(float x, float y, float w, float h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(w), Math.max(1, px(h)));
        lp.leftMargin = px(x);
        lp.topMargin = px(y);
        return lp;
    }

    private FrameLayout.LayoutParams spot(float x, float y) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = px(x);
        lp.topMargin = px(y);
        return lp;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout line() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setBaselineAligned(true);
        return l;
    }

    private TextView text(String words, float size, Typeface face, int color) {
        TextView t = new TextView(this);
        t.setText(words);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * s);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** A poster's billing block: condensed, heavy, upper case. */
    private TextView caps(String words, float size, int color) {
        TextView t = text(words, size, billing, color);
        t.setAllCaps(true);
        t.setLetterSpacing(0.012f);
        t.setLineSpacing(0, 0.98f);
        return t;
    }

    private ImageView poster(WallData.Tile t, float w, float h) {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setBackgroundColor(0xFF12181A);
        v.setLayoutParams(new LinearLayout.LayoutParams(px(w), px(h)));
        images.into(v, t);
        return v;
    }

    private <V extends View> V below(V v, float gap) {
        LinearLayout.LayoutParams lp = v.getLayoutParams() instanceof LinearLayout.LayoutParams ? (LinearLayout.LayoutParams) v.getLayoutParams()
                : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(gap);
        v.setLayoutParams(lp);
        return v;
    }

    private <V extends View> V after(V v, float gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = px(gap);
        v.setLayoutParams(lp);
        return v;
    }
}
