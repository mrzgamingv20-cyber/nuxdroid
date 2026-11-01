package com.termux.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.termux.R;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Repairs an already extracted NuxDroid prefix created from an old bootstrap
 * whose scripts still referenced the official Termux prefix.
 *
 * <p/>
 * This is the Java port of {@code scripts/repair-prefix.sh}, but it runs
 * automatically when the app starts so the user does not have to copy and run a
 * script inside the terminal. It only rewrites known text files below the
 * NuxDroid prefix and never touches files outside of it.
 *
 * <p/>
 * Repairs applied (same order as the shell script):
 * <ol>
 *     <li>{@code /data/data/com.termux} -> {@code /data/data/com.nuxdroid}</li>
 *     <li>Component class names stay in the Termux Java namespace:
 *     {@code com.nuxdroid/com.termux.app.*}</li>
 *     <li>Actions move to the NuxDroid package:
 *     {@code com.nuxdroid.app.reload_style}, {@code com.nuxdroid.service_wake_*}</li>
 *     <li>Quoted package name and user-agent strings move to {@code com.nuxdroid}</li>
 *     <li>{@code etc/apt/sources.list} is pointed at the NuxDroid repository</li>
 * </ol>
 */
public final class TermuxPrefixRepair {

    private static final String LOG_TAG = "TermuxPrefixRepair";

    /** The prefix of the broken bootstrap. */
    private static final String OLD_PREFIX = "/data/data/com.termux";

    /** The private data dir of this app, i.e. {@code /data/data/com.nuxdroid}. */
    private static final String NEW_PREFIX = TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH;

    /** Directories scanned for stale references (mirrors repair-prefix.sh). */
    private static final String[] SCAN_DIRS = {"bin", "etc", "libexec", "var/lib/dpkg/info"};

    /** NuxDroid package repository served from GitHub Pages. */
    private static final String NUXDROID_SOURCES =
        "deb [trusted=yes] https://mrzgamingv20-cyber.github.io/nuxdroid-packages aarch64 main\n";

    /** Official Termux repositories that must never be used with this prefix. */
    private static final String[] OFFICIAL_REPOS = {
        "packages.termux.dev",
        "packages-cf.termux.dev",
        "packages.termux.org",
    };

    /** Ordered literal replacements, mirroring the sed expressions. */
    private static final String[][] REPLACEMENTS = {
        {OLD_PREFIX, NEW_PREFIX},
        {"com.termux/com.termux.app.", "com.nuxdroid/com.termux.app."},
        // Component classes always live in the Termux Java namespace, only the
        // package prefix is com.nuxdroid; this form appears in prefixes built
        // before termux-tools was patched by CI.
        {"com.nuxdroid/com.nuxdroid.app.", "com.nuxdroid/com.termux.app."},
        {"com.termux.app.reload_style", "com.nuxdroid.app.reload_style"},
        {"com.termux.service_wake_lock", "com.nuxdroid.service_wake_lock"},
        {"com.termux.service_wake_unlock", "com.nuxdroid.service_wake_unlock"},
        {"\"com.termux\"", "\"com.nuxdroid\""},
        {"Termux (com.termux;", "Termux (com.nuxdroid;"},
        {"escaped_package_name=\"com.termux\"", "escaped_package_name=\"com.nuxdroid\""},
    };

    /** Largest file that is considered for repair. */
    private static final long MAX_FILE_SIZE = 8L * 1024 * 1024;

    /** Number of bytes inspected to decide whether a file is text. */
    private static final int TEXT_SAMPLE_SIZE = 4096;

    /** Maximum directory recursion depth, guards against unexpected layouts. */
    private static final int MAX_DEPTH = 8;

    private TermuxPrefixRepair() {
    }

    /** Outcome of a repair pass. */
    public static final class Result {
        /** Files inspected below the prefix. */
        public int scanned;
        /** Files rewritten (including {@code sources.list}). */
        public int changed;
        /** Directory holding the pre-repair copies, or {@code null} if nothing changed. */
        public String backupDir;
        /** {@code true} when no stale {@code /data/data/com.termux} reference is left. */
        public boolean verified;
        /** {@code true} when the prefix was absent, so there was nothing to repair. */
        public boolean skipped;
    }

