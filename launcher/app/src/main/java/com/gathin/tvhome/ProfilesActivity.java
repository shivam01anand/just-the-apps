package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Who's watching?": one picture for each person in config.json's "profiles", in a row in the middle of a quiet
 * screen, with their name under it. The one the remote is on is bigger, brighter and ringed, and says what its
 * home is like; the others wait, dimmed. The picture is one the person chose ("card_image", an address of one), a
 * still from one of their films ("card_still"), or, for someone with walls, a few of their posters; "card_focus"
 * (0 to 1, left to right) says which part of a wide picture to keep. The one who watched last is already chosen,
 * so for them it's a single press of OK. Shown when the TV wakes or restarts, and from Menu at any time.
 */
public class ProfilesActivity extends Activity {

    /** Starts at once and settles gently, as on the home it opens. */
    private static final Interpolator SETTLE = new PathInterpolator(0.2f, 0f, 0f, 1f);
    /** A picture's size on a 1920 px wide screen, the space between two, and how the chosen one grows. */
    private static final float TILE_W = 330, TILE_H = 412, GAP = 72, GROW = 1.09f, WAITING = 0.42f;

    private final List<FrameLayout> tiles = new ArrayList<>();
    private final List<View> rings = new ArrayList<>(), taglines = new ArrayList<>();
    private final List<TextView> names = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Every picture loads at once, so none waits behind a slower one. */
    private final ExecutorService loader = Executors.newFixedThreadPool(3);
    private List<Profiles.Person> people;
    private int at;
    private float s = 1, tileW;
    private boolean asked, reducedMotion;
    private Sounds sounds;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        people = Profiles.all(this);
        if (people.size() < 2) {
            finish();
            return;
        }
        s = getResources().getDisplayMetrics().widthPixels / 1920f;
        asked = Profiles.shouldAsk(this);
        reducedMotion = Config.load(this).reducedMotion;
        sounds = new Sounds(this);
        at = Profiles.activeIndex(this, people);
        // Up to four fit at full size; more than that and they all get a little smaller.
        tileW = Math.min(TILE_W, (1920 - 2 * 140 - (people.size() - 1) * GAP) / people.size());

        FrameLayout root = new FrameLayout(this);
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setColors(new int[]{0xFF211D19, getColor(R.color.night)});
        glow.setGradientCenter(0.5f, 0.42f);
        glow.setGradientRadius(px(900));
        root.setBackground(glow);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setClipChildren(false);
        root.setClipChildren(false);
        column.addView(text(getString(R.string.profiles_ask), 50, getResources().getFont(R.font.mukta_medium), getColor(R.color.ivory)),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);   // the chosen picture grows past the row
        for (int i = 0; i < people.size(); i++) row.addView(person(people.get(i), i));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.topMargin = px(84);
        column.addView(row, rp);

        LinearLayout hint = new LinearLayout(this);
        hint.setGravity(Gravity.CENTER_VERTICAL);
        TextView ok = text("OK", 20, getResources().getFont(R.font.mukta_bold), getColor(R.color.brass_ink));
        ok.setGravity(Gravity.CENTER);
        ok.setLetterSpacing(0.04f);
        ok.setBackgroundResource(R.drawable.ok_circle);
        hint.addView(ok, new LinearLayout.LayoutParams(px(48), px(48)));
        TextView choose = text(getString(R.string.profiles_choose), 27, getResources().getFont(R.font.mukta_medium), getColor(R.color.ivory_dim));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.setMarginStart(px(16));
        hint.addView(choose, cp);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hp.topMargin = px(64);
        column.addView(hint, hp);

        root.addView(column, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
        focus(false);
    }

