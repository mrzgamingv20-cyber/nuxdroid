package com.termux.app.activities;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.logger.Logger;
import com.termux.shared.net.uri.UriUtils;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;

import java.io.File;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;

/**
 * The single place that turns a command into a real NuxDroid terminal session.
 * The dashboard and the package list both go through here, so the UI stays a
 * thin layer over the shell instead of re-implementing it.
 */
public final class TerminalCommands {

    private static final String LOG_TAG = "TerminalCommands";

    private TerminalCommands() {
    }

    /**
     * Whether the prefix is installed well enough to run commands in it. Single
     * source of truth: the dashboard uses it for its first run hint, {@link #run}
     * uses it before starting a session.
     */
    public static boolean isReady() {
        return new File(TERMUX_PREFIX_DIR_PATH, "bin/bash").exists();
    }

    /**
     * Run {@code command} in a new foreground terminal session and switch to it.
     *
     * <p/>
     * On a fresh install the prefix does not exist yet, so instead of failing we
     * send the user to the terminal, which installs the bootstrap first.
     */
    public static void run(Context context, String command) {
        if (!isReady()) {
            Toast.makeText(context, R.string.dashboard_terminal_not_ready, Toast.LENGTH_LONG).show();
            openTerminal(context);
            return;
        }
        try {
            Uri bash = UriUtils.getFileUri(TERMUX_PREFIX_DIR_PATH + "/bin/bash");
            Intent intent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, bash);
            intent.setClass(context, TermuxService.class);
            intent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, new String[]{"-lc", command});
            intent.putExtra(TERMUX_SERVICE.EXTRA_SESSION_ACTION,
                String.valueOf(TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY));
            intent.putExtra(TERMUX_SERVICE.EXTRA_COMMAND_LABEL, command);
            context.startService(intent);
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Could not run \"" + command + "\": " + e.getMessage());
            Toast.makeText(context, context.getString(R.string.dashboard_action_failed, e.getMessage()),
                Toast.LENGTH_LONG).show();
        }
    }

    /** Open the main terminal screen. */
    public static void openTerminal(Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

}
