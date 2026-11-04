package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;

import java.io.File;

/**
 * Sounds for moving around Home. "android" (the default) is Android TV's own set, read from the TV's system
 * folder rather than shipped here: a scroll sound per direction, a select, and its error at the end of a row,
 * the same family as the TV's menus. "wood" (res/raw, synthesised) is a small dry knock, used when chosen or
 * when the TV has no such folder. They play on the system-sound stream at its volume, and only while the TV's
 * system sounds are on. Home moves focus itself, so Android's own click, from its focus search, never plays.
 */
final class Sounds {
    private static final String UI = "/system/media/audio/ui/android_TV_";
    private final SoundPool pool;
    private final int left, right, up, down, select, back, edge;
    private final float edgeLevel;
    private float volume;
    private boolean on;
    private long lastMove;

    Sounds(Context c) {
        pool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        boolean system = !Config.load(c).navigationSoundSet.equals("wood") && new File(UI + "select.ogg").isFile();
        if (system) {
            left = pool.load(UI + "scroll_left.ogg", 1);
            right = pool.load(UI + "scroll_right.ogg", 1);
            up = pool.load(UI + "scroll_up.ogg", 1);
            down = pool.load(UI + "scroll_down.ogg", 1);
            select = pool.load(UI + "select.ogg", 1);
            back = left;
            edge = pool.load(UI + "error.ogg", 1);
            edgeLevel = 0.45f;   // an error tone at full level is a reprimand, not a hint
        } else {
            left = right = up = down = back = pool.load(c, R.raw.wood_move, 1);
            select = pool.load(c, R.raw.wood_select, 1);
            edge = pool.load(c, R.raw.wood_edge, 1);
            edgeLevel = 0.9f;
        }
        refresh(c);
    }

    /** Follows the config and the TV's own system-sounds switch; called whenever Home comes back. */
    void refresh(Context c) {
        Config cfg = Config.load(c);
        on = cfg.navigationSounds
                && Settings.System.getInt(c.getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 1) == 1;
        volume = cfg.navigationSoundVolume / 100f;
    }

    /** Focus moved with this arrow. Holding one repeats fast: a sound per 45 ms at most, so it ripples. */
    void move(int keyCode) {
        long now = SystemClock.uptimeMillis();
        if (now - lastMove < 45) return;
        lastMove = now;
        int id = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? left : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? right
                : keyCode == KeyEvent.KEYCODE_DPAD_UP ? up : down;
        play(id, 0.8f);
    }

    void select() {
        play(select, 0.8f);
    }

    void back() {
        play(back, 0.8f);
    }

    void edge() {
        play(edge, edgeLevel);
    }

    private void play(int id, float level) {
        if (on) pool.play(id, volume * level, volume * level, 1, 0, 1f);
    }

    void release() {
        pool.release();
    }
}
