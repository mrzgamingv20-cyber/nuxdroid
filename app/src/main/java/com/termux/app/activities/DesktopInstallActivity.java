package com.termux.app.activities;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.app.TermuxInstaller;
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
 * First-open desktop setup, styled like an installer: a title, an animated
 * phase bar and the phase line, then the hand-over to the dashboard.
 *
 * <p/>
 * It appears until Openbox is installed ({@code openbox_installed}) and runs
 * with the prefix's own bash/pkg. A failure stays recoverable: the screen
 * returns on the next open unless the user dismissed it
 * ({@code openbox_dismissed}). The trigger lives in {@link DashboardActivity},
 * not in the bootstrap phase.
 */
public class DesktopInstallActivity extends AppCompatActivity {

    private static final String LOG_TAG = "DesktopInstallActivity";

    private static final String PREFS = "nuxdroid_bootstrap";
    private static final String KEY_INSTALLED = "openbox_installed";
    /**
     * Set only when the user actively leaves the setup screen (Later or Back).
     * A failed attempt must NOT stick: the screen comes back on the next open
     * so a transient network or repository error can recover on its own.
     */
    private static final String KEY_DISMISSED = "openbox_dismissed";

    /** Lets the "ready" line land before the dashboard takes over. */
    private static final long DONE_DELAY_MS = 1200L;

    private View mProgressGroup;
    private View mErrorGroup;
    private TextView mPhaseView;
    private TextView mErrorMessageView;
    private ProgressBar mProgressBar;

    private boolean mInstalling;

    /** Whether the first-open setup screen should appear for this launch. */
    public static boolean shouldInstall(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (prefs.getBoolean(KEY_INSTALLED, false)
                || prefs.getBoolean(KEY_DISMISSED, false)) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        // Without a bootable prefix apt cannot install anything; the screen is
        // retried on a later open, once the bootstrap has run from the terminal.
        return TermuxInstaller.isPrefixBootable();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_desktop_install);

        mProgressGroup = findViewById(R.id.desktop_install_progress_group);
        mErrorGroup = findViewById(R.id.desktop_install_error_group);
        mPhaseView = findViewById(R.id.desktop_install_phase);
        mErrorMessageView = findViewById(R.id.desktop_install_error_message);
        mProgressBar = findViewById(R.id.desktop_install_progress);

        findViewById(R.id.desktop_install_retry).setOnClickListener(view -> startInstall());
        findViewById(R.id.desktop_install_later).setOnClickListener(view -> {
            markDismissed();
            goDashboard();
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mInstalling) {
                    NuxDialog.show(DesktopInstallActivity.this,
                        getString(R.string.desktop_install_exit_title),
                        getString(R.string.desktop_install_exit_message),
                        getString(R.string.desktop_install_exit_positive),
                        () -> {
                            markDismissed();
                            DesktopInstallActivity.this.finish();
                        },
                        getString(R.string.desktop_install_exit_negative));
                } else {
                    markDismissed();
                    finish();
                }
            }
        });

        startInstall();
    }

    private void startInstall() {
        if (mInstalling) return;
        mInstalling = true;

        mProgressGroup.setVisibility(View.VISIBLE);
        mErrorGroup.setVisibility(View.GONE);
        mProgressBar.setIndeterminate(true);
        mPhaseView.setText(R.string.desktop_install_phase_update);

        new Thread(() -> {
            boolean ok = runPkg("timeout 120 apt-get update", 140);
            if (ok) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    mPhaseView.setText(R.string.desktop_install_phase_openbox);
                });
                ok = runPkg("timeout 300 pkg install -y openbox", 320);
            }
            final boolean success = ok;
            if (success) {
                try {
                    getSharedPreferences(PREFS, MODE_PRIVATE)
                        .edit().putBoolean(KEY_INSTALLED, true).apply();
                } catch (Exception ignored) {
                }
            }
            runOnUiThread(() -> {
                mInstalling = false;
                if (isFinishing() || isDestroyed()) return;
                if (success) {
                    mPhaseView.setText(R.string.desktop_install_done);
                    mProgressBar.setIndeterminate(false);
                    mProgressBar.setProgress(mProgressBar.getMax());
                    new Handler(Looper.getMainLooper())
                        .postDelayed(DesktopInstallActivity.this::goDashboard, DONE_DELAY_MS);
                } else {
                    mProgressGroup.setVisibility(View.GONE);
                    mErrorGroup.setVisibility(View.VISIBLE);
                    mErrorMessageView.setText(R.string.desktop_install_failed_note);
                }
            });
        }, "nuxdroid-openbox-installer").start();
    }

    /**
     * Run one repository command on the prefix environment. Bounded by an
     * in-command timeout so a dead network can never hang the installer.
     */
    private boolean runPkg(String command, int timeoutSeconds) {
        String bash = TERMUX_PREFIX_DIR_PATH + "/bin/bash";
        if (!new File(bash).exists()) {
            Logger.logError(LOG_TAG, "Desktop setup skipped: bash missing in prefix");
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
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                Logger.logError(LOG_TAG, "Desktop setup timed out: " + command);
                return false;
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                Logger.logError(LOG_TAG, "Desktop setup exit " + exitCode + ": " + tail);
                return false;
            }
            return true;
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Desktop setup failed: " + e.getMessage());
            return false;
        }
    }

    /** Remember that the user chose to skip setup; a failure never sets this. */
    private void markDismissed() {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putBoolean(KEY_DISMISSED, true).apply();
        } catch (Exception ignored) {
        }
    }

    private void goDashboard() {
        startActivity(new Intent(this, DashboardActivity.class));
        finish();
    }

}
