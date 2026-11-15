package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Sets what the screensaver hangs from a computer over adb (tools/own-paintings.sh), the same setting Left/Right
 * changes on the screensaver itself. Only senders holding android.permission.DUMP (adb's shell) can reach it.
 *
 *   am broadcast -n com.gathin.tvhome/.GalleryModeReceiver -a com.gathin.tvhome.GALLERY_MODE --es mode mix
 *
 * mode: masters, mix or own; "status" changes nothing. The result data is the mode now in use.
 */
public class GalleryModeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String m = i.getStringExtra("mode");
        if (m != null && !"status".equals(m)) {
            Gallery.setMode(c, m);
            Gallery.prepare(c);
        }
        setResultData(Gallery.mode(c));
    }
}
