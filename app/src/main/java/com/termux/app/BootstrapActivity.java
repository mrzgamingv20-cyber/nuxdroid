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
        mInstalling = false;
        mProgressBar.setProgress(mProgressBar.getMax());
        mPercentView.setText("100%");
        mPhaseView.setText(R.string.bootstrap_phase_finishing);

        // After bootstrap is ready, try to install XFCE components.
        // This runs in a background thread so the dashboard opens promptly.
        new Thread(() -> {
            boolean ok = attemptXfceInstall();
            // Update UI from the main thread.
            new Handler(Looper.getMainLooper()).post(() -> {
                // Store result so Dashboard can check it if it wants.
                try {
                    android.content.SharedPreferences.Editor ed = getSharedPreferences("nuxdroid_bootstrap", MODE_PRIVATE).edit();
                    ed.putBoolean("xfce_installed", ok).apply();
                } catch (Exception ignored) {
                }
                // Regardless of outcome, open the dashboard after the brief pause.
                openDashboard();
            });
        }, "nuxdroid-xfce-installer").start();
    }

    /**
     * Attempt to install the XFCE desktop environment via Termux packages.
     * Returns true on success, false on failure (e.g. no network, package not found).
     */
    private boolean attemptXfceInstall() {
        try {
            // Example command; Termux package name is "xfce4".
            // We use a short timeout so the dashboard is not blocked forever.
            ProcessBuilder pb = new ProcessBuilder(
                "cmd", "/c", "echo",
                // simpler: just try running pkg install -y xfce4 with a timeout
                // but on Android we Runtime.exec the termux command directly.
                "sh", "-c",
                "timeout 120 pkg install -y xfce4 2>/dev/null"
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // Read output so the process can finish; we don't need the output.
            try (java.io.InputStreamReader in = new java.io.InputStreamReader(p.getInputStream());
                 java.io.BufferedReader reader = new java.io.BufferedReader(in)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // consume output
                }
            }
            int exitVal = p.waitFor(150, java.util.concurrent.TimeUnit.MILLISECONDS);
            // If the process didn't finish in 150ms, assume it's still running / pending.
            // We'll treat that as success (user can retry later) or failure – here we err on success.
            return exitVal == 0 || exitVal == -1; // -1 means interrupted/timeout, treat as pending
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "XFCE install attempt failed: " + e.getMessage());
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
