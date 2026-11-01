package com.termux.app.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.color.DynamicColors;
import com.termux.R;
import com.termux.shared.logger.Logger;

/**
 * Main entry screen of NuxDroid: a menu of large tiles instead of dropping the
 * user straight into a shell.
 */
public class DashboardActivity extends AppCompatActivity {

    private static final String LOG_TAG = "DashboardActivity";

    /** SharedPreferences flag: the first run note is shown at most once. */
    private static final String PREF_FIRST_RUN_HINT = "first_run_hint_shown";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Material You dynamic colors (Android 12+) with graceful fallback.
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        findViewById(R.id.dashboard_tile_terminal).setOnClickListener(view ->
            TerminalCommands.openTerminal(this));
        setupDisabledTile(R.id.dashboard_tile_desktop);
        findViewById(R.id.dashboard_tile_packages).setOnClickListener(view ->
            startActivity(new Intent(this, PackagesActivity.class)));
        findViewById(R.id.dashboard_tile_settings).setOnClickListener(view ->
            startActivity(new Intent(this, SettingsActivity.class)));

        showVersion();
        showStatus();
        maybeShowFirstRunHint();
    }

    /**
     * Desktop tile is not available yet: keep it visible but dimmed, show a
     * "Segera" badge, and only open the honest dialog when tapped.
     */
    private void setupDisabledTile(int tileId) {
        View tile = findViewById(tileId);
        if (tile == null) return;
        tile.setAlpha(0.55f);
        tile.setOnClickListener(view -> showDesktopDialog());
        TextView badge = findViewById(R.id.dashboard_tile_desktop_badge);
        if (badge != null) badge.setVisibility(View.VISIBLE);
    }

    /** Show the installed versionName in the footer, or "NuxDroid" if it cannot be read. */
    private void showVersion() {
        TextView versionView = findViewById(R.id.dashboard_version);
        if (versionView == null) return;
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            versionView.setText(getString(R.string.dashboard_version,
                (version == null || version.isEmpty()) ? "?" : version));
        } catch (Exception e) {
            Logger.logVerbose(LOG_TAG, "Could not read versionName: " + e.getMessage());
        }
    }

    /** Reflect whether the Linux prefix is ready in the header status chip. */
    private void showStatus() {
        TextView statusView = findViewById(R.id.dashboard_status);
        if (statusView == null) return;
        boolean ready = TerminalCommands.isReady();
        statusView.setText(ready ? R.string.dashboard_status_ready : R.string.dashboard_status_not_ready);
    }

    /** One time welcome note, shown only while Linux is not set up yet. */
    private void maybeShowFirstRunHint() {
        SharedPreferences prefs = getSharedPreferences("dashboard", MODE_PRIVATE);
        if (prefs.getBoolean(PREF_FIRST_RUN_HINT, false) || TerminalCommands.isReady()) return;
        prefs.edit().putBoolean(PREF_FIRST_RUN_HINT, true).apply();
        new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.dashboard_welcome_title)
            .setMessage(R.string.dashboard_welcome_message)
            .setPositiveButton(R.string.dashboard_open_terminal, (dialog, which) ->
                TerminalCommands.openTerminal(this))
            .setNegativeButton(R.string.dashboard_later, null)
            .show();
    }

    /** The desktop milestone (embed Xlorie, package XFCE) is not implemented yet. */
    private void showDesktopDialog() {
        new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.dashboard_desktop_title)
            .setMessage(R.string.dashboard_desktop_message)
            .setPositiveButton(R.string.dashboard_open_terminal, (dialog, which) ->
                TerminalCommands.openTerminal(this))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }
}
