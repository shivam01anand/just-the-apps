package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.view.View;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screensaver. With screensaver_gallery on and a painting already downloaded (Gallery), it is a gallery: one
 * painting hung on a wash of its own colours for two minutes, then the next fades in. Otherwise it is the
 * launcher's thin clock and date on black over a slow ambient glow, drifting every minute so nothing burns in.
 */
public class ClockDream extends DreamService {

    private static final long MOVE_MS = 60_000L;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private View content;
    private GradientDrawable glow;
    private int colorIndex;
    private int glowColor;

    /** How long each painting hangs. Nothing is redrawn in between. */
    private static final long ART_MS = 2 * 60_000L;
    private boolean gallery;
    private ImageView artFront, artBack, wash;
    private TextView artTitle, artArtist, artWeather;
    private View caption, frame;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Runnable nextArt = new Runnable() {
        @Override
        public void run() {
            Gallery.Art a = Gallery.take(ClockDream.this);
            if (a != null) showArt(a);
            Gallery.prepare(ClockDream.this);
            handler.postDelayed(this, ART_MS);
        }
    };

    /** Left/Right change what hangs (Gallery modes); this swaps in a matching painting as soon as one is on the TV. */
    private boolean switchable;
    private int swapTries;
    private TextView modeLabel;
    private final Runnable swapSoon = new Runnable() {
        @Override
        public void run() {
            Gallery.Art a = Gallery.take(ClockDream.this);
            Gallery.prepare(ClockDream.this);
            if (a != null) {
                showArt(a);
                handler.postDelayed(nextArt, ART_MS);
            } else if (++swapTries < 12) {
                handler.postDelayed(this, 1500);
            } else {
                handler.postDelayed(nextArt, ART_MS);
            }
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            moveClock();
            shiftGlow();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        Gallery.Art first = Gallery.enabled(this) ? Gallery.take(this) : null;
        Gallery.prepare(this);   // the next one, or the first, so the gallery is ready next time
        if (first != null) {
            startGallery(first);
            // With the household's own paintings listed, Left/Right choose what hangs; any other key still wakes.
            switchable = Gallery.hasOwn(this);
            setInteractive(switchable);
            return;
        }
        setContentView(R.layout.dream_clock);

        content = findViewById(R.id.dreamContent);
        View ambient = findViewById(R.id.dreamAmbient);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        TextView weather = findViewById(R.id.dreamWeather);
        Runnable showWeather = () -> {
            String w = Weather.saved(this);
            weather.setText(w != null ? w : "");
            weather.setVisibility(w != null ? View.VISIBLE : View.GONE);
        };
        showWeather.run();
        Weather.refreshIfStale(this, showWeather);
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (gallery) {
            handler.postDelayed(nextArt, ART_MS);
            return;
        }
        content.setAlpha(0f);
        content.post(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        handler.postDelayed(tick, MOVE_MS);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (!switchable) return super.dispatchKeyEvent(e);
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(e);
        }
        if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                String was = Gallery.mode(this);
                String now = Gallery.step(this, k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                showMode(now);
                if (!now.equals(was)) {
                    handler.removeCallbacks(nextArt);
                    handler.removeCallbacks(swapSoon);
                    swapTries = 0;
                    Gallery.prepare(this);
                    handler.postDelayed(swapSoon, 600);
                }
            }
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_UP) finish();   // any other key wakes the TV, as before
        return true;
    }

    /** "The masters · Mix · Dolly Prasad", the one in use bright, fading away after a moment. */
    private void showMode(String mode) {
        if (modeLabel == null) {
            modeLabel = new TextView(this);
            modeLabel.setTypeface(getResources().getFont(R.font.outfit));
            modeLabel.setTextSize(15);
            modeLabel.setLetterSpacing(0.06f);
            modeLabel.setAlpha(0f);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = Math.round(30 * getResources().getDisplayMetrics().density);
            ((FrameLayout) frame.getParent()).addView(modeLabel, lp);
        }
        String[] names = {getString(R.string.gallery_masters), getString(R.string.gallery_mix), Gallery.ownArtist(this)};
        String[] modes = {Gallery.MASTERS, Gallery.MIX, Gallery.OWN};
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        for (int i = 0; i < names.length; i++) {
            if (i > 0) sb.append("   \u00b7   ");
            int start = sb.length();
            sb.append(names[i]);
            int color = modes[i].equals(mode) ? 0xF2FFFFFF : 0x73FFFFFF;
            sb.setSpan(new android.text.style.ForegroundColorSpan(color), start, sb.length(), 0);
        }
        modeLabel.setText(sb);
        modeLabel.animate().cancel();
        modeLabel.animate().alpha(1f).setDuration(200).withEndAction(() ->
                modeLabel.animate().alpha(0f).setStartDelay(2200).setDuration(500).start()).start();
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(nextArt);
        handler.removeCallbacks(swapSoon);
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(nextArt);
        handler.removeCallbacks(swapSoon);
        decoder.shutdownNow();
        super.onDetachedFromWindow();
    }

