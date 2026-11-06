package com.termux.app.activities;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.termux.R;
import com.termux.shared.logger.Logger;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Home of the NuxDroid shell: an editorial page (title, one status section, the
 * desktop milestone and a quiet version line) inside an emulator-style window
 * skin, above the shared flat navigation bar.
 */
public class DashboardActivity extends AppCompatActivity {

    private static final String LOG_TAG = "DashboardActivity";

    /** SharedPreferences flag: the first run note is shown at most once. */
    private static final String PREF_FIRST_RUN_HINT = "first_run_hint_shown";

    /** Window frame margin of the emulator skin (dp). */
    private static final int FRAME_MARGIN_DP = 8;

    /** Whether the emulator window currently sits edge to edge. */
    private boolean mFrameMaximized;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        // Terminal, Packages and Settings are reached from the bottom bar, so
        // the home screen only keeps the two status quick actions.
        findViewById(R.id.dashboard_quick_terminal).setOnClickListener(view ->
            TerminalCommands.openTerminal(this));
        findViewById(R.id.dashboard_quick_update).setOnClickListener(view ->
            TerminalCommands.run(this, "apt-get update"));
        setupMilestoneCard(R.id.dashboard_tile_desktop);

        // Emulator window controls.
        findViewById(R.id.emulator_btn_minimize).setOnClickListener(view ->
            moveTaskToBack(true));
        findViewById(R.id.emulator_btn_maximize).setOnClickListener(view ->
            toggleFrameMaximized());
        findViewById(R.id.emulator_btn_close).setOnClickListener(view ->
            confirmExit());

        // Shared bottom bar: mark Home as the current destination.
        BottomNavigationView bottomNav = findViewById(R.id.dashboard_bottom_nav);
        DashboardNav.bind(this, bottomNav, R.id.nav_home);

        showVersion();
        showStatus();
        showStorage();
        maybeShowFirstRunHint();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The prefix can be installed from the terminal while this screen stays
        // in the back stack, so refresh the status card when coming back.
        showStatus();
    }

    /** Maximize: window margins 8dp -> 0dp, so the frame goes edge to edge. */
    private void toggleFrameMaximized() {
        View frame = findViewById(R.id.emulator_frame);
        if (frame == null) return;
        mFrameMaximized = !mFrameMaximized;
        int margin = mFrameMaximized ? 0 : Math.round(FRAME_MARGIN_DP * getResources().getDisplayMetrics().density);
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) frame.getLayoutParams();
        params.setMargins(margin, margin, margin, margin);
        frame.setLayoutParams(params);
    }

    /** Close: honest confirmation before tearing down all sessions. */
    private void confirmExit() {
        NuxDialog.show(this,
            getString(R.string.dashboard_exit_title),
            getString(R.string.dashboard_exit_message),
            getString(R.string.dashboard_exit_positive),
            this::finishAffinity,
            getString(android.R.string.cancel));
    }

    /**
     * Desktop milestone is a roadmap row: it only opens the honest note about
     * what is planned (embed Xlorie, package XFCE for the repository).
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

        // Green dot = ready, grey = not ready, so status reads at a glance.
        View dot = findViewById(R.id.dashboard_status_dot);
        if (dot != null) {
            dot.setBackgroundResource(ready
                ? R.drawable.bg_nux_dot_active
                : R.drawable.bg_nux_dot_idle);
        }
    }

    /**
     * Footer meta: size of the app files (prefix, sessions, packages), measured
     * on a background thread because the prefix holds tens of thousands of
     * files.
     */
    private void showStorage() {
        final TextView storageView = findViewById(R.id.dashboard_storage);
        if (storageView == null) return;
        new Thread(() -> {
            final String text;
            try {
                text = formatSize(sizeOf(getFilesDir(), new HashSet<>(), 0));
            } catch (Exception e) {
                Logger.logVerbose(LOG_TAG, "Could not measure storage: " + e.getMessage());
                return;
            }
            runOnUiThread(() -> storageView.setText(getString(R.string.dashboard_storage_used, text)));
        }, "nuxdroid-storage").start();
    }

    /** Recursive size with a visited set: prefixes contain symlinks. */
    private static long sizeOf(File file, Set<String> visited, int depth) throws IOException {
        if (file.isDirectory()) {
            String path = file.getCanonicalPath();
            if (depth > 40 || !visited.add(path)) return 0;
            long size = 0;
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) size += sizeOf(child, visited, depth + 1);
            }
            return size;
        }
        return file.length();
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1_000_000_000L) return String.format(Locale.US, "%.1f GB", bytes / 1e9);
        if (bytes >= 1_000_000L) return String.format(Locale.US, "%.0f MB", bytes / 1e6);
        return Math.max(1, bytes / 1000) + " KB";
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