    /** One person: their picture, their name under it, and a line about their home. */
    private View person(Profiles.Person p, int index) {
        float tileH = tileW * TILE_H / TILE_W;
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setClipChildren(false);

        FrameLayout tile = new FrameLayout(this);
        tile.setClipToOutline(true);
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), px(18));
            }
        });
        tile.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{lighter(p.color), p.color}));
        ImageView picture = new ImageView(this);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        tile.addView(picture, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        View ring = new View(this);
        GradientDrawable stroke = new GradientDrawable();
        stroke.setCornerRadius(px(18));
        stroke.setStroke(Math.max(2, px(5)), getColor(R.color.ivory));
        ring.setBackground(stroke);
        tile.addView(ring, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        column.addView(tile, new LinearLayout.LayoutParams(px(tileW), px(tileH)));

        TextView name = text(p.name, 46, getResources().getFont(R.font.mukta_semibold), getColor(R.color.ivory));
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(1);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        np.topMargin = px(52);   // room for the chosen picture to grow
        column.addView(name, np);
        TextView tagline = text(p.tagline, 25, getResources().getFont(R.font.mukta_medium), getColor(R.color.ivory_dim));
        tagline.setGravity(Gravity.CENTER);
        tagline.setMaxLines(1);
        column.addView(tagline, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(tileW + GAP), LinearLayout.LayoutParams.WRAP_CONTENT);
        column.setLayoutParams(lp);
        tiles.add(tile);
        rings.add(ring);
        names.add(name);
        taglines.add(tagline);
        final float shape = TILE_W / TILE_H, focusX = (float) p.json.optDouble("card_focus", 0.5);
        loader.execute(() -> {
            final Bitmap b = crop(picture(p), shape, focusX);
            if (b != null) handler.post(() -> picture.setImageBitmap(b));
        });
        return column;
    }

    /** Cuts a picture to the tile's shape, keeping the part around focusX (0 left, 1 right) of a wide one. */
    private static Bitmap crop(Bitmap b, float shape, float focusX) {
        if (b == null) return null;
        float has = (float) b.getWidth() / b.getHeight();
        if (has <= shape) return b;   // tall enough already: the view crops top and bottom evenly
        int w = Math.round(b.getHeight() * shape);
        int x = Math.max(0, Math.min(b.getWidth() - w, Math.round(b.getWidth() * focusX - w / 2f)));
        return Bitmap.createBitmap(b, x, 0, w, b.getHeight());
    }

    /** The person's own picture, a still from one of their films, or their posters. */
    private Bitmap picture(Profiles.Person p) {
        try {
            File dir = ChannelData.dir(this, p.id);
            String address = p.json.optString("card_image", "");
            if (address.startsWith("http")) {
                File f = new File(dir, "card.jpg");
                // A new address is a new picture: the file remembers which address it came from.
                File from = new File(dir, "card.txt");
                String had = from.isFile() ? new String(java.nio.file.Files.readAllBytes(from.toPath())) : "";
                if (!f.isFile() || !had.equals(address)) {
                    if (WallImages.fetch(address, f)) java.nio.file.Files.write(from.toPath(), address.getBytes());
                }
                Bitmap b = decode(f);
                if (b != null) return b;
            }
            if (!p.cardStill.isEmpty()) {
                Bitmap b = decode(ChannelData.still(this, p.id, p.cardStill));
                if (b != null) return b;
            }
            if (!p.walls()) return null;
            // Putting the posters together takes a few seconds, so the picture is kept and made afresh once a day.
            File kept = new File(dir, "card-posters.jpg");
            if (kept.isFile() && System.currentTimeMillis() - kept.lastModified() < 24L * 60 * 60 * 1000) {
                Bitmap b = decode(kept);
                if (b != null) return b;
            }
            Bitmap b = posters(new File(dir, "posters"));
            if (b != null) {
                try (java.io.OutputStream out = new java.io.FileOutputStream(kept)) {
                    b.compress(Bitmap.CompressFormat.JPEG, 88, out);
                }
            }
            return b;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static Bitmap decode(File f) {
        if (!f.isFile()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 1300) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** A piece of the person's wall: the first nine posters it downloaded, three across. */
    private static Bitmap posters(File dir) {
        File[] files = dir.listFiles((d, name) -> name.startsWith("tt") && name.endsWith(".jpg"));
        if (files == null || files.length < 8) return null;
        final java.util.Map<File, Long> when = new java.util.HashMap<>();
        for (File f : files) when.put(f, f.lastModified());
        Arrays.sort(files, (a, b) -> Long.compare(when.get(a), when.get(b)));
        int cols = 3, rows = 3, w = 200, h = 300;
        Bitmap out = Bitmap.createBitmap(cols * w, rows * h, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 2;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        for (int i = 0; i < cols * rows; i++) {
            Bitmap b = BitmapFactory.decodeFile(files[i].getPath(), o);
            if (b == null) continue;
            int x = (i % cols) * w, y = (i / cols) * h;
            canvas.drawBitmap(b, null, new Rect(x + 2, y + 2, x + w - 2, y + h - 2), paint);
            b.recycle();
        }
        return out;
    }

    /** Shows which person the remote is on: bigger, brighter, ringed, with the line about their home. */
    private void focus(boolean animate) {
        for (int i = 0; i < tiles.size(); i++) {
            boolean on = i == at;
            View tile = tiles.get(i), ring = rings.get(i), tagline = taglines.get(i);
            TextView name = names.get(i);
            float size = on ? GROW : 1f, light = on ? 1f : WAITING;
            name.setTextColor(getColor(on ? R.color.ivory : R.color.ivory_faint));
            if (animate && !reducedMotion) {
                tile.animate().scaleX(size).scaleY(size).alpha(light).setDuration(260).setInterpolator(SETTLE).start();
                ring.animate().alpha(on ? 1f : 0f).setDuration(on ? 160 : 260).start();
                tagline.animate().alpha(on ? 1f : 0f).setDuration(260).start();
            } else {
                tile.setScaleX(size);
                tile.setScaleY(size);
                tile.setAlpha(light);
                ring.setAlpha(on ? 1f : 0f);
                tagline.setAlpha(on ? 1f : 0f);
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int by = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                int next = Math.max(0, Math.min(people.size() - 1, at + by));
                if (next == at) {
                    sounds.edge();
                } else {
                    sounds.move(e.getKeyCode());
                    at = next;
                    focus(true);
                }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (e.getRepeatCount() == 0) choose();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!asked) {   // from Menu or Back: back to whoever was watching. On waking it waits for a choice.
                    sounds.back();
                    finish();
                }
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void choose() {
        sounds.select();
        Profiles.Person p = people.get(at);
        Profiles.choose(this, p);
        Profiles.open(this, p, true);
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sounds != null) sounds.refresh(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        loader.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        if (sounds != null) sounds.release();
    }

    private int px(float v) {
        return Math.round(v * s);
    }

    private TextView text(String words, float size, android.graphics.Typeface face, int color) {
        TextView t = new TextView(this);
        t.setText(words);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * s);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private static int lighter(int c) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(c, hsv);
        hsv[2] = Math.min(1f, hsv[2] * 1.45f + 0.04f);
        return android.graphics.Color.HSVToColor(hsv);
    }
}
