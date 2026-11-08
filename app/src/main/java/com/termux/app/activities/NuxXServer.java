package com.termux.app.activities;

import android.content.Context;
import android.content.Intent;

import com.termux.shared.logger.Logger;

import java.io.InputStream;

/**
 * Starts the embedded Lorie X server (the Termux:X11 native binary) inside this
 * app's own process tree.
 *
 * <p/>
 * The official Termux:X11 flow goes through a shell script plus a loader APK
 * in $PREFIX; because the X server code is compiled straight into this APK we
 * can launch it directly with app_process and CLASSPATH pointing at our own
 * base.apk. The server then broadcasts its connection binder, which the
 * embedded {@code com.termux.x11.MainActivity} picks up automatically.
 */
public final class NuxXServer {

    private static final String LOG_TAG = "NuxXServer";

    /** Entry point of the embedded X server (kept by proguard rules of :lorie). */
    private static final String ENTRY_CLASS = "com.termux.x11.CmdEntryPoint";

    private static Process sProcess;

    private NuxXServer() {
    }

    /** Launch the X server on display :0 if it is not already running. */
    public static synchronized void start(Context context) {
        if (sProcess != null && sProcess.isAlive()) {
            Logger.logVerbose(LOG_TAG, "X server already running");
            return;
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(
                "/system/bin/app_process",
                "-Xnoimage-dex2oat",
                "/",
                "--nice-name=nuxdroid-x11",
                ENTRY_CLASS,
                ":0");
            builder.environment().put("CLASSPATH", context.getApplicationInfo().sourceDir);
            // Run the desktop session as soon as the server is ready: the
            // script exports the prefix environment (PATH, LD_LIBRARY_PATH,
            // fontconfig, gio/pixbuf caches) and execs openbox-session.
            builder.environment().put("TERMUX_X11_XSTARTUP",
                "/data/data/com.nuxdroid/files/usr/bin/nuxdroid-session");
            builder.environment().remove("LD_LIBRARY_PATH");
            builder.environment().remove("LD_PRELOAD");
            builder.redirectErrorStream(true);
            sProcess = builder.start();

            // Drain output so the server can never block on a full pipe.
            final Process process = sProcess;
            new Thread(() -> {
                try (InputStream in = process.getInputStream()) {
                    byte[] buffer = new byte[1024];
                    while (in.read(buffer) >= 1) {
                        // keep draining
                    }
                } catch (Exception ignored) {
                }
            }, "nuxdroid-x11-drain").start();

            Logger.logInfo(LOG_TAG, "Embedded X server started (app_process, CLASSPATH="
                + context.getApplicationInfo().sourceDir + ")");
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Could not start embedded X server: " + e.getMessage());
            sProcess = null;
        }
    }

    /** Open the embedded X desktop surface (the Lorie view activity). */
    public static void openDesktop(Context context) {
        start(context);
        Intent intent = new Intent(context, com.termux.x11.MainActivity.class);
        if (!(context instanceof android.app.Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }
}
