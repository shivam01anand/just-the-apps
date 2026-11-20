package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.PathInterpolator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A wall of posters that fills the TV. The wall is bigger than the screen: moving to a poster slides the wall so
 * that poster sits near the middle. Stay on one for a few seconds and it comes off the wall at a readable size,
 * the wall dims behind it, and the screen that owns this view opens a card beside it (or shows something of its
 * own over the dimmed wall). Directors' faces carry their names and videos their titles.
 *
 * Everything is measured on a 1920 by 1080 screen and scaled to the real one.
 */
final class WallView extends View {

    static final float W = 1920, H = 1080;
    static final int MOVED = 0, EDGE = 1, TOP = 2;
    private static final float GAP = 4, MARGIN = 48;
    private static final int SCREEN = 0xFF070A0B, HOLD = 0xFF12181A, INK = 0xFFF2F0EA;
    /** A pause this long means the viewer may stay: time to look the tile up. This long, and they want to know more. */
    private static final long SETTLE_MS = 700, REST_MS = 3000;

    interface Host {
        /** The viewer has paused on a tile. */
        void onSettle(WallData.Tile t);

        /**
         * The viewer has stayed on a tile. Return true to have it come off the wall at liftRect, with room for a
         * card at cardRight; false and the wall only dims, for the screen to show something of its own.
         */
        boolean onRest(WallData.Tile t);

        /** The viewer moved on. */
        void onLeave();
    }

    private final Paint picture = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint hold = new Paint(), ring = new Paint(Paint.ANTI_ALIAS_FLAG), shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrim = new Paint();
    private final TextPaint nameSmall = new TextPaint(Paint.ANTI_ALIAS_FLAG), nameBig = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            titleSmall = new TextPaint(Paint.ANTI_ALIAS_FLAG), titleBig = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            bySmall = new TextPaint(Paint.ANTI_ALIAS_FLAG), byBig = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Map<String, StaticLayout> labels = new HashMap<>();
    private final RectF r = new RectF(), view = new RectF(), near = new RectF();
    private final Rect src = new Rect();
    private final PathInterpolator settle = new PathInterpolator(0.22f, 0.7f, 0.2f, 1f);

    private WallImages images;
    private Host host;
    private List<WallData.Tile> tiles = new ArrayList<>();
    private WallData.Hang hang = new WallData.Hang();
    private WallData.Tile focus;
    private int cr, cc;
    private float unitW = 180, unitH = 270, liftW = 300, liftH = 450, bigLiftW = 380, bigLiftH = 570, top;
    private boolean faces, videos, lifted = true;
    private float tx, ty, lift, dim;
    private ValueAnimator pan, lifting;
    private boolean reducedMotion;

    /** Where the lifted tile sits, and where its card goes: both on the 1920 by 1080 screen. */
    final RectF liftRect = new RectF();
    boolean cardRight;
    float cardW = 540;

    private final Runnable settled = () -> {
        if (focus != null && host != null) host.onSettle(focus);
    };
    private final Runnable rest = () -> {
        if (focus == null || host == null) return;
        placeLift();
        lifted = host.onRest(focus);
        animateLift(1f);
    };

