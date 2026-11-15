package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
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

    private final List<View> cards = new ArrayList<>();
    private final List<View> rings = new ArrayList<>();
    private List<Profiles.Person> people;
    private int at;
    private float density;
    private boolean asked;

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
        ok.setBackgroundResource(R.drawable.ok_circle);
        hint.addView(ok, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView choose = text(getString(R.string.profiles_choose), 13, R.font.mukta_medium, getColor(R.color.ivory_faint));
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
        words.addView(text(p.name, 42, R.font.rozha_one, getColor(R.color.ivory)));
        if (!p.tagline.isEmpty()) words.addView(text(p.tagline, 12, R.font.mukta_medium, getColor(R.color.ivory_dim)));
        FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        wp.setMargins(dp(15), 0, dp(15), dp(14));
        card.addView(words, wp);

        View ring = new View(this);
        GradientDrawable stroke = new GradientDrawable();
        stroke.setCornerRadius(dp(9));
        stroke.setStroke(dp(2.5f), getColor(R.color.brass));
        ring.setBackground(stroke);
        ring.setVisibility(View.GONE);
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
            rings.get(i).setVisibility(on ? View.VISIBLE : View.GONE);
            float s = on ? 1.07f : 1f;
            if (animate) c.animate().scaleX(s).scaleY(s).alpha(on ? 1f : 0.5f).setDuration(260).start();
            else {
                c.setScaleX(s);
                c.setScaleY(s);
                c.setAlpha(on ? 1f : 0.5f);
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                at = Math.max(0, at - 1);
                focus(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                at = Math.min(people.size() - 1, at + 1);
                focus(true);
                return true;
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
                if (!asked) finish();   // from Menu: back to whoever was watching. On waking it waits for a choice.
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void choose() {
        Profiles.Person p = people.get(at);
        Profiles.choose(this, p);
        Profiles.open(this, p, true);
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
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
