package com.termux.app;

import android.content.Intent;
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
 * bootstrap the way a game installs its additional data, then hands over to the
 * dashboard.
 *
 * <p/>
 * The screen only appears while the prefix is missing or broken. Once the
 * prefix is bootable the launch is a straight pass-through, so a normal open
 * never waits here.
 */
public class BootstrapActivity extends AppCompatActivity implements TermuxInstaller.InstallListener {

    private static final String LOG_TAG = "BootstrapActivity";

    /** Lets the 100% state land before the dashboard takes over. */
    private static final long HANDOVER_DELAY_MS = 320L;

    /** Lets the failure line be read before the dashboard takes over. */
    private static final long RESULT_DELAY_MS = 2400L;

    private View mProgressGroup;
    private View mErrorGroup;
    private TextView mPercentView;
    private TextView mPhaseView;
    private TextView mErrorMessageView;
    private ProgressBar mProgressBar;
    private Button mRetryButton;

    private boolean mInstalling;

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

        mRetryButton.setOnClickListener(view -> startInstall());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmExit();
            }
        });

        if (TermuxInstaller.isPrefixBootable()) {
            openDashboard();
            return;
        }
        startInstall();
    }

    private void startInstall() {
        if (mInstalling) return;
        mInstalling = true;

        mProgressGroup.setVisibility(View.VISIBLE);
        mErrorGroup.setVisibility(View.GONE);
        mProgressBar.setProgress(0);
        mPercentView.setText("0%");
        mPhaseView.setText(R.string.bootstrap_phase_preparing);

        TermuxInstaller.installBootstrap(this, this);
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

        // Install the desktop on a background thread: the screen stays alive
        // through "Updating package index…" and "Installing Openbox…", then
        // hands over to the dashboard.
        new Thread(() -> {
            boolean ok = attemptOpenboxInstall();
            new Handler(Looper.getMainLooper()).post(() -> {
                mInstalling = false;
                if (isFinishing() || isDestroyed()) return;
                // Same key DesktopInstallActivity checks: a success skips the
                // dashboard's own setup screen; a failure lets it offer Retry.
                if (ok) {
                    try {
                        getSharedPreferences("nuxdroid_bootstrap", MODE_PRIVATE)
                            .edit().putBoolean("openbox_installed", true).apply();
                    } catch (Exception ignored) {
                    }
                }
                mProgressBar.setIndeterminate(false);
                mPhaseView.setText(ok
                    ? R.string.desktop_install_done
                    : R.string.desktop_install_failed_note);
                long delay = ok ? HANDOVER_DELAY_MS : RESULT_DELAY_MS;
                new Handler(Looper.getMainLooper()).postDelayed(this::openDashboard, delay);
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

    private void confirmExit() {
        if (!mInstalling) {
            finish();
            return;
        }
        NuxDialog.show(this,
            getString(R.string.bootstrap_exit_title),
            getString(R.string.bootstrap_exit_message),
            getString(R.string.bootstrap_exit_positive),
            this::finish,
            getString(R.string.bootstrap_exit_negative));
    }

    private void openDashboard() {
        startActivity(new Intent(this, DashboardActivity.class));
        finish();
    }

}
