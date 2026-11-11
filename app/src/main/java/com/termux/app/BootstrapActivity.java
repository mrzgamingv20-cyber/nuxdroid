package com.termux.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.app.activities.DashboardActivity;
import com.termux.app.activities.NuxDialog;
import com.termux.shared.logger.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.termux.shared.termux.TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH;

/**
 * First-run screen of the NuxDroid shell: it unpacks the bundled Linux
 * bootstrap the way a game installs its additional data, installs the desktop
 * (Openbox) and only then hands over to the dashboard. Both steps share the
 * same full-screen animation, so the first open reads as one install.
 *
 * <p/>
 * The screen appears while the prefix is missing or broken, and also when the
 * prefix already exists but Openbox has not been installed yet (an app update
 * over an older install). Once both are in place the launch is a straight
 * pass-through, so a normal open never waits here.
 */
public class BootstrapActivity extends AppCompatActivity implements TermuxInstaller.InstallListener {

    private static final String LOG_TAG = "BootstrapActivity";

    /**
     * Set by this screen when it hands over after a failed attempt, so the
     * dashboard does not bounce straight back into it in the same session.
     * The next launch starts without the extra and therefore retries.
     */
    public static final String EXTRA_INSTALL_ATTEMPTED = "install_attempted";

    private static final String PREFS = "nuxdroid_bootstrap";
    private static final String KEY_INSTALLED = "openbox_installed";
    /** Set only when the user actively leaves setup (exit dialog). */
    private static final String KEY_DISMISSED = "openbox_dismissed";
    /**
     * Install time of the APK that stored the dismissal: a freshly installed
     * build retries setup once, so a fix always gets its chance.
     */
    private static final String KEY_DISMISSED_AT = "openbox_dismissed_at";

    /** Lets the "ready" state land before the dashboard takes over. */
    private static final long HANDOVER_DELAY_MS = 900L;

    private View mProgressGroup;
    private View mErrorGroup;
    private TextView mPercentView;
    private TextView mPhaseView;
    private TextView mErrorMessageView;
    private ProgressBar mProgressBar;
    private Button mRetryButton;

    private boolean mInstalling;

    /** Whether the first-open desktop setup should run for this launch. */
    public static boolean shouldInstall(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (prefs.getBoolean(KEY_INSTALLED, false)) {
                return false;
            }
            if (prefs.getBoolean(KEY_DISMISSED, false)
                && prefs.getLong(KEY_DISMISSED_AT, 0L) == installTime(context)) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        // Without a bootable prefix apt cannot install anything; the bootstrap
        // extraction below handles that case first.
        return TermuxInstaller.isPrefixBootable();
    }

    /** Install time of this APK copy; it changes with every install. */
    private static long installTime(Context context) {
        try {
            return context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        } catch (Exception e) {
            return 0L;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bootstrap);

        mProgressGroup = findViewById(R.id.bootstrap_progress_group);
        mErrorGroup = findViewById(R.id.bootstrap_error_group);
        mPercentView = findViewById(R.id.bootstrap_percent);
        mPhaseView = findViewById(R.id.bootstrap_phase);
        mErrorMessageView = findViewById(R.id.bootstrap_error_message);
        mProgressBar = findViewById(R.id.bootstrap_progress);
        mRetryButton = findViewById(R.id.bootstrap_retry);

        mRetryButton.setOnClickListener(view -> {
            if (TermuxInstaller.isPrefixBootable()) startDesktopSetup();
            else startInstall();
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBack();
            }
        });

