package com.termux.app.activities;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.termux.R;
import com.termux.shared.logger.Logger;

/**
 * Home of the NuxDroid shell: an editorial page (title, one status section, the
 * desktop milestone and a quiet version line) above the shared flat navigation
 * bar.
 */
public class DashboardActivity extends AppCompatActivity {

    private static final String LOG_TAG = "DashboardActivity";

    /** SharedPreferences flag: the first run note is shown at most once. */
    private static final String PREF_FIRST_RUN_HINT = "first_run_hint_shown";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        // Terminal, Packages and Settings are reached from the bottom pill, so
        // the home screen only keeps the two status quick actions.
        findViewById(R.id.dashboard_quick_terminal).setOnClickListener(view ->
            TerminalCommands.openTerminal(this));
        findViewById(R.id.dashboard_quick_update).setOnClickListener(view ->
            TerminalCommands.run(this, "apt-get update"));
        setupMilestoneCard(R.id.dashboard_tile_desktop);

        // Shared bottom bar: mark Home as the current destination.
        BottomNavigationView bottomNav = findViewById(R.id.dashboard_bottom_nav);
        DashboardNav.bind(this, bottomNav, R.id.nav_home);

        showVersion();
        showStatus();
        maybeShowFirstRunHint();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The prefix can be installed from the terminal while this screen stays
        // in the back stack, so refresh the status card when coming back.
        showStatus();
    }

    /**
     * Desktop milestone is not available yet: the row reads as inactive because
     * of its "Soon" label and only opens the honest note when tapped.
     */
    private void setupMilestoneCard(int cardId) {
        View card = findViewById(cardId);
        if (card == null) return;
        card.setOnClickListener(view -> showDesktopDialog());
        TextView badge = findViewById(R.id.dashboard_tile_desktop_badge);
        if (badge != null) badge.setVisibility(View.VISIBLE);
    }

    /** Show the installed versionName in the meta line, or "?" if unreadable. */
    private void showVersion() {
        TextView versionView = findViewById(R.id.dashboard_version);
        if (versionView == null) return;
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            versionView.setText((version == null || version.isEmpty()) ? "?" : "v" + version);
        } catch (Exception e) {
            versionView.setText("?");
            Logger.logVerbose(LOG_TAG, "Could not read versionName: " + e.getMessage());
        }
    }

    /** Reflect whether the Linux prefix is ready in the status section. */
    private void showStatus() {
        boolean ready = TerminalCommands.isReady();

        TextView statusView = findViewById(R.id.dashboard_status);
        if (statusView != null) {
            statusView.setText(ready ? R.string.dashboard_status_ready : R.string.dashboard_status_not_ready);
        }

        TextView descView = findViewById(R.id.dashboard_status_desc);
        if (descView != null) {
            descView.setText(ready
                ? R.string.dashboard_status_ready_desc
                : R.string.dashboard_status_not_ready_desc);
        }

        View dot = findViewById(R.id.dashboard_status_dot);
        if (dot != null) {
            dot.setBackgroundResource(ready
                ? R.drawable.bg_nux_dot_active
                : R.drawable.bg_nux_dot_idle);
        }

        TextView prefixView = findViewById(R.id.dashboard_prefix_value);
        if (prefixView != null) {
            prefixView.setText(ready
                ? R.string.dashboard_prefix_installed
                : R.string.dashboard_prefix_missing);
        }
    }

    /** One time welcome note, shown only while Linux is not set up yet. */
    private void maybeShowFirstRunHint() {
        SharedPreferences prefs = getSharedPreferences("dashboard", MODE_PRIVATE);
        if (prefs.getBoolean(PREF_FIRST_RUN_HINT, false) || TerminalCommands.isReady()) return;
        prefs.edit().putBoolean(PREF_FIRST_RUN_HINT, true).apply();
        NuxDialog.show(this,
            getString(R.string.dashboard_welcome_title),
            getString(R.string.dashboard_welcome_message),
            getString(R.string.dashboard_open_terminal),
            () -> TerminalCommands.openTerminal(this),
            getString(R.string.dashboard_later));
    }

    /** The desktop milestone (embed Xlorie, package XFCE) is not implemented yet. */
    private void showDesktopDialog() {
        NuxDialog.show(this,
            getString(R.string.dashboard_desktop_title),
            getString(R.string.dashboard_desktop_message),
            getString(R.string.dashboard_open_terminal),
            () -> TerminalCommands.openTerminal(this),
            getString(android.R.string.cancel));
    }
}
