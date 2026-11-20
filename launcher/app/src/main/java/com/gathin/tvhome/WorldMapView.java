package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import androidx.core.graphics.PathParser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The world, for choosing a country with the remote. Countries that have films are lighter and carry a dot (small
 * ones would be lost without it); the one chosen is lit. Left, Right, Up and Down go to the nearest country that way.
 *
 * The outlines are Natural Earth's (public domain), drawn for a 1920 px wide screen in assets/world.json.
 */
final class WorldMapView extends View {

    private static final int LAND = 0xFF12191B, HAS = 0xFF33403F, INK = 0xFFF2F0EA, DOT = 0xFF8A9695, EDGE = 0xFF070A0B;
    /** How far down the 1080 px screen the map starts. */
    private static final float TOP = 122;

    private final Map<String, Path> lands = new LinkedHashMap<>();
    private final Map<String, float[]> centres = new LinkedHashMap<>();
    private final Map<String, String> names = new LinkedHashMap<>();
    private Path rest;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Set<String> has = new java.util.HashSet<>();
    private String focus;

    WorldMapView(Context c) {
        super(c);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(0.8f);
        edge.setColor(EDGE);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.5f);
        ring.setColor(INK);
        JSONObject w = WallData.world(c);
        JSONObject paths = w.optJSONObject("paths"), cent = w.optJSONObject("cent"), nm = w.optJSONObject("names");
        try {
            rest = PathParser.createPathFromPathData(w.optString("rest", "M0,0"));
            for (Iterator<String> it = paths != null ? paths.keys() : null; it != null && it.hasNext(); ) {
                String code = it.next();
                lands.put(code, PathParser.createPathFromPathData(paths.optString(code)));
            }
        } catch (RuntimeException e) {
            rest = new Path();
        }
        for (Iterator<String> it = cent != null ? cent.keys() : null; it != null && it.hasNext(); ) {
            String code = it.next();
            JSONArray xy = cent.optJSONArray(code);
            if (xy != null) centres.put(code, new float[]{(float) xy.optDouble(0), (float) xy.optDouble(1)});
            names.put(code, nm != null ? nm.optString(code, code) : code);
        }
    }

    /** The countries that have films to show. */
    void countries(Set<String> codes) {
        has = codes;
        if (focus == null || !has.contains(focus)) focus = has.contains("IN") ? "IN" : has.isEmpty() ? null : has.iterator().next();
        invalidate();
    }

    String focused() {
        return focus;
    }

    void focus(String code) {
        if (has.contains(code)) focus = code;
        invalidate();
    }

    String name(String code) {
        String n = names.get(code);
        return n != null ? n : code;
    }

    /** Whether the chosen country is on the left half, so its films can open on the right. */
    boolean focusOnLeft() {
        float[] c = centres.get(focus);
        return c == null || c[0] < WallView.W / 2;
    }

    /** Goes to the nearest country in that direction; false when there is none. */
    boolean move(int dx, int dy) {
        float[] from = centres.get(focus);
        if (from == null) return false;
        String best = null;
        float bestScore = Float.MAX_VALUE;
        for (String code : has) {
            float[] to = centres.get(code);
            if (to == null || code.equals(focus)) continue;
            float ex = to[0] - from[0], ey = to[1] - from[1];
            float along = dx != 0 ? ex * dx : ey * dy, across = dx != 0 ? Math.abs(ey) : Math.abs(ex);
            if (along <= 3 || across > along * 2.2f) continue;
            float score = along + across * 2.4f;
            if (score < bestScore) {
                bestScore = score;
                best = code;
            }
        }
        if (best == null) return false;
        focus = best;
        invalidate();
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float s = getWidth() / WallView.W;
        canvas.save();
        canvas.scale(s, s);
        canvas.translate(0, TOP);
        fill.setColor(LAND);
        canvas.drawPath(rest, fill);
        canvas.drawPath(rest, edge);
        for (Map.Entry<String, Path> e : lands.entrySet()) {
            boolean lit = e.getKey().equals(focus);
            fill.setColor(lit ? INK : has.contains(e.getKey()) ? HAS : LAND);
            canvas.drawPath(e.getValue(), fill);
            canvas.drawPath(e.getValue(), edge);
        }
        for (String code : has) {
            float[] c = centres.get(code);
            if (c == null) continue;
            boolean lit = code.equals(focus);
            fill.setColor(lit ? INK : DOT);
            canvas.drawCircle(c[0], c[1], lit ? 5 : 4, fill);
            if (lit) {
                canvas.drawCircle(c[0], c[1], 5, edge);
                canvas.drawCircle(c[0], c[1], 20, ring);
            }
        }
        canvas.restore();
    }
}