    WallView(Context c) {
        super(c);
        hold.setColor(HOLD);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(4);
        ring.setColor(INK);
        shadow.setColor(0xFF000000);
        scrim.setShader(new LinearGradient(0, 0, 0, 1, new int[]{0x00050809, 0xB3050809, 0xF2050809}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        for (TextPaint p : new TextPaint[]{nameSmall, nameBig, titleSmall, titleBig}) p.setColor(INK);
        for (TextPaint p : new TextPaint[]{bySmall, byBig}) p.setColor(0xFFAEB4B2);
        nameSmall.setTextSize(32);
        nameBig.setTextSize(56);
        titleSmall.setTextSize(19);
        titleBig.setTextSize(31);
        bySmall.setTextSize(15);
        byBig.setTextSize(21);
        nameSmall.setLetterSpacing(0.012f);
        nameBig.setLetterSpacing(0.012f);
    }

    /** The type for what is written on tiles: directors' names, and videos' titles and channels. */
    void setFaces(Typeface billing, Typeface semi, Typeface plain) {
        nameSmall.setTypeface(billing);
        nameBig.setTypeface(billing);
        titleSmall.setTypeface(semi);
        titleBig.setTypeface(semi);
        bySmall.setTypeface(plain);
        byBig.setTypeface(plain);
    }

    void setUp(WallImages images, Host host, boolean reducedMotion) {
        this.images = images;
        this.host = host;
        this.reducedMotion = reducedMotion;
    }

    /** Hangs a wall. The kind sets the size of a tile and of the one lifted off; "sub" is a short wall under a name. */
    void hang(List<WallData.Tile> list, String kind, int startC, int startR) {
        cancel();
        tiles = list;
        labels.clear();
        faces = WallData.PEOPLE.equals(kind);
        videos = WallData.VIDEO.equals(kind);
        top = 0;
        int rows;
        boolean sizes = true;
        int cells = 0;
        for (WallData.Tile t : list) cells += t.big ? 4 : 1;
        switch (kind) {
            case WallData.VIDEO:
                set(320, 180, 576, 324, 640, 360, 520);
                rows = cells <= 60 ? 6 : 8;
                break;
            case WallData.PEOPLE:
                set(216, 324, 320, 480, 360, 540, 640);
                rows = 4;
                break;
            case "sub":
                set(300, 450, 330, 495, 330, 495, 540);
                rows = 2;
                sizes = false;
                top = 110;
                break;
            default:
                set(180, 270, 300, 450, 380, 570, 540);
                rows = cells <= 48 ? 4 : cells <= 170 ? 6 : 8;
        }
        hang = WallData.hang(list, rows, sizes);
        focus = hang.at(startC, startR);
        if (focus == null && !list.isEmpty()) focus = list.get(0);
        if (focus != null) {
            cr = focus.r;
            cc = focus.c;
            aim(false);
        }
        lift = 0;
        if (host != null) host.onLeave();
        if (!reducedMotion) {
            setAlpha(0f);
            animate().alpha(1f).setDuration(320).start();
        }
        invalidate();
    }

    private void set(float uw, float uh, float lw, float lh, float blw, float blh, float card) {
        unitW = uw;
        unitH = uh;
        liftW = lw;
        liftH = lh;
        bigLiftW = blw;
        bigLiftH = blh;
        cardW = card;
    }

    List<WallData.Tile> tiles() {
        return tiles;
    }

    WallData.Tile focused() {
        return focus;
    }

    /** Moves one tile over. TOP means there was nothing above: the screen's bar of walls is up there. */
    int move(int dx, int dy) {
        if (focus == null) return EDGE;
        int c, row;
        if (dx != 0) {
            c = dx > 0 ? focus.c + focus.w : focus.c - 1;
            row = Math.max(focus.r, Math.min(focus.r + focus.w - 1, cr));
        } else {
            row = dy > 0 ? focus.r + focus.w : focus.r - 1;
            c = Math.max(focus.c, Math.min(focus.c + focus.w - 1, cc));
        }
        if (row < 0) return TOP;
        WallData.Tile t = hang.at(c, row);
        if (t == null && dx != 0) {
            for (int rr : new int[]{row - 1, row + 1, row - 2, row + 2}) {
                if ((t = hang.at(c, rr)) != null) {
                    row = rr;
                    break;
                }
            }
        }
        if (t == null && dy != 0) {
            for (int k : new int[]{c - 1, c + 1}) {
                if ((t = hang.at(k, row)) != null) {
                    c = k;
                    break;
                }
            }
        }
        if (t == null) return EDGE;
        cr = row;
        cc = c;
        focus = t;
        leave();
        aim(true);
        rest(true);
        return MOVED;
    }

    /** Starts (or stops) waiting for the viewer to settle on the tile. */
    void rest(boolean on) {
        removeCallbacks(rest);
        removeCallbacks(settled);
        if (on && focus != null) {
            postDelayed(settled, SETTLE_MS);
            postDelayed(rest, REST_MS);
        } else leave();
    }

    private void leave() {
        removeCallbacks(rest);
        removeCallbacks(settled);
        if (lifting != null) lifting.cancel();
        if (lift != 0) {
            lift = 0;
            invalidate();
        }
        if (host != null) host.onLeave();
    }

    /** Dims the wall a little, for when the bar of walls is open over it. */
    void dim(boolean on) {
        dim = on ? 1 : 0;
        invalidate();
    }

    private void cancel() {
        removeCallbacks(rest);
        removeCallbacks(settled);
        if (pan != null) pan.cancel();
        if (lifting != null) lifting.cancel();
        animate().cancel();
    }

    private void aim(boolean animate) {
        float cx = (focus.c + focus.w / 2f) * unitW, cy = (focus.r + focus.w / 2f) * unitH;
        float wallW = hang.cells.length * unitW, wallH = hang.rows * unitH, room = H - top;
        final float toX = wallW <= W ? (W - wallW) / 2 : Math.max(W - wallW, Math.min(0, 780 - cx));
        final float toY = wallH <= room ? top + (room - wallH) / 2 : Math.max(H - wallH, Math.min(top, H / 2 - cy));
        if (pan != null) pan.cancel();
        if (!animate || reducedMotion) {
            tx = toX;
            ty = toY;
            invalidate();
            return;
        }
        final float fromX = tx, fromY = ty;
        pan = ValueAnimator.ofFloat(0f, 1f).setDuration(420);
        pan.setInterpolator(settle);
        pan.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            tx = fromX + (toX - fromX) * f;
            ty = fromY + (toY - fromY) * f;
            invalidate();
        });
        pan.start();
    }

