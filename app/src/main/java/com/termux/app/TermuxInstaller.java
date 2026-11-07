package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.system.Os;
import android.util.Pair;
import android.view.WindowManager;
import android.widget.Toast;

import com.termux.R;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 * <p/>
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 * <p/>
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * <p/>
 * (4) The zip file is loaded from a shared library.
 * <p/>
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 * continuously encountering zip file entries:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /**
     * Whether the extracted prefix still contains the binaries no shell can start
     * without. Both are guaranteed members of the bootstrap, so a miss means the
     * prefix is broken and must be reinstalled, not used.
     */
    public static boolean isPrefixBootable() {
        return new File(TERMUX_PREFIX_DIR_PATH, "bin/bash").exists()
            && new File(TERMUX_PREFIX_DIR_PATH, "bin/dpkg").exists();
    }

    /** Performs bootstrap setup if necessary. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError);
            //noinspection SdCardPath
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !TermuxConstants.TERMUX_FILES_DIR_PATH.equals(activity.getFilesDir().getAbsolutePath().replaceAll("^/data/user/0/", "/data/data/"))) {
                bootstrapErrorMessage += "\n\n" + activity.getString(R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            }

            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        // If prefix directory exists, even if its a symlink to a valid directory and symlink is not broken/dangling
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" exists but is empty or only contains specific unimportant files.");
            } else if (!isPrefixBootable()) {
                // A half written prefix (killed install, cleared files, broken symlink) would
                // otherwise be trusted here and the first shell would fail with a cryptic
                // "cannot execute" error. Fall through to a clean reinstall instead.
                Logger.logError(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" exists but is not bootable (essential binaries missing), reinstalling the bootstrap.");
            } else {
                whenDone.run();
                return;
            }
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
        }

        // Horizontal progress with a real title: an endless spinner gives no
        // feedback while a ~33 MB archive is unpacked on slow storage.
        // The style must be set before show(), otherwise only a spinner shows.
        final ProgressDialog progress = new ProgressDialog(activity);
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setTitle(R.string.bootstrap_installer_title);
        progress.setMessage(activity.getString(R.string.bootstrap_installer_counting));
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.setCanceledOnTouchOutside(false);
        progress.show();

        installBootstrap(activity, new InstallListener() {
            @Override
            public void onTotalEntries(int totalEntries) {
                if (totalEntries <= 0) return;
                progress.setIndeterminate(false);
                progress.setMax(totalEntries);
                progress.setProgress(0);
            }

            @Override
            public void onProgress(int done, int total) {
                if (total > 0) {
                    progress.setProgress(done);
                    progress.setMessage(activity.getString(R.string.bootstrap_installer_extracting, done, total));
                } else {
                    progress.setMessage(activity.getString(R.string.bootstrap_installer_body));
                }
            }

            @Override
            public void onDone() {
                dismissQuietly(progress);
                whenDone.run();
            }

            @Override
            public void onError(String errorMarkdown) {
                dismissQuietly(progress);
                showBootstrapErrorDialog(activity, whenDone, errorMarkdown);
            }
        });
    }

    /** Dismiss a progress dialog that may already be gone with its activity. */
    private static void dismissQuietly(ProgressDialog progress) {
        try {
            progress.dismiss();
        } catch (RuntimeException e) {
            // Activity already dismissed - ignore.
        }
    }

    /** Progress sink for bootstrap extraction, always called on the UI thread. */
    interface InstallListener {
        void onTotalEntries(int totalEntries);

        void onProgress(int done, int total);

        void onDone();

        void onError(String errorMarkdown);
    }

    /**
     * Install the bootstrap archive, replacing any prefix that is already there.
     *
     * <p/>
     * The first-run screen and the in-terminal fallback share this method: the
     * extraction is the same, only the progress surface differs.
     */
    static void installBootstrap(final Activity activity, final InstallListener listener) {
        new Thread(() -> {
            try {
                extractBootstrap(activity, listener);
                // Recreate env file since termux prefix was wiped earlier
                TermuxShellEnvironment.writeEnvironmentToFile(activity);
                Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");
                activity.runOnUiThread(listener::onDone);
            } catch (final Exception e) {
                final String message = Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e));
                Logger.logErrorExtended(LOG_TAG, "Bootstrap installation failed:\n" + message);
                activity.runOnUiThread(() -> listener.onError(message));
            }
        }, "nuxdroid-bootstrap").start();
    }

    /**
     * Extract the bundled archive into the prefix and install its symlinks.
     * Throws on the first unrecoverable problem; the caller reports it.
     */
    private static void extractBootstrap(Activity activity, InstallListener listener) throws Exception {
        Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

        Error error;

        // Delete prefix staging directory or any file at its destination
        error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
        if (error != null) throw new RuntimeException(Error.getErrorMarkdownString(error));

        // Delete prefix directory or any file at its destination
        error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
        if (error != null) throw new RuntimeException(Error.getErrorMarkdownString(error));

        // Create prefix staging directory if it does not already exist and set required permissions
        error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
        if (error != null) throw new RuntimeException(Error.getErrorMarkdownString(error));

        // Create prefix directory if it does not already exist and set required permissions
        error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
        if (error != null) throw new RuntimeException(Error.getErrorMarkdownString(error));

        Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");

        final byte[] buffer = new byte[8096];
        final List<Pair<String, String>> symlinks = new ArrayList<>(50);

        final byte[] zipBytes = loadZipBytes();

        // The archive is already fully in memory, so one cheap extra pass gives
        // a real entry count and turns the spinner into a determinate bar.
        final int entryCount = countZipEntries(zipBytes);
        int extractedCount = 0;
        long lastUiUpdate = 0L;
        if (entryCount > 0) {
            final int total = entryCount;
            activity.runOnUiThread(() -> listener.onTotalEntries(total));
        }

        try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry zipEntry;
            while ((zipEntry = zipInput.getNextEntry()) != null) {
                if (zipEntry.getName().equals("SYMLINKS.txt")) {
                    BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                    String line;
                    while ((line = symlinksReader.readLine()) != null) {
                        String[] parts = line.split("←");
                        if (parts.length != 2)
                            throw new RuntimeException("Malformed symlink line: " + line);
                        String oldPath = parts[0];
                        String newPath = TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1];
                        symlinks.add(Pair.create(oldPath, newPath));

                        error = ensureDirectoryExists(new File(newPath).getParentFile());
                        if (error != null) throw new RuntimeException(Error.getErrorMarkdownString(error));
                    }
                } else {
                    String zipEntryName = zipEntry.getName();
                    File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName);
                    boolean isDirectory = zipEntry.isDirectory();

                    error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                    if (error != null) throw new RuntimeException(Error.getErrorMarkdownString(error));

                    if (!isDirectory) {
                        try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                            int readBytes;
                            while ((readBytes = zipInput.read(buffer)) != -1)
                                outStream.write(buffer, 0, readBytes);
                        }
                        if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
                            zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods")) {
                            //noinspection OctalInteger
                            Os.chmod(targetFile.getAbsolutePath(), 0700);
                        }
                    }
                }

                // Throttled UI update: one post every ~100ms is enough and keeps
                // 4k+ entries from flooding the main thread.
                extractedCount++;
                long now = System.currentTimeMillis();
                if (now - lastUiUpdate >= 100L || (entryCount > 0 && extractedCount == entryCount)) {
                    lastUiUpdate = now;
                    final int done = extractedCount;
                    final int total = entryCount;
                    activity.runOnUiThread(() -> listener.onProgress(done, total));
                }
            }
        }

        if (symlinks.isEmpty())
            throw new RuntimeException("No SYMLINKS.txt encountered");
        for (Pair<String, String> symlink : symlinks) {
            Os.symlink(symlink.first, symlink.second);
        }

        Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");

        if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
            throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
        }
    }

    /**
     * Count the entries of an in-memory bootstrap archive so extraction can
     * report "1234/4526" instead of an endless spinner. Returns -1 when the
     * archive cannot be read, in which case the dialog stays indeterminate.
     */
    private static int countZipEntries(byte[] zipBytes) {
        try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            int count = 0;
            while (zipInput.getNextEntry() != null) {
                count++;
            }
            return count;
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Could not count bootstrap entries: " + e.getMessage());
            return -1;
        }
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        // Show the actual cause: previously the dialog only said "unable to
        // install" and the reason went to the log where nobody sees it.
        final String errorDetails = buildBootstrapErrorMessage(activity, message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(errorDetails)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    })
                    .setNeutralButton(R.string.bootstrap_error_copy_details, (dialog, which) -> {
                        dialog.dismiss();
                        copyErrorDetailsToClipboard(activity, errorDetails);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    /** Generic explanation plus the underlying cause, truncated to stay readable. */
    private static String buildBootstrapErrorMessage(Context context, String message) {
        String body = context.getString(R.string.bootstrap_error_body);
        if (message == null || message.trim().isEmpty()) return body;
        String details = message.trim();
        final int maxLength = 4000;
        if (details.length() > maxLength) {
            details = details.substring(0, maxLength) + "\n…";
        }
        return body + "\n\n" + details;
    }

    /** Lets the failure be pasted into a bug report without retyping it. */
    private static void copyErrorDetailsToClipboard(Context context, String details) {
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return;
            clipboard.setPrimaryClip(ClipData.newPlainText(TermuxConstants.TERMUX_APP_NAME + " error", details));
            Toast.makeText(context, R.string.bootstrap_error_details_copied, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Could not copy bootstrap error details: " + e.getMessage());
        }
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";

        // Add info of all install Termux plugin apps as well since their target sdk or installation
        // on external/portable sd card can affect Termux app files directory access or exec.
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                            "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                            true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    // Get primary storage root "/storage/emulated/0" symlink
                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    Os.symlink(documentsDir.getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    File podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS);
                    Os.symlink(podcastsDir.getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        File audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS);
                        Os.symlink(audiobooksDir.getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }

                    // Dir 0 should ideally be for primary storage
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/app/ContextImpl.java;l=818
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=219
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=181
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/StorageManagerService.java;l=3796
                    // https://cs.android.com/android/platform/superproject/+/android-7.0.0_r36:frameworks/base/services/core/java/com/android/server/MountService.java;l=3053

                    // Create "Android/data/com.termux" symlinks
                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    // Create "Android/media/com.termux" symlinks
                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                        "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                }
            }
        }.start();
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    public static byte[] loadZipBytes() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }

    public static native byte[] getZip();

}
