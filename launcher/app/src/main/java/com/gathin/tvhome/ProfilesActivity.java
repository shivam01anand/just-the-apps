package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
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

import java.util.ArrayList;
import java.util.List;

/**
 * "Who's watching?": a card for each person in config.json's "profiles". The one who watched last is already
 * chosen, so for them it's a single press of OK. Shown when the TV wakes or restarts, and from Menu at any time.
 */
public class ProfilesActivity extends Activity {

    /** Starts at once and settles gently, as on the home it opens. */
    private static final Interpolator SETTLE = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private final List<View> cards = new ArrayList<>();
    private final List<View> rings = new ArrayList<>();
    private List<Profiles.Person> people;
    private int at;
    private float density;
    private boolean asked;
    private Sounds sounds;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        people = Profiles.all(this);
        if (people.size() < 2) {
            finish();
            return;
        }
        density = getResources().getDisplayMetrics().density;
        asked = Profiles.shouldAsk(this);
        sounds = new Sounds(this);
        at = Profiles.activeIndex(this, people);

        FrameLayout root = new FrameLayout(this);
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setColors(new int[]{0xFF3A2C1E, getColor(R.color.night)});
        glow.setGradientCenter(0.5f, 0f);
        glow.setGradientRadius(dp(560));
        root.setBackground(glow);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView ask = text(getString(R.string.profiles_ask), 26, R.font.mukta_medium, getColor(R.color.ivory_dim));
        column.addView(ask, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(32);
        for (Profiles.Person p : people) row.addView(card(p));
        // The chosen card grows a little; let it draw past the row instead of being cut off.
        row.setClipChildren(false);
        column.setClipChildren(false);
        root.setClipChildren(false);
        column.addView(row, rp);

        LinearLayout hint = new LinearLayout(this);
        hint.setGravity(Gravity.CENTER_VERTICAL);
        TextView ok = text("OK", 10, R.font.mukta_bold, getColor(R.color.brass_ink));
        ok.setGravity(Gravity.CENTER);
        ok.setLetterSpacing(0.04f);
        ok.setBackgroundResource(R.drawable.ok_circle);
        hint.addView(ok, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView choose = text(getString(R.string.profiles_choose), 14, R.font.mukta_medium, getColor(R.color.ivory_dim));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.setMarginStart(dp(8));
        hint.addView(choose, cp);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(30);
        column.addView(hint, hp);

        root.addView(column, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
        focus(false);
    }

    private View card(Profiles.Person p) {
        FrameLayout card = new FrameLayout(this);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(9));
            }
        });
        GradientDrawable field = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{lighter(p.color), p.color, 0xFF120E0B});
        card.setBackground(field);
        Bitmap still = p.cardStill.isEmpty() ? null : BitmapFactory.decodeFile(ChannelData.still(this, p.id, p.cardStill).getPath(), sampled());
        if (still != null) {
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageBitmap(still);
            ColorMatrix gray = new ColorMatrix();
            gray.setSaturation(0f);
            ColorMatrix warm = new ColorMatrix(new float[]{1.08f, 0, 0, 0, 6, 0, 1f, 0, 0, 2, 0, 0, 0.86f, 0, -4, 0, 0, 0, 1, 0});
            gray.postConcat(warm);
            img.setColorFilter(new ColorMatrixColorFilter(gray));
            card.addView(img, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0x000A0705, 0xD90A0705}));
        card.addView(shade, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setClipChildren(false);   // room for the shadows below
        // The same shadows as the home: wide and soft under the name, close under the small line, for a bright still.
        TextView name = text(p.name, 42, R.font.rozha_one, getColor(R.color.ivory));
        name.setShadowLayer(dp(8), 0, dp(2), 0x99000000);
        words.addView(name);
        if (!p.tagline.isEmpty()) {
            TextView tagline = text(p.tagline, 12, R.font.mukta_medium, getColor(R.color.ivory_dim));
            tagline.setShadowLayer(dp(3), 0, dp(1), 0xB3000000);
            words.addView(tagline);
        }
        FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        wp.setMargins(dp(16), 0, dp(16), dp(16));
        card.addView(words, wp);

        // A hairline of light around every card, so the one not chosen still has an edge against the dark.
        View edge = new View(this);
        GradientDrawable hairline = new GradientDrawable();
        hairline.setCornerRadius(dp(9));
        hairline.setStroke(dp(1), 0x1FF3EBDD);
        edge.setBackground(hairline);
        card.addView(edge, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // The brass ring, with a hairline of shade inside it so it keeps its edge against a bright still.
        View ring = new View(this);
        GradientDrawable stroke = new GradientDrawable();
        stroke.setCornerRadius(dp(9));
        stroke.setStroke(dp(2.5f), getColor(R.color.brass));
        GradientDrawable inner = new GradientDrawable();
        inner.setCornerRadius(dp(6.5f));
        inner.setStroke(dp(1), 0x59000000);
        LayerDrawable both = new LayerDrawable(new Drawable[]{inner, stroke});
        both.setLayerInset(0, dp(2.5f), dp(2.5f), dp(2.5f), dp(2.5f));
        ring.setBackground(both);
        ring.setAlpha(0f);
        card.addView(ring, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        rings.add(ring);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(180), dp(240));
        lp.setMargins(dp(14), 0, dp(14), 0);
        card.setLayoutParams(lp);
        cards.add(card);
        return card;
    }

    private void focus(boolean animate) {
        for (int i = 0; i < cards.size(); i++) {
            View c = cards.get(i);
            boolean on = i == at;
            View ring = rings.get(i);
            float s = on ? 1.05f : 1f, a = on ? 1f : 0.6f;
            if (animate) {
                c.animate().scaleX(s).scaleY(s).alpha(a).setDuration(280).setInterpolator(SETTLE).start();
                ring.animate().alpha(on ? 1f : 0f).setDuration(on ? 180 : 280).start();
            } else {
                c.setScaleX(s);
                c.setScaleY(s);
                c.setAlpha(a);
                ring.setAlpha(on ? 1f : 0f);
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
        if (sounds != null) sounds.release();
    }

    private TextView text(String s, float sp, int font, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(getResources().getFont(font));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private static BitmapFactory.Options sampled() {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 2;
        return o;
    }

    private static int lighter(int c) {
        int r = Math.min(255, ((c >> 16) & 0xFF) + 40), g = Math.min(255, ((c >> 8) & 0xFF) + 30), b = Math.min(255, (c & 0xFF) + 30);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