    /**
     * Run {@link #repair()} on a background thread and always post
     * {@code whenDone} to the main thread afterwards, so a repair failure never
     * blocks starting a session.
     */
    public static void ensureRepairedAsync(final Context context, final Runnable whenDone) {
        final Handler main = new Handler(Looper.getMainLooper());
        final File prefixBin = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "bin");
        if (!prefixBin.isDirectory()) {
            // Fresh install: the bootstrap has not been extracted yet.
            main.post(whenDone);
            return;
        }
        new Thread(() -> {
            try {
                Result result = repair();
                if (result.changed > 0) {
                    Logger.logInfo(LOG_TAG, "Repaired prefix: " + result.changed + " file(s) rewritten, backups at "
                        + result.backupDir + ", verified=" + result.verified);
                    final String notice = context.getString(R.string.prefix_repaired_toast, result.changed,
                        result.backupDir == null ? "" : result.backupDir);
                    main.post(() -> Logger.showToast(context, notice, true));
                } else if (!result.verified) {
                    Logger.logError(LOG_TAG, "Prefix still references " + OLD_PREFIX + " after repair scan");
                }
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Prefix repair failed: " + e.getMessage());
                Logger.logStackTraceWithMessage(LOG_TAG, "Prefix repair failed", e);
            } finally {
                main.post(whenDone);
            }
        }, "nuxdroid-prefix-repair").start();
    }

    /**
     * Blocking repair pass, exposed so it can be re-used from tests or a
     * debug screen. Must not be called on the main thread.
     */
    public static Result repair() {
        Result result = new Result();
        File prefix = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        if (!new File(prefix, "bin").isDirectory()) {
            result.skipped = true;
            result.verified = true;
            return result;
        }

        for (String dir : SCAN_DIRS) {
            scan(new File(prefix, dir), prefix, result, 0);
        }

        result.verified = !hasStalePrefix(new File(prefix, "bin"), 0)
            && !hasStalePrefix(new File(prefix, "etc"), 0)
            && !hasStalePrefix(new File(prefix, "libexec"), 0)
            && !hasStalePrefix(new File(prefix, "var/lib/dpkg/info"), 0);
        return result;
    }

    /** Depth first walk over one of the scanned directories. */
    private static void scan(File file, File prefix, Result result, int depth) {
        if (file == null || depth > MAX_DEPTH) return;
        if (isSymlink(file)) return;

        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    scan(child, prefix, result, depth + 1);
                }
            }
            return;
        }
        if (!file.isFile()) return;

        result.scanned++;
        if (file.length() == 0 || file.length() > MAX_FILE_SIZE) return;

        byte[] data;
        try {
            data = readIfText(file);
        } catch (IOException e) {
            return;
        }
        if (data == null) return; // Empty, oversized or binary: never touched.

        String content;
        try {
            content = new String(data, "UTF-8");
        } catch (IOException e) {
            return;
        }

        if (isSourcesList(file, prefix)) {
            try {
                repairSourcesList(file, prefix, result);
            } catch (IOException e) {
                Logger.logError(LOG_TAG, "Failed to configure sources.list: " + e.getMessage());
            }
            return;
        }

        if (!needsRepair(content)) return;

        String repaired = apply(content);
        if (repaired.equals(content)) return;

        try {
            if (backup(file, prefix, result)) {
                write(file, repaired);
                result.changed++;
                Logger.logVerbose(LOG_TAG, "repaired " + relativize(prefix, file));
            }
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "Failed to repair " + file.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    /** {@code true} when the content matches any pattern the shell script repairs. */
    private static boolean needsRepair(String content) {
        if (content.indexOf(OLD_PREFIX) >= 0) return true;
        if (content.indexOf("\"com.termux\"") >= 0) return true;
        if (content.indexOf("com.termux/com.termux.app.") >= 0) return true;
        if (content.indexOf("com.nuxdroid/com.nuxdroid.app.") >= 0) return true;
        if (content.indexOf("com.termux.app.reload_style") >= 0) return true;
        if (content.indexOf("com.termux.service_wake_lock") >= 0) return true;
        if (content.indexOf("com.termux.service_wake_unlock") >= 0) return true;
        if (content.indexOf("Termux (com.termux;") >= 0) return true;
        return false;
    }

    /** Apply every replacement in the same order as the sed script. */
    private static String apply(String content) {
        String out = content;
        for (String[] pair : REPLACEMENTS) {
            out = out.replace(pair[0], pair[1]);
        }
        return out;
    }

    /** Point apt at the NuxDroid repository instead of the official Termux ones. */
    private static boolean isSourcesList(File file, File prefix) {
        return file.getAbsolutePath().equals(new File(prefix, "etc/apt/sources.list").getAbsolutePath());
    }

    private static void repairSourcesList(File file, File prefix, Result result) throws IOException {
        if (!file.isFile()) return;
        String content = readFileAsString(file);
        boolean hasOfficial = false;
        for (String repo : OFFICIAL_REPOS) {
            if (content.contains(repo)) {
                hasOfficial = true;
                break;
            }
        }
        if (!hasOfficial && content.contains("mrzgamingv20-cyber.github.io/nuxdroid-packages")) return;
        if (content.equals(NUXDROID_SOURCES)) return;
        if (backup(file, prefix, result)) {
            write(file, NUXDROID_SOURCES);
            result.changed++;
            Logger.logInfo(LOG_TAG, "Configured the NuxDroid package repository");
        }
    }

    /** {@code true} when any scanned text file still mentions the old prefix. */
    private static boolean hasStalePrefix(File file, int depth) {
        if (file == null || depth > MAX_DEPTH || isSymlink(file)) return false;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (hasStalePrefix(child, depth + 1)) return true;
                }
            }
            return false;
        }
        if (!file.isFile() || file.length() == 0 || file.length() > MAX_FILE_SIZE) return false;
        try {
            byte[] data = readIfText(file);
            if (data == null) return false;
            return new String(data, "UTF-8").indexOf(OLD_PREFIX) >= 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** Copy the original file into the backup directory, created on first change. */
    private static boolean backup(File file, File prefix, Result result) throws IOException {
        if (result.backupDir == null) {
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File dir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "nuxdroid-prefix-repair-" + stamp);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("Cannot create backup directory " + dir);
            }
            result.backupDir = dir.getAbsolutePath();
        }
        File target = new File(result.backupDir, relativize(prefix, file));
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create backup directory " + parent);
        }
        if (target.exists()) return true;
        write(target, readFileAsString(file));
        return true;
    }

    private static String relativize(File prefix, File file) {
        String prefixPath = prefix.getAbsolutePath() + File.separator;
        String filePath = file.getAbsolutePath();
        return filePath.startsWith(prefixPath) ? filePath.substring(prefixPath.length()) : file.getName();
    }

    private static boolean isSymlink(File file) {
        try {
            return !file.getCanonicalPath().equals(file.getAbsolutePath());
        } catch (IOException e) {
            return true; // Unresolvable path: treat as unsafe and skip.
        }
    }

    /**
     * Read a file only when it is text. The first {@link #TEXT_SAMPLE_SIZE}
     * bytes are inspected first, so the several hundred ELF files below
     * {@code bin/} and {@code libexec/} cost 4 KB each instead of their full
     * size - the repair runs on every cold start.
     *
     * @return the file contents, or {@code null} when the file is empty, larger
     * than {@link #MAX_FILE_SIZE} or binary (contains a NUL byte, same rule as
     * {@code grep -I}).
     */
    private static byte[] readIfText(File file) throws IOException {
        long length = file.length();
        if (length == 0 || length > MAX_FILE_SIZE) return null;

        int headerLength = (int) Math.min(length, TEXT_SAMPLE_SIZE);
        byte[] header = new byte[headerLength];
        try (InputStream in = new FileInputStream(file)) {
            readFully(in, header);
            for (byte b : header) {
                if (b == 0) return null;
            }
            if (length == headerLength) return header;

            ByteArrayOutputStream out = new ByteArrayOutputStream((int) length);
            out.write(header);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static void readFully(InputStream in, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int read = in.read(target, offset, target.length - offset);
            if (read < 0) throw new IOException("Unexpected end of file");
            offset += read;
        }
    }

    private static byte[] readAll(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(file.length(), MAX_FILE_SIZE));
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static String readFileAsString(File file) throws IOException {
        return new String(readAll(file), "UTF-8");
    }

    /** Overwrite in place so the original file mode and owner are preserved. */
    private static void write(File file, String content) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(content.getBytes("UTF-8"));
            out.flush();
        }
    }

}
