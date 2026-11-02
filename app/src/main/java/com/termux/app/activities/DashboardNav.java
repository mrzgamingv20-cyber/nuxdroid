package com.termux.app.activities;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.MenuItem;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.termux.R;

/**
 * Wires the shared bottom navigation bar of the NuxDroid shell.
 *
 * <p/>
 * The four destinations are separate screens by design: Home, Packages and
 * Settings are activities that carry the same bar, while Terminal opens the
 * real {@code TermuxActivity} full screen (keyboard, extra keys and session
 * handling stay untouched). The bar therefore never fakes a tab: it just
 * routes, and each screen marks itself as the selected item.
 */
public final class DashboardNav {

    private DashboardNav() {
    }

    /**
     * Bind the bar of the hosting screen.
     *
     * @param activity   screen owning the bar
     * @param nav        the {@link BottomNavigationView} to wire, may be null
     * @param selectedId id of the item representing this screen
     */
    public static void bind(Activity activity, BottomNavigationView nav, int selectedId) {
        if (nav == null) return;

        // Check the current destination without firing the listener, otherwise
        // binding would immediately navigate away from the screen.
        MenuItem current = nav.getMenu().findItem(selectedId);
        if (current != null) current.setChecked(true);

        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == selectedId) {
                // Re-tapping the current tab must not rebuild the screen.
                return true;
            }
            if (id == R.id.nav_home) {
                open(activity, DashboardActivity.class);
            } else if (id == R.id.nav_terminal) {
                TerminalCommands.openTerminal(activity);
            } else if (id == R.id.nav_packages) {
                open(activity, PackagesActivity.class);
            } else if (id == R.id.nav_settings) {
                open(activity, SettingsActivity.class);
            }
            // Returning true keeps the highlight on the screen that is really
            // visible; the destination marks itself when it binds its own bar.
            return true;
        });
    }

    private static void open(Context context, Class<?> target) {
        Intent intent = new Intent(context, target);
        if (target == DashboardActivity.class) {
            // Home is the root: pop everything above it instead of stacking.
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        } else {
            // Reuse a screen that is already in the stack.
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        }
        context.startActivity(intent);
    }

}
