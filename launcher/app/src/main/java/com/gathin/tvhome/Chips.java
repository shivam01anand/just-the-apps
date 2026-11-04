package com.gathin.tvhome;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * The watchlist's chips: the personal one, Podcasts, Continue, the collections, genres. The selected one's
 * posters show below. A plain strip in a HorizontalScrollView with every chip always there: as a RecyclerView
 * it laid itself out again from its first chip now and then and dropped the chip focus had just reached past
 * the right edge, so focus fell out of the row to Settings, from where no arrow key led back (seen on the TV).
 */
final class Chips {
    interface Listener {
        void onGroupFocused(int position);

        /** A D-pad key on a chip. Return true when handled (focus stays put). */
        boolean onGroupKey(int position, int keyCode);
    }

    private final LinearLayout row;
    private final Listener listener;

    Chips(LinearLayout row, Listener listener) {
        this.row = row;
        this.listener = listener;
    }

    void setGroups(List<Watchlist.Group> groups) {
        row.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(row.getContext());
        for (int i = 0; i < groups.size(); i++) {
            final int position = i;
            TextView chip = (TextView) inflater.inflate(R.layout.item_group, row, false);
            chip.setText(groups.get(i).label);   // no counts: nobody picks a genre by how many it has
            chip.setOnFocusChangeListener((v, focused) -> {
                if (focused) listener.onGroupFocused(position);
            });
            chip.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                return listener.onGroupKey(position, keyCode);
            });
            row.addView(chip);
        }
    }

    /** Lights the chip whose posters are showing, so it stays lit while focus is on the posters. */
    void setSelected(int position) {
        for (int i = 0; i < row.getChildCount(); i++) row.getChildAt(i).setSelected(i == position);
    }

    /** "Directors  ›  Satyajit Ray" while a director's films are showing; the group's own label otherwise. */
    void setLabel(int position, String label) {
        View v = chip(position);
        if (v instanceof TextView) ((TextView) v).setText(label);
    }

    View chip(int position) {
        return position >= 0 && position < row.getChildCount() ? row.getChildAt(position) : null;
    }
}