    /** Centres the lifted tile on its place in the wall, kept on screen with room for the card on one side. */
    private void placeLift() {
        boolean big = focus.w == 2;
        float lw = big ? bigLiftW : liftW, lh = big ? bigLiftH : liftH;
        float cx = tx + (focus.c + focus.w / 2f) * unitW, cy = ty + (focus.r + focus.w / 2f) * unitH;
        float lx = Math.max(MARGIN, Math.min(W - MARGIN - lw, cx - lw / 2));
        float ly = Math.max(MARGIN, Math.min(H - MARGIN - lh, cy - lh / 2));
        cardRight = lx + lw + cardW <= W - MARGIN;
        if (!cardRight && lx - cardW < MARGIN) lx = MARGIN + cardW;
        liftRect.set(lx, ly, lx + lw, ly + lh);
    }

    private void animateLift(float to) {
        if (lifting != null) lifting.cancel();
        if (reducedMotion) {
            lift = to;
            invalidate();
            return;
        }
        lifting = ValueAnimator.ofFloat(lift, to).setDuration(300);
        lifting.setInterpolator(settle);
        lifting.addUpdateListener(a -> {
            lift = (float) a.getAnimatedValue();
            invalidate();
        });
        lifting.start();
    }

    /** The tile's own colour, darkened: what its card is painted in. */
    int tone(WallData.Tile t) {
        Bitmap b = images != null ? images.get(t, false) : null;
        if (b == null) return 0xFF1A2224;
        Bitmap tiny = Bitmap.createScaledBitmap(b, 4, 4, true);
        long red = 0, green = 0, blue = 0;
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                int p = tiny.getPixel(x, y);
                red += Color.red(p);
                green += Color.green(p);
                blue += Color.blue(p);
            }
        }
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (red / 16), (int) (green / 16), (int) (blue / 16), hsv);
        hsv[1] = Math.max(0.15f, Math.min(0.6f, hsv[1]));
        hsv[2] = 0.2f;
        return Color.HSVToColor(hsv);
    }

    private void rect(WallData.Tile t, RectF out) {
        out.set(t.c * unitW + GAP / 2, t.r * unitH + GAP / 2, (t.c + t.w) * unitW - GAP / 2, (t.r + t.w) * unitH - GAP / 2);
    }

    private void draw(Canvas canvas, WallData.Tile t, RectF into, boolean large) {
        Bitmap b = images != null ? images.get(t, large) : null;
        if (b == null) {
            canvas.drawRect(into, hold);
            return;
        }
        // Fill the tile, cropping what doesn't fit: evenly, or from under a face's chin.
        float want = into.width() / into.height(), has = (float) b.getWidth() / b.getHeight();
        if (has > want) {
            int w = Math.round(b.getHeight() * want), x = (b.getWidth() - w) / 2;
            src.set(x, 0, x + w, b.getHeight());
        } else {
            int h = Math.round(b.getWidth() / want), y = Math.round((b.getHeight() - h) * (faces ? 0.12f : 0.5f));
            src.set(0, y, b.getWidth(), y + h);
        }
        canvas.drawBitmap(b, src, into, picture);
    }

    /**
     * What a picture alone doesn't say: a director's name over their face, a video's title and channel over its
     * still. Written along the bottom of the tile, over a shade that fades up into the picture.
     */
    private void label(Canvas canvas, WallData.Tile t, RectF on) {
        if (!faces && !videos || t.title == null) return;
        boolean big = t.w == 2;
        float pad = faces ? (big ? 24 : 14) : (big ? 20 : 12), shade = on.height() * (faces ? 0.5f : 0.66f);
        canvas.save();
        canvas.translate(on.left, on.bottom - shade);
        canvas.scale(on.width(), shade);
        canvas.drawRect(0, 0, 1, 1, scrim);
        canvas.restore();
        TextPaint paint = faces ? (big ? nameBig : nameSmall) : (big ? titleBig : titleSmall);
        String key = t.id + (big ? "@2" : "@1");
        StaticLayout l = labels.get(key);
        if (l == null) {
            String words = faces ? t.title.toUpperCase(Locale.ROOT) : t.title;
            int width = Math.round((big ? 2 * unitW : unitW) - GAP - 2 * pad);
            l = StaticLayout.Builder.obtain(words, 0, words.length(), paint, width).setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, faces ? 0.94f : 1.06f).setIncludePad(false).build();
            labels.put(key, l);
        }
        float top = on.bottom - pad - l.getHeight();
        canvas.save();
        canvas.translate(on.left + pad, top);
        l.draw(canvas);
        canvas.restore();
        if (videos && t.by != null) {
            TextPaint by = big ? byBig : bySmall;
            CharSequence who = TextUtils.ellipsize(t.by, by, on.width() - 2 * pad, TextUtils.TruncateAt.END);
            canvas.drawText(who, 0, who.length(), on.left + pad, top - (big ? 9 : 6), by);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(SCREEN);
        if (tiles.isEmpty()) return;
        float s = getWidth() / W;
        canvas.save();
        canvas.scale(s, s);
        canvas.translate(tx, ty);
        view.set(-tx, -ty, -tx + W, -ty + H);
        near.set(view);
        near.inset(-W * 0.4f, -H * 0.4f);
        picture.setAlpha(255);
        for (WallData.Tile t : tiles) {
            if (t == focus) continue;
            rect(t, r);
            if (RectF.intersects(r, view)) {
                draw(canvas, t, r, t.w == 2);
                label(canvas, t, r);
            } else if (images != null && RectF.intersects(r, near)) images.get(t, t.w == 2);   // nearly on screen: start loading
        }
        if (focus != null) {
            rect(focus, r);
            float grow = focus.w == 2 ? 1.035f : 1.07f, gx = r.width() * (grow - 1) / 2, gy = r.height() * (grow - 1) / 2;
            r.inset(-gx, -gy);
            draw(canvas, focus, r, focus.w == 2);
            label(canvas, focus, r);
            canvas.drawRect(r, ring);
        }
        canvas.restore();
        float d = Math.max(lift * (lifted ? 0.68f : 0.9f), dim * 0.38f);
        if (d > 0) canvas.drawColor(Color.argb(Math.round(d * 255), 5, 8, 9));
        if (lift > 0 && lifted && focus != null) {
            canvas.save();
            canvas.scale(s, s);
            float k = 0.72f + 0.28f * lift;
            r.set(liftRect);
            r.inset(r.width() * (1 - k) / 2, r.height() * (1 - k) / 2);
            shadow.setAlpha(Math.round(lift * 255));
            shadow.setShadowLayer(60, 0, 24, Color.argb(Math.round(lift * 190), 0, 0, 0));
            canvas.drawRect(r, shadow);
            picture.setAlpha(Math.round(lift * 255));
            draw(canvas, focus, r, true);
            picture.setAlpha(255);
            canvas.restore();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancel();
    }
}
