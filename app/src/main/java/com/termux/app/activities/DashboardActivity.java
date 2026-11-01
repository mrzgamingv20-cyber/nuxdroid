package com.termux.app.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.shared.logger.Logger;

/**
 * Main entry screen of NuxDroid: a menu of large tiles instead of dropping the
 * user straight into a shell.
 *
 * <p/>
 * The tiles never fake anything: "Terminal" and "Pengaturan" open the real
 * screens, "Install Paket" opens the package list from the NuxDroid repository,
 * and "Desktop" says honestly that the XFCE/Xlorie milestone is not built yet
 * instead of failing silently.
 */
public class DashboardActivity extends AppCompatActivity {

    private static final String LOG_TAG = "DashboardActivity";

    /** SharedPreferences flag: the first run note is shown at most once. */
    private static final String PREF_FIRST_RUN_HINT = "first_run_hint_shown";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        findViewById(R.id.dashboard_tile_terminal).setOnClickListener(view ->
            TerminalCommands.openTerminal(this));
        findViewById(R.id.dashboard_tile_desktop).setOnClickListener(view -> showDesktopDialog());
        findViewById(R.id.dashboard_tile_packages).setOnClickListener(view ->
            startActivity(new Intent(this, PackagesActivity.class)));
        findViewById(R.id.dashboard_tile_settings).setOnClickListener(view ->
            startActivity(new Intent(this, SettingsActivity.class)));

        showVersion();
        maybeShowFirstRunHint();
    }

    /**
     * One time welcome note, shown only while Linux is not set up yet, so the
     * first tap on "Terminal" is not a surprise: the bootstrap is installed on
     * that first open and takes a minute or two.
     */
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

    /** Show the installed versionName in the footer, or "?" if it cannot be read. */
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