    // ---- gallery ---------------------------------------------------------------------------------

    private void startGallery(Gallery.Art first) {
        gallery = true;
        setContentView(R.layout.dream_gallery);
        artFront = findViewById(R.id.galleryA);
        artBack = findViewById(R.id.galleryB);
        wash = findViewById(R.id.galleryWash);
        frame = findViewById(R.id.galleryFrame);
        caption = findViewById(R.id.galleryCaption);
        artTitle = findViewById(R.id.galleryTitle);
        artArtist = findViewById(R.id.galleryArtist);
        artWeather = findViewById(R.id.galleryWeather);
        BitmapDrawable scrim = new BitmapDrawable(getResources(), Backdrop.scrim());
        scrim.setFilterBitmap(true);
        findViewById(R.id.galleryScrim).setBackground(scrim);
        Runnable line = () -> {
            String w = Weather.saved(this);
            String day = new SimpleDateFormat("EEE d MMM", Locale.ENGLISH).format(new Date());
            artWeather.setText(w != null ? day + "  ·  " + w : day);
        };
        line.run();
        Weather.refreshIfStale(this, line);
        frame.post(() -> showArt(first));
    }

    /** Decodes off the main thread, then fades the painting in over the last, with its wash and caption. */
    private void showArt(Gallery.Art a) {
        final File f = Gallery.file(this, a);
        decoder.execute(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            o.inJustDecodeBounds = false;
            o.inSampleSize = Math.max(1, Math.max(o.outWidth, o.outHeight) / 2048);
            o.inPreferredConfig = Bitmap.Config.RGB_565;   // half the memory; paintings have no transparency
            Bitmap art;
            try {
                art = BitmapFactory.decodeFile(f.getPath(), o);
            } catch (Exception | OutOfMemoryError e) {
                return;
            }
            if (art == null) return;
            Bitmap colours = Backdrop.blurred(art);
            handler.post(() -> hang(a, art, colours));
        });
    }

    private void hang(Gallery.Art a, Bitmap art, Bitmap colours) {
        int fw = frame.getWidth() - frame.getPaddingLeft() - frame.getPaddingRight();
        int fh = frame.getHeight() - frame.getPaddingTop() - frame.getPaddingBottom();
        if (fw <= 0 || fh <= 0) return;
        float s = Math.min(fw / (float) art.getWidth(), fh / (float) art.getHeight());
        ImageView in = artBack, out = artFront;
        in.setLayoutParams(new FrameLayout.LayoutParams(Math.round(art.getWidth() * s), Math.round(art.getHeight() * s), Gravity.CENTER));
        BitmapDrawable d = new BitmapDrawable(getResources(), art);
        d.setFilterBitmap(true);
        in.setImageDrawable(d);
        in.setAlpha(0f);
        in.setScaleX(1.02f);
        in.setScaleY(1.02f);
        in.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(1800).start();
        out.animate().alpha(0f).setDuration(1400).withEndAction(() -> out.setImageDrawable(null)).start();
        artFront = in;
        artBack = out;
        wash.animate().alpha(0f).setDuration(700).withEndAction(() -> {
            BitmapDrawable w = new BitmapDrawable(getResources(), colours);
            w.setFilterBitmap(true);
            wash.setImageDrawable(w);
            wash.animate().alpha(0.55f).setDuration(1400).start();
        }).start();
        caption.animate().alpha(0f).setDuration(500).withEndAction(() -> {
            artTitle.setText(a.title);
            artArtist.setText(a.artist + (a.when.isEmpty() ? "" : ", " + a.when));
            caption.animate().alpha(1f).setDuration(900).start();
        }).start();
    }

    private void moveClock() {
        content.animate().alpha(0f).setDuration(900).withEndAction(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        }).start();
    }

    /** A random spot that keeps the whole clock on screen, with the glow centred behind it. */
    private void placeClock() {
        View parent = (View) content.getParent();
        int maxX = Math.max(0, parent.getWidth() - content.getWidth());
        int maxY = Math.max(0, parent.getHeight() - content.getHeight());
        float x = maxX * (0.1f + 0.8f * random.nextFloat());
        float y = maxY * (0.1f + 0.8f * random.nextFloat());
        content.setX(x);
        content.setY(y);
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setGradientCenter((x + content.getWidth() / 2f) / parent.getWidth(),
                    (y + content.getHeight() / 2f) / parent.getHeight());
        }
    }

    private void shiftGlow() {
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), from, PALETTE[colorIndex]);
        anim.setDuration(8000);
        anim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        anim.start();
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | c, 0x14000000 | c, 0x00000000 | c});
    }
}