        if (TermuxInstaller.isPrefixBootable()) {
            if (shouldInstall(this)) {
                // Prefix ready, only the desktop is missing: same full-screen
                // animation, without the extraction step.
                startDesktopSetup();
            } else {
                openDashboard();
            }
            return;
        }
        startInstall();
    }

    /** Unpack the bundled bootstrap, then install the desktop on top of it. */
    private void startInstall() {
        if (mInstalling) return;
        mInstalling = true;

        mProgressGroup.setVisibility(View.VISIBLE);
        mErrorGroup.setVisibility(View.GONE);
        mProgressBar.setIndeterminate(false);
        mProgressBar.setProgress(0);
        mPercentView.setText("0%");
        mPhaseView.setText(R.string.bootstrap_phase_preparing);

        TermuxInstaller.installBootstrap(this, this);
    }

    /** First-open desktop setup when the prefix already exists. */
    private void startDesktopSetup() {
        if (mInstalling) return;
        mInstalling = true;

        mProgressGroup.setVisibility(View.VISIBLE);
        mErrorGroup.setVisibility(View.GONE);
        mProgressBar.setIndeterminate(true);
        mPercentView.setText("100%");
        mPhaseView.setText(R.string.desktop_install_phase_update);

        runDesktopInstall();
    }

    @Override
    public void onTotalEntries(int totalEntries) {
        if (totalEntries <= 0) return;
        mProgressBar.setMax(totalEntries);
        mProgressBar.setProgress(0);
    }

    @Override
    public void onProgress(int done, int total) {
        if (total <= 0) return;
        int percent = (int) (done * 100L / total);
        mProgressBar.setProgress(done);
        mPercentView.setText(percent + "%");
        mPhaseView.setText(getString(R.string.bootstrap_phase_extracting, done, total));
    }

    @Override
    public void onDone() {
        if (isFinishing() || isDestroyed()) return;
        mProgressBar.setIndeterminate(true);
        mPercentView.setText("100%");
        mPhaseView.setText(R.string.desktop_install_phase_update);
        runDesktopInstall();
    }

    /**
     * Install the desktop on a background thread: the screen stays alive
     * through "Updating package index…" and "Installing Openbox…", then hands
     * over to the dashboard. A failure shows the note with Retry; the next
     * launch tries again unless the user exited setup explicitly.
     */
    private void runDesktopInstall() {
        new Thread(() -> {
            boolean ok = attemptOpenboxInstall();
            new Handler(Looper.getMainLooper()).post(() -> {
                mInstalling = false;
                if (isFinishing() || isDestroyed()) return;
                mProgressBar.setIndeterminate(false);
                if (ok) {
                    try {
                        getSharedPreferences(PREFS, MODE_PRIVATE)
                            .edit().putBoolean(KEY_INSTALLED, true).apply();
                    } catch (Exception ignored) {
                    }
                    mPhaseView.setText(R.string.desktop_install_done);
                    new Handler(Looper.getMainLooper())
                        .postDelayed(this::openDashboard, HANDOVER_DELAY_MS);
                } else {
                    mProgressGroup.setVisibility(View.GONE);
                    mErrorGroup.setVisibility(View.VISIBLE);
                    mErrorMessageView.setText(R.string.desktop_install_failed_note);
                }
            });
        }, "nuxdroid-openbox-installer").start();
    }

    /** Show a phase line on the UI thread from the installer thread. */
    private void postPhase(int stringRes) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (isFinishing() || isDestroyed()) return;
            mPhaseView.setText(stringRes);
        });
    }

    /**
     * Install Openbox from nuxdroid-packages with the prefix's own bash and
     * pkg, on the prefix environment: refresh the package index first, then
     * install. Both steps are bounded by an in-command timeout so a dead
     * network can never hang the installer thread.
     */
    private boolean attemptOpenboxInstall() {
        postPhase(R.string.desktop_install_phase_update);
        if (!runPrefixCommand("timeout 120 apt-get update")) {
            Logger.logError(LOG_TAG, "apt-get update failed before openbox install");
            return false;
        }
        postPhase(R.string.desktop_install_phase_openbox);
        return runPrefixCommand("timeout 300 pkg install -y openbox");
    }

    /** Run a shell command inside the prefix environment with a hard timeout. */
    private boolean runPrefixCommand(String command) {
        String bash = TERMUX_PREFIX_DIR_PATH + "/bin/bash";
        if (!new File(bash).exists()) {
            Logger.logError(LOG_TAG, "Prefix command skipped, bash missing: " + command);
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(bash, "-c", command);
            Map<String, String> env = pb.environment();
            env.put("PREFIX", TERMUX_PREFIX_DIR_PATH);
            env.put("HOME", TERMUX_HOME_DIR_PATH);
            env.put("TMPDIR", TERMUX_TMP_PREFIX_DIR_PATH);
            env.put("PATH", TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin");
            env.remove("LD_PRELOAD");
            pb.redirectErrorStream(true);
            pb.directory(new File(TERMUX_HOME_DIR_PATH));

            Process process = pb.start();
            StringBuilder tail = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (tail.length() < 500) tail.append(line).append('\n');
                }
            }
            if (!process.waitFor(320, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                Logger.logError(LOG_TAG, "Prefix command timed out: " + command);
                return false;
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                Logger.logError(LOG_TAG, "Prefix command exit " + exitCode + ": " + command + "\n" + tail);
            }
            return exitCode == 0;
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Prefix command failed: " + command + " - " + e.getMessage());
            return false;
        }
    }

    @Override
    public void onError(String errorMarkdown) {
        if (isFinishing() || isDestroyed()) return;
        mInstalling = false;
        Logger.logError(LOG_TAG, "Bootstrap install failed: " + errorMarkdown);

        mProgressGroup.setVisibility(View.GONE);
        mErrorGroup.setVisibility(View.VISIBLE);
        String details = errorMarkdown == null ? "" : errorMarkdown.trim();
        // The full stack trace lives in the log; the screen names the cause.
        if (details.length() > 400) details = details.substring(0, 400) + "…";
        mErrorMessageView.setText(TextUtils.isEmpty(details)
            ? getString(R.string.bootstrap_error_note)
            : details);
    }

    private void handleBack() {
        if (mInstalling) {
            NuxDialog.show(this,
                getString(R.string.bootstrap_exit_title),
                getString(R.string.bootstrap_exit_message),
                getString(R.string.bootstrap_exit_positive),
                this::exitSetup,
                getString(R.string.bootstrap_exit_negative));
            return;
        }
        // Setup already finished or failed: let the dashboard open. A failed
        // install is retried on the next launch (nothing is stored as dismissed).
        if (TermuxInstaller.isPrefixBootable()) openDashboardAfterAttempt();
        else finish();
    }

    /** The user chose to leave setup: remember it until a new APK is installed. */
    private void exitSetup() {
        if (TermuxInstaller.isPrefixBootable()) {
            try {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_DISMISSED, true)
                    .putLong(KEY_DISMISSED_AT, installTime(this))
                    .apply();
            } catch (Exception ignored) {
            }
        }
        finish();
    }

    private void openDashboard() {
        startActivity(new Intent(this, DashboardActivity.class));
        finish();
    }

    /**
     * Hand over after a failed attempt: the extra keeps the dashboard from
     * relaunching this screen in the same session.
     */
    private void openDashboardAfterAttempt() {
        startActivity(new Intent(this, DashboardActivity.class)
            .putExtra(EXTRA_INSTALL_ATTEMPTED, true));
        finish();
    }

}
