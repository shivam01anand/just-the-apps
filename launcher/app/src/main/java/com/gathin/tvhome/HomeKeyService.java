package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Makes the remote's Home button land on this launcher where the system won't let it be the default home.
 *
 * - Xiaomi sticks and boxes send KEYCODE_ALL_APPS for Home, and Android TV 9 routes that key only to the
 *   system-image launcher, so once the stock launcher is disabled the button does nothing. Here it opens
 *   this launcher instead.
 * - Fire OS always opens Amazon's home screen on Home and offers no way to pick another. Whenever that
 *   screen comes up (Home, leaving an app, after a restart) this launcher is opened over it.
 *
 * Every other key passes through untouched.
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {

    /** Amazon's home screen on Fire OS. Its other screens (search, the app library) are left alone. */
    private static final String FIRE_HOME_PACKAGE = "com.amazon.tv.launcher";
    private static final String FIRE_HOME_ACTIVITY = "com.amazon.tv.launcher.ui.HomeActivity";

    /** Scancodes of remote buttons that should open Settings (config.xml, settings_key_scancodes). */
    private int[] settingsScancodes = new int[0];

    /** When Home was last pressed: Amazon's home appearing straight after it means "go to the top". */
    private long homePressedAt;
    /** Recent redirects. If this launcher keeps failing to stay up, stop for a little while rather than loop. */
    private final long[] redirects = new long[6];
    private int nextRedirect;
    private long pausedUntil;

    /** With profiles: the TV going to sleep means the next person is asked who's watching when it wakes. */
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (!Profiles.enabled(c)) return;
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) Profiles.slept(c);
            else if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) openHome(true);
        }
    };
    private boolean screenRegistered;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screen, f);
        screenRegistered = true;
        // After a restart Amazon's home is already up before this service starts, so its event is missed.
        if (isFireOs() && SystemClock.elapsedRealtime() < 5 * 60_000) openHome(true);
    }

    /**
     * Fire OS's Settings screen. The standard Settings link goes through Amazon's home screen first, which this
     * service would turn straight back into this launcher. Null elsewhere.
     */
    static Intent fireSettings(PackageManager pm) {
        Intent i = new Intent("amazon.intent.action.MAINSETTINGS").setComponent(
                new ComponentName(FIRE_HOME_PACKAGE, FIRE_HOME_PACKAGE + ".ui.MainSettingsActivity"));
        return i.resolveActivity(pm) != null ? i : null;
    }

    private boolean isFireOs() {
        return getPackageManager().hasSystemFeature("amazon.hardware.fire_tv");
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            homePressedAt = SystemClock.uptimeMillis();
            return false;   // the system still handles it (and its long press)
        }
        // The stock launcher handled the remote's Settings button; with it disabled the button does nothing.
        if (isSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                Intent settings = fireSettings(getPackageManager());
                if (settings == null) settings = new Intent(Settings.ACTION_SETTINGS);
                startActivity(settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return true;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) openHome(true);
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null || cls == null || !FIRE_HOME_PACKAGE.contentEquals(pkg)) return;
        if (!cls.toString().startsWith(FIRE_HOME_ACTIVITY)) return;
        long now = SystemClock.uptimeMillis();
        if (now < pausedUntil) return;
        // One appearance of Amazon's home raises several window events (and Fire OS can bring it up twice when the
        // screensaver ends): one redirect covers them all.
        long last = redirects[(nextRedirect + redirects.length - 1) % redirects.length];
        if (last != 0 && now - last < 1_500) return;
        long oldest = redirects[nextRedirect];
        if (oldest != 0 && now - oldest < 10_000) {   // six real redirects in ten seconds: something is wrong
            pausedUntil = now + 15_000;
            return;
        }
        redirects[nextRedirect] = now;
        nextRedirect = (nextRedirect + 1) % redirects.length;
        // Home pressed: back to the top, as Home does. Left an app: return to where you were.
        openHome(now - homePressedAt < 3_000);
    }

    private void openHome(boolean fresh) {
        Intent home = new Intent(Intent.ACTION_MAIN)
                .setClassName(getPackageName(), MainActivity.class.getName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (fresh) home.addCategory(Intent.CATEGORY_HOME);
        startActivity(home);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (screenRegistered) unregisterReceiver(screen);
        screenRegistered = false;
        super.onDestroy();
    }
}
